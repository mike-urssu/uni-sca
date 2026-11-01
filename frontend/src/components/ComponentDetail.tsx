import { useEffect, useState } from 'react'
import { pathTo } from '../graph'
import { setCopyright } from '../api'
import { PencilIcon } from './Icons'
import type { Component, ScanResult, Sourced } from '../types'
import { DepGraph } from './DepGraph'

interface Props {
  component: Component; graphRoot: string; result: ScanResult; byKey: Map<string, Component>
  onClose: () => void; onSelect: (key: string) => void; onChanged?: () => void
}

function Field({ label, v, link }: { label: string; v: Sourced<string> | null; link?: boolean }) {
  return <>
    <dt>{label}</dt>
    <dd>{v ? <>{link ? <a href={v.value} target="_blank" rel="noreferrer">{v.value}</a> : v.value} <span className="badge">{v.source}</span></> : <span className="muted">—</span>}</dd>
  </>
}

/** Scanned copyright statements, or a hand-entered text that replaces them for this package version in every product. */
function Copyright({ c, onChanged }: { c: Component; onChanged?: () => void }) {
  const [draft, setDraft] = useState<string | null>(null)   // null when not editing
  const [error, setError] = useState<string | null>(null)
  const save = (text: string) => setCopyright(c.key, text).then(() => { setDraft(null); setError(null); onChanged?.() }, (e) => setError(e.message))
  return <>
    <dt>Copyright</dt>
    <dd>
      {draft === null ? <div className="copyright">
        {c.copyright ? <><pre>{c.copyright.value}</pre> <span className="badge">{c.copyright.source}</span></> : <span className="muted">—</span>}
        <button className="icon sm" onClick={() => setDraft(c.copyright?.value ?? '')} aria-label="Edit copyright" title="Edit"><PencilIcon size={14} /></button>
      </div> : <form onSubmit={(e) => { e.preventDefault(); save(draft) }}>
        <textarea aria-label="Copyright" rows={4} autoFocus value={draft} placeholder="Copyright (c) 2024 Example Corp."
          onChange={(e) => setDraft(e.target.value)} onKeyDown={(e) => { if (e.key === 'Escape') { e.stopPropagation(); setDraft(null) } }} />
        <p className="muted">Applies to {c.name} {c.version} in every product. One statement per line.</p>
        <div className="row">
          <button>Save</button>
          <button type="button" className="secondary" onClick={() => setDraft(null)}>Cancel</button>
          {c.copyright?.source === 'manual' && <button type="button" className="secondary" onClick={() => save('')}>Use scanned value</button>}
        </div>
        {error && <p className="error">{error}</p>}
      </form>}
    </dd>
  </>
}

export function ComponentDetail({ component: c, graphRoot, result, byKey, onClose, onSelect, onChanged }: Props) {
  // Esc closes the panel; an open copyright editor takes the first Esc for itself (it stops propagation)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !e.defaultPrevented) onClose() }
    addEventListener('keydown', onKey)
    return () => removeEventListener('keydown', onKey)
  }, [onClose])
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
        {/* keyed by component so an open editor doesn't carry over to the next selection */}
        <Copyright key={c.key} c={c} onChanged={onChanged} />
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
      <h3>Dependencies of {byKey.get(graphRoot)?.name ?? graphRoot}</h3>
      <DepGraph result={result} root={graphRoot} byKey={byKey} selected={c.key} onSelect={onSelect} />
    </aside>
  )
}
