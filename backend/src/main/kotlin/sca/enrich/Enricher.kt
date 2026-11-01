package sca.enrich

import com.github.packageurl.PackageURL
import sca.merge.allOf
import sca.model.Component
import sca.model.Json
import sca.model.Sourced
import sca.normalize.OS_TYPES
import tools.jackson.databind.JsonNode
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/** Fills fields the scanners left empty. Never overwrites a value a tool observed. */
class Enricher(
    val depsDevBase: String = "https://api.deps.dev",
    val clearlyDefinedBase: String = "https://api.clearlydefined.io",
    val cacheDir: Path,
    val backoff: Duration = Duration.ofSeconds(1),
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()

    fun enrichAll(cs: List<Component>): List<Component> {
        val gate = Semaphore(8)
        return Executors.newVirtualThreadPerTaskExecutor().use { ex ->
            // best effort: an enrichment problem leaves fields unresolved, it never fails the scan
            cs.map { c -> ex.submit<Component> { gate.acquire(); try { runCatching { enrich(c) }.getOrElse { c.copy(unresolved = missing(c)) } } finally { gate.release() } } }.map { it.get() }
        }
    }

    fun enrich(c: Component): Component {
        var r = c
        val p = c.purl?.let { runCatching { PackageURL(it) }.getOrNull() }
        if (p != null) {
            if (r.downloadUrl == null) r = r.copy(downloadUrl = DownloadUrls.forPurl(c.purl!!)?.let { Sourced(it, "template") })
            if (r.registry == null) r = r.copy(registry = p.qualifiers?.get("repository_url")?.let { Sourced(it, c.foundBy.first()) }
                ?: DownloadUrls.defaultRegistry(p.type)?.let { Sourced(it, "template") })
            if (p.type !in OS_TYPES && (r.licenses == null || r.sourceRepo == null)) r = depsDev(r, p)
            if (r.licenses == null || r.copyright == null) r = clearlyDefined(r, p)
        }
        return r.copy(unresolved = missing(r))
    }

    private fun missing(r: Component) = buildSet {
        if (r.licenses == null) add("licenses")
        if (r.downloadUrl == null) add("downloadUrl")
        if (r.supplier == null) add("supplier")
        if (r.sourceRepo == null) add("sourceRepo")
        if (r.hashes.isEmpty()) add("hashes")
    }

    private fun depsDev(c: Component, p: PackageURL): Component {
        val system = mapOf("npm" to "NPM", "maven" to "MAVEN", "pypi" to "PYPI", "golang" to "GO", "cargo" to "CARGO", "nuget" to "NUGET")[p.type]
            ?: return c
        val name = when (p.type) {
            "maven" -> "${p.namespace}:${p.name}"
            else -> listOfNotNull(p.namespace, p.name).joinToString("/")
        }
        val v = p.version ?: return c
        val doc = get("$depsDevBase/v3/systems/$system/packages/${enc(name)}/versions/${enc(v)}") ?: return c
        val licenses = doc["licenses"]?.mapNotNull { it.asString() }?.filter { it != "non-standard" }?.takeIf { it.isNotEmpty() }
        val repo = doc["links"]?.firstOrNull { it["label"]?.asString() == "SOURCE_REPO" }?.get("url")?.asString()
        return c.copy(
            licenses = c.licenses ?: licenses?.let { Sourced(allOf(it), "deps.dev") },
            sourceRepo = c.sourceRepo ?: repo?.let { Sourced(it, "deps.dev") },
        )
    }

    private fun clearlyDefined(c: Component, p: PackageURL): Component {
        val (type, provider) = mapOf(
            "npm" to ("npm" to "npmjs"), "maven" to ("maven" to "mavencentral"), "pypi" to ("pypi" to "pypi"),
            "gem" to ("gem" to "rubygems"), "nuget" to ("nuget" to "nuget"), "cargo" to ("crate" to "cratesio"),
            "golang" to ("go" to "golang"), "deb" to ("deb" to "debian"),
        )[p.type] ?: return c
        val v = p.version ?: return c
        val doc = get("$clearlyDefinedBase/definitions/$type/$provider/${p.namespace?.let(::enc) ?: "-"}/${enc(p.name)}/${enc(v)}") ?: return c
        val declared = doc["licensed"]?.get("declared")?.asString()?.takeIf { it.isNotBlank() && it != "NOASSERTION" && it != "OTHER" }
        // the copyright statements ClearlyDefined harvested from the package's files
        val parties = doc["licensed"]?.get("facets")?.get("core")?.get("attribution")?.get("parties")
            ?.mapNotNull { it.asString()?.trim()?.takeIf(String::isNotEmpty) }?.distinct()?.takeIf { it.isNotEmpty() }
        return c.copy(
            licenses = c.licenses ?: declared?.let { Sourced(it, "clearlydefined") },
            copyright = c.copyright ?: parties?.let { Sourced(it.joinToString("\n"), "clearlydefined") },
        )
    }

    /**
     * GET with a file cache. 404 is cached as "nothing"; 429/5xx/IO errors retry 3 times, then give up uncached.
     * Only parseable JSON is cached (written atomically); an unreadable cache entry is treated as a miss.
     */
    private fun get(url: String): JsonNode? {
        val file = cacheDir.resolve(sha256(url) + ".json")
        if (Files.exists(file)) {
            val text = Files.readString(file)
            if (text.isEmpty()) return null
            runCatching { Json.mapper.readTree(text) }.onSuccess { return it }
            Files.deleteIfExists(file)
        }
        repeat(4) { attempt ->
            if (attempt > 0) Thread.sleep(backoff.multipliedBy(1L shl (attempt - 1)))
            val res = try {
                http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString())
            } catch (_: IOException) { return@repeat }
            when (res.statusCode()) {
                200 -> {
                    val node = runCatching { Json.mapper.readTree(res.body()) }.getOrNull()?.takeIf { it.isObject } ?: return null
                    cache(file, res.body()); return node
                }
                404 -> { cache(file, ""); return null }
                429, in 500..599 -> {}
                else -> return null
            }
        }
        return null
    }

    private fun cache(file: Path, body: String) {
        Files.createDirectories(cacheDir)
        val tmp = Files.createTempFile(cacheDir, "tmp", ".part")
        Files.writeString(tmp, body)
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
