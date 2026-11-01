package sca.product

import sca.acquire.InvalidInput
import sca.model.Json
import sca.model.ScanResult
import sca.model.Sourced
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Copyright statements entered by hand, keyed by component key (type/namespace/name@version).
 * Shared by every product: who holds a package version's copyright doesn't depend on who ships it.
 */
class CopyrightStore(dataDir: Path) {
    private val file = dataDir.resolve("copyrights.json")

    @Synchronized fun all(): Map<String, String> = if (Files.exists(file)) Json.mapper.readValue(file.toFile()) else emptyMap()

    /** A blank text removes the entry, so the scanned value (if any) shows again. */
    @Synchronized fun set(key: String, text: String) {
        if (key.isBlank() || key.length > 512) throw InvalidInput("invalid component key")
        if (text.length > 10_000) throw InvalidInput("copyright text is too long")
        val m = all().toMutableMap()
        if (text.isBlank()) m.remove(key) else m[key] = text.trim()
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling("copyrights.json.tmp")
        Files.writeString(tmp, Json.mapper.writeValueAsString(m))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** A hand-entered statement wins over the scanned one. */
    fun apply(r: ScanResult): ScanResult {
        val m = all().ifEmpty { return r }
        return r.copy(components = r.components.map { c -> m[c.key]?.let { c.copy(copyright = Sourced(it, "manual")) } ?: c })
    }
}
