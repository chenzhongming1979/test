// rapid click stress test: down/up pairs at decreasing intervals
const url = process.argv[2] || "ws://127.0.0.1:18080";
const ws = new WebSocket(url);
let ok = 0, fail = 0, pending = 0;

function click(x, y) {
    ws.send(JSON.stringify({ type: "inject_touch", data: { action: "down", x, y } }));
    ws.send(JSON.stringify({ type: "inject_touch", data: { action: "up", x, y } }));
    pending += 2;
}

ws.onopen = () => {
    console.log("connected, rapid clicking...");
    let x = 200, y = 400;
    // burst 1: 20 clicks no delay
    for (let i = 0; i < 20; i++) { click(200 + (i % 5) * 30, 400); }
    // burst 2 after 1s: 20 clicks 10ms apart
    setTimeout(() => {
        for (let i = 0; i < 20; i++) setTimeout(() => click(200 + (i % 5) * 30, 600), i * 10);
    }, 1000);
    // burst 3 after 3s: 10 clicks 30ms apart
    setTimeout(() => {
        for (let i = 0; i < 10; i++) setTimeout(() => click(300 + (i % 5) * 30, 800), i * 30);
    }, 3000);
    setTimeout(() => { console.log(`done ok=${ok} fail=${fail}`); process.exit(0); }, 6000);
};
ws.onmessage = (ev) => {
    if (typeof ev.data !== "string") return;
    const m = JSON.parse(ev.data);
    if (m.type === "inject_result") { if (m.data.ok) ok++; else fail++; }
};
ws.onerror = (e) => { console.log("ws error", e.message); process.exit(1); };
