package sca.emit

import sca.model.ScanResult
import sca.model.Warning

/** NTIA minimum elements per component. Author and timestamp are always written by the emitters, so not checked here. */
object NtiaValidator {
    fun check(r: ScanResult): List<Warning> {
        val missing = mapOf(
            "name" to r.components.count { it.name.isBlank() },
            "version" to r.components.count { it.version.isNullOrBlank() },
            "unique identifier (purl)" to r.components.count { it.purl == null },
            "supplier" to r.components.count { it.supplier == null },
        ).filterValues { it > 0 }.map { (f, n) -> Warning("NTIA_MISSING", "$f missing for $n components") }
        val graph = if (r.components.size >= 2 && r.edges.isEmpty() && r.roots.isEmpty())
            listOf(Warning("NTIA_MISSING", "dependency relationships missing")) else emptyList()
        return missing + graph
    }
}
