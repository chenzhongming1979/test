# -*- coding: utf-8 -*-
"""临时：任务面板截图分析结果摘要输出（task_survey 探测用）"""
import json
import sys

d = json.load(sys.stdin)["data"]
print("scene:", d["scene_label"])
print("vision:", d["vision_available"], "| phash:", d["phash"])
print("desc:", d["description"][:500])
print("--- elements ---")
for e in d["elements"][:30]:
    print(f"{e['text'][:32]} @ {e['center']} conf={e['confidence']} primary={e['is_primary']}")
