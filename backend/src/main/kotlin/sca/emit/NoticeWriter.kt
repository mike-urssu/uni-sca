package sca.emit

import org.cyclonedx.util.LicenseResolver
import sca.model.Component
import sca.model.ScanResult
import sca.model.Warning
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Open source notice as an HTML page: every shipped component with its copyright and license, then each license's full text once.
 * Left out: CI actions (pkg:github) and whatever is reachable only from development roots (a direct dependency with scope
 * optional/excluded, e.g. devDependencies). A component counts as shipped when any non-development root reaches it, or no
 * root does, so a runtime package shared with a dev tool stays in even where its own scope is wrong (e.g. cdxgen's raw npm
 * scope tracks imports; NpmScope corrects it only for repositories scanned by syft or trivy).
 */
object NoticeWriter {
    private val KEYWORDS = setOf("AND", "OR", "WITH")

    private val DEV = setOf("optional", "excluded")

    internal fun shipped(r: ScanResult): List<Component> {
        val kids = r.edges.groupBy({ it.first }, { it.second })
        fun reach(from: Collection<String>): Set<String> {
            val seen = from.toMutableSet(); val todo = ArrayDeque(from)
            while (todo.isNotEmpty()) kids[todo.removeFirst()]?.forEach { if (seen.add(it)) todo.add(it) }
            return seen
        }
        val dev = r.components.filter { it.key in r.roots && it.scope?.value in DEV }.map { it.key }.toSet()
        val prod = reach(r.roots - dev)
        val devOnly = reach(dev) - prod
        return r.components.filter { it.key !in devOnly && it.ecosystem != "github" }
    }

    /** The license ids an SPDX expression names, in order (`(MIT OR Apache-2.0) AND BSD-3-Clause` → MIT, Apache-2.0, BSD-3-Clause). */
    internal fun ids(expr: String) = expr.replace("(", " ").replace(")", " ").split(Regex("\\s+"))
        .filter { it.isNotEmpty() && it !in KEYWORDS }.map { it.removeSuffix("+") }.distinct()

    private fun text(id: String, c: Map<String, String>) =
        c[id] ?: LicenseResolver::class.java.getResource("/licenses/$id.txt")?.readText()?.trim()

    private fun anchor(id: String) = "lic-" + id.replace(Regex("[^A-Za-z0-9.-]"), "-")

    /** A LicenseRef-<hash> reads as the license name the scanner reported; SPDX ids as themselves. */
    private fun label(id: String, texts: Map<String, String>) = esc(texts[id]?.takeIf { id.startsWith("LicenseRef-") } ?: id)

    /** The expression with each license id linked to its text further down; operators and parentheses stay plain. */
    private fun linked(expr: String, texts: Map<String, String>) = Regex("""[()]|\s+|[^()\s]+""").findAll(expr).joinToString("") { m ->
        val t = m.value
        if (t.isBlank() || t == "(" || t == ")") t else if (t in KEYWORDS) "<span class=op>$t</span>"
        else "<a href=\"#${anchor(t.removeSuffix("+"))}\">${label(t, texts)}</a>"
    }

