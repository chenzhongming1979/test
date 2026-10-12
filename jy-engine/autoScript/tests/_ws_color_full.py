# -*- coding: utf-8 -*-
"""WS 规则比色/找色完整测试（单通道 9300：op=log 日志 + op=ok/warn 回包混流）
用法：python tests/_ws_color_full.py（需设备端 main.lua 带日志转发版）
"""
import io
import json
import subprocess
import sys
import time

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

DEV = "emulator-5558"
SHOT = r"D:\Project\script\autoScript\screenshots\_ws_color_probe.png"
subprocess.run(["adb", "-s", DEV, "forward", "tcp:19301", "tcp:9300"],
               capture_output=True, check=True)

import websocket


class CloudWs:
    """单连接收发：op=log 帧实时打印，op=ok/warn 作为命令回包返回"""

    def __init__(self):
        self.ws = websocket.create_connection("ws://127.0.0.1:19301", timeout=10)

    def call(self, msg, wait_s=30, on_log=None):
        self.ws.send(json.dumps(msg, ensure_ascii=False))
        deadline = time.time() + wait_s
        self.ws.settimeout(2)
        while time.time() < deadline:
            try:
                raw = self.ws.recv()
            except websocket.WebSocketTimeoutException:
                continue
            try:
                m = json.loads(raw)
            except (ValueError, TypeError):
                continue
            op = m.get("op")
            if op == "log":
                if on_log:
                    on_log(m.get("line", ""))
                continue
            if op in ("ok", "warn"):
                return m
        return None


def build(page, row, timeout=10):
    conf = {"appid": [""], "开发比例": [720, 1280], "显示浮窗": False,
            "卡屏检测": False, "是否打印输出": True, "日志记录": False,
            "游戏模式": 1, "循环次数": 1, "无特征次数": 5,
            "outTime": 60, "timeout": timeout, "ms": 80,
            "comWait": 500, "comSleep": 700, "任务循环延迟": 80,
            "启动线程": False, "二次登录": False}
    return {"modle": 1, "page": page,
            "taps": {page: {"循环次数": 1, "1": row},
                     "任务列表": {}, "任务列表模块": {}},
            "conf": conf, "vars": {"任务变量": {}}}


# 动态取色（屏幕内容随时变化，硬编码色会失效）
subprocess.run(["adb", "-s", DEV, "exec-out", "screencap", "-p"],
               stdout=open(SHOT, "wb"), check=True)
from PIL import Image
img = Image.open(SHOT).convert("RGB")
W, H = img.size


def hexat(x, y):
    r, g, b = img.getpixel((x, y))
    return "%02X%02X%02X" % (r, g, b)


c_center, c_corner = hexat(W // 2, H // 2), hexat(W - 60, H - 60)
print("probe: center(%d,%d)=%s corner(%d,%d)=%s" % (
    W // 2, H // 2, c_center, W - 60, H - 60, c_corner))

ps_hit = {"1": "中心比色", "2": 5, "ff": "ps", "p": "360|640|%s-101010" % c_center,
          "degree": 0.9, "isClick": False, "out": True, "wait": 300}
ps_miss = {"1": "中心比色", "2": 5, "ff": "ps", "p": "360|640|FF00FF-101010",
           "degree": 0.9, "isClick": False, "out": True, "wait": 300}
fc_hit = {"1": "全屏找色", "2": 5, "ff": "fc_c", "r": [0, 0, W - 1, H - 1],
          "color": "%s-050505" % c_center, "orient": 0, "degree": 0.9,
          "isClick": False, "out": True, "wait": 300}
ft_hit = {"1": "区域找色", "2": 5, "ff": "ft", "r": [W - 160, H - 160, W - 1, H - 1],
          "one": "%s-050505" % c_corner,
          "ct": "-1|0|%s-050505" % hexat(W - 61, H - 60), "orient": 0,
          "degree": 0.9, "isClick": False, "out": True, "wait": 300}

cases = [
    ("1.比色命中(ps)", build("比色测试", ps_hit)),
    ("2.比色未命中(ps)", build("比色测试", ps_miss)),
    ("3.找色命中(fc_c)", build("找色测试", fc_hit)),
    ("4.多点找色命中(ft)", build("找色测试", ft_hit)),
]

cw = CloudWs()
for name, msg in cases:
    print("== %s ==" % name)
    logs = []
    t0 = time.time()
    reply = cw.call(msg, wait_s=30, on_log=logs.append)
    dt = time.time() - t0
    for ln in logs:
        print("  [log]", ln)
    print("  [回包 %.2fs] %s" % (dt, json.dumps(reply, ensure_ascii=False)[:160] if reply else "无"))
    time.sleep(0.5)
print("==== done ====")
