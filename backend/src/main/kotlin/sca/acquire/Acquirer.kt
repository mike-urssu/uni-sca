package sca.acquire

import sca.model.TargetKind
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit

sealed interface Target {
    data class Repo(val url: String, val ref: String?) : Target
    data class ImageRef(val ref: String) : Target
    data class ImageTar(val path: Path) : Target
}

class InvalidInput(msg: String) : RuntimeException(msg)
class AcquireFailed(msg: String) : RuntimeException(msg)

private val REPO_URL = Regex("""^https://[^\s]+$""")
private val GIT_REF = Regex("""^[A-Za-z0-9][A-Za-z0-9._/-]{0,254}$""")
private val IMAGE_REF = Regex("""^[a-zA-Z0-9][a-zA-Z0-9._/:@-]{0,254}$""")

/** Classifies user input. Everything here ends up as a process argument, so anything option-like or shell-like is rejected. */
fun parseTarget(input: String, ref: String?): Target {
    // a pasted `docker pull <image>` line works as-is; a scheme-less github.com path is a repo, not an image on a registry named github.com
    val s = input.trim().removePrefix("docker pull ").trim().let { if (it.startsWith("github.com/")) "https://$it" else it }
    if (ref != null && !GIT_REF.matches(ref)) throw InvalidInput("invalid git ref: $ref")
    if (s.startsWith("https://")) return if (REPO_URL.matches(s)) Target.Repo(s, ref) else throw InvalidInput("invalid repo URL")
    if (s.endsWith(".tar") && runCatching { Files.isRegularFile(Path.of(s)) }.getOrDefault(false)) return Target.ImageTar(Path.of(s))
    if ("://" !in s && IMAGE_REF.matches(s)) return Target.ImageRef(s)
    throw InvalidInput("expected an https:// git URL, an image reference, or a path to an image .tar: $input")
}

/** REPO: checkout dir + commit SHA. IMAGE: tar file + sha256 digest. */
data class Acquired(val kind: TargetKind, val path: Path, val id: String)

open class Acquirer(val gitTimeout: Duration = Duration.ofMinutes(10), val dockerTimeout: Duration = Duration.ofMinutes(30)) {
    open fun acquire(t: Target, workDir: Path): Acquired {
        // commands run with `work` as their cwd, so every path handed to them must be absolute
        val work = workDir.toAbsolutePath().also { Files.createDirectories(it) }
        return when (t) {
            is Target.Repo -> {
                val dest = work.resolve("src")
                run(listOf("git", "-c", "core.hooksPath=/dev/null", "clone", "--depth", "1", "--recurse-submodules", "--shallow-submodules") +
                    (t.ref?.let { listOf("--branch", it) } ?: emptyList()) + listOf("--", t.url, dest.toString()), work, gitTimeout)
                Acquired(TargetKind.REPO, dest, run(listOf("git", "-C", dest.toString(), "rev-parse", "HEAD"), work, gitTimeout).trim())
            }
            is Target.ImageRef -> {
                val tar = work.resolve("image.tar")
                try { run(listOf("docker", "pull", "-q", "--", t.ref), work, dockerTimeout) } catch (e: AcquireFailed) {
                    // private images use the host's docker credentials
                    if (Regex("unauthorized|denied", RegexOption.IGNORE_CASE).containsMatchIn(e.message ?: "")) {
                        throw AcquireFailed("${e.message}\nprivate image? run `docker login ${t.ref.substringBefore('/').takeIf { '.' in it || ':' in it } ?: "docker.io"}` on the server host, then rescan")
                    }
                    throw e
                }
                run(listOf("docker", "save", "-o", tar.toString(), "--", t.ref), work, dockerTimeout)
                val digest = runCatching { run(listOf("docker", "inspect", "--format", "{{index .RepoDigests 0}}", "--", t.ref), work, dockerTimeout).trim() }
                    .getOrNull()?.substringAfter('@', "")?.takeIf { it.startsWith("sha256:") }
                Acquired(TargetKind.IMAGE, tar, digest ?: sha256(tar))
            }
            is Target.ImageTar -> Acquired(TargetKind.IMAGE, t.path.toAbsolutePath(), sha256(t.path))
        }
    }

    /** Runs without a shell; git never prompts for credentials, so private/missing repos fail instead of hanging. */
    private fun run(cmd: List<String>, dir: Path, timeout: Duration): String {
        val log = Files.createTempFile(dir, "cmd", ".log")
        try {
            val p = ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).apply {
                environment()["GIT_TERMINAL_PROMPT"] = "0"
                environment()["GIT_ASKPASS"] = "/bin/false"
            }.start()
            val done = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!done) p.destroyForcibly()
            val out = Files.readString(log)
            if (!done || p.exitValue() != 0) {
                throw AcquireFailed("${cmd.take(2).joinToString(" ")} failed${if (!done) " (timeout)" else ""}: " + out.lines().takeLast(20).joinToString("\n"))
            }
            return out
        } finally { Files.deleteIfExists(log) }
    }

    private fun sha256(f: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(f).use { s -> val buf = ByteArray(1 shl 16); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return "sha256:" + md.digest().joinToString("") { "%02x".format(it) }
    }
}
