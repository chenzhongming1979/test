# -*- coding: utf-8 -*-
# 验证"停止→再运行"循环（不重启 app）：run → stop → 再 run → cloud echo
# 模拟插件用户操作：点运行 → 点停止 → 再点运行
# 用法：python verify_stop_run.py [--device emulator-5558] [--loops 2]
import argparse
import json
import subprocess
import time

import websocket

ap = argparse.ArgumentParser()
ap.add_argument("--device", default="emulator-5558")
ap.add_argument("--ide-local", type=int, default=18081)    # IDE 通道 8080
ap.add_argument("--cloud-local", type=int, default=19300)  # cloud 通道 9300
ap.add_argument("--path", default="/framesdk_test/main.lua")
ap.add_argument("--secret-file", default="", help="brand.json（含 secret 时用 PSK 加密帧 echo，适配 jy 完整版）")
ap.add_argument("--loops", type=int, default=2, help="停止→再运行 循环次数")
args = ap.parse_args()

# 加密模式：jy 完整版 main（brand.secret → cloud.open 加密）需要加密帧 echo
crypto = None
if args.secret_file:
    from ws_client import FrameCrypto
    with open(args.secret_file, "r", encoding="utf-8-sig") as f:
        _psk = json.load(f).get("secret", "")
    crypto = FrameCrypto(_psk)
    print("[verify] PSK 加密 echo 模式（帧 v1）")

subprocess.run(["adb", "-s", args.device, "forward", "tcp:%d" % args.ide_local, "tcp:8080"], check=True)
subprocess.run(["adb", "-s", args.device, "forward", "tcp:%d" % args.cloud_local, "tcp:9300"], check=True)


def ws_recv_until(ws, want_types, timeout):
    """收帧直到命中 want_types 之一（返回该帧）或超时（返回 None）；log 帧打印"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            ws.settimeout(max(0.2, deadline - time.time()))
            raw = ws.recv()
            m = json.loads(raw)
        except websocket.WebSocketTimeoutException:
            return None
        except Exception as ex:
            print("  recv error:", ex)
            return None
        t = m.get("type")
        if t == "log":
            print("  L:", (m.get("data") or {}).get("line", ""))
            continue
        if t in want_types:
            return m
    return None


def run_script(ws):
    """run_script，等 script_started（5s）；返回 bool"""
    ws.send(json.dumps({"type": "run_script", "data": {"path": args.path}}))
    m = ws_recv_until(ws, {"script_started"}, 5)
    return m is not None


def stop_script(ws):
    ws.send(json.dumps({"type": "stop_script", "data": {}}))
    ws_recv_until(ws, {"result", "script_state"}, 5)


def cloud_echo_check():
    """cloud 通道发一条，期望回包（3s）：echo:true（调试版）/ ok:true（jy 协议）"""
    try:
        ws = websocket.create_connection("ws://127.0.0.1:%d" % args.cloud_local, timeout=5)
    except Exception as ex:
        print("  cloud connect error:", ex)
        return False
    try:
        if crypto is not None:
            ws.send(crypto.encode_frame({"op": "ping", "tag": "verify"}))
        else:
            ws.send(json.dumps({"op": "ping", "tag": "verify"}))
        ws.settimeout(3)
        raw = ws.recv()
        reply = crypto.decode_frame(raw) if crypto is not None else json.loads(raw)
        if isinstance(reply, dict) and reply.get("e") == 0:
            print("  cloud error frame:", reply)  # 协议不匹配（明文 vs 加密）
            return False
        ok = reply.get("echo") is True or reply.get("ok") is True
        # 重复回包检测：onMessage 注册叠加时同一条消息会 dispatch 多次（多份回包）
        try:
            ws.settimeout(0.8)
            extra = ws.recv()
            print("  !! 重复回包:", (raw[:60], extra[:60]))
            return False
        except Exception:
            pass
        return ok
    except Exception as ex:
        print("  cloud recv error:", ex)
        return False
    finally:
        ws.close()


print("=== 连接 IDE 通道（8080） ===")
ws = websocket.create_connection("ws://127.0.0.1:%d" % args.ide_local, timeout=10)
print("hello:", ws.recv()[:100])

all_ok = True
for i in range(1, args.loops + 1):
    print("\n=== 第 %d 轮：run_script ===" % i)
    ok1 = run_script(ws)
    print("  script_started:", "OK" if ok1 else "FAIL（超时未开始）")
    time.sleep(1.5)
    print("  cloud echo:", "OK" if cloud_echo_check() else "FAIL")

    print("=== stop_script（不重启 app） ===")
    stop_script(ws)
    time.sleep(2)  # 等泵退出 + OnStop 关 server + worker 回 Idle

    print("=== 再 run_script（验证忙态已清） ===")
    ok2 = run_script(ws)
    print("  script_started:", "OK" if ok2 else "FAIL（stop 后被忙态拒绝）")
    time.sleep(1.5)
    ok3 = cloud_echo_check()
    print("  cloud echo:", "OK" if ok3 else "FAIL")
    round_ok = ok1 and ok2 and ok3
    all_ok = all_ok and round_ok
    print("  第 %d 轮：%s" % (i, "PASS" if round_ok else "FAIL"))

ws.close()
print("\n=== 验证完成：%s ===" % ("全部 PASS" if all_ok else "存在 FAIL"))
raise SystemExit(0 if all_ok else 1)
