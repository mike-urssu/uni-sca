package sca

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class ScaApplication

fun main(args: Array<String>) {
	runApplication<ScaApplication>(*args)
}
