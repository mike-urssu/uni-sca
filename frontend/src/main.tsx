import { StrictMode, useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import { ScanForm } from './pages/ScanForm'
import { JobList } from './pages/JobList'
import { JobView } from './pages/JobView'

// Hash routes: #/ (new scan), #/jobs, #/jobs/<id>
function useHash() {
  const [hash, setHash] = useState(location.hash)
  useEffect(() => {
    const on = () => setHash(location.hash)
    addEventListener('hashchange', on)
    return () => removeEventListener('hashchange', on)
  }, [])
  return hash.replace(/^#/, '') || '/'
}

function App() {
  const route = useHash()
  const job = route.match(/^\/jobs\/([\w-]+)$/)?.[1]
  return (
    <>
      <header>
        <b>SCA Integration</b>
        <nav><a href="#/">New scan</a><a href="#/jobs">Scans</a></nav>
      </header>
      <main>
        {job ? <JobView id={job} /> : route === '/jobs' ? <JobList /> : <ScanForm />}
      </main>
    </>
  )
}

createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>)
