# -*- coding: utf-8 -*-
"""合成图自检：quest-progress / dialog-detect / popup-classify 正例"""
import io
import json
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
sys.path.insert(0, "scripts")

from PIL import Image, ImageDraw, ImageFont
from verify import classify_popup, detect_dialog, detect_quest_progress


def make(path, lines):
    img = Image.new("RGB", (720, 1280), (240, 240, 240))
    d = ImageDraw.Draw(img)
    try:
        font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 36)
    except OSError:
        font = ImageFont.load_default()
    y = 60
    for ln in lines:
        d.text((40, y), ln, fill=(20, 20, 20), font=font)
        y += 70
    img.save(path)


make("screenshots/_synth_quest.png", ["击败野狼 3/5", "采集草药 0 / 10"])
make("screenshots/_synth_dialog.png", ["任务对话文本..."] + [""] * 12 + ["继续"])
make("screenshots/_synth_popup.png", ["恭喜获得奖励", "立即领取", "取消"])

r1 = detect_quest_progress("screenshots/_synth_quest.png")
r2 = detect_dialog("screenshots/_synth_dialog.png")
r3 = classify_popup("screenshots/_synth_popup.png")
print(json.dumps({"quest": r1, "dialog": r2, "popup": r3}, ensure_ascii=False))

assert r1["has_progress"] and r1["progress"]["cur"] == 3 and r1["progress"]["total"] == 5, "quest 解析失败"
assert r2["is_dialog"] and r2["click_hint"], "dialog 检测失败"
assert r3["detected"] and r3["category"] == "reward" and r3["click_hint"], "popup 分类失败"
print("self-check passed")
