"""coordinate.py — 九宫格锚点坐标换算

真实像素坐标与 JY-Frame 设计坐标的双向换算，采用九宫格锚点系统实现全分辨率适配。
被 compile.py 直接 import（核心函数零改动可用），也可作为独立 CLI 工具运行。
设计文档：docs/03-模块详细设计.md §5

九宫格锚点：1=左上 2=上中 3=右上 / 4=左中 5=中心 6=右中 / 7=左下 8=下中 9=右下
"""
from __future__ import annotations

import argparse
import json
import math
import re
import subprocess
import sys
import time
from pathlib import Path

# === 配置 ===

SCRIPT_NAME = "coordinate.py"
DEFAULT_DESIGN_RESOLUTION = (720, 1280)
ADB_TIMEOUT = 10                      # adb 命令超时（秒）
VERIFY_TOLERANCE_PX = 1               # verify 往返误差容忍（像素）

ANCHOR_REGIONS = {
    1: "左上", 2: "上中", 3: "右上",
    4: "左中", 5: "中心", 6: "右中",
    7: "左下", 8: "下中", 9: "右下",
}

# === 异常（CLI 内部用于区分退出码）===

class _ArgError(Exception):
    """参数错误 → 退出码 2"""


class _DeviceError(Exception):
    """设备/ADB 错误 → 退出码 3"""

# === 核心函数（compile.py 直接 import，签名不可改动）===

def real_to_design(x: float, y: float, real_res: tuple, design_res: tuple, anchor: int) -> tuple[int, int]:
    """真实像素坐标 → 设计坐标（按九宫格锚点）。

    公式为 design_to_real 的逆运算（解方程）。
    """
    _check_anchor(anchor)
    Wr, Hr = real_res
    Wd, Hd = design_res
    sx = Wr / Wd
    sy = Hr / Hd
    col = (anchor - 1) % 3   # 0=靠左 1=居中 2=靠右
    row = (anchor - 1) // 3  # 0=靠上 1=居中 2=靠下

    if col == 0:
        dx = x / sx
    elif col == 1:
        dx = Wd / 2 + (x - Wr / 2) / sx
    else:
        dx = Wd - (Wr - x) / sx

    if row == 0:
        dy = y / sy
    elif row == 1:
        dy = Hd / 2 + (y - Hr / 2) / sy
    else:
        dy = Hd - (Hr - y) / sy

    return (int(round(dx)), int(round(dy)))

def design_to_real(x: float, y: float, real_res: tuple, design_res: tuple, anchor: int) -> tuple[int, int]:
    """设计坐标 → 真实像素坐标（按九宫格锚点）。

    设 Wd,Hd=设计分辨率；Wr,Hr=真实分辨率；sx=Wr/Wd，sy=Hr/Hd。
    x 轴：锚点 1/4/7 → x*sx；2/5/8 → Wr/2+(x-Wd/2)*sx；3/6/9 → Wr-(Wd-x)*sx
    y 轴：锚点 1/2/3 → y*sy；4/5/6 → Hr/2+(y-Hd/2)*sy；7/8/9 → Hr-(Hd-y)*sy
    """
    _check_anchor(anchor)
    Wr, Hr = real_res
    Wd, Hd = design_res
    sx = Wr / Wd
    sy = Hr / Hd
    col = (anchor - 1) % 3   # 0=靠左 1=居中 2=靠右
    row = (anchor - 1) // 3  # 0=靠上 1=居中 2=靠下

    if col == 0:
        dx = x * sx
    elif col == 1:
        dx = Wr / 2 + (x - Wd / 2) * sx
    else:
        dx = Wr - (Wd - x) * sx

    if row == 0:
        dy = y * sy
    elif row == 1:
        dy = Hr / 2 + (y - Hd / 2) * sy
    else:
        dy = Hr - (Hd - y) * sy

    return (int(round(dx)), int(round(dy)))

