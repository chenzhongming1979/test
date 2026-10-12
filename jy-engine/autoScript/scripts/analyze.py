# scripts/analyze.py
"""analyze.py — 界面分析（视觉 LLM + OCR 融合）

对截图做多模态分析，输出结构化 ScreenState：
  - OCR：RapidOCR（rapidocr_onnxruntime），模块级单例懒加载，不可用时自动降级
  - 视觉 LLM：OpenAI 兼容 /chat/completions（豆包/Ark），纯标准库 urllib 实现，
    未配置 DOUBAO_API_KEY 时自动降级为仅 OCR
  - 融合：bbox IoU > 0.5 合并（文字优先 OCR 结果，bbox 取并集，confidence 取 vision）

设计文档：docs/03-模块详细设计.md §3、docs/04-数据结构与接口.md §2/§4.2

CLI：
    python scripts/analyze.py --image <path> [--ocr-only] [--vision-model <model>] [--output <json_path>]

约定：stdout 只输出 JSON（ensure_ascii=False），日志走 stderr。
退出码：0 成功 / 1 通用错误 / 2 参数错误 / 4 外部依赖错误（LLM/OCR）
"""
from __future__ import annotations

import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # 保险：保证可 import 同目录 models.py

import argparse
import base64
import copy
import json

import re
import time
import urllib.error
import urllib.request

import numpy as np
import yaml
from PIL import Image

from models import ScreenState, UIElement

# === 配置 ===

SCRIPT_NAME = "analyze.py"
API_VERSION = "1.0"

VISION_PROMPT = """你是安卓界面分析专家。分析这张截图，返回严格 JSON。

要求：
1. 识别所有可交互元素（按钮/图标/列表项/输入框/标签页）
2. 每个元素：type, text, bbox[x1,y1,x2,y2], confidence, is_primary
3. 判断当前界面语义标签（主界面/副本选择/战斗中/背包/设置/弹窗/加载中...）
4. 简要描述界面

返回：
{"scene_label": "...", "elements": [...], "description": "..."}
只返回 JSON。"""

_DEFAULT_MODEL_CONFIG = {
    "vision": {
        "provider": "doubao",
        "api_key": "${DOUBAO_API_KEY}",
        "model": "doubao-1-5-vision-pro-32k",
        "base_url": "https://ark.cn-beijing.volces.com/api/v3",
        "timeout": 30,
        "max_retries": 2,
    },
    "ocr": {"provider": "rapidocr"},
}

_OCR_ENGINE = None           # RapidOCR 单例（懒加载）
_OCR_FAILED = False          # 本进程内 OCR 已判定不可用
_last_availability = {"vision_available": False, "ocr_available": False}



# === 核心函数 ===

