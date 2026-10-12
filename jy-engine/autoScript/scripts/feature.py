"""feature.py — 稳定色点采样 / 感知哈希 / 画面对比

自动生成 JY-Frame ps 特征的核心模块（docs/03-模块详细设计.md §4）：
- compute_phash        感知哈希（DCT pHash，64bit → 16 位十六进制）
- hamming_distance     两个十六进制哈希的汉明距离（复用 models）
- sample_stable_points 稳定色点采样（§4.3 算法）
- verify_points        色点匹配率验证
- compare_screens      画面变化检测
- generate_ps_param    生成 ps 指令的 p 参数

CLI 约定：
- stdout 只输出 JSON（ensure_ascii=False），日志走 stderr
- 成功 {"status":"ok","data":{...},"meta":{...}}；失败 {"status":"error","error":...,"message":...}
- 退出码：0 成功 / 1 通用错误 / 2 参数错误
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import cv2
import numpy as np
from PIL import Image

import models
from models import hamming_distance  # noqa: F401  复用 models 实现


# ======================================================================
# 核心函数
# ======================================================================

def compute_phash(image_path: str) -> str:
    """感知哈希：灰度 → 缩放 32x32 → DCT → 左上 8x8 → 均值二值化 → 64bit → 16 位十六进制。

    阈值取排除 [0,0] 直流项后其余 63 个系数的均值，结果确定性、运行间稳定。
    """
    return _phash_from_image(_load_image(image_path))


def sample_stable_points(image_path: str, bbox: tuple, count: int = 3) -> list[dict]:
    """稳定色点采样（docs/03 §4.3）。

    1. 裁剪 bbox 外扩 2px 区域
    2. 灰度后计算每像素 5x5 邻域方差（blur(x^2) - blur(x)^2）
    3. 候选筛选：方差 < 50；与背景对比度 > 30（背景 = bbox 外圈灰度均值）；不在裁剪边缘 3px 内
    4. 按方差小优先贪心选 count 个点，点间距 > min(bbox_w, bbox_h)/3，不足则有多少算多少
    5. color = 该点 RGB 十六进制大写；tolerance = 逐通道 max(方差*2, 0x10) 上限 0x30

    返回绝对坐标 [{"x","y","color","tolerance"}, ...]（float→int）。
    """
    x1, y1, x2, y2 = (int(v) for v in bbox)
    if x2 <= x1 or y2 <= y1:
        raise FeatureError("BAD_BBOX", f"非法 bbox {bbox}（要求 x1<x2 且 y1<y2）")
    if count <= 0:
        raise FeatureError("BAD_ARGS", f"count 必须为正整数: {count}")

    img = _load_image(image_path).convert("RGB")
    img_w, img_h = img.size
    if x1 >= img_w or y1 >= img_h or x2 <= 0 or y2 <= 0:
        raise FeatureError("BAD_BBOX", f"bbox {bbox} 完全超出图像 ({img_w}x{img_h})")

    # 1. 裁剪 bbox 外扩 2px（越界部分裁到图像边界）
    cx1, cy1 = max(0, x1 - 2), max(0, y1 - 2)
    cx2, cy2 = min(img_w, x2 + 2), min(img_h, y2 + 2)
    crop = img.crop((cx1, cy1, cx2, cy2))

    gray = np.asarray(crop.convert("L"), dtype=np.float32)
    rgb = np.asarray(crop, dtype=np.uint8)

    # 2. 每像素 5x5 邻域方差
    mean = cv2.blur(gray, (5, 5))
    mean_sq = cv2.blur(gray * gray, (5, 5))
    var = np.clip(mean_sq - mean * mean, 0.0, None)

    # 背景 = bbox 外圈（裁剪区域内、bbox 之外）灰度均值
    ring = np.ones(gray.shape, dtype=bool)
    ring[y1 - cy1:y2 - cy1, x1 - cx1:x2 - cx1] = False
    background = float(gray[ring].mean()) if ring.any() else float(gray.mean())

    # 3. 候选筛选
    contrast = np.abs(gray - background)
    mask = (var < 50.0) & (contrast > 30.0)
    margin = 3
    crop_h, crop_w = gray.shape
    if crop_h > 2 * margin and crop_w > 2 * margin:
        mask[:margin, :] = False
        mask[crop_h - margin:, :] = False
        mask[:, :margin] = False
        mask[:, crop_w - margin:] = False
    else:
        mask[:] = False

    # 4. 方差小优先 + 点间距 > min(bbox_w, bbox_h)/3 贪心选取
    ys, xs = np.nonzero(mask)
    points: list[dict] = []
    if ys.size:
        order = np.argsort(var[ys, xs], kind="stable")
        min_dist_sq = (min(x2 - x1, y2 - y1) / 3.0) ** 2
        chosen: list[tuple[int, int]] = []
        for idx in order:
            lx, ly = int(xs[idx]), int(ys[idx])
            if any((lx - px) ** 2 + (ly - py) ** 2 <= min_dist_sq for px, py in chosen):
                continue
            chosen.append((lx, ly))
            # 5. 色值与偏色
            r, g, b = (int(c) for c in rgb[ly, lx])
            v = float(var[ly, lx])
            tol = min(0x30, max(int(v * 2), 0x10))
            points.append({
                "x": cx1 + lx,
                "y": cy1 + ly,
                "color": f"{r:02X}{g:02X}{b:02X}",
                "tolerance": f"{tol:02X}{tol:02X}{tol:02X}",
            })
            if len(points) >= count:
                break

    _log(f"sample_stable_points: 裁剪 ({cx1},{cy1})-({cx2},{cy2}) 背景灰度={background:.1f} "
         f"候选={int(ys.size)} 选中={len(points)}/{count}")
    return points


def verify_points(image_path: str, points: list) -> dict:
    """逐点验证：读取像素 RGB，逐通道 |diff| <= tolerance 对应通道值即命中。

    返回 {"hit_rate", "matched", "total"}。
    """
    img = _load_image(image_path).convert("RGB")
    px = img.load()
    img_w, img_h = img.size
    matched = 0
    total = 0
    for p in points:
        total += 1
        x, y = int(p["x"]), int(p["y"])
        if not (0 <= x < img_w and 0 <= y < img_h):
            continue  # 越界点视为未命中
        cr, cg, cb = _parse_hex_color(p["color"], "color")
        tr, tg, tb = _parse_hex_color(p.get("tolerance", "101010"), "tolerance")
        r, g, b = px[x, y][:3]
        if abs(r - cr) <= tr and abs(g - cg) <= tg and abs(b - cb) <= tb:
            matched += 1
    return {
        "hit_rate": matched / total if total else 0.0,
        "matched": matched,
        "total": total,
    }


def compare_screens(before_path: str, after_path: str) -> dict:
    """画面变化检测。

    返回 {"phash_distance", "changed"(距离>5), "similarity"(1-dist/64), "change_regions"}。
    change_regions：灰度 absdiff → 高斯模糊 → threshold(25) → 轮廓按面积降序取前 5 的
    bbox [x1,y1,x2,y2]。
    """
    before = _load_image(before_path)
    after = _load_image(after_path)
    dist = hamming_distance(_phash_from_image(before), _phash_from_image(after))

    gray_b = np.asarray(before.convert("L"), dtype=np.uint8)
    gray_a = np.asarray(after.convert("L"), dtype=np.uint8)
    if gray_a.shape != gray_b.shape:
        gray_a = cv2.resize(gray_a, (gray_b.shape[1], gray_b.shape[0]),
                            interpolation=cv2.INTER_AREA)

    diff = cv2.absdiff(gray_b, gray_a)
    diff = cv2.GaussianBlur(diff, (5, 5), 0)
    _, binary = cv2.threshold(diff, 25, 255, cv2.THRESH_BINARY)
    contours, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    ranked = sorted(((cv2.contourArea(c), c) for c in contours),
                    key=lambda item: item[0], reverse=True)
    regions = []
    for _, contour in ranked[:5]:
        rx, ry, rw, rh = cv2.boundingRect(contour)
        regions.append([rx, ry, rx + rw, ry + rh])

    return {
        "phash_distance": dist,
        "changed": bool(dist > 5),
        "similarity": 1.0 - dist / 64.0,
        "change_regions": regions,
    }


def generate_ps_param(points: list) -> str:
    """生成 ps 指令的 p 参数："x|y|color-tol,..."，颜色小写。"""
    parts = []
    for p in points:
        color = str(p["color"]).lower()
        tol = str(p.get("tolerance", "101010")).lower()
        parts.append(f"{int(p['x'])}|{int(p['y'])}|{color}-{tol}")
    return ",".join(parts)


# ======================================================================
# _内部工具
# ======================================================================

_PARAM_ERROR_CODES = {"BAD_ARGS", "BAD_BBOX", "BAD_POINTS"}


class FeatureError(Exception):
    """带错误码的业务异常。"""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def _log(message: str) -> None:
    print(f"[feature] {message}", file=sys.stderr)


def _load_image(image_path: str) -> Image.Image:
    """加载图片；不存在 → IMAGE_NOT_FOUND，解码失败 → IMAGE_DECODE_FAILED。"""
    if not os.path.isfile(image_path):
        raise FeatureError("IMAGE_NOT_FOUND", f"图片不存在: {image_path}")
    try:
        img = Image.open(image_path)
        img.load()  # 强制解码，把损坏文件的错误拦在这一步
        return img
    except Exception as exc:
        raise FeatureError("IMAGE_DECODE_FAILED",
                           f"图片解码失败: {image_path} ({exc})") from exc


def _phash_from_image(img: Image.Image) -> str:
    """对已加载的 PIL 图像计算 DCT 感知哈希。"""
    small = img.convert("L").resize((32, 32), Image.Resampling.LANCZOS)
    arr = np.asarray(small, dtype=np.float32)
    dct = cv2.dct(arr)
    block = dct[:8, :8].flatten()
    threshold = float(block[1:].mean())  # 排除 [0,0] 直流项
    bits = 0
    for value in block:
        bits = (bits << 1) | (1 if float(value) > threshold else 0)
    return f"{bits:016x}"


def _parse_hex_color(value, field: str) -> tuple[int, int, int]:
    s = str(value).strip().lstrip("#")
    if len(s) != 6:
        raise FeatureError("BAD_POINTS", f"{field} 必须是 6 位十六进制: {value!r}")
    try:
        return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16))
    except ValueError as exc:
        raise FeatureError("BAD_POINTS",
                           f"{field} 含非十六进制字符: {value!r}") from exc


def _parse_bbox(text: str) -> tuple[int, int, int, int]:
    parts = text.split(",")
    if len(parts) != 4:
        raise FeatureError("BAD_BBOX", f"--bbox 需要格式 x1,y1,x2,y2: {text!r}")
    try:
        x1, y1, x2, y2 = (int(p.strip()) for p in parts)
    except ValueError as exc:
        raise FeatureError("BAD_BBOX", f"--bbox 必须为整数: {text!r}") from exc
    if x2 <= x1 or y2 <= y1:
        raise FeatureError("BAD_BBOX", f"--bbox 要求 x1<x2 且 y1<y2: {text!r}")
    return (x1, y1, x2, y2)


def _load_json_file(path: str):
    if not os.path.isfile(path):
        raise FeatureError("BAD_POINTS", f"points 文件不存在: {path}")
    try:
        with open(path, "r", encoding="utf-8-sig") as f:  # utf-8-sig 兼容带 BOM 的 JSON
            return json.load(f)
    except json.JSONDecodeError as exc:
        raise FeatureError("BAD_POINTS",
                           f"points 文件不是有效 JSON: {path} ({exc})") from exc


def _parse_points_arg(text: str) -> list[dict]:
    """--points 支持：JSON 字符串 / @文件路径 / 文件路径。"""
    if text.startswith("@"):
        return _normalize_points(_load_json_file(text[1:]))
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        data = _load_json_file(text)  # 不是 JSON 则当作文件路径
    return _normalize_points(data)


def _normalize_points(data) -> list[dict]:
    if not isinstance(data, list):
        raise FeatureError("BAD_POINTS", "points 必须是对象数组")
    normalized = []
    for i, p in enumerate(data):
        if not isinstance(p, dict) or not {"x", "y", "color"} <= set(p):
            raise FeatureError("BAD_POINTS", f"第 {i} 个点缺少 x/y/color 字段: {p!r}")
        normalized.append({
            "x": int(p["x"]),
            "y": int(p["y"]),
            "color": str(p["color"]),
            "tolerance": str(p.get("tolerance", "101010")),
        })
    return normalized


# ======================================================================
# main() argparse CLI
# ======================================================================

class _ArgumentParser(argparse.ArgumentParser):
    """argparse 出错时抛异常而非直接退出，便于统一输出 JSON 错误。"""

    def error(self, message):
        raise FeatureError("BAD_ARGS", message)


def _build_parser() -> _ArgumentParser:
    parser = _ArgumentParser(
        prog="feature.py",
        description="稳定色点采样 / 感知哈希 / 画面对比（JY-Frame ps 特征生成）",
    )
    sub = parser.add_subparsers(dest="command", required=True,
                                parser_class=_ArgumentParser)

    p_sample = sub.add_parser("sample", help="采样稳定色点并生成 ps 参数")
    p_sample.add_argument("--image", required=True, help="截图路径")
    p_sample.add_argument("--bbox", required=True, help="元素区域 x1,y1,x2,y2（真实像素）")
    p_sample.add_argument("--count", type=int, default=3, help="采样点数（默认 3）")

    p_phash = sub.add_parser("phash", help="计算感知哈希")
    p_phash.add_argument("--image", required=True, help="截图路径")

    p_compare = sub.add_parser("compare", help="画面变化检测")
    p_compare.add_argument("--before", required=True, help="操作前截图")
    p_compare.add_argument("--after", required=True, help="操作后截图")

    p_verify = sub.add_parser("verify", help="验证色点匹配率")
    p_verify.add_argument("--image", required=True, help="截图路径")
    p_verify.add_argument("--points", required=True,
                          help='points JSON 字符串、@文件路径 或 文件路径')
    return parser


def _run_command(args) -> dict:
    if args.command == "sample":
        bbox = _parse_bbox(args.bbox)
        if args.count <= 0:
            raise FeatureError("BAD_ARGS", f"--count 必须为正整数: {args.count}")
        points = sample_stable_points(args.image, bbox, args.count)
        verify = verify_points(args.image, points)  # 采样后在原图自验证一次
        return {
            "points": points,
            "ps_param": generate_ps_param(points),
            "verify_hit_rate": verify["hit_rate"],
        }
    if args.command == "phash":
        return {"phash": compute_phash(args.image)}
    if args.command == "compare":
        return compare_screens(args.before, args.after)
    if args.command == "verify":
        return verify_points(args.image, _parse_points_arg(args.points))
    raise FeatureError("BAD_ARGS", f"未知命令: {args.command}")


def _emit(payload: dict) -> None:
    print(json.dumps(payload, ensure_ascii=False))


def _emit_ok(args, data: dict, started: float) -> None:
    _emit({
        "status": "ok",
        "data": data,
        "meta": {
            "script": "feature.py",
            "command": list(sys.argv[1:]),
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S"),
            "duration_ms": round((time.time() - started) * 1000),
        },
    })


def _emit_error(err: FeatureError) -> None:
    _emit({"status": "error", "error": err.code, "message": err.message})


def main(argv: list[str] | None = None) -> int:
    started = time.time()
    try:
        args = _build_parser().parse_args(argv)
        data = _run_command(args)
    except FeatureError as err:
        _emit_error(err)
        return 2 if err.code in _PARAM_ERROR_CODES else 1
    except Exception as exc:  # 意外异常兜底
        _emit_error(FeatureError("INTERNAL_ERROR", f"{type(exc).__name__}: {exc}"))
        return 1
    _emit_ok(args, data, started)
    return 0


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):
        try:
            _stream.reconfigure(encoding="utf-8")
        except Exception:
            pass
    sys.exit(main())