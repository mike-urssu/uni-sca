package sca.merge

import com.github.packageurl.PackageURL
import org.cyclonedx.util.LicenseResolver
import sca.model.Component
import sca.model.Conflict
import sca.model.NormalizedResult
import sca.model.RawComponent
import sca.model.Sourced
import sca.normalize.OS_TYPES
import java.security.MessageDigest

/** SPDX AND of several expressions; OR-expressions get parentheses so AND's higher precedence can't regroup them. */
fun allOf(exprs: List<String>): String {
    val parts = exprs.distinct()
    return if (parts.size == 1) parts[0] else parts.joinToString(" AND ") { if (" OR " in it && !it.startsWith("(")) "($it)" else it }
}

data class Merged(val components: List<Component>, val edges: Set<Pair<String, String>>, val roots: Set<String>)

object Merger {
    fun merge(results: List<NormalizedResult>, rules: MergeRules): Merged {
        val projects = results.mapNotNull { it.project }.toSet()
        val keys = results.flatMap { it.components.keys }.filter { it !in projects }.distinct()
        val isOs = keys.associateWith { ecosystem(it) in OS_TYPES }

        val (edges, roots) = graph(results, rules, keys.toSet(), isOs)
        val components = keys.map { key ->
            val seen = results.mapNotNull { r -> r.components[key]?.let { r.tool to it } }.toMap()
            component(key, seen, isOs.getValue(key), rules, key in roots)
        }
        return Merged(components, edges, roots)
    }

    private fun component(key: String, seen: Map<String, RawComponent>, os: Boolean, rules: MergeRules, direct: Boolean): Component {
        val conflicts = mutableListOf<Conflict>()
        val licenseTexts = mutableMapOf<String, String>()

        /** First tool (in rule order) with a value wins; differing values from other tools become conflicts. */
        fun pick(field: String, same: (String) -> String = { it }, value: (RawComponent) -> String?): Sourced<String>? {
            val order = rules.order(field, os)
            val values = (order + seen.keys.filter { it !in order }).mapNotNull { t -> seen[t]?.let(value)?.let { t to it } }
            val (tool, chosen) = values.firstOrNull { it.first in order } ?: return null
            values.filter { (t, v) -> t != tool && same(v) != same(chosen) }.forEach { (t, v) -> conflicts += Conflict(field, v, t) }
            return Sourced(chosen, tool)
        }

        val identity = (rules.order("graph", os) + seen.keys).firstNotNullOf { seen[it] }
        // the key alone can't tell: a purl-less "github.com/a/b@v1" key contains '/' too
        val validPurl = seen.values.any { r -> r.purl?.let { runCatching { PackageURL(it) }.isSuccess } == true }
        val hashTool = rules.order("hashes", os).firstOrNull { seen[it]?.hashes?.isNotEmpty() == true }
        val licenses = pick("licenses") { c ->
            c.licenses.mapNotNull { spdx(it, licenseTexts) }.distinct().takeIf { it.isNotEmpty() }?.let(::allOf)
        }
        return Component(
            key = key, purl = identity.purl ?: seen.values.firstNotNullOfOrNull { it.purl },
            name = identity.name, version = identity.version, ecosystem = if (validPurl) ecosystem(key) else "unknown",
            qualifiers = seen.values.mapNotNull { it.purl }.flatMap { qualifiers(it).entries }.associate { it.toPair() },
            licenses = licenses,
            hashes = hashTool?.let { t -> seen.getValue(t).hashes.map { Sourced(it, t) } } ?: emptyList(),
            scope = pick("scope") { it.scope },
            downloadUrl = pick("downloadUrl", ::sameUrl) { it.downloadUrl },
            supplier = pick("supplier") { it.supplier },
            sourceRepo = pick("sourceRepo", ::sameUrl) { it.sourceRepo },
            locations = seen.values.flatMap { it.locations }.toSet(),
            foundBy = seen.keys, direct = direct, conflicts = conflicts,
            // single-tool findings stay visible through foundBy; they are not review items by themselves (see docs/analysis)
            needsReview = conflicts.isNotEmpty() || !validPurl,
            licenseTexts = licenseTexts.filterKeys { licenses?.value?.contains(it) == true },
        )
    }

