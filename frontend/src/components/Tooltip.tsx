import { useId, useRef, useState, type ReactNode } from 'react'

/**
 * Shows `text` next to a focusable trigger on hover or keyboard focus.
 * Positioned fixed from the trigger's rect, so a scrolling container (like .table-wrap) cannot clip it.
 */
export function Tooltip({ text, label, tone, small, children }: { text: string; label: string; tone?: 'error'; small?: boolean; children: ReactNode }) {
  const id = useId()
  const ref = useRef<HTMLButtonElement>(null)
  const [pos, setPos] = useState<{ left: number; top?: number; bottom?: number } | null>(null)
  const show = () => {
    const r = ref.current!.getBoundingClientRect()
    const left = Math.max(8, Math.min(r.left, document.documentElement.clientWidth - 448))
    // below the trigger, unless it sits low in the viewport: then above, so the text is never cut off
    setPos(r.bottom > innerHeight * 0.6 ? { left, bottom: innerHeight - r.top + 6 } : { left, top: r.bottom + 6 })
  }
  return <>
    <button ref={ref} type="button" className={`icon ${small ? 'xs' : 'sm'} tip-trigger${tone ? ` ${tone}` : ''}`} aria-label={label} aria-describedby={pos ? id : undefined}
      onMouseEnter={show} onMouseLeave={() => setPos(null)} onFocus={show} onBlur={() => setPos(null)}
      onKeyDown={(e) => { if (e.key === 'Escape') setPos(null) }} onClick={(e) => e.stopPropagation()}>{children}</button>
    {pos && <span id={id} role="tooltip" className="tooltip" style={pos}>{text}</span>}
  </>
}
