# JY-Frame selftest remote runner (Python + websocket-client)
# Usage: python run_remote.py [--device emulator-5558] [--timeout 240]
import argparse
import json
import subprocess
import sys
import time
import os

import websocket

BASE = os.path.dirname(os.path.abspath(__file__))
LOG_FILE = os.path.join(BASE, "selftest_run.log")

def log(msg):
    line = "[%s] %s" % (time.strftime("%H:%M:%S"), msg)
    print(line, flush=True)
    with open(LOG_FILE, "a", encoding="utf-8") as f:
        f.write(line + "\n")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", default="emulator-5558")
    ap.add_argument("--local-port", type=int, default=18080)
    ap.add_argument("--remote-port", type=int, default=8080)
    ap.add_argument("--script", default=os.path.join(BASE, "main.lua"))
    ap.add_argument("--push-path", default="selftest/main.lua")
    ap.add_argument("--timeout", type=int, default=240)
    args = ap.parse_args()

    open(LOG_FILE, "w", encoding="utf-8").close()

    subprocess.run(["adb", "-s", args.device, "forward",
                    "tcp:%d" % args.local_port, "tcp:%d" % args.remote_port], check=True)
    log("adb forward tcp:%d -> tcp:%d (%s)" % (args.local_port, args.remote_port, args.device))

    ws = websocket.create_connection("ws://127.0.0.1:%d" % args.local_port, timeout=10)
    log("WS connected")

    hello = ws.recv()
    log("hello: %s" % hello[:200])

    with open(args.script, "r", encoding="utf-8") as f:
        lua = f.read()

    ws.send(json.dumps({"type": "push_files_start", "data": {}}))
    ws.send(json.dumps({"type": "push_file", "data": {
        "path": args.push_path, "content": lua, "encoding": "utf8"}},
        ensure_ascii=False))
    ws.send(json.dumps({"type": "push_files_end", "data": {}}))
    log("pushed %s (%d bytes)" % (args.push_path, len(lua.encode("utf-8"))))

    pr = ws.recv()
    log("push_result: %s" % pr)

    run_path = "/" + args.push_path.replace("\\", "/")
    ws.send(json.dumps({"type": "run_script", "data": {"path": run_path}}))
    log("run_script sent: %s" % run_path)

    started = False
    finished = False
    deadline = time.time() + args.timeout
    while time.time() < deadline:
        try:
            raw = ws.recv()
        except websocket.WebSocketTimeoutException:
            continue
        if not raw:
            continue
        try:
            msg = json.loads(raw)
        except ValueError:
            log("raw: %s" % raw[:300])
            continue
        t = msg.get("type")
        data = msg.get("data") or {}
        if t == "script_started":
            started = True
            log("script_started: %s" % data)
        elif t == "script_state":
            state = data.get("state")
            if state == "running":
                log("script_state: running")
            elif started and state == "idle":
                log("script_state: idle (finished)")
                finished = True
                break
        elif t == "log":
            log("L: %s" % data.get("line", ""))
        elif t == "error":
            log("error: %s" % data)
        elif t == "image":
            log("image frame %dx%d" % (data.get("width", 0), data.get("height", 0)))
        else:
            log("msg %s: %s" % (t, str(data)[:200]))

    if not started:
        log("WARN: script_started not received (root engine offline?)")
    elif not finished:
        log("WARN: timeout after %ds, script still running" % args.timeout)

    ws.close()
    log("=== done, log saved to %s ===" % LOG_FILE)

if __name__ == "__main__":
    main()
