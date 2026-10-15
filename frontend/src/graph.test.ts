import { expect, test } from 'vitest'
import { children, pathTo } from './graph'
import type { ScanResult } from './types'

const r = {
  roots: ['a'],
  edges: [{ first: 'a', second: 'b' }, { first: 'b', second: 'c' }, { first: 'c', second: 'b' }, { first: 'a', second: 'd' }],
} as unknown as ScanResult

test('shortest path from root', () => expect(pathTo(r, 'c')).toEqual(['a', 'b', 'c']))
test('terminates on cycles and returns null when unreachable', () => expect(pathTo(r, 'zzz')).toBeNull())
test('root itself', () => expect(pathTo(r, 'a')).toEqual(['a']))
test('children are grouped by parent', () => expect(children(r).get('a')).toEqual(['b', 'd']))
