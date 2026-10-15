package sca.normalize

import com.github.packageurl.PackageURL
import sca.model.Hash
import sca.model.Json
import sca.model.RawComponent
import sca.model.RawResult
import tools.jackson.databind.JsonNode
import java.nio.file.Path
import java.util.Base64

/** Turns each tool's native output into a RawResult. Reads JSON trees; never models the full formats. */
object Readers {
    fun read(tool: String, file: Path): RawResult {
        val root = Json.mapper.readTree(file.toFile())
        return when (tool) {
            "cdxgen", "trivy" -> cycloneDx(tool, root)
            "syft" -> syft(root)
            "scancode" -> scancode(root)
            else -> throw IllegalArgumentException("unknown tool: $tool")
        }
    }

    private val CONTAINER_TYPES = setOf("application", "operating-system", "container", "platform")

    private fun cycloneDx(tool: String, root: JsonNode): RawResult {
        val all = mutableListOf<JsonNode>()
        fun collect(arr: JsonNode?) { arr?.forEach { all.add(it); collect(it["components"]) } }
        collect(root["components"])

        // purl-less application/OS nodes (trivy's lockfile/OS nodes) only group packages; see through them
        val containers = all.filter { it.str("purl") == null && it.str("type") in CONTAINER_TYPES }.mapNotNull { it.str("bom-ref") }.toSet()
        val components = all.filter { it.str("bom-ref") !in containers }.map { c ->
            val props = c["properties"]?.associate { it.str("name") to it.str("value") } ?: emptyMap()
            val refs = c["externalReferences"]?.toList() ?: emptyList()
            RawComponent(
                tool = tool, ref = c.str("bom-ref") ?: c.str("purl") ?: "${c.str("name")}@${c.str("version")}",
                purl = c.str("purl"), name = c.str("name") ?: "", version = c.str("version"),
                licenses = c["licenses"]?.mapNotNull { it.str("expression") ?: it["license"]?.let { l -> l.str("id") ?: l.str("name") } } ?: emptyList(),
                hashes = c["hashes"]?.mapNotNull { h -> h.str("content")?.let { Hash(h.str("alg") ?: "", it) } } ?: emptyList(),
                downloadUrl = props["ResolvedUrl"] ?: refs.firstOrNull { it.str("type") == "distribution" }?.str("url"),
                supplier = c["supplier"]?.str("name") ?: c.str("publisher") ?: c.str("author"),
                sourceRepo = refs.firstOrNull { it.str("type") == "vcs" }?.str("url"),
                scope = c.str("scope"),
                locations = (listOfNotNull(props["SrcFile"]) +
                    (c["evidence"]?.get("occurrences")?.mapNotNull { it.str("location") } ?: emptyList())).toSet(),
            )
        }

        val deps = root["dependencies"]?.associate { d -> d.str("ref") to (d["dependsOn"]?.mapNotNull { it.asString() } ?: emptyList()) } ?: emptyMap()
        val edges = deps.flatMap { (from, tos) -> tos.map { from!! to it } }.toSet()
        val meta = root["metadata"]?.get("component")
        val roots = meta?.str("bom-ref")?.let { deps[it] }?.let { expand(it, deps, containers) }
        return RawResult(tool, components, edges, roots, project = meta?.str("purl"))
    }

    private fun expand(refs: List<String>, deps: Map<String?, List<String>>, containers: Set<String>): Set<String> {
        val out = mutableSetOf<String>(); val seen = mutableSetOf<String>()
        val queue = ArrayDeque(refs)
        while (queue.isNotEmpty()) {
            val r = queue.removeFirst()
            if (!seen.add(r)) continue
            if (r in containers) queue += deps[r] ?: emptyList() else out += r
        }
        return out
    }

    private fun syft(root: JsonNode): RawResult {
        val components: List<RawComponent> = root["artifacts"]?.toList()?.map { a ->
            val purl = a.str("purl")
            val meta = a["metadata"]
            val upstream = purl?.let { runCatching { PackageURL(it).qualifiers?.get("upstream") }.getOrNull() }
                ?.substringBefore(' ')?.substringBefore('@')
            RawComponent(
                tool = "syft", ref = a.str("id")!!, purl = purl, name = a.str("name") ?: "", version = a.str("version"),
                licenses = a["licenses"]?.mapNotNull { if (it.isString) it.asString() else it.str("value") }
                    ?.filterNot { it.startsWith("sha256:") } ?: emptyList(),
                hashes = listOfNotNull(meta?.str("integrity")?.let(::sriToHash)),
                downloadUrl = meta?.str("resolved"),
                supplier = meta?.str("maintainer") ?: meta?.str("author"),
                locations = a["locations"]?.mapNotNull { it.str("path") }?.toSet() ?: emptySet(),
                upstream = upstream ?: meta?.str("source"),
            )
        } ?: emptyList<RawComponent>()
        // syft: "parent is a dependency of child", so the edge is child → parent
        val edges = root["artifactRelationships"]?.filter { it.str("type") == "dependency-of" }
            ?.map { it.str("child")!! to it.str("parent")!! }?.toSet() ?: emptySet()
        return RawResult("syft", components, edges, roots = null)
    }

    private fun scancode(root: JsonNode): RawResult {
        val components: List<RawComponent> = root["packages"]?.toList()?.map { p ->
            RawComponent(
                tool = "scancode", ref = p.str("package_uid") ?: p.str("purl")!!, purl = p.str("purl"),
                name = p.str("name") ?: "", version = p.str("version"),
                licenses = listOfNotNull(p.str("declared_license_expression_spdx")),
                downloadUrl = p.str("download_url"), sourceRepo = p.str("vcs_url"),
                locations = p["datafile_paths"]?.mapNotNull { it.asString() }?.toSet() ?: emptySet(),
            )
        } ?: emptyList<RawComponent>()
        return RawResult("scancode", components, emptySet(), roots = null)
    }

    /** "sha512-<base64>" (npm SRI) → Hash("SHA-512", hex). Takes the first sha512 entry. */
    private fun sriToHash(sri: String): Hash? {
        val b64 = sri.split(' ').firstOrNull { it.startsWith("sha512-") }?.removePrefix("sha512-") ?: return null
        return Hash("SHA-512", Base64.getDecoder().decode(b64).joinToString("") { "%02x".format(it) })
    }

    private fun JsonNode.str(field: String): String? = get(field)?.takeIf { !it.isNull && !it.isMissingNode }?.asString()
}
