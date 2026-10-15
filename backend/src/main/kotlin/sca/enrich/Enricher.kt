package sca.enrich

import com.github.packageurl.PackageURL
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
            cs.map { c -> ex.submit<Component> { gate.acquire(); try { enrich(c) } finally { gate.release() } } }.map { it.get() }
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
            if (r.licenses == null) r = clearlyDefined(r, p)
        }
        val missing = buildSet {
            if (r.licenses == null) add("licenses")
            if (r.downloadUrl == null) add("downloadUrl")
            if (r.supplier == null) add("supplier")
            if (r.sourceRepo == null) add("sourceRepo")
            if (r.hashes.isEmpty()) add("hashes")
        }
        return r.copy(unresolved = missing)
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
            licenses = c.licenses ?: licenses?.let { Sourced(it.joinToString(" AND "), "deps.dev") },
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
        return c.copy(licenses = declared?.let { Sourced(it, "clearlydefined") })
    }

    /** GET with a file cache. 404 is cached as "nothing"; 429/5xx/IO errors retry 3 times, then give up uncached. */
    private fun get(url: String): JsonNode? {
        val file = cacheDir.resolve(sha256(url) + ".json")
        if (Files.exists(file)) return Files.readString(file).takeIf { it.isNotEmpty() }?.let { Json.mapper.readTree(it) }
        repeat(4) { attempt ->
            if (attempt > 0) Thread.sleep(backoff.multipliedBy(1L shl (attempt - 1)))
            val res = try {
                http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString())
            } catch (_: IOException) { return@repeat }
            when (res.statusCode()) {
                200 -> { Files.createDirectories(cacheDir); Files.writeString(file, res.body()); return Json.mapper.readTree(res.body()) }
                404 -> { Files.createDirectories(cacheDir); Files.writeString(file, ""); return null }
                429, in 500..599 -> {}
                else -> return null
            }
        }
        return null
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
