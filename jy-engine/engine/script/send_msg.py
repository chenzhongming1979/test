# -*- coding: utf-8 -*-
# WS 消息发送调试脚本：连接设备端 cloud 通道（adb forward），发送 JSON 并打印回包
# 用法：
#   python send_msg.py                                          # 交互模式（默认 emulator-5558）
#   python send_msg.py --msg '{"op":"ping","n":1}'              # 单发一条
#   python send_msg.py --interval 2                             # 每 2 秒发 {"op":"tick","n":N}
#   python send_msg.py --device 192.168.1.99:5555 --port 9300   # 指定设备/端口
import argparse
import json
import re
import subprocess
import sys
import time

import websocket

ap = argparse.ArgumentParser(description="cloud 通道发送调试")
ap.add_argument("--device", default="emulator-5558")
ap.add_argument("--port", type=int, default=9300, help="设备端 cloud 端口")
ap.add_argument("--local", type=int, default=19300, help="本地 adb forward 端口")
ap.add_argument("--msg", default="", help="单发一条 JSON 后退出")
ap.add_argument("--msg-file", default="", help="从文件读取 JSON 后单发（复杂消息/中文免 shell 转义）")
ap.add_argument("--interval", type=float, default=0, help=">0 时周期发送 tick 消息（秒）")
ap.add_argument("--timeout", type=float, default=2.0, help="等回包超时秒")
args = ap.parse_args()


def fix_loose_json(s):
    """shell 传参常把内层双引号剥掉（PowerShell: '{\"a\":1}' → {a:1}）→ 尝试补回合法 JSON"""
    try:
        json.loads(s)
        return s
    except Exception:
        pass

    def repl_val(m):
        v = m.group(1)
        if re.fullmatch(r"-?\d+(\.\d+)?|true|false|null", v):
            return m.group(0)  # 数字/布尔/null 保持裸值
        return ': "%s"%s' % (v, m.group(2))

    fixed = re.sub(r"([{,]\s*)([^\s\":,{}\[\]]+)\s*:", lambda m: '%s"%s":' % (m.group(1), m.group(2)), s)  # 键补引号
    fixed = re.sub(r":\s*([^\s\",:{}\[\]]+)\s*([,}])", repl_val, fixed)  # 裸字符串值补引号（含中文）
    json.loads(fixed)  # 仍非法则抛异常给上层
    print("[send] 宽松修复:", s, "->", fixed)
    return fixed

subprocess.run(["adb", "-s", args.device, "forward",
                "tcp:%d" % args.local, "tcp:%d" % args.port], check=True)
url = "ws://127.0.0.1:%d" % args.local
ws = websocket.create_connection(url, timeout=10)
print("[send] connected: %s (device %s, port %d)" % (url, args.device, args.port))


def send_one(text):
    """发送一条原文，阻塞等一条回包打印"""
    print(">>", text)
    ws.send(text)
    try:
        ws.settimeout(args.timeout)
        reply = ws.recv()
        print("<<", reply)
    except websocket.WebSocketTimeoutException:
        print("<< (%.1fs 无回包)" % args.timeout)
    except Exception as ex:
        print("<< recv error:", ex)


try:
    if args.msg_file:
        with open(args.msg_file, "r", encoding="utf-8") as f:
            send_one(fix_loose_json(f.read()))
    elif args.msg:
        send_one(fix_loose_json(args.msg))
    elif args.interval > 0:
        n = 0
        while True:
            n += 1
            send_one(json.dumps({"op": "tick", "n": n}, ensure_ascii=False))
            time.sleep(args.interval)
    else:
        print("输入 JSON 回车发送（exit 退出）：")
        for line in sys.stdin:
            line = line.strip()
            if line == "exit":
                break
            if line:
                send_one(fix_loose_json(line))
except KeyboardInterrupt:
    pass
finally:
    ws.close()
    print("[send] closed")
