package sca.web

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import sca.acquire.InvalidInput
import sca.emit.CycloneDxWriter
import sca.emit.NoticeWriter
import sca.emit.SpdxWriter
import sca.job.JobRunner
import sca.job.JobStatus
import sca.job.JobStore
import sca.job.ScanInput
import sca.model.Json
import sca.model.ScanResult
import sca.model.Status
import sca.product.CopyrightStore
import sca.product.DiffRow
import sca.product.Product
import sca.product.ProductStore
import sca.product.ProductVersion
import sca.product.Source
import sca.product.SourceInput
import sca.product.combine
import sca.product.compare
import sca.product.newId
import sca.product.source
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.time.Instant

data class CreateProduct(val name: String, val version: String, val sources: List<SourceInput>)
data class CreateVersion(val version: String, val sources: List<SourceInput>)
data class Rename(val name: String)

/** Version exports: file name → writer and media type. */
private val EXPORTS = mapOf(
    "sbom.cdx.json" to (CycloneDxWriter::write to MediaType.APPLICATION_JSON),
    "sbom.spdx.json" to (SpdxWriter::write to MediaType.APPLICATION_JSON),
    "oss-notice.html" to (NoticeWriter::write to MediaType("text", "html", Charsets.UTF_8)),
)

/** Products → versions → sources. Creating a version or source scans it right away; a scan always belongs to one source. */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api/products")
class ProductController(private val products: ProductStore, private val jobs: JobStore, private val runner: JobRunner, private val copyrights: CopyrightStore) {

    @GetMapping fun list(): List<Product> = products.list()

    /** Registers a product, or a new version of the product with that name. The response's last version is the new one. */
    @PostMapping
    fun create(@RequestBody req: CreateProduct): ResponseEntity<Product> {
        val p = products.create(req.name, req.version, nonEmpty(req.sources))
        p.versions.last().let { v -> scan(p, v, v.sources) }
        return ResponseEntity.status(HttpStatus.CREATED).body(p)
    }

    @GetMapping("/{pid}") fun get(@PathVariable pid: String) = product(pid)

    /** Renaming to another product's name merges this product's versions (and their scans) into that product. */
    @PatchMapping("/{pid}")
    fun rename(@PathVariable pid: String, @RequestBody req: Rename): Product {
        product(pid)
        val into = products.list().firstOrNull { it.id != pid && it.name.equals(req.name.trim(), ignoreCase = true) }
            ?: return products.update(pid) { it.copy(name = req.name) }
        val mine = jobs.list().filter { it.input.productId == pid }
        // a running job saves its status from memory and would write the old product id back
        if (mine.any { it.status !in JobRunner.TERMINAL }) throw IllegalStateException("a scan is still running; merge after it finishes")
        val merged = products.merge(pid, into.id)
        mine.forEach { jobs.save(it.copy(input = it.input.copy(productId = into.id))) }
        return merged
    }

