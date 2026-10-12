# -*- coding: utf-8 -*-
"""引导对话自动推进循环（会话工具，用后即删）"""
import sys
import time

sys.path.insert(0, "scripts")
sys.stdout.reconfigure(encoding="utf-8")
import adb
from verify import detect_guide

DEV = "emulator-5558"
for i in range(10):
    shot = f"screenshots/run_wulin_g{i:02d}.png"
    adb.screenshot(DEV, shot)
    g = detect_guide(shot)
    hint = g.get("click_hint")
    print(f"[{i}] is_guide={g['is_guide']} skip={bool(g['skip_button'])} "
          f"hint={hint} highlight={g.get('highlight', {}) and g['highlight']['bbox']}")
    if not g["is_guide"] or not hint:
        print("引导态结束")
        break
    adb.click(DEV, hint[0], hint[1])
    time.sleep(1.8)
print("done")
