package sca

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import sca.acquire.Acquired
import sca.acquire.Acquirer
import sca.acquire.Target
import sca.enrich.Enricher
import sca.job.Pipeline
import sca.job.ScanInput
import sca.job.ScanStep
import sca.job.TrivyDb
import sca.merge.MergeRules
import sca.model.Json
import sca.model.ScanResult
import sca.model.Status
import sca.model.TargetKind
import sca.scan.DockerRunner
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyToRecursively
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Real containers, real APIs. Needs docker and network: ./gradlew integrationTest */
@Tag("integration")
class IntegrationTest {
    @TempDir lateinit var tmp: Path

    private val props: ScaProperties = run {
        val yml = YAMLMapper().readTree(Path.of("src/main/resources/application.yml").toFile())
        val images = yml["sca"]["images"].properties().associate { (k, v) -> k to v.asString() }
        ScaProperties(dataDir = Path.of(System.getProperty("sca.dataDir", "data")), images = images)
    }

    private fun pipeline(acquirer: Acquirer) = Pipeline(props, acquirer,
        ScanStep { s, log -> DockerRunner().run(s, props.limits, props.toolTimeout, log) },
        Enricher(cacheDir = props.dataDir.resolve("cache")), MergeRules.load())

    private fun scan(acquirer: Acquirer, input: ScanInput): ScanResult =
        TrivyDb(props).use { pipeline(acquirer).run(tmp.resolve("job"), input) { println("[it] $it") } }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test fun `lab directory`() {
        val lab = Path.of("../lab").toAbsolutePath().normalize()
        val src = tmp.resolve("lab")
        // out/ holds earlier SBOM files that scanners would pick up as inputs; node_modules is not part of the source
        lab.copyToRecursively(src, followLinks = false) { s, d -> if (s.fileName.toString() in setOf("out", "node_modules")) kotlin.io.path.CopyActionResult.SKIP_SUBTREE else s.copyToIgnoringExistingDirectory(d, false) }
        val r = scan(object : Acquirer() {
            override fun acquire(t: Target, work: Path) = Acquired(TargetKind.REPO, src, "local")
        }, ScanInput("lab"))
        assertEquals(Status.DONE, r.status, r.warnings.toString())
        val lodash = r.components.single { it.name == "lodash" }
        assertEquals("https://registry.npmjs.org/lodash/-/lodash-4.17.15.tgz", lodash.downloadUrl?.value)
        assertEquals("MIT", lodash.licenses?.value)
        assertTrue(r.components.single { it.name == "axios" }.direct)
        assertTrue(r.warnings.none { it.code == "SCHEMA_INVALID" }, r.warnings.toString())
        golden("lab", r)
    }

    @Test fun `node 16 slim image`() {
        val r = scan(Acquirer(), ScanInput("node:16-slim"))
        assertTrue(r.status == Status.DONE || r.status == Status.PARTIAL, r.warnings.toString())
        assertTrue(r.components.none { it.ecosystem == "generic" && it.version == null })
        assertTrue(r.components.any { it.name == "libacl1" })
        assertTrue(r.components.none { it.name == "acl" && it.ecosystem == "deb" })
        val debs = r.components.filter { it.ecosystem == "deb" }
        assertTrue(debs.count { it.downloadUrl != null } >= debs.size * 0.9, "deb download urls ${debs.count { it.downloadUrl != null }}/${debs.size}")
        assertTrue(r.warnings.none { it.code == "SCHEMA_INVALID" }, r.warnings.toString())
        golden("node:16-slim", r)
    }

    /** Component counts per ecosystem must stay within ±5% of the recorded run. Record with -Dsca.recordGolden=true. */
    private fun golden(name: String, r: ScanResult) {
        val file = Path.of("src/test/resources/golden.json")
        val all: MutableMap<String, Map<String, Int>> = if (Files.exists(file)) Json.mapper.readValue(file.toFile()) else mutableMapOf()
        val counts = r.components.groupingBy { it.ecosystem }.eachCount().toSortedMap()
        println("[it] $name counts: $counts")
        if (System.getProperty("sca.recordGolden") == "true") {
            all[name] = counts
            Files.writeString(file, Json.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(all.toSortedMap()))
            return
        }
        val expected = all[name] ?: fail("no golden counts for $name; run once with -Dsca.recordGolden=true")
        assertEquals(expected.keys, counts.keys, "ecosystems")
        expected.forEach { (eco, n) -> assertTrue(Math.abs((counts[eco] ?: 0) - n) <= maxOf(1.0, n * 0.05), "$eco: expected ~$n, got ${counts[eco]}") }
    }
}
