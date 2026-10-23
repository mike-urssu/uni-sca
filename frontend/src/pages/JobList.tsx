import { useEffect, useState } from 'react'
import { listScans } from '../api'
import type { JobStatus } from '../types'

export function JobList() {
  const [jobs, setJobs] = useState<JobStatus[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const load = () => listScans().then(setJobs, (e) => setError(e.message))
    load()
    const t = setInterval(load, 5000)
    return () => clearInterval(t)
  }, [])

  if (error) return <p className="error">{error}</p>
  if (!jobs) return <p className="muted">Loading…</p>
  if (jobs.length === 0) return <p className="muted">No scans yet. <a href="#/">Start one.</a></p>
  return (
    <div className="table-wrap"><table>
      <thead><tr><th>Status</th><th>Target</th><th>Started</th><th>Stage / error</th></tr></thead>
      <tbody>
        {jobs.map((j) => (
          <tr key={j.id} className="clickable" onClick={() => { location.hash = `#/jobs/${j.id}` }}>
            <td><span className={`badge ${j.status}`}>{j.status}</span></td>
            <td>{j.input.target}{j.input.ref ? ` @ ${j.input.ref}` : ''}</td>
            <td>{new Date(j.createdAt).toLocaleString()}</td>
            <td className={j.error ? 'error' : 'muted'}>{j.error ?? j.stage ?? ''}</td>
          </tr>
        ))}
      </tbody>
    </table></div>
  )
}
