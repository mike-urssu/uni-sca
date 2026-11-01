package sca.cli

import org.springframework.stereotype.Component
import sca.job.Pipeline
import sca.job.ScanInput
import sca.job.TrivyDb
import sca.model.ScanResult
import sca.model.Status
import java.nio.file.Files
import java.nio.file.Path

sealed interface CliCommand {
    data class Scan(val input: ScanInput, val out: Path, val strict: Boolean) : CliCommand
    data class Serve(val port: Int) : CliCommand
    data class Usage(val msg: String) : CliCommand
}

const val USAGE = """usage:
  uni-sca scan <repo-url | image-ref | image.tar> [--ref <branch|tag>] [--deep] [--strict] [-o <dir>]
  uni-sca serve [--port <port>]"""

fun parseCli(args: Array<String>): CliCommand {
    val a = args.toList()
    fun usage(why: String) = CliCommand.Usage("$why\n$USAGE")
    return when (a.firstOrNull()) {
        "scan" -> {
            val target = a.getOrNull(1)?.takeIf { !it.startsWith("-") } ?: return usage("missing target")
            var ref: String? = null; var deep = false; var strict = false; var out = Path.of("sca-out")
            var i = 2
            while (i < a.size) {
                when (a[i]) {
                    "--deep" -> deep = true
                    "--strict" -> strict = true
                    "--ref" -> ref = a.getOrNull(++i) ?: return usage("--ref needs a value")
                    "-o", "--out" -> out = Path.of(a.getOrNull(++i) ?: return usage("-o needs a value"))
                    else -> return usage("unknown option ${a[i]}")
                }
                i++
            }
            CliCommand.Scan(ScanInput(target, ref, deep), out, strict)
        }
        "serve" -> when {
            a.size == 1 -> CliCommand.Serve(8080)
            a.size == 3 && a[1] == "--port" -> a[2].toIntOrNull()?.let { CliCommand.Serve(it) } ?: usage("invalid port")
            else -> usage("unknown option ${a.drop(1).joinToString(" ")}")
        }
        else -> usage(if (a.isEmpty()) "missing command" else "unknown command ${a[0]}")
    }
}

/** 0 DONE, 2 PARTIAL or validation warnings (1 with --strict), 1 FAILED or crashed. */
fun exitCode(r: ScanResult?, strict: Boolean): Int = when {
    r == null || r.status == Status.FAILED -> 1
    r.status == Status.PARTIAL -> 2
    r.warnings.any { it.code == "NTIA_MISSING" || it.code == "SCHEMA_INVALID" } -> if (strict) 1 else 2
    else -> 0
}

@Component
class CliRunner(private val pipeline: Pipeline, private val trivyDb: TrivyDb) {
    fun run(c: CliCommand.Scan): Int {
        val r = try {
            Files.createDirectories(c.out)
            trivyDb.use { pipeline.run(c.out, c.input) { System.err.println("[uni-sca] $it") } }
        } catch (e: Exception) {
            System.err.println("[uni-sca] failed: ${e.message ?: e.javaClass.simpleName}")
            null
        }
        r?.let {
            System.err.println("[uni-sca] ${it.status}: ${it.components.size} components (${it.components.count { c -> c.direct }} direct), " +
                "${it.components.count { c -> c.needsReview }} need review → ${c.out.toAbsolutePath()}")
            it.warnings.forEach { w -> System.err.println("[uni-sca] warning ${w.code}: ${w.message}") }
        }
        return exitCode(r, c.strict)
    }
}
