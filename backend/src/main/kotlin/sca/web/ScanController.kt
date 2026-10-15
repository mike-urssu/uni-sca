package sca.web

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import sca.acquire.InvalidInput
import sca.acquire.Target
import sca.acquire.parseTarget
import sca.job.JobRunner
import sca.job.JobStatus
import sca.job.JobStore
import sca.job.ScanInput
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ScanRequest(val target: String, val ref: String? = null, val deep: Boolean = false)

private val FILES = mapOf(
    "sbom.cdx.json" to MediaType.APPLICATION_JSON, "sbom.spdx.json" to MediaType.APPLICATION_JSON,
    "result.json" to MediaType.APPLICATION_JSON, "report.html" to MediaType.TEXT_HTML,
)

@RestController
@ConditionalOnWebApplication
@RequestMapping("/api/scans")
class ScanController(private val runner: JobRunner, private val store: JobStore) {

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun submit(@RequestBody req: ScanRequest): ResponseEntity<Map<String, String>> {
        // a web client may only name remote targets; local tar paths on this machine are CLI-only
        if (parseTarget(req.target, req.ref) is Target.ImageTar) throw InvalidInput("upload the image tar instead of naming a server path")
        return ResponseEntity.status(HttpStatus.CREATED).body(mapOf("id" to runner.submit(ScanInput(req.target.trim(), req.ref, req.deep))))
    }

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(@RequestParam("file") file: MultipartFile, @RequestParam("deep", defaultValue = "false") deep: Boolean): ResponseEntity<Map<String, String>> {
        val name = (file.originalFilename ?: "image.tar").substringAfterLast('/').substringAfterLast('\\').ifBlank { "image.tar" }
        return ResponseEntity.status(HttpStatus.CREATED).body(mapOf("id" to runner.submitUpload(name, deep) { file.transferTo(it) }))
    }

    @GetMapping fun list(): List<JobStatus> = store.list()

    @GetMapping("/{id}") fun get(@PathVariable id: String): ResponseEntity<JobStatus> =
        store.get(id)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @GetMapping("/{id}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(@PathVariable id: String): ResponseEntity<SseEmitter> =
        if (store.get(id) == null) ResponseEntity.notFound().build() else ResponseEntity.ok(runner.subscribe(id))

    @GetMapping("/{id}/result") fun result(@PathVariable id: String) = file(id, "result.json")

    @GetMapping("/{id}/files/{name}")
    fun file(@PathVariable id: String, @PathVariable name: String): ResponseEntity<*> {
        if (store.get(id) == null) return ResponseEntity.notFound().build<Unit>()
        val dir = store.dir(id)
        if (name == "raw.zip") {
            val raw = dir.resolve("raw")
            if (!Files.isDirectory(raw)) return ResponseEntity.notFound().build<Unit>()
            val body = StreamingResponseBody { out ->
                ZipOutputStream(out).use { zip ->
                    Files.list(raw).use { s -> s.sorted().forEach { f -> zip.putNextEntry(ZipEntry(f.fileName.toString())); Files.copy(f, zip); zip.closeEntry() } }
                }
            }
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header("Content-Disposition", "attachment; filename=\"$id-raw.zip\"").body(body)
        }
        val type = FILES[name] ?: return ResponseEntity.notFound().build<Unit>()
        val f = dir.resolve(name)
        if (!Files.exists(f)) return ResponseEntity.notFound().build<Unit>()
        return ResponseEntity.ok().contentType(type).body(FileSystemResource(f))
    }

    @ExceptionHandler(InvalidInput::class)
    fun invalid(e: InvalidInput) = ResponseEntity.badRequest().body(mapOf("error" to e.message))
}
