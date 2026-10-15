package sca.cli

import org.junit.jupiter.api.Test
import sca.emit.sampleResult
import sca.job.ScanInput
import sca.model.Status
import sca.model.Warning
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliRunnerTest {
    private fun result(s: Status, vararg w: Warning) = sampleResult().copy(status = s, warnings = w.toList())

    @Test fun `cli parsing`() {
        assertEquals(CliCommand.Scan(ScanInput("node:16-slim", null, true), Path.of("o"), true),
            parseCli(arrayOf("scan", "node:16-slim", "--deep", "--strict", "-o", "o")))
        assertEquals(CliCommand.Scan(ScanInput("https://github.com/a/b", "v1", false), Path.of("sca-out"), false),
            parseCli(arrayOf("scan", "https://github.com/a/b", "--ref", "v1")))
        assertEquals(CliCommand.Serve(8080), parseCli(arrayOf("serve")))
        assertEquals(CliCommand.Serve(9000), parseCli(arrayOf("serve", "--port", "9000")))
        listOf(arrayOf(), arrayOf("scan"), arrayOf("scan", "x", "--bogus"), arrayOf("scan", "x", "-o"), arrayOf("serve", "--port", "abc"))
            .forEach { assertTrue(parseCli(it) is CliCommand.Usage, it.joinToString(" ")) }
    }

    @Test fun `exit codes`() {
        assertEquals(0, exitCode(result(Status.DONE), false))
        assertEquals(2, exitCode(result(Status.PARTIAL), false))
        assertEquals(1, exitCode(result(Status.FAILED), false))
        assertEquals(1, exitCode(null, false))
        assertEquals(1, exitCode(result(Status.DONE, Warning("NTIA_MISSING", "x")), true))
        assertEquals(2, exitCode(result(Status.DONE, Warning("NTIA_MISSING", "x")), false))
        assertEquals(2, exitCode(result(Status.DONE, Warning("SCHEMA_INVALID", "x")), false))
        assertEquals(0, exitCode(result(Status.DONE, Warning("NO_COMPONENTS", "x")), true))
    }
}
