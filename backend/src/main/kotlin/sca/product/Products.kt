package sca.product

import sca.acquire.InvalidInput
import sca.acquire.Target
import sca.acquire.parseTarget
import sca.model.Json
import sca.model.TargetKind
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/** A scan target of one product version. Never edited once created: delete and re-add instead, so scan history matches it. */
data class Source(val id: String, val kind: TargetKind, val target: String, val ref: String? = null, val deep: Boolean = false)
data class ProductVersion(val id: String, val version: String, val createdAt: Instant, val sources: List<Source>)
data class Product(val id: String, val name: String, val createdAt: Instant, val versions: List<ProductVersion> = emptyList()) {
    fun version(vid: String) = versions.firstOrNull { it.id == vid } ?: throw NoSuchElementException("version $vid")
}

data class SourceInput(val kind: TargetKind, val target: String, val ref: String? = null, val deep: Boolean = false)

private val ID = Regex("^[a-z0-9]{8}$")
fun newId() = (1..8).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")

/** Validates a source against its declared kind and stores the canonical target (e.g. `github.com/a/b` → https URL). */
fun source(s: SourceInput): Source {
    val ref = s.ref?.trim()?.ifEmpty { null }
    if (s.kind == TargetKind.IMAGE && ref != null) throw InvalidInput("a branch or tag only applies to a repository")
    return when (val t = parseTarget(s.target, ref)) {
        is Target.Repo -> if (s.kind == TargetKind.REPO) Source(newId(), TargetKind.REPO, t.url, t.ref, s.deep) else null
        is Target.ImageRef -> if (s.kind == TargetKind.IMAGE) Source(newId(), TargetKind.IMAGE, t.ref) else null
        is Target.ImageTar -> null
    } ?: throw InvalidInput("not a ${if (s.kind == TargetKind.REPO) "GitHub repository URL" else "docker image reference"}: ${s.target}")
}

/** One JSON file per product under <dataDir>/products. */
class ProductStore(dataDir: Path) {
    private val dir = dataDir.resolve("products")

    fun list(): List<Product> = if (!Files.exists(dir)) emptyList() else
        Files.list(dir).use { s -> s.map { it.fileName.toString().removeSuffix(".json") }.toList() }.mapNotNull(::get).sortedBy { it.name.lowercase() }

    fun get(id: String): Product? {
        if (!ID.matches(id)) return null   // ids become paths
        val f = dir.resolve("$id.json")
        return if (Files.exists(f)) Json.mapper.readValue<Product>(f.toFile()) else null
    }

    // ponytail: one lock for every product write; per-product locks if concurrent edits ever matter
    fun byName(name: String): Product? = list().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    /** Registers a version: under the product of that name when one exists, otherwise under a new product. The new version is last. */
    @Synchronized fun create(name: String, version: String, sources: List<SourceInput>): Product {
        val v = ProductVersion(newId(), version, Instant.now(), sources.map(::source))
        val existing = byName(name)
        return save(existing?.copy(versions = existing.versions + v) ?: Product(newId(), name, Instant.now(), listOf(v)))
    }

    /** Applies f to the stored product and saves the result after validating it. */
    @Synchronized fun update(id: String, f: (Product) -> Product): Product = save(f(get(id) ?: throw NoSuchElementException("product $id")))

    @Synchronized fun delete(id: String) { if (get(id) != null) Files.delete(dir.resolve("$id.json")) }

    /** Moves every version of `from` into `into` (kept in registration order), then removes `from`. */
    @Synchronized fun merge(from: String, into: String): Product {
        val f = get(from) ?: throw NoSuchElementException("product $from")
        val t = get(into) ?: throw NoSuchElementException("product $into")
        return save(t.copy(versions = (t.versions + f.versions).sortedBy { it.createdAt }), ignore = from).also { delete(from) }
    }

    /** ignore: a product about to be removed, which may still hold the name. */
    private fun save(p: Product, ignore: String? = null): Product {
        val name = p.name.trim()
        if (name.isEmpty() || name.length > 100) throw InvalidInput("product name must be 1-100 characters")
        // the name is the product's identity (ignoring case): registering it again adds a version instead
        if (list().any { it.id != p.id && it.id != ignore && it.name.equals(name, ignoreCase = true) }) throw InvalidInput("product \"$name\" already exists")
        val versions = p.versions.map { it.copy(version = it.version.trim()) }
        versions.forEach { v ->
            if (v.version.isEmpty() || v.version.length > 100) throw InvalidInput("version must be 1-100 characters")
            if (versions.count { it.version.equals(v.version, ignoreCase = true) } > 1) throw InvalidInput("$name ${v.version} already exists")
        }
        val saved = p.copy(name = name, versions = versions)
        Files.createDirectories(dir)
        val tmp = dir.resolve("${p.id}.json.tmp")
        Files.writeString(tmp, Json.mapper.writeValueAsString(saved))
        Files.move(tmp, dir.resolve("${p.id}.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return saved
    }
}
