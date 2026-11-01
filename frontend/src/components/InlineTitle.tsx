import { useState, type ReactNode } from 'react'
import { CheckIcon, PencilIcon, XIcon } from './Icons'

/** A page title whose value is edited in place: the pencil opens an input, Enter or ✓ saves, Esc or ✕ cancels. */
export function InlineTitle({ value, label, prefix, onSave }: { value: string; label: string; prefix?: ReactNode; onSave: (v: string) => void }) {
  const [draft, setDraft] = useState<string | null>(null)   // null when not editing
  if (draft === null) return (
    <div className="title-row">
      <h2>{prefix}{value}</h2>
      <button className="icon sm" onClick={() => setDraft(value)} aria-label={`Rename ${label}`} title="Rename"><PencilIcon size={16} /></button>
    </div>
  )
  const save = () => { const v = draft.trim(); setDraft(null); if (v && v !== value) onSave(v) }
  return (
    <form className="title-row" onSubmit={(e) => { e.preventDefault(); save() }}>
      {prefix && <h2>{prefix}</h2>}
      <input type="text" className="title-input" aria-label={label} required autoFocus value={draft}
        onChange={(e) => setDraft(e.target.value)} onKeyDown={(e) => { if (e.key === 'Escape') setDraft(null) }} />
      <button className="icon sm" aria-label="Save" title="Save (Enter)"><CheckIcon size={16} /></button>
      <button type="button" className="icon sm" onClick={() => setDraft(null)} aria-label="Cancel" title="Cancel (Esc)"><XIcon size={16} /></button>
    </form>
  )
}
