package sca.cli

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import sca.job.JobRunner
import kotlin.test.assertTrue

/** `sca scan` starts Spring without a web server; nothing web-only may be required there. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CliContextTest {
    @Autowired lateinit var ctx: ApplicationContext

    @Test fun `cli context starts without web beans`() {
        ctx.getBean(CliRunner::class.java)
        assertTrue(ctx.getBeanNamesForType(JobRunner::class.java).isEmpty())
    }
}
