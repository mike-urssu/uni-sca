package sca.merge

import org.junit.jupiter.api.Test
import sca.model.Component
import sca.model.Sourced
import sca.model.TargetKind
import sca.model.ToolRun
import kotlin.test.assertEquals

class NpmScopeTest {
    private fun c(name: String, vararg by: String, eco: String = "npm") =
        Component("$eco/$name@1", null, name, "1", eco, scope = Sourced("optional", "cdxgen"), foundBy = by.toSet())
    private fun run(tool: String, ok: Boolean = true) = ToolRun(tool, "img", ok, 0, "")
    private val cs = listOf(c("expo", "cdxgen", "syft", "trivy"), c("jest", "cdxgen"), c("guava", "cdxgen", eco = "maven"))

    @Test fun `a package syft or trivy found is runtime, cdxgen alone means dev`() {
        val r = NpmScope.apply(cs, TargetKind.REPO, listOf(run("cdxgen"), run("syft"), run("trivy")))
        assertEquals(listOf("required", "optional", "optional"), r.map { it.scope?.value })
        assertEquals("cdxgen", r[2].scope?.source)   // other ecosystems keep cdxgen's scope
    }

    @Test fun `left alone without syft and trivy, or for images`() {
        assertEquals(cs, NpmScope.apply(cs, TargetKind.REPO, listOf(run("cdxgen"), run("syft", ok = false), run("trivy", ok = false))))
        assertEquals(cs, NpmScope.apply(cs, TargetKind.IMAGE, listOf(run("syft"), run("trivy"))))
    }
}
