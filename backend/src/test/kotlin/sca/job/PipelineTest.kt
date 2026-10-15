package sca.job

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import sca.ScaProperties
import sca.acquire.Acquired
import sca.acquire.Acquirer
import sca.acquire.Target
import sca.enrich.Enricher
import sca.enrich.FakeApi
import sca.fixture
import sca.merge.MergeRules
import sca.model.Json
import sca.model.ScanResult
import sca.model.Status
import sca.model.TargetKind
import sca.scan.ContainerSpec
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val FIXTURES = mapOf("cdxgen" to "cdxgen.json", "syft" to "syft-dir.json", "trivy" to "trivy-dir.cdx.json")
private val EMPTY = mapOf(
    "cdxgen" to """{"bomFormat":"CycloneDX","specVersion":"1.6","components":[]}""",
    "trivy" to """{"bomFormat":"CycloneDX","specVersion":"1.6","components":[]}""",
    "syft" to """{"artifacts":[],"artifactRelationships":[]}""")

private fun outFile(spec: ContainerSpec) = spec.mounts.single { it.container == "/out" }.host.resolve("${spec.tool}.json")

fun fakeStep(ok: Set<String>, cdxgenLog: String = "") = ScanStep { spec, log ->
    Files.writeString(log, if (spec.tool == "cdxgen") cdxgenLog else "")
    if (spec.tool in ok) { Files.copy(fixture(FIXTURES.getValue(spec.tool)), outFile(spec)); true } else false
}

fun fakeStepWritingEmptyBoms() = ScanStep { spec, log -> Files.writeString(log, ""); Files.writeString(outFile(spec), EMPTY.getValue(spec.tool)); true }

/** Pretends to clone: hands back a directory with the commit "sha". */
class FakeAcquirer(private val src: Path) : Acquirer() {
    override fun acquire(t: Target, work: Path) = Acquired(TargetKind.REPO, src.also { Files.createDirectories(it) }, "sha")
}

class PipelineTest {
    @TempDir lateinit var tmp: Path
    private val api = FakeApi()
    @AfterEach fun stop() = api.close()
    private val jobDir get() = tmp.resolve("job")

    private fun pipeline(step: ScanStep) = Pipeline(
        ScaProperties(dataDir = tmp, images = mapOf("cdxgen" to "c", "syft" to "s", "trivy" to "t", "scancode" to "sc")),
        FakeAcquirer(tmp.resolve("job/work/src")), step, Enricher(api.base, api.base, tmp.resolve("cache"), java.time.Duration.ZERO), MergeRules.load())

    @Test fun `all tools succeed gives DONE and all files`() {
        val r = pipeline(fakeStep(ok = setOf("cdxgen", "syft", "trivy"))).run(jobDir, ScanInput("https://github.com/a/b"), {})
        assertEquals(Status.DONE, r.status)
        assertEquals("sha", r.target.id)
        listOf("result.json", "sbom.cdx.json", "sbom.spdx.json", "report.html").forEach { assertTrue(Files.exists(jobDir.resolve(it)), it) }
        assertEquals(r, Json.mapper.readValue<ScanResult>(jobDir.resolve("result.json").toFile()))
        assertTrue(r.components.single { it.name == "lodash" }.foundBy.size == 3)
    }

    @Test fun `one tool failing gives PARTIAL with warning`() {
        val r = pipeline(fakeStep(ok = setOf("cdxgen", "syft"))).run(jobDir, ScanInput("https://github.com/a/b"), {})
        assertEquals(Status.PARTIAL, r.status)
        assertTrue(r.warnings.any { it.code == "TOOL_FAILED" && "trivy" in it.message })
        assertFalse(r.tools.single { it.tool == "trivy" }.ok)
    }

    @Test fun `all tools failing gives FAILED`() {
        assertEquals(Status.FAILED, pipeline(fakeStep(ok = emptySet())).run(jobDir, ScanInput("https://github.com/a/b"), {}).status)
    }

    @Test fun `repo with nothing to scan gives DONE with NO_COMPONENTS`() {   // Review Focus 1
        val r = pipeline(fakeStepWritingEmptyBoms()).run(jobDir, ScanInput("https://github.com/a/b"), {})
        assertEquals(Status.DONE, r.status)
        assertTrue(r.warnings.any { it.code == "NO_COMPONENTS" })
        assertTrue(r.warnings.none { it.code == "SCHEMA_INVALID" })
    }

    @Test fun `cdxgen resolution failures become TRANSITIVE_INCOMPLETE warnings`() {
        val log = "ok line\nUnable to resolve dependencies for /src/pom.xml\n"
        val r = pipeline(fakeStep(setOf("cdxgen", "syft", "trivy"), cdxgenLog = log)).run(jobDir, ScanInput("https://github.com/a/b"), {})
        assertEquals(listOf("Unable to resolve dependencies for /src/pom.xml"), r.warnings.filter { it.code == "TRANSITIVE_INCOMPLETE" }.map { it.message })
    }

    @Test fun `progress reports stages in order and the checkout is removed`() {
        val stages = mutableListOf<String>()
        pipeline(fakeStep(setOf("cdxgen", "syft", "trivy"))).run(jobDir, ScanInput("https://github.com/a/b"), { stages += it })
        assertEquals(listOf("acquire", "scan", "normalize", "merge", "enrich", "emit"), stages)
        assertFalse(Files.exists(jobDir.resolve("work")))
        assertTrue(Files.exists(jobDir.resolve("raw/cdxgen.json")))
    }
}
