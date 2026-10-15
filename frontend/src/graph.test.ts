import { expect, test } from 'vitest'
import { children, layered, pathTo } from './graph'
import type { ScanResult } from './types'

const r = {
  roots: ['a'],
  edges: [{ first: 'a', second: 'b' }, { first: 'b', second: 'c' }, { first: 'c', second: 'b' }, { first: 'a', second: 'd' }],
} as unknown as ScanResult

test('shortest path from root', () => expect(pathTo(r, 'c')).toEqual(['a', 'b', 'c']))
test('terminates on cycles and returns null when unreachable', () => expect(pathTo(r, 'zzz')).toBeNull())
test('root itself', () => expect(pathTo(r, 'a')).toEqual(['a']))
test('children are grouped by parent', () => expect(children(r).get('a')).toEqual(['b', 'd']))

// a → b → c ↺ b, a → d → c : c is shared, c → b closes a cycle
const g = {
  roots: ['a', 'x'],
  edges: [{ first: 'a', second: 'b' }, { first: 'b', second: 'c' }, { first: 'c', second: 'b' },
    { first: 'a', second: 'd' }, { first: 'd', second: 'c' }, { first: 'x', second: 'y' }],
} as unknown as ScanResult

test('layered places nodes by BFS depth from the root, shared nodes once', () => {
  const l = layered(g, 'a')
  expect(Object.fromEntries(l.nodes.map((n) => [n.key, [n.col, n.row]]))).toEqual({ a: [0, 0], b: [1, 0], d: [1, 1], c: [2, 0] })
})

test('layered keeps only the root subgraph and marks edges that point back as cycle edges', () => {
  const l = layered(g, 'a')
  expect(l.edges.map((e) => `${e.from}>${e.to}${e.back ? '*' : ''}`).sort()).toEqual(['a>b', 'a>d', 'b>c', 'c>b*', 'd>c'])
})

test('layered from a leaf is just the leaf', () => {
  expect(layered(g, 'y')).toEqual({ nodes: [{ key: 'y', col: 0, row: 0 }], edges: [] })
})

test('an edge between siblings is not a cycle: the target moves one column right', () => {
  // a → b, a → c, b → c : BFS would put b and c in the same column and call b → c a cycle
  const s = { roots: ['a'], edges: [{ first: 'a', second: 'b' }, { first: 'a', second: 'c' }, { first: 'b', second: 'c' }] } as unknown as ScanResult
  const l = layered(s, 'a')
  expect(Object.fromEntries(l.nodes.map((n) => [n.key, n.col]))).toEqual({ a: 0, b: 1, c: 2 })
  expect(l.edges.filter((e) => e.back)).toEqual([])
})

test('every non-cycle edge points right', () => {
  const l = layered(g, 'a')
  const col = new Map(l.nodes.map((n) => [n.key, n.col]))
  for (const e of l.edges) expect(e.back || col.get(e.to)! > col.get(e.from)!).toBe(true)
})
