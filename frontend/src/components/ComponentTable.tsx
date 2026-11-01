import { useMemo, useState, type CSSProperties } from 'react'
import { children } from '../graph'
import { Tooltip } from './Tooltip'
import { InfoIcon } from './Icons'
import type { Component, ScanResult } from '../types'

const PAGE = 500

// CycloneDX scope: from the lockfile for npm repositories (see backend NpmScope), from cdxgen otherwise; OS packages have none
const SCOPE_HELP = `배포되는 제품에 포함되는지 여부
required: 런타임 의존성 (배포됨)
optional: 개발·테스트 전용 (배포 안 됨)
excluded: 배포물에서 제외
빈칸: 알 수 없음 (OS 패키지, 이미지에서만 발견)
npm은 lock 파일 기준, 그 외는 cdxgen 기준`

const RELATION_HELP = `제품이 이 컴포넌트를 직접 쓰는지 여부
direct: 제품이 직접 선언한 의존성 (OS 패키지는 다른 패키지가 의존하지 않는 최상위)
transitive: direct가 끌어온 간접 의존성, 제품에는 똑같이 포함
▸ 를 펼치면 그 컴포넌트가 끌어온 의존성이 보입니다`

interface RowProps {
  k: string; depth: number; ancestors: Set<string>
  kids: Map<string, string[]>; byKey: Map<string, Component>; onSelect: (key: string) => void
}

function Cells({ c }: { c: Component }) {
  return <>
    <td className="nowrap">{c.version}</td>{/* long SPDX expressions stay on one line, cut with an ellipsis; the full expression is in the tooltip and the detail panel */}
    <td>{c.licenses ? <span className="ellipsis license" title={c.licenses.value}>{c.licenses.value}</span> : <span className="muted">unresolved</span>}</td><td>{c.ecosystem}</td>
    <td>{c.scope?.value ?? ''}</td><td>{c.direct ? <span className="badge direct">direct</span> : <span className="muted">transitive</span>}</td>
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
    {/* rows under an expanded component are tinted and carry one guide line per level, so they read as its children */}
    <tr className={`clickable${depth ? ' child' : ''}${c.needsReview ? ' review' : ''}`} onClick={() => onSelect(k)}>
      <td className="nowrap tree-name" style={{ paddingLeft: 8 + depth * 18, '--depth': depth } as CSSProperties}>
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
        <thead><tr><th>Name</th><th>Version</th><th>License</th><th>Ecosystem</th><th><span className="th-help">Scope<Tooltip small label="What scope means" text={SCOPE_HELP}><InfoIcon size={14} /></Tooltip></span></th><th><span className="th-help">Relation<Tooltip small label="What relation means" text={RELATION_HELP}><InfoIcon size={14} /></Tooltip></span></th></tr></thead>
        <tbody>
          {filtering
            ? rows.slice(0, shown).map((c) => (
              <tr key={c.key} className={`clickable${c.needsReview ? ' review' : ''}`} onClick={() => onSelect(c.key)}><td className="nowrap">{c.name}</td><Cells c={c} /></tr>))
            : rows.slice(0, shown).map((c) => <TreeRow key={c.key} k={c.key} depth={0} ancestors={new Set()} kids={kids} byKey={byKey} onSelect={onSelect} />)}
        </tbody>
      </table></div>
      {rows.length > shown && <button onClick={() => setShown(shown + PAGE)} style={{ marginTop: 8 }}>Show more ({rows.length - shown} left)</button>}
    </>
  )
}
