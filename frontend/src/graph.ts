import type { ScanResult } from './types'

export function children(r: Pick<ScanResult, 'edges'>): Map<string, string[]> {
  const m = new Map<string, string[]>()
  for (const { first, second } of r.edges) {
    const list = m.get(first)
    if (list) list.push(second); else m.set(first, [second])
  }
  return m
}

/** Shortest root → key path (BFS with a visited set, so cycles terminate). null when unreachable. */
export function pathTo(r: Pick<ScanResult, 'edges' | 'roots'>, key: string): string[] | null {
  const kids = children(r)
  const prev = new Map<string, string | null>(r.roots.map((k) => [k, null]))
  const queue = [...r.roots]
  while (queue.length) {
    const cur = queue.shift()!
    if (cur === key) {
      const path: string[] = []
      for (let k: string | null = cur; k !== null; k = prev.get(k) ?? null) path.unshift(k)
      return path
    }
    for (const next of kids.get(cur) ?? []) if (!prev.has(next)) { prev.set(next, cur); queue.push(next) }
  }
  return null
}

export interface LayeredNode { key: string; col: number; row: number }
export interface LayeredEdge { from: string; to: string; back: boolean }

/**
 * Everything reachable from root, laid out left→right. A DFS marks edges that close a cycle (back); every other
 * edge points right because a node's column is its longest path from the root. Rows follow discovery order.
 */
export function layered(r: Pick<ScanResult, 'edges'>, root: string): { nodes: LayeredNode[]; edges: LayeredEdge[] } {
  const kids = children(r)
  const onStack = new Set<string>(), seen = new Set<string>()
  const preorder: string[] = [], postorder: string[] = [], edges: LayeredEdge[] = []
  const visit = (k: string) => {
    seen.add(k); onStack.add(k); preorder.push(k)
    for (const n of kids.get(k) ?? []) {
      edges.push({ from: k, to: n, back: onStack.has(n) })
      if (!seen.has(n)) visit(n)
    }
    onStack.delete(k); postorder.push(k)
  }
  visit(root)

  const forward = children({ edges: edges.filter((e) => !e.back).map((e) => ({ first: e.from, second: e.to })) })
  const col = new Map(preorder.map((k) => [k, 0]))
  for (const k of postorder.reverse()) {   // topological order once back edges are ignored
    for (const n of forward.get(k) ?? []) col.set(n, Math.max(col.get(n)!, col.get(k)! + 1))
  }
  const rows = new Map<number, number>()
  const nodes = preorder.map((key) => {
    const c = col.get(key)!, row = rows.get(c) ?? 0
    rows.set(c, row + 1)
    return { key, col: c, row }
  })
  return { nodes, edges }
}