def detect_anchor(center_x: float, center_y: float, screen_w: int, screen_h: int) -> int:
    """根据控件中心在屏幕九宫格中的位置返回锚点编号 1-9。"""
    col = 0 if center_x < screen_w / 3 else (1 if center_x < screen_w * 2 / 3 else 2)
    row = 0 if center_y < screen_h / 3 else (1 if center_y < screen_h * 2 / 3 else 2)
    return row * 3 + col + 1  # 返回 1-9


def detect_resolution(device_id: str) -> tuple[int, int]:
    """检测设备真实分辨率：`adb -s <id> shell wm size`，优先 Override size。

    异常统一抛 RuntimeError（CLI 层映射为退出码 3）。
    """
    out = _adb_shell(device_id, "wm", "size")
    physical = override = None
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("Override size:"):
            override = line.split(":", 1)[1].strip()
        elif line.startswith("Physical size:"):
            physical = line.split(":", 1)[1].strip()
    raw = override or physical
    if not raw:
        raise RuntimeError(f"无法从 wm size 输出解析分辨率: {out.strip()!r}")
    m = re.search(r"(\d+)\s*x\s*(\d+)", raw)
    if not m:
        raise RuntimeError(f"分辨率格式异常: {raw!r}")
    return (int(m.group(1)), int(m.group(2)))

# === 内部工具 ===

def _check_anchor(anchor: int) -> None:
    if anchor not in range(1, 10):
        raise ValueError(f"anchor 必须为 1-9，得到 {anchor!r}")


def _log(message: str) -> None:
    """日志走 stderr，保证 stdout 只有 JSON。"""
    print(f"[{SCRIPT_NAME}] {message}", file=sys.stderr)


def _adb_shell(device_id: str, *args: str) -> str:
    """执行 `adb -s <device_id> shell <args>`，失败抛 RuntimeError。"""
    cmd = ["adb", "-s", device_id, "shell", *args]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True,
                              errors="replace", timeout=ADB_TIMEOUT)
    except FileNotFoundError as e:
        raise RuntimeError("adb 命令不可用（未安装或不在 PATH）") from e
    except subprocess.TimeoutExpired as e:
        raise RuntimeError(f"adb 命令超时（{ADB_TIMEOUT}s）: {' '.join(args)}") from e
    if proc.returncode != 0:
        err = (proc.stderr or proc.stdout or "").strip()
        raise RuntimeError(f"adb 命令失败（exit={proc.returncode}）: {err}")
    return proc.stdout

def _detect_density(device_id: str) -> int:
    """检测设备屏幕密度：`adb -s <id> shell wm density`，优先 Override density。"""
    out = _adb_shell(device_id, "wm", "density")
    physical = override = None
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("Override density:"):
            override = line.split(":", 1)[1].strip()
        elif line.startswith("Physical density:"):
            physical = line.split(":", 1)[1].strip()
    raw = override or physical
    if not raw:
        raise RuntimeError(f"无法从 wm density 输出解析密度: {out.strip()!r}")
    m = re.search(r"(\d+)", raw)
    if not m:
        raise RuntimeError(f"密度格式异常: {raw!r}")
    return int(m.group(1))


def _load_default_design_res() -> tuple[int, int]:
    """读取 config/devices.yaml 中 default_device 的 design_resolution。

    文件缺失 / pyyaml 缺失 / 解析失败一律回退 DEFAULT_DESIGN_RESOLUTION，仅记 stderr 日志。
    """
    candidates = [
        Path(__file__).resolve().parent.parent / "config" / "devices.yaml",
        Path.cwd() / "config" / "devices.yaml",
    ]
    cfg = next((p for p in candidates if p.is_file()), None)
    if cfg is None:
        _log(f"未找到 config/devices.yaml，使用默认设计分辨率 {DEFAULT_DESIGN_RESOLUTION}")
        return DEFAULT_DESIGN_RESOLUTION
    try:
        import yaml
    except ImportError:
        _log("pyyaml 未安装，使用默认设计分辨率")
        return DEFAULT_DESIGN_RESOLUTION
    try:
        data = yaml.safe_load(cfg.read_text(encoding="utf-8")) or {}
        default_name = data.get("default_device")
        for dev in data.get("devices") or []:
            if isinstance(dev, dict) and dev.get("name") == default_name:
                dr = dev.get("design_resolution")
                if dr and len(dr) == 2:
                    return (int(dr[0]), int(dr[1]))
        _log(f"{cfg} 中未找到 default_device={default_name!r} 的 design_resolution，使用默认")
    except Exception as e:  # 配置问题不致命，统一回退默认值
        _log(f"解析 {cfg} 失败（{e}），使用默认设计分辨率")
    return DEFAULT_DESIGN_RESOLUTION

