package sca.enrich

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import sca.fixture
import sca.model.Component
import sca.model.Sourced
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tiny fake for deps.dev / ClearlyDefined: per-path canned responses, a default status, and request counts. */
class FakeApi(private val defaultStatus: Int = 404) : AutoCloseable {
    private val routes = ConcurrentHashMap<String, Pair<Int, String>>()
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.rawPath
            counts.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            val (status, body) = routes[path] ?: (defaultStatus to "")
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    val base = "http://127.0.0.1:${server.address.port}"
    fun respond(path: String, status: Int, body: String) = apply { routes[path] = status to body }
    fun count(path: String) = counts[path]?.get() ?: 0
    override fun close() = server.stop(0)
}

class EnricherTest {
    @TempDir lateinit var cacheDir: Path
    private val api = FakeApi()
    @AfterEach fun stop() = api.close()

    private fun enricher(a: FakeApi = api) = Enricher(a.base, a.base, cacheDir, Duration.ZERO)
    private fun comp(purl: String) = Component(key = purl.removePrefix("pkg:"), purl = purl, name = purl.substringAfterLast('/').substringBefore('@'),
        version = purl.substringAfter('@'), ecosystem = purl.removePrefix("pkg:").substringBefore('/'), foundBy = setOf("syft"))

    @Test fun `fills only empty fields from deps dev and template`() {
        api.respond("/v3/systems/NPM/packages/lodash/versions/4.17.15", 200, Files.readString(fixture("depsdev-lodash.json")))
        val r = enricher().enrich(comp("pkg:npm/lodash@4.17.15"))
        assertEquals(Sourced("MIT", "deps.dev"), r.licenses)
        assertEquals(Sourced("https://registry.npmjs.org/lodash/-/lodash-4.17.15.tgz", "template"), r.downloadUrl)
        assertEquals(Sourced("https://registry.npmjs.org", "template"), r.registry)
        assertEquals("git+https://github.com/lodash/lodash.git", r.sourceRepo?.value)
    }

    @Test fun `never overwrites tool values`() {
        api.respond("/v3/systems/NPM/packages/lodash/versions/4.17.15", 200, Files.readString(fixture("depsdev-lodash.json")))
        val c = comp("pkg:npm/lodash@4.17.15").copy(licenses = Sourced("Apache-2.0", "cdxgen"))
        assertEquals(Sourced("Apache-2.0", "cdxgen"), enricher().enrich(c).licenses)
    }

    @Test fun `falls back to clearlydefined when deps dev has no license`() {
        api.respond("/definitions/npm/npmjs/-/x/1", 200, """{"licensed":{"declared":"BSD-3-Clause"}}""")
        assertEquals(Sourced("BSD-3-Clause", "clearlydefined"), enricher().enrich(comp("pkg:npm/x@1")).licenses)
    }

    @Test fun `429 retried 3 times then unresolved and not cached`() {
        FakeApi(defaultStatus = 429).use { busy ->
            val r = enricher(busy).enrich(comp("pkg:npm/x@1"))
            assertTrue("licenses" in r.unresolved)
            assertEquals(1 + 3, busy.count("/v3/systems/NPM/packages/x/versions/1"))
            assertEquals(0, Files.list(cacheDir).count())
        }
    }

    @Test fun `second call is served from cache`() {
        api.respond("/v3/systems/NPM/packages/lodash/versions/4.17.15", 200, Files.readString(fixture("depsdev-lodash.json")))
        enricher().enrich(comp("pkg:npm/lodash@4.17.15")); enricher().enrich(comp("pkg:npm/lodash@4.17.15"))
        assertEquals(1, api.count("/v3/systems/NPM/packages/lodash/versions/4.17.15"))
    }

    @Test fun `os packages skip deps dev`() {
        enricher().enrich(comp("pkg:deb/debian/libacl1@2.2.53-4?arch=arm64"))
        assertEquals(0, api.count("/v3/systems/DEB/packages/libacl1/versions/2.2.53-4") + api.count("/v3/systems/NPM/packages/libacl1/versions/2.2.53-4"))
    }

    @Test fun `scoped npm and maven names are encoded for deps dev`() {
        enricher().enrich(comp("pkg:npm/%40types/node@20.1.0"))
        enricher().enrich(comp("pkg:maven/org.apache.commons/commons-lang3@3.12.0"))
        assertEquals(1, api.count("/v3/systems/NPM/packages/%40types%2Fnode/versions/20.1.0"))
        assertEquals(1, api.count("/v3/systems/MAVEN/packages/org.apache.commons%3Acommons-lang3/versions/3.12.0"))
    }

    @Test fun `corrupt cache file is a miss, not a crash`() {
        api.respond("/v3/systems/NPM/packages/lodash/versions/4.17.15", 200, Files.readString(fixture("depsdev-lodash.json")))
        enricher().enrich(comp("pkg:npm/lodash@4.17.15"))
        Files.list(cacheDir).use { s -> s.forEach { Files.writeString(it, "{\"licen") } }   // truncated write
        assertEquals(Sourced("MIT", "deps.dev"), enricher().enrich(comp("pkg:npm/lodash@4.17.15")).licenses)
    }

    @Test fun `non-json 200 is unresolved and not cached`() {   // captive portal / proxy page
        api.respond("/v3/systems/NPM/packages/x/versions/1", 200, "<html>login</html>")
        val r = enricher().enrich(comp("pkg:npm/x@1"))
        assertTrue("licenses" in r.unresolved)
        enricher().enrich(comp("pkg:npm/x@1"))
        assertEquals(2, api.count("/v3/systems/NPM/packages/x/versions/1"))   // asked again: the HTML page was not cached
    }

    @Test fun `deps dev license lists keep OR groups intact`() {
        api.respond("/v3/systems/NPM/packages/x/versions/1", 200, """{"licenses":["MIT OR Apache-2.0","Zlib"]}""")
        assertEquals("(MIT OR Apache-2.0) AND Zlib", enricher().enrich(comp("pkg:npm/x@1")).licenses?.value)
    }

    @Test fun `unresolved lists the fields nobody could fill`() {
        val r = enricher().enrich(comp("pkg:rpm/redhat/bash@5.1"))
        assertEquals(setOf("licenses", "downloadUrl", "supplier", "sourceRepo", "hashes"), r.unresolved)
    }
}
