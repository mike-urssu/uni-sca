package sca.normalize

import org.junit.jupiter.api.Test
import sca.fixture
import sca.model.RawComponent
import sca.model.RawResult
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NormalizerTest {
    @Test fun `key strips qualifiers and normalizes pypi`() {
        assertEquals("deb/debian/libacl1@2.2.53-4", Normalizer.key("pkg:deb/debian/libacl1@2.2.53-4?arch=arm64&upstream=acl", "libacl1", "2.2.53-4"))
        assertEquals("pypi/zope-interface@5.0", Normalizer.key("pkg:pypi/Zope_Interface@5.0", "Zope_Interface", "5.0"))
        assertEquals("npm/@types/node@20.1.0", Normalizer.key("pkg:npm/%40types/node@20.1.0", "node", "20.1.0"))
        assertEquals("deb/debian/attr@1:2.4.48-4", Normalizer.key("pkg:deb/debian/attr@1%3A2.4.48-4", "attr", "1:2.4.48-4"))
    }

    @Test fun `deb and rpm epoch qualifier folds into the version`() {   // trivy: epoch=1 qualifier; syft/cdxgen: "1:" in the version
        assertEquals("deb/debian/bsdutils@1:2.33.1-0.1", Normalizer.key("pkg:deb/debian/bsdutils@2.33.1-0.1?arch=arm64&epoch=1", "bsdutils", "1:2.33.1-0.1"))
        assertEquals("deb/debian/bsdutils@1:2.33.1-0.1", Normalizer.key("pkg:deb/debian/bsdutils@1%3A2.33.1-0.1?arch=arm64&epoch=1", "bsdutils", "1:2.33.1-0.1"))
        assertEquals("rpm/redhat/bash@1:5.1-2", Normalizer.key("pkg:rpm/redhat/bash@5.1-2?epoch=1", "bash", "5.1-2"))
        assertEquals("npm/x@1.0", Normalizer.key("pkg:npm/x@1.0?epoch=1", "x", "1.0"))
    }

    @Test fun `missing or broken purl falls back to name@version`() {
        assertEquals("foo@1.0", Normalizer.key(null, "foo", "1.0"))
        assertEquals("foo@1.0", Normalizer.key("not a purl", "foo", "1.0"))
    }

    @Test fun `image results drop versionless generic and fold source packages`() {
        val n = Normalizer.normalize(listOf(Readers.read("cdxgen", fixture("cdxgen-img.json")), Readers.read("syft", fixture("syft-img.json"))))
        val cdx = n.first { it.tool == "cdxgen" }
        assertTrue(cdx.components.keys.none { it.startsWith("generic/") && !it.contains('@') })
        assertFalse("deb/debian/acl@2.2.53-4" in cdx.components)
        assertTrue("deb/debian/libacl1@2.2.53-4" in cdx.components)
    }

    @Test fun `edges and roots are rewritten to keys and dangling edges dropped`() {
        val raw = RawResult("cdxgen", listOf(
            RawComponent("cdxgen", "r1", "pkg:npm/a@1", "a", "1"),
            RawComponent("cdxgen", "r2", "pkg:npm/b@1", "b", "1"),
            RawComponent("cdxgen", "r3", "pkg:generic/bash", "bash", null),
        ), edges = setOf("r1" to "r2", "r1" to "r3", "r1" to "ghost", "r2" to "r2"), roots = setOf("r1"), project = "pkg:npm/app@1")
        val n = Normalizer.normalize(listOf(raw)).single()
        assertEquals(setOf("npm/a@1" to "npm/b@1"), n.edges)
        assertEquals(setOf("npm/a@1"), n.roots)
        assertEquals("npm/app@1", n.project)
    }
}
