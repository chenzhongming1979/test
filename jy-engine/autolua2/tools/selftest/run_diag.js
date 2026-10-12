// one-off diagnostic runner: push & run a single lua file (same protocol as run_remote.js)
const fs = require('fs');
const path = require('path');
const WebSocket = require(path.join(__dirname, '..', '..', 'vscode-plugin', 'node_modules', 'ws'));

const script = process.argv[2] || 'diag_ime.lua';
const wsPort = process.argv[3] || '18080';
const scriptPath = path.join(__dirname, script);
const pushRelPath = 'selftest/' + script;

const ws = new WebSocket(`ws://127.0.0.1:${wsPort}`);
ws.on('error', (e) => { console.log('WS error: ' + e.message); process.exit(1); });
ws.on('message', (d) => handle(String(d)));

let helloSeen = false, started = false, sent = false;
const t0 = Date.now();

function send(o) { ws.send(JSON.stringify(o)); }
function handle(m) {
  if (!helloSeen) { helloSeen = true; push(); return; }
  let f; try { f = JSON.parse(m); } catch (t) { return; }
  if (f.type === 'push_result') { run(); return; }
  if (f.type === 'script_started') { started = true; return; }
  if (f.type === 'script_state') {
    if (started && f.data && f.data.state === 'idle') { console.log('=== done ==='); process.exit(0); }
    return;
  }
  if (f.type === 'log') {
    const line = f.data && f.data.line !== undefined ? f.data.line : JSON.stringify(f.data);
    console.log('L: ' + line); return;
  }
  if (f.type === 'error') { console.log('error: ' + m.slice(0, 400)); return; }
}
function push() {
  const lua = fs.readFileSync(scriptPath, 'utf8');
  send({ type: 'push_files_start', data: {} });
  send({ type: 'push_file', data: { path: pushRelPath, content: lua, encoding: 'utf8' } });
  send({ type: 'push_files_end', data: {} });
  setTimeout(() => { if (!sent) run(); }, 8000).unref();
}
function run() {
  if (sent) return; sent = true;
  send({ type: 'run_script', data: { path: '/' + pushRelPath } });
}
setInterval(() => {
  if (Date.now() - t0 > 60000) { console.log('=== timeout ==='); process.exit(3); }
}, 1000).unref();
