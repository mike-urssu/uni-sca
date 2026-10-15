import { useMemo, useState } from 'react'
import { children } from '../graph'
import type { Component, ScanResult } from '../types'

interface Props { result: ScanResult; byKey: Map<string, Component>; onSelect: (key: string) => void }

function Node({ k, ancestors, kids, byKey, onSelect }: { k: string; ancestors: Set<string>; kids: Map<string, string[]>; byKey: Props['byKey']; onSelect: Props['onSelect'] }) {
  const [open, setOpen] = useState(false)
  const c = byKey.get(k)
  const cycle = ancestors.has(k)
  const next = kids.get(k) ?? []
  return (
    <li>
      <span onClick={() => !cycle && next.length && setOpen(!open)}>{cycle ? '↺' : next.length ? (open ? '▾' : '▸') : '•'}</span>{' '}
      <a href="#" onClick={(e) => { e.preventDefault(); onSelect(k) }}>{c ? `${c.name}@${c.version}` : k}</a>
      {c?.licenses && <span className="muted"> · {c.licenses.value}</span>}
      {cycle && <span className="muted"> (cycle)</span>}
      {open && !cycle && <ul>{next.map((n) => <Node key={n} k={n} ancestors={new Set([...ancestors, k])} kids={kids} byKey={byKey} onSelect={onSelect} />)}</ul>}
    </li>
  )
}

export function DepTree({ result, byKey, onSelect }: Props) {
  const kids = useMemo(() => children(result), [result])
  const roots = [...result.roots].sort()
  if (roots.length === 0) return <p className="muted">No dependency graph was reported for this target.</p>
  return <div className="card tree"><ul>{roots.map((r) => <Node key={r} k={r} ancestors={new Set()} kids={kids} byKey={byKey} onSelect={onSelect} />)}</ul></div>
}
