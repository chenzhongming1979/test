"""verify.py — 点击后验证器（验证层）

设计依据：点击后不应只做单一画面对比，而应区分场景：
  - 打怪/寻路类（点击后画面持续变化）：循环截图直到画面稳定，再比对任务文本变化
  - 静态界面（点按钮开面板）：一次变化检测即可
  - 遮罩/弹窗：OCR 关键词检测（跳过/确定/关闭/立即领取等）

CLI:
  verify.py postclick --device <id> --before <img> [--max-wait 20000]
        [--interval 1200] [--stable-need 2] [--dist-threshold 5]
        [--task-region x1,y1,x2,y2] [--final-shot <path>]
  verify.py compare-task --before <img> --after <img> [--task-region ...]

stdout 只输出 JSON；日志走 stderr。退出码 0 成功 / 4 外部依赖错误。
"""
from __future__ import annotations

import json
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("\\", 1)[0] if "\\" in __file__ else
                __file__.rsplit("/", 1)[0])

import adb
from feature import compute_phash
from models import hamming_distance

try:
    from analyze import ocr_recognize
except Exception:  # OCR 引擎不可用时验证器仍可工作（降级无任务对比）
    ocr_recognize = None

SCRIPT_NAME = "verify.py"

MASK_KEYWORDS = ["跳过", "点击继续", "确定", "取消", "关闭", "知道了",
                 "立即领取", "立即前往", "点击任意", "不再提示"]
DEFAULT_TASK_REGION = (0, 180, 340, 480)  # 左侧任务栏（多数 MMO 通用）

# === 执行过程检测（打怪数量/对话/弹窗分类）===

# 任务数量进度："击败野狼 3/5"、"采集 3 / 10"（兼容全角斜杠）
_PROGRESS_RE = re.compile(r"(\d+)\s*[/／]\s*(\d+)")

# 弹窗分类 → 标准处理动作（按优先级：reward > confirm > dismiss）
POPUP_CATEGORIES = {
    "reward": ["立即领取", "领取奖励", "一键领取", "立即前往", "领取"],
    "confirm": ["确定", "确认", "立即使用", "使用"],
    "dismiss": ["不再提示", "知道了", "我知道了", "关闭", "取消"],
}

# NPC 对话按钮词（底部区域命中才算对话态）
_DIALOG_BUTTONS = ("继续", "完成对话", "完成", "离开", "接受", "交付", "关闭对话")


def _log(msg: str) -> None:
    print(f"[{SCRIPT_NAME}] {msg}", file=sys.stderr, flush=True)


def _ocr_rows(path: str, region=None) -> list:
    """OCR 原始行（含 bbox/center），供位置类检测使用。失败返回空。"""
    if ocr_recognize is None:
        return []
    try:
        return ocr_recognize(path, region=region) or []
    except Exception as e:  # OCR 失败不影响验证主流程
        _log(f"OCR 失败（忽略）: {e}")
        return []


def _ocr_texts(path: str, region) -> list[str]:
    if ocr_recognize is None:
        return []
    return [r["text"] for r in _ocr_rows(path, region)]


def _norm(texts: list[str]) -> str:
    return "|".join(re.sub(r"\s", "", t) for t in texts)


def detect_mask(path: str = "", texts: list[str] | None = None) -> dict:
    """全屏 OCR 弹窗关键词检测（保守词表，主界面常驻词不入表）。"""
    hits = [kw for kw in MASK_KEYWORDS
            if any(kw in t for t in (texts if texts is not None
                                     else _ocr_texts(path, None)))]
    return {"detected": bool(hits), "keywords": hits}


