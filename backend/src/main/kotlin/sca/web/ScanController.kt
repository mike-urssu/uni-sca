package sca.web

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.core.io.FileSystemResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import sca.job.JobRunner
import sca.job.JobStatus
import sca.job.JobStore
import sca.model.Json
import sca.model.ScanResult
import sca.product.CopyrightStore
import tools.jackson.module.kotlin.readValue
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Zips regular files under dir (relative names). Symlinks, which scanner containers could plant, are skipped, never followed. */
fun zipRegularFiles(dir: Path, out: OutputStream) = ZipOutputStream(out).use { zip ->
    Files.walk(dir).use { s ->
        s.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.sorted().forEach { f ->
            zip.putNextEntry(ZipEntry(dir.relativize(f).toString())); Files.copy(f, zip); zip.closeEntry()
        }
    }
}

private val FILES = mapOf(
    "sbom.cdx.json" to MediaType.APPLICATION_JSON, "sbom.spdx.json" to MediaType.APPLICATION_JSON,
    "result.json" to MediaType.APPLICATION_JSON, "report.html" to MediaType.TEXT_HTML,
)

@RestController
@ConditionalOnWebApplication
@RequestMapping("/api/scans")
class ScanController(private val runner: JobRunner, private val store: JobStore, private val copyrights: CopyrightStore) {

    @GetMapping("/{id}") fun get(@PathVariable id: String): ResponseEntity<JobStatus> =
        store.get(id)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @GetMapping("/{id}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(@PathVariable id: String): ResponseEntity<SseEmitter> =
        if (store.get(id) == null) ResponseEntity.notFound().build() else ResponseEntity.ok(runner.subscribe(id))

    /** result.json with hand-entered copyrights applied. */
    @GetMapping("/{id}/result")
    fun result(@PathVariable id: String): ResponseEntity<ScanResult> {
        val f = store.get(id)?.let { store.dir(id).resolve("result.json") }?.takeIf { Files.exists(it) } ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(copyrights.apply(Json.mapper.readValue<ScanResult>(f.toFile())))
    }

    @GetMapping("/{id}/files/{name}")
    fun file(@PathVariable id: String, @PathVariable name: String): ResponseEntity<*> {
        if (store.get(id) == null) return ResponseEntity.notFound().build<Unit>()
        val dir = store.dir(id)
        if (name == "raw.zip") {
            val raw = dir.resolve("raw")
            if (!Files.isDirectory(raw)) return ResponseEntity.notFound().build<Unit>()
            val body = StreamingResponseBody { out -> zipRegularFiles(raw, out) }
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header("Content-Disposition", "attachment; filename=\"$id-raw.zip\"").body(body)
        }
        val type = FILES[name] ?: return ResponseEntity.notFound().build<Unit>()
        val f = dir.resolve(name)
        if (!Files.exists(f)) return ResponseEntity.notFound().build<Unit>()
        // report.html carries scan-derived text: render it without script or same-origin access; downloads stay allowed for its download-URL links
        return ResponseEntity.ok().contentType(type).apply { if (name == "report.html") header("Content-Security-Policy", "sandbox allow-downloads") }
            .body(FileSystemResource(f))
    }
}
