import { pathTo } from '../graph'
import type { Component, ScanResult, Sourced } from '../types'

interface Props { component: Component; result: ScanResult; byKey: Map<string, Component>; onClose: () => void; onSelect: (key: string) => void }

function Field({ label, v, link }: { label: string; v: Sourced<string> | null; link?: boolean }) {
  return <>
    <dt>{label}</dt>
    <dd>{v ? <>{link ? <a href={v.value} target="_blank" rel="noreferrer">{v.value}</a> : v.value} <span className="badge">{v.source}</span></> : <span className="muted">—</span>}</dd>
  </>
}

export function ComponentDetail({ component: c, result, byKey, onClose, onSelect }: Props) {
  const path = pathTo(result, c.key)
  return (
    <aside className="detail">
      <button onClick={onClose} style={{ float: 'right' }}>Close</button>
      <h3>{c.name} <span className="muted">{c.version}</span></h3>
      <dl>
        <dt>purl</dt><dd><code>{c.purl ?? '—'}</code></dd>
        <dt>Relation</dt><dd>{c.direct ? 'direct' : 'transitive'}</dd>
        <dt>Included via</dt>
        <dd>{path ? path.map((k, i) => <span key={k}>{i > 0 && ' → '}<a href="#" onClick={(e) => { e.preventDefault(); onSelect(k) }}>{byKey.get(k)?.name ?? k}</a></span>)
          : <span className="muted">not reachable from the project's direct dependencies</span>}</dd>
        <Field label="License" v={c.licenses} />
        <Field label="Download URL" v={c.downloadUrl} link />
        <Field label="Registry" v={c.registry} />
        <Field label="Supplier" v={c.supplier} />
        <Field label="Source repository" v={c.sourceRepo} />
        <Field label="Scope" v={c.scope} />
        <dt>Hashes</dt>
        <dd>{c.hashes.length ? c.hashes.map((h) => <div key={h.value.alg}><code>{h.value.alg}: {h.value.content}</code> <span className="badge">{h.source}</span></div>) : <span className="muted">—</span>}</dd>
        <dt>Found by</dt><dd>{c.foundBy.join(', ')}</dd>
        {c.conflicts.length > 0 && <><dt>Conflicts</dt><dd><ul>{c.conflicts.map((x, i) => <li key={i}>{x.field}: {x.source} says <code>{x.value}</code></li>)}</ul></dd></>}
        {c.unresolved.length > 0 && <><dt>Unresolved</dt><dd>{c.unresolved.join(', ')}</dd></>}
        {c.locations.length > 0 && <><dt>Locations</dt><dd>{c.locations.map((l) => <div key={l}><code>{l}</code></div>)}</dd></>}
        {Object.keys(c.licenseTexts).length > 0 && <><dt>License texts</dt><dd>{Object.entries(c.licenseTexts).map(([k, t]) => <details key={k}><summary>{k}</summary><pre style={{ whiteSpace: 'pre-wrap' }}>{t}</pre></details>)}</dd></>}
      </dl>
    </aside>
  )
}
