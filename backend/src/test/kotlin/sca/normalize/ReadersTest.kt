package sca.normalize

import org.junit.jupiter.api.Test
import sca.fixture
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadersTest {
    @Test fun `cdxgen gives lodash with resolved url, sha512 and MIT`() {
        val r = Readers.read("cdxgen", fixture("cdxgen.json"))
        val l = r.components.single { it.name == "lodash" }
        assertEquals("pkg:npm/lodash@4.17.15", l.purl)
        assertEquals("https://registry.npmjs.org/lodash/-/lodash-4.17.15.tgz", l.downloadUrl)
        assertEquals(listOf("MIT"), l.licenses)
        assertEquals("SHA-512", l.hashes.single().alg)
        assertTrue("pkg:npm/axios@0.21.0" to "pkg:npm/follow-redirects@1.16.1" in r.edges)
    }

    @Test fun `cdxgen roots are the project's direct dependencies`() {
        val r = Readers.read("cdxgen", fixture("cdxgen.json"))
        assertEquals("pkg:npm/sca-lab@1.0.0", r.project)
        assertEquals(6, r.roots!!.size)
        assertTrue("pkg:npm/axios@0.21.0" in r.roots!!)
    }

    @Test fun `syft integrity becomes the same sha512 hex as cdxgen`() {
        val s = Readers.read("syft", fixture("syft-dir.json")).components.single { it.name == "lodash" }
        val c = Readers.read("cdxgen", fixture("cdxgen.json")).components.single { it.name == "lodash" }
        assertEquals(c.hashes.single().content, s.hashes.single().content)
    }

    @Test fun `syft drops sha256 license values and reads upstream`() {
        val r = Readers.read("syft", fixture("syft-img.json"))
        assertTrue(r.components.flatMap { it.licenses }.none { it.startsWith("sha256:") })
        assertEquals("acl", r.components.single { it.name == "libacl1" }.upstream)
    }

    @Test fun `syft dependency-of becomes child to parent edge`() {
        val r = Readers.read("syft", fixture("syft-dir.json"))
        val byRef = r.components.associateBy { it.ref }
        assertTrue(r.edges.any { (a, b) -> byRef[a]?.name == "axios" && byRef[b]?.name == "follow-redirects" })
    }

    @Test fun `trivy libraries are read and roots go through the purl-less lockfile node`() {
        val r = Readers.read("trivy", fixture("trivy-dir.cdx.json"))
        assertEquals(56, r.components.size)
        assertTrue(r.components.all { it.purl != null })
        assertTrue("pkg:npm/axios@0.21.0" in r.roots!!.map { ref -> r.components.single { it.ref == ref }.purl })
    }
}
