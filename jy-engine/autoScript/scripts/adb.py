"""adb.py — 设备连接与操作（JY-Frame 脚本生成套装）

封装 ADB 命令，提供设备查询、分辨率、截图、点击、滑动、输入、按键、
画面稳定等待与环境自检（doctor）。

设计约定（docs/03 §2 / docs/06 §5-§6）：
- 单文件自包含；核心函数可独立 import（不依赖 argparse）
- adb 调用一律 subprocess 列表形式；截图用 exec-out 拿字节流直接写文件
  （严禁 shell > 重定向，Windows 下 CRLF 转换会损坏 PNG）
- stdout 只输出 JSON（ensure_ascii=False），日志一律走 stderr
- 退出码：0 成功 / 1 通用错误 / 2 参数错误 / 3 设备错误

CLI 用法::

    python scripts/adb.py doctor
    python scripts/adb.py devices
    python scripts/adb.py screenshot --device <id> --output <path>
    python scripts/adb.py click --device <id> --x <x> --y <y>
    python scripts/adb.py swipe --device <id> --x1 <x1> --y1 <y1> --x2 <x2> --y2 <y2> --duration <ms>
    python scripts/adb.py longpress --device <id> --x <x> --y <y> --duration <ms>
    python scripts/adb.py input --device <id> --text "<text>"
    python scripts/adb.py keyevent --device <id> --key <back|home>
    python scripts/adb.py resolution [--device <id>]
    python scripts/adb.py foreground [--device <id>]            # 查当前前台应用
    python scripts/adb.py launch --package <pkg> [--device <id>]   # 拉起应用
    python scripts/adb.py ensure --package <pkg> [--device <id>] [--wait-ms 15000]  # 不在前台则拉起并等稳定

--device 可省略：只有一台在线设备时自动选择；多台时抛 DEVICE_NOT_FOUND 并列出设备。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time


# === 配置 ===

SCRIPT_NAME = "adb.py"
PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

ADB_TIMEOUT = 10          # 常规 adb 命令超时（秒）
SCREENSHOT_TIMEOUT = 15   # 截图超时（秒）

PNG_MAGIC = b"\x89PNG"    # PNG 文件魔数

HASH_SIZE = 16              # 平均哈希缩放边长（16x16 = 256bit）
STABLE_DIST_THRESHOLD = 3   # 画面稳定判定：汉明距离 < 3
STABLE_CONSECUTIVE = 2      # 连续达标次数

KEYEVENT_MAP = {"back": 4, "home": 3}

# adb devices -l 的合法状态列，用于过滤表头/守护进程提示行
DEVICE_STATES = {"device", "offline", "unauthorized", "recovery",
                 "bootloader", "sideload", "no-device"}


# === 错误码 ===

E_ADB_NOT_FOUND = "ADB_NOT_FOUND"
E_DEVICE_NOT_FOUND = "DEVICE_NOT_FOUND"
E_SCREENSHOT_FAILED = "SCREENSHOT_FAILED"
E_INPUT_UNSUPPORTED = "INPUT_UNSUPPORTED"
E_ADB_TIMEOUT = "ADB_TIMEOUT"
E_ADB_ERROR = "ADB_ERROR"
E_INVALID_ARGUMENT = "INVALID_ARGUMENT"
E_APP_NOT_INSTALLED = "APP_NOT_INSTALLED"

# 退出码
EXIT_OK = 0
EXIT_ERROR = 1
EXIT_ARGS = 2
EXIT_DEVICE = 3

# 错误码 → 退出码（未列出的走通用错误 1）
_ERROR_EXIT = {
    E_DEVICE_NOT_FOUND: EXIT_DEVICE,
    E_INVALID_ARGUMENT: EXIT_ARGS,
}


class AdbError(Exception):
    """带错误码的 ADB 操作异常（.code / .message 供 CLI 输出 JSON）。"""

    def __init__(self, code: str, message: str):
        super().__init__(f"[{code}] {message}")
        self.code = code
        self.message = message


# === 核心函数（可独立 import，不依赖 argparse）===

def get_devices() -> list[dict]:
    """解析 adb devices -l，返回 [{"serial", "state", "model"}, ...]。"""
    result = _run_adb(["devices", "-l"])
    devices = []
    for line in result.stdout.splitlines():
        parts = line.strip().split()
        if len(parts) < 2 or parts[1] not in DEVICE_STATES:
            continue  # 跳过表头/守护进程提示行
        model = ""
        for extra in parts[2:]:
            if extra.startswith("model:"):
                model = extra.split(":", 1)[1]
                break
        devices.append({"serial": parts[0], "state": parts[1], "model": model})
    return devices


def get_resolution(device_id: str | None) -> tuple[int, int]:
    """获取设备分辨率 (w, h)：优先 Override size，否则 Physical size。"""
    device = _resolve_device(device_id)
    result = _run_adb(["shell", "wm", "size"], device=device)
    return _parse_wm_size(result.stdout)


def get_density(device_id: str | None) -> int:
    """获取设备屏幕密度（dpi）：优先 Override density，否则 Physical density。"""
    device = _resolve_device(device_id)
    result = _run_adb(["shell", "wm", "density"], device=device)
    return _parse_wm_density(result.stdout)


def screenshot(device_id: str | None, save_path: str) -> str:
    """截图并保存为 PNG，返回保存路径（绝对路径）。

    adb -s <serial> exec-out screencap -p 用 capture_output 拿原始字节
    直接写文件（严禁 shell 重定向），并校验 PNG 魔数。
    """
    device = _resolve_device(device_id)
    data = _screenshot_bytes(device)
    save_path = os.path.abspath(save_path)
    parent = os.path.dirname(save_path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(save_path, "wb") as f:
        f.write(data)
    return save_path


def click(device_id: str | None, x: int, y: int) -> bool:
    """点击屏幕坐标 (x, y)。"""
    device = _resolve_device(device_id)
    _run_adb(["shell", "input", "tap", x, y], device=device)
    return True


def swipe(device_id: str | None, x1: int, y1: int, x2: int, y2: int,
          duration_ms: int = 300) -> bool:
    """从 (x1, y1) 滑动到 (x2, y2)，duration_ms 为滑动时长。"""
    device = _resolve_device(device_id)
    _run_adb(["shell", "input", "swipe", x1, y1, x2, y2, duration_ms],
             device=device)
    return True


def long_press(device_id: str | None, x: int, y: int,
               duration_ms: int = 800) -> bool:
    """长按 (x, y)：input swipe 起止同点实现。"""
    device = _resolve_device(device_id)
    _run_adb(["shell", "input", "swipe", x, y, x, y, duration_ms],
             device=device)
    return True


def input_text(device_id: str | None, text: str) -> bool:
    """输入文本：空格转 %s；非 ASCII 字符 adb 原生不支持，抛 INPUT_UNSUPPORTED。"""
    device = _resolve_device(device_id)
    if not text:
        raise AdbError(E_INVALID_ARGUMENT, "text 不能为空")
    if any(ord(ch) > 127 for ch in text):
        raise AdbError(
            E_INPUT_UNSUPPORTED,
            f"adb input text 不支持非 ASCII 字符: {text!r}（中文需走剪贴板/IME 方案）")
    _run_adb(["shell", "input", "text", text.replace(" ", "%s")], device=device)
    return True


def keyevent(device_id: str | None, key) -> bool:
    """发送按键：back=4, home=3；也接受 Android 键值数字（int 或数字字符串）。"""
    device = _resolve_device(device_id)
    if isinstance(key, str):
        code = KEYEVENT_MAP.get(key.strip().lower())
        if code is None:
            try:
                code = int(key)
            except ValueError:
                raise AdbError(E_INVALID_ARGUMENT,
                               f"未知按键: {key!r}（支持 back/home 或数字键值）")
    else:
        code = int(key)
    _run_adb(["shell", "input", "keyevent", code], device=device)
    return True


def wait_stable(device_id: str | None, timeout_ms: int = 3000,
                interval_ms: int = 500) -> bool:
    """等待画面稳定：循环截图比较 16x16 平均哈希，汉明距离 < 3 连续 2 次即稳定。

    超时仍未稳定返回 False（不抛异常）。
    """
    device = _resolve_device(device_id)
    deadline = time.monotonic() + timeout_ms / 1000.0
    prev_hash = None
    stable_run = 0
    while True:
        cur_hash = _avg_hash(_screenshot_bytes(device))
        if prev_hash is not None:
            if _hamming_distance(prev_hash, cur_hash) < STABLE_DIST_THRESHOLD:
                stable_run += 1
                if stable_run >= STABLE_CONSECUTIVE:
                    return True
            else:
                stable_run = 0
        prev_hash = cur_hash
        if time.monotonic() >= deadline:
            return False
        time.sleep(interval_ms / 1000.0)


# === 前台应用守护（录制/执行过程中确保游戏在前台）===

# dumpsys activity activities 中前台 Activity 行的候选字段（安卓版本差异）
_RESUMED_LINE = re.compile(
    r"(?:topResumedActivity|mResumedActivity|resumedActivity)[=:]\s*ActivityRecord\{[^}]*\}")


def get_foreground_app(device_id: str | None) -> dict:
    """查询当前前台应用，返回 {"package", "activity", "raw"}。

    依次尝试 dumpsys activity activities 的 topResumedActivity /
    mResumedActivity / resumedActivity 行；全部解析失败时抛 ADB_ERROR。
    """
    device = _resolve_device(device_id)
    result = _run_adb(["shell", "dumpsys", "activity", "activities"],
                      device=device, timeout=ADB_TIMEOUT + 5)
    for line in result.stdout.splitlines():
        record = _RESUMED_LINE.search(line)
        if not record:
            continue
        # ActivityRecord{hash u0 com.pkg/.MainActivity tNN} → 取 u0 后的组件名
        m = re.search(r"\bu0\s+(\S+)", record.group(0))
        if not m:
            continue
        comp = m.group(1)  # 形如 com.pkg/.MainActivity 或 com.pkg/com.Activity
        if "/" in comp:
            pkg, act = comp.split("/", 1)
            if act.startswith("."):
                act = pkg + act
        else:
            pkg, act = comp, ""
        return {"package": pkg, "activity": act, "raw": line.strip()}
    raise AdbError(E_ADB_ERROR, "无法从 dumpsys 解析前台应用（无 resumedActivity 行）")


def is_package_installed(device_id: str | None, package: str) -> bool:
    """检查包名是否已安装（pm path 有输出即已安装）。

    未安装时部分系统 pm path 以非零码退出，同样视为未安装。
    """
    device = _resolve_device(device_id)
    try:
        result = _run_adb(["shell", "pm", "path", str(package)], device=device)
    except AdbError:
        return False
    return bool(result.stdout.strip())


def launch_app(device_id: str | None, package: str) -> bool:
    """拉起应用（monkey -p <pkg> -c android.intent.category.LAUNCHER 1）。

    相比 am start 无需知道具体 Activity 名，对游戏通用。
    """
    device = _resolve_device(device_id)
    if not is_package_installed(device, package):
        raise AdbError(E_APP_NOT_INSTALLED,
                       f"应用未安装: {package}（请检查包名）")
    _run_adb(["shell", "monkey", "-p", str(package),
              "-c", "android.intent.category.LAUNCHER", "1"],
             device=device, timeout=ADB_TIMEOUT + 10)
    return True


def ensure_foreground(device_id: str | None, package: str,
                      wait_ms: int = 15000) -> dict:
    """确保游戏在前台：不在则拉起并等待画面稳定（游戏冷启动较慢）。

    返回 {"foreground": 当前是否已在前台, "relaunched": 是否执行了拉起,
          "package": 前台包名（可解析时）, "stable": 拉起后画面是否稳定}。
    """
    device = _resolve_device(device_id)
    cur = {}
    try:
        cur = get_foreground_app(device)
    except AdbError:
        pass  # 桌面/锁屏/解析失败均按"不在前台"处理
    if cur.get("package") == package:
        return {"foreground": True, "relaunched": False,
                "package": package, "stable": True}
    launch_app(device, package)
    stable = wait_stable(device, timeout_ms=wait_ms, interval_ms=1000)
    return {"foreground": False, "relaunched": True,
            "package": cur.get("package", ""), "stable": bool(stable)}


def doctor() -> dict:
    """环境自检（docs/06 §6 检查清单）。

    返回 {status, checks, summary}：
    - checks 每项 {name, status: ok|fail|warning, detail}
    - summary {passed, failed, warnings}
    - failed == 0 时顶层 status = "ok"，否则 "error"
    """
    checks = []
    checks.append(_check_python_version())
    checks.append(_check_dependencies())
    checks.append(_check_adb())

    # 设备 + 设备分辨率（第一台在线设备）
    online = []
    try:
        all_devices = get_devices()
        online = [d for d in all_devices if d["state"] == "device"]
        if online:
            serials = ", ".join(d["serial"] for d in online)
            checks.append({"name": "devices", "status": "ok",
                           "detail": f"{len(online)} device(s): {serials}"})
        else:
            checks.append({"name": "devices", "status": "fail",
                           "detail": "无在线设备（state=device 的项为 0）"})
    except AdbError as exc:
        checks.append({"name": "devices", "status": "fail", "detail": exc.message})
    checks.append(_check_resolution(online))

    checks.append(_check_llm_config())
    checks.append(_check_config_files())
    checks.append(_check_dirs())
    checks.append(_check_jy_docs())

    summary = {
        "passed": sum(1 for c in checks if c["status"] == "ok"),
        "failed": sum(1 for c in checks if c["status"] == "fail"),
        "warnings": sum(1 for c in checks if c["status"] == "warning"),
    }
    return {"status": "ok" if summary["failed"] == 0 else "error",
            "checks": checks, "summary": summary}


# === 内部工具 ===

def _log(message: str) -> None:
    """日志一律走 stderr（stdout 只保留 JSON 输出）。"""
    print(f"[{SCRIPT_NAME}] {message}", file=sys.stderr, flush=True)


def _reconfigure_streams() -> None:
    """Windows GBK 控制台下强制 stdout/stderr 为 UTF-8，避免中文/JSON 乱码。"""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except (AttributeError, ValueError, OSError):
            pass


def _run_adb(args: list, device: str | None = None,
             timeout: int = ADB_TIMEOUT, binary: bool = False):
    """执行 adb 命令（subprocess 列表形式，不经 shell）。

    - binary=False：text 模式（utf-8 解码，errors=replace）
    - binary=True：返回原始字节（截图用，杜绝任何换行转换）
    adb 不存在 / 超时 / 非 0 返回码均抛 AdbError。
    """
    cmd = ["adb"]
    if device:
        cmd += ["-s", str(device)]
    cmd += [str(a) for a in args]
    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=not binary,
            encoding=None if binary else "utf-8",
            errors=None if binary else "replace",
            timeout=timeout,
        )
    except FileNotFoundError:
        raise AdbError(E_ADB_NOT_FOUND,
                       "adb 可执行文件不存在（请确认已安装并加入 PATH）")
    except subprocess.TimeoutExpired:
        raise AdbError(E_ADB_TIMEOUT, f"adb 命令超时（>{timeout}s）: {' '.join(cmd)}")
    if result.returncode != 0:
        err = result.stderr
        if isinstance(err, bytes):
            err = err.decode("utf-8", "replace")
        err = (err or "").strip()
        raise AdbError(E_ADB_ERROR,
                       f"adb 命令失败(rc={result.returncode}): {err or ' '.join(cmd)}")
    return result


def _resolve_device(device_id: str | None) -> str:
    """解析目标设备序列号并校验在线状态。

    - 显式指定：不在在线列表则抛 DEVICE_NOT_FOUND
    - 省略：仅一台在线设备时自动选择；多台抛 DEVICE_NOT_FOUND 并列出
    """
    devices = [d for d in get_devices() if d["state"] == "device"]
    if device_id:
        for d in devices:
            if d["serial"] == device_id:
                return device_id
        raise AdbError(E_DEVICE_NOT_FOUND, f"设备 {device_id} 未连接或未在线")
    if not devices:
        raise AdbError(E_DEVICE_NOT_FOUND,
                       "没有在线设备（adb devices 无 device 状态项）")
    if len(devices) == 1:
        return devices[0]["serial"]
    serials = ", ".join(d["serial"] for d in devices)
    raise AdbError(E_DEVICE_NOT_FOUND,
                   f"检测到多台在线设备，请用 --device 指定: {serials}")


def _screenshot_bytes(device: str) -> bytes:
    """exec-out screencap 拿原始 PNG 字节并校验魔数。"""
    try:
        result = _run_adb(["exec-out", "screencap", "-p"], device=device,
                          timeout=SCREENSHOT_TIMEOUT, binary=True)
    except AdbError as exc:
        if exc.code == E_ADB_NOT_FOUND:
            raise
        raise AdbError(E_SCREENSHOT_FAILED, f"截图失败 ({device}): {exc.message}")
    data = result.stdout
    if not data.startswith(PNG_MAGIC):
        raise AdbError(E_SCREENSHOT_FAILED,
                       f"截图数据不是有效 PNG（{len(data)} bytes）: {device}")
    return data


def _avg_hash(png_bytes: bytes) -> tuple:
    """16x16 灰度平均哈希（256bit 元组），用于画面稳定性比较。"""
    import io
    from PIL import Image
    img = (Image.open(io.BytesIO(png_bytes))
           .convert("L")
           .resize((HASH_SIZE, HASH_SIZE), Image.LANCZOS))
    pixels = list(img.getdata())
    avg = sum(pixels) / len(pixels)
    return tuple(1 if p >= avg else 0 for p in pixels)


def _hamming_distance(hash_a: tuple, hash_b: tuple) -> int:
    """两个等长 0/1 元组的汉明距离（不等长视为最大差异）。"""
    if len(hash_a) != len(hash_b):
        return HASH_SIZE * HASH_SIZE
    return sum(1 for a, b in zip(hash_a, hash_b) if a != b)


def _parse_wm_size(text: str) -> tuple[int, int]:
    """解析 wm size 输出，优先 Override size。"""
    for pattern in (re.compile(r"Override size:\s*(\d+)x(\d+)"),
                    re.compile(r"Physical size:\s*(\d+)x(\d+)")):
        m = pattern.search(text)
        if m:
            return int(m.group(1)), int(m.group(2))
    raise AdbError(E_ADB_ERROR, f"无法从 wm size 输出解析分辨率: {text.strip()!r}")


def _parse_wm_density(text: str) -> int:
    """解析 wm density 输出，优先 Override density。"""
    for pattern in (re.compile(r"Override density:\s*(\d+)"),
                    re.compile(r"Physical density:\s*(\d+)")):
        m = pattern.search(text)
        if m:
            return int(m.group(1))
    raise AdbError(E_ADB_ERROR, f"无法从 wm density 输出解析密度: {text.strip()!r}")


# === doctor 检查项（docs/06 §6）===

def _import_ok(module: str) -> bool:
    try:
        __import__(module)
        return True
    except Exception:
        return False


def _check_python_version() -> dict:
    v = sys.version_info
    detail = f"{v.major}.{v.minor}.{v.micro}"
    ok = (v.major, v.minor) >= (3, 11)
    return {"name": "python_version", "status": "ok" if ok else "fail",
            "detail": detail + ("" if ok else "（要求 >= 3.11）")}


def _check_dependencies() -> dict:
    """核心依赖 pillow/numpy/cv2/yaml/jinja2 缺一即 fail；rapidocr 缺失算 warning。"""
    core = [("pillow", "PIL"), ("numpy", "numpy"), ("opencv", "cv2"),
            ("yaml", "yaml"), ("jinja2", "jinja2")]
    missing = [name for name, module in core if not _import_ok(module)]
    if missing:
        return {"name": "dependencies", "status": "fail",
                "detail": f"缺失核心依赖: {', '.join(missing)}"}
    if not (_import_ok("rapidocr_onnxruntime") or _import_ok("rapidocr")):
        return {"name": "dependencies", "status": "warning",
                "detail": f"{len(core)}/{len(core) + 1} 已安装，rapidocr 缺失（OCR 不可用，可选）"}
    return {"name": "dependencies", "status": "ok",
            "detail": f"all {len(core) + 1} packages installed"}


def _check_adb() -> dict:
    try:
        result = _run_adb(["version"])
        first_line = result.stdout.strip().splitlines()[0] if result.stdout.strip() else ""
        return {"name": "adb", "status": "ok",
                "detail": first_line or "adb 可用"}
    except AdbError as exc:
        return {"name": "adb", "status": "fail", "detail": exc.message}


def _check_resolution(online: list) -> dict:
    if not online:
        return {"name": "device_resolution", "status": "warning",
                "detail": "无在线设备，跳过分辨率检查"}
    serial = online[0]["serial"]
    try:
        w, h = get_resolution(serial)
        return {"name": "device_resolution", "status": "ok",
                "detail": f"{w}x{h} ({serial})"}
    except AdbError as exc:
        return {"name": "device_resolution", "status": "fail", "detail": exc.message}


def _check_llm_config() -> dict:
    """config/models.yaml 存在且 vision.api_key 展开 DOUBAO_API_KEY 后非空。"""
    path = os.path.join(PROJECT_ROOT, "config", "models.yaml")
    if not os.path.isfile(path):
        return {"name": "llm_config", "status": "fail",
                "detail": f"{path} 不存在"}
    try:
        import yaml
        with open(path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f) or {}
    except Exception as exc:
        return {"name": "llm_config", "status": "fail",
                "detail": f"models.yaml 解析失败: {exc}"}
    vision = data.get("vision") or {}
    raw_key = str(vision.get("api_key") or "")
    expanded = os.path.expandvars(raw_key)
    model = vision.get("model", "")
    if expanded and "${" not in expanded:
        return {"name": "llm_config", "status": "ok",
                "detail": f"api_key 已配置 (model: {model})"}
    return {"name": "llm_config", "status": "warning",
            "detail": f"api_key 未配置（{raw_key or '空'} 展开后为空，请设置 DOUBAO_API_KEY）"}


def _check_config_files() -> dict:
    problems = []
    for name in ("devices.yaml", "models.yaml"):
        path = os.path.join(PROJECT_ROOT, "config", name)
        if not os.path.isfile(path):
            problems.append(f"{name} 不存在")
            continue
        try:
            import yaml
            with open(path, "r", encoding="utf-8") as f:
                yaml.safe_load(f)
        except Exception as exc:
            problems.append(f"{name} 解析失败: {exc}")
    if problems:
        return {"name": "config_files", "status": "fail", "detail": "; ".join(problems)}
    return {"name": "config_files", "status": "ok",
            "detail": "devices.yaml / models.yaml 存在且格式有效"}


def _check_dirs() -> dict:
    """state/output/screenshots 目录可写（不存在则创建）。"""
    names = ("state", "output", "screenshots")
    problems = []
    for name in names:
        path = os.path.join(PROJECT_ROOT, name)
        try:
            os.makedirs(path, exist_ok=True)
            probe = os.path.join(path, "_doctor_write_test.tmp")
            with open(probe, "w", encoding="utf-8") as f:
                f.write("ok")
            os.remove(probe)
        except OSError as exc:
            problems.append(f"{name}/ 不可写: {exc}")
    if problems:
        return {"name": "directories", "status": "fail", "detail": "; ".join(problems)}
    return {"name": "directories", "status": "ok",
            "detail": f"{', '.join(names)}/ 均可写"}


def _check_jy_docs() -> dict:
    """knowledge/jy_frame_index.json 存在；doc_path 不存在算 warning。"""
    path = os.path.join(PROJECT_ROOT, "knowledge", "jy_frame_index.json")
    if not os.path.isfile(path):
        return {"name": "jy_frame_docs", "status": "fail",
                "detail": f"{path} 不存在"}
    try:
        with open(path, "r", encoding="utf-8") as f:
            index = json.load(f)
    except Exception as exc:
        return {"name": "jy_frame_docs", "status": "fail",
                "detail": f"索引解析失败: {exc}"}
    doc_path = str(index.get("doc_path") or "")
    if not doc_path:
        return {"name": "jy_frame_docs", "status": "warning",
                "detail": "索引缺少 doc_path，仅索引可用"}
    if not os.path.isabs(doc_path):
        doc_path = os.path.join(PROJECT_ROOT, doc_path)
    if not os.path.exists(doc_path):
        return {"name": "jy_frame_docs", "status": "warning",
                "detail": f"doc_path 不存在: {doc_path}（仅索引可用）"}
    return {"name": "jy_frame_docs", "status": "ok",
            "detail": f"索引有效，doc_path 存在 ({doc_path})"}


# === CLI 入口 ===

_DEVICE_HELP = "设备序列号（省略时：仅一台在线设备则自动选择，多台报错并列出设备）"


class _JsonArgumentParser(argparse.ArgumentParser):
    """参数错误时输出 JSON 并以退出码 2 结束（维持 stdout 纯 JSON 约定）。"""

    def error(self, message):
        print(json.dumps({"status": "error", "error": E_INVALID_ARGUMENT,
                          "message": message}, ensure_ascii=False))
        sys.exit(EXIT_ARGS)


def _build_parser() -> argparse.ArgumentParser:
    parser = _JsonArgumentParser(
        prog="scripts/adb.py",
        description="JY-Frame 设备操作模块：设备查询/截图/点击/滑动/输入/按键/环境自检",
        epilog="示例: python scripts/adb.py screenshot --output screenshots/shot.png",
    )
    sub = parser.add_subparsers(dest="command", metavar="<command>", required=True)

    sub.add_parser("doctor", help="环境自检（依赖/adb/设备/配置/目录/知识库）")

    sub.add_parser("devices", help="列出已连接设备")

    p = sub.add_parser("screenshot", help="截图并保存为 PNG")
    p.add_argument("--device", help=_DEVICE_HELP)
    p.add_argument("--output", required=True, help="PNG 保存路径（必填）")

    p = sub.add_parser("click", help="点击屏幕坐标")
    p.add_argument("--device", help=_DEVICE_HELP)
    p.add_argument("--x", required=True, type=int, help="横坐标")
    p.add_argument("--y", required=True, type=int, help="纵坐标")

    p = sub.add_parser("swipe", help="滑动")
    p.add_argument("--device", help=_DEVICE_HELP)
    p.add_argument("--x1", required=True, type=int, help="起点横坐标")
    p.add_argument("--y1", required=True, type=int, help="起点纵坐标")
    p.add_argument("--x2", required=True, type=int, help="终点横坐标")
    p.add_argument("--y2", required=True, type=int, help="终点纵坐标")
    p.add_argument("--duration", type=int, default=300, help="滑动时长 ms（默认 300）")

    p = sub.add_parser("longpress", help="长按（input swipe 同点实现）")
    p.add_argument("--device", help=_DEVICE_HELP)
    p.add_argument("--x", required=True, type=int, help="横坐标")
    p.add_argument("--y", required=True, type=int, help="纵坐标")
    p.add_argument("--duration", type=int, default=800, help="按压时长 ms（默认 800）")

    p = sub.add_parser("input", help="输入文本（仅支持 ASCII，空格转 %%s）")
    p.add_argument("--device", help=_DEVICE_HELP)
    p.add_argument("--text", required=True, help="要输入的文本")

    p = sub.add_parser("keyevent", help="发送按键（back/home）")
    p.add_argument("--device", help=_DEVICE_HELP)
    p.add_argument("--key", required=True, choices=sorted(KEYEVENT_MAP),
                   help="按键名：back / home")

    p = sub.add_parser("resolution", help="获取设备分辨率")
    p.add_argument("--device", help=_DEVICE_HELP)

    p = sub.add_parser("foreground", help="查询当前前台应用（包名/Activity）")
    p.add_argument("--device", help=_DEVICE_HELP)

    p = sub.add_parser("launch", help="拉起应用（monkey LAUNCHER，无需 Activity 名）")
    p.add_argument("--device", help=_DEVICE_HELP)
    p.add_argument("--package", required=True, help="应用包名")

    p = sub.add_parser("ensure", help="确保应用在前台：不在则拉起并等画面稳定")
    p.add_argument("--device", help=_DEVICE_HELP)
    p.add_argument("--package", required=True, help="应用包名")
    p.add_argument("--wait-ms", type=int, default=15000,
                   help="拉起后等画面稳定的超时 ms（默认 15000，游戏冷启动较慢）")

    return parser


def _meta(command: str, start: float) -> dict:
    return {"script": SCRIPT_NAME, "command": command,
            "timestamp": round(time.time(), 3),
            "duration_ms": int((time.time() - start) * 1000)}


def _emit(payload: dict) -> None:
    print(json.dumps(payload, ensure_ascii=False))


def _dispatch(command: str, args) -> dict:
    """执行子命令并返回 data 字段（doctor 单独处理）。"""
    if command == "devices":
        devices = get_devices()
        return {"devices": devices, "count": len(devices)}

    device = _resolve_device(getattr(args, "device", None))
    if command == "screenshot":
        path = screenshot(device, args.output)
        return {"device": device, "path": path, "size_bytes": os.path.getsize(path)}
    if command == "click":
        click(device, args.x, args.y)
        return {"device": device, "x": args.x, "y": args.y}
    if command == "swipe":
        swipe(device, args.x1, args.y1, args.x2, args.y2, args.duration)
        return {"device": device, "from": [args.x1, args.y1],
                "to": [args.x2, args.y2], "duration_ms": args.duration}
    if command == "longpress":
        long_press(device, args.x, args.y, args.duration)
        return {"device": device, "x": args.x, "y": args.y,
                "duration_ms": args.duration}
    if command == "input":
        input_text(device, args.text)
        return {"device": device, "text": args.text}
    if command == "keyevent":
        keyevent(device, args.key)
        return {"device": device, "key": args.key, "code": KEYEVENT_MAP[args.key]}
    if command == "resolution":
        w, h = get_resolution(device)
        return {"device": device, "resolution": [w, h]}
    if command == "foreground":
        app = get_foreground_app(device)
        return {"device": device, "package": app["package"],
                "activity": app["activity"]}
    if command == "launch":
        launch_app(device, args.package)
        return {"device": device, "package": args.package}
    if command == "ensure":
        result = ensure_foreground(device, args.package, args.wait_ms)
        return {"device": device, **result}
    raise AdbError(E_INVALID_ARGUMENT, f"未知子命令: {command}")


def _run_doctor(start: float) -> int:
    result = doctor()
    data = {"checks": result["checks"], "summary": result["summary"]}
    failed = result["summary"]["failed"]
    if failed == 0:
        _emit({"status": "ok", "data": data, "meta": _meta("doctor", start)})
        return EXIT_OK
    failed_names = [c["name"] for c in result["checks"] if c["status"] == "fail"]
    _emit({"status": "error", "error": "DOCTOR_FAILED",
           "message": f"{failed} 项检查失败: {', '.join(failed_names)}",
           "data": data, "meta": _meta("doctor", start)})
    return EXIT_ERROR


def main(argv: list[str] | None = None) -> int:
    _reconfigure_streams()
    start = time.time()
    parser = _build_parser()
    args = parser.parse_args(argv)
    command = args.command
    _log(f"执行命令: {command}")
    try:
        if command == "doctor":
            return _run_doctor(start)
        data = _dispatch(command, args)
    except AdbError as exc:
        _emit({"status": "error", "error": exc.code, "message": exc.message,
               "meta": _meta(command, start)})
        return _ERROR_EXIT.get(exc.code, EXIT_ERROR)
    except KeyboardInterrupt:
        _emit({"status": "error", "error": "INTERRUPTED", "message": "用户中断",
               "meta": _meta(command, start)})
        return EXIT_ERROR
    except Exception as exc:  # 兜底：保证任何异常都以 JSON 报告
        _emit({"status": "error", "error": "INTERNAL_ERROR",
               "message": f"{type(exc).__name__}: {exc}",
               "meta": _meta(command, start)})
        return EXIT_ERROR
    _emit({"status": "ok", "data": data, "meta": _meta(command, start)})
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
