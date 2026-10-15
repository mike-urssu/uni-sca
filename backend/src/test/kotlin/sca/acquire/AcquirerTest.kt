package sca.acquire

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import sca.model.TargetKind
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AcquirerTest {
    @TempDir lateinit var tmp: Path

    @Test fun `parseTarget classifies and rejects injection`() {
        assertEquals(Target.Repo("https://github.com/a/b", "v1"), parseTarget("https://github.com/a/b", "v1"))
        assertEquals(Target.ImageRef("ghcr.io/a/b@sha256:" + "0".repeat(64)), parseTarget("ghcr.io/a/b@sha256:" + "0".repeat(64), null))
        assertEquals(Target.ImageRef("node:16-slim"), parseTarget("node:16-slim", null))
        listOf("--privileged", "a b", "\$(id)", "`id`", "http://x/y.git", "file:///etc", "", "https://x/y --upload-pack=evil")
            .forEach { assertThrows<InvalidInput>(it) { parseTarget(it, null) } }
        assertThrows<InvalidInput> { parseTarget("https://github.com/a/b", "--upload-pack=evil") }
    }

    @Test fun `existing tar file is an image tar`() {
        val tar = Files.writeString(tmp.resolve("app.tar"), "x")
        assertEquals(Target.ImageTar(tar), parseTarget(tar.toString(), null))
    }

    @Test fun `repo clone records the commit sha`() {
        val origin = tmp.resolve("origin").also { Files.createDirectories(it) }
        fun git(vararg a: String) = ProcessBuilder(listOf("git", "-C", origin.toString()) + a).redirectErrorStream(true).start().run { inputStream.readAllBytes(); waitFor() }
        git("init", "-q"); Files.writeString(origin.resolve("package.json"), "{}")
        git("add", "."); git("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init")
        val sha = ProcessBuilder("git", "-C", origin.toString(), "rev-parse", "HEAD").start().inputStream.readAllBytes().decodeToString().trim()
        val a = Acquirer().acquire(Target.Repo(origin.toUri().toString(), null), tmp.resolve("work"))
        assertEquals(TargetKind.REPO, a.kind)
        assertEquals(sha, a.id)
        assertTrue(Files.exists(a.path.resolve("package.json")))
    }

    @Test fun `image tar is hashed`() {
        val tar = Files.writeString(tmp.resolve("i.tar"), "abc")
        val a = Acquirer().acquire(Target.ImageTar(tar), tmp.resolve("work"))
        assertEquals(Acquired(TargetKind.IMAGE, tar, "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), a)
    }

    @Tag("integration")
    @Test fun `missing repo fails fast without prompting`() {   // Review Focus 5
        val t0 = System.nanoTime()
        assertThrows<AcquireFailed> { Acquirer().acquire(Target.Repo("https://github.com/this-org-does-not-exist-xyz/nope", null), tmp) }
        assertTrue(Duration.ofNanos(System.nanoTime() - t0) < Duration.ofSeconds(30))
    }
}
