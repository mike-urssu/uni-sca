package sca.job

import jakarta.annotation.PreDestroy
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import sca.ScaProperties
import sca.model.Json
import sca.model.Status
import sca.model.ToolRun
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class JobStatus(
    val id: String, val status: Status, val stage: String?, val input: ScanInput, val createdAt: Instant,
    val tools: List<ToolRun> = emptyList(), val error: String? = null,
)

private val JOB_ID = Regex("""^\d{8}-\d{6}-[a-z0-9]{6}$""")

/** One directory per job under <dataDir>/jobs; status.json is the only mutable file. */
class JobStore(val dataDir: Path) {
    private val jobs = dataDir.resolve("jobs")

    fun create(input: ScanInput): JobStatus {
        val id = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now()) + "-" +
            (1..6).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
        Files.createDirectories(jobs.resolve(id))
        Files.writeString(jobs.resolve(id).resolve("input.json"), Json.mapper.writeValueAsString(input))
        return JobStatus(id, Status.QUEUED, null, input, Instant.now()).also(::save)
    }

    fun save(s: JobStatus) {
        val tmp = jobs.resolve(s.id).resolve("status.json.tmp")
        Files.writeString(tmp, Json.mapper.writeValueAsString(s))
        Files.move(tmp, jobs.resolve(s.id).resolve("status.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun get(id: String): JobStatus? {
        if (!JOB_ID.matches(id)) return null   // ids become paths: never let one escape the jobs dir
        val f = jobs.resolve(id).resolve("status.json")
        return if (Files.exists(f)) Json.mapper.readValue<JobStatus>(f.toFile()) else null
    }

    fun list(): List<JobStatus> = if (!Files.exists(jobs)) emptyList() else
        Files.list(jobs).use { s -> s.map { it.fileName.toString() }.toList() }.sortedDescending().mapNotNull(::get)

    fun dir(id: String): Path = jobs.resolve(id).also { require(JOB_ID.matches(id)) }

    fun failInterrupted() = list().filter { it.status == Status.RUNNING || it.status == Status.QUEUED }
        .forEach { save(it.copy(status = Status.FAILED, error = "server restarted")) }
}

/** trivy's vulnerability/java DBs, downloaded into a dir the scan containers mount read-only. Refreshed at most daily. */
class TrivyDb(private val props: ScaProperties) {
    private val lock = ReentrantReadWriteLock()
    private val dir: Path get() = props.dataDir.resolve("trivy-db")

    fun <T> use(block: () -> T): T {
        val marker = dir.resolve("db/metadata.json")
        val stale = !Files.exists(marker) || Files.getLastModifiedTime(marker).toInstant() < Instant.now().minus(Duration.ofHours(24))
        if (stale) lock.write { refresh() }
        return lock.read(block)
    }

    private fun refresh() {
        Files.createDirectories(dir)
        val user = com.sun.security.auth.module.UnixSystem().let { "${it.uid}:${it.gid}" }
        for (flag in listOf("--download-db-only", "--download-java-db-only")) {
            ProcessBuilder("docker", "run", "--rm", "--user", user, "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--tmpfs", "/tmp:mode=1777", "-e", "HOME=/tmp", "-v", "${dir.toAbsolutePath()}:/trivy", props.images.getValue("trivy"),
                "image", flag, "--cache-dir", "/trivy", "-q").inheritIO().start().waitFor(30, TimeUnit.MINUTES)
        }
    }
}

// web mode only: a CLI run must not mark a live server's jobs as interrupted
@Service
@ConditionalOnWebApplication
class JobRunner(private val store: JobStore, private val pipeline: Pipeline, private val trivyDb: TrivyDb, props: ScaProperties) {
    private val pool = Executors.newFixedThreadPool(props.workers)
    private val emitters = ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>()

    init { store.failInterrupted() }

    @PreDestroy fun stop() = pool.shutdownNow()

    fun submit(input: ScanInput): String {
        val job = store.create(input)
        pool.submit { run(job) }
        return job.id
    }

    /** Stores an uploaded image tar as jobs/<id>/upload.tar, then queues the scan. */
    fun submitUpload(name: String, deep: Boolean, transfer: (Path) -> Unit): String {
        val created = store.create(ScanInput(name, deep = deep))
        val tar = store.dir(created.id).resolve("upload.tar")
        transfer(tar)
        val job = created.copy(input = created.input.copy(uploadedTar = tar.toAbsolutePath())).also(store::save)
        pool.submit { run(job) }
        return job.id
    }

    fun subscribe(id: String): SseEmitter {
        val e = SseEmitter(Duration.ofHours(2).toMillis())
        val s = store.get(id) ?: return e.also { it.completeWithError(NoSuchElementException(id)) }
        emitters.computeIfAbsent(id) { CopyOnWriteArrayList() }.add(e)
        e.onCompletion { emitters[id]?.remove(e) }
        e.onTimeout { emitters[id]?.remove(e) }
        send(s, if (s.status in TERMINAL) "done" else "stage")
        return e
    }

    private fun run(job: JobStatus) {
        var s = job.copy(status = Status.RUNNING, stage = "queued").also(::update)
        try {
            val r = trivyDb.use { pipeline.run(store.dir(job.id), job.input) { stage -> s = s.copy(stage = stage).also(::update) } }
            s = s.copy(status = r.status, stage = null, tools = r.tools,
                error = if (r.status == Status.FAILED) r.warnings.joinToString("; ") { it.message } else null)
        } catch (e: Exception) {
            s = s.copy(status = Status.FAILED, stage = null, error = e.message ?: e.javaClass.simpleName)
        }
        update(s)
    }

    private fun update(s: JobStatus) {
        store.save(s)
        send(s, if (s.status in TERMINAL) "done" else "stage")
    }

    private fun send(s: JobStatus, event: String) {
        emitters[s.id]?.forEach { e ->
            runCatching { e.send(SseEmitter.event().name(event).data(Json.mapper.writeValueAsString(s))) }.onFailure { emitters[s.id]?.remove(e) }
            if (event == "done") runCatching { e.complete() }
        }
    }

    companion object { val TERMINAL = setOf(Status.DONE, Status.PARTIAL, Status.FAILED) }
}
