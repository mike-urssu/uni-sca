import { StrictMode, useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import { ProductList } from './pages/ProductList'
import { ProductView } from './pages/ProductView'
import { VersionView } from './pages/VersionView'
import { Compare } from './pages/Compare'
import { JobView } from './pages/JobView'

// Hash routes: #/ (products), #/products/<pid>, #/products/<pid>/versions/<vid>, #/products/<pid>/compare?a=<vid>&b=<vid>, #/jobs/<id>
function useHash() {
  const [hash, setHash] = useState(location.hash)
  useEffect(() => {
    const on = () => setHash(location.hash)
    addEventListener('hashchange', on)
    return () => removeEventListener('hashchange', on)
  }, [])
  return hash.replace(/^#/, '') || '/'
}

function page(route: string) {
  const [path, query] = route.split('?')
  const q = new URLSearchParams(query)
  let m
  if ((m = path.match(/^\/jobs\/([\w-]+)$/))) return <JobView id={m[1]} />
  if ((m = path.match(/^\/products\/(\w+)\/versions\/(\w+)$/))) return <VersionView key={m[2]} pid={m[1]} vid={m[2]} />
  if ((m = path.match(/^\/products\/(\w+)\/compare$/)) && q.get('a') && q.get('b')) return <Compare pid={m[1]} a={q.get('a')!} b={q.get('b')!} />
  if ((m = path.match(/^\/products\/(\w+)$/))) return <ProductView key={m[1]} pid={m[1]} />
  return <ProductList />
}

function App() {
  const route = useHash()
  return (
    <>
      <header>
        <b>uni-sca</b>
        <nav><a href="#/" className="active">Products</a></nav>
      </header>
      <main>{page(route)}</main>
    </>
  )
}

createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>)
