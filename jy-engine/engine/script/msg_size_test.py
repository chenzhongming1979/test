# -*- coding: utf-8 -*-
# WS 消息长度上限实测：向 cloud 通道发送指定大小的 JSON 并统计回包
# 用法：python msg_size_test.py [--sizes 64,1024,65536,1048576,5242880]
import argparse
import json
import subprocess
import time

import websocket

ap = argparse.ArgumentParser()
ap.add_argument("--device", default="emulator-5558")
ap.add_argument("--port", type=int, default=9300)
ap.add_argument("--local", type=int, default=19300)
ap.add_argument("--sizes", default="1024,65536,262144,1048576,5242880", help="逗号分隔的 payload 字节数")
args = ap.parse_args()

subprocess.run(["adb", "-s", args.device, "forward", "tcp:%d" % args.local, "tcp:%d" % args.port], check=True)
url = "ws://127.0.0.1:%d" % args.local

for size in [int(s) for s in args.sizes.split(",")]:
    payload = {"op": "size_test", "pad": "x" * size}
    wire = json.dumps(payload, ensure_ascii=False)
    try:
        ws = websocket.create_connection(url, timeout=15)
        t0 = time.time()
        ws.send(wire)
        ws.settimeout(20)
        raw = ws.recv()
        dt = (time.time() - t0) * 1000
        reply = json.loads(raw)
        rt = "echo" if reply.get("echo") else str(reply.get("msg", ""))[:40]
        print("send %9d B (%7.1f KB) -> %s in %6.0f ms, reply_len=%d" % (
            len(wire), len(wire) / 1024.0, "OK(" + rt[:10] + ")", dt, len(raw)))
        ws.close()
    except Exception as ex:
        print("send %9d B (%7.1f KB) -> FAIL: %s" % (len(wire), len(wire) / 1024.0, ex))
