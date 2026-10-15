package sca

import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import sca.cli.CliCommand
import sca.cli.CliRunner
import sca.cli.parseCli
import kotlin.system.exitProcess

@SpringBootApplication
@ConfigurationPropertiesScan
class ScaApplication

fun main(args: Array<String>) {
    when (val c = parseCli(args)) {
        is CliCommand.Usage -> { System.err.println(c.msg); exitProcess(1) }
        is CliCommand.Serve -> SpringApplicationBuilder(ScaApplication::class.java).run("--server.port=${c.port}")
        is CliCommand.Scan -> {
            val ctx = SpringApplicationBuilder(ScaApplication::class.java).web(WebApplicationType.NONE).logStartupInfo(false).run()
            exitProcess(ctx.getBean(CliRunner::class.java).run(c).also { ctx.close() })
        }
    }
}
