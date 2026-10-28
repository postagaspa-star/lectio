// Un server di file minimo per le foto delle pagine: serve SOLO la cartella pc/ui, SOLO su 127.0.0.1,
// in sola lettura. Si ferma da solo dopo `--per` secondi (predefinito 600). Scrive la porta su stdout.
// Uso: node servi.js [--porta 0] [--per 600]
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');

const UI = path.resolve(__dirname, '..', '..', 'ui');
const arg = (nome, pred) => { const i = process.argv.indexOf(nome); return i >= 0 ? process.argv[i + 1] : pred; };
const TIPI = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.ttf': 'font/ttf', '.txt': 'text/plain; charset=utf-8', '.png': 'image/png', '.svg': 'image/svg+xml' };

const server = http.createServer((req, res) => {
  const percorso = decodeURIComponent((req.url || '/').split('?')[0]).replace(/^\/+/, '') || 'index.html';
  const file = path.resolve(UI, percorso);
  if (!file.startsWith(UI + path.sep) || req.method !== 'GET') { res.writeHead(404); res.end(); return; }
  fs.readFile(file, (err, dati) => {
    if (err) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'Content-Type': TIPI[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    res.end(dati);
  });
});
server.listen(Number(arg('--porta', 0)), '127.0.0.1', () => {
  process.stdout.write(String(server.address().port) + '\n');
});
setTimeout(() => process.exit(0), Number(arg('--per', 600)) * 1000).unref();
