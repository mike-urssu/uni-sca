import { useEffect, useState, type FormEvent } from 'react'
import { createProduct, listProducts } from '../api'
import type { Product, SourceInput } from '../types'
import { SourceFields, clean, emptySource } from '../components/SourceFields'
import { ModalForm } from '../components/Modal'

export function ProductList() {
  const [products, setProducts] = useState<Product[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)
  useEffect(() => { listProducts().then(setProducts, (e) => setError(e.message)) }, [])

  if (error) return <p className="error">{error}</p>
  if (!products) return <p className="muted">Loading…</p>
  return <>
    <div className="page-head">
      <h2>Products</h2>
      <button className="primary" onClick={() => setAdding(true)}>+ Register product</button>
    </div>
    {products.length === 0 ? <div className="card empty">
      <p>No products yet.</p>
      <p className="muted">Register a product with its first version and the GitHub repositories or docker images that make it up.</p>
    </div> : <div className="table-wrap"><table>
      <thead><tr><th>Product</th><th>Latest version</th><th>Versions</th><th>Registered</th></tr></thead>
      <tbody>{products.map((p) => (
        <tr key={p.id} className="clickable" onClick={() => { location.hash = `#/products/${p.id}` }}>
          <td>{p.name}</td><td>{p.versions.at(-1)?.version ?? ''}</td><td>{p.versions.length}</td>
          <td>{new Date(p.createdAt).toLocaleString()}</td>
        </tr>))}</tbody>
    </table></div>}
    {adding && <NewProduct onClose={() => setAdding(false)} />}
  </>
}

function NewProduct({ onClose }: { onClose: () => void }) {
  const [name, setName] = useState('')
  const [version, setVersion] = useState('')
  const [sources, setSources] = useState<SourceInput[]>([emptySource()])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null); setBusy(true)
    try {
      const p = await createProduct(name.trim(), version.trim(), sources.map(clean))
      location.hash = `#/products/${p.id}/versions/${p.versions.at(-1)!.id}`   // an existing name gets the new version appended
    } catch (err) {
      setError((err as Error).message)
    } finally { setBusy(false) }
  }

  return (
    <ModalForm title="Register product" submitLabel="Register and scan" busy={busy} error={error} onSubmit={submit} onClose={onClose}>
      <label>Product name<input type="text" required autoFocus value={name} onChange={(e) => setName(e.target.value)} /></label>
      <p className="muted">An existing product name adds this as a new version of that product.</p>
      <label>Version<input type="text" required placeholder="1.0.0" value={version} onChange={(e) => setVersion(e.target.value)} /></label>
      <SourceFields sources={sources} onChange={setSources} />
    </ModalForm>
  )
}
