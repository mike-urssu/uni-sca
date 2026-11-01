package sca.product

import sca.model.Component
import sca.model.Conflict
import sca.model.ScanResult
import sca.model.Status
import sca.model.TargetInfo
import sca.model.TargetKind
import sca.model.Warning
import java.time.Instant

/**
 * One result for a product version: each source's latest successful scan (null when it has none), merged by component key.
 * The earlier source wins a field; a differing license from a later one becomes a conflict and puts the component up for review.
 */
fun combine(name: String, version: String, parts: List<Pair<Source, ScanResult?>>): ScanResult {
    val found = parts.mapNotNull { it.second }
    val byKey = LinkedHashMap<String, Component>()
    found.forEach { r -> r.components.forEach { c -> byKey.merge(c.key, c, ::mergeComponent) } }
    val warnings = parts.flatMap { (s, r) ->
        r?.warnings?.map { it.copy(message = "${s.target}: ${it.message}") } ?: listOf(Warning("SOURCE_NOT_SCANNED", "${s.target} has no successful scan"))
    }
    val status = when {
        parts.all { it.second?.status == Status.DONE } -> Status.DONE
        found.isEmpty() -> Status.FAILED
        else -> Status.PARTIAL
    }
    return ScanResult(TargetInfo(TargetKind.PRODUCT, name, null, version), status, found.flatMap { it.tools }.distinctBy { it.tool to it.image },
        byKey.values.toList(), found.flatMap { it.edges }.toSet(), found.flatMap { it.roots }.toSet(), warnings, Instant.now())
}

private fun mergeComponent(a: Component, b: Component): Component {
    val licenseConflict = b.licenses?.takeIf { a.licenses != null && it.value != a.licenses.value }?.let { Conflict("licenses", it.value, it.source) }
    val conflicts = (a.conflicts + b.conflicts + listOfNotNull(licenseConflict)).distinct()
    return a.copy(
        licenses = a.licenses ?: b.licenses, hashes = (a.hashes + b.hashes).distinctBy { it.value },
        scope = a.scope ?: b.scope, downloadUrl = a.downloadUrl ?: b.downloadUrl, registry = a.registry ?: b.registry,
        supplier = a.supplier ?: b.supplier, sourceRepo = a.sourceRepo ?: b.sourceRepo, copyright = a.copyright ?: b.copyright,
        qualifiers = b.qualifiers + a.qualifiers, locations = a.locations + b.locations, foundBy = a.foundBy + b.foundBy,
        direct = a.direct || b.direct, conflicts = conflicts,
        unresolved = a.unresolved intersect b.unresolved,   // a field one source left unresolved was filled from the other
        needsReview = a.needsReview || b.needsReview || licenseConflict != null,
        licenseTexts = b.licenseTexts + a.licenseTexts,
    )
}

enum class Change { ADDED, REMOVED, VERSION_CHANGED, LICENSE_CHANGED }
data class DiffRow(
    val id: String, val name: String, val ecosystem: String, val change: Change,
    val before: List<String>, val after: List<String>, val licenseBefore: String?, val licenseAfter: String?,
)

/** A package's identity across versions: its merge key without the version (`npm/@s/x@1.0` → `npm/@s/x`). */
internal fun identity(key: String) = if ('@' in key.substringAfterLast('/')) key.substringBeforeLast('@') else key

/** What changed from a to b, package by package. A package present in several versions compares as a set of versions. */
fun compare(a: ScanResult, b: ScanResult): List<DiffRow> {
    val before = a.components.groupBy { identity(it.key) }
    val after = b.components.groupBy { identity(it.key) }
    fun versions(cs: List<Component>?) = cs.orEmpty().map { it.version ?: "" }.distinct().sorted()
    fun licenses(cs: List<Component>?) = cs?.mapNotNull { it.licenses?.value }?.distinct()?.sorted()?.joinToString(", ")?.ifEmpty { null }
    return (before.keys + after.keys).mapNotNull { id ->
        val x = before[id]; val y = after[id]
        val change = when {
            x == null -> Change.ADDED
            y == null -> Change.REMOVED
            versions(x) != versions(y) -> Change.VERSION_CHANGED
            licenses(x) != licenses(y) -> Change.LICENSE_CHANGED
            else -> return@mapNotNull null
        }
        val c = (y ?: x)!!.first()
        DiffRow(id, c.name, c.ecosystem, change, versions(x), versions(y), licenses(x), licenses(y))
    }.sortedWith(compareBy({ it.change }, { it.name.lowercase() }, { it.id }))
}
