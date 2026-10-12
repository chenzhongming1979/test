// WS test 3: inject_touch + run_script only
const url = process.argv[2] || "ws://127.0.0.1:18080";
function log(tag, msg) { console.log(`[${tag}] ${msg}`); }
const ws = new WebSocket(url);
let done = false, step = 0;

ws.onopen = () => {
    log("WS", "connected");
    ws.send(JSON.stringify({ type: "inject_touch", data: { action: "down", x: 360, y: 1000 } }));
};
ws.onmessage = (ev) => {
    if (typeof ev.data !== "string") return;
    const msg = JSON.parse(ev.data);
    if (msg.type === "inject_result") {
        log("inject_result", `step=${step} ok=${msg.data.ok} mode=${msg.data.mode || "?"}`);
        if (step === 0) ws.send(JSON.stringify({ type: "inject_touch", data: { action: "up", x: 360, y: 1000 } }));
        if (step === 1) ws.send(JSON.stringify({ type: "run_script", data: { path: "/main.lua" } }));
        if (step >= 2) finish();
        step++;
    } else if (msg.type === "script_started") {
        log("script_started", `ok=${msg.data.ok} path=${msg.data.path}`);
        ws.send(JSON.stringify({ type: "stop_script", data: {} }));
    } else if (msg.type === "error") {
        log("error", JSON.stringify(msg.data));
        if (step >= 2) finish(); else finish(1);
    }
};
ws.onerror = (e) => { log("error", e.message || "ws"); finish(1); };
function finish(code) { if (done) return; done = true; setTimeout(() => process.exit(code || 0), 200); }
setTimeout(() => { if (!done) { log("timeout", "15s"); finish(1); } }, 15000);
