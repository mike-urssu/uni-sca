package sca.model

import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.time.Instant

data class Hash(val alg: String, val content: String)          // alg: "SHA-512", "SHA-256", "SHA-1", "MD5"
data class Sourced<T>(val value: T, val source: String, val evidence: String? = null)
data class Conflict(val field: String, val value: String, val source: String)

/** One row of one tool's output. `ref` is that tool's own id (bom-ref, syft id). */
data class RawComponent(
    val tool: String, val ref: String, val purl: String?, val name: String, val version: String?,
    val licenses: List<String> = emptyList(), val hashes: List<Hash> = emptyList(),
    val downloadUrl: String? = null, val supplier: String? = null, val sourceRepo: String? = null,
    val scope: String? = null, val locations: Set<String> = emptySet(),
    val upstream: String? = null,        // source package name of an OS binary package
)

data class RawResult(
    val tool: String, val components: List<RawComponent>,
    val edges: Set<Pair<String, String>>,   // ref → ref (first depends on second)
    val roots: Set<String>?,                // direct dependencies of the project; null when the tool doesn't know
    val project: String? = null,            // purl of the scanned project itself, when the tool reports it
)

data class NormalizedResult(
    val tool: String, val components: Map<String, RawComponent>, // key → component
    val edges: Set<Pair<String, String>>, val roots: Set<String>?, val project: String? = null, // project: key
)

data class Component(
    val key: String, val purl: String?, val name: String, val version: String?, val ecosystem: String,
    val qualifiers: Map<String, String> = emptyMap(),
    val licenses: Sourced<String>? = null, val hashes: List<Sourced<Hash>> = emptyList(),
    val scope: Sourced<String>? = null, val downloadUrl: Sourced<String>? = null,
    val registry: Sourced<String>? = null, val supplier: Sourced<String>? = null,
    val sourceRepo: Sourced<String>? = null, val locations: Set<String> = emptySet(),
    val foundBy: Set<String>, val direct: Boolean = false,
    val conflicts: List<Conflict> = emptyList(), val unresolved: Set<String> = emptySet(),
    val needsReview: Boolean = false, val licenseTexts: Map<String, String> = emptyMap(), // LicenseRef-* → original text
)

enum class Status { QUEUED, RUNNING, DONE, PARTIAL, FAILED }
enum class TargetKind { REPO, IMAGE }

data class TargetInfo(val kind: TargetKind, val input: String, val ref: String?, val id: String?) // id: commit SHA or sha256 digest
data class ToolRun(val tool: String, val image: String, val ok: Boolean, val millis: Long, val log: String)
// code: TOOL_FAILED, TRANSITIVE_INCOMPLETE, NTIA_MISSING, SCHEMA_INVALID, NO_COMPONENTS
data class Warning(val code: String, val message: String)

data class ScanResult(
    val target: TargetInfo, val status: Status, val tools: List<ToolRun>,
    val components: List<Component>, val edges: Set<Pair<String, String>>,
    val roots: Set<String>, val warnings: List<Warning>, val createdAt: Instant,
)

object Json {
    val mapper: JsonMapper = jacksonMapperBuilder().build()
}
