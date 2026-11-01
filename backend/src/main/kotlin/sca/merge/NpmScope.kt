package sca.merge

import sca.model.Component
import sca.model.Sourced
import sca.model.TargetKind
import sca.model.ToolRun

/**
 * cdxgen's npm scope isn't "runtime vs dev": it marks `required` only what it sees imported in the source and `optional`
 * everything else, runtime dependencies included. syft and trivy read the same lockfile but skip its dev packages, so for
 * a repository the honest signal is who found the package: syft or trivy → required, cdxgen alone → optional (dev only).
 * Applied only when syft or trivy actually ran; without them every package would look dev-only.
 */
object NpmScope {
    private val PROD_TOOLS = setOf("syft", "trivy")

    fun apply(cs: List<Component>, kind: TargetKind, tools: List<ToolRun>): List<Component> {
        if (kind != TargetKind.REPO || tools.none { it.ok && it.tool in PROD_TOOLS }) return cs
        return cs.map { c ->
            if (c.ecosystem != "npm") c
            else c.copy(scope = Sourced(if (c.foundBy.any { it in PROD_TOOLS }) "required" else "optional", "lockfile"))
        }
    }
}
