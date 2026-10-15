import { useState, type FormEvent } from 'react'
import { submitScan } from '../api'

type Kind = 'repo' | 'image' | 'tar'

export function ScanForm() {
  const [kind, setKind] = useState<Kind>('repo')
  const [target, setTarget] = useState('')
  const [ref, setRef] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [deep, setDeep] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null); setBusy(true)
    try {
      let body: Parameters<typeof submitScan>[0]
      if (kind === 'tar') {
        if (!file) throw new Error('choose an image .tar file')
        body = new FormData(); body.append('file', file); body.append('deep', String(deep))
      } else {
        body = { target: target.trim(), ref: kind === 'repo' && ref.trim() ? ref.trim() : undefined, deep }
      }
      const { id } = await submitScan(body)
      location.hash = `#/jobs/${id}`
    } catch (err) {
      setError((err as Error).message)
    } finally { setBusy(false) }
  }

  return (
    <form className="card" onSubmit={submit}>
      <h2>New scan</h2>
      <div className="row">
        {(['repo', 'image', 'tar'] as Kind[]).map((k) => (
          <label key={k}><input type="radio" checked={kind === k} onChange={() => setKind(k)} />{' '}
            {{ repo: 'GitHub repository', image: 'Image reference', tar: 'Image .tar upload' }[k]}</label>
        ))}
      </div>
      {kind === 'repo' && <>
        <label>Repository URL<input type="text" required placeholder="https://github.com/owner/repo" value={target} onChange={(e) => setTarget(e.target.value)} /></label>
        <label>Branch or tag (optional)<input type="text" value={ref} onChange={(e) => setRef(e.target.value)} /></label>
      </>}
      {kind === 'image' && <label>Image<input type="text" required placeholder="nginx:1.25" value={target} onChange={(e) => setTarget(e.target.value)} /></label>}
      {kind === 'tar' && <label>Image tar (docker save output)<br /><input type="file" accept=".tar" onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></label>}
      {kind !== 'image' && kind !== 'tar' && <label><input type="checkbox" checked={deep} onChange={(e) => setDeep(e.target.checked)} /> Deep scan (ScanCode file matching for vendored C/C++ code; slow)</label>}
      <button disabled={busy}>{busy ? 'Submitting…' : 'Scan'}</button>
      {error && <p className="error">{error}</p>}
    </form>
  )
}
