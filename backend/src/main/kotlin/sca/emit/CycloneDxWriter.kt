package sca.emit

import org.cyclonedx.Version
import org.cyclonedx.generators.BomGeneratorFactory
import org.cyclonedx.model.Bom
import org.cyclonedx.model.Dependency
import org.cyclonedx.model.ExternalReference
import org.cyclonedx.model.Hash
import org.cyclonedx.model.LicenseChoice
import org.cyclonedx.model.Metadata
import org.cyclonedx.model.OrganizationalContact
import org.cyclonedx.model.OrganizationalEntity
import org.cyclonedx.model.Property
import org.cyclonedx.model.license.Expression
import org.cyclonedx.model.metadata.ToolInformation
import org.cyclonedx.parsers.JsonParser
import sca.model.ScanResult
import sca.model.TargetKind
import sca.model.Warning
import java.nio.file.Files
import java.nio.file.Path
import java.util.Date
import java.util.UUID
import org.cyclonedx.model.Component as CdxComponent

const val TOOL_NAME = "uni-sca"
internal const val TARGET_REF = "sca-target"

object CycloneDxWriter {
    fun write(r: ScanResult, out: Path): List<Warning> {
        val bom = Bom().apply {
            serialNumber = "urn:uuid:${UUID.randomUUID()}"
            metadata = Metadata().apply {
                timestamp = Date.from(r.createdAt)
                authors = listOf(OrganizationalContact().apply { name = TOOL_NAME })
                toolChoice = ToolInformation().apply {
                    components = listOf(app(TOOL_NAME, null)) + r.tools.map { app(it.tool, it.image) }
                }
                component = CdxComponent().apply {
                    type = if (r.target.kind == TargetKind.IMAGE) CdxComponent.Type.CONTAINER else CdxComponent.Type.APPLICATION
                    bomRef = TARGET_REF; name = r.target.input; version = r.target.id
                }
                properties = listOf(prop("sca:status", r.status.name)) + r.warnings.map { prop("sca:warning", "${it.code}: ${it.message}") }
            }
            components = r.components.map { c ->
                CdxComponent().apply {
                    type = CdxComponent.Type.LIBRARY
                    bomRef = c.key; name = c.name; version = c.version; purl = c.purl
                    c.licenses?.let { l -> licenses = LicenseChoice().apply { expression = Expression(l.value) } }
                    hashes = c.hashes.map { Hash(it.value.alg, it.value.content) }.ifEmpty { null }
                    c.scope?.value?.let { s -> scope = runCatching { CdxComponent.Scope.valueOf(s.uppercase()) }.getOrNull() }
                    c.supplier?.let { s -> supplier = OrganizationalEntity().apply { name = s.value } }
                    externalReferences = listOfNotNull(
                        c.downloadUrl?.let { ref(ExternalReference.Type.DISTRIBUTION, it.value) },
                        c.sourceRepo?.let { ref(ExternalReference.Type.VCS, it.value) },
                    ).ifEmpty { null }
                    properties = buildList {
                        add(prop("sca:foundBy", c.foundBy.sorted().joinToString(",")))
                        mapOf("licenses" to c.licenses, "scope" to c.scope, "downloadUrl" to c.downloadUrl, "registry" to c.registry,
                            "supplier" to c.supplier, "sourceRepo" to c.sourceRepo)
                            .forEach { (f, v) -> v?.let { add(prop("sca:source:$f", it.source)) } }
                        c.hashes.firstOrNull()?.let { add(prop("sca:source:hashes", it.source)) }
                        c.registry?.let { add(prop("sca:registry", it.value)) }
                        add(prop("sca:direct", c.direct.toString()))
                        if (c.needsReview) add(prop("sca:needsReview", "true"))
                        if (c.unresolved.isNotEmpty()) add(prop("sca:unresolved", c.unresolved.sorted().joinToString(",")))
                        c.conflicts.forEach { add(prop("sca:conflict", "${it.field}=${it.value} (${it.source})")) }
                    }
                }
            }
            val children = r.edges.groupBy({ it.first }, { it.second })
            dependencies = listOf(dep(TARGET_REF, r.roots.sorted())) + r.components.map { dep(it.key, children[it.key]?.sorted() ?: emptyList()) }
        }
        Files.writeString(out, BomGeneratorFactory.createJson(Version.VERSION_16, bom).toJsonString())
        return JsonParser().validate(out.toFile(), Version.VERSION_16).map { Warning("SCHEMA_INVALID", "CycloneDX: ${it.message}") }
    }

    private fun app(n: String, v: String?) = CdxComponent().apply { type = CdxComponent.Type.APPLICATION; name = n; version = v }
    private fun prop(n: String, v: String) = Property().apply { name = n; value = v }
    private fun ref(t: ExternalReference.Type, u: String) = ExternalReference().apply { type = t; url = u }
    private fun dep(ref: String, on: List<String>) = Dependency(ref).apply { dependencies = on.map { Dependency(it) } }
}
