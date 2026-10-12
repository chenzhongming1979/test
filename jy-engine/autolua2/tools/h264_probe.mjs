// H264 stream frame analyzer: dump avcC structure + NALU type sequence
const url = process.argv[2] || "ws://127.0.0.1:18080";
function hex(b, n = 32) { return Array.from(b.slice(0, n)).map(x => x.toString(16).padStart(2, "0")).join(" "); }

const ws = new WebSocket(url);
let gotCfg = false, slices = 0;

ws.onopen = () => {
    console.log("connected");
    ws.send(JSON.stringify({ type: "stream_start", data: { codec: "h264", fps: 10, bitrate: 1500000, scale: 1.0 } }));
};

ws.onmessage = async (ev) => {
    if (typeof ev.data === "string") {
        const m = JSON.parse(ev.data);
        if (m.type === "stream_started") console.log("stream_started", m.data);
        if (m.type === "error") { console.log("ERROR:", JSON.stringify(m.data)); process.exit(1); }
        return;
    }
    const buf = Buffer.from(await ev.data.arrayBuffer());
    const seq = buf.readUInt32BE(0), flag = buf.readUInt32BE(4), len = buf.readUInt32BE(8);
    const payload = buf.subarray(12);

    if (flag === 0 && !gotCfg) {
        gotCfg = true;
        console.log("=== avcC description (flag=0) ===");
        console.log("total len:", payload.length);
        console.log("first 48 bytes:", hex(payload, 48));
        // check if it wrongly starts with a box header (4B size + 'avcC')
        const magic = payload.subarray(4, 8).toString("ascii");
        console.log("bytes[4..8] as ascii:", JSON.stringify(magic), "(should NOT be 'avcC' - description is the record, not the box)");
        console.log("configurationVersion:", payload[0]);
        console.log("profile/compat/level:", payload[1].toString(16), payload[2].toString(16), payload[3].toString(16));
        console.log("lengthSizeMinusOne:", payload[4] & 0x03);
        console.log("numOfSPS:", payload[5] & 0x1F);
        let off = 6;
        for (let i = 0; i < (payload[5] & 0x1F); i++) {
            const spsLen = payload.readUInt16BE(off); off += 2;
            const sps = payload.subarray(off, off + spsLen); off += spsLen;
            console.log(`  SPS[${i}] len=${spsLen} type=${sps[0] & 0x1F} head=${hex(sps, 12)}`);
        }
        const numPps = payload[off] & 0x1F; off += 1;
        for (let i = 0; i < numPps; i++) {
            const ppsLen = payload.readUInt16BE(off); off += 2;
            const pps = payload.subarray(off, off + ppsLen); off += ppsLen;
            console.log(`  PPS[${i}] len=${ppsLen} type=${pps[0] & 0x1F} head=${hex(pps, 8)}`);
        }
        console.log("codec string should be: avc1." + payload[1].toString(16).padStart(2, "0") + payload[2].toString(16).padStart(2, "0") + payload[3].toString(16).padStart(2, "0"));
        console.log("consumed:", off, "of", payload.length, off === payload.length ? "(clean)" : "(TRAILING BYTES!)");
        return;
    }

    if (flag === 1) {
        slices++;
        // parse AVCC NALU sequence
        const types = [];
        let off = 0, isIdr = false, bad = 0;
        while (off + 4 <= payload.length) {
            const nl = payload.readUInt32BE(off); off += 4;
            if (nl < 1 || off + nl > payload.length) { bad++; break; }
            let t = payload[off] & 0x1F;
            // strip ref-idc for reporting
            types.push(t + "(" + ((payload[off] >> 5) & 3) + ")");
            if (t === 5) isIdr = true;
            off += nl;
        }
        console.log(`slice#${slices} seq=${seq} len=${payload.length} IDR=${isIdr} badLen=${bad} NALUs: ${types.join(" ")}`);
        if (slices === 1) console.log("  first-32B:", hex(payload, 32));
        if (slices >= 5) { console.log("done"); ws.send(JSON.stringify({ type: "stream_stop", data: {} })); setTimeout(() => process.exit(0), 500); }
    }
};

ws.onerror = (e) => { console.log("ws error:", e.message || e); process.exit(1); };
setTimeout(() => { console.log("timeout"); process.exit(1); }, 25000);
