package sca.job

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import sca.model.Status
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JobsTest {
    @TempDir lateinit var tmp: Path

    @Test fun `interrupted jobs become FAILED on startup`() {
        val store = JobStore(tmp)
        val s = store.create(ScanInput("node:16-slim"))
        store.save(s.copy(status = Status.RUNNING, stage = "scan"))
        store.failInterrupted()
        assertEquals(Status.FAILED, store.get(s.id)!!.status)
        assertEquals("server restarted", store.get(s.id)!!.error)
    }

    @Test fun `list is newest first and unknown or malicious ids are null`() {
        val store = JobStore(tmp)
        val a = store.create(ScanInput("a:1")); Thread.sleep(1100); val b = store.create(ScanInput("b:1"))
        assertEquals(listOf(b.id, a.id), store.list().map { it.id })
        assertNull(store.get("../../etc"))
        assertNull(store.get("20990101-000000-zzzzzz"))
    }
}
