package sca.emit

import sca.model.ScanResult
import java.nio.file.Files
import java.nio.file.Path

/** One static, self-contained HTML page for human review. Every value is escaped. */
object ReportWriter {
    fun write(r: ScanResult, out: Path) {
        val cs = r.components
        fun counts(f: (sca.model.Component) -> String) = cs.groupingBy(f).eachCount().entries.sortedByDescending { it.value }
            .joinToString("") { "<li>${esc(it.key)}: ${it.value}</li>" }
        val rows = cs.sortedBy { it.key }.joinToString("\n") { c ->
            "<tr${if (c.needsReview) " class=review" else ""}><td>${esc(c.name)}</td><td>${esc(c.version)}</td><td>${esc(c.ecosystem)}</td>" +
                "<td>${esc(c.licenses?.value)}</td><td>${if (c.direct) "direct" else "transitive"}</td>" +
                "<td>${c.downloadUrl?.value?.let { "<a href=\"${esc(it)}\">${esc(it)}</a>" } ?: ""}</td>" +
                "<td>${esc(c.foundBy.sorted().joinToString(", "))}</td><td>${if (c.needsReview) "review" else ""}</td></tr>"
        }
        val html = """<!doctype html><html><head><meta charset="utf-8"><title>SBOM report: ${esc(r.target.input)}</title>
<style>body{font:14px system-ui,sans-serif;margin:24px}table{border-collapse:collapse;width:100%}td,th{border:1px solid #ccc;padding:4px 6px;text-align:left;vertical-align:top}
tr.review{background:#fff6dd}.warn{color:#a40}</style></head><body>
<h1>SBOM report</h1>
<p>Target: <b>${esc(r.target.input)}</b> ${esc(r.target.id)}<br>Status: <b>${r.status}</b> · Generated ${r.createdAt} by $TOOL_NAME</p>
<h2>Warnings</h2><ul class=warn>${r.warnings.joinToString("") { "<li>${esc(it.code)}: ${esc(it.message)}</li>" }.ifEmpty { "<li>none</li>" }}</ul>
<h2>Tools</h2><ul>${r.tools.joinToString("") { "<li>${esc(it.tool)} (${esc(it.image)}): ${if (it.ok) "ok" else "FAILED"}, ${it.millis} ms</li>" }}</ul>
<h2>Summary</h2><p>${cs.size} components: ${cs.count { it.direct }} direct, ${cs.count { !it.direct }} transitive, ${cs.count { it.needsReview }} need review</p>
<h3>By ecosystem</h3><ul>${counts { it.ecosystem }}</ul>
<h3>By license</h3><ul>${counts { it.licenses?.value ?: "(unresolved)" }}</ul>
<h2>Components</h2><input id=q placeholder="filter" size=40>
<table id=t><thead><tr><th>Name</th><th>Version</th><th>Ecosystem</th><th>License</th><th>Relation</th><th>Download URL</th><th>Found by</th><th>Review</th></tr></thead>
<tbody>
$rows
</tbody></table>
<script>document.getElementById('q').oninput=e=>{const q=e.target.value.toLowerCase();for(const tr of document.querySelectorAll('#t tbody tr'))tr.hidden=!tr.textContent.toLowerCase().includes(q)}</script>
</body></html>
"""
        Files.writeString(out, html)
    }

    private fun esc(s: String?) = (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
