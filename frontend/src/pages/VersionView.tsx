import { useEffect, useState, type FormEvent } from 'react'
import { addSource, deleteSource, deleteVersion, getProduct, renameVersion, rescan, versionFileUrl, versionResult, versionScans } from '../api'
import { TERMINAL, type JobStatus, type Product, type ScanResult, type SourceInput } from '../types'
import { ResultView } from '../components/ResultView'
import { SourceRow, clean, emptySource } from '../components/SourceFields'
import { AlertIcon, ChevronIcon, RefreshIcon, TrashIcon } from '../components/Icons'
import { Tooltip } from '../components/Tooltip'
import { InlineTitle } from '../components/InlineTitle'
import { ModalForm } from '../components/Modal'
import { ExportMenu } from '../components/ExportMenu'

const OK = ['DONE', 'PARTIAL']

export function VersionView({ pid, vid }: { pid: string; vid: string }) {
  const [product, setProduct] = useState<Product | null>(null)
  const [scans, setScans] = useState<JobStatus[]>([])
  const [unchecked, setUnchecked] = useState<Set<string>>(new Set())   // sources left out of the shown result; new sources start checked
  const [result, setResult] = useState<ScanResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [tick, setTick] = useState(0)
  const [against, setAgainst] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)
  const [showSources, setShowSources] = useState(false)
  const reload = () => setTick((t) => t + 1)

  useEffect(() => { getProduct(pid).then(setProduct, (e) => setError(e.message)) }, [pid, tick])
  const running = scans.some((s) => !TERMINAL.includes(s.status))
  useEffect(() => {
    const load = () => versionScans(pid, vid).then(setScans, (e) => setError(e.message))
    load()
    if (!running) return
    const t = setInterval(load, 3000)
    return () => clearInterval(t)
  }, [pid, vid, tick, running])

  // the shown result only changes when a scan finishes, so key the fetch on finished scans
  const finished = scans.filter((s) => TERMINAL.includes(s.status)).map((s) => s.id).join()
  const version = product?.versions.find((v) => v.id === vid)
  const checked = (version?.sources ?? []).map((s) => s.id).filter((id) => !unchecked.has(id))
  const checkedKey = checked.join()
  useEffect(() => {
    setResult(null)
    if (!checkedKey) return
    versionResult(pid, vid, checkedKey.split(',')).then(setResult, (e) => setError(e.message))
  }, [pid, vid, checkedKey, finished])
  const toggle = (sid: string) => setUnchecked((u) => { const n = new Set(u); if (n.has(sid)) n.delete(sid); else n.add(sid); return n })

  if (error) return <p className="error">{error}</p>
  if (!product) return <p className="muted">Loading…</p>
  if (!version) return <p className="error">version not found</p>
  const act = (p: Promise<unknown>) => p.then(reload, (e) => alert(e.message))
  const others = product.versions.filter((v) => v.id !== vid)
  const latest = version.sources.map((s) => scans.find((j) => j.input.sourceId === s.id))
  const failed = latest.filter((j) => j?.status === 'FAILED').length
  const scanning = latest.filter((j) => j && !TERMINAL.includes(j.status)).length
  const sourceSummary = <>
    {version.sources.length} source{version.sources.length === 1 ? '' : 's'}
    {scanning > 0 && <> · <span className="info">{scanning} scanning</span></>}
    {failed > 0 && <> · <span className="error">{failed} failed</span></>}
  </>
  const previous = product.versions[product.versions.indexOf(version) - 1] ?? others.at(-1)

  return <>
    <div className="page-head">
      {/* the product name links back to its version list */}
      <InlineTitle value={version.version} label="version" prefix={<a href={`#/products/${pid}`}>{product.name}</a>}
        onSave={(n) => act(renameVersion(pid, vid, n))} />
      <div className="row">
        {previous && <>
          <select aria-label="Version to compare with" value={against ?? previous.id} onChange={(e) => setAgainst(e.target.value)}>
            {others.map((v) => <option key={v.id} value={v.id}>{v.version}{v === previous ? ' (previous)' : ''}</option>)}
          </select>
          <button onClick={() => { location.hash = `#/products/${pid}/compare?a=${against ?? previous.id}&b=${vid}` }}>Compare</button>
        </>}
        <button className="icon danger" aria-label={`Delete version ${version.version}`} title="Delete version"
          onClick={() => { if (confirm(`Delete version ${version.version} and its scans?`)) deleteVersion(pid, vid).then(() => { location.hash = `#/products/${pid}` }, (e) => alert(e.message)) }}><TrashIcon /></button>
      </div>
    </div>

    <div className="section-head">
      {/* collapsed by default; the summary keeps a failing source visible without opening the table */}
      <button className="disclosure" aria-expanded={showSources} aria-controls="sources-table" onClick={() => setShowSources(!showSources)}>
        <ChevronIcon size={16} /><h3>Sources</h3>
        <span className="muted">{sourceSummary}</span>
      </button>
      <button className="primary" onClick={() => setAdding(true)}>+ Add source</button>
    </div>

    {showSources && <div className="table-wrap" id="sources-table"><table className="sources">
      <thead><tr><th>Type</th><th>Source</th><th>Latest scan</th><th aria-label="Actions"></th></tr></thead>
      <tbody>{version.sources.map((s) => {
        const last = scans.find((j) => j.input.sourceId === s.id)
        const busy = last != null && !TERMINAL.includes(last.status)
        return <tr key={s.id}>
          <td className="nowrap"><span className={`badge ${s.kind}`}>{s.kind === 'REPO' ? 'Repository' : 'Image'}</span></td>
          {/* repo targets are validated https:// URLs on the server, so they are safe as links */}
          <td>{s.kind === 'REPO' ? <a href={s.target} target="_blank" rel="noreferrer">{s.target}</a> : s.target}
            {s.ref && <span className="muted"> @ {s.ref}</span>}</td>
          <td><div className="status-cell">
            {last ? <a href={`#/jobs/${last.id}`}><span className={`badge ${last.status}`}>{last.status}</span> {last.stage ?? new Date(last.createdAt).toLocaleString()}</a> : <span className="muted">never</span>}
            {last?.status === 'FAILED' && last.error && <Tooltip text={last.error} label="Show error" tone="error"><AlertIcon size={16} /></Tooltip>}
          </div></td>
          <td className="cell-icon"><div className="icon-row">
            <button className="icon sm" disabled={busy} onClick={() => act(rescan(pid, vid, s.id))}
              aria-label={`Rescan ${s.target}`} title={busy ? 'Scan in progress' : 'Rescan'}><RefreshIcon size={16} /></button>
            <button className="icon sm danger" onClick={() => { if (confirm(`Remove ${s.target} and its scans?`)) act(deleteSource(pid, vid, s.id)) }}
              aria-label={`Remove ${s.target}`} title="Remove source"><TrashIcon size={14} /></button>
          </div></td>
        </tr>
      })}</tbody>
    </table></div>}
    {adding && <NewSource onClose={() => setAdding(false)} onAdd={(s) => addSource(pid, vid, s).then(reload)} />}

    <div className="section-head">
      <h3>Components</h3>
      {/* exports always cover every source of the version, whatever is checked below */}
      <ExportMenu items={[
        { label: 'CycloneDX', hint: 'SBOM · JSON (1.6)', href: versionFileUrl(pid, vid, 'sbom.cdx.json') },
        { label: 'SPDX', hint: 'SBOM · JSON (2.3)', href: versionFileUrl(pid, vid, 'sbom.spdx.json') },
        { label: 'OSS notice', hint: '오픈소스 고지문 · HTML', href: versionFileUrl(pid, vid, 'oss-notice.html') },
      ]} />
    </div>
    <div className="result-layout">
      <aside className="card source-picker" aria-label="Sources shown in the result">
        <b>Show components from</b>
        {version.sources.map((s) => {
          const ok = scans.some((j) => j.input.sourceId === s.id && OK.includes(j.status))
          return <label key={s.id} className={unchecked.has(s.id) ? 'off' : ''} title={s.target + (s.ref ? ` @ ${s.ref}` : '')}>
            <input type="checkbox" checked={!unchecked.has(s.id)} onChange={() => toggle(s.id)} />
            <span className="picker-text">
              <span className="picker-name">{s.kind === 'REPO' ? s.target.replace(/^https:\/\/(www\.)?github\.com\//, '') : s.target}</span>
              <span className="picker-meta"><span className={`badge ${s.kind}`}>{s.kind === 'REPO' ? 'Repository' : 'Image'}</span>{!ok && <span className="muted">no successful scan</span>}</span>
            </span>
          </label>
        })}
      </aside>
      <div className="result-main">
        {checked.length === 0 ? <p className="muted">Check at least one source to see its components.</p>
          : result ? <ResultView key={checkedKey} result={result} onChanged={() => versionResult(pid, vid, checked).then(setResult, (e) => setError(e.message))} />
          : <p className="muted">Loading…</p>}
      </div>
    </div>
  </>
}

function NewSource({ onAdd, onClose }: { onAdd: (s: SourceInput) => Promise<unknown>; onClose: () => void }) {
  const [s, setS] = useState<SourceInput>(emptySource)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null); setBusy(true)
    try { await onAdd(clean(s)); onClose() } catch (err) { setError((err as Error).message) } finally { setBusy(false) }
  }
  return (
    <ModalForm title="Add source" submitLabel="Add and scan" busy={busy} error={error} onSubmit={submit} onClose={onClose}>
      <SourceRow s={s} onChange={(p) => setS({ ...s, ...p })} />
    </ModalForm>
  )
}
