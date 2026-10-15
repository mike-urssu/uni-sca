import { useMemo, useState } from 'react'
import type { Component } from '../types'

const PAGE = 500

export function ComponentTable({ components, onSelect }: { components: Component[]; onSelect: (c: Component) => void }) {
  const [q, setQ] = useState('')
  const [eco, setEco] = useState('')
  const [rel, setRel] = useState<'' | 'direct' | 'transitive'>('')
  const [reviewOnly, setReviewOnly] = useState(false)
  const [shown, setShown] = useState(PAGE)

  const ecosystems = useMemo(() => [...new Set(components.map((c) => c.ecosystem))].sort(), [components])
  const rows = useMemo(() => {
    const needle = q.toLowerCase()
    return components
      .filter((c) => !eco || c.ecosystem === eco)
      .filter((c) => !rel || (rel === 'direct') === c.direct)
      .filter((c) => !reviewOnly || c.needsReview)
      .filter((c) => !needle || [c.name, c.version, c.purl, c.licenses?.value, c.supplier?.value].some((v) => v?.toLowerCase().includes(needle)))
      .sort((a, b) => a.name.localeCompare(b.name))
  }, [components, q, eco, rel, reviewOnly])

  return (
    <>
      <div className="row" style={{ marginBottom: 8, alignItems: 'center' }}>
        <input type="text" placeholder="Search name, version, purl, license, supplier" value={q} onChange={(e) => { setQ(e.target.value); setShown(PAGE) }} style={{ maxWidth: 360 }} />
        <select value={eco} onChange={(e) => setEco(e.target.value)}><option value="">All ecosystems</option>{ecosystems.map((e) => <option key={e}>{e}</option>)}</select>
        <select value={rel} onChange={(e) => setRel(e.target.value as typeof rel)}><option value="">Direct + transitive</option><option value="direct">Direct</option><option value="transitive">Transitive</option></select>
        <label><input type="checkbox" checked={reviewOnly} onChange={(e) => setReviewOnly(e.target.checked)} /> Needs review only</label>
        <span className="muted">{rows.length} shown</span>
      </div>
      <table>
        <thead><tr><th>Name</th><th>Version</th><th>Ecosystem</th><th>License</th><th>Relation</th><th>Scope</th><th>Download URL</th><th>Supplier</th><th>Found by</th><th>Review</th></tr></thead>
        <tbody>
          {rows.slice(0, shown).map((c) => (
            <tr key={c.key} className={`clickable${c.needsReview ? ' review' : ''}`} onClick={() => onSelect(c)}>
              <td>{c.name}</td><td>{c.version}</td><td>{c.ecosystem}</td><td>{c.licenses?.value ?? <span className="muted">unresolved</span>}</td>
              <td>{c.direct ? 'direct' : 'transitive'}</td><td>{c.scope?.value ?? ''}</td>
              <td>{c.downloadUrl ? <a href={c.downloadUrl.value} onClick={(e) => e.stopPropagation()} target="_blank" rel="noreferrer">{c.downloadUrl.value}</a> : <span className="muted">unresolved</span>}</td>
              <td>{c.supplier?.value ?? ''}</td><td>{c.foundBy.join(', ')}</td><td>{c.needsReview ? '⚠' : ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {rows.length > shown && <button onClick={() => setShown(shown + PAGE)} style={{ marginTop: 8 }}>Show more ({rows.length - shown} left)</button>}
    </>
  )
}