def load_model_config(config_path="config/models.yaml") -> dict:
    """读取模型配置；api_key 中 "${ENV_VAR}" 形式用 expandvars 展开（空则视为未配置）。

    文件缺失或解析失败时返回内置默认 dict（深拷贝，调用方可安全修改）。
    """
    cfg = None
    if os.path.isfile(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                cfg = yaml.safe_load(f)
        except Exception as e:
            _log(f"配置文件解析失败，使用内置默认: {e}")
    else:
        _log(f"配置文件不存在，使用内置默认: {config_path}")
    if not isinstance(cfg, dict) or not cfg:
        cfg = copy.deepcopy(_DEFAULT_MODEL_CONFIG)
    vision = cfg.get("vision")
    if isinstance(vision, dict) and "api_key" in vision:
        vision["api_key"] = _resolve_api_key(vision.get("api_key"))
    return cfg


def _post_chat_json(url: str, payload: dict, api_key: str,
                    timeout: float) -> dict:
    """POST JSON 并解析响应。

    优先用系统 curl.exe 子进程（部分网关 WAF 按 TLS 指纹掐断 Python
    urllib，实测 apihub.agnes-ai.com 秒断 urllib 而 curl 正常）；
    curl 不可用时回退 urllib。
    """
    import subprocess
    import tempfile
    payload_str = json.dumps(payload, ensure_ascii=False)
    try:
        with tempfile.NamedTemporaryFile(
                "w", suffix=".json", delete=False,
                encoding="utf-8") as f:
            f.write(payload_str)
            tmp_path = f.name
        try:
            proc = subprocess.run(
                ["curl.exe", "-sS", "--max-time", str(int(timeout)),
                 "-H", "Content-Type: application/json",
                 "-H", "Authorization: Bearer " + api_key,
                 "--data", "@" + tmp_path, url],
                capture_output=True, timeout=timeout + 10)
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass
        out = proc.stdout.decode("utf-8", errors="replace")
        if proc.returncode != 0:
            raise RuntimeError(
                "curl 失败(rc=%d): %s %s" % (
                    proc.returncode, out[:200],
                    proc.stderr.decode("utf-8", "replace")[:200]))
        try:
            return json.loads(out)
        except json.JSONDecodeError:
            raise RuntimeError("响应非 JSON: %s" % out[:300])
    except FileNotFoundError:
        # 无 curl.exe → urllib 回退
        req = urllib.request.Request(
            url, data=payload_str.encode("utf-8"),
            headers={"Content-Type": "application/json",
                     "Authorization": "Bearer " + api_key},
            method="POST")
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return json.loads(resp.read().decode("utf-8",
                                                     errors="replace"))
        except urllib.error.HTTPError as e:
            detail = ""
            try:
                detail = e.read().decode("utf-8", errors="replace")[:300]
            except Exception:
                pass
            raise RuntimeError("HTTP %d: %s" % (e.code, detail)) from e
        except Exception as e:
            raise RuntimeError("请求失败: %s" % e) from e


def vision_analyze(image_path: str, prompt: str, model_config: dict) -> dict:
    """调用 OpenAI 兼容视觉接口，返回 {"scene_label","elements","description"}。

    纯标准库 urllib.request 实现；HTTP/解析异常抛 RuntimeError。
    model_config 传 vision 子配置（兼容传入整份配置）。
    """
    cfg = model_config or {}
    if isinstance(cfg.get("vision"), dict):
        cfg = cfg["vision"]
    api_key = str(cfg.get("api_key") or "").strip()
    if not api_key:
        raise RuntimeError("api_key 未配置")
    model = str(cfg.get("model") or _DEFAULT_MODEL_CONFIG["vision"]["model"])
    base_url = str(cfg.get("base_url") or _DEFAULT_MODEL_CONFIG["vision"]["base_url"]).rstrip("/")
    try:
        timeout = float(cfg.get("timeout") or 30)
    except (TypeError, ValueError):
        timeout = 30.0

    with open(image_path, "rb") as f:
        img = Image.open(f)
        # PNG 截图体积大（游戏场景 1-2MB，base64 后 2-3MB），转 JPEG 压缩 5-8 倍
        import io
        buf = io.BytesIO()
        img.convert("RGB").save(buf, "JPEG", quality=85)
        b64 = base64.b64encode(buf.getvalue()).decode("ascii")

    payload = {
        "model": model,
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "image_url", "image_url": {"url": "data:image/png;base64," + b64}},
                    {"type": "text", "text": prompt},
                ],
            }
        ],
        "temperature": 0,
        "max_tokens": 6000,  # 游戏主界面元素多，2000 会截断 JSON（finish_reason=length）
        # 思考型多模态模型（doubao-seed-1-6 等）关闭深度思考，降低时延
        "thinking": {"type": "disabled"},
    }
    body = _post_chat_json(base_url + "/chat/completions", payload,
                           api_key, timeout)

    if not isinstance(body, dict):
        raise RuntimeError("响应格式异常: %s" % str(body)[:300])
    try:
        choices = body.get("choices") or []
        content = choices[0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as e:
        raise RuntimeError("响应格式异常: %s" % json.dumps(body, ensure_ascii=False)[:300]) from e
    if isinstance(content, list):  # 部分网关返回分段 content
        content = "".join(p.get("text", "") for p in content if isinstance(p, dict))
    if os.environ.get("AUTOSCRIPT_DEBUG_LLM"):
        _log("LLM finish_reason: %s | content_len=%d" % (
            choices[0].get("finish_reason"), len(content)))
        _log("LLM 返回尾部200字符: %s" % content[-200:])

    data = _parse_llm_json(content)
    elements = data.get("elements")
    return {
        "scene_label": str(data.get("scene_label") or "未识别"),
        "elements": elements if isinstance(elements, list) else [],
        "description": str(data.get("description") or ""),
    }


def ocr_recognize(image_path: str, region=None) -> list[dict]:
    """RapidOCR 识别，返回 [{"text","bbox":[x1,y1,x2,y2],"score"}]。

    region=(x1,y1,x2,y2) 时裁剪后识别、坐标平移回原图。
    import 失败或运行失败抛 RuntimeError(OCR_UNAVAILABLE)。
    """
    engine = _get_ocr()
    offset_x, offset_y = 0, 0
    try:
        if region:
            x1, y1, x2, y2 = (int(v) for v in region)
            offset_x, offset_y = x1, y1
            img = Image.open(image_path).convert("RGB").crop((x1, y1, x2, y2))
            arr = np.asarray(img)[:, :, ::-1].copy()  # RGB→BGR，RapidOCR 遵循 OpenCV 约定
            out = engine(arr)
        else:
            out = engine(image_path)
    except RuntimeError:
        raise
    except Exception as e:
        raise RuntimeError("OCR_UNAVAILABLE: OCR 识别失败: %s" % e) from e

    raw = out[0] if isinstance(out, tuple) else out
    items = []
    for entry in raw or []:
        try:
            box, text, score = entry[0], entry[1], entry[2]
        except (TypeError, ValueError, IndexError):
            continue
        xs = [float(p[0]) for p in box]
        ys = [float(p[1]) for p in box]
        bx1 = int(min(xs)) + offset_x
        by1 = int(min(ys)) + offset_y
        bx2 = int(max(xs)) + offset_x
        by2 = int(max(ys)) + offset_y
        items.append({
            "text": str(text),
            "bbox": [bx1, by1, bx2, by2],
            "score": float(score),
        })
    return items


def fuse_elements(vision_elements: list, ocr_elements: list) -> list:
    """融合 vision 元素与 OCR 元素。

    vision 元素：{type,text,bbox,confidence,is_primary}；OCR 元素：{text,bbox,score}。
    bbox IoU > 0.5 合并：文字优先 OCR 结果，bbox 取并集，confidence 取 vision；
    未合并的 OCR 条目转为 type="text" 元素。返回中间 dict 列表。
    """
    fused = []
    used_ocr = set()

    v_els = []
    for el in vision_elements or []:
        if not isinstance(el, dict):
            continue
        bbox = _norm_bbox(el.get("bbox"))
        if bbox is None:
            continue
        try:
            conf = float(el.get("confidence", 0.0))
        except (TypeError, ValueError):
            conf = 0.0
        v_els.append({
            "type": str(el.get("type") or "text"),
            "text": str(el.get("text") or ""),
            "bbox": bbox,
            "confidence": conf,
            "is_primary": bool(el.get("is_primary", False)),
        })

    for v in v_els:
        best_iou, best_idx = 0.0, -1
        for j, o in enumerate(ocr_elements or []):
            if j in used_ocr or not isinstance(o, dict):
                continue
            ob = _norm_bbox(o.get("bbox"))
            if ob is None:
                continue
            iou = _iou(v["bbox"], ob)
            if iou > best_iou:
                best_iou, best_idx = iou, j
        if best_idx >= 0 and best_iou > 0.5:
            used_ocr.add(best_idx)
            o = ocr_elements[best_idx]
            otext = str(o.get("text") or "").strip()
            fused.append({
                "type": v["type"],
                "text": otext or v["text"],
                "bbox": _union_bbox(v["bbox"], _norm_bbox(o["bbox"])),
                "confidence": v["confidence"],
                "is_primary": v["is_primary"],
            })
        else:
            fused.append(dict(v))

    for j, o in enumerate(ocr_elements or []):
        if j in used_ocr or not isinstance(o, dict):
            continue
        ob = _norm_bbox(o.get("bbox"))
        if ob is None:
            continue
        try:
            score = float(o.get("score", 0.0))
        except (TypeError, ValueError):
            score = 0.0
        fused.append({
            "type": "text",
            "text": str(o.get("text") or ""),
            "bbox": ob,
            "confidence": score,
            "is_primary": False,
        })
    return fused


def is_native_layout(image_path: str) -> bool:
    """启发式判断原生控件界面：HSV 低饱和像素占比 > 0.6 且文本块数 > 8。

    保守判断：条件不满足或任何异常时返回 False。
    """
    try:
        img = Image.open(image_path).convert("HSV")
        arr = np.asarray(img)
        sat = arr[:, :, 1]
        low_ratio = float((sat < 51).mean())  # 饱和度 < 0.2*255 视为低饱和
        if low_ratio <= 0.6:
            return False
        blocks = ocr_recognize(image_path)
        return len(blocks) > 8
    except Exception:
        return False


def analyze_screen(image_path: str, model_config=None, ocr_only=False) -> ScreenState:
    """主分析入口：phash → OCR → vision → 融合 → ScreenState。

    - vision 不可用（无 key 或 ocr_only）→ 降级：elements 仅来自 OCR（type="text"），
      description 注明 "vision 降级：仅 OCR"
    - vision 有 key 但调用失败 → RuntimeError(LLM_ERROR)
    - --ocr-only 且 OCR 不可用 → RuntimeError(OCR_UNAVAILABLE)
    """
    cfg = model_config if isinstance(model_config, dict) and model_config else load_model_config()
    vision_cfg = cfg.get("vision") if isinstance(cfg.get("vision"), dict) else cfg
    t0 = time.time()

    # 1. phash + 分辨率（内部实现，不 import feature.py）
    ph = _phash(image_path)
    with Image.open(image_path) as im:
        resolution = tuple(int(v) for v in im.size)

    # 2. OCR（可用则用）
    ocr_elements = []
    ocr_ok = False
    try:
        ocr_elements = ocr_recognize(image_path)
        ocr_ok = True
        _log(f"OCR 识别到 {len(ocr_elements)} 个文本块")
    except RuntimeError as e:
        if ocr_only:
            raise RuntimeError("OCR_UNAVAILABLE: %s" % e) from e
        _log(f"OCR 不可用，走降级路径: {e}")

    # 3. vision
    vision_available = False
    vision_elements = []
    scene_label = "未识别"
    description = ""
    if ocr_only:
        description = "vision 降级：仅 OCR（--ocr-only 指定）"
        _log("vision 跳过（--ocr-only）")
    else:
        api_key = str(vision_cfg.get("api_key") or "").strip() if isinstance(vision_cfg, dict) else ""
        if not api_key:
            description = "vision 降级：仅 OCR（未配置视觉模型 Key）"
            _log("vision 降级：api_key 未配置")
        else:
            try:
                v = vision_analyze(image_path, VISION_PROMPT, vision_cfg)
            except Exception as e:
                # 视觉调用失败不整体报错：降级 OCR 继续分析（服务端故障时不阻塞采集流程）
                _log(f"vision 调用失败，降级 OCR: {type(e).__name__}: {e}")
                description = f"vision 降级：仅 OCR（视觉模型调用失败: {type(e).__name__}）"
                v = {}
            vision_elements = v.get("elements") or []
            if vision_elements or v.get("scene_label"):
                scene_label = str(v.get("scene_label") or "未识别")
                description = str(v.get("description") or description)
                vision_available = True
                _log(f"vision 完成: scene={scene_label}, elements={len(vision_elements)}")

    if not ocr_ok:
        description = (description + "；OCR 不可用").strip("；")

    # 4. 融合 → UIElement
    fused = fuse_elements(vision_elements if vision_available else [], ocr_elements)
    ui_elements = []
    for i, el in enumerate(fused, 1):
        bbox = tuple(int(v) for v in el["bbox"])
        center = ((bbox[0] + bbox[2]) // 2, (bbox[1] + bbox[3]) // 2)
        ui_elements.append(UIElement(
            id=f"elem_{i:03d}",
            type=str(el.get("type") or "text"),
            text=str(el.get("text") or ""),
            bbox=bbox,
            center=center,
            confidence=float(el.get("confidence") or 0.0),  # confidence<0.5 保留原值，交由 AI 决策时过滤
            is_primary=bool(el.get("is_primary") or False),
        ))

    texts = [{"text": o["text"], "bbox": o["bbox"]} for o in ocr_elements]

    state = ScreenState(
        state_id=0,
        scene_label=scene_label,
        description=description,
        elements=ui_elements,
        texts=texts,
        phash=ph,
        screenshot=image_path,
        resolution=resolution,
    )
    _last_availability["vision_available"] = vision_available
    _last_availability["ocr_available"] = ocr_ok
    _log(f"分析完成，耗时 {time.time() - t0:.2f}s")
    return state


# === 内部工具 ===

def _log(message: str) -> None:
    """日志走 stderr，不污染 stdout 的 JSON 输出。"""
    print(f"[{SCRIPT_NAME}] {message}", file=sys.stderr, flush=True)


def _resolve_api_key(value) -> str:
    """展开 "${ENV_VAR}" 形式的 api_key；未设置或展开后为空视为未配置（返回空串）。"""
    if value is None:
        return ""
    expanded = os.path.expandvars(str(value)).strip()
    if not expanded or "${" in expanded:  # 环境变量未设置时 expandvars 保留原样
        return ""
    return expanded


def _extract_json_block(text: str):
    """从文本中提取第一个花括号平衡的 {...} 块（处理字符串与转义）。"""
    start = text.find("{")
    if start < 0:
        return None
    depth, in_str, esc = 0, False, False
    for i in range(start, len(text)):
        c = text[i]
        if in_str:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
        else:
            if c == '"':
                in_str = True
            elif c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    return text[start:i + 1]
    return None


def _parse_llm_json(content: str) -> dict:
    """剥离 ```json 围栏后 json.loads；失败则提取第一个 {...} 平衡块再试。"""
    text = str(content).strip()
    if text.startswith("```"):
        text = re.sub(r"^```[a-zA-Z0-9_-]*\s*", "", text)
        text = re.sub(r"\s*```$", "", text).strip()
    try:
        data = json.loads(text)
        if isinstance(data, dict):
            return data
    except (json.JSONDecodeError, ValueError):
        pass
    block = _extract_json_block(text)
    if block:
        try:
            data = json.loads(block)
            if isinstance(data, dict):
                return data
        except (json.JSONDecodeError, ValueError):
            pass
    raise RuntimeError("LLM 返回内容无法解析为 JSON: %s" % str(content)[:200])


def _norm_bbox(b):
    """将 bbox 规整为 [x1,y1,x2,y2] 整数列表；非法返回 None。"""
    if b is None:
        return None
    try:
        seq = list(b)
        if len(seq) != 4:
            return None
        x1, y1, x2, y2 = (int(round(float(v))) for v in seq)
    except (TypeError, ValueError):
        return None
    if x2 < x1:
        x1, x2 = x2, x1
    if y2 < y1:
        y1, y2 = y2, y1
    return [x1, y1, x2, y2]


def _union_bbox(a, b) -> list:
    return [min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3])]


