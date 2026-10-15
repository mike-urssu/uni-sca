package sca.web

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import sca.job.JobRunner
import sca.job.JobStore
import sca.job.ScanInput

@WebMvcTest(ScanController::class, HostGuard::class)
class ScanControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var runner: JobRunner
    @MockitoBean lateinit var store: JobStore

    private fun json(body: String) = post("/api/scans").contentType(MediaType.APPLICATION_JSON).content(body)

    @Test fun `rejects foreign host and origin`() {
        mvc.perform(get("/api/scans").header("Host", "evil.com")).andExpect(status().isForbidden)
        mvc.perform(json("""{"target":"node:16-slim"}""").header("Origin", "https://evil.com")).andExpect(status().isForbidden)
        verify(runner, never()).submit(any())
    }

    @Test fun `post must be json or multipart`() {
        mvc.perform(post("/api/scans").contentType(MediaType.TEXT_PLAIN).content("x")).andExpect(status().isForbidden)
    }

    @Test fun `bad target is 400`() {
        mvc.perform(json("""{"target":"--privileged"}""")).andExpect(status().isBadRequest).andExpect(jsonPath("$.error").exists())
    }

    @Test fun `json cannot point at a tar file on the server`() {
        val tar = kotlin.io.path.createTempFile(suffix = ".tar")
        mvc.perform(json("""{"target":"$tar"}""")).andExpect(status().isBadRequest)
        verify(runner, never()).submit(any())
    }

    @Test fun `file name outside whitelist is 404`() {
        mvc.perform(get("/api/scans/20261015-000000-abcdef/files/status.json")).andExpect(status().isNotFound)
        mvc.perform(get("/api/scans/20261015-000000-abcdef/files/..%2Fstatus.json")).andExpect(status().isNotFound)
    }

    @Test fun `json submit returns 201 with id`() {
        whenever(runner.submit(ScanInput("node:16-slim", null, false))).thenReturn("20261015-000000-abcdef")
        mvc.perform(json("""{"target":"node:16-slim"}""")).andExpect(status().isCreated).andExpect(jsonPath("$.id").value("20261015-000000-abcdef"))
    }

    @Test fun `unknown job is 404`() {
        mvc.perform(get("/api/scans/20261015-000000-abcdef")).andExpect(status().isNotFound)
    }
}
