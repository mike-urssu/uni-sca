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
        val hashTool = rules.order("hashes", os).firstOrNull { seen[it]?.hashes?.isNotEmpty() == true }
        val licenses = pick("licenses") { c ->
            c.licenses.mapNotNull { spdx(it, licenseTexts) }.distinct().takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        }
        return Component(
            key = key, purl = identity.purl ?: seen.values.firstNotNullOfOrNull { it.purl },
            name = identity.name, version = identity.version, ecosystem = ecosystem(key),
            qualifiers = seen.values.mapNotNull { it.purl }.flatMap { qualifiers(it).entries }.associate { it.toPair() },
            licenses = licenses,
            hashes = hashTool?.let { t -> seen.getValue(t).hashes.map { Sourced(it, t) } } ?: emptyList(),
            scope = pick("scope") { it.scope },
            downloadUrl = pick("downloadUrl", ::sameUrl) { it.downloadUrl },
            supplier = pick("supplier") { it.supplier },
            sourceRepo = pick("sourceRepo", ::sameUrl) { it.sourceRepo },
            locations = seen.values.flatMap { it.locations }.toSet(),
            foundBy = seen.keys, direct = direct, conflicts = conflicts,
            needsReview = conflicts.isNotEmpty() || seen.size == 1 || !key.contains('/'),
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
        val toolRoots = (appTool ?: results.firstOrNull { it.roots != null })?.roots?.filter { it in keys }?.toSet()
        val roots = (toolRoots ?: keys.filter { isOs[it] == false && it !in hasIncoming }.toSet()) +
            keys.filter { isOs[it] == true && it !in hasIncoming }
        return edges to roots
    }

    private fun ecosystem(key: String) = if (key.contains('/')) key.substringBefore('/') else "unknown"

    private fun qualifiers(purl: String): Map<String, String> =
        runCatching { PackageURL(purl).qualifiers ?: emptyMap() }.getOrDefault(emptyMap())

    private fun sameUrl(u: String) = u.removePrefix("git+").removeSuffix("/").removeSuffix(".git")

    private val ID_OR_EXPR = Regex("""[A-Za-z0-9.+\-]+""")

    /** SPDX id/expression as-is, a known license name mapped to its id, anything else → LicenseRef-<sha256 prefix>. */
    internal fun spdx(raw: String, texts: MutableMap<String, String>): String? {
        val s = raw.trim().takeIf { it.isNotEmpty() && it != "NOASSERTION" && it != "UNKNOWN" } ?: return null
        val tokens = s.replace("(", " ").replace(")", " ").split(Regex("\\s+")).filter { it !in setOf("AND", "OR", "WITH") }
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
