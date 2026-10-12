# -*- coding: utf-8 -*-
"""jy-frame 云控测试客户端（PC 端）
连接引擎 cloud.lua WS 服务（adb forward tcp:19300 -> 设备 9300），发送 JSON 验证 4 层执行。

用法：
  python script/ws_client.py --demo                       # auth 前置全链路：sync(conf.auth) → 未授权拦截 → auth → 执行类 → 错误卡密 → 恢复
  python script/ws_client.py --demo --no-auth             # 原始全链路（不下发 conf.auth，验证未配置 auth 时向后兼容）
  python script/ws_client.py --demo --card DEMO-CARD-001  # 指定卡密（默认 DEMO-CARD-001，白名单由 sync conf.auth 下发）
  python script/ws_client.py --demo --secret-file config/brand.json  # PSK 全帧加密（与 config/brand.json secret 同值）
  python script/ws_client.py --cmd ping
  python script/ws_client.py --cmd ping --secret <PSK>    # 加密单发
  python script/ws_client.py --cmd sync --json '{"taps":{...}}'
  python script/ws_client.py --cmd auth --json '{"card":"xxx"}'
  python script/ws_client.py --cmd pageTest --json '{"page":"云控测试"}'
"""
import argparse
import base64
import hashlib
import json
import subprocess
import sys
import time

import websocket

LOCAL_PORT = 19300
DEVICE_PORT = 9300

def log(msg):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)

def adb_forward(device):
    subprocess.run(["adb", "-s", device, "forward",
                    "tcp:%d" % LOCAL_PORT, "tcp:%d" % DEVICE_PORT], check=True)
    log("adb forward tcp:%d -> tcp:%d (%s)" % (LOCAL_PORT, DEVICE_PORT, device))


# ---------------- PSK 帧加密（v1，与 cloud.lua 同构；docs/plans/云控消息加密任务书.md §2） ----------------
def _import_aes():
    """探测 AES 后端：pycryptodome 优先，cryptography 兜底；都无则返回 (None, None)。"""
    try:
        from Crypto.Cipher import AES  # pycryptodome
        return "pycryptodome", AES
    except ImportError:
        pass
    try:
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
        return "cryptography", (Cipher, algorithms, modes)
    except ImportError:
        pass
    return None, None

