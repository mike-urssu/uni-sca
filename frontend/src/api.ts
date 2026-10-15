import type { JobStatus, ScanResult } from './types'

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = await res.json().catch(() => null)
    throw new Error(body?.error ?? `${res.status} ${res.statusText}`)
  }
  return res.json()
}

export function submitScan(body: { target: string; ref?: string; deep?: boolean } | FormData): Promise<{ id: string }> {
  const init: RequestInit = body instanceof FormData
    ? { method: 'POST', body }
    : { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) }
  return fetch('/api/scans', init).then(json<{ id: string }>)
}

export const listScans = () => fetch('/api/scans').then(json<JobStatus[]>)
export const getScan = (id: string) => fetch(`/api/scans/${id}`).then(json<JobStatus>)
export const getResult = (id: string) => fetch(`/api/scans/${id}/result`).then(json<ScanResult>)
export const fileUrl = (id: string, name: string) => `/api/scans/${id}/files/${name}`

/** Live status via SSE; returns an unsubscribe function. */
export function watch(id: string, onStatus: (s: JobStatus) => void): () => void {
  const es = new EventSource(`/api/scans/${id}/events`)
  const handle = (e: MessageEvent) => onStatus(JSON.parse(e.data))
  es.addEventListener('stage', handle)
  es.addEventListener('done', (e) => { handle(e as MessageEvent); es.close() })
  return () => es.close()
}
