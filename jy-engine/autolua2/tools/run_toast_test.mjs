// Toast 测试触发脚本：WS 连接设备 IDE 服务器(8080)发送 run_script
// 用法: node run_toast_test.mjs [设备IP]  (默认 127.0.0.1，配合 adb forward tcp:18080 tcp:8080)
const host = process.argv[2] || '127.0.0.1';
const port = process.argv[3] || '18080';
const ws = new WebSocket(`ws://${host}:${port}`);

ws.onopen = () => {
  console.log('[WS] connected');
  ws.send(JSON.stringify({ type: 'run_script', data: { path: '/toast_test.lua' } }));
  console.log('[WS] run_script /toast_test.lua sent');
};
ws.onmessage = (e) => {
  console.log('[WS] recv:', e.data);
  const msg = JSON.parse(e.data);
  if (msg.type === 'script_started') {
    console.log('[OK] script submitted, watch the device screen for toasts');
    setTimeout(() => { ws.close(); process.exit(0); }, 500);
  }
};
ws.onerror = (e) => { console.error('[WS] error:', e.message || e); process.exit(1); };
setTimeout(() => { console.error('[WS] timeout'); process.exit(1); }, 5000);
