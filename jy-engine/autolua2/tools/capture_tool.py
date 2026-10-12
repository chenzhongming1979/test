# -*- coding: utf-8 -*-
"""
AutoLua2 抓抓 —— 截图取色工具（独立桌面版，单文件）

布局参考懒人精灵/按键精灵"抓抓"：左侧工具栏 + 中央画布 + 右侧多标签面板（图色工具/代码生成）。
    连接模拟器（adb forward / 直连）→ 多张截图管理（可连续截 N 张、缩略图切换）
    → 取色（单点+多点，勾选/显示/坐标/颜色/RGB/偏移/反色 表格管理）
    → cmp_color / find_multi_color / find_image / ocr 一键实机验证
    → 按函数生成引擎 Lua 找色找图代码（占位符模板可自定义）

用法:  python capture_tool.py
依赖:  Python 3.10+、tkinter、pillow、websocket-client
       pip install pillow websocket-client
协议:  复用现有 IDE WS 命令，引擎零改动（契约见 docs/plans/截图取色抓抓工具任务书.md §2）
通道:  adb forward 使用独立本地端口段 18090-18095，不与 VSCode 插件争抢。
"""

import base64
import io
import json
import os
import queue
import struct
import subprocess
import sys
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

import websocket  # websocket-client
from PIL import Image, ImageTk

TOOL_DIR = os.path.dirname(os.path.abspath(__file__))
CFG_PATH = os.path.join(TOOL_DIR, "capture_config.json")
TPL_PATH = os.path.join(TOOL_DIR, "capture_templates.json")

DEV_PORTS = list(range(8080, 8086))    # 引擎 IDE 服务端口（8080 失败回退 8081-8085）
FWD_PORTS = list(range(18090, 18096))  # adb forward 本地端口段（独立于插件，避免争抢）
HEARTBEAT_SEC = 25                      # 应用层心跳（<30s，防 75s 无活跃被踢）
FRAME_TIMEOUT = 10.0                    # 截图首帧等待上限（秒）
MAX_SHOTS = 20                          # 截图上限（720x1280 RGB 单张约 2.7MB）
THUMB_H = 64                            # 缩略图条高度

DEFAULT_CONFIG = {
    "mode": "adb",                # adb | direct
    "serial": "",                 # 上次 adb 设备序列号
    "direct_addr": "127.0.0.1",   # 直连模式 IP
    "adb_path": "adb",            # adb 可执行文件路径
    "similarity": 0.9,            # 相似度
    "tpl_dir": TOOL_DIR,          # 模板 PNG 默认保存目录
    "mag_on": True,               # 显示放大镜
    "fit_mode": "fit",            # fit 适应窗口 | actual 原始尺寸
}

# 代码生成函数（键=模板持久化键，label=下拉显示）
FUNCS = {
    "findMultiColor": "多点找色",
    "isFeature": "多点比色",
    "cmpColor": "单点比色",
    "findColor": "区域找色",
    "findImage": "找图",
}
# 方向 → FIND_ORDER 常量（默认不拼 order 参数）
ORDERS = {
    "默认": "",
    "从左上往右下": "LEFT_RIGHT_UP_DOWN",
    "从右上往左下": "RIGHT_LEFT_UP_DOWN",
    "从左下往右上": "LEFT_RIGHT_DOWN_UP",
    "从右下往左上": "RIGHT_LEFT_DOWN_UP",
}

DEFAULT_TEMPLATES = {
    "findMultiColor": 'local x, y = findMultiColor({x1}, {y1}, {x2}, {y2}, {firstColor}, "{offsetStr}", {sim}{order})',
    "isFeature":      'local ok = isFeature({x}, {y}, "0|0|{firstColor},{offsetStr}", {sim})',
    "cmpColor":       'local ok = cmpColor({x}, {y}, {color}, {sim})',
    "findColor":      'local x, y = findColor({x1}, {y1}, {x2}, {y2}, {color}, {sim}{order})',
    "findImage":      'local ix, iy = findImage("{img}", {sim}{order})',
}


def load_json(path, default):
    try:
        with open(path, "r", encoding="utf-8") as f:
            obj = json.load(f)
        if isinstance(obj, dict):
            merged = dict(default)
            merged.update(obj)
            return merged
    except Exception:
        pass
    return dict(default)


def save_json(path, obj):
    try:
        with open(path, "w", encoding="utf-8") as f:
            json.dump(obj, f, ensure_ascii=False, indent=2)
    except Exception:
        pass


CONFIG = load_json(CFG_PATH, DEFAULT_CONFIG)
TEMPLATES = load_json(TPL_PATH, DEFAULT_TEMPLATES)
for k, v in DEFAULT_TEMPLATES.items():
    TEMPLATES.setdefault(k, v)


def color_hex(rgb):
    return "0x%06X" % (rgb & 0xFFFFFF)


def create_no_window():
    return 0x08000000 if os.name == "nt" else 0  # CREATE_NO_WINDOW


# ============================================================================
# WS 客户端：收发线程 + 按 type 等待回包（引擎协议无 callId，单连接串行应答）
# ============================================================================

class WsClient:
    """引擎 IDE WS 连接封装。

    - recv 线程循环收帧：文本 JSON → 命中的 waiter 队列交付，其余忽略
      （log/script_state 属插件业务）；二进制帧（投屏 jpeg）→ binary_q（有界，丢旧保新）
    - request(): 发命令并阻塞等待指定 type 回包（UI 侧在 worker 线程调用）
    - 心跳线程每 HEARTBEAT_SEC 秒发应用层 ping
    """

    def __init__(self):
        self.ws = None
        self.send_lock = threading.Lock()
        self.waiters = {}          # reply_type -> queue.Queue
        self.wlock = threading.Lock()
        self.binary_q = queue.Queue(maxsize=32)
        self.stop_evt = threading.Event()
        self.recv_thread = None
        self.hb_thread = None
        self.on_closed = None      # fn(reason) —— 断线回调

    # ---- 生命周期 ----------------------------------------------------------

    def connect(self, url, timeout=2.5):
        """同步连接（在 worker 线程调用）。成功后启动 recv/心跳线程。"""
        ws = websocket.create_connection(url, timeout=timeout, enable_multithread=True)
        ws.settimeout(timeout)
        hello = None
        deadline = time.time() + timeout + 1
        while time.time() < deadline:
            try:
                raw = ws.recv()
            except websocket.WebSocketTimeoutException:
                break
            if isinstance(raw, str):
                try:
                    msg = json.loads(raw)
                except Exception:
                    continue
                if msg.get("type") == "hello":
                    hello = msg.get("data") or {}
                    break
        if hello is None:
            try:
                ws.close()
            except Exception:
                pass
            raise RuntimeError("连接已建立但未收到 hello（非引擎 IDE 服务?）")
        self.ws = ws
        self.stop_evt.clear()
        self.ws.settimeout(0.5)
        self.recv_thread = threading.Thread(target=self._recv_loop, daemon=True)
        self.hb_thread = threading.Thread(target=self._hb_loop, daemon=True)
        self.recv_thread.start()
        self.hb_thread.start()
        return hello

    def close(self):
        self.stop_evt.set()
        ws, self.ws = self.ws, None
        if ws is not None:
            try:
                ws.close()
            except Exception:
                pass

    @property
    def connected(self):
        return self.ws is not None and not self.stop_evt.is_set()

    # ---- 收发 --------------------------------------------------------------

    def send_json(self, obj):
        ws = self.ws
        if ws is None:
            raise ConnectionError("未连接设备")
        with self.send_lock:
            ws.send(json.dumps(obj, ensure_ascii=False))

    def request(self, cmd, data, reply_type, timeout=10.0):
        """发送 {type:cmd,data} 并等待 reply_type 回包；error 回包抛 RuntimeError。"""
        q = queue.Queue(maxsize=1)
        with self.wlock:
            self.waiters[reply_type] = q
        try:
            self.send_json({"type": cmd, "data": data})
            try:
                typ, payload = q.get(timeout=timeout)
            except queue.Empty:
                raise TimeoutError("命令 %s 等待 %s 回包超时(%ss)" % (cmd, reply_type, timeout))
            if typ == "error":
                raise RuntimeError(payload.get("msg") or "引擎返回错误")
            return payload
        finally:
            with self.wlock:
                self.waiters.pop(reply_type, None)

    def clear_binary(self):
        try:
            while True:
                self.binary_q.get_nowait()
        except queue.Empty:
            pass

    def get_binary(self, timeout):
        try:
            return self.binary_q.get(timeout=timeout)
        except queue.Empty:
            return None

    # ---- 内部线程 -----------------------------------------------------------

    def _recv_loop(self):
        reason = "closed"
        try:
            while not self.stop_evt.is_set():
                try:
                    raw = self.ws.recv()
                except websocket.WebSocketTimeoutException:
                    continue
                except (ConnectionError, OSError, websocket.WebSocketException):
                    reason = "连接断开"
                    raise
                if isinstance(raw, bytes):
                    try:
                        self.binary_q.put_nowait(raw)
                    except queue.Full:
                        try:
                            self.binary_q.get_nowait()
                            self.binary_q.put_nowait(raw)
                        except Exception:
                            pass
                    continue
                try:
                    msg = json.loads(raw)
                except Exception:
                    continue
                typ = msg.get("type") or ""
                data = msg.get("data")
                if data is None:
                    data = {}
                with self.wlock:
                    q = self.waiters.get(typ)
                    if q is not None:
                        try:
                            q.put_nowait((typ, data))
                        except queue.Full:
                            pass
        except Exception:
            pass
        finally:
            self.stop_evt.set()
            cb = self.on_closed
            if cb is not None:
                try:
                    cb(reason)
                except Exception:
                    pass

    def _hb_loop(self):
        while not self.stop_evt.wait(HEARTBEAT_SEC):
            try:
                self.send_json({"type": "ping", "data": {}})
            except Exception:
                return


