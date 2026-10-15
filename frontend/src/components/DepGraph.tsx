import { useMemo } from 'react'
import { layered } from '../graph'
import type { Component, ScanResult } from '../types'

const COL_W = 230, ROW_H = 34, NODE_W = 190, NODE_H = 24, PAD = 12

interface Props { result: ScanResult; root: string; byKey: Map<string, Component>; selected: string; onSelect: (key: string) => void }

/** Everything `root` pulls in, left→right by depth. Shared deps are drawn once; dashed edges close a cycle. */
export function DepGraph({ result, root, byKey, selected, onSelect }: Props) {
  const { nodes, edges } = useMemo(() => layered(result, root), [result, root])
  if (nodes.length === 1) return <p className="muted">{byKey.get(root)?.name ?? root} has no dependencies of its own.</p>

  const pos = new Map(nodes.map((n) => [n.key, { x: PAD + n.col * COL_W, y: PAD + n.row * ROW_H }]))
  const width = PAD * 2 + (Math.max(...nodes.map((n) => n.col)) * COL_W) + NODE_W
  const height = PAD * 2 + Math.max(...nodes.map((n) => n.row)) * ROW_H + NODE_H
  const label = (k: string) => { const c = byKey.get(k); const s = c ? `${c.name}@${c.version ?? ''}` : k; return s.length > 28 ? s.slice(0, 27) + '…' : s }

  return (
    <div className="graph">
      <p className="muted">{nodes.length - 1} components reachable · columns are dependency depth · click a node for its details</p>
      <svg width={width} height={height} role="img" aria-label={`dependency graph of ${label(root)}`}>
        <defs>
          <marker id="arrow" viewBox="0 0 10 10" refX="10" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" className="edge-head" />
          </marker>
        </defs>
        {edges.map((e) => {
          const a = pos.get(e.from)!, b = pos.get(e.to)!
          const x1 = a.x + NODE_W, y1 = a.y + NODE_H / 2, x2 = b.x, y2 = b.y + NODE_H / 2
          const d = e.back
            ? `M${a.x + NODE_W / 2},${a.y} C${a.x + NODE_W / 2},${a.y - 30} ${b.x + NODE_W / 2},${b.y - 30} ${b.x + NODE_W / 2},${b.y}`
            : `M${x1},${y1} C${x1 + 30},${y1} ${x2 - 30},${y2} ${x2},${y2}`
          return <path key={`${e.from}>${e.to}`} d={d} className={e.back ? 'edge back' : 'edge'} markerEnd="url(#arrow)" />
        })}
        {nodes.map((n) => {
          const p = pos.get(n.key)!
          const c = byKey.get(n.key)
          return (
            <g key={n.key} transform={`translate(${p.x},${p.y})`} onClick={() => onSelect(n.key)} className={`node${n.key === selected ? ' selected' : ''}${n.key === root ? ' root' : ''}${c?.needsReview ? ' review' : ''}`}>
              <title>{c ? `${c.name}@${c.version}\n${c.licenses?.value ?? 'license unresolved'}` : n.key}</title>
              <rect width={NODE_W} height={NODE_H} rx={5} />
              <text x={8} y={16}>{label(n.key)}</text>
            </g>
          )
        })}
      </svg>
    </div>
  )
}