def detect_guide(path: str, region=None, full_texts: list[str] | None = None) -> dict:
    """强制引导检测：跳过按钮（OCR）+ 高亮区域/光圈（CV 亮度分析）。

    返回 {is_guide, skip_button, highlight, click_hint}：
    - skip_button: OCR 命中"跳过/Skip"的文本与 bbox
    - highlight: 最亮连通域 bbox/center（引导高亮目标，遮罩下尤其明显）
    - click_hint: 建议点击坐标（跳过按钮中心 > 高亮区域中心）
    """
    import numpy as np
    from PIL import Image

    skip = None
    texts = full_texts if full_texts is not None else _ocr_texts(path, region)
    for t in texts:
        txt = re.sub(r"\s", "", str(t) if isinstance(t, str) else
                     str(t.get("text", "")))
        if "跳过" in txt or txt.lower() == "skip":
            skip = {"text": txt}
            break

    img = Image.open(path).convert("L")
    arr = np.asarray(img, dtype=np.uint8)
    h, w = arr.shape
    # 高亮目标 = 亮度阈值最大连通域（引导遮罩下目标区显著亮于四周）
    bright = arr >= 240
    highlight = None
    if bright.any():
        ys, xs = np.nonzero(bright)
        # 取最大密集簇：用分位数剔除散点噪点
        if len(xs) > 200:
            cx, cy = int(np.median(xs)), int(np.median(ys))
            # 以中位数为中心的密集窗口
            win = bright[max(0, cy - 150):cy + 150, max(0, cx - 150):cx + 150]
            wys, wxs = np.nonzero(win)
            if len(wxs) > 100:
                gx1 = max(0, cx - 150) + int(wxs.min())
                gx2 = max(0, cx - 150) + int(wxs.max())
                gy1 = max(0, cy - 150) + int(wys.min())
                gy2 = max(0, cy - 150) + int(wys.max())
                if (gx2 - gx1) < w * 0.6 and 0 <= (gx1 + gx2) // 2 < w \
                        and 0 <= (gy1 + gy2) // 2 < h:  # 排除全屏白屏/超界误报
                    highlight = {"bbox": [gx1, gy1, gx2, gy2],
                                 "center": [(gx1 + gx2) // 2, (gy1 + gy2) // 2],
                                 "bright_pixels": int(bright.sum())}
    # 对话推进态："点击任意区域继续"类提示 → 建议点屏幕中央
    tap_any = any("点击任意" in t for t in texts)
    if tap_any:
        highlight = None  # 对话态无特定高亮目标
        click_hint = [640, 360] if not skip else skip.get("center")
        return {"is_guide": True, "skip_button": skip,
                "tap_anywhere": True, "click_hint": click_hint}
    click_hint = (skip or {}).get("center") or (highlight or {}).get("center")
    return {"is_guide": bool(skip or highlight), "skip_button": skip,
            "highlight": highlight, "click_hint": click_hint}


def detect_quest_progress(path: str, region=None, rows=None) -> dict:
    """任务数量进度解析：打怪/采集类任务的循环锚点。

    在任务栏区域 OCR 中找 "击败野狼 3/5" 样式的进度文本：
    - has_progress / progress: {text, cur, total, center}（首个命中=当前主任务）
    - done: cur >= total（数量已满 → 去交任务）
    录制时判断任务是否完成；编译时生成 ocr 写变量 + 数值执行条件 out 行。
    """
    rows = rows if rows is not None else _ocr_rows(path, region)
    hits = []
    for r in rows:
        m = _PROGRESS_RE.search(str(r.get("text", "")))
        if not m:
            continue
        bx1, by1, bx2, by2 = r["bbox"]
        hits.append({"text": str(r["text"]).strip(),
                     "cur": int(m.group(1)), "total": int(m.group(2)),
                     "center": [(bx1 + bx2) // 2, (by1 + by2) // 2]})
    main = hits[0] if hits else None
    return {"has_progress": bool(hits), "progress": main,
            "all": hits[:5], "done": bool(main and main["cur"] >= main["total"])}


def detect_dialog(path: str, full_rows=None) -> dict:
    """NPC 对话框检测（区别于引导态）：屏幕底部 1/3 出现对话按钮词。

    返回 {is_dialog, buttons:[{text,bbox,center}], click_hint}：
    - click_hint = 第一个对话按钮中心（点击推进对话）
    - 对话推进多步 → 录制时重复点击直到按钮消失（编译为 ps_s + bigCount）
    """
    from PIL import Image
    with Image.open(path) as im:
        h = im.size[1]
    rows = full_rows if full_rows is not None else _ocr_rows(path, None)
    buttons = []
    for r in rows:
        txt = re.sub(r"\s", "", str(r.get("text", "")))
        if not txt or any(kw in txt for kw in ("跳过",)):
            continue  # 跳过按钮归引导检测管
        for kw in _DIALOG_BUTTONS:
            if kw in txt:
                bx1, by1, bx2, by2 = r["bbox"]
                buttons.append({"text": txt, "bbox": r["bbox"],
                                "center": [(bx1 + bx2) // 2, (by1 + by2) // 2]})
                break
    bottom = [b for b in buttons if b["center"][1] > h * 0.6]
    return {"is_dialog": bool(bottom), "buttons": bottom or buttons,
            "click_hint": bottom[0]["center"] if bottom else None}


def classify_popup(path: str = "", full_rows=None) -> dict:
    """点击后弹窗分类：按关键词类别给出标准处理动作与点击位置。

    - reward(领奖) > confirm(确认) > dismiss(关闭)，命中最优先类即返回
    - click_hint = 该类首个命中按钮中心；录制时直接按分类采样入库通用弹窗
    - "跳过/点击继续/点击任意" 归引导检测（detect_guide），此处不处理
    """
    rows = full_rows if full_rows is not None else _ocr_rows(path, None)
    result = {"detected": False, "category": None,
              "click_hint": None, "hits": []}
    for cat, keywords in POPUP_CATEGORIES.items():
        for r in rows:
            txt = re.sub(r"\s", "", str(r.get("text", "")))
            for kw in keywords:
                if kw in txt:
                    bx1, by1, bx2, by2 = r["bbox"]
                    result["hits"].append({"category": cat, "keyword": kw,
                                           "text": txt, "bbox": r["bbox"],
                                           "center": [(bx1 + bx2) // 2,
                                                      (by1 + by2) // 2]})
                    if not result["detected"]:
                        result["detected"] = True
                        result["category"] = cat
                        result["click_hint"] = result["hits"][-1]["center"]
                    break
        if result["detected"]:
            break
    return result


def compare_task(before: str, after: str, region) -> dict:
    tb, ta = _ocr_texts(before, region), _ocr_texts(after, region)
    nb, na = _norm(tb), _norm(ta)
    return {"task_changed": nb != na and bool(na),
            "task_before": tb[:8], "task_after": ta[:8]}


def postclick(device: str | None, before: str, max_wait_ms: int,
              interval_ms: int, stable_need: int, dist_threshold: int,
              task_region, final_shot: str | None) -> dict:
    """点击后循环截图：等画面稳定，输出变化/任务/遮罩综合判定。"""
    before_hash = compute_phash(before)
    t0 = time.monotonic()
    deadline = t0 + max_wait_ms / 1000.0
    prev_hash, stable_run, frames, last_path = None, 0, 0, before
    stable = False

    while True:
        last_path = f"{final_shot or before}.tmp_{frames}.png"
        adb.screenshot(device, last_path)
        cur_hash = compute_phash(last_path)
        frames += 1
        if prev_hash is not None:
            if hamming_distance(prev_hash, cur_hash) <= 1:
                stable_run += 1
                if stable_run >= stable_need:
                    stable = True
                    break
            else:
                stable_run = 0
        prev_hash = cur_hash
        if time.monotonic() >= deadline:
            break
        time.sleep(interval_ms / 1000.0)

    wait_ms = int((time.monotonic() - t0) * 1000)
    if final_shot:
        try:
            import shutil
            shutil.copyfile(last_path, final_shot)
            last_path = final_shot
        except OSError as e:
            _log(f"final_shot 保存失败（保留中间帧 {last_path}）: {e}")
    changed = hamming_distance(before_hash, compute_phash(last_path)) > dist_threshold

    result = compare_task(before, last_path, task_region)
    full_rows = _ocr_rows(last_path, None)
    full_ocr = [r["text"] for r in full_rows]
    result["mask"] = detect_mask(texts=full_ocr)
    result["guide"] = detect_guide(last_path, full_texts=full_ocr)
    result["popup"] = classify_popup(full_rows=full_rows)
    result["dialog"] = detect_dialog(last_path, full_rows=full_rows)
    result["quest"] = detect_quest_progress(last_path, region=task_region)
    result.update({
        "changed": changed, "stable": stable, "wait_ms": wait_ms,
        "frames": frames, "final_shot": last_path,
        "verdict": ("no_change" if not changed else
                    "changed_stable" if stable else "still_moving"),
    })
    return result


# === CLI ===

def main(argv=None) -> int:
    for s in (sys.stdout, sys.stderr):
        try:
            s.reconfigure(encoding="utf-8")
        except Exception:
            pass
    import argparse
    p = argparse.ArgumentParser(prog=SCRIPT_NAME)
    sub = p.add_subparsers(dest="command", required=True)

    pc = sub.add_parser("postclick")
    pc.add_argument("--device", default=None)
    pc.add_argument("--before", required=True, help="点击前截图")
    pc.add_argument("--max-wait", type=int, default=20000)
    pc.add_argument("--interval", type=int, default=1200)
    pc.add_argument("--stable-need", type=int, default=2)
    pc.add_argument("--dist-threshold", type=int, default=5)
    pc.add_argument("--task-region", default=None,
                    help="x1,y1,x2,y2 任务栏区域（真实像素）")
    pc.add_argument("--final-shot", default=None, help="最终帧保存路径")

    ct = sub.add_parser("compare-task")
    ct.add_argument("--before", required=True)
    ct.add_argument("--after", required=True)
    ct.add_argument("--task-region", default=None)

    gd = sub.add_parser("guide-detect")
    gd.add_argument("--image", required=True, help="截图路径")

    qp = sub.add_parser("quest-progress",
                        help="任务数量进度解析（打怪/采集 X/Y 锚点）")
    qp.add_argument("--image", required=True, help="截图路径")
    qp.add_argument("--task-region", default=None)

    dd = sub.add_parser("dialog-detect",
                        help="NPC 对话框检测（底部对话按钮 + click_hint）")
    dd.add_argument("--image", required=True, help="截图路径")

    pcl = sub.add_parser("popup-classify",
                         help="弹窗分类（reward/confirm/dismiss + click_hint）")
    pcl.add_argument("--image", required=True, help="截图路径")

    for sp in (pc, ct, gd, qp, dd, pcl):
        sp.add_argument("--output", default=None)

    args = p.parse_args(argv)
    region = (tuple(int(v) for v in args.task_region.split(","))
              if getattr(args, "task_region", None)
              else DEFAULT_TASK_REGION)

    started = time.time()
    try:
        if args.command == "postclick":
            data = postclick(args.device, args.before, args.max_wait,
                             args.interval, args.stable_need,
                             args.dist_threshold, region, args.final_shot)
        elif args.command == "guide-detect":
            data = detect_guide(args.image)
        elif args.command == "quest-progress":
            data = detect_quest_progress(args.image, region=region)
        elif args.command == "dialog-detect":
            data = detect_dialog(args.image)
        elif args.command == "popup-classify":
            data = classify_popup(args.image)
        else:
            data = compare_task(args.before, args.after, region)
            data["mask"] = detect_mask(args.after)
    except Exception as e:
        print(json.dumps({"status": "error", "error": "VERIFY_ERROR",
                          "message": str(e)}, ensure_ascii=False))
        return 4
    print(json.dumps({
        "status": "ok", "data": data,
        "meta": {"script": SCRIPT_NAME, "command": args.command,
                 "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
                 "duration_ms": int((time.time() - started) * 1000)},
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