    /** App edges from the first app-graph tool that has any, OS edges likewise; roots are the project's direct deps. */
    private fun graph(results: List<NormalizedResult>, rules: MergeRules, keys: Set<String>, isOs: Map<String, Boolean>):
        Pair<Set<Pair<String, String>>, Set<String>> {
        fun valid(e: Pair<String, String>) = e.first in keys && e.second in keys
        fun firstWith(order: List<String>, want: (Pair<String, String>) -> Boolean) =
            order.firstNotNullOfOrNull { t -> results.firstOrNull { it.tool == t }?.takeIf { r -> r.edges.any { valid(it) && want(it) } } }

        val appWant = { e: Pair<String, String> -> isOs[e.first] == false || isOs[e.second] == false }
        val osWant = { e: Pair<String, String> -> isOs[e.first] == true && isOs[e.second] == true }
        val appTool = firstWith(rules.order("graph", false), appWant)
        val osTool = firstWith(rules.order("graph", true), osWant)
        val edges = ((appTool?.edges?.filter(appWant) ?: emptyList()) + (osTool?.edges?.filter(osWant) ?: emptyList()))
            .filter(::valid).toSet()

        val hasIncoming = edges.map { it.second }.toSet()
        // roots from the graph tool, else the first tool (graph order) that knows any; an empty list means "doesn't know"
        val order = listOfNotNull(appTool) + rules.order("graph", false).mapNotNull { t -> results.firstOrNull { it.tool == t } } + results
        val toolRoots = order.firstNotNullOfOrNull { r -> r.roots?.filter { it in keys }?.toSet()?.takeIf { it.isNotEmpty() } }
        val roots = (toolRoots ?: keys.filter { isOs[it] == false && it !in hasIncoming }.toSet()) +
            keys.filter { isOs[it] == true && it !in hasIncoming }
        return edges to roots
    }

    private fun ecosystem(key: String) = if (key.contains('/')) key.substringBefore('/') else "unknown"

    private fun qualifiers(purl: String): Map<String, String> =
        runCatching { PackageURL(purl).qualifiers ?: emptyMap() }.getOrDefault(emptyMap())

    private fun sameUrl(u: String) = u.removePrefix("git+").removeSuffix("/").removeSuffix(".git")

    private val ID_OR_EXPR = Regex("""[A-Za-z0-9.+\-]+""")
    // GNU ids SPDX deprecated: GPL-2.0 is GPL-2.0-only, GPL-2.0+ is GPL-2.0-or-later (same for LGPL, AGPL, GFDL)
    private val DEPRECATED_GNU = Regex("""\b((?:A|L)?GPL|GFDL)-(\d\.\d)(\+?)(?![\w.\-])""")

    /** Current SPDX ids for deprecated GNU ones, and no redundant outer parentheses, so equal expressions compare equal. */
    internal fun canonical(expr: String): String {
        val s = DEPRECATED_GNU.replace(expr.trim()) { m -> "${m.groupValues[1]}-${m.groupValues[2]}-${if (m.groupValues[3] == "+") "or-later" else "only"}" }
        val inner = s.removeSurrounding("(", ")")
        return if (inner != s && '(' !in inner && ')' !in inner) inner.trim() else s
    }

    /** SPDX id/expression as-is, a known license name mapped to its id, anything else → LicenseRef-<sha256 prefix>. */
    internal fun spdx(raw: String, texts: MutableMap<String, String>): String? {
        val s = canonical(raw).takeIf { it.isNotEmpty() && it != "NOASSERTION" && it != "UNKNOWN" } ?: return null
        // "(MIT OR CC0-1.0)" leaves empty tokens at the edges once the parentheses are gone
        val tokens = s.replace("(", " ").replace(")", " ").split(Regex("\\s+")).filter { it.isNotEmpty() && it !in setOf("AND", "OR", "WITH") }
        if (tokens.all { it.startsWith("LicenseRef-") || (ID_OR_EXPR.matches(it) && isSpdxId(it.removeSuffix("+"))) }) return s
        LicenseResolver.resolve(s, false)?.let { choice ->
            choice.expression?.value?.let { return it }
            choice.licenses?.firstNotNullOfOrNull { it.id }?.let { return it }
        }
        val ref = "LicenseRef-" + MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
        texts[ref] = s
        return ref
    }

    private fun isSpdxId(id: String) = LicenseResolver::class.java.getResource("/licenses/$id.txt") != null
}
