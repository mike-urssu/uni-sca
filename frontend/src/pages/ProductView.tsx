import { useEffect, useState, type FormEvent } from 'react'
import { addVersion, deleteProduct, getProduct, listProducts, renameProduct } from '../api'
import type { Product, SourceInput } from '../types'
import { SourceFields, clean, emptySource } from '../components/SourceFields'
import { ModalForm } from '../components/Modal'
import { TrashIcon } from '../components/Icons'
import { InlineTitle } from '../components/InlineTitle'

export function ProductView({ pid }: { pid: string }) {
  const [product, setProduct] = useState<Product | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)
  useEffect(() => { getProduct(pid).then(setProduct, (e) => setError(e.message)) }, [pid])

  async function rename(name: string) {
    // renaming to another product's name merges into that product, which has its own id
    const into = (await listProducts()).find((o) => o.id !== pid && o.name.toLowerCase() === name.toLowerCase())
    if (into && !confirm(`"${into.name}" already exists. Merge all versions of ${product!.name} into it? This cannot be undone.`)) return
    renameProduct(pid, name).then((p) => { if (p.id === pid) setProduct(p); else location.hash = `#/products/${p.id}` }, (e) => alert(e.message))
  }
  async function remove() {
    if (!confirm(`Delete ${product!.name} with all its versions and scans?`)) return
    deleteProduct(pid).then(() => { location.hash = '#/' }, (e) => alert(e.message))
  }

  if (error) return <p className="error">{error}</p>
  if (!product) return <p className="muted">Loading…</p>
  return <>
    <div className="page-head">
      <InlineTitle value={product.name} label="product name" onSave={rename} />
      <button className="icon danger" onClick={remove} aria-label={`Delete ${product.name}`} title="Delete product"><TrashIcon /></button>
    </div>
    <div className="page-head end">
      <button className="primary" onClick={() => setAdding(true)}>+ Add version</button>
    </div>
    {product.versions.length > 0 && <div className="table-wrap"><table>
      <thead><tr><th>Version</th><th>Sources</th><th>Registered</th></tr></thead>
      <tbody>{[...product.versions].reverse().map((v) => (
        <tr key={v.id} className="clickable" onClick={() => { location.hash = `#/products/${pid}/versions/${v.id}` }}>
          <td>{v.version}</td><td>{v.sources.map((s) => <div key={s.id}>{s.target}{s.ref ? ` @ ${s.ref}` : ''}</div>)}</td>
          <td>{new Date(v.createdAt).toLocaleString()}</td>
        </tr>))}</tbody>
    </table></div>}
    {product.versions.length === 0 && <p className="muted">No versions yet.</p>}
    {adding && <NewVersion product={product} onClose={() => setAdding(false)} />}
  </>
}

/** Modal form. Starts from the latest version's sources, so a new release usually only needs its refs and tags changed. */
function NewVersion({ product, onClose }: { product: Product; onClose: () => void }) {
  const last = product.versions.at(-1)
  const [version, setVersion] = useState('')
  const [sources, setSources] = useState<SourceInput[]>(() =>
    last?.sources.length ? last.sources.map((s) => ({ kind: s.kind, target: s.target, ref: s.ref ?? '', deep: s.deep })) : [emptySource()])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null); setBusy(true)
    try {
      const v = await addVersion(product.id, version.trim(), sources.map(clean))
      location.hash = `#/products/${product.id}/versions/${v.id}`
    } catch (err) {
      setError((err as Error).message)
    } finally { setBusy(false) }
  }

  return (
    <ModalForm title="Add version" submitLabel="Add and scan" busy={busy} error={error} onSubmit={submit} onClose={onClose}>
      {last && <p className="muted">Sources are copied from {last.version}; change refs and tags as needed.</p>}
      <label>Version<input type="text" required autoFocus value={version} onChange={(e) => setVersion(e.target.value)} /></label>
      <SourceFields sources={sources} onChange={setSources} />
    </ModalForm>
  )
}
