import { useEffect, useMemo, useState } from 'react'
import { fileUrl, getResult, getScan, watch } from '../api'
import { TERMINAL, type Component, type JobStatus, type ScanResult } from '../types'
import { Summary } from '../components/Summary'
import { ComponentTable } from '../components/ComponentTable'
import { DepTree } from '../components/DepTree'
import { ComponentDetail } from '../components/ComponentDetail'

const STAGES = ['queued', 'acquire', 'scan', 'normalize', 'merge', 'enrich', 'emit']

export function JobView({ id }: { id: string }) {
  const [job, setJob] = useState<JobStatus | null>(null)
  const [result, setResult] = useState<ScanResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [tab, setTab] = useState<'table' | 'tree'>('table')
  const [selected, setSelected] = useState<string | null>(null)

  useEffect(() => {
    setJob(null); setResult(null); setError(null)
    getScan(id).then(setJob, (e) => setError(e.message))
    return watch(id, setJob)
  }, [id])

  const done = job != null && TERMINAL.includes(job.status)
  useEffect(() => {
    if (done && job?.status !== 'FAILED') getResult(id).then(setResult, (e) => setError(e.message))
  }, [id, done, job?.status])

  const byKey = useMemo(() => new Map((result?.components ?? []).map((c) => [c.key, c])), [result])
  const open = (c: Component | string) => setSelected(typeof c === 'string' ? c : c.key)

  if (error) return <p className="error">{error}</p>
  if (!job) return <p className="muted">Loading…</p>
  return (
    <>
      <div className="card">
        <h2>{job.input.target}{job.input.ref ? ` @ ${job.input.ref}` : ''} <span className={`badge ${job.status}`}>{job.status}</span></h2>
        {!done && <p>Stage: <b>{job.stage}</b> <span className="muted">({STAGES.indexOf(job.stage ?? '') + 1}/{STAGES.length})</span></p>}
        {job.error && <p className="error">{job.error}</p>}
        {job.tools.length > 0 && <p>{job.tools.map((t) => (
          <span key={t.tool} className={`badge ${t.ok ? 'DONE' : 'FAILED'}`} title={`${t.image} · ${t.log}`} style={{ marginRight: 6 }}>
            {t.tool} {t.ok ? 'ok' : 'failed'} · {(t.millis / 1000).toFixed(1)}s</span>))}</p>}
        {done && <p>Download: {['sbom.cdx.json', 'sbom.spdx.json', 'report.html', 'raw.zip'].map((n) =>
          <a key={n} href={fileUrl(id, n)} style={{ marginRight: 12 }} download={n === 'report.html' ? undefined : `${id}-${n}`}>{n}</a>)}</p>}
      </div>
      {result && <>
        <Summary result={result} />
        <div className="tabs">
          <button className={tab === 'table' ? 'active' : ''} onClick={() => setTab('table')}>Components</button>
          <button className={tab === 'tree' ? 'active' : ''} onClick={() => setTab('tree')}>Dependency tree</button>
        </div>
        {tab === 'table' ? <ComponentTable components={result.components} onSelect={open} /> : <DepTree result={result} byKey={byKey} onSelect={open} />}
        {selected && byKey.get(selected) && <ComponentDetail component={byKey.get(selected)!} result={result} byKey={byKey} onClose={() => setSelected(null)} onSelect={open} />}
      </>}
    </>
  )
}