class FrameCrypto:
    """PSK 帧加解密：encKey=sha256(psk+"|enc1")[:32]（32 字节 ASCII）；sigKey=sha256(psk+"|sig1")[:32]；
    帧 {"e":1,"iv":"<16位hex>","d":"<AES-256-CBC密文Base64>","s":"<sha256(sigKey|iv|d)[:16]>"}。
    key/iv 全程可打印 ASCII（对齐引擎 CryptoLib String(UTF8) 往返约束）；
    填充 PKCS7（块 16，与引擎 PKCS5Padding 同算法）。"""

    def __init__(self, psk):
        self.enc_key = hashlib.sha256((psk + "|enc1").encode("utf-8")).hexdigest()[:32].encode("ascii")
        self.sig_key = hashlib.sha256((psk + "|sig1").encode("utf-8")).hexdigest()[:32].encode("ascii")
        self.counter = 0
        self.backend, mod = _import_aes()
        if self.backend is None:
            log("pycryptodome/cryptography 均不可用，pip install pycryptodome ...")
            subprocess.run([sys.executable, "-m", "pip", "install", "pycryptodome"], check=True)
            self.backend, mod = _import_aes()
            if self.backend is None:
                raise RuntimeError("AES 后端不可用（pip install pycryptodome 失败）")
        self._mod = mod
        log("AES 后端: %s（AES-256-CBC，帧 v1）" % self.backend)

    def _next_iv(self):
        self.counter += 1
        raw = (format(int(time.time()), "x")
               + format(int((time.perf_counter() % 1) * 1e6) & 0xFFFFFF, "06x")
               + format(self.counter & 0xFFFF, "04x"))
        return raw.lower()[:16].ljust(16, "0")

    def _encrypt(self, data, iv):
        pad = 16 - len(data) % 16
        data = data + bytes([pad]) * pad
        if self.backend == "pycryptodome":
            return self._mod.new(self.enc_key, self._mod.MODE_CBC, iv).encrypt(data)
        Cipher, algorithms, modes = self._mod
        enc = Cipher(algorithms.AES(self.enc_key), modes.CBC(iv)).encryptor()
        return enc.update(data) + enc.finalize()

    def _decrypt(self, data, iv):
        if self.backend == "pycryptodome":
            return self._mod.new(self.enc_key, self._mod.MODE_CBC, iv).decrypt(data)
        Cipher, algorithms, modes = self._mod
        dec = Cipher(algorithms.AES(self.enc_key), modes.CBC(iv)).decryptor()
        return dec.update(data) + dec.finalize()

    def encode_frame(self, msg):
        plain = json.dumps(msg, ensure_ascii=False).encode("utf-8")
        iv = self._next_iv().encode("ascii")
        d = base64.b64encode(self._encrypt(plain, iv)).decode("ascii")
        s = hashlib.sha256(self.sig_key + b"|" + iv + b"|" + d.encode("ascii")).hexdigest()[:16]
        return json.dumps({"e": 1, "iv": iv.decode("ascii"), "d": d, "s": s})

    def decode_frame(self, raw):
        """原始帧 → 明文消息 dict；明文错误帧 {"e":0,"err":..} 原样直通。"""
        m = json.loads(raw)
        if m.get("e") == 0:
            return m
        if m.get("e") != 1:
            return m
        iv = m.get("iv", "").encode("ascii")
        d = m.get("d", "")
        s = m.get("s", "")
        s_exp = hashlib.sha256(self.sig_key + b"|" + iv + b"|" + d.encode("ascii")).hexdigest()[:16]
        if s != s_exp:
            raise ValueError("帧校验失败（s 不匹配）")
        padded = self._decrypt(base64.b64decode(d), iv)
        pad = padded[-1]
        if not (1 <= pad <= 16) or padded[-pad:] != bytes([pad]) * pad:
            raise ValueError("填充错误（PSK 不匹配？）")
        return json.loads(padded[:-pad].decode("utf-8"))

# ---------------- 测试用 taps（720x1280 白屏语义；ps 中心白色特征） ----------------
PS_WHITE = "360|640|FFFFFF-101010"

def demo_taps():
    """Lua 特征行 = 混合表（[1]=名,[2]=超时 + hash 参数键）——JSON 用对象全字符串键表达，
    "1"/"2" 由 cloud.sync 的 normalizeKeys 转回 number 键，其余键原样保留。"""
    def row(name, wait, extra=None):
        r = {"1": name, "2": 5, "ff": "ps", "p": PS_WHITE, "degree": 0.9,
             "isClick": False, "out": True, "wait": wait}
        if extra:
            r.update(extra)
        return r

    return {
        "云控测试": {"循环次数": 1, "1": row("Center White", 300)},
        "云控任务": {"循环次数": 1, "1": row("Center White", 300)},
        "任务列表": {
            "1": row("云控任务", 300, {"任务参数": {"是否有对应设置": False}}),
        },
        "任务列表模块": {"1": "云控任务"},
    }

def demo_conf(card):
    """云控 conf：auth Provider 配置（builtin 白名单卡密；sync 特殊透传到 cloud.conf.auth，
    不写 G 注册表）。未配置 auth 时 cloud.lua 完全不拦截（向后兼容）。"""
    return {"auth": {"provider": "builtin", "cards": [card]}}

