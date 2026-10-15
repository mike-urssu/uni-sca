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
