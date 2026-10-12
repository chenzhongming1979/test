# frameSdk-autolua2 smoke test runner (Python + websocket-client, multi-file push)
# Usage: python script/run_test.py [--device emulator-5558] [--timeout 120]
import argparse
import json
import os
import subprocess
import time

import websocket

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.dirname(SCRIPT_DIR)  # 工作区根
FRAME = os.path.join(BASE, "frame")      # Lua 工程（main.lua/JY/frameSdk-autolua2）
CONFIG = os.path.join(BASE, "config")    # 配置（brand.json）
LOG_FILE = os.path.join(BASE, "test_run.log")

def log(msg):
    line = "[%s] %s" % (time.strftime("%H:%M:%S"), msg)
    print(line, flush=True)
    with open(LOG_FILE, "a", encoding="utf-8") as f:
        f.write(line + "\n")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", default="emulator-5558")
    ap.add_argument("--local-port", type=int, default=18081)
    ap.add_argument("--remote-port", type=int, default=8080)
    ap.add_argument("--push-root", default="framesdk_test")
    ap.add_argument("--sdk-src", default="")
    ap.add_argument("--jy-src", default="")
    ap.add_argument("--restart", action="store_true", default=True)
    ap.add_argument("--no-restart", dest="restart", action="store_false")
    ap.add_argument("--timeout", type=int, default=120)
    args = ap.parse_args()

    open(LOG_FILE, "w", encoding="utf-8").close()

    subprocess.run(["adb", "-s", args.device, "forward",
                    "tcp:%d" % args.local_port, "tcp:%d" % args.remote_port], check=True)
    log("adb forward tcp:%d -> tcp:%d (%s)" % (args.local_port, args.remote_port, args.device))

    ws = websocket.create_connection("ws://127.0.0.1:%d" % args.local_port, timeout=10)
    log("WS connected")

    hello = ws.recv()
    log("hello: %s" % hello[:200])

    # restart-first：引擎 stop_script 后再 run_script 新脚本不执行（实测），
    # 且旧实例的 ws server 端口残留——每次部署前重启 app 最可靠
    if args.restart:
        log("restarting app ...")
        subprocess.run(["adb", "-s", args.device, "shell",
                        "am force-stop com.autolua.autolua2"], capture_output=True)
        # root 引擎进程独立于 app 进程（force-stop 不杀）——脚本常驻 pump 会占住
        # 主引擎 worker，新 run_script 被忙态拒绝（实测：零日志 + 旧 9300 残留）。一并杀掉
        subprocess.run(["adb", "-s", args.device, "shell",
                        "su 0 pkill -f com.autolua.autolua2"], capture_output=True)
        time.sleep(1.5)
        subprocess.run(["adb", "-s", args.device, "shell",
                        "monkey -p com.autolua.autolua2 -c android.intent.category.LAUNCHER 1"],
                       capture_output=True)
        deadline = time.time() + 25
        while time.time() < deadline:
            r = subprocess.run(["adb", "-s", args.device, "shell", "netstat -tln 2>/dev/null"],
                               capture_output=True, text=True)
            if "8080" in (r.stdout or ""):
                log("app ready (8080 listening)")
                break
            time.sleep(1)
        # 8080 ready ≠ 引擎就绪：引擎构建（root 拉起/沙箱降级）在 onCreate 尾部，实测
        # 冷启动 >15s。过早 run_script 会被忙态/未就绪 worker 静默吞掉（零日志）
        time.sleep(10)
        # 重启后需要重连（旧 WS 连接随进程死亡失效）
        ws.close()
        ws = websocket.create_connection("ws://127.0.0.1:%d" % args.local_port, timeout=10)
        hello = ws.recv()
        log("hello(reconnect): %s" % hello[:120])
        time.sleep(0.5)

    # collect files: main.lua/jyframe.lua/jy_protocol.lua 等（根，常用操作文件）+ frame/frameSdk-autolua2/ + frame/JY/
    def src_of(name):
        """顶层脚本定位：工作区根优先（常用操作文件放根），回落 frame/（框架底层）"""
        for d in (BASE, FRAME):
            p = os.path.join(d, name)
            if os.path.isfile(p):
                return p
        return os.path.join(BASE, name)

    # SDK 源包目录：--sdk-src 参数时直推 jy-frame-firefly 源包（绕过本地副本同步）
    sdk_dir = os.path.join(FRAME, "frameSdk-autolua2")
    if args.sdk_src:
        sdk_dir = args.sdk_src
    log("SDK dir: " + sdk_dir)
    files = [("main.lua", src_of("main.lua"))]
    for extra in ("jyframe.lua", "jy_protocol.lua", "Funs.lua", "JUI.lua", "auth_provider_demo.lua", "main.config"):
        p = src_of(extra)
        if os.path.isfile(p):
            files.append((extra, p))
    brand_cfg = os.path.join(CONFIG, "brand.json")
    if os.path.isfile(brand_cfg):
        files.append(("brand.json", brand_cfg))
    for name in sorted(os.listdir(sdk_dir)):
        if name.endswith(".lua"):
            files.append(("frameSdk-autolua2/" + name, os.path.join(sdk_dir, name)))
    # JY 核心：优先工作区 frame/JY，缺省回落 jy-frame-firefly 框架源
    jy_src = args.jy_src
    if not jy_src:
        local_jy = os.path.join(FRAME, "JY")
        jy_src = local_jy if os.path.isdir(local_jy) else \
            r"d:\Project\script\lrjl\jy-frame-lrjl\jy-frame-firefly\脚本\JY"
    if os.path.isdir(jy_src):
        n_jy = 0
        for dirpath, _, names in os.walk(jy_src):
            for n in sorted(names):
                if n.endswith(".lua"):
                    ap_ = os.path.join(dirpath, n)
                    rel = os.path.relpath(ap_, jy_src).replace("\\", "/")
                    files.append(("JY/" + rel, ap_))
                    n_jy += 1
        log("JY core: %s (%d files)" % (jy_src, n_jy))

    ws.send(json.dumps({"type": "push_files_start", "data": {}}))
    total = 0
    for rel, abs_path in files:
        with open(abs_path, "r", encoding="utf-8") as f:
            lua = f.read()
        ws.send(json.dumps({"type": "push_file", "data": {
            "path": "%s/%s" % (args.push_root, rel),
            "content": lua, "encoding": "utf8"}}, ensure_ascii=False))
        total += len(lua.encode("utf-8"))
        log("pushed: %s (%d chars)" % (rel, len(lua)))
    ws.send(json.dumps({"type": "push_files_end", "data": {}}))
    log("pushed %d files, %d bytes total" % (len(files), total))

    pr = ws.recv()
    log("push_result: %s" % pr)

    run_path = "/%s/main.lua" % args.push_root
    # run_script 提交后引擎可能仍在构建（8080 ready ≠ 引擎就绪，冷启动实测 >15s），
    # 提交被静默吞掉时无 script_started——重发至多 3 次（间隔 5s）
    for attempt in range(3):
        ws.send(json.dumps({"type": "run_script", "data": {"path": run_path}}))
        log("run_script sent (attempt %d): %s" % (attempt + 1, run_path))
        got_started = False
        deadline = time.time() + 5
        while time.time() < deadline:
            try:
                ack = ws.recv()
            except websocket.WebSocketTimeoutException:
                continue
            if not ack:
                continue
            if '"script_started"' in ack:
                got_started = True
                log("script_started")
                break
        if got_started:
            break
        log("run_script no ack, retrying ...")
        time.sleep(5)

    started = got_started
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
            log("script_started")
        elif t == "script_state":
            state = data.get("state")
            if started and state == "idle":
                log("script_state: idle (finished)")
                finished = True
                break
        elif t == "log":
            log("L: %s" % data.get("line", ""))
        elif t == "error":
            log("error: %s" % data)
        else:
            log("msg %s: %s" % (t, str(data)[:200]))

    if not started:
        log("WARN: script_started not received")
    elif not finished:
        log("WARN: timeout after %ds, script still running" % args.timeout)

    ws.close()
    log("=== done, log saved to %s ===" % LOG_FILE)

if __name__ == "__main__":
    main()
