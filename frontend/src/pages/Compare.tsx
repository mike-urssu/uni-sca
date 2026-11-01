import { useEffect, useState } from 'react'
import { compareVersions, getProduct } from '../api'
import type { Change, DiffRow, Product } from '../types'

const LABELS: Record<Change, string> = { ADDED: 'Added', REMOVED: 'Removed', VERSION_CHANGED: 'Version changed', LICENSE_CHANGED: 'License changed' }

/** What changed from version a to version b, by package, using each version's combined result. */
export function Compare({ pid, a, b }: { pid: string; a: string; b: string }) {
  const [product, setProduct] = useState<Product | null>(null)
  const [rows, setRows] = useState<DiffRow[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    setRows(null)
    getProduct(pid).then(setProduct, (e) => setError(e.message))
    compareVersions(pid, a, b).then(setRows, (e) => setError(e.message))
  }, [pid, a, b])

  if (error) return <p className="error">{error}</p>
  if (!product || !rows) return <p className="muted">Comparing…</p>
  const name = (id: string) => product.versions.find((v) => v.id === id)?.version ?? id
  const go = (x: string, y: string) => { location.hash = `#/products/${pid}/compare?a=${x}&b=${y}` }
  const pick = (value: string, on: (v: string) => void) => (
    <select value={value} onChange={(e) => on(e.target.value)}>{product.versions.map((v) => <option key={v.id} value={v.id}>{v.version}</option>)}</select>)

  return <>
    <div className="card">
      <p className="muted"><a href="#/">Products</a> / <a href={`#/products/${pid}`}>{product.name}</a> / <a href={`#/products/${pid}/versions/${b}`}>{name(b)}</a></p>
      <h2>{product.name}: {name(a)} → {name(b)}</h2>
      <div className="row">{pick(a, (x) => go(x, b))} → {pick(b, (y) => go(a, y))}</div>
      <div className="row stats">{(Object.keys(LABELS) as Change[]).map((c) =>
        <div key={c}><b className="num">{rows.filter((r) => r.change === c).length}</b> {LABELS[c].toLowerCase()}</div>)}</div>
    </div>
    {rows.length === 0 ? <p className="muted">No differences.</p> : (Object.keys(LABELS) as Change[]).map((c) => {
      const rs = rows.filter((r) => r.change === c)
      return rs.length > 0 && <div key={c}>
        <h3>{LABELS[c]} ({rs.length})</h3>
        <div className="table-wrap"><table>
          <thead><tr><th>Name</th><th>Ecosystem</th><th>{name(a)}</th><th>{name(b)}</th><th>License {name(a)}</th><th>License {name(b)}</th></tr></thead>
          <tbody>{rs.map((r) => <tr key={r.id}>
            <td title={r.id}>{r.name}</td><td>{r.ecosystem}</td><td>{r.before.join(', ')}</td><td>{r.after.join(', ')}</td>
            <td>{r.licenseBefore ?? ''}</td><td>{r.licenseAfter ?? ''}</td>
          </tr>)}</tbody>
        </table></div>
      </div>
    })}
  </>
}
