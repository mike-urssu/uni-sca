import type { SourceInput } from '../types'
import { TrashIcon } from './Icons'

export const emptySource = (): SourceInput => ({ kind: 'REPO', target: '', ref: '', deep: false })

/** Editable list of sources (GitHub repositories or docker images) for a new version. */
export function SourceFields({ sources, onChange }: { sources: SourceInput[]; onChange: (s: SourceInput[]) => void }) {
  const set = (i: number, patch: Partial<SourceInput>) => onChange(sources.map((s, j) => (j === i ? { ...s, ...patch } : s)))
  return <>
    {sources.map((s, i) => <SourceRow key={i} s={s} onChange={(p) => set(i, p)}
      onRemove={sources.length > 1 ? () => onChange(sources.filter((_, j) => j !== i)) : undefined} />)}
    <button type="button" className="secondary" onClick={() => onChange([...sources, emptySource()])}>+ Add source</button>
  </>
}

export function SourceRow({ s, onChange, onRemove }: { s: SourceInput; onChange: (p: Partial<SourceInput>) => void; onRemove?: () => void }) {
  return (
    <fieldset className="source">
      <div className="source-head">
        <div className="row">
          <label><input type="radio" checked={s.kind === 'REPO'} onChange={() => onChange({ kind: 'REPO' })} /> GitHub repository</label>
          <label><input type="radio" checked={s.kind === 'IMAGE'} onChange={() => onChange({ kind: 'IMAGE', ref: '' })} /> Docker image</label>
        </div>
        {onRemove && <button type="button" className="icon danger" onClick={onRemove} aria-label="Remove source" title="Remove source"><TrashIcon /></button>}
      </div>
      {s.kind === 'REPO' ? <>
        <label>Repository URL<input type="text" required placeholder="https://github.com/owner/repo" value={s.target} onChange={(e) => onChange({ target: e.target.value })} /></label>
        <label>Branch or tag (optional)<input type="text" value={s.ref ?? ''} onChange={(e) => onChange({ ref: e.target.value })} /></label>
      </> : <label>Image<input type="text" required placeholder="nginx:1.25" value={s.target} onChange={(e) => onChange({ target: e.target.value })} /></label>}
    </fieldset>
  )
}

/**
 * Trims fields and drops a ref the server would reject (images) or treat as unset (blank).
 * Deep scan (ScanCode) stays off in the UI until its file-level license findings reach the result; the CLI still has --deep.
 */
export const clean = (s: SourceInput): SourceInput =>
  ({ kind: s.kind, target: s.target.trim(), ref: s.kind === 'REPO' && s.ref?.trim() ? s.ref.trim() : undefined, deep: false })
