package sca.scan

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import sca.acquire.Acquired
import sca.model.TargetKind
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScannersTest {
    @TempDir lateinit var tmp: Path
    private val images = mapOf("cdxgen" to "cdx", "syft" to "syft", "trivy" to "trivy", "scancode" to "sc")

    @Test fun `docker args are locked down`() {
        val a = dockerArgs(ContainerSpec("syft", "img", listOf("x"), listOf(Mount(Path.of("/h"), "/src", true)), false, "/out/syft.json"), Limits())
        assertTrue(a.containsAll(listOf("--cap-drop", "ALL", "--network", "none", "--security-opt", "no-new-privileges", "-v", "/h:/src:ro", "--rm")))
        assertEquals(listOf("img", "x"), a.takeLast(2))
        assertTrue(a.windowed(2).contains(listOf("--tmpfs", "/tmp")))   // non-root tools need a writable /tmp (HOME)
        assertThrows<IllegalArgumentException> {
            dockerArgs(ContainerSpec("x", "i", emptyList(), listOf(Mount(Path.of("/var/run/docker.sock"), "/var/run/docker.sock", false)), false, ""), Limits())
        }
    }

    @Test fun `only cdxgen gets network, scancode only when deep repo`() {
        val s = Scanners.specs(Acquired(TargetKind.REPO, tmp, "sha"), deep = false, tmp.resolve("raw"), tmp.resolve("db"), images)
        assertEquals(setOf("cdxgen"), s.filter { it.network }.map { it.tool }.toSet())
        assertEquals(setOf("cdxgen", "syft", "trivy"), s.map { it.tool }.toSet())
        assertTrue(Scanners.specs(Acquired(TargetKind.REPO, tmp, "sha"), true, tmp.resolve("raw"), tmp.resolve("db"), images).any { it.tool == "scancode" })
        assertTrue(Scanners.specs(Acquired(TargetKind.IMAGE, tmp.resolve("i.tar"), "sha"), true, tmp.resolve("raw"), tmp.resolve("db"), images).none { it.tool == "scancode" })
    }

    @Test fun `syft does not phone home for updates`() {
        val syft = Scanners.specs(Acquired(TargetKind.REPO, tmp, "sha"), false, tmp.resolve("raw"), tmp.resolve("db"), images).single { it.tool == "syft" }
        assertTrue(dockerArgs(syft, Limits()).windowed(2).contains(listOf("-e", "SYFT_CHECK_FOR_APP_UPDATE=false")))
    }

    @Test fun `only cdxgen and the output dir are writable`() {
        val s = Scanners.specs(Acquired(TargetKind.REPO, tmp, "sha"), deep = true, tmp.resolve("raw"), tmp.resolve("db"), images)
        s.forEach { spec -> spec.mounts.filter { !it.readOnly }.forEach { m -> assertTrue(m.container == "/out" || (spec.tool == "cdxgen" && m.container == "/src"), "${spec.tool} ${m.container}") } }
    }
}
