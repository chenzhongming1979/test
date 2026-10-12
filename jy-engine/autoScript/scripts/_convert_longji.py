# -*- coding: utf-8 -*-
"""龙迹之城按键精灵脚本 → JY-Frame Lua 迁移转换器（临时工具）

输入: .tmp_sync/longji_mq.lua （GBK .mq 转码后的 UTF-8 文本，2314 行）
输出:
  1) games/ljzckcb/特征.lua      —— taps 区（496-2294 行）Q语言 → Lua 全量转换
  2) .tmp_sync/longji_vars_keys.txt —— 自动提取的 vars 引用键清单（校验用）
  3) 统计输出：分组数/特征行数/括号配对校验

转换规则（Q语言 → Lua）:
  - // 注释           → -- 注释（字符串内 // 不动）
  - array(...) / Array(...) → {...}（括号栈配对）
  - True/False/Null/null → true/false/nil
  - "key":value       → key=value（ASCII 键）/ ["key"]=value（中文键）
  - X(UBOUND(X)+1)=.. → X[#X+1]=..（任务行追加语法）
  - Dim X = taps["G"] → local X = taps["G"]（任务列表组别名）
不迁移：Import 外部文件、14 个自定义函数、change.freemInit 坐标适配调用（仅记录）
"""
import io
import os
import re
import sys

ENGINE_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # jy-frame-engine/
AUTO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # autoScript/
SRC = os.path.join(ENGINE_ROOT, '.tmp_sync', 'longji_mq.lua')
OUT_TAPS = os.path.join(AUTO_ROOT, 'games', 'ljzckcb', '特征.lua')
OUT_VARSKEYS = os.path.join(ENGINE_ROOT, '.tmp_sync', 'longji_vars_keys.txt')

with io.open(SRC, 'r', encoding='utf-8') as f:
    TEXT = f.read()

# 原始 .mq 自身笔误修复（已核对原始 GBK 源，非转录缺陷）
PRE_FIXES = [
    # 1) 狂暴战场任务界面[2] 行尾丢 "}" 且与下一语句粘行
    ('0.9taps["狂暴遗迹"][1]', '0.9}\ntaps["狂暴遗迹"][1]'),
    # 2) boos识别[5] zxtj 数组多一个 )
    ('"inStr","盟重"))}', '"inStr","盟重")}'),
    # 3) 战旗[1] 行尾丢 "}"
    ('"degree":0.9\n\ntaps["官印"]', '"degree":0.9}\n\ntaps["官印"]'),
]
for old, new in PRE_FIXES:
    assert old in TEXT, '修复点未找到: %r' % old
    TEXT = TEXT.replace(old, new, 1)

LINES = TEXT.split('\n')

# ---------------- 基础转换 ----------------
STRING_RE = re.compile(r'"[^"]*"')

def split_comment(line):
    """找字符串外的 //，返回 (代码段, 注释段|None)"""
    in_str = False
    i = 0
    n = len(line)
    while i < n - 1:
        c = line[i]
        if c == '"':
            in_str = not in_str
        elif not in_str and line[i] == '/' and line[i + 1] == '/':
            return line[:i], line[i + 2:]
        i += 1
    return line, None

def convert_code(code):
    """代码段转换：字符串先寄存 → 词替换 → 括号配对 → 键名转换 → 还原字符串"""
    strings = []

    def stash(m):
        strings.append(m.group(0))
        return '\x00%d\x00' % (len(strings) - 1)

    code = STRING_RE.sub(stash, code)
    # array( / Array( → {
    code = re.sub(r'(?i)\barray\s*\(', '{', code)
    # True/False/Null/null
    code = re.sub(r'\bTrue\b', 'true', code)
    code = re.sub(r'\bFalse\b', 'false', code)
    code = re.sub(r'\b(?:Null|null)\b', 'nil', code)
    # 括号配对：array 派生的 { 与 ) 配对转 }
    out = []
    depth = 0
    for c in code:
        if c == '{':
            depth += 1
            out.append(c)
        elif c == ')':
            if depth > 0:
                out.append('}')
                depth -= 1
            else:
                out.append(')')
        else:
            out.append(c)
    code = ''.join(out)
    # "key": → key= / ["key"]=
    def key_repl(m):
        idx = int(m.group(1))
        token = strings[idx]  # 含引号，如 "ff"
        content = token[1:-1]
        if re.match(r'^[A-Za-z_][A-Za-z0-9_]*$', content):
            return '%s=%s' % (content, m.group(2))
        return '[%s]=%s' % (token, m.group(2))

    code = re.sub(r'\x00(\d+)\x00(\s*):', key_repl, code)
    # 还原字符串
    def unstash(m):
        return strings[int(m.group(1))]

    code = re.sub(r'\x00(\d+)\x00', unstash, code)
    return code

APPEND_RE = re.compile(r'^(\w+)\(\s*UBOUND\(\1\s*\)\s*\+\s*1\s*\)\s*=\s*(.*)$',
                       re.IGNORECASE)
ALIAS_RE = re.compile(r'^Dim\s+(\w+)\s*=\s*(taps\[[^\]]+\])\s*$', re.IGNORECASE)

def convert_line(line):
    m = APPEND_RE.match(line)
    if m:
        return '%s[#%s + 1] = %s' % (m.group(1), m.group(1), convert_code(m.group(2)))
    m = ALIAS_RE.match(line)
    if m:
        return 'local %s = %s' % (m.group(1), m.group(2))
    code, comment = split_comment(line)
    out = convert_code(code.rstrip())
    if comment is not None:
        out = (out + ' ' if out.strip() else '') + '-- ' + comment.rstrip()
    return out

# ---------------- taps 区转换 ----------------
# 原 .mq 496..2294 行；修复 1 拆行后 +1（1337 行拆为两行，其后内容整体后移）
HEADER = '''-- =====================================================================
-- ljzckcb（贪玩·传奇 com.tanwan.ljzckcb）特征文件（taps 全量）
-- 来源：按键精灵脚本《龙迹之城》.mq 迁移（Q语言 → Lua 直译，2026-09-26）
-- 坐标基准：开发比例 {720, 1280}（竖屏，按键实测值，conf["开发比例"] 对齐）
-- 注意：
--   1) 本文件为迁移基线，特征均未经 WS pageTest 验证，逐模块启用时验证
--   2) 特征 diff 值 -101010 为按键习惯写法，验证时按实测修正
--   3) 原脚本依赖 change.freemInit 做坐标缩放适配，JY 引擎由 conf["开发比例"]
--      承担同职责，无需手动调用
--   4) 原脚本 14 个自定义函数（boosSel/mbcheck/Hero 等，经 HJG.addFun 注册，
--      被 "运行后方法"/notFun/zxtj 引用）暂未迁移，清单见迁移记录
--   5) "任务列表"/"BOOS任务"/"面板任务" 三组为调度任务编排组（任务参数语义：
--      {启动方法,执行方法,执行模式,执行间隔时间,进入任务超时时间,是否有对应设置,
--       执行次数,UIID,任务执行前操作}）
--   6) taps["手指"] 为手指哨兵约定组（JY 规则），原脚本无此组，保留占位
-- =====================================================================

local taps = {}

'''

FOOTER = '''
return taps
'''

def main():
    start = 496 - 1
    end = 2295  # 1-based inclusive；其后为 change.freemInit 调用
    body_lines = []
    stats = {'groups': 0, 'rows': 0, 'blank': 0, 'comment': 0}
    brace_errors = 0
    for raw in LINES[start:end]:
        if not raw.strip():
            body_lines.append('')
            stats['blank'] += 1
            continue
        conv = convert_line(raw)
        # 校验花括号配对（字符串感知粗检）
        code, _ = split_comment(raw)
        s = 0
        in_str = False
        for c in code:
            if c == '"':
                in_str = not in_str
            elif not in_str:
                if c in '{([':
                    s += 1
                elif c in '})]':
                    s -= 1
        if s != 0:
            brace_errors += 1
            print('括号不配对 行%d: %s' % (start + len(body_lines) + 1, raw[:80]))
        stripped = conv.strip()
        if stripped.startswith('--'):
            stats['comment'] += 1
        elif re.match(r'^taps\["[^"]+"\]\s*=\s*\{\}', stripped):
            stats['groups'] += 1
        elif re.match(r'^taps\["[^"]+"\]', stripped) or re.match(r'^\w+\[#\w+ \+ 1\]', stripped):
            stats['rows'] += 1
        body_lines.append(conv)

    # 收尾空行压缩
    while body_lines and not body_lines[-1]:
        body_lines.pop()
    # 双空行压缩为单空行
    out_lines = []
    for ln in body_lines:
        if not ln and (not out_lines or not out_lines[-1]):
            continue
        out_lines.append(ln)

    content = HEADER + '\n'.join(out_lines) + FOOTER
    with io.open(OUT_TAPS, 'w', encoding='utf-8', newline='\n') as f:
        f.write(content)
    print('特征.lua 写入完成: %s' % OUT_TAPS)
    print('统计: 分组=%d 特征行=%d 注释行=%d 空行=%d 括号异常=%d'
          % (stats['groups'], stats['rows'], stats['comment'], stats['blank'], brace_errors))

    # ---------------- vars 键提取 ----------------
    text = '\n'.join(LINES)
    keys = {}

    def add(k, src):
        keys.setdefault(k, set()).add(src)

    for m in re.finditer(r'v\["任务变量"\]\["([^"]+)"\]', text):
        add(m.group(1), '读/写(v)')
    for m in re.finditer(r'ocrWrite":\s*[Aa]rray\(\s*"vars"\s*,\s*"任务变量"\s*,\s*"([^"]+)"', text):
        add(m.group(1), 'ocrWrite')
    for m in re.finditer(r'writePoint":\s*[Aa]rray\(\s*"任务变量"\s*,\s*"([^"]+)"', text):
        add(m.group(1), 'writePoint')
    for m in re.finditer(r'wp":\s*[Aa]rray\(\s*"任务变量"\s*,\s*"([^"]+)"', text):
        add(m.group(1), 'wp')
    # 裸键写入：变量赋值":array("KEY",...) / array(1,"任务变量","KEY",...)
    for m in re.finditer(r'变量赋值":\s*[Aa]rray\(\s*(?:array|Array)?\s*\(\s*1\s*,\s*"任务变量"\s*,\s*"([^"]+)"', text):
        add(m.group(1), '赋值(类型1)')
    with io.open(OUT_VARSKEYS, 'w', encoding='utf-8') as f:
        for k in sorted(keys):
            f.write('%s\t%s\n' % (k, ','.join(sorted(keys[k]))))
    print('vars 键清单: %d 个键 → %s' % (len(keys), OUT_VARSKEYS))

if __name__ == '__main__':
    sys.exit(main())