def _parse_resolution(text: str, arg_name: str) -> tuple[int, int]:
    """解析 "w,h" 为 (w, h)，失败抛参数错误。"""
    parts = text.split(",")
    if len(parts) != 2:
        raise _ArgError(f"{arg_name} 格式应为 w,h，得到 {text!r}")
    try:
        w, h = int(parts[0]), int(parts[1])
    except ValueError:
        raise _ArgError(f"{arg_name} 格式应为 w,h（整数），得到 {text!r}") from None
    if w <= 0 or h <= 0:
        raise _ArgError(f"{arg_name} 分辨率必须为正整数，得到 {text!r}")
    return (w, h)


def _parse_point(text: str) -> tuple:
    """解析 "x,y" 坐标，整数值转 int 便于 JSON 输出，失败抛参数错误。"""
    parts = text.split(",")
    if len(parts) != 2:
        raise _ArgError(f"坐标格式应为 x,y，得到 {text!r}")
    try:
        x, y = float(parts[0]), float(parts[1])
    except ValueError:
        raise _ArgError(f"坐标格式应为 x,y（数字），得到 {text!r}") from None
    return (_as_int_if_whole(x), _as_int_if_whole(y))


def _as_int_if_whole(value: float):
    """浮点整数值（如 540.0）转 int，其余原样返回。"""
    if math.isfinite(value) and value == int(value):
        return int(value)
    return value


def _device_resolution(device_id: str) -> tuple[int, int]:
    try:
        return detect_resolution(device_id)
    except RuntimeError as e:
        raise _DeviceError(str(e)) from e


def _device_density(device_id: str) -> int:
    try:
        return _detect_density(device_id)
    except RuntimeError as e:
        raise _DeviceError(str(e)) from e

# === CLI ===

class _JsonArgumentParser(argparse.ArgumentParser):
    """参数错误也向 stdout 输出 JSON 并以退出码 2 退出。"""

    def error(self, message):
        print(json.dumps({"status": "error", "error": "ARGS_INVALID", "message": message},
                         ensure_ascii=False))
        sys.exit(2)


def _cmd_convert(args) -> dict:
    real_res = _parse_resolution(args.resolution, "--resolution")
    if args.design_resolution:
        design_res = _parse_resolution(args.design_resolution, "--design-resolution")
    else:
        design_res = _load_default_design_res()
    if args.from_space == args.to_space:
        raise _ArgError("--from 与 --to 不能相同")
    if args.from_space == "real":
        x, y = real_to_design(args.x, args.y, real_res, design_res, args.anchor)
    else:
        x, y = design_to_real(args.x, args.y, real_res, design_res, args.anchor)
    return {"x": x, "y": y, "from": args.from_space, "to": args.to_space, "anchor": args.anchor}


def _cmd_detect_anchor(args) -> dict:
    w, h = _parse_resolution(args.resolution, "--resolution")
    anchor = detect_anchor(args.x, args.y, w, h)
    return {"anchor": anchor, "region": ANCHOR_REGIONS[anchor]}


def _cmd_detect(args) -> dict:
    _log(f"检测设备 {args.device} ...")
    width, height = _device_resolution(args.device)
    density = _device_density(args.device)
    return {"width": width, "height": height, "density": density}