def _iou(a, b) -> float:
    ix1, iy1 = max(a[0], b[0]), max(a[1], b[1])
    ix2, iy2 = min(a[2], b[2]), min(a[3], b[3])
    iw, ih = max(0, ix2 - ix1), max(0, iy2 - iy1)
    inter = iw * ih
    if inter <= 0:
        return 0.0
    area_a = max(0, a[2] - a[0]) * max(0, a[3] - a[1])
    area_b = max(0, b[2] - b[0]) * max(0, b[3] - b[1])
    union = area_a + area_b - inter
    return inter / union if union > 0 else 0.0


def _get_ocr():
    """懒加载 RapidOCR 单例；不可用抛 RuntimeError(OCR_UNAVAILABLE)。"""
    global _OCR_ENGINE, _OCR_FAILED
    if _OCR_ENGINE is not None:
        return _OCR_ENGINE
    if _OCR_FAILED:
        raise RuntimeError("OCR_UNAVAILABLE: RapidOCR 此前初始化失败")
    try:
        from rapidocr_onnxruntime import RapidOCR
    except Exception as e:
        _OCR_FAILED = True
        raise RuntimeError("OCR_UNAVAILABLE: rapidocr_onnxruntime 导入失败: %s" % e) from e
    try:
        _OCR_ENGINE = RapidOCR()
    except Exception as e:
        _OCR_FAILED = True
        raise RuntimeError("OCR_UNAVAILABLE: RapidOCR 初始化失败: %s" % e) from e
    return _OCR_ENGINE


