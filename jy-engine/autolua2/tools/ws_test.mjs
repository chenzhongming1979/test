// WS protocol stack auto test: capabilities + ping + stream binary frames
// Node 22+ native WebSocket client, mirrors what a browser would do.
const url = process.argv[2] || "ws://127.0.0.1:18080";

function log(tag, msg) {
    console.log(`[${tag}] ${msg}`);
}

const ws = new WebSocket(url, { perMessageDeflate: false });
let binFrames = 0;
let done = false;

ws.onopen = () => {
    log("WS", `connected: ${url}`);
    ws.send(JSON.stringify({ type: "capabilities", data: {} }));
};

ws.onmessage = async (ev) => {
    if (typeof ev.data === "string") {
        const msg = JSON.parse(ev.data);
        switch (msg.type) {
            case "hello":
                log("hello", `clientId=${msg.data.clientId} device=${msg.data.device_name}`);
                ws.send(JSON.stringify({ type: "ping", data: {} }));
                break;
            case "capabilities":
                log("capabilities", JSON.stringify(msg.data).slice(0, 400));
                ws.send(JSON.stringify({ type: "ping", data: {} }));
                break;
            case "pong":
                log("pong", "ok");
                // start jpeg stream at 5fps
                ws.send(JSON.stringify({ type: "stream_start", data: { codec: "jpeg", fps: 5, quality: 50, scale: 1.0 } }));
                break;
            case "stream_started":
                log("stream_started", `w=${msg.data.w} h=${msg.data.h} (waiting binary frames...)`);
                break;
            case "stream_stopped":
                log("stream_stopped", "ok");
                finish();
                break;
            case "error":
                log("error", JSON.stringify(msg.data));
                if (!done) finish();
                break;
            default:
                log(msg.type, JSON.stringify(msg.data).slice(0, 200));
        }
    } else {
        // binary frame: [4B seq][4B w][4B h][4B len][jpeg]
        const buf = Buffer.from(await ev.data.arrayBuffer());
        binFrames++;
        const seq = buf.readUInt32BE(0), w = buf.readUInt32BE(4), h = buf.readUInt32BE(8), len = buf.readUInt32BE(12);
        const magic = buf.length > 16 ? buf[16].toString(16) + buf[17].toString(16) : "?";
        log("frame", `#${binFrames} seq=${seq} ${w}x${h} payload=${len}B total=${buf.length}B jpegMagic=0x${magic}`);
        if (binFrames === 3) {
            ws.send(JSON.stringify({ type: "stream_stop", data: {} }));
        }
    }
};

ws.onerror = (e) => {
    log("error", `ws error: ${e.message || e}`);
    finish(1);
};

ws.onclose = (e) => {
    log("close", `code=${e.code} reason=${e.reason}`);
    if (!done) finish(binFrames >= 3 ? 0 : 1);
};

function finish(code) {
    if (done) return;
    done = true;
    log("result", binFrames >= 3 ? "PASS (3+ binary frames received)" : `FAIL (binFrames=${binFrames})`);
    setTimeout(() => process.exit(code || 0), 200);
}

// global watchdog
setTimeout(() => {
    if (!done) { log("timeout", "test did not finish in 30s"); finish(1); }
}, 30000);
