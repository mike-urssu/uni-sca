import type { DiffRow, JobStatus, Product, ProductVersion, ScanResult, Source, SourceInput } from './types'

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = await res.json().catch(() => null)
    throw new Error(body?.error ?? `${res.status} ${res.statusText}`)
  }
  return res.status === 204 ? (undefined as T) : res.json()
}

// POST must be JSON (HostGuard), even without a body
const send = <T>(method: string, url: string, body?: unknown) =>
  fetch(url, { method, headers: { 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) }).then(json<T>)

export const getScan = (id: string) => fetch(`/api/scans/${id}`).then(json<JobStatus>)
export const getResult = (id: string) => fetch(`/api/scans/${id}/result`).then(json<ScanResult>)
export const fileUrl = (id: string, name: string) => `/api/scans/${id}/files/${name}`

const P = '/api/products'
export const listProducts = () => fetch(P).then(json<Product[]>)
export const getProduct = (pid: string) => fetch(`${P}/${pid}`).then(json<Product>)
export const createProduct = (name: string, version: string, sources: SourceInput[]) => send<Product>('POST', P, { name, version, sources })
export const renameProduct = (pid: string, name: string) => send<Product>('PATCH', `${P}/${pid}`, { name })
export const deleteProduct = (pid: string) => send<void>('DELETE', `${P}/${pid}`)

const V = (pid: string, vid: string) => `${P}/${pid}/versions/${vid}`
export const addVersion = (pid: string, version: string, sources: SourceInput[]) => send<ProductVersion>('POST', `${P}/${pid}/versions`, { version, sources })
export const renameVersion = (pid: string, vid: string, name: string) => send<Product>('PATCH', V(pid, vid), { name })
export const deleteVersion = (pid: string, vid: string) => send<void>('DELETE', V(pid, vid))
export const addSource = (pid: string, vid: string, s: SourceInput) => send<Source>('POST', `${V(pid, vid)}/sources`, s)
export const deleteSource = (pid: string, vid: string, sid: string) => send<void>('DELETE', `${V(pid, vid)}/sources/${sid}`)
export const rescan = (pid: string, vid: string, sid?: string) =>
  send<string[]>('POST', `${V(pid, vid)}/scans${sid ? `?source=${sid}` : ''}`)
export const versionScans = (pid: string, vid: string) => fetch(`${V(pid, vid)}/scans`).then(json<JobStatus[]>)
/** The version's sources combined; `sources` limits it to those ids. */
export const versionResult = (pid: string, vid: string, sources?: string[]) =>
  fetch(`${V(pid, vid)}/result${sources ? `?sources=${sources.map(encodeURIComponent).join(',')}` : ''}`).then(json<ScanResult>)
export const versionFileUrl = (pid: string, vid: string, name: string) => `${V(pid, vid)}/files/${name}`
export const compareVersions = (pid: string, a: string, b: string) => fetch(`${P}/${pid}/compare?a=${a}&b=${b}`).then(json<DiffRow[]>)

/** Sets a component's copyright by hand for every product; a blank text clears it. */
export const setCopyright = (key: string, text: string) => send<void>('PUT', '/api/copyrights', { key, text })

/** Live status via SSE; returns an unsubscribe function. */
export function watch(id: string, onStatus: (s: JobStatus) => void): () => void {
  const es = new EventSource(`/api/scans/${id}/events`)
  const handle = (e: MessageEvent) => onStatus(JSON.parse(e.data))
  es.addEventListener('stage', handle)
  es.addEventListener('done', (e) => { handle(e as MessageEvent); es.close() })
  return () => es.close()
}
