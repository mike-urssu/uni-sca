import { useMemo, useState } from 'react'
import { children } from '../graph'
import type { Component, ScanResult } from '../types'

const PAGE = 500

interface RowProps {
  k: string; depth: number; ancestors: Set<string>
  kids: Map<string, string[]>; byKey: Map<string, Component>; onSelect: (key: string) => void
}

function Cells({ c }: { c: Component }) {
  return <>
    <td>{c.version}</td><td>{c.ecosystem}</td><td>{c.licenses?.value ?? <span className="muted">unresolved</span>}</td>
    <td>{c.direct ? 'direct' : 'transitive'}</td><td>{c.scope?.value ?? ''}</td>
    <td>{c.downloadUrl ? <a href={c.downloadUrl.value} onClick={(e) => e.stopPropagation()} target="_blank" rel="noreferrer">{c.downloadUrl.value}</a> : <span className="muted">unresolved</span>}</td>
    <td>{c.supplier?.value ?? ''}</td><td>{c.foundBy.join(', ')}</td><td>{c.needsReview ? '⚠' : ''}</td>
  </>
}

/** One component and, once expanded, the components it depends on (rendered only when opened). */
function TreeRow({ k, depth, ancestors, kids, byKey, onSelect }: RowProps) {
  const [open, setOpen] = useState(false)
  const c = byKey.get(k)
  const cycle = ancestors.has(k)
  const next = cycle ? [] : (kids.get(k) ?? [])
  if (!c) return null
  return <>
    <tr className={`clickable${c.needsReview ? ' review' : ''}`} onClick={() => onSelect(k)}>
      <td style={{ paddingLeft: 8 + depth * 18 }}>
        <span className="toggle" onClick={(e) => { e.stopPropagation(); if (next.length) setOpen(!open) }}>
          {cycle ? '↺' : next.length ? (open ? '▾' : '▸') : '·'}
        </span>{' '}
        {c.name}{cycle && <span className="muted"> (cycle)</span>}
        {next.length > 0 && !open && <span className="muted"> · {next.length}</span>}
      </td>
      <Cells c={c} />
    </tr>
    {open && next.map((n) => <TreeRow key={n} k={n} depth={depth + 1} ancestors={new Set([...ancestors, k])} kids={kids} byKey={byKey} onSelect={onSelect} />)}
  </>
}

export function ComponentTable({ result, byKey, onSelect }: { result: ScanResult; byKey: Map<string, Component>; onSelect: (key: string) => void }) {
  const components = result.components
  const [q, setQ] = useState('')
  const [eco, setEco] = useState('')
  const [rel, setRel] = useState<'' | 'direct' | 'transitive'>('')
  const [reviewOnly, setReviewOnly] = useState(false)
  const [shown, setShown] = useState(PAGE)

  const kids = useMemo(() => children(result), [result])
  const ecosystems = useMemo(() => [...new Set(components.map((c) => c.ecosystem))].sort(), [components])
  const byName = (a: Component, b: Component) => a.name.localeCompare(b.name)
  // no filter: direct components as a tree; any filter: a flat list of every match, wherever it sits in the graph
  const filtering = q !== '' || eco !== '' || rel !== '' || reviewOnly
  const rows = useMemo(() => {
    if (!filtering) return components.filter((c) => c.direct).sort(byName)
    const needle = q.toLowerCase()
    return components
      .filter((c) => !eco || c.ecosystem === eco)
      .filter((c) => !rel || (rel === 'direct') === c.direct)
      .filter((c) => !reviewOnly || c.needsReview)
      .filter((c) => !needle || [c.name, c.version, c.purl, c.licenses?.value, c.supplier?.value].some((v) => v?.toLowerCase().includes(needle)))
      .sort(byName)
  }, [components, filtering, q, eco, rel, reviewOnly])

  return (
    <>
      <div className="row" style={{ marginBottom: 8, alignItems: 'center' }}>
        <input type="text" placeholder="Search name, version, purl, license, supplier" value={q} onChange={(e) => { setQ(e.target.value); setShown(PAGE) }} style={{ maxWidth: 360 }} />
        <select value={eco} onChange={(e) => setEco(e.target.value)}><option value="">All ecosystems</option>{ecosystems.map((e) => <option key={e}>{e}</option>)}</select>
        <select value={rel} onChange={(e) => setRel(e.target.value as typeof rel)}><option value="">Direct + transitive</option><option value="direct">Direct</option><option value="transitive">Transitive</option></select>
        <label><input type="checkbox" checked={reviewOnly} onChange={(e) => setReviewOnly(e.target.checked)} /> Needs review only</label>
        <span className="muted">{filtering ? `${rows.length} matching` : `${rows.length} direct · expand ▸ to see what each one pulls in`}</span>
      </div>
      <div className="table-wrap"><table>
        <thead><tr><th>Name</th><th>Version</th><th>Ecosystem</th><th>License</th><th>Relation</th><th>Scope</th><th>Download URL</th><th>Supplier</th><th>Found by</th><th>Review</th></tr></thead>
        <tbody>
          {filtering
            ? rows.slice(0, shown).map((c) => (
              <tr key={c.key} className={`clickable${c.needsReview ? ' review' : ''}`} onClick={() => onSelect(c.key)}><td>{c.name}</td><Cells c={c} /></tr>))
            : rows.slice(0, shown).map((c) => <TreeRow key={c.key} k={c.key} depth={0} ancestors={new Set()} kids={kids} byKey={byKey} onSelect={onSelect} />)}
        </tbody>
      </table></div>
      {rows.length > shown && <button onClick={() => setShown(shown + PAGE)} style={{ marginTop: 8 }}>Show more ({rows.length - shown} left)</button>}
    </>
  )
}
