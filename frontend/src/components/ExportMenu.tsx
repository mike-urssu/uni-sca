import { useEffect, useRef } from 'react'
import { ChevronIcon, DownloadIcon } from './Icons'

export interface ExportItem { label: string; hint: string; href: string }

/** A disclosure button listing download links; closes on Esc, on a click outside, or once a file is picked. */
export function ExportMenu({ items }: { items: ExportItem[] }) {
  const ref = useRef<HTMLDetailsElement>(null)
  useEffect(() => {
    const close = (e: Event) => {
      const d = ref.current
      if (d?.open && (e instanceof KeyboardEvent ? e.key === 'Escape' : !d.contains(e.target as Node))) d.open = false
    }
    addEventListener('click', close); addEventListener('keydown', close)
    return () => { removeEventListener('click', close); removeEventListener('keydown', close) }
  }, [])
  return (
    <details ref={ref} className="menu">
      <summary className="button"><DownloadIcon size={16} /> Export <span className="chev"><ChevronIcon size={14} /></span></summary>
      <div className="menu-list">
        {items.map((i) => (
          <a key={i.href} href={i.href} download onClick={() => { if (ref.current) ref.current.open = false }}>
            <span>{i.label}</span><span className="muted">{i.hint}</span>
          </a>))}
      </div>
    </details>
  )
}
