package sca.emit

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import sca.merge.mergedDir
import sca.model.Json
import sca.model.ScanResult
import sca.model.Status
import sca.model.TargetInfo
import sca.model.TargetKind
import sca.model.ToolRun
import sca.model.Warning
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

fun sampleResult(): ScanResult = mergedDir().let { m ->
    ScanResult(TargetInfo(TargetKind.REPO, "https://github.com/x/sca-lab", null, "abc123"), Status.DONE,
        listOf(ToolRun("cdxgen", "ghcr.io/cyclonedx/cdxgen:v11", true, 1000, "raw/cdxgen.log")),
        m.components, m.edges, m.roots, emptyList(), Instant.parse("2026-10-15T00:00:00Z"))
}

class EmitTest {
    @TempDir lateinit var tmp: Path
    private val result = sampleResult()

    @Test fun `cyclonedx output is schema valid and keeps provenance`() {
        assertEquals(emptyList<Warning>(), CycloneDxWriter.write(result, tmp.resolve("sbom.cdx.json")))
        val bom = Json.mapper.readTree(tmp.resolve("sbom.cdx.json").toFile())
        assertEquals("1.6", bom["specVersion"].asString())
        val l = bom["components"].single { it["name"].asString() == "lodash" }
        assertEquals("https://registry.npmjs.org/lodash/-/lodash-4.17.15.tgz",
            l["externalReferences"].single { it["type"].asString() == "distribution" }["url"].asString())
        assertTrue(l["properties"].any { it["name"].asString() == "sca:source:licenses" && it["value"].asString() == "cdxgen" })
        val root = bom["metadata"]["component"]["bom-ref"].asString()
        assertTrue(bom["dependencies"].single { it["ref"].asString() == root }["dependsOn"].any { it.asString() == "npm/axios@0.21.0" })
    }

    @Test fun `spdx output is schema valid with depends_on relationships`() {
        assertEquals(emptyList<Warning>(), SpdxWriter.write(result, tmp.resolve("sbom.spdx.json")))
        val doc = Json.mapper.readTree(tmp.resolve("sbom.spdx.json").toFile())
        assertEquals("SPDX-2.3", doc["spdxVersion"].asString())
        assertTrue(doc["relationships"].any { it["relationshipType"].asString() == "DEPENDS_ON" })
        val l = doc["packages"].single { it["name"].asString() == "lodash" }
        assertEquals("https://registry.npmjs.org/lodash/-/lodash-4.17.15.tgz", l["downloadLocation"].asString())
        assertEquals("MIT", l["licenseDeclared"].asString())
    }

    @Test fun `spdx ids stay unique when keys collapse to the same id`() {
        val c = result.components.first()
        val twins = result.copy(components = listOf(c.copy(key = "npm/a_b@1"), c.copy(key = "npm/a+b@1")), edges = emptySet(), roots = emptySet())
        assertEquals(emptyList<Warning>(), SpdxWriter.write(twins, tmp.resolve("t.spdx.json")))
        val ids = Json.mapper.readTree(tmp.resolve("t.spdx.json").toFile())["packages"].toList().map { it["SPDXID"].asString() }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun `schema validators actually reject bad documents`() {
        val c = result.components.first()
        val bad = result.copy(components = listOf(c.copy(hashes = listOf(sca.model.Sourced(sca.model.Hash("BOGUS", "zz"), "syft")))), edges = emptySet(), roots = emptySet())
        assertTrue(CycloneDxWriter.write(bad, tmp.resolve("b.cdx.json")).any { it.code == "SCHEMA_INVALID" })
        assertTrue(SpdxWriter.write(bad, tmp.resolve("b.spdx.json")).any { it.code == "SCHEMA_INVALID" })
    }

    @Test fun `ntia reports missing supplier once`() {
        val w = NtiaValidator.check(result.copy(components = result.components.map { it.copy(supplier = null) }))
        assertEquals(1, w.count { it.code == "NTIA_MISSING" && it.message.startsWith("supplier") })
    }

    @Test fun `report counts single-tool findings separately`() {
        ReportWriter.write(result, tmp.resolve("s.html"))
        assertTrue(Files.readString(tmp.resolve("s.html")).contains("${result.components.count { it.foundBy.size == 1 }} found by one tool only"))
    }

    @Test fun `report never links non-http urls`() {   // downloadUrl comes from lockfiles the scanned repo controls
        val evil = result.components[0].copy(downloadUrl = sca.model.Sourced("javascript:fetch('/api/scans')", "syft"))
        ReportWriter.write(result.copy(components = listOf(evil)), tmp.resolve("x.html"))
        assertFalse(Files.readString(tmp.resolve("x.html")).contains("href=\"javascript:"))
    }

    @Test fun `report escapes html`() {
        ReportWriter.write(result.copy(components = listOf(result.components[0].copy(name = "<script>x"))), tmp.resolve("r.html"))
        assertFalse(Files.readString(tmp.resolve("r.html")).contains("<script>x"))
    }

    @Test fun `empty result still yields valid sboms`() {   // Review Focus 1 (output side)
        val empty = result.copy(components = emptyList(), edges = emptySet(), roots = emptySet())
        assertEquals(emptyList<Warning>(), CycloneDxWriter.write(empty, tmp.resolve("e.cdx.json")) + SpdxWriter.write(empty, tmp.resolve("e.spdx.json")))
    }
}
