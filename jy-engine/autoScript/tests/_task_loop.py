# -*- coding: utf-8 -*-
"""新手任务链自动推进循环（会话工具）

模式：识别任务栏目标文本 → 点击（自动寻路）→ postclick 等待验证 →
     无目标时用引导高亮 hint 推进对话 → 循环直到链完成或卡住
"""
import sys
import time

sys.path.insert(0, "scripts")
sys.stdout.reconfigure(encoding="utf-8")
import adb
from analyze import ocr_recognize
from verify import detect_guide, postclick

DEV = "emulator-5558"
REGION = (0, 180, 340, 480)
VERBS = ("拜访", "击败", "购买", "交付", "领取", "对话", "找", "击杀")


def shot_path(i):
    return f"screenshots/run_wulin_t{i:02d}.png"


def find_target(path):
    rows = ocr_recognize(path, region=REGION) or []
    title = None
    for r in rows:
        t = r["text"]
        if "可领取" in t or "可交付" in t:
            title = ((r["bbox"][0] + r["bbox"][2]) // 2,
                     (r["bbox"][1] + r["bbox"][3]) // 2)
        for v in VERBS:
            if t.startswith(v):
                return ((r["bbox"][0] + r["bbox"][2]) // 2,
                        (r["bbox"][1] + r["bbox"][3]) // 2), t
    return title, None


def main():
    stuck = 0
    for i in range(18):
        shot = shot_path(i)
        adb.screenshot(DEV, shot)
        target, label = find_target(shot)
        g = detect_guide(shot)
        print(f"[{i:02d}] task={label} target={target} "
              f"guide={g['is_guide']} hint={g['click_hint']}", flush=True)
        point = target or g.get("click_hint")
        if point is None:
            print(">> 无任务目标且无引导，链结束")
            break
        adb.click(DEV, point[0], point[1])
        time.sleep(2)
        res = postclick(DEV, shot, max_wait_ms=20000, interval_ms=1200,
                        stable_need=2, dist_threshold=5,
                        task_region=REGION, final_shot=None)
        print(f"     -> {res['verdict']} task_changed={res['task_changed']} "
              f"mask={res['mask']['keywords']}", flush=True)
        if res["verdict"] == "no_change" and not res["guide"]["is_guide"]:
            stuck += 1
            if stuck >= 2:
                print(">> 连续无响应，停止")
                break
        else:
            stuck = 0
    print("loop done")


if __name__ == "__main__":
    main()