def _cmd_verify(args) -> dict:
    _log(f"检测设备 {args.device} ...")
    real_res = _device_resolution(args.device)
    design_res = _load_default_design_res()
    px, py = _parse_point(args.test_point)
    # real → design → real → design 链路；design→real→design 与原 design_point 比较
    dx, dy = real_to_design(px, py, real_res, design_res, args.anchor)
    fx, fy = design_to_real(dx, dy, real_res, design_res, args.anchor)
    rtx, rty = real_to_design(fx, fy, real_res, design_res, args.anchor)
    err = max(abs(rtx - dx), abs(rty - dy))
    return {
        "real_point": [px, py],
        "design_point": [dx, dy],
        "framework_point": [fx, fy],
        "match": err <= VERIFY_TOLERANCE_PX,
    }


_DISPATCH = {
    "convert": _cmd_convert,
    "detect-anchor": _cmd_detect_anchor,
    "detect": _cmd_detect,
    "verify": _cmd_verify,
}

def _build_parser() -> argparse.ArgumentParser:
    parser = _JsonArgumentParser(prog="coordinate.py", description="九宫格锚点坐标换算工具")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("convert", help="设计坐标与真实坐标互转")
    p.add_argument("--x", type=float, required=True, help="坐标 x")
    p.add_argument("--y", type=float, required=True, help="坐标 y")
    p.add_argument("--from", dest="from_space", required=True, choices=["real", "design"],
                   help="源坐标系")
    p.add_argument("--to", dest="to_space", required=True, choices=["real", "design"],
                   help="目标坐标系")
    p.add_argument("--resolution", required=True, metavar="w,h",
                   help="真实分辨率")
    p.add_argument("--design-resolution", dest="design_resolution", default=None, metavar="w,h",
                   help="设计分辨率（默认读 config/devices.yaml，否则 720,1280）")
    p.add_argument("--anchor", type=int, choices=range(1, 10), required=True, metavar="1-9",
                   help="九宫格锚点")

    p = sub.add_parser("detect-anchor", help="根据控件中心位置判断九宫格锚点")
    p.add_argument("--x", type=float, required=True, help="控件中心 x")
    p.add_argument("--y", type=float, required=True, help="控件中心 y")
    p.add_argument("--resolution", required=True, metavar="w,h", help="屏幕分辨率")

    p = sub.add_parser("detect", help="检测设备分辨率与屏幕密度")
    p.add_argument("--device", required=True, help="设备 id")

    p = sub.add_parser("verify", help="坐标换算 round-trip 自洽校验")
    p.add_argument("--device", required=True, help="设备 id")
    p.add_argument("--test-point", dest="test_point", required=True, metavar="x,y",
                   help="测试点（真实像素坐标）")
    p.add_argument("--anchor", type=int, choices=range(1, 10), required=True, metavar="1-9",
                   help="九宫格锚点")
    return parser

def main(argv=None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = _build_parser()
    args = parser.parse_args(argv)
    t0 = time.time()
    meta = {"script": SCRIPT_NAME, "command": args.cmd, "timestamp": round(time.time(), 3)}
    try:
        data = _DISPATCH[args.cmd](args)
        meta["duration_ms"] = int((time.time() - t0) * 1000)
        result = {"status": "ok", "data": data, "meta": meta}
        code = 0
    except _ArgError as e:
        result = {"status": "error", "error": "ARGS_INVALID", "message": str(e), "meta": meta}
        code = 2
    except _DeviceError as e:
        result = {"status": "error", "error": "ADB_DEVICE_ERROR", "message": str(e), "meta": meta}
        code = 3
    except Exception as e:  # 顶层兜底，保证 stdout 始终是 JSON
        result = {"status": "error", "error": "INTERNAL_ERROR",
                  "message": f"{type(e).__name__}: {e}", "meta": meta}
        code = 1
    print(json.dumps(result, ensure_ascii=False))
    return code


if __name__ == "__main__":
    sys.exit(main())