# -*- coding: utf-8 -*-
"""临时工具：按 GBK 读取 .mq，抽取指定 taps 模块定义段
用法: python tests/_read_mq.py <mq路径> <模块名...>
     python tests/_read_mq.py --grep <mq路径> <关键词>
     python tests/_read_mq.py --defs <mq路径>   列出全部模块定义行号
"""
import io
import re
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

DEFAULT = r"D:\Project\script\anjian\按键精灵手机助手3.7.9\Script\传奇\龙迹之城(9f1cad3d-549a-4b7e-b783-4a95065527b2).mq"

args = sys.argv[1:]
mode = "names"
if args and args[0] in ("--grep", "--defs"):
    mode = args[0]
    args = args[1:]
if mode == "names":
    P = args[0] if args else DEFAULT
    names = args[1:] if args else []
else:
    P = args[0] if args else DEFAULT
    names = args[1:]

lines = open(P, encoding="gbk", errors="replace").read().splitlines()

if mode == "--grep":
    kw = names[0] if names else ""
    for i, ln in enumerate(lines, 1):
        if kw in ln:
            print(i, ":", ln)
    sys.exit(0)

if mode == "--defs":
    for i, ln in enumerate(lines, 1):
        if re.match(r'\s*taps\["(.+?)"\]\s*=\s*Array\(\)', ln):
            print(i, ":", ln.strip())
    sys.exit(0)

defs = {}
for i, ln in enumerate(lines):
    m = re.match(r'\s*taps\["(.+?)"\]\s*=\s*Array\(\)', ln)
    if m:
        defs[m.group(1)] = i

names = sys.argv[1:] or ["背包", "英雄", "转生", "任务识别", "通用弹窗"]
for name in names:
    if name not in defs:
        print(f"===== {name} : 未找到 =====")
        continue
    i = defs[name]
    print(f"===== {name} (line {i+1}) =====")
    j = i + 1
    while j < len(lines) and j < i + 15:
        ln = lines[j]
        if re.match(r'\s*taps\[".+?"\]\s*=\s*Array\(\)', ln) or ln.strip().startswith("// ---"):
            break
        print(j + 1, ":", ln)
        j += 1
    print()
