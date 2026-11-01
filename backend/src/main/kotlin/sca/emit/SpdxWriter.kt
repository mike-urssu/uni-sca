package sca.emit

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import sca.model.Json
import sca.model.ScanResult
import sca.model.TargetKind
import sca.model.Warning
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** SPDX 2.3 JSON written straight from the merged model, checked against the official 2.3 schema. */
object SpdxWriter {
    private val schema by lazy {
        val text = SpdxWriter::class.java.getResource("/spdx-schema-2.3.json")!!.readText()
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7).getSchema(text, InputFormat.JSON)
    }

    fun write(r: ScanResult, out: Path): List<Warning> {
        val ids = mutableMapOf<String, String>()
        val used = mutableSetOf("SPDXRef-DOCUMENT", "SPDXRef-target")
        r.components.forEach { c ->
            val base = "SPDXRef-" + c.key.replace(Regex("[^A-Za-z0-9.-]"), "-")
            var id = base; var n = 1
            while (!used.add(id)) id = "$base-${n++}"
            ids[c.key] = id
        }
        val target = mapOf(
            "SPDXID" to "SPDXRef-target", "name" to r.target.input, "versionInfo" to r.target.id,
            "downloadLocation" to (if (r.target.kind == TargetKind.REPO) r.target.input else "NOASSERTION"),
            "filesAnalyzed" to false,
        ).filterValues { it != null }
        val packages = r.components.map { c ->
            mapOf(
                "SPDXID" to ids.getValue(c.key), "name" to c.name, "versionInfo" to c.version,
                "downloadLocation" to (c.downloadUrl?.value ?: "NOASSERTION"),
                "supplier" to (c.supplier?.value?.let { "Organization: $it" } ?: "NOASSERTION"),
                "licenseDeclared" to (c.licenses?.value ?: "NOASSERTION"), "licenseConcluded" to "NOASSERTION",
                "filesAnalyzed" to false,
                "checksums" to c.hashes.map { mapOf("algorithm" to it.value.alg.replace("-", ""), "checksumValue" to it.value.content) }.ifEmpty { null },
                "externalRefs" to c.purl?.let { listOf(mapOf("referenceCategory" to "PACKAGE-MANAGER", "referenceType" to "purl", "referenceLocator" to it)) },
            ).filterValues { it != null }
        }
        val relationships = listOf(rel("SPDXRef-DOCUMENT", "DESCRIBES", "SPDXRef-target")) +
            r.roots.mapNotNull { ids[it] }.map { rel("SPDXRef-target", "DEPENDS_ON", it) } +
            r.edges.mapNotNull { (a, b) -> ids[a]?.let { x -> ids[b]?.let { y -> rel(x, "DEPENDS_ON", y) } } }
        val extracted = r.components.flatMap { it.licenseTexts.entries }.associate { it.toPair() }
            .map { (id, text) -> mapOf("licenseId" to id, "extractedText" to text) }
        // a product version string is neither unique across products nor URI-safe
        val nsId = if (r.target.kind == TargetKind.PRODUCT) sha256("${r.target.input}@${r.target.id}") else r.target.id ?: sha256(r.target.input)
        val doc = mapOf(
            "spdxVersion" to "SPDX-2.3", "dataLicense" to "CC0-1.0", "SPDXID" to "SPDXRef-DOCUMENT",
            "name" to "SBOM for ${r.target.input}",
            "documentNamespace" to "https://uni-sca.local/spdx/$nsId/${r.createdAt.epochSecond}",
            "creationInfo" to mapOf("created" to r.createdAt.toString(), "creators" to listOf("Tool: $TOOL_NAME")),
            "packages" to listOf(target) + packages,
            "relationships" to relationships,
            "hasExtractedLicensingInfos" to extracted.ifEmpty { null },
        ).filterValues { it != null }
        val text = Json.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc)
        Files.writeString(out, text)
        return schema.validate(text, InputFormat.JSON).map { Warning("SCHEMA_INVALID", "SPDX: ${it.message}") }
    }

    private fun rel(a: String, type: String, b: String) = mapOf("spdxElementId" to a, "relationshipType" to type, "relatedSpdxElement" to b)
    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
