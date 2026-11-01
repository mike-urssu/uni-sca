import type { ScanResult } from '../types'

function top(values: string[], n: number) {
  const counts = new Map<string, number>()
  for (const v of values) counts.set(v, (counts.get(v) ?? 0) + 1)
  return [...counts].sort((a, b) => b[1] - a[1]).slice(0, n)
}

export function Summary({ result }: { result: ScanResult }) {
  const cs = result.components
  const direct = cs.filter((c) => c.direct).length
  return (
    <div className="card">
      <div className="row stats">
        <div><b className="num">{cs.length}</b> components<br /><span className="muted">{direct} direct · {cs.length - direct} transitive</span></div>
        <div><b className="num">{cs.filter((c) => c.needsReview).length}</b> need review<br /><span className="muted">{cs.filter((c) => c.foundBy.length === 1).length} found by one tool only</span></div>
        <div><b>Ecosystems</b><br />{top(cs.map((c) => c.ecosystem), 10).map(([k, v]) => <span key={k} className="badge" style={{ marginRight: 4 }}>{k} {v}</span>)}</div>
      </div>
      {result.warnings.length > 0 && <>
        <h3>Warnings</h3>
        <ul>{result.warnings.map((w, i) => <li key={i}><code>{w.code}</code> {w.message}</li>)}</ul>
      </>}
    </div>
  )
}