# ============================================================================
# 主应用
# ============================================================================

class App:
    def __init__(self, root):
        self.root = root
        root.title("AutoLua2 抓抓 - 截图取色")
        root.geometry("1420x880")
        root.minsize(1100, 660)

        # 连接状态
        self.client = None            # WsClient
        self.device_info = {}         # hello data
        self.caps = {}                # capabilities data
        self.connecting = False
        self._busy = False            # 后台操作护栏（防命令串包）

        # 截图管理：多张截图，每张独立拥有 取色点/框选/验证标记
        self.shots = []               # [{"img","name","points":[],"rect","marks":[]}]
        self.active = -1              # 当前截图索引
        self.shot_seq = 0             # 截图命名序号
        self.photo = None             # ImageTk 引用（防 GC）
        self.scale = 1.0              # 画布缩放比 = 显示/原始

        # 取色/框选（取色点为全局列表，切换截图保留；框选/验证标记归属各截图）
        self.tool_var = tk.StringVar(value="pick")   # pick | rect
        self.points = []              # [{"x","y","rgb","checked"}] 屏幕绝对坐标
        self._rect_start = None       # 框选拖拽起点（屏幕坐标）
        self.tpl_b64 = None
        self.tpl_path = ""
        self.tpl_size = (0, 0)
        self.ocr_result = None

        # UI
        self.ui_q = queue.Queue()
        self._mag = None              # 放大镜浮动窗
        self._mag_photo = None
        self._resize_job = None

        self._build_left()
        self._build_connbar()
        self._build_center()
        self._build_right()
        self._build_statusbar()
        self._load_templates_to_ui()
        self.refresh_state()
        self._bind_keys()
        root.protocol("WM_DELETE_WINDOW", self.on_close)
        root.after(50, self._poll_ui)

    # ---- UI 线程轮询 ---------------------------------------------------------

    def post(self, fn):
        self.ui_q.put(fn)

    def _poll_ui(self):
        try:
            while True:
                fn = self.ui_q.get_nowait()
                try:
                    fn()
                except Exception:
                    pass
        except queue.Empty:
            pass
        self.root.after(40, self._poll_ui)

    def set_status(self, text):
        self.status_var.set(text)

    def alert(self, level, title, msg):
        """线程安全弹窗（post 到 UI 线程执行）。"""
        def _show():
            {"error": messagebox.showerror, "warning": messagebox.showwarning,
             "info": messagebox.showinfo}[level](title, msg, parent=self.root)
        self.post(_show)

    @property
    def shot(self):
        """当前截图（无则 None）。"""
        if 0 <= self.active < len(self.shots):
            return self.shots[self.active]
        return None

    # ---- 左侧工具栏（固定宽度） -------------------------------------------------

    def _build_left(self):
        left = ttk.Frame(self.root, width=152, padding=(6, 6, 2, 4))
        left.grid(row=0, column=0, rowspan=2, sticky="ns")
        left.grid_propagate(False)   # 锁定宽度，不随子控件文字变化
        left.columnconfigure(0, weight=1)
        self.root.columnconfigure(1, weight=1)
        self.root.rowconfigure(0, weight=1)

        self.mag_on_var = tk.BooleanVar(value=bool(CONFIG.get("mag_on", True)))
        ttk.Checkbutton(left, text="显示放大镜", variable=self.mag_on_var,
                        command=self._on_mag_toggle).pack(fill="x")

        self.btn_shot = ttk.Button(left, text="截图 (Ctrl+S)", command=self.do_screenshot)
        self.btn_shot.pack(fill="x", pady=(4, 1))
        rowc = ttk.Frame(left)
        rowc.pack(fill="x")
        ttk.Label(rowc, text="次数").pack(side="left")
        self.shot_count_var = tk.IntVar(value=1)
        ttk.Spinbox(rowc, from_=1, to=10, increment=1, width=4,
                    textvariable=self.shot_count_var).pack(side="left", padx=4)

        self.btn_load = ttk.Button(left, text="加载 (Ctrl+L)", command=self.load_image)
        self.btn_load.pack(fill="x", pady=(4, 1))
        self.btn_save = ttk.Button(left, text="保存", command=self.save_image)
        self.btn_save.pack(fill="x", pady=1)
        self.btn_save_tpl = ttk.Button(left, text="存模板 PNG", command=self.save_template)
        self.btn_save_tpl.pack(fill="x", pady=1)
        self.btn_crop = ttk.Button(left, text="裁剪 (Ctrl+X)", command=self.crop_to_new_shot)
        self.btn_crop.pack(fill="x", pady=1)
        self.btn_range = ttk.Button(left, text="范围: 关 (Ctrl+R)", command=self.toggle_range_tool)
        self.btn_range.pack(fill="x", pady=1)

        ttk.Separator(left).pack(fill="x", pady=6)
        self.pos_var = tk.StringVar(value="(-- , --)")
        ttk.Label(left, textvariable=self.pos_var, font=("Consolas", 10)).pack(fill="x")
        crow = ttk.Frame(left)
        crow.pack(fill="x", pady=2)
        self.color_swatch = tk.Canvas(crow, width=24, height=24, highlightthickness=1,
                                      highlightbackground="#2c313a")
        self.color_swatch.pack(side="left")
        self.color_swatch.create_rectangle(0, 0, 24, 24, fill="#000000", outline="", tags="sw")
        self.color_var = tk.StringVar(value="#000000")
        ttk.Label(crow, textvariable=self.color_var, font=("Consolas", 10, "bold")).pack(side="left", padx=4)
        self.size_var = tk.StringVar(value="0 x 0")
        ttk.Label(left, textvariable=self.size_var, foreground="#7d8590").pack(fill="x")
        self.btn_copy = ttk.Button(left, text="复制颜色", command=self.copy_color)
        self.btn_copy.pack(fill="x", pady=(4, 1))

        zrow = ttk.Frame(left)
        zrow.pack(fill="x", pady=1)
        self.btn_fit = ttk.Button(zrow, text="适应", width=5, command=lambda: self.set_fit_mode("fit"))
        self.btn_fit.pack(side="left")
        self.btn_orig = ttk.Button(zrow, text="1:1", width=5, command=lambda: self.set_fit_mode("actual"))
        self.btn_orig.pack(side="left", padx=2)

        ttk.Separator(left).pack(fill="x", pady=6)
        self.btn_clear_marks = ttk.Button(left, text="清除查找标记", command=self.clear_marks)
        self.btn_clear_marks.pack(fill="x", pady=1)
        self.btn_clear_rect = ttk.Button(left, text="清除框选", command=self.clear_rect)
        self.btn_clear_rect.pack(fill="x", pady=1)
        self.btn_ocr = ttk.Button(left, text="OCR 此区域", command=self.run_ocr)
        self.btn_ocr.pack(fill="x", pady=(8, 1))
        self.rect_var = tk.StringVar(value="未框选")
        ttk.Label(left, textvariable=self.rect_var, foreground="#4f9cf9").pack(fill="x", pady=(2, 0))

        # 需随状态启停的按钮：(按钮, 需连接, 需截图)
        self.stateful = [
            (self.btn_shot, True, False),
            (self.btn_save, False, True),
            (self.btn_save_tpl, False, True),
            (self.btn_crop, False, True),
            (self.btn_copy, False, True),
            (self.btn_fit, False, True),
            (self.btn_orig, False, True),
            (self.btn_clear_marks, False, True),
            (self.btn_clear_rect, False, True),
            (self.btn_ocr, True, True),
        ]

    def _on_mag_toggle(self):
        CONFIG["mag_on"] = bool(self.mag_on_var.get())
        if not self.mag_on_var.get():
            self._hide_magnifier()
        self.save_config()

    def toggle_range_tool(self):
        self.tool_var.set("rect" if self.tool_var.get() == "pick" else "pick")
        self._sync_range_button()

    def _sync_range_button(self):
        on = self.tool_var.get() == "rect"
        self.btn_range.config(text="范围: 开 (Ctrl+R)" if on else "范围: 关 (Ctrl+R)")

    def copy_color(self):
        self.root.clipboard_clear()
        self.root.clipboard_append(self.color_var.get())
        self.set_status("已复制 %s" % self.color_var.get())

    def set_fit_mode(self, mode):
        self.fit_var = mode
        CONFIG["fit_mode"] = mode
        self.save_config()
        self._render_image()

    def _bind_keys(self):
        def _crop_guard(_e):
            # 焦点在输入框/文本域时不劫持 Ctrl+X（保留剪切）
            f = self.root.focus_get()
            if isinstance(f, (tk.Entry, ttk.Entry, tk.Text)):
                return None
            self.crop_to_new_shot()
            return "break"
        self.root.bind_all("<Control-s>", lambda _e: (self.do_screenshot(), "break")[1])
        self.root.bind_all("<Control-l>", lambda _e: (self.load_image(), "break")[1])
        self.root.bind_all("<Control-x>", _crop_guard)
        self.root.bind_all("<Control-r>", lambda _e: (self.toggle_range_tool(), "break")[1])
        self.root.bind_all("<Control-b>", lambda _e: (self.clear_points(), "break")[1])
        self.root.bind_all("<Delete>", self._on_delete_key)


    # ---- 连接栏（顶） ----------------------------------------------------------

    def _build_connbar(self):
        bar = ttk.Frame(self.root, padding=(2, 6, 6, 0))
        bar.grid(row=0, column=1, columnspan=2, sticky="ew")
        self.root.rowconfigure(0, weight=0)
        self.mode_var = tk.StringVar(value=CONFIG.get("mode", "adb"))
        for val, txt in (("adb", "ADB 设备"), ("direct", "直连 IP")):
            ttk.Radiobutton(bar, text=txt, value=val, variable=self.mode_var,
                            command=self._on_mode_change).pack(side="left")
        ttk.Label(bar, text=" 设备:").pack(side="left")
        self.dev_var = tk.StringVar(value=CONFIG.get("serial", ""))
        self.dev_combo = ttk.Combobox(bar, textvariable=self.dev_var, width=16, state="readonly")
        self.dev_combo.pack(side="left", padx=(2, 2))
        self.btn_refresh = ttk.Button(bar, text="刷新", width=4, command=self.refresh_devices)
        self.btn_refresh.pack(side="left")
        ttk.Label(bar, text=" 地址:").pack(side="left")
        self.addr_var = tk.StringVar(value=CONFIG.get("direct_addr", "127.0.0.1"))
        self.addr_entry = ttk.Entry(bar, textvariable=self.addr_var, width=13)
        self.addr_entry.pack(side="left", padx=(2, 4))
        ttk.Label(bar, text="adb:").pack(side="left")
        self.adb_var = tk.StringVar(value=CONFIG.get("adb_path", "adb"))
        self.adb_entry = ttk.Entry(bar, textvariable=self.adb_var, width=11)
        self.adb_entry.pack(side="left", padx=(2, 6))
        self.btn_connect = ttk.Button(bar, text="连接", command=self.on_connect)
        self.btn_connect.pack(side="left")
        self.btn_disconnect = ttk.Button(bar, text="断开", command=self.on_disconnect)
        self.btn_disconnect.pack(side="left", padx=(4, 8))
        self.dot_var = tk.StringVar(value="● 未连接")
        ttk.Label(bar, textvariable=self.dot_var, foreground="#3fb96f").pack(side="left")
        self.ocr_label = ttk.Label(bar, text="OCR: 未知", foreground="#7d8590")
        self.ocr_label.pack(side="right")
        adb = self.mode_var.get() == "adb"
        self.dev_combo.config(state="readonly" if adb else "disabled")
        self.addr_entry.config(state="disabled" if adb else "normal")

    # ---- 中央：画布 + 截图缩略图条 ----------------------------------------------

    def _build_center(self):
        self.root.columnconfigure(1, weight=1)
        self.root.rowconfigure(1, weight=1)
        self.fit_var = CONFIG.get("fit_mode", "fit")

        center = ttk.Frame(self.root)
        center.grid(row=1, column=1, sticky="nsew", padx=(2, 4), pady=4)
        center.rowconfigure(0, weight=1)
        center.columnconfigure(0, weight=1)

        self.canvas = tk.Canvas(center, bg="#0b0d10", highlightthickness=0, cursor="crosshair")
        self.canvas.grid(row=0, column=0, sticky="nsew")
        self.canvas.bind("<Motion>", self._on_motion)
        self.canvas.bind("<Leave>", lambda _e: self._hide_magnifier())
        self.canvas.bind("<Button-1>", self._on_left_down)
        self.canvas.bind("<B1-Motion>", self._on_left_drag)
        self.canvas.bind("<ButtonRelease-1>", self._on_left_up)
        self.canvas.bind("<Button-3>", self._on_right_click)
        self.canvas.bind("<Configure>", self._on_canvas_resize)
        self._hint_id = None

        # 截图缩略图条
        strip = ttk.Frame(center)
        strip.grid(row=1, column=0, sticky="ew", pady=(4, 0))
        self.thumb_canvas = tk.Canvas(strip, height=THUMB_H + 8, bg="#14161a",
                                      highlightthickness=1, highlightbackground="#2c313a")
        self.thumb_canvas.pack(side="left", fill="x", expand=True)
        self.thumb_canvas.bind("<Button-1>", self._on_thumb_click)
        self.thumb_canvas.bind("<Button-3>", self._on_thumb_right)
        self.thumb_canvas.bind("<Configure>", lambda _e: self._draw_thumbs())
        ttk.Button(strip, text="删除当前截图", width=11,
                   command=self.delete_active_shot).pack(side="left", padx=(4, 0))
        self.thumb_hit = []          # [(x0,x1,shot_idx)] 命中区域

    # ---- 右侧：多标签面板 --------------------------------------------------------

    def _build_right(self):
        right = ttk.Frame(self.root, width=470)
        right.grid(row=1, column=2, sticky="ns", padx=(0, 6), pady=4)
        right.grid_propagate(False)
        right.columnconfigure(0, weight=1)
        right.rowconfigure(0, weight=1)
        nb = ttk.Notebook(right)
        nb.grid(row=0, column=0, sticky="nsew")
        self.tab_pts = ttk.Frame(nb)
        self.tab_code = ttk.Frame(nb)
        nb.add(self.tab_pts, text="图色工具")
        nb.add(self.tab_code, text="代码生成")
        self._build_points_tab(self.tab_pts)
        self._build_codegen_tab(self.tab_code)

    def _build_points_tab(self, tab):
        tab.columnconfigure(0, weight=1)
        tab.rowconfigure(0, weight=1)
        cols = ("chk", "pos", "color", "rgb", "offset", "inv")
        heads = (("chk", "勾", 30), ("pos", "坐标", 86),
                 ("color", "颜色", 82), ("rgb", "RGB", 92), ("offset", "偏移", 72),
                 ("inv", "反色", 82))
        self.pt_tree = ttk.Treeview(tab, columns=cols, show="headings", height=16)
        for cid, txt, w in heads:
            self.pt_tree.heading(cid, text=txt)
            self.pt_tree.column(cid, width=w, anchor="center", stretch=(cid in ("pos", "color", "rgb")))
        self.pt_tree.grid(row=0, column=0, sticky="nsew", padx=(4, 0), pady=4)
        vsb = ttk.Scrollbar(tab, orient="vertical", command=self.pt_tree.yview)
        vsb.grid(row=0, column=1, sticky="ns", pady=4)
        self.pt_tree.configure(yscrollcommand=vsb.set)
        self.pt_tree.bind("<Button-1>", self._on_pt_tree_click)
        self.pt_tree.bind("<Double-Button-1>", self._on_pt_tree_edit)
        self.pt_tree.bind("<Button-3>", self._on_pt_tree_menu)
        self.pt_menu = tk.Menu(self.root, tearoff=0)
        self.pt_menu.add_command(label="删除选中行", command=self._delete_selected_rows)
        self.pt_menu.add_command(label="反选勾选", command=self._invert_checked)

        brow = ttk.Frame(tab)
        brow.grid(row=1, column=0, columnspan=2, sticky="ew", padx=4, pady=(0, 4))
        self.btn_clear_points = ttk.Button(brow, text="清除所有", command=self.clear_points)
        self.btn_clear_points.pack(side="left")
        self.btn_del_rows = ttk.Button(brow, text="删除选中", command=self._delete_selected_rows)
        self.btn_del_rows.pack(side="left", padx=4)
        self.btn_recheck = ttk.Button(brow, text="刷新坐标颜色", command=self.recheck_colors)
        self.btn_recheck.pack(side="left")
        ttk.Label(tab, text="勾=参与验证/生成并显示标记（点击取消）   双击坐标/颜色/RGB/反色可编辑   右键=删除行",
                  foreground="#7d8590", wraplength=430, justify="left").grid(
            row=2, column=0, columnspan=2, sticky="w", padx=4, pady=(0, 4))
        for b in (self.btn_clear_points, self.btn_del_rows, self.btn_recheck):
            self.stateful.append((b, False, True))

    def _build_codegen_tab(self, tab):
        tab.columnconfigure(1, weight=1)
        r = 0
        ttk.Label(tab, text="精度").grid(row=r, column=0, sticky="w", padx=6, pady=(8, 2))
        self.sim_var = tk.DoubleVar(value=float(CONFIG.get("similarity", 0.9)))
        sp = ttk.Spinbox(tab, from_=0.5, to=1.0, increment=0.01, width=7,
                         textvariable=self.sim_var, command=self._on_sim_change)
        sp.grid(row=r, column=1, sticky="w", pady=(8, 2))
        sp.bind("<FocusOut>", lambda _e: self._on_sim_change())
        r += 1
        ttk.Label(tab, text="函数").grid(row=r, column=0, sticky="w", padx=6, pady=2)
        self.func_var = tk.StringVar(value="多点找色")
        self.func_combo = ttk.Combobox(tab, textvariable=self.func_var, state="readonly",
                                       values=list(FUNCS.values()))
        self.func_combo.grid(row=r, column=1, sticky="ew", pady=2)
        self.func_combo.bind("<<ComboboxSelected>>", lambda _e: self._load_templates_to_ui())
        r += 1
        ttk.Label(tab, text="方向").grid(row=r, column=0, sticky="w", padx=6, pady=2)
        self.order_var = tk.StringVar(value="默认")
        ttk.Combobox(tab, textvariable=self.order_var, state="readonly",
                     values=list(ORDERS.keys())).grid(row=r, column=1, sticky="ew", pady=2)
        r += 1
        ttk.Label(tab, text="颜色").grid(row=r, column=0, sticky="w", padx=6, pady=2)
        crow = ttk.Frame(tab)
        crow.grid(row=r, column=1, sticky="ew", pady=2)
        crow.columnconfigure(0, weight=1)
        self.color_entry_var = tk.StringVar(value="")
        ttk.Entry(crow, textvariable=self.color_entry_var).grid(row=0, column=0, sticky="ew")
        ttk.Button(crow, text="复制颜色", width=8, command=self._copy_color_entry).grid(row=0, column=1, padx=(4, 0))
        r += 1
        ttk.Label(tab, text="参数").grid(row=r, column=0, sticky="w", padx=6, pady=2)
        prow = ttk.Frame(tab)
        prow.grid(row=r, column=1, sticky="ew", pady=2)
        prow.columnconfigure(0, weight=1)
        self.param_entry_var = tk.StringVar(value="")
        ttk.Entry(prow, textvariable=self.param_entry_var).grid(row=0, column=0, sticky="ew")
        ttk.Button(prow, text="格式", width=5, command=self._sync_param_entry).grid(row=0, column=1, padx=(4, 0))
        ttk.Button(prow, text="复制参数", width=8, command=self._copy_param_entry).grid(row=0, column=2, padx=(4, 0))
        r += 1
        ttk.Label(tab, text="模板").grid(row=r, column=0, sticky="nw", padx=6, pady=(4, 2))
        tfr = ttk.Frame(tab)
        tfr.grid(row=r, column=1, sticky="ew", pady=(4, 2))
        tfr.columnconfigure(0, weight=1)
        self.tpl_text = tk.Text(tfr, height=3, wrap="word", font=("Consolas", 10),
                                bg="#101216", fg="#d6dae1", insertbackground="#d6dae1")
        self.tpl_text.grid(row=0, column=0, sticky="ew")
        ttk.Button(tfr, text="恢复默认", width=8, command=self.reset_template).grid(row=0, column=1, padx=(4, 0), sticky="n")
        r += 1
        ttk.Label(tab, text="结果").grid(row=r, column=0, sticky="nw", padx=6, pady=(4, 2))
        self.out_text = tk.Text(tab, height=9, wrap="word", font=("Consolas", 10),
                                bg="#101216", fg="#9ad48a", insertbackground="#d6dae1")
        self.out_text.grid(row=r, column=1, sticky="new", pady=(4, 2))
        r += 1
        br = ttk.Frame(tab)
        br.grid(row=r, column=0, columnspan=2, sticky="ew", padx=6, pady=(6, 8))
        self.btn_gen = ttk.Button(br, text="生成代码", command=self.gen_code)
        self.btn_gen.pack(side="left")
        self.btn_t_single = ttk.Button(br, text="比色测试", command=self.verify_single)
        self.btn_t_single.pack(side="left", padx=4)
        self.btn_t_multi = ttk.Button(br, text="多点测试", command=self.verify_multi)
        self.btn_t_multi.pack(side="left")
        self.btn_t_img = ttk.Button(br, text="图片查找", command=self.verify_image)
        self.btn_t_img.pack(side="left", padx=4)
        self.btn_copy_code = ttk.Button(br, text="复制代码", command=self.copy_code)
        self.btn_copy_code.pack(side="right")
        for b in (self.btn_t_single, self.btn_t_multi):
            self.stateful.append((b, True, False))
        self.stateful.append((self.btn_t_img, True, True))

    def _build_statusbar(self):
        self.status_var = tk.StringVar(value="就绪 — 连接后【截图】（可设次数连续截多张），点画布取色")
        bar = ttk.Label(self.root, textvariable=self.status_var, anchor="w",
                        relief="sunken", padding=(8, 3))
        bar.grid(row=2, column=0, columnspan=3, sticky="ew")

    def refresh_state(self):
        """按 连接/截图 状态启停各按钮。"""
        conn = self.client is not None and self.client.connected
        has_img = self.shot is not None
        self.btn_connect.config(state="disabled" if (conn or self.connecting) else "normal")
        self.btn_disconnect.config(state="normal" if conn else "disabled")
        self.btn_refresh.config(state="normal" if (not self.connecting and self.mode_var.get() == "adb") else "disabled")
        for b, need_conn, need_img in self.stateful:
            ok = (conn or not need_conn) and (has_img or not need_img)
            b.config(state="normal" if ok else "disabled")
        if conn:
            self.dot_var.set("● 已连接 " + self.device_info.get("device_name", ""))
        elif not self.connecting:
            self.dot_var.set("● 未连接")

    def save_config(self):
        CONFIG["mode"] = self.mode_var.get()
        CONFIG["serial"] = self.dev_var.get()
        CONFIG["direct_addr"] = self.addr_var.get()
        CONFIG["adb_path"] = self.adb_var.get()
        CONFIG["similarity"] = round(float(self.sim_var.get()), 2)
        save_json(CFG_PATH, CONFIG)


    # ---- 连接管理 --------------------------------------------------------------

    def _adb(self, *args, timeout=8):
        exe = self.adb_var.get().strip() or "adb"
        return subprocess.run([exe] + list(args), capture_output=True, text=True,
                              timeout=timeout, creationflags=create_no_window())

    def _on_mode_change(self):
        adb = self.mode_var.get() == "adb"
        CONFIG["mode"] = self.mode_var.get()
        self.dev_combo.config(state="readonly" if adb else "disabled")
        self.addr_entry.config(state="disabled" if adb else "normal")
        self.refresh_state()
        self.save_config()

    def _on_sim_change(self):
        CONFIG["similarity"] = round(float(self.sim_var.get()), 2)
        self.save_config()

    def refresh_devices(self):
        def worker():
            try:
                out = self._adb("devices").stdout
            except Exception as e:
                self.alert("error", "adb 失败", "执行 adb devices 失败：%s\n请检查顶栏 adb 路径" % e)
                return
            serials = []
            for line in out.splitlines()[1:]:
                line = line.strip()
                if line.endswith("\tdevice"):
                    serials.append(line.split("\t")[0])
            def apply():
                self.dev_combo["values"] = serials
                if serials:
                    if self.dev_var.get() not in serials:
                        self.dev_var.set(serials[0])
                    self.set_status("发现 %d 台设备：%s" % (len(serials), ", ".join(serials)))
                else:
                    self.set_status("未发现 adb 设备（模拟器需先 adb connect 或检查 adb 路径）")
            self.post(apply)
        threading.Thread(target=worker, daemon=True).start()

    def on_connect(self):
        if self.client is not None and self.client.connected:
            return
        self.connecting = True
        self.refresh_state()
        self.save_config()
        mode = self.mode_var.get()
        serial = self.dev_var.get().strip()
        addr = self.addr_var.get().strip()
        if mode == "adb" and not serial:
            self.connecting = False
            self.refresh_state()
            messagebox.showwarning("提示", "请先【刷新】并选择 adb 设备", parent=self.root)
            return
        if mode == "direct" and not addr:
            self.connecting = False
            self.refresh_state()
            messagebox.showwarning("提示", "请填写直连地址（IP[:端口]）", parent=self.root)
            return
        self.set_status("连接中…")
        threading.Thread(target=self._connect_worker, args=(mode, serial, addr), daemon=True).start()

    def _connect_worker(self, mode, serial, addr):
        errors = []
        client = WsClient()
        client.on_closed = lambda reason: self._on_closed(reason)
        url = None
        hello = None
        if mode == "adb":
            custom = self._parse_addr_port(serial)
            if custom:  # 设备框手填 ip:port → 按直连处理
                addr, hello, url, errors = self._try_direct(client, custom[0], [custom[1]], errors)
            if url is None:
                for i, dp in enumerate(DEV_PORTS):
                    fp = FWD_PORTS[i]
                    try:
                        self._adb("-s", serial, "forward", "tcp:%d" % fp, "tcp:%d" % dp)
                    except Exception as e:
                        errors.append("forward %d: %s" % (dp, e))
                        continue
                    url2 = "ws://127.0.0.1:%d" % fp
                    self.post(lambda u=url2: self.set_status("探测 %s …" % u))
                    try:
                        hello = client.connect(url2, timeout=2.0)
                        url = url2
                        break
                    except Exception as e:
                        errors.append("%d: %s" % (dp, e))
        else:
            addr, hello, url, errors = self._try_direct(client, addr, DEV_PORTS, errors)

        if url is None:
            self.connecting = False
            self.post(self.refresh_state)
            self.alert("error", "连接失败", "所有端口(8080-8085)均未连通：\n" + "\n".join(errors[-6:]))
            return
        try:
            caps = client.request("capabilities", {}, "capabilities", timeout=6)
        except Exception as e:
            client.close()
            self.connecting = False
            self.post(self.refresh_state)
            self.alert("error", "连接失败", "capabilities 获取失败：%s" % e)
            return
        self.client = client
        self.device_info = hello or {}
        self.caps = caps or {}
        self.connecting = False
        def apply():
            self.refresh_state()
            vis = (self.caps.get("vision") or {}).get("ocr")
            self.ocr_label.config(text="OCR: %s" % ("可用" if vis else "不可用"),
                                  foreground="#3fb96f" if vis else "#7d8590")
            self.set_status("已连接 %s（%s）— 引擎 v%s" % (
                self.device_info.get("device_name", "?"), url,
                self.device_info.get("app_version", "?")))
        self.post(apply)

    @staticmethod
    def _parse_addr_port(text):
        """'ip:port' → (ip, port)；纯 ip/serial 返回 None。"""
        if ":" in text and text.rsplit(":", 1)[1].isdigit():
            ip, p = text.rsplit(":", 1)
            return (ip, int(p))
        return None

    def _try_direct(self, client, addr, ports, errors):
        url = None
        hello = None
        parsed = self._parse_addr_port(addr)
        if parsed:
            ports = [parsed[1]]
            addr = parsed[0]
        for dp in ports:
            url2 = "ws://%s:%d" % (addr, dp)
            self.post(lambda u=url2: self.set_status("探测 %s …" % u))
            try:
                hello = client.connect(url2, timeout=2.0)
                url = url2
                break
            except Exception as e:
                errors.append("%s: %s" % (dp, e))
        return addr, hello, url, errors

    def _on_closed(self, reason):
        self.client = None
        self.connecting = False
        def apply():
            self.refresh_state()
            self.set_status("连接已断开（%s）" % reason)
        self.post(apply)

    def on_disconnect(self):
        if self.client is not None:
            self.client.close()
        self.client = None
        self.refresh_state()
        self.set_status("已断开")

    # ---- 截图（支持一次连拍 N 张） ----------------------------------------------

    def _capture_frames(self, count):
        """连拍 count 帧：单次 stream 会话内收 N 帧，返回 [PIL.Image]；失败抛异常。"""
        client = self.client
        client.clear_binary()
        reply = client.request("stream_start",
                               {"codec": "jpeg", "fps": 2, "quality": 80, "scale": 1.0},
                               "stream_started", timeout=FRAME_TIMEOUT)
        engine_w, engine_h = int(reply.get("w", 0)), int(reply.get("h", 0))
        frames = []
        try:
            stale = 0
            while len(frames) < count:
                raw = client.get_binary(timeout=1.0)
                if raw is None or len(raw) < 16:
                    stale += 1
                    if stale > FRAME_TIMEOUT:  # 1s×10 无帧
                        break
                    continue
                stale = 0
                _seq, fw, fh, ln = struct.unpack(">IIII", raw[:16])
                payload = raw[16:]
                if len(payload) < ln:
                    continue
                try:
                    img = Image.open(io.BytesIO(payload[:ln]))
                    img.load()
                    if img.mode != "RGB":
                        img = img.convert("RGB")
                except Exception:
                    continue
                if frames and img.size == frames[-1].size and list(img.getdata()) == list(frames[-1].getdata()):
                    continue  # 引擎在静止画面会重复喂最后一帧，去重避免"连续截 3 张全一样"
                frames.append(img)
        finally:
            try:
                client.request("stream_stop", {}, "result", timeout=5)
            except Exception:
                pass
        if not frames:
            raise TimeoutError("等待 jpeg 帧超时（引擎回报 %dx%d）" % (engine_w, engine_h))
        return frames

    def do_screenshot(self):
        if self._busy:
            self.set_status("上一个操作还在执行中…")
            return
        if self.client is None or not self.client.connected:
            return
        count = max(1, min(10, int(self.shot_count_var.get() or 1)))
        self.set_status("截图请求中…（%d 张，每张约 1-2s）" % count)
        def fn():
            frames = self._capture_frames(count)
            def apply():
                for img in frames:
                    self._append_shot(img)
                self.set_status("已截 %d 张（共 %d 张）— 点缩略图切换，点画布取色" % (len(frames), len(self.shots)))
            self.post(apply)
        self._run_bg(fn, "截图")  # busy 置位/复位统一由 _run_bg 管理，切勿提前置位（会自锁）

    def _append_shot(self, img):
        self.shot_seq += 1
        self.shots.append({"img": img, "name": "截图%d" % self.shot_seq,
                           "rect": None, "marks": []})
        if len(self.shots) > MAX_SHOTS:
            self.shots.pop(0)
        self.active = len(self.shots) - 1
        self._on_shot_changed()

    def _on_shot_changed(self):
        s = self.shot
        if s:
            self.size_var.set("%d x %d" % s["img"].size)
        else:
            self.size_var.set("0 x 0")
        self._render_image()
        self._draw_thumbs()
        self.refresh_points_tree()
        self._update_rect_labels()
        self._sync_color_param_entries()
        self.refresh_state()

    def delete_active_shot(self):
        if not self.shots:
            return
        del self.shots[self.active]
        self.active = min(self.active, len(self.shots) - 1)
        self._on_shot_changed()
        self.set_status("已删除，剩 %d 张" % len(self.shots))

    def _on_thumb_click(self, event):
        for x0, x1, idx in self.thumb_hit:
            if x0 <= event.x < x1:
                if idx != self.active:
                    self.active = idx
                    self._on_shot_changed()
                return

    def _on_thumb_right(self, event):
        for x0, x1, idx in self.thumb_hit:
            if x0 <= event.x < x1:
                del self.shots[idx]
                if not self.shots:
                    self.active = -1
                else:
                    self.active = min(self.active, len(self.shots) - 1)
                self._on_shot_changed()
                return

    def _draw_thumbs(self):
        c = self.thumb_canvas
        c.delete("all")
        self.thumb_hit = []
        if not self.shots:
            c.create_text(12, THUMB_H // 2 + 4, anchor="w", text="暂无截图 — 连接后点【截图】",
                          fill="#7d8590")
            return
        x = 6
        for i, s in enumerate(self.shots):
            tw = int(s["img"].width * (THUMB_H / s["img"].height))
            tw = max(tw, 20)
            th = THUMB_H
            thumb = s["img"].resize((tw, th), Image.NEAREST)
            photo = ImageTk.PhotoImage(thumb)
            if not hasattr(self, "_thumb_photos"):
                self._thumb_photos = {}
            self._thumb_photos[i] = photo
            active = (i == self.active)
            outline = "#4f9cf9" if active else "#2c313a"
            c.create_rectangle(x, 4, x + tw, 4 + th, fill="#000000", outline=outline, width=2 if active else 1)
            c.create_image(x, 4, anchor="nw", image=photo)
            c.create_text(x + 3, 6, anchor="nw", text=s["name"], fill="#4f9cf9" if active else "#d6dae1",
                          font=("Segoe UI", 8))
            self.thumb_hit.append((x - 2, x + tw + 2, i))
            x += tw + 8
        c.configure(scrollregion=(0, 0, x, THUMB_H + 8))

    # ---- 画布渲染与坐标 ---------------------------------------------------------

    def _render_image(self):
        """按 fit/1:1 模式重绘当前截图（含覆盖层）。"""
        c = self.canvas
        s = self.shot
        if s is None:
            c.delete("all")
            self._hint_id = c.create_text(260, 80, fill="#7d8590", font=("Segoe UI", 11),
                                          text="未连接设备 —— 选择连接方式后点【连接】，再点【📷 截图】")
            return
        img = s["img"]
        cw = max(c.winfo_width(), 100)
        ch = max(c.winfo_height(), 100)
        if self.fit_var == "fit":
            self.scale = min(cw / img.width, ch / img.height, 1.0)
        else:
            self.scale = 1.0
        disp_w = max(int(img.width * self.scale), 1)
        disp_h = max(int(img.height * self.scale), 1)
        disp = img.resize((disp_w, disp_h), Image.NEAREST)
        self.photo = ImageTk.PhotoImage(disp)
        c.delete("all")
        c.create_image(0, 0, anchor="nw", image=self.photo)
        self._redraw_overlays()

    def _on_canvas_resize(self, _event):
        if self.shot is None:
            return
        if getattr(self, "_resize_job", None):
            self.root.after_cancel(self._resize_job)
        self._resize_job = self.root.after(120, self._render_image)

    def _to_screen(self, cx, cy):
        """画布坐标 → 截图像素坐标（钳制到图像范围）。"""
        s = self.shot
        x = int(round(cx / max(self.scale, 1e-6)))
        y = int(round(cy / max(self.scale, 1e-6)))
        x = max(0, min(x, s["img"].width - 1))
        y = max(0, min(y, s["img"].height - 1))
        return x, y

    def _to_canvas(self, x, y):
        return x * self.scale, y * self.scale

    def _pixel_at(self, x, y):
        s = self.shot
        if s is None:
            return 0
        px = s["img"].getpixel((max(0, min(x, s["img"].width - 1)),
                                max(0, min(y, s["img"].height - 1))))
        return (px[0] << 16) | (px[1] << 8) | px[2]

    def _redraw_overlays(self):
        """重绘 取色点（勾选才显示，全局列表）/框选/验证标记。"""
        c = self.canvas
        s = self.shot
        c.delete("ovl")
        if s is None:
            return
        for i, p in enumerate(self.points):
            if not p.get("checked", True):
                continue
            cx, cy = self._to_canvas(p["x"], p["y"])
            r = 4
            c.create_oval(cx - r, cy - r, cx + r, cy + r, outline="#ffffff", width=1, tags="ovl")
            c.create_rectangle(cx + r, cy - 8, cx + r + 36, cy + 8,
                               fill="#%06X" % p["rgb"], outline="#2c313a", tags="ovl")
            lum = ((p["rgb"] >> 16) & 255) + ((p["rgb"] >> 8) & 255) + (p["rgb"] & 255)
            c.create_text(cx + r + 2, cy, text=str(i + 1),
                          fill="#000000" if lum > 384 else "#ffffff",
                          font=("Segoe UI", 8), tags="ovl")
        if s["rect"]:
            x, y, w, h = s["rect"]
            cx0, cy0 = self._to_canvas(x, y)
            cx1, cy1 = self._to_canvas(x + w, y + h)
            c.create_rectangle(cx0, cy0, cx1, cy1, outline="#4f9cf9", width=2, tags="ovl")
        for item in s["marks"]:
            if item.get("kind") == "rect":
                cx0, cy0 = self._to_canvas(item["x"], item["y"])
                cx1, cy1 = self._to_canvas(item["x"] + item["w"], item["y"] + item["h"])
                c.create_rectangle(cx0, cy0, cx1, cy1, outline=item.get("color", "#3fb96f"),
                                   width=2, tags="ovl")
                if item.get("label"):
                    c.create_text(cx0 + 2, max(cy0 - 8, 8), text=item["label"], anchor="w",
                                  fill=item.get("color", "#3fb96f"), font=("Segoe UI", 9, "bold"),
                                  tags="ovl")
            elif item.get("kind") == "cross":
                cx, cy = self._to_canvas(item["x"], item["y"])
                c.create_line(cx - 8, cy, cx + 8, cy, fill=item.get("color", "#e0564f"), width=2, tags="ovl")
                c.create_line(cx, cy - 8, cx, cy + 8, fill=item.get("color", "#e0564f"), width=2, tags="ovl")

    def clear_marks(self):
        s = self.shot
        if s:
            s["marks"] = []
            self._redraw_overlays()
            self.set_status("查找标记已清除")


    # ---- 画布交互：浮动放大镜 / 取色 / 框选 --------------------------------------

    def _on_motion(self, event):
        if self.shot is None:
            return
        sx, sy = self._to_screen(event.x, event.y)
        rgb = self._pixel_at(sx, sy)
        self.pos_var.set("(%d , %d)" % (sx, sy))
        self.color_var.set("#%06X" % rgb)
        self.color_swatch.itemconfig("sw", fill="#%06X" % rgb)
        if self.mag_on_var.get():
            self._show_magnifier(event.x_root, event.y_root, sx, sy)
        else:
            self._hide_magnifier()

    def _show_magnifier(self, rx, ry, sx, sy):
        """浮动放大镜（跟随鼠标，15x15 像素 → 120x120）。"""
        if self._mag is None:
            self._mag = tk.Toplevel(self.root)
            self._mag.overrideredirect(True)
            self._mag.attributes("-topmost", True)
            self.mag_cv = tk.Canvas(self._mag, width=120, height=120,
                                    highlightthickness=1, highlightbackground="#4f9cf9")
            self.mag_cv.pack()
        M, Z = 7, 8
        half = M * Z // 2
        s = self.shot
        x0, y0 = max(0, sx - M), max(0, sy - M)
        x1, y1 = min(s["img"].width, sx + M + 1), min(s["img"].height, sy + M + 1)
        crop = s["img"].crop((x0, y0, x1, y1)).resize(((x1 - x0) * Z, (y1 - y0) * Z), Image.NEAREST)
        self._mag_photo = ImageTk.PhotoImage(crop)
        cv = self.mag_cv
        cv.delete("all")
        ox, oy = (sx - x0) * Z, (sy - y0) * Z
        cv.create_image(half - ox, half - oy, anchor="nw", image=self._mag_photo)
        cv.create_rectangle(half - Z, half - Z, half + Z, half + Z, outline="#e0564f", width=1)
        px = rx + 18
        py = ry + 18
        sw, sh = 122, 122
        if px + sw > self.root.winfo_screenwidth():
            px = rx - sw - 12
        if py + sh > self.root.winfo_screenheight():
            py = ry - sh - 12
        self._mag.geometry("+%d+%d" % (px, py))

    def _hide_magnifier(self):
        if self._mag is not None:
            self._mag.destroy()
            self._mag = None

    def _on_left_down(self, event):
        if self.shot is None:
            return
        if self.tool_var.get() == "pick":
            sx, sy = self._to_screen(event.x, event.y)
            self.points.append({"x": sx, "y": sy, "rgb": self._pixel_at(sx, sy),
                                "checked": True})
            self.refresh_points_tree()
            self._redraw_overlays()
            self._sync_color_param_entries()
            self.set_status("已记录点 %d (%d,%d) #%06X（画布右键撤销）" %
                            (len(self.points), sx, sy, self.points[-1]["rgb"]))
        else:
            self._rect_start = self._to_screen(event.x, event.y)

    def _on_left_drag(self, event):
        if self.shot is None or self._rect_start is None:
            return
        x0, y0 = self._rect_start
        x1, y1 = self._to_screen(event.x, event.y)
        rx, ry = min(x0, x1), min(y0, y1)
        rw, rh = abs(x1 - x0), abs(y1 - y0)
        c = self.canvas
        c.delete("dragrect")
        cx0, cy0 = self._to_canvas(rx, ry)
        cx1, cy1 = self._to_canvas(rx + rw, ry + rh)
        c.create_rectangle(cx0, cy0, cx1, cy1, outline="#d9a13c", width=2, tags="dragrect")
        self.set_status("框选中：%d,%d %dx%d" % (rx, ry, rw, rh))

    def _on_left_up(self, event):
        if self.shot is None or self._rect_start is None:
            return
        x0, y0 = self._rect_start
        self._rect_start = None
        x1, y1 = self._to_screen(event.x, event.y)
        rx, ry = min(x0, x1), min(y0, y1)
        rw, rh = abs(x1 - x0), abs(y1 - y0)
        self.canvas.delete("dragrect")
        if rw < 2 or rh < 2:
            self.set_status("框选太小（<2px），已忽略")
            return
        self.shot["rect"] = (rx, ry, rw, rh)
        self.shot["marks"] = []
        self._update_rect_labels()
        self._redraw_overlays()
        self.set_status("框选完成 (%d,%d) %dx%d — 可存模板(图片查找/裁剪/OCR)" % (rx, ry, rw, rh))

    def _on_right_click(self, _event):
        if self.points:
            self.points.pop()
            self.refresh_points_tree()
            self._redraw_overlays()
            self._sync_color_param_entries()

    def _update_rect_labels(self):
        s = self.shot
        if s and s["rect"]:
            x, y, w, h = s["rect"]
            self.rect_var.set("范围: %d,%d %dx%d" % (x, y, w, h))
        else:
            self.rect_var.set("未框选")

    def clear_rect(self):
        s = self.shot
        if s:
            s["rect"] = None
            s["marks"] = []
            self._update_rect_labels()
            self._redraw_overlays()

    # ---- 截图文件：加载 / 保存 / 裁剪 --------------------------------------------

    def load_image(self):
        path = filedialog.askopenfilename(
            title="加载图片为新截图", parent=self.root,
            filetypes=[("图片", "*.png;*.jpg;*.jpeg;*.bmp"), ("所有文件", "*.*")])
        if not path:
            return
        try:
            img = Image.open(path)
            img.load()
            if img.mode != "RGB":
                img = img.convert("RGB")
        except Exception as e:
            messagebox.showerror("加载失败", str(e), parent=self.root)
            return
        self._append_shot(img)
        self.set_status("已加载 %s（%dx%d）" % (os.path.basename(path), img.width, img.height))

    def save_image(self):
        s = self.shot
        if s is None:
            return
        path = filedialog.asksaveasfilename(
            title="保存当前截图", defaultextension=".png",
            initialdir=CONFIG.get("tpl_dir", TOOL_DIR),
            initialfile="%s.png" % s["name"], filetypes=[("PNG 图片", "*.png")], parent=self.root)
        if not path:
            return
        CONFIG["tpl_dir"] = os.path.dirname(path)
        self.save_config()
        s["img"].save(path, format="PNG")
        self.set_status("截图已保存：%s" % path)

    def crop_to_new_shot(self):
        s = self.shot
        if s is None or not s["rect"]:
            messagebox.showwarning("提示", "请先用【范围】工具框选区域", parent=self.root)
            return
        x, y, w, h = s["rect"]
        crop = s["img"].crop((x, y, x + w, y + h))
        self._append_shot(crop)
        self.set_status("已裁剪为新截图（%dx%d）" % (w, h))

    # ---- 取色点表格 ---------------------------------------------------------------

    def refresh_points_tree(self):
        self.pt_tree.delete(*self.pt_tree.get_children())
        pts = self.points
        ax, ay = (pts[0]["x"], pts[0]["y"]) if pts else (0, 0)
        for i, p in enumerate(pts):
            chk = "☑" if p.get("checked", True) else "☐"
            off = "—" if i == 0 else "+%d,%+d" % (p["x"] - ax, p["y"] - ay)
            inv = 0xFFFFFF - (p["rgb"] & 0xFFFFFF)
            self.pt_tree.insert("", "end", values=(
                chk, "%d,%d" % (p["x"], p["y"]),
                "#%06X" % p["rgb"],
                "%d,%d,%d" % ((p["rgb"] >> 16) & 255, (p["rgb"] >> 8) & 255, p["rgb"] & 255),
                off, "#%06X" % inv))

    def _on_pt_tree_click(self, event):
        # 注意：identify_region/identify_column/identify_row 分别要 (x,y)/(x)/(y)，不能整包传 event
        if self.pt_tree.identify_region(event.x, event.y) != "cell":
            return
        col = self.pt_tree.identify_column(event.x)
        row_id = self.pt_tree.identify_row(event.y)
        if not row_id or col != "#1":
            return
        idx = self.pt_tree.index(row_id)
        if idx >= len(self.points):
            return
        p = self.points[idx]
        p["checked"] = not p.get("checked", True)
        self.refresh_points_tree()
        self._redraw_overlays()
        self._sync_color_param_entries()

    def _on_pt_tree_edit(self, event):
        """双击坐标/颜色/RGB/反色单元格 → 就地 Entry 编辑。"""
        if self.pt_tree.identify_region(event.x, event.y) != "cell":
            return
        col = self.pt_tree.identify_column(event.x)
        row_id = self.pt_tree.identify_row(event.y)
        if not row_id or col not in ("#2", "#3", "#4", "#6"):
            return
        idx = self.pt_tree.index(row_id)
        if idx >= len(self.points):
            return
        col_key = {"#2": "pos", "#3": "color", "#4": "rgb", "#6": "inv"}[col]
        bbox = self.pt_tree.bbox(row_id, col)
        if not bbox:
            return
        x, y, w, h = bbox
        cur = self.pt_tree.set(row_id, col)
        ed = tk.Entry(self.pt_tree, justify="center", font=("Consolas", 10))
        ed.place(x=x, y=y, width=w, height=h)
        ed.insert(0, cur)
        ed.focus_set()
        ed.select_range(0, "end")

        def commit(_e=None):
            try:
                self._apply_pt_edit(idx, col_key, ed.get().strip())
            except Exception as e:
                messagebox.showwarning("格式错误", "%s（保持原值）" % e, parent=self.root)
            finally:
                ed.destroy()

        ed.bind("<Return>", commit)
        ed.bind("<FocusOut>", commit)
        ed.bind("<Escape>", lambda _e: ed.destroy())

    def _apply_pt_edit(self, idx, col_key, text):
        """提交单元格编辑：pos="x,y" / color="0xRRGGBB" / rgb="r,g,b" / inv="0xRRGGBB"。"""
        p = self.points[idx]

        def parse_color(v):
            v = v.strip().lstrip("#").removeprefix("0x").removeprefix("0X")
            n = int(v, 16)
            if not (0 <= n <= 0xFFFFFF):
                raise ValueError("颜色超出 0xFFFFFF")
            return n

        if col_key == "pos":
            parts = text.replace("，", ",").split(",")
            if len(parts) != 2:
                raise ValueError('坐标格式 "x,y"')
            x, y = int(parts[0]), int(parts[1])
            s = self.shot
            if s:
                x = max(0, min(x, s["img"].width - 1))
                y = max(0, min(y, s["img"].height - 1))
            p["x"], p["y"] = x, y
        elif col_key == "color":
            p["rgb"] = parse_color(text)
        elif col_key == "rgb":
            parts = text.replace("，", ",").split(",")
            if len(parts) != 3:
                raise ValueError('RGB 格式 "r,g,b"')
            r, g, b = (int(v) for v in parts)
            if not all(0 <= v <= 255 for v in (r, g, b)):
                raise ValueError("RGB 每通道 0-255")
            p["rgb"] = (r << 16) | (g << 8) | b
        elif col_key == "inv":
            p["rgb"] = 0xFFFFFF - parse_color(text)
        self.refresh_points_tree()
        self._redraw_overlays()
        self._sync_color_param_entries()

    def _on_pt_tree_menu(self, event):
        row_id = self.pt_tree.identify_row(event.y)
        if row_id:
            if row_id not in self.pt_tree.selection():
                self.pt_tree.selection_set(row_id)
            self.pt_menu.tk_popup(event.x_root, event.y_root)

    def _delete_selected_rows(self):
        sel = [self.pt_tree.index(i) for i in self.pt_tree.selection()]
        if not sel:
            return
        for idx in sorted(sel, reverse=True):
            if idx < len(self.points):
                del self.points[idx]
        self.refresh_points_tree()
        self._redraw_overlays()
        self._sync_color_param_entries()

    def _invert_checked(self):
        for p in self.points:
            p["checked"] = not p.get("checked", True)
        self.refresh_points_tree()
        self._redraw_overlays()
        self._sync_color_param_entries()

    def _on_delete_key(self, _event):
        focused = self.root.focus_get()
        if isinstance(focused, ttk.Treeview) and focused is self.pt_tree:
            self._delete_selected_rows()

    def clear_points(self):
        if self.points:
            self.points = []
            self.refresh_points_tree()
            self._redraw_overlays()
            self._sync_color_param_entries()
            self.set_status("已清除全部取色点")

    def recheck_colors(self):
        """按当前截图重读各点颜色（换屏后校准色值用）。"""
        for p in self.points:
            p["rgb"] = self._pixel_at(p["x"], p["y"])
        self.refresh_points_tree()
        self._redraw_overlays()
        self._sync_color_param_entries()
        self.set_status("已按当前截图刷新 %d 个点的颜色" % len(self.points))


    # ---- 后台执行护栏 -------------------------------------------------------------

    def _set_busy(self, busy, status=None):
        self._busy = busy
        if status:
            self.set_status(status)
        self.btn_shot.config(state="disabled" if busy else "normal")

    def _run_bg(self, fn, name):
        """统一后台执行：busy 护栏防并发（waiters 按 type 键控，并发会串包），异常回弹窗。"""
        if self._busy:
            self.set_status("上一个操作还在执行中…")
            return
        self._set_busy(True)
        def worker():
            try:
                fn()
            except Exception as e:
                self.alert("error", name + "失败", str(e))
            finally:
                self._set_busy(False)
                self.post(self.refresh_state)
        threading.Thread(target=worker, daemon=True).start()

    def _check_ready(self, need_img=True):
        if self.client is None or not self.client.connected:
            messagebox.showwarning("提示", "请先连接设备", parent=self.root)
            return False
        if need_img and self.shot is None:
            messagebox.showwarning("提示", "请先【截图】", parent=self.root)
            return False
        return True

    def _rect_data(self):
        s = self.shot
        if s and s["rect"]:
            x, y, w, h = s["rect"]
            return {"x": x, "y": y, "w": w, "h": h}
        return None

    def _checked_points(self):
        return [p for p in self.points if p.get("checked", True)]

    def _out(self, text):
        self.out_text.delete("1.0", "end")
        self.out_text.insert("1.0", text)

    # ---- 实机验证（引擎在验证时刻重新采帧，与截图时刻无关） -------------------------

    def verify_single(self):
        if not self._check_ready():
            return
        pts = self._checked_points()
        if not pts:
            messagebox.showwarning("提示", "请先取色并勾选（至少 1 个点）", parent=self.root)
            return
        sim = round(float(self.sim_var.get()), 2)
        payload = {"points": [{"x": p["x"], "y": p["y"], "color": color_hex(p["rgb"])} for p in pts],
                   "similarity": sim}
        rd = self._rect_data()
        if rd:
            payload["rect"] = rd
        def fn():
            r = self.client.request("cmp_color", payload, "result", timeout=15)
            match = bool(r.get("match"))
            def apply():
                s = self.shot
                if s:
                    s["marks"] = [{"kind": "cross", "x": p["x"], "y": p["y"],
                                   "color": "#3fb96f" if match else "#e0564f"} for p in pts]
                    self._redraw_overlays()
                msg = "比色：%s（%d 点，sim=%.2f）" % ("全部命中" if match else "未全中", len(pts), sim)
                self.set_status(msg)
                self._out(msg + "\n" + json.dumps(r, ensure_ascii=False))
            self.post(apply)
        self._run_bg(fn, "比色测试")

    def verify_multi(self):
        if not self._check_ready():
            return
        pts = self._checked_points()
        if not pts:
            messagebox.showwarning("提示", "请先取色并勾选（至少 1 个点）", parent=self.root)
            return
        sim = round(float(self.sim_var.get()), 2)
        payload = {"points": [{"x": p["x"], "y": p["y"], "color": color_hex(p["rgb"])} for p in pts],
                   "similarity": sim}
        rd = self._rect_data()
        if rd:
            payload["rect"] = rd
        def fn():
            r = self.client.request("find_multi_color", payload, "result", timeout=20)
            found = bool(r.get("found"))
            rpts = r.get("points") or []
            def apply():
                s = self.shot
                if s:
                    s["marks"] = []
                    if found and rpts:
                        for i, p in enumerate(rpts):
                            s["marks"].append({"kind": "cross", "x": p["x"], "y": p["y"],
                                               "color": "#3fb96f" if i == 0 else "#d9a13c"})
                        self._redraw_overlays()
                msg = "多点找色：%s（sim=%.2f）" % (
                    ("命中 锚点(%d,%d)" % (rpts[0]["x"], rpts[0]["y"])) if found and rpts else "未命中", sim)
                self.set_status(msg)
                self._out(msg + ("\n" + json.dumps(r, ensure_ascii=False) if found else ""))
            self.post(apply)
        self._run_bg(fn, "多点测试")

    def verify_image(self):
        if not self._check_ready():
            return
        s = self.shot
        if not s["rect"]:
            messagebox.showwarning("提示", "请先用【范围】框选模板区域", parent=self.root)
            return
        x, y, w, h = s["rect"]
        buf = io.BytesIO()
        s["img"].crop((x, y, x + w, y + h)).save(buf, format="PNG")
        self.tpl_b64 = base64.b64encode(buf.getvalue()).decode("ascii")
        self.tpl_size = (w, h)
        threshold = round(float(self.sim_var.get()), 2)
        payload = {"templateBase64": self.tpl_b64, "threshold": threshold}  # 不带 rect：全屏找
        def fn():
            r = self.client.request("find_image", payload, "result", timeout=30)
            found = bool(r.get("found"))
            def apply():
                s = self.shot
                if s:
                    s["marks"] = []
                    if found:
                        fx, fy = int(r.get("x", 0)), int(r.get("y", 0))
                        s["marks"].append({"kind": "rect", "x": fx, "y": fy, "w": w, "h": h,
                                           "color": "#3fb96f",
                                           "label": "sim %.2f" % float(r.get("similarity", 0))})
                        self._redraw_overlays()
                        msg = "找图：命中 (%d,%d) 相似度 %.2f" % (fx, fy, float(r.get("similarity", 0)))
                    else:
                        msg = "找图：未命中（threshold=%.2f，可尝试调低精度）" % threshold
                    self.set_status(msg)
                    self._out(msg + "\n" + json.dumps(r, ensure_ascii=False))
            self.post(apply)
        self._run_bg(fn, "图片查找")

    def run_ocr(self):
        if not self._check_ready():
            return
        vis = (self.caps.get("vision") or {}).get("ocr")
        if not vis:
            messagebox.showwarning("提示", "OCR 不可用：设备未安装 provides 含 ocr 的插件（ncnn-ocr）",
                                   parent=self.root)
            return
        payload = {}
        rd = self._rect_data()
        if rd:
            payload["rect"] = rd
        def fn():
            r = self.client.request("ocr", payload, "result", timeout=60)
            self.ocr_result = r
            blocks = r.get("results") or []
            def apply():
                s = self.shot
                if s:
                    s["marks"] = [{"kind": "rect", "x": b["x"], "y": b["y"], "w": b["w"], "h": b["h"],
                                   "color": "#4f9cf9", "label": (b.get("text") or "")[:10]}
                                  for b in blocks if b.get("w") and b.get("h")]
                    self._redraw_overlays()
                lines = ["OCR 共 %d 块：" % len(blocks)]
                for i, b in enumerate(blocks, 1):
                    lines.append("%d. %r @(%d,%d,%d,%d) conf=%.2f" %
                                 (i, b.get("text", ""), b.get("x", 0), b.get("y", 0),
                                  b.get("w", 0), b.get("h", 0), b.get("confidence", 0)))
                lines.append("全文：%s" % (r.get("text") or ""))
                self._out("\n".join(lines))
                self.set_status("OCR 完成：%d 块（详情见【代码生成】结果区）" % len(blocks))
            self.post(apply)
        self._run_bg(fn, "OCR")

    # ---- 模板 PNG ---------------------------------------------------------------

    def save_template(self):
        s = self.shot
        if s is None or not s["rect"]:
            messagebox.showwarning("提示", "请先截图并框选模板区域", parent=self.root)
            return
        x, y, w, h = s["rect"]
        path = filedialog.asksaveasfilename(
            title="保存模板 PNG", defaultextension=".png",
            initialdir=CONFIG.get("tpl_dir", TOOL_DIR),
            initialfile="tpl_%d_%d_%dx%d.png" % (x, y, w, h),
            filetypes=[("PNG 图片", "*.png")], parent=self.root)
        if not path:
            return
        CONFIG["tpl_dir"] = os.path.dirname(path)
        self.save_config()
        crop = s["img"].crop((x, y, x + w, y + h))
        crop.save(path, format="PNG")
        self.tpl_path = path
        self.tpl_size = (w, h)
        self.set_status("模板已保存：%s（%dx%d）" % (path, w, h))

    # ---- 颜色/参数联动与代码生成 -------------------------------------------------

    def _anchor_offset(self):
        """勾选点 → (锚点色hex, offsetStr)；无勾选点返回 ("", "")。"""
        pts = self._checked_points()
        if not pts:
            return "", ""
        p0 = pts[0]
        offs = ["%d|%d|%s" % (p["x"] - p0["x"], p["y"] - p0["y"], color_hex(p["rgb"]))
                for p in pts[1:]]
        if not offs:
            offs = ["0|0|%s" % color_hex(p0["rgb"])]  # 单点：锚点自校验占位
        return color_hex(p0["rgb"]), ",".join(offs)

    def _sync_color_param_entries(self):
        c, o = self._anchor_offset()
        self.color_entry_var.set(c)
        self.param_entry_var.set(o)

    def _sync_param_entry(self):
        self._sync_color_param_entries()
        self.set_status("参数已按当前勾选点重新生成")

    def _copy_color_entry(self):
        self.root.clipboard_clear()
        self.root.clipboard_append(self.color_entry_var.get())

    def _copy_param_entry(self):
        self.root.clipboard_clear()
        self.root.clipboard_append(self.param_entry_var.get())

    def _tpl_key(self):
        label = self.func_var.get()
        for k, v in FUNCS.items():
            if v == label:
                return k
        return "findMultiColor"

    def _load_templates_to_ui(self):
        key = self._tpl_key()
        self.tpl_text.delete("1.0", "end")
        self.tpl_text.insert("1.0", TEMPLATES.get(key, ""))

    def reset_template(self):
        key = self._tpl_key()
        self.tpl_text.delete("1.0", "end")
        self.tpl_text.insert("1.0", DEFAULT_TEMPLATES.get(key, ""))
        TEMPLATES[key] = DEFAULT_TEMPLATES.get(key, "")
        save_json(TPL_PATH, TEMPLATES)

    def gen_code(self):
        key = self._tpl_key()
        tpl = self.tpl_text.get("1.0", "end").rstrip("\n")
        TEMPLATES[key] = tpl
        save_json(TPL_PATH, TEMPLATES)
        s = self.shot
        if s is None:
            messagebox.showwarning("提示", "请先【截图】", parent=self.root)
            return
        pts = self._checked_points()
        if key in ("findMultiColor", "isFeature", "cmpColor", "findColor") and not pts:
            messagebox.showwarning("提示", "请先取色并勾选点位", parent=self.root)
            return
        if key == "findImage" and not self.tpl_path:
            messagebox.showwarning("提示", "请先【存模板 PNG】（找图代码需图片路径）", parent=self.root)
            return
        sim = ("%.2f" % float(self.sim_var.get())).rstrip("0").rstrip(".") or "0"
        order = ORDERS.get(self.order_var.get(), "")
        ph = {
            "order": (", FIND_ORDER.%s" % order) if order else "",
            "sim": sim,
            "img": self.tpl_path.replace("\\", "/"),
            "rect_json": json.dumps(self._rect_data() or {}, ensure_ascii=False),
            "ocr_text": (self.ocr_result or {}).get("text", ""),
            "ocr_json": json.dumps((self.ocr_result or {}).get("results", []), ensure_ascii=False),
        }
        if s["rect"]:
            x, y, w, h = s["rect"]
            ph.update({"x1": str(x), "y1": str(y), "x2": str(x + w - 1), "y2": str(y + h - 1)})
        else:
            ph.update({"x1": "0", "y1": "0", "x2": str(s["img"].width - 1),
                       "y2": str(s["img"].height - 1)})
        anchor_hex, offset_str = self._anchor_offset()
        if not self.color_entry_var.get():
            self.color_entry_var.set(anchor_hex)
        if not self.param_entry_var.get():
            self.param_entry_var.set(offset_str)
        ph["firstColor"] = self.color_entry_var.get() or anchor_hex
        ph["color"] = self.color_entry_var.get() or anchor_hex
        ph["offsetStr"] = self.param_entry_var.get() or offset_str
        if pts:
            ph["x"] = str(pts[0]["x"])  # isFeature / 单点比色的基准点（首个勾选点）
            ph["y"] = str(pts[0]["y"])

        def render(over):
            code = tpl
            merged = dict(ph)
            merged.update(over or {})
            for k, v in merged.items():
                code = code.replace("{%s}" % k, v)
            return code

        if key == "cmpColor" and len(pts) > 1:
            # 多点逐行生成（懒人语义：每点一行 cmpColor）
            code = "\n".join(render({"x": str(p["x"]), "y": str(p["y"]),
                                     "color": color_hex(p["rgb"])}) for p in pts)
        else:
            code = render(None)
        self._out(code)
        self.set_status("代码已生成（函数：%s，精度 %s，方向：%s）" %
                        (FUNCS.get(key, key), sim, self.order_var.get()))

    def copy_code(self):
        text = self.out_text.get("1.0", "end").rstrip("\n")
        if not text:
            return
        self.root.clipboard_clear()
        self.root.clipboard_append(text)
        self.set_status("已复制到剪贴板")

    # ---- 退出 -------------------------------------------------------------------

    def on_close(self):
        try:
            self.save_config()
            save_json(TPL_PATH, TEMPLATES)
        except Exception:
            pass
        self._hide_magnifier()
        if self.client is not None:
            self.client.close()
        self.root.destroy()


def main():
    if os.name == "nt":
        try:
            import ctypes
            ctypes.windll.shcore.SetProcessDpiAwareness(1)  # 高 DPI 清晰渲染
        except Exception:
            pass
    root = tk.Tk()
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
