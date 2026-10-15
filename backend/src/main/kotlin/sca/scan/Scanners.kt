package sca.scan

import sca.acquire.Acquired
import sca.model.TargetKind
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.copyToRecursively

/** Which containers to run for a target. Only cdxgen gets network (build tools resolving transitive deps). */
object Scanners {
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun specs(a: Acquired, deep: Boolean, outDir: Path, work: Path, trivyDb: Path, images: Map<String, String>): List<ContainerSpec> {
        // one output dir per tool: cdxgen runs the repo's build code and must not be able to touch other tools' results
        Files.createDirectories(work)
        fun out(tool: String) = Mount(outDir.resolve(tool).also { Files.createDirectories(it) }, "/out", false)
        val repo = a.kind == TargetKind.REPO
        val src = if (repo) Mount(a.path, "/src", true) else Mount(a.path, "/in/image.tar", true)
        val target = if (repo) "/src" else "/in/image.tar"

        // cdxgen may install dependencies while resolving, so it gets its own writable copy of the checkout
        val cdxSrc = if (repo) {
            val copy = work.resolve("cdxgen-src")
            if (!Files.exists(copy)) a.path.copyToRecursively(copy, followLinks = false, overwrite = false)
            Mount(copy, "/src", false)
        } else src
        val trivyCache = listOf("--offline-scan", "--skip-db-update", "--skip-java-db-update", "--cache-dir", "/trivy", "--cache-backend", "memory")

        // each tool gets a host-backed /tmp (HOME): non-root users need it writable, and syft unpacks image layers there
        fun tmp(tool: String) = Mount(work.resolve("tmp-$tool").also { Files.createDirectories(it) }, "/tmp", false)

        return listOfNotNull(
            ContainerSpec("cdxgen", images.getValue("cdxgen"),
                (if (repo) listOf("-r", target) else listOf("-t", "docker", target)) + listOf("-o", "/out/cdxgen.json", "--spec-version", "1.6"),
                listOf(cdxSrc, out("cdxgen"), tmp("cdxgen")), network = true, output = "/out/cdxgen.json"),
            ContainerSpec("syft", images.getValue("syft"),
                listOf("scan", if (repo) "dir:$target" else "docker-archive:$target", "-o", "syft-json=/out/syft.json"),
                listOf(src, out("syft"), tmp("syft")), network = false, output = "/out/syft.json", env = mapOf("SYFT_CHECK_FOR_APP_UPDATE" to "false")),
            ContainerSpec("trivy", images.getValue("trivy"),
                (if (repo) listOf("fs", target) else listOf("image", "--input", target)) +
                    listOf("--format", "cyclonedx", "--output", "/out/trivy.json") + trivyCache,
                listOf(src, out("trivy"), Mount(trivyDb, "/trivy", true), tmp("trivy")), network = false, output = "/out/trivy.json"),
            if (deep && repo) ContainerSpec("scancode", images.getValue("scancode"),
                listOf("-clpi", "-n", "4", "--json-pp", "/out/scancode.json", target),
                listOf(src, out("scancode"), tmp("scancode")), network = false, output = "/out/scancode.json") else null,
        )
    }
}