class CloudClient:
    def __init__(self, timeout=60, secret=None):
        self.crypto = FrameCrypto(secret) if secret else None
        self.ws = websocket.create_connection("ws://127.0.0.1:%d" % LOCAL_PORT, timeout=10)
        log("connected ws://127.0.0.1:%d" % LOCAL_PORT)
        self.timeout = timeout

    def send(self, msg):
        msg.setdefault("req", "r%d" % int(time.time() * 1000 % 1e9))
        if self.crypto:
            wire = self.crypto.encode_frame(msg)
            log(">> [enc] %s" % wire[:220])  # 抓包样例：仅见 e/iv/d(Base64密文)/s，无 op/taps 明文
        else:
            wire = json.dumps(msg, ensure_ascii=False)
            log(">> %s" % wire[:220])
        self.ws.send(wire)
        return msg["req"]

    def recv_until(self, req, deadline):
        while time.time() < deadline:
            try:
                raw = self.ws.recv()
            except websocket.WebSocketTimeoutException:
                continue
            if not raw:
                continue
            if self.crypto:
                try:
                    m = self.crypto.decode_frame(raw)
                except (ValueError, KeyError) as ex:
                    log("<< [enc] 解帧失败: %s (raw %s)" % (ex, raw[:120]))
                    continue
            else:
                try:
                    m = json.loads(raw)
                except ValueError:
                    log("<< raw %s" % raw[:200])
                    continue
            if m.get("e") == 0:
                log("<< %s" % json.dumps(m, ensure_ascii=False)[:300])
                return m  # 明文错误帧（encrypted/plaintext/bad frame）
            if m.get("op") == "result" and m.get("req") == req:
                log("<< %s" % json.dumps(m, ensure_ascii=False)[:300])
                return m
            log("<< %s" % json.dumps(m, ensure_ascii=False)[:200])
        return None

    def call(self, msg, wait=None):
        req = self.send(msg)
        w = wait if wait is not None else self.timeout
        return self.recv_until(req, time.time() + w)

