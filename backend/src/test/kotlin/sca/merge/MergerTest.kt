package sca.merge

import org.junit.jupiter.api.Test
import sca.fixture
import sca.model.Conflict
import sca.model.RawComponent
import sca.model.RawResult
import sca.model.Sourced
import sca.normalize.Normalizer
import sca.normalize.Readers
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

fun mergedDir(): Merged = Merger.merge(
    Normalizer.normalize(listOf("cdxgen" to "cdxgen.json", "syft" to "syft-dir.json", "trivy" to "trivy-dir.cdx.json")
        .map { (t, f) -> Readers.read(t, fixture(f)) }),
    MergeRules.load())

private fun raw(tool: String, purl: String, licenses: List<String> = emptyList()) =
    RawResult(tool, listOf(RawComponent(tool, purl, purl, purl.substringAfterLast('/').substringBefore('@'), purl.substringAfter('@'), licenses = licenses)), emptySet(), null)

private fun rawResult(tool: String, purls: List<String>, edges: Set<Pair<String, String>>, roots: Set<String>?) =
    RawResult(tool, purls.map { RawComponent(tool, it, it, it.substringAfterLast('/').substringBefore('@'), it.substringAfter('@')) }, edges, roots)

class MergerTest {
    @Test fun `lodash merges into one component with sourced fields`() {
        val l = mergedDir().components.single { it.name == "lodash" }
        assertEquals(setOf("cdxgen", "syft", "trivy"), l.foundBy)
        assertEquals(Sourced("MIT", "cdxgen"), l.licenses)
        assertEquals("https://registry.npmjs.org/lodash/-/lodash-4.17.15.tgz", l.downloadUrl?.value)
        assertFalse(l.needsReview)
    }

    @Test fun `axios is direct and follow-redirects is transitive`() {
        val m = mergedDir()
        assertTrue(m.components.single { it.name == "axios" }.direct)
        assertFalse(m.components.single { it.name == "follow-redirects" }.direct)
    }

    @Test fun `the scanned project itself is not listed as a component`() {
        assertTrue(mergedDir().components.none { it.name == "sca-lab" })
    }

    @Test fun `differing license values are recorded as conflict`() {
        val a = raw("cdxgen", "pkg:npm/x@1", licenses = listOf("MIT")); val b = raw("syft", "pkg:npm/x@1", licenses = listOf("Apache-2.0"))
        val c = Merger.merge(Normalizer.normalize(listOf(a, b)), MergeRules.load()).components.single()
        assertEquals("MIT", c.licenses?.value)
        assertEquals(listOf(Conflict("licenses", "Apache-2.0", "syft")), c.conflicts)
        assertTrue(c.needsReview)
    }

    @Test fun `license names map to spdx ids and free text becomes LicenseRef`() {
        val c = Merger.merge(Normalizer.normalize(listOf(raw("cdxgen", "pkg:npm/x@1", listOf("Apache License 2.0")))), MergeRules.load()).components.single()
        assertEquals("Apache-2.0", c.licenses?.value)
        val d = Merger.merge(Normalizer.normalize(listOf(raw("cdxgen", "pkg:npm/y@1", listOf("Some custom terms")))), MergeRules.load()).components.single()
        assertTrue(d.licenses!!.value.startsWith("LicenseRef-"))
        assertEquals("Some custom terms", d.licenseTexts[d.licenses!!.value])
    }

    @Test fun `component without purl is kept and needs review`() {   // Review Focus 3
        val r = RawResult("syft", listOf(RawComponent("syft", "r1", null, "mystery", "1.0"), RawComponent("syft", "r2", "pkg:npm/%%%", "broken", "2")), emptySet(), null)
        val m = Merger.merge(Normalizer.normalize(listOf(r)), MergeRules.load())
        assertEquals(setOf("mystery@1.0", "broken@2"), m.components.map { it.key }.toSet())
        assertTrue(m.components.all { it.needsReview })
    }

