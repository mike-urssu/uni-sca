package sca.web

import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import sca.job.JobRunner
import sca.job.JobStore
import sca.job.ScanInput

@WebMvcTest(ScanController::class, HostGuard::class, properties = ["sca.public-host=sca.example.com"])
class ScanControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var runner: JobRunner
    @MockitoBean lateinit var store: JobStore
    @MockitoBean lateinit var copyrights: sca.product.CopyrightStore

    @Test fun `rejects foreign host and origin`() {
        mvc.perform(get("/api/scans/20261015-000000-abcdef").header("Host", "evil.com")).andExpect(status().isForbidden)
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content("{}").header("Origin", "https://evil.com")).andExpect(status().isForbidden)
    }

    @Test fun `allows the public host`() {
        mvc.perform(get("/api/scans/20261015-000000-abcdef/files/status.json").header("Host", "sca.example.com")
            .header("Origin", "https://sca.example.com")).andExpect(status().isNotFound)
    }

    @Test fun `post must be json`() {
        mvc.perform(post("/api/products").contentType(MediaType.TEXT_PLAIN).content("x")).andExpect(status().isForbidden)
        mvc.perform(post("/api/products").contentType(MediaType.MULTIPART_FORM_DATA).content("x")).andExpect(status().isForbidden)
    }

    @Test fun `file name outside whitelist is 404`() {
        mvc.perform(get("/api/scans/20261015-000000-abcdef/files/status.json")).andExpect(status().isNotFound)
        mvc.perform(get("/api/scans/20261015-000000-abcdef/files/..%2Fstatus.json")).andExpect(status().isNotFound)
    }

    @Test fun `report is served sandboxed`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        val id = "20261015-000000-abcdef"
        java.nio.file.Files.writeString(dir.resolve("report.html"), "<html></html>")
        whenever(store.get(id)).thenReturn(sca.job.JobStatus(id, sca.model.Status.DONE, null, ScanInput("x:1"), java.time.Instant.now()))
        whenever(store.dir(id)).thenReturn(dir)
        mvc.perform(get("/api/scans/$id/files/report.html"))
            .andExpect(status().isOk)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Security-Policy", "sandbox allow-downloads"))
    }

    @Test fun `raw zip contains regular files only`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        val raw = java.nio.file.Files.createDirectories(dir.resolve("raw/trivy"))
        java.nio.file.Files.writeString(dir.resolve("raw/cdxgen.log"), "log")
        java.nio.file.Files.writeString(dir.resolve("secret"), "host file")
        java.nio.file.Files.createSymbolicLink(raw.resolve("trivy.json"), dir.resolve("secret"))
        val out = java.io.ByteArrayOutputStream()
        zipRegularFiles(dir.resolve("raw"), out)
        val names = java.util.zip.ZipInputStream(out.toByteArray().inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        kotlin.test.assertEquals(listOf("cdxgen.log"), names)
    }

    @Test fun `unknown job is 404`() {
        mvc.perform(get("/api/scans/20261015-000000-abcdef")).andExpect(status().isNotFound)
    }
}
