package sca.normalize

import com.github.packageurl.PackageURL
import sca.model.NormalizedResult
import sca.model.RawComponent
import sca.model.RawResult

val OS_TYPES = setOf("deb", "rpm", "apk", "alpm")

object Normalizer {
    /** Merge key: type/namespace/name@version without qualifiers. Falls back to name@version when the purl is missing or broken. */
    fun key(purl: String?, name: String, version: String?): String {
        val p = parse(purl) ?: return "$name@$version"
        val n = if (p.type == "pypi") p.name.lowercase().replace(Regex("[-_.]+"), "-") else p.name
        val v = p.version ?: version
        return listOfNotNull(p.type, p.namespace, n).joinToString("/") + (v?.let { "@$it" } ?: "")
    }

    fun normalize(results: List<RawResult>): List<NormalizedResult> {
        // Source → binary package mapping, learned from tools that report binary packages with an upstream (syft).
        val binaryAware = results.filter { r -> r.components.any { it.upstream != null } }
        val knownBinaries = binaryAware.flatMap { r -> r.components.map { key(it.purl, it.name, it.version) } }.toSet()
        val sourceToBinary = binaryAware.flatMap { it.components }.mapNotNull { c ->
            val p = parse(c.purl) ?: return@mapNotNull null
            if (p.type !in OS_TYPES || c.upstream == null || c.upstream == p.name) return@mapNotNull null
            val src = listOfNotNull(p.type, p.namespace, c.upstream).joinToString("/") + "@" + (p.version ?: c.version)
            src to key(c.purl, c.name, c.version)
        }.groupBy({ it.first }, { it.second }).mapValues { (_, bins) -> bins.min() }
            .filterKeys { it !in knownBinaries }   // a real binary with that name exists: don't fold it

        return results.map { r ->
            val refToKey = mutableMapOf<String, String>()
            val components = linkedMapOf<String, RawComponent>()
            for (c in r.components) {
                val p = parse(c.purl)
                if (p?.type == "generic" && p.version == null) continue   // bare file names (cdxgen image scans), not packages
                val k = key(c.purl, c.name, c.version).let { sourceToBinary[it] ?: it }
                refToKey[c.ref] = k
                components.merge(k, c) { old, new -> old.copy(locations = old.locations + new.locations) }
            }
            val edges = r.edges.mapNotNull { (a, b) ->
                val ka = refToKey[a] ?: return@mapNotNull null
                val kb = refToKey[b] ?: return@mapNotNull null
                (ka to kb).takeIf { ka != kb }
            }.toSet()
            NormalizedResult(r.tool, components, edges, r.roots?.mapNotNull { refToKey[it] }?.toSet(),
                project = r.project?.let { key(it, it, null) })
        }
    }

    private fun parse(purl: String?): PackageURL? = purl?.let { runCatching { PackageURL(it) }.getOrNull() }
}