def run_demo(card="DEMO-CARD-001", use_auth=True, secret=None):
    c = CloudClient(secret=secret)
    if secret:
        log("PSK 加密模式（帧 v1）：全链 send/recv 走 AES-256-CBC")
    results = []

    def step(name, msg, expect_ok=True, wait=None, expect_msg=None, expect_ret=None):
        r = c.call(msg, wait=wait)
        ok = (r is not None and r.get("ok") == expect_ok)
        if ok and expect_msg is not None:
            ok = expect_msg in (r.get("msg") or "")
        if ok and expect_ret is not None:
            ok = (r.get("ret") == expect_ret)
        results.append((name, ok, r))
        log("== %s: %s" % (name, "OK" if ok else "FAIL"))
        return r

    if use_auth:
        # auth 前置序列：sync 下发 conf.auth → 未授权拦截 → auth → 执行类全链 → 错误卡密 denied → 重新 auth 恢复
        step("ping", {"op": "ping"})
        step("state", {"op": "state"})
        step("sync", {"op": "sync", "taps": demo_taps(), "conf": demo_conf(card)})
        # 未授权拦截：pageTest 预期 ok:false 且 msg 含"未授权"（上轮 demo 已授权则跳过该校验步）
        st = c.call({"op": "state"})
        if (st or {}).get("state", {}).get("auth", {}).get("status") == "authorized":
            log("== pageTest-unauth: SKIP（设备已授权，跳过未授权拦截校验）")
            results.append(("pageTest-unauth", True, None))
        else:
            step("pageTest-unauth", {"op": "pageTest", "page": "云控测试"},
                 expect_ok=False, expect_msg="未授权", wait=30)
        # 正确卡密 → authorized；auth 后执行类恢复（demo 页循环次数=1 → TaskRunner 指定循环次数退出码=5）
        step("auth", {"op": "auth", "card": card})
        step("pageTest", {"op": "pageTest", "page": "云控测试"}, wait=90, expect_ret=5)
        # 层3：action（单行，对象形态）
        step("action", {"op": "action", "row": {
            "1": "Center White", "2": 5, "ff": "ps", "p": PS_WHITE, "degree": 0.9,
            "isClick": False, "out": True, "wait": 200}}, wait=90)
        # 层2：taskTest（任务列表任务）
        step("taskTest", {"op": "taskTest", "task": "云控任务"}, wait=90)
        # 错误卡密 → denied；重新正确 auth → 恢复 authorized
        step("auth-bad", {"op": "auth", "card": "BAD-CARD"}, expect_ok=False)
        step("auth-re", {"op": "auth", "card": card})
        step("state-final", {"op": "state"})
    else:
        # 向后兼容序列（不下发 conf.auth，cloud 完全不拦截）：原 demo 全链路
        step("ping", {"op": "ping"})
        step("state", {"op": "state"})
        step("sync", {"op": "sync", "taps": demo_taps()})
        step("init", {"op": "init"}, wait=90)
        step("state2", {"op": "state"})
        step("pageTest", {"op": "pageTest", "page": "云控测试"}, wait=90)
        step("action", {"op": "action", "row": {
            "1": "Center White", "2": 5, "ff": "ps", "p": PS_WHITE, "degree": 0.9,
            "isClick": False, "out": True, "wait": 200}}, wait=90)
        step("taskTest", {"op": "taskTest", "task": "云控任务"}, wait=90)
        step("state3", {"op": "state"})

    c.ws.close()
    log("==== 结果汇总 ====")
    npass = 0
    for name, ok, r in results:
        log("  %-16s %s" % (name, "OK" if ok else "FAIL"))
        npass += 1 if ok else 0
    log("==== %d/%d OK ====" % (npass, len(results)))
    return npass == len(results)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", default="emulator-5558")
    ap.add_argument("--cmd", default="")
    ap.add_argument("--json", default="{}")
    ap.add_argument("--demo", action="store_true")
    ap.add_argument("--card", default="DEMO-CARD-001", help="demo/auth 使用的卡密（默认 DEMO-CARD-001）")
    ap.add_argument("--no-auth", dest="use_auth", action="store_false", default=True,
                    help="demo 不下发 conf.auth（验证未配置 auth 时向后兼容）")
    ap.add_argument("--secret", default="", help="PSK（与设备 brand.json secret 同值，启用全帧加密）")
    ap.add_argument("--secret-file", default="", help="brand.json 路径（读其 secret 字段作 PSK；--secret 优先）")
    ap.add_argument("--logs", action="store_true",
                    help="实时日志跟随：持续打印云控推送的 op=log 帧（脚本 print + cloud 内部日志），Ctrl+C 退出")
    ap.add_argument("--wait", type=int, default=60)
    args = ap.parse_args()

    secret = args.secret
    if not secret and args.secret_file:
        try:
            with open(args.secret_file, "r", encoding="utf-8-sig") as f:
                secret = (json.load(f) or {}).get("secret") or ""
        except Exception as ex:
            log("读取 secret-file 失败: %s" % ex)
            sys.exit(1)
        if not secret:
            log("secret-file 无 secret 字段（明文模式？）: %s" % args.secret_file)
            sys.exit(1)

    adb_forward(args.device)

    if args.logs:
        # 实时日志跟随：持续收帧，op=log 打印行；加密模式自动解帧
        log("实时日志跟随中（Ctrl+C 退出）...")
        c = CloudClient(secret=secret or None)
        try:
            while True:
                try:
                    raw = c.ws.recv()
                except websocket.WebSocketTimeoutException:
                    continue
                if not raw:
                    continue
                try:
                    if c.crypto:
                        m = c.crypto.decode_frame(raw)
                    else:
                        m = json.loads(raw)
                except (ValueError, KeyError) as ex:
                    log("<< [enc] 解帧失败: %s" % ex)
                    continue
                if isinstance(m, dict) and m.get("op") == "log":
                    print("[%s] %s" % (time.strftime("%H:%M:%S"), m.get("line", "")), flush=True)
        except KeyboardInterrupt:
            log("已退出日志跟随")
        finally:
            c.ws.close()
        return

    if args.demo:
        ok = run_demo(args.card, use_auth=args.use_auth, secret=secret or None)
        sys.exit(0 if ok else 1)
        return

    c = CloudClient(secret=secret or None)
    msg = json.loads(args.json)
    if args.cmd:
        msg["op"] = args.cmd
    r = c.call(msg, wait=args.wait)
    print(json.dumps(r, ensure_ascii=False, indent=2) if r else "NO REPLY")
    c.ws.close()

if __name__ == "__main__":
    main()
