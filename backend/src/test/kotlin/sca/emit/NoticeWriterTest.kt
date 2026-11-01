package sca.emit

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import sca.model.Component
import sca.model.ScanResult
import sca.model.Sourced
import sca.model.Status
import sca.model.TargetInfo
import sca.model.TargetKind
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoticeWriterTest {
    @TempDir lateinit var tmp: Path

    private fun c(name: String, license: String?, scope: String? = null, eco: String = "npm") =
        Component("$eco/$name@1", null, name, "1", eco, licenses = license?.let { Sourced(it, "syft") },
            scope = scope?.let { Sourced(it, "cdxgen") }, foundBy = setOf("syft"))

    @Test fun `lists shipped components and each license text once`() {
        val r = ScanResult(TargetInfo(TargetKind.PRODUCT, "Foo", null, "1.0"), Status.DONE, emptyList(),
            listOf(c("a", "MIT", "required"), c("b", "MIT AND Apache-2.0", "optional"), c("lint", "ISC", scope = "optional"), c("lintdep", "0BSD", "optional"),
                c("checkout", "MIT", eco = "github"), c("x", null)),
            // b is marked optional but the runtime root a pulls it in; lintdep is reachable only from the dev root lint
            setOf("npm/a@1" to "npm/b@1", "npm/lint@1" to "npm/lintdep@1", "npm/lint@1" to "npm/b@1"), setOf("npm/a@1", "npm/lint@1"), emptyList(), Instant.now())
        val out = tmp.resolve("n.html")
        NoticeWriter.write(r, out)
        val s = Files.readString(out)
        assertTrue(s.contains("<h1>Foo 1.0</h1>"))
        assertTrue(s.contains("<b>a</b>") && s.contains("""<a href="#lic-MIT">MIT</a> <span class=op>AND</span> <a href="#lic-Apache-2.0">Apache-2.0</a>"""))
        assertFalse(s.contains("lint") || s.contains("checkout") || s.contains("ISC") || s.contains("0BSD"))   // dev-only and CI are not shipped
        assertTrue(s.contains("Not identified") && s.contains("1 component(s) have no identified license"))
        assertEquals(1, Regex("Permission is hereby granted").findAll(s).count())   // MIT text once, though two components use it
        assertTrue(s.contains("""<article id="lic-Apache-2.0">""") && s.contains("Apache License"))
    }

    @Test fun `scan-derived text is escaped`() {
        val evil = c("<script>x</script>", "MIT").copy(copyright = Sourced("<img src=x onerror=alert(1)>", "manual"))
        val r = ScanResult(TargetInfo(TargetKind.PRODUCT, "<b>P</b>", null, "1"), Status.DONE, emptyList(), listOf(evil), emptySet(), emptySet(), emptyList(), Instant.now())
        val out = tmp.resolve("e.html")
        NoticeWriter.write(r, out)
        val s = Files.readString(out)
        assertFalse(s.contains("<script>x") || s.contains("<img") || s.contains("<b>P</b>"))
        assertTrue(s.contains("&lt;script&gt;x&lt;/script&gt;") && s.contains("&lt;img src=x"))
    }

    @Test fun `ids splits an expression`() {
        assertEquals(listOf("MIT", "Apache-2.0", "GPL-2.0-only", "Classpath-exception-2.0"),
            NoticeWriter.ids("(MIT OR Apache-2.0) AND GPL-2.0-only WITH Classpath-exception-2.0"))
    }
}
