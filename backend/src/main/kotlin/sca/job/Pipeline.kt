package sca.job

import sca.ScaProperties
import sca.acquire.Acquirer
import sca.acquire.Target
import sca.acquire.parseTarget
import sca.emit.CycloneDxWriter
import sca.emit.NtiaValidator
import sca.emit.ReportWriter
import sca.emit.SpdxWriter
import sca.enrich.Enricher
import sca.merge.MergeRules
import sca.merge.Merger
import sca.model.Json
import sca.model.ScanResult
import sca.model.Status
import sca.model.TargetInfo
import sca.model.ToolRun
import sca.model.Warning
import sca.normalize.Normalizer
import sca.normalize.Readers
import sca.scan.ContainerSpec
import sca.scan.Scanners
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.io.path.deleteRecursively

data class ScanInput(val target: String, val ref: String? = null, val deep: Boolean = false, val uploadedTar: Path? = null)

/** Runs one scanner container; DockerRunner in production, a fake in tests. */
fun interface ScanStep { fun run(spec: ContainerSpec, log: Path): Boolean }

// cdxgen log lines that mean a build tool could not resolve the dependency tree
private val RESOLUTION_FAILURE = Regex("Unable to|exited with error")

class Pipeline(val props: ScaProperties, val acquirer: Acquirer, val step: ScanStep, val enricher: Enricher, val rules: MergeRules) {
    /** Writes everything into jobDir. Invalid input and acquire failures throw; scanner failures become PARTIAL/FAILED. */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun run(jobDir: Path, input: ScanInput, progress: (String) -> Unit): ScanResult {
        val raw = jobDir.resolve("raw").also { Files.createDirectories(it) }
        // private, uniquely named: in CLI mode jobDir is the user's -o directory, which may hold a "work" dir of their own
        val work = Files.createTempDirectory(jobDir.also { Files.createDirectories(it) }, ".work-")
        try {
            progress("acquire")
            val target = input.uploadedTar?.let { Target.ImageTar(it) } ?: parseTarget(input.target, input.ref)
            val acquired = acquirer.acquire(target, work)
            val info = TargetInfo(acquired.kind, input.target, input.ref, acquired.id)

            progress("scan")
            val specs = Scanners.specs(acquired, input.deep, raw, work, props.dataDir.resolve("trivy-db"), props.images)
            specs.forEach { Files.deleteIfExists(output(raw, it.tool)) }   // never reuse a previous run's result
            val runs = Executors.newVirtualThreadPerTaskExecutor().use { ex ->
                specs.map { s -> ex.submit<ToolRun> {
                    val t0 = System.currentTimeMillis()
                    val ok = runCatching { step.run(s, raw.resolve("${s.tool}.log")) }.getOrDefault(false) && Files.isRegularFile(output(raw, s.tool), LinkOption.NOFOLLOW_LINKS)
                    ToolRun(s.tool, s.image, ok, System.currentTimeMillis() - t0, "raw/${s.tool}.log")
                } }.map { it.get() }
            }

            progress("normalize")
            val parsed = runs.associate { r -> r.tool to (if (r.ok) runCatching { Readers.read(r.tool, output(raw, r.tool)) }.getOrNull() else null) }
            val tools = runs.map { it.copy(ok = parsed[it.tool] != null) }
            val failed = tools.filter { !it.ok }.map { it.tool }
            if (failed.size == tools.size) {
                return ScanResult(info, Status.FAILED, tools, emptyList(), emptySet(), emptySet(),
                    failed.map { Warning("TOOL_FAILED", "$it failed, see raw/$it.log") }, Instant.now())
            }
            val normalized = Normalizer.normalize(parsed.values.filterNotNull())

            progress("merge")
            val merged = Merger.merge(normalized, rules)

            progress("enrich")
            val components = enricher.enrichAll(merged.components)

            progress("emit")
            val warnings = failed.map { Warning("TOOL_FAILED", "$it failed, see raw/$it.log") } +
                (if (components.isEmpty()) listOf(Warning("NO_COMPONENTS", "no packages were found in the target")) else emptyList()) +
                resolutionFailures(raw.resolve("cdxgen.log"))
            var result = ScanResult(info, if (failed.isEmpty()) Status.DONE else Status.PARTIAL, tools, components,
                merged.edges, merged.roots, warnings, Instant.now())
            result = result.copy(warnings = result.warnings + NtiaValidator.check(result))
            val schema = CycloneDxWriter.write(result, jobDir.resolve("sbom.cdx.json")) + SpdxWriter.write(result, jobDir.resolve("sbom.spdx.json"))
            result = result.copy(warnings = result.warnings + schema)
            Files.writeString(jobDir.resolve("result.json"), Json.mapper.writeValueAsString(result))
            ReportWriter.write(result, jobDir.resolve("report.html"))
            return result
        } finally {
            work.deleteRecursively()   // checkout, cdxgen copy, pulled image tar, tool tmp dirs; raw/ stays as evidence
        }
    }

    /** Where a tool's container writes its result: raw/<tool>/<tool>.json (only regular files are read). */
    private fun output(raw: Path, tool: String): Path = raw.resolve(tool).resolve("$tool.json")

    private fun resolutionFailures(log: Path): List<Warning> = if (!Files.exists(log)) emptyList() else
        Files.readAllLines(log).map { it.trim() }.filter { RESOLUTION_FAILURE.containsMatchIn(it) }.distinct().take(20)
            .map { Warning("TRANSITIVE_INCOMPLETE", it) }
}
