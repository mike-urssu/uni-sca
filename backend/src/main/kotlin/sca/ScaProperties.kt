package sca

import org.springframework.boot.context.properties.ConfigurationProperties
import sca.scan.Limits
import java.nio.file.Path
import java.time.Duration

@ConfigurationProperties("sca")
data class ScaProperties(
    val dataDir: Path = Path.of("data"),
    val toolTimeout: Duration = Duration.ofMinutes(15),
    val workers: Int = 2,
    val images: Map<String, String> = emptyMap(),
    val limits: Limits = Limits(),
    val depsDevBase: String = "https://api.deps.dev",
    val clearlyDefinedBase: String = "https://api.clearlydefined.io",
)