    @Test fun `two versions of the same package stay separate and both are linked`() {   // Review Focus 2
        val r = rawResult("cdxgen", purls = listOf("pkg:npm/app@1", "pkg:npm/lodash@4.17.15", "pkg:npm/lodash@4.17.21", "pkg:npm/b@1"),
            edges = setOf("pkg:npm/app@1" to "pkg:npm/lodash@4.17.21", "pkg:npm/app@1" to "pkg:npm/b@1", "pkg:npm/b@1" to "pkg:npm/lodash@4.17.15"),
            roots = setOf("pkg:npm/app@1"))
        val m = Merger.merge(Normalizer.normalize(listOf(r)), MergeRules.load())
        assertEquals(2, m.components.count { it.name == "lodash" })
        assertTrue("npm/b@1" to "npm/lodash@4.17.15" in m.edges)
    }

    @Test fun `being found by one tool alone is not a review item`() {
        // measured: without a lockfile only cdxgen resolves transitive deps, so "single tool" flagged ~all of express/gradle/.NET
        val c = Merger.merge(Normalizer.normalize(listOf(raw("cdxgen", "pkg:npm/x@1", listOf("MIT")))), MergeRules.load()).components.single()
        assertFalse(c.needsReview)
        assertEquals(setOf("cdxgen"), c.foundBy)
    }

    @Test fun `empty roots from one tool fall back to another tool's roots`() {
        // measured: cdxgen reports dependsOn [] for requirements.txt / .NET while trivy knows the direct deps
        val cdx = rawResult("cdxgen", listOf("pkg:pypi/a@1", "pkg:pypi/b@1"), emptySet(), roots = emptySet())
        val trivy = rawResult("trivy", listOf("pkg:pypi/a@1", "pkg:pypi/b@1"), emptySet(), roots = setOf("pkg:pypi/a@1"))
        val m = Merger.merge(Normalizer.normalize(listOf(cdx, trivy)), MergeRules.load())
        assertEquals(setOf("pypi/a@1"), m.roots)
        assertTrue(m.components.single { it.name == "a" }.direct)
    }

    @Test fun `no roots anywhere falls back to nodes nobody depends on`() {
        val r = rawResult("cdxgen", listOf("pkg:npm/a@1", "pkg:npm/b@1"), setOf("pkg:npm/a@1" to "pkg:npm/b@1"), roots = emptySet())
        assertEquals(setOf("npm/a@1"), Merger.merge(Normalizer.normalize(listOf(r)), MergeRules.load()).roots)
    }

    @Test fun `compound license operands are parenthesized when combined`() {
        val c = Merger.merge(Normalizer.normalize(listOf(raw("cdxgen", "pkg:npm/x@1", listOf("MIT OR Apache-2.0", "BSD-3-Clause")))), MergeRules.load()).components.single()
        assertEquals("(MIT OR Apache-2.0) AND BSD-3-Clause", c.licenses?.value)
    }

    @Test fun `purl-less name containing a slash still needs review`() {   // Review Focus 3: go modules, npm scopes
        val r = RawResult("syft", listOf(RawComponent("syft", "r1", null, "github.com/a/b", "v1.0.0")), emptySet(), null)
        val c = Merger.merge(Normalizer.normalize(listOf(r)), MergeRules.load()).components.single()
        assertTrue(c.needsReview)
        assertEquals("unknown", c.ecosystem)
    }

    @Test fun `cyclic graph terminates`() {   // Review Focus 4
        val r = rawResult("cdxgen", purls = listOf("pkg:npm/a@1", "pkg:npm/b@1"),
            edges = setOf("pkg:npm/a@1" to "pkg:npm/b@1", "pkg:npm/b@1" to "pkg:npm/a@1"), roots = null)
        assertEquals(2, Merger.merge(Normalizer.normalize(listOf(r)), MergeRules.load()).components.size)
    }
}