    @DeleteMapping("/{pid}")
    fun delete(@PathVariable pid: String): ResponseEntity<Unit> {
        val p = product(pid)
        deleteScans(p.versions.flatMap { it.sources })
        products.delete(pid)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{pid}/versions")
    fun addVersion(@PathVariable pid: String, @RequestBody req: CreateVersion): ResponseEntity<ProductVersion> {
        val v = ProductVersion(newId(), req.version, Instant.now(), nonEmpty(req.sources).map(::source))
        val p = products.update(pid) { it.copy(versions = it.versions + v) }
        scan(p, p.version(v.id), v.sources)
        return ResponseEntity.status(HttpStatus.CREATED).body(p.version(v.id))
    }

    @PatchMapping("/{pid}/versions/{vid}")
    fun renameVersion(@PathVariable pid: String, @PathVariable vid: String, @RequestBody req: Rename) = products.update(pid) { p ->
        p.version(vid).let { v -> p.copy(versions = p.versions.map { if (it.id == vid) v.copy(version = req.name) else it }) }
    }

    @DeleteMapping("/{pid}/versions/{vid}")
    fun deleteVersion(@PathVariable pid: String, @PathVariable vid: String): ResponseEntity<Unit> {
        deleteScans(product(pid).version(vid).sources)
        products.update(pid) { p -> p.copy(versions = p.versions.filter { it.id != vid }) }
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{pid}/versions/{vid}/sources")
    fun addSource(@PathVariable pid: String, @PathVariable vid: String, @RequestBody req: SourceInput): ResponseEntity<Source> {
        val s = source(req)
        val p = products.update(pid) { p -> p.version(vid).let { v -> p.copy(versions = p.versions.map { if (it.id == vid) v.copy(sources = v.sources + s) else it }) } }
        scan(p, p.version(vid), listOf(s))
        return ResponseEntity.status(HttpStatus.CREATED).body(s)
    }

    @DeleteMapping("/{pid}/versions/{vid}/sources/{sid}")
    fun deleteSource(@PathVariable pid: String, @PathVariable vid: String, @PathVariable sid: String): ResponseEntity<Unit> {
        deleteScans(product(pid).version(vid).sources.filter { it.id == sid }.ifEmpty { throw NoSuchElementException("source $sid") })
        products.update(pid) { p -> p.copy(versions = p.versions.map { v -> if (v.id == vid) v.copy(sources = v.sources.filter { it.id != sid }) else v }) }
        return ResponseEntity.noContent().build()
    }

    /** Scans every source of the version, or only `source` when given. */
    @PostMapping("/{pid}/versions/{vid}/scans")
    fun rescan(@PathVariable pid: String, @PathVariable vid: String, @RequestParam(required = false) source: String?): ResponseEntity<List<String>> {
        val p = product(pid); val v = p.version(vid)
        val sources = if (source == null) v.sources else v.sources.filter { it.id == source }.ifEmpty { throw NoSuchElementException("source $source") }
        return ResponseEntity.status(HttpStatus.CREATED).body(scan(p, v, sources))
    }

    /** The version's scans, newest first. */
    @GetMapping("/{pid}/versions/{vid}/scans")
    fun scans(@PathVariable pid: String, @PathVariable vid: String): List<JobStatus> {
        product(pid).version(vid)
        return jobs.list().filter { it.input.versionId == vid }
    }

    /** The version's combined result, or only the given sources combined (`?sources=a,b`; unknown ids are ignored). */
    @GetMapping("/{pid}/versions/{vid}/result")
    fun result(@PathVariable pid: String, @PathVariable vid: String, @RequestParam(required = false) sources: Set<String>? = null) =
        combined(product(pid), vid, sources)

    @GetMapping("/{pid}/versions/{vid}/files/{name}")
    fun file(@PathVariable pid: String, @PathVariable vid: String, @PathVariable name: String): ResponseEntity<ByteArray> {
        val (write, type) = EXPORTS[name] ?: return ResponseEntity.notFound().build()
        val p = product(pid)
        val r = combined(p, vid)
        val tmp = Files.createTempFile("export", null)
        try {
            write(r, tmp)
            // fixed file names (sbom.cdx.json, sbom.spdx.json, oss-notice.html), whatever the product and version
            // the notice carries scan-derived text: should a browser render it anyway, no script or same-origin access
            return ResponseEntity.ok().contentType(type).header("Content-Security-Policy", "sandbox")
                .header("Content-Disposition", "attachment; filename=\"$name\"").body(Files.readAllBytes(tmp))
        } finally { Files.deleteIfExists(tmp) }
    }

    @GetMapping("/{pid}/compare")
    fun compare(@PathVariable pid: String, @RequestParam a: String, @RequestParam b: String): List<DiffRow> {
        val p = product(pid)
        return compare(combined(p, a), combined(p, b))
    }

    private fun product(pid: String) = products.get(pid) ?: throw NoSuchElementException("product $pid")

    private fun nonEmpty(sources: List<SourceInput>) = sources.ifEmpty { throw InvalidInput("add at least one repository or image") }

    private fun scan(p: Product, v: ProductVersion, sources: List<Source>) =
        sources.map { s -> runner.submit(ScanInput(s.target, s.ref, s.deep, p.id, v.id, s.id)) }

    // ponytail: reads every job's status.json per request; index jobs by source if the job count gets large
    private fun combined(p: Product, vid: String, only: Set<String>? = null): ScanResult {
        val v = p.version(vid)
        val all = jobs.list()
        return copyrights.apply(combine(p.name, v.version, v.sources.filter { only == null || it.id in only }.map { s ->
            s to all.firstOrNull { it.input.sourceId == s.id && it.status in OK }?.let { Json.mapper.readValue<ScanResult>(jobs.dir(it.id).resolve("result.json").toFile()) }
        }))
    }

    private fun deleteScans(sources: List<Source>) {
        val ids = sources.map { it.id }.toSet()
        val mine = jobs.list().filter { it.input.sourceId in ids }
        if (mine.any { it.status !in JobRunner.TERMINAL }) throw IllegalStateException("a scan is still running; delete after it finishes")
        mine.forEach { jobs.delete(it.id) }
    }

    @ExceptionHandler(InvalidInput::class) fun invalid(e: InvalidInput) = ResponseEntity.badRequest().body(mapOf("error" to e.message))
    @ExceptionHandler(NoSuchElementException::class) fun missing(e: NoSuchElementException) = ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to e.message))
    @ExceptionHandler(IllegalStateException::class) fun busy(e: IllegalStateException) = ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to e.message))

    private companion object { val OK = setOf(Status.DONE, Status.PARTIAL) }
}
