import { useEffect, useRef, type FormEvent, type ReactNode } from 'react'

/**
 * A form in a native modal <dialog>: showModal() brings the focus trap, Esc and the backdrop.
 * Only Cancel and Esc end it (in onClose, where the caller unmounts it): a stray click on the backdrop must not discard what was typed.
 */
export function ModalForm({ title, submitLabel, busy, error, onSubmit, onClose, children }: {
  title: string; submitLabel: string; busy: boolean; error: string | null
  onSubmit: (e: FormEvent) => void; onClose: () => void; children: ReactNode
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => { dialog.current?.showModal() }, [])
  return (
    <dialog ref={dialog} className="modal" aria-labelledby="modal-title" onClose={onClose}>
      <form onSubmit={onSubmit}>
        <h2 id="modal-title">{title}</h2>
        {children}
        {error && <p className="error" role="alert">{error}</p>}
        <div className="modal-actions">
          <button type="button" className="secondary" onClick={() => dialog.current?.close()}>Cancel</button>
          <button disabled={busy}>{busy ? 'Working…' : submitLabel}</button>
        </div>
      </form>
    </dialog>
  )
}
