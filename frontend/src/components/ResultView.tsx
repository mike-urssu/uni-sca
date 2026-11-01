import { useMemo, useState } from 'react'
import type { ScanResult } from '../types'
import { Summary } from './Summary'
import { ComponentTable } from './ComponentTable'
import { ComponentDetail } from './ComponentDetail'

/**
 * Summary, component table and the detail panel of one result (a single scan or a combined version).
 * onChanged: fetch the result again after an edit (e.g. a copyright) changed it on the server.
 */
export function ResultView({ result, onChanged }: { result: ScanResult; onChanged?: () => void }) {
  const [selected, setSelected] = useState<string | null>(null)
  const [graphRoot, setGraphRoot] = useState<string | null>(null)
  const byKey = useMemo(() => new Map(result.components.map((c) => [c.key, c])), [result])
  // a list row picks the graph's root; clicking inside the panel only moves the selection
  const openFromList = (key: string) => { setGraphRoot(key); setSelected(key) }
  return <>
    <Summary result={result} />
    <ComponentTable result={result} byKey={byKey} onSelect={openFromList} />
    {selected && graphRoot && byKey.get(selected) && <ComponentDetail component={byKey.get(selected)!} graphRoot={graphRoot} result={result} byKey={byKey}
      onClose={() => setSelected(null)} onSelect={setSelected} onChanged={onChanged} />}
  </>
}