    /** One self-contained HTML page (inline styles, no scripts); every scan-derived value is escaped. */
    fun write(r: ScanResult, out: Path): List<Warning> {
        val cs = shipped(r).sortedWith(compareBy({ it.name.lowercase() }, { it.version }))
        val (known, unknown) = cs.partition { it.licenses != null }
        val byId = known.flatMap { c -> ids(c.licenses!!.value).map { it to c } }.groupBy({ it.first }, { it.second })
            .mapValues { it.value.distinct() }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        val texts = cs.flatMap { it.licenseTexts.entries }.associate { it.toPair() }
        val title = "${r.target.input} ${r.target.id ?: ""}".trim()
        val date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(r.createdAt)
        fun nv(c: Component) = "${esc(c.name)}${c.version?.let { " ${esc(it)}" } ?: ""}"
        fun row(c: Component) = "<tr><td><b>${esc(c.name)}</b>" +
            (c.copyright?.let { "<div class=copy>${esc(it.value)}</div>" } ?: "") +
            "</td><td class=ver>${esc(c.version)}</td><td>${c.licenses?.let { linked(it.value, texts) } ?: "<span class=muted>Not identified</span>"}</td></tr>"
        val html = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Open Source Notice — ${esc(title)}</title>
<style>
:root { --text: #101828; --muted: #667085; --line: #eaecf0; --soft: #f9fafb; --accent: #444ce7; color-scheme: light dark; }
@media (prefers-color-scheme: dark) { :root { --text: #f2f4f7; --muted: #98a2b3; --line: #2b3240; --soft: #161b26; --accent: #8098f9; } body { background: #0c111d; } }
* { box-sizing: border-box; }
body { margin: 0; font: 15px/1.55 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Noto Sans KR", sans-serif; color: var(--text); }
main { max-width: 960px; margin: 0 auto; padding: 48px 24px 80px; }
.eyebrow { margin: 0; font-size: 12px; font-weight: 600; letter-spacing: .08em; text-transform: uppercase; color: var(--accent); }
h1 { margin: 6px 0 4px; font-size: 30px; line-height: 1.2; }
h2 { margin: 48px 0 12px; font-size: 20px; padding-bottom: 8px; border-bottom: 1px solid var(--line); }
h3 { margin: 0 0 4px; font-size: 17px; }
.meta, .muted, .op { color: var(--muted); }
.intro { margin: 20px 0 0; padding: 14px 16px; background: var(--soft); border-radius: 10px; }
a { color: var(--accent); text-decoration: none; } a:hover { text-decoration: underline; }
.toc { display: flex; flex-wrap: wrap; gap: 8px; padding: 0; margin: 0; list-style: none; }
.toc a { display: inline-flex; gap: 6px; padding: 4px 10px; border: 1px solid var(--line); border-radius: 999px; font-size: 13px; }
.toc span { color: var(--muted); }
table { width: 100%; border-collapse: collapse; font-size: 14px; }
th { text-align: left; font-size: 12px; font-weight: 500; color: var(--muted); padding: 8px 12px; border-bottom: 1px solid var(--line); }
td { padding: 10px 12px; border-bottom: 1px solid var(--line); vertical-align: top; }
td.ver { white-space: nowrap; color: var(--muted); }
.copy { margin-top: 2px; font-size: 12px; color: var(--muted); white-space: pre-line; }
article { padding: 20px 0; border-bottom: 1px solid var(--line); scroll-margin-top: 16px; }
article details { margin: 4px 0 12px; font-size: 13px; color: var(--muted); }
article details summary { cursor: pointer; }
pre { margin: 0; padding: 16px; background: var(--soft); border-radius: 10px; font: 12.5px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
@media print { main { padding: 0; } h2 { break-after: avoid; } article { break-inside: auto; } .toc a { border: none; padding: 0 6px 0 0; } }
</style></head><body><main>
<header>
<p class="eyebrow">Open Source Software Notice</p>
<h1>${esc(title)}</h1>
<p class="meta">Generated $date by $TOOL_NAME · ${cs.size} components · ${byId.size} licenses</p>
</header>
<p class="intro">This product includes the open source software listed below, each used under the license shown with it. The full text of every license follows the list. Development and test dependencies, which are not distributed with the product, are not listed.</p>
<h2>Licenses</h2>
<ul class="toc">${byId.entries.joinToString("") { (id, users) -> "<li><a href=\"#${anchor(id)}\">${label(id, texts)} <span>${users.size}</span></a></li>" }}</ul>
<h2>Components</h2>
<table><thead><tr><th>Component</th><th>Version</th><th>License</th></tr></thead><tbody>
${cs.joinToString("\n") { row(it) }}
</tbody></table>
${if (unknown.isEmpty()) "" else "<p class=muted>${unknown.size} component(s) have no identified license yet.</p>"}
<h2>License texts</h2>
${byId.entries.joinToString("\n") { (id, users) ->
            "<article id=\"${anchor(id)}\"><h3>${label(id, texts)}</h3><details><summary>Used by ${users.size} component(s)</summary>${users.joinToString(", ") { nv(it) }}</details>" +
                "<pre>${esc(text(id, texts) ?: "License text not available. See https://spdx.org/licenses/$id.html")}</pre></article>"
        }}
</main></body></html>
"""
        Files.writeString(out, html)
        return emptyList()
    }
}
