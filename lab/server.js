// SCA comparison lab: shows index.html and streams the output of commands each pane sends.
// No dependencies. Run with `node server.js` → http://localhost:4000
const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const PORT = Number(process.env.PORT) || 4000;
const HOSTS = new Set([`localhost:${PORT}`, `127.0.0.1:${PORT}`]);

http.createServer((req, res) => {
  // This server runs arbitrary commands, so it only accepts local requests from this page:
  // the Host check blocks DNS rebinding, and requiring JSON forces a CORS preflight on other sites, which this server never answers
  if (!HOSTS.has(req.headers.host) || (req.headers.origin && !HOSTS.has(req.headers.origin.replace(/^https?:\/\//, '')))) {
    return res.writeHead(403).end('forbidden');
  }
  if (req.method === 'GET' && req.url === '/') {
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    return fs.createReadStream(path.join(__dirname, 'index.html')).pipe(res);
  }
  if (req.method !== 'POST' || req.url !== '/run' || req.headers['content-type'] !== 'application/json') {
    return res.writeHead(404).end();
  }
  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', () => {
    let cmd, img;
    try { ({ cmd, img } = JSON.parse(body)); } catch { return res.writeHead(400).end('bad json'); }
    if (typeof cmd !== 'string') return res.writeHead(400).end('cmd required');
    res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' });
    const child = spawn('bash', ['-c', cmd], {
      cwd: __dirname,
      detached: true, // own process group, so Stop kills the whole pipeline
      env: { ...process.env, IMG: img || 'node:16-slim', NO_COLOR: '1', TERM: 'dumb' },
    });
    child.stdout.on('data', (c) => res.write(c));
    child.stderr.on('data', (c) => res.write(c));
    child.on('close', (code) => res.end(`\n\u0000EXIT:${code}`));
    res.on('close', () => { if (child.exitCode === null) try { process.kill(-child.pid, 'SIGTERM'); } catch {} });
  });
}).listen(PORT, '127.0.0.1', () => console.log(`SCA lab → http://localhost:${PORT}`));
