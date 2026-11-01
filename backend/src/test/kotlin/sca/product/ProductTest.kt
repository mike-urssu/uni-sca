package sca.product

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import sca.acquire.InvalidInput
import sca.model.Component
import sca.model.ScanResult
import sca.model.Sourced
import sca.model.Status
import sca.model.TargetInfo
import sca.model.TargetKind
import sca.model.Warning
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductTest {
    @TempDir lateinit var tmp: Path

    @Test fun `sources must match their kind and are stored canonical`() {
        assertEquals("https://github.com/a/b", source(SourceInput(TargetKind.REPO, "github.com/a/b", " v1 ")).target)
        assertEquals("v1", source(SourceInput(TargetKind.REPO, "github.com/a/b", " v1 ")).ref)
        assertEquals("nginx:1.25", source(SourceInput(TargetKind.IMAGE, "docker pull nginx:1.25")).target)
        assertThrows<InvalidInput> { source(SourceInput(TargetKind.REPO, "nginx:1.25")) }
        assertThrows<InvalidInput> { source(SourceInput(TargetKind.IMAGE, "https://github.com/a/b")) }
        assertThrows<InvalidInput> { source(SourceInput(TargetKind.IMAGE, "nginx:1.25", "v1")) }
        val tar = kotlin.io.path.createTempFile(tmp, suffix = ".tar")
        assertThrows<InvalidInput> { source(SourceInput(TargetKind.IMAGE, tar.toString())) }
    }

    @Test fun `registering an existing name adds a version, the same name and version is a duplicate`() {
        val store = ProductStore(tmp)
        val p = store.create(" Foo ", "1.0", listOf(SourceInput(TargetKind.IMAGE, "nginx:1.25")))
        assertEquals("Foo", store.get(p.id)!!.name)
        assertThrows<InvalidInput> { store.create("foo", "1.0", emptyList()) }   // same name, same version
        val q = store.create("foo", "2.0", emptyList())   // same name, other version: a version of the same product
        assertEquals(p.id, q.id)
        assertEquals(listOf("1.0", "2.0"), q.versions.map { it.version })
        store.create("Bar", "1.0", emptyList())   // other name, same version
        store.create("Baz", "3.0", emptyList())   // other name, other version
        assertEquals(listOf("Bar", "Baz", "Foo"), store.list().map { it.name })
        assertThrows<InvalidInput> { store.update(p.id) { it.copy(name = "bar") } }   // names stay unique
        val m = store.merge(store.list().first { it.name == "Baz" }.id, p.id)
        assertEquals(listOf("1.0", "2.0", "3.0"), m.versions.map { it.version })
        assertEquals(listOf("Bar", "Foo"), store.list().map { it.name })
        assertNull(store.get("../../x"))
    }

    private fun c(key: String, version: String?, license: String? = null, tool: String = "syft", review: Boolean = false) =
        Component(key, null, key.substringAfterLast('/').substringBefore('@'), version, key.substringBefore('/'),
            licenses = license?.let { Sourced(it, tool) }, foundBy = setOf(tool), needsReview = review)

    private fun r(vararg cs: Component, status: Status = Status.DONE, edges: Set<Pair<String, String>> = emptySet(), roots: Set<String> = emptySet()) =
        ScanResult(TargetInfo(TargetKind.REPO, "x", null, "sha"), status, emptyList(), cs.toList(), edges, roots, listOf(Warning("W", "w")), Instant.now())

    private fun src(t: String) = Source(newId(), TargetKind.REPO, t)

    @Test fun `combine merges shared components, first source wins and a license difference needs review`() {
        val a = r(c("npm/x@1", "1", "MIT", "syft"), c("npm/y@1", "1"), edges = setOf("npm/x@1" to "npm/y@1"), roots = setOf("npm/x@1"))
        val b = r(c("npm/x@1", "1", "Apache-2.0", "trivy"), c("npm/z@1", "1"), status = Status.PARTIAL, roots = setOf("npm/z@1"))
        val out = combine("Foo", "1.0", listOf(src("A") to a, src("B") to b))
        assertEquals(TargetInfo(TargetKind.PRODUCT, "Foo", null, "1.0"), out.target)
        assertEquals(listOf("npm/x@1", "npm/y@1", "npm/z@1"), out.components.map { it.key })
        val x = out.components.first()
        assertEquals("MIT", x.licenses!!.value)
        assertEquals(setOf("syft", "trivy"), x.foundBy)
        assertTrue(x.needsReview && x.conflicts.single().value == "Apache-2.0")
        assertEquals(setOf("npm/x@1", "npm/z@1"), out.roots)
        assertEquals(Status.PARTIAL, out.status)
        assertEquals(listOf("A: w", "B: w"), out.warnings.map { it.message })
    }

    @Test fun `a source without a successful scan is a warning, not an error`() {
        val out = combine("Foo", "1.0", listOf(src("A") to r(c("npm/x@1", "1")), src("B") to null))
        assertEquals(Status.PARTIAL, out.status)
        assertTrue(out.warnings.any { it.code == "SOURCE_NOT_SCANNED" && "B" in it.message })
        assertEquals(Status.FAILED, combine("Foo", "1.0", listOf(src("B") to null)).status)
    }

    @Test fun `identity drops only the version`() {
        assertEquals("npm/@s/x", identity("npm/@s/x@1.0"))
        assertEquals("npm/@s/x", identity("npm/@s/x"))
        assertEquals("deb/debian/libc", identity("deb/debian/libc@1:2.3"))
        assertEquals("foo", identity("foo@1"))
    }

    @Test fun `compare classifies added, removed, version and license changes`() {
        val a = r(c("npm/keep@1", "1", "MIT"), c("npm/gone@1", "1"), c("npm/up@1", "1"), c("npm/lic@1", "1", "MIT"), c("npm/multi@1", "1"), c("npm/multi@2", "2"))
        val b = r(c("npm/keep@1", "1", "MIT"), c("npm/new@1", "1"), c("npm/up@2", "2"), c("npm/lic@1", "1", "GPL-3.0-only"), c("npm/multi@2", "2"))
        val d = compare(a, b).associateBy { it.id }
        assertEquals(setOf("npm/new", "npm/gone", "npm/up", "npm/lic", "npm/multi"), d.keys)
        assertEquals(Change.ADDED, d.getValue("npm/new").change)
        assertEquals(Change.REMOVED, d.getValue("npm/gone").change)
        assertEquals(listOf("1") to listOf("2"), d.getValue("npm/up").let { it.before to it.after })
        assertEquals(listOf("1", "2") to listOf("2"), d.getValue("npm/multi").let { it.before to it.after })
        assertEquals("MIT" to "GPL-3.0-only", d.getValue("npm/lic").let { it.licenseBefore to it.licenseAfter })
        assertEquals(Change.LICENSE_CHANGED, d.getValue("npm/lic").change)
    }
}
