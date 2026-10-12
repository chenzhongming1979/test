// UI smoke test remote runner (node + ws): push uitest/main.lua then run
// Usage: node run_ui.js [wsPort] [timeoutSec]
const fs = require('fs');
const path = require('path');
const WebSocket = require(path.join(__dirname, '..', '..', 'vscode-plugin', 'node_modules', 'ws'));

const wsPort = process.argv[2] || '18080';
const timeoutSec = parseInt(process.argv[3] || '120', 10);
const scriptName = process.argv[4] || 'main.lua';
const logFile = path.join(__dirname, 'uitest_run.log');
const scriptPath = path.join(__dirname, scriptName);
const pushRelPath = 'uitest/' + scriptName;

fs.writeFileSync(logFile, `=== uitest run ${new Date().toLocaleString()} ===\n`);
function log(msg) {
  const line = `[${new Date().toLocaleTimeString()}] ${msg}`;
  console.log(line);
  fs.appendFileSync(logFile, line + '\n');
}

const ws = new WebSocket(`ws://127.0.0.1:${wsPort}`);
ws.on('error', (e) => { log('WS error: ' + e.message); process.exit(1); });
ws.on('message', (data) => handle(String(data)));

let helloSeen = false, started = false, idle = false, scriptSent = false;
const startAt = Date.now();
function send(obj) { ws.send(JSON.stringify(obj)); }

function handle(msg) {
  if (!helloSeen) { helloSeen = true; log('hello: ' + msg.slice(0, 120)); pushScript(); return; }
  let frame;
  try { frame = JSON.parse(msg); } catch (t) { return; }
  const type = frame.type;
  if (type === 'push_result') { log('push_result'); runScript(); return; }
  if (type === 'script_started') { started = true; log('script_started'); return; }
  if (type === 'script_state') {
    log('script_state: ' + JSON.stringify(frame.data));
    if (started && frame.data && frame.data.state === 'idle') { idle = true; finish(); }
    return;
  }
  if (type === 'log') {
    const line = frame.data && frame.data.line !== undefined ? frame.data.line : JSON.stringify(frame.data);
    log('L: ' + line);
    return;
  }
  if (type === 'error') { log('error: ' + msg.slice(0, 500)); return; }
}

function pushScript() {
  const lua = fs.readFileSync(scriptPath, 'utf8');
  send({ type: 'push_files_start', data: {} });
  send({ type: 'push_file', data: { path: pushRelPath, content: lua, encoding: 'utf8' } });
  send({ type: 'push_files_end', data: {} });
  log('pushed: ' + pushRelPath);
  setTimeout(() => { if (!scriptSent) { log('WARN: no push_result, run anyway'); runScript(); } }, 10000).unref();
}

function runScript() {
  if (scriptSent) return;
  scriptSent = true;
  send({ type: 'run_script', data: { path: '/' + pushRelPath } });
  log('run_script sent');
}

function finish() {
  log(`=== done: started=${started} idle=${idle} elapsed=${((Date.now() - startAt) / 1000).toFixed(1)}s ===`);
  try { ws.close(); } catch (t) {}
  process.exit(idle ? 0 : 2);
}

setInterval(() => {
  if (Date.now() - startAt > timeoutSec * 1000) {
    log(`WARN: timeout (${timeoutSec}s) started=${started} idle=${idle}`);
    process.exit(3);
  }
}, 1000).unref();