def _phash(image_path: str) -> str:
    """感知哈希：32x32 灰度 → DCT → 左上 8x8 → 均值二值化 → 64 位十六进制。

    analyze.py 内部实现，与 feature.py 模块解耦；
    算法与 feature.compute_phash 严格一致（排除直流项阈值、cv2.dct、float32），
    保证跨模块哈希可比（状态去重依赖）。
    """
    import cv2
    img = Image.open(image_path).convert("L").resize((32, 32), Image.LANCZOS)
    arr = np.asarray(img, dtype=np.float32)
    dct = cv2.dct(arr)
    block = dct[:8, :8].flatten()
    threshold = float(block[1:].mean())  # 排除 [0,0] 直流项
    value = 0
    for v in block:
        value = (value << 1) | (1 if float(v) > threshold else 0)
    return f"{value:016x}"


# === CLI 入口 ===

def main(argv=None) -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except Exception:
            pass

    parser = argparse.ArgumentParser(
        prog=SCRIPT_NAME,
        description="界面分析：视觉 LLM + OCR 融合，输出 ScreenState JSON",
    )
    parser.add_argument("--image", required=True, help="截图路径")
    parser.add_argument("--ocr-only", action="store_true", help="仅 OCR，不调用视觉 LLM")
    parser.add_argument("--vision-model", default=None, help="覆盖 models.yaml 中的 vision.model")
    parser.add_argument("--output", default=None, help="结果 JSON 同时写入该文件")
    args = parser.parse_args(argv)

    t0 = time.time()

    def _meta(extra=None) -> dict:
        m = {
            "script": SCRIPT_NAME,
            "command": "analyze",
            "timestamp": time.time(),
            "duration_ms": int((time.time() - t0) * 1000),
            "api_version": API_VERSION,
        }
        if extra:
            m.update(extra)
        return m

    def _emit_error(code: str, message: str, exit_code: int = 1) -> int:
        print(json.dumps(
            {"status": "error", "error": code, "message": message, "meta": _meta()},
            ensure_ascii=False,
        ))
        return exit_code

    # 图片校验（提前给出精确错误码）
    if not os.path.isfile(args.image):
        return _emit_error("IMAGE_NOT_FOUND", f"图片不存在: {args.image}", 1)
    try:
        with Image.open(args.image) as im:
            im.verify()
        with Image.open(args.image) as im:
            im.load()
    except Exception as e:
        return _emit_error("IMAGE_DECODE_FAILED", f"图片解码失败: {args.image}: {e}", 1)

    # 配置：优先 CWD 相对路径，回退到项目根（脚本父目录）
    cfg_path = "config/models.yaml"
    if not os.path.exists(cfg_path):
        alt = os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
            "config", "models.yaml",
        )
        if os.path.exists(alt):
            cfg_path = alt
    cfg = load_model_config(cfg_path)
    if args.vision_model:
        cfg.setdefault("vision", {})["model"] = args.vision_model

    try:
        state = analyze_screen(args.image, model_config=cfg, ocr_only=args.ocr_only)
    except RuntimeError as e:
        msg = str(e)
        if msg.startswith("LLM_ERROR"):
            return _emit_error("LLM_ERROR", msg, 4)
        if msg.startswith("OCR_UNAVAILABLE"):
            return _emit_error("OCR_UNAVAILABLE", msg, 4)
        return _emit_error("ANALYZE_FAILED", msg, 1)
    except Exception as e:
        return _emit_error("ANALYZE_FAILED", f"分析失败: {e}", 1)

    data = state.to_dict()
    data["vision_available"] = bool(_last_availability.get("vision_available"))
    data["ocr_available"] = bool(_last_availability.get("ocr_available"))

    result = {"status": "ok", "data": data, "meta": _meta()}
    if args.output:
        out_dir = os.path.dirname(os.path.abspath(args.output))
        os.makedirs(out_dir, exist_ok=True)
        result["meta"]["output_path"] = args.output
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
        _log(f"结果已写入: {args.output}")

    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())