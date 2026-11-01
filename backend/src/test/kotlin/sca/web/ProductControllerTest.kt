package sca.web

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import sca.job.JobRunner
import sca.job.JobStore
import sca.job.ScanInput
import sca.model.Component
import sca.model.Json
import sca.model.ScanResult
import sca.model.Status
import sca.model.TargetInfo
import sca.model.TargetKind
import sca.product.CopyrightStore
import sca.product.ProductStore
import sca.product.SourceInput
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals

class ProductControllerTest {
    @TempDir lateinit var tmp: Path

    /** A runner that records each submitted scan as a job in the given state, without running anything. */
    private fun setup(state: Status): Triple<ProductController, JobStore, ProductStore> {
        val jobs = JobStore(tmp); val products = ProductStore(tmp)
        val runner = mock<JobRunner>()
        whenever(runner.submit(any())).thenAnswer { inv ->
            val input = inv.getArgument<ScanInput>(0)
            val j = jobs.create(input).copy(status = state).also(jobs::save)
            val c = Component("generic/${input.target}@1", null, input.target, "1", "generic", foundBy = setOf("syft"))   // one component per source
            if (state == Status.DONE) Files.writeString(jobs.dir(j.id).resolve("result.json"), Json.mapper.writeValueAsString(
                ScanResult(TargetInfo(TargetKind.REPO, "x", null, "sha"), Status.DONE, emptyList(), listOf(c), emptySet(), emptySet(), emptyList(), Instant.now())))
            j.id
        }
        return Triple(ProductController(products, jobs, runner, CopyrightStore(tmp)), jobs, products)
    }

    private val req = CreateProduct("Foo", "1.0", listOf(SourceInput(TargetKind.REPO, "https://github.com/a/b"), SourceInput(TargetKind.IMAGE, "nginx:1.25")))

    @Test fun `creating a product scans each source of its first version`() {
        val (c, jobs) = setup(Status.DONE)
        val p = c.create(req).body!!
        val v = p.versions.single()
        assertEquals(v.sources.map { it.id }.toSet(), jobs.list().map { it.input.sourceId }.toSet())
        assertEquals(Status.DONE, c.result(p.id, v.id).status)
        assertEquals(v.sources.map { it.target }, c.result(p.id, v.id).components.map { it.name })
        val one = v.sources.first()
        assertEquals(listOf(one.target), c.result(p.id, v.id, setOf(one.id, "unknown")).components.map { it.name })   // only the checked sources
    }

    @Test fun `renaming to another product's name merges the versions and moves their scans`() {
        val (c, jobs, products) = setup(Status.DONE)
        val a = c.create(req).body!!
        val b = c.create(req.copy(name = "Other", version = "2.0")).body!!
        val merged = c.rename(b.id, Rename("foo"))
        assertEquals(a.id, merged.id)
        assertEquals(listOf("1.0", "2.0"), merged.versions.map { it.version })
        assertEquals(null, products.get(b.id))
        assertEquals(setOf(a.id), jobs.list().map { it.input.productId }.toSet())
    }

    @Test fun `a hand-entered copyright shows in the version result`() {
        val (c, _, _) = setup(Status.DONE)
        val p = c.create(req).body!!
        val v = p.versions.single()
        val key = c.result(p.id, v.id).components.first().key
        CopyrightStore(tmp).set(key, " Copyright (c) 2024 Someone ")
        assertEquals(sca.model.Sourced("Copyright (c) 2024 Someone", "manual"), c.result(p.id, v.id).components.first { it.key == key }.copyright)
        CopyrightStore(tmp).set(key, "")   // blank clears it
        assertEquals(null, c.result(p.id, v.id).components.first { it.key == key }.copyright)
    }

    @Test fun `deleting is refused while a scan runs and removes the scans otherwise`() {
        val (busy, _, _) = setup(Status.RUNNING)
        val p = busy.create(req).body!!
        assertThrows<IllegalStateException> { busy.delete(p.id) }

        val (c, jobs, products) = setup(Status.DONE)
        val q = c.create(req.copy(name = "Bar")).body!!
        c.delete(q.id)
        assertEquals(null, products.get(q.id))
        assertEquals(emptyList(), jobs.list().filter { it.input.productId == q.id })
    }
}
