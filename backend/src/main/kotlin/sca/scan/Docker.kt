package sca.scan

import com.sun.security.auth.module.UnixSystem
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

data class Mount(val host: Path, val container: String, val readOnly: Boolean)

data class ContainerSpec(
    val tool: String, val image: String, val args: List<String>, val mounts: List<Mount>,
    val network: Boolean, val output: String,   // result file inside the container, /out/<tool>.json
    val env: Map<String, String> = emptyMap(),
)

data class Limits(val cpus: String = "2", val memory: String = "4g")

/** `docker` arguments for a locked-down scanner run: non-root, no capabilities, no privilege gain, capped resources. */
fun dockerArgs(s: ContainerSpec, limits: Limits, name: String = "sca-${s.tool}-${UUID.randomUUID().toString().take(8)}"): List<String> {
    require(s.mounts.none { it.host.toString().endsWith("docker.sock") || it.container.endsWith("docker.sock") }) {
        "refusing to mount the docker socket into a scanner"
    }
    val user = UnixSystem().let { "${it.uid}:${it.gid}" }
    return listOf("run", "--rm", "--name", name, "--user", user, "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
        "--cpus", limits.cpus, "--memory", limits.memory, "-e", "HOME=/tmp") +
        s.env.flatMap { (k, v) -> listOf("-e", "$k=$v") } +
        (if (s.network) emptyList() else listOf("--network", "none")) +
        s.mounts.flatMap { listOf("-v", "${it.host.toAbsolutePath()}:${it.container}${if (it.readOnly) ":ro" else ""}") } +
        s.image + s.args
}

class DockerRunner {
    /** Returns true when the container exited 0 in time. On timeout the container is killed. */
    fun run(s: ContainerSpec, limits: Limits, timeout: Duration, log: Path): Boolean {
        val name = "sca-${s.tool}-${UUID.randomUUID().toString().take(8)}"
        Files.createDirectories(log.parent)
        val p = ProcessBuilder(listOf("docker") + dockerArgs(s, limits, name)).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            ProcessBuilder("docker", "kill", name).start().waitFor(30, TimeUnit.SECONDS)
            p.destroyForcibly()
            Files.writeString(log, "\n[sca] timed out after $timeout\n", java.nio.file.StandardOpenOption.APPEND)
            return false
        }
        return p.exitValue() == 0
    }
}
