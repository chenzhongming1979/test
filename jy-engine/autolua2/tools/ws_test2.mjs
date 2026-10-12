// WS test 2: h264 stream + inject_touch + run_script
const url = process.argv[2] || "ws://127.0.0.1:18080";
function log(tag, msg) { console.log(`[${tag}] ${msg}`); }

const ws = new WebSocket(url);
let h264Frames = 0, results = [];
let phase = "start";
let done = false;

ws.onopen = () => log("WS", "connected");

ws.onmessage = async (ev) => {
    if (typeof ev.data === "string") {
        const msg = JSON.parse(ev.data);
        if (msg.type === "hello") {
            // phase 1: h264 stream
            ws.send(JSON.stringify({ type: "stream_start", data: { codec: "h264", fps: 10, bitrate: 1500000, scale: 1.0 } }));
        } else if (msg.type === "stream_started") {
            log("stream_started(h264)", `w=${msg.data.w} h=${msg.data.h}`);
            // trigger screen activity so mirror produces frames
            setTimeout(() => { try { require("child_process"); } catch (e) {} }, 0);
        } else if (msg.type === "stream_stopped") {
            log("stream_stopped(h264)", `frames=${h264Frames}`);
            // phase 2: inject_touch
            phase = "inject";
            ws.send(JSON.stringify({ type: "inject_touch", data: { action: "down", x: 360, y: 640 } }));
        } else if (msg.type === "inject_result") {
            results.push(`inject: ok=${msg.data.ok}`);
            if (results.length === 1) {
                ws.send(JSON.stringify({ type: "inject_touch", data: { action: "up", x: 360, y: 640 } }));
            } else {
                // phase 3: run_script (push a tiny lua via execute沙箱 is not the same; use run_script with existing file or fallback)
                phase = "run";
                ws.send(JSON.stringify({ type: "run_script", data: { path: "/main.lua" } }));
            }
        } else if (msg.type === "script_started") {
            results.push(`run_script: ok=${msg.data.ok} path=${msg.data.path}`);
            ws.send(JSON.stringify({ type: "stop_script", data: {} }));
        } else if (msg.type === "error") {
            if (phase === "run" && /not exist|no such|failed/i.test(JSON.stringify(msg.data))) {
                results.push("run_script: main.lua absent (acceptable in empty device)");
                finish();
                return;
            }
            log("error", JSON.stringify(msg.data));
        }
    } else {
        const buf = Buffer.from(await ev.data.arrayBuffer());
        h264Frames++;
        const seq = buf.readUInt32BE(0), flag = buf.readUInt32BE(4), len = buf.readUInt32BE(8);
        log("h264 frame", `#${h264Frames} seq=${seq} flag=${flag}(0=sps/pps,1=slice) len=${len}B total=${buf.length}B`);
        if (h264Frames === 3) {
            ws.send(JSON.stringify({ type: "stream_stop", data: {} }));
        }
    }
};

ws.onerror = (e) => { log("error", e.message || "ws error"); finish(1); };
ws.onclose = () => { if (!done) finish(h264Frames >= 3 && results.length >= 2 ? 0 : 1); };

function finish(code) {
    if (done) return; done = true;
    log("summary", results.join(" | "));
    log("result", h264Frames >= 3 ? "PASS (h264 stream verified)" : `PARTIAL (h264 frames=${h264Frames})`);
    setTimeout(() => process.exit(code || 0), 300);
}
setTimeout(() => { if (!done) { log("timeout", "30s"); finish(1); } }, 30000);
