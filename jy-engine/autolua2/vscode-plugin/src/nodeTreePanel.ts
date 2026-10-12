/**
 * 节点树抓取面板：
 * - 抓取：优先经设备 execute 通道（Cmd.EXECUTE → 引擎取回 _WS_RESULT）执行
 *   `getNodeXml([includeInvisible])` 拿 XML；未连接/失败时降级（剪贴板 / 本地 .xml 文件）。
 * - 面板：WebView 单例。左侧树形展示（折叠/过滤/纯文本标记），右侧全部属性 +
 *   两个"生成选择器"按钮（懒人式 / UiSelector 式，复制到剪贴板）。
 * - XML 契约：<nodes w h><n i d c t de id p b bp ck cc ch en fo fc sc lc se ed vt pw do ...>
 *   布尔 0/1，文本已 XML 转义，子节点嵌套。
 */

import * as vscode from 'vscode';
import * as fs from 'fs';
import { Cmd, Msg } from './protocol';
import { DeviceManager } from './deviceManager';

// ------------------------------------------------------------------
// XML 解析
// ------------------------------------------------------------------

export interface LuaNode {
  /** 唯一 key（根 '0'，子 '0.1.2' = 路径索引） */
  key: string;
  /** 相对根的层级（根=0） */
  depth: number;
  /** 全部原始属性（值已做 XML 反转义） */
  attrs: Record<string, string>;
  children: LuaNode[];
}

export interface NodeTree {
  w: string;
  h: string;
  root: LuaNode | null;
  /** key → 节点（选择器生成用，不下发 webview） */
  byKey: Map<string, LuaNode>;
  parentKey: Map<string, string>;
  count: number;
}

const TAG_RE = /<(\/?)([A-Za-z_][\w.:-]*)((?:"[^"]*"|'[^']*'|[^>"'])*?)(\/?)>/g;
const ATTR_RE = /([\w.:-]+)\s*=\s*"([^"]*)"/g;

/** XML 反转义（含数字字符引用），单遍扫描避免 &amp; 双重展开 */
function xmlUnescape(s: string): string {
  if (!s.includes('&')) { return s; }
  return s.replace(/&(amp|lt|gt|quot|apos|#x?[0-9a-fA-F]+);/g, (all, ent: string) => {
    switch (ent) {
      case 'amp': return '&';
      case 'lt': return '<';
      case 'gt': return '>';
      case 'quot': return '"';
      case 'apos': return "'";
      default:
        try {
          if (/^#x/i.test(ent)) { return String.fromCodePoint(parseInt(ent.slice(2), 16)); }
          return String.fromCodePoint(parseInt(ent.slice(1), 10));
        } catch {
          return all;
        }
    }
  });
}

/** 解析 getNodeXml 输出。畸形片段宽容处理（忽略未知标签/多余根） */
export function parseNodeXml(xml: string): NodeTree {
  const byKey = new Map<string, LuaNode>();
  const parentKey = new Map<string, string>();
  const stack: LuaNode[] = [];
  let root: LuaNode | null = null;
  let w = '';
  let h = '';
  let count = 0;

  TAG_RE.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = TAG_RE.exec(xml)) !== null) {
    const closing = m[1] === '/';
    const name = m[2].toLowerCase();
    const attrSrc = m[3];
    const selfClose = m[0].endsWith('/>');
    if (closing) {
      if (name === 'n' && stack.length > 0) { stack.pop(); }
      continue;
    }
    const attrs: Record<string, string> = {};
    ATTR_RE.lastIndex = 0;
    let am: RegExpExecArray | null;
    while ((am = ATTR_RE.exec(attrSrc)) !== null) {
      attrs[am[1]] = xmlUnescape(am[2]);
    }
    if (name === 'nodes') {
      w = attrs['w'] ?? '';
      h = attrs['h'] ?? '';
      continue;
    }
    if (name !== 'n') { continue; }
    const parent = stack.length > 0 ? stack[stack.length - 1] : null;
    if (!parent && root) { continue; } // 契约只允许一个根，多余根忽略
    const node: LuaNode = {
      key: parent ? `${parent.key}.${parent.children.length}` : '0',
      depth: parent ? parent.depth + 1 : 0,
      attrs,
      children: [],
    };
    count++;
    byKey.set(node.key, node);
    if (parent) {
      parent.children.push(node);
      parentKey.set(node.key, parent.key);
    } else {
      root = node;
    }
    if (!selfClose) { stack.push(node); }
  }
  return { w, h, root, byKey, parentKey, count };
}

// ------------------------------------------------------------------
// 选择器生成
// ------------------------------------------------------------------

export interface SelectorResult {
  text: string;
  hint?: string;
}

type CondKind = 'id' | 'text' | 'de' | 'cls';

interface Cond {
  kind: CondKind;
  value: string;
  /** 距目标节点的层数（0=目标自身） */
  dft: number;
}

/** 属性优先级：id > text > desc > className */
const COND_PRIORITY: Record<CondKind, number> = { id: 0, text: 1, de: 2, cls: 3 };
/** 条件 kind → XML 属性名 */
const COND_ATTR: Record<CondKind, string> = { id: 'id', text: 't', de: 'de', cls: 'c' };
/** 条件 kind → Lua 链式函数名 */
const COND_FN: Record<CondKind, string> = { id: 'id', text: 'text', de: 'desc', cls: 'className' };

function luaEscape(s: string): string {
  return s
    .replace(/\\/g, '\\\\')
    .replace(/"/g, '\\"')
    .replace(/\r\n|\r|\n/g, '\\n');
}

/** 遍历全树，收集满足单条件的节点 key 集合 */
function matchSet(tree: NodeTree, cond: Cond): Set<string> {
  const attr = COND_ATTR[cond.kind];
  const out = new Set<string>();
  const walk = (n: LuaNode): void => {
    if ((n.attrs[attr] ?? '') === cond.value) { out.add(n.key); }
    for (const c of n.children) { walk(c); }
  };
  if (tree.root) { walk(tree.root); }
  return out;
}

/**
 * 从根到目标节点的路径上挑"能唯一定位的最少条件"组合：
 * 依次尝试 1/2/3 个条件的组合（候选按"近目标优先、属性优先级排序"），
 * 在全树中验证交集恰为目标节点（目标满足所有候选条件，故交集=1 即唯一且为目标）。
 */
export function buildSelector(tree: NodeTree, targetKey: string, style: 'lazy' | 'ui'): SelectorResult | null {
  const target = tree.byKey.get(targetKey);
  if (!target || !tree.root) { return null; }

  // 根 → 目标路径
  const path: LuaNode[] = [];
  let cur: LuaNode | undefined = target;
  while (cur) {
    path.push(cur);
    const pk = tree.parentKey.get(cur.key);
    cur = pk !== undefined ? tree.byKey.get(pk) : undefined;
  }
  path.reverse();

  // 候选条件（近目标优先、同层按 id>text>de>clazz），限制规模防组合爆炸
  const cands: Cond[] = [];
  const maxDft = path.length - 1;
  for (let i = 0; i < path.length; i++) {
    const dft = maxDft - i;
    for (const kind of ['id', 'text', 'de', 'cls'] as const) {
      const v = path[i].attrs[COND_ATTR[kind]] ?? '';
      if (v) { cands.push({ kind, value: v, dft }); }
    }
  }
  cands.sort((a, b) => a.dft - b.dft || COND_PRIORITY[a.kind] - COND_PRIORITY[b.kind]);
  const limited = cands.slice(0, 48);
  const sets = limited.map((c) => matchSet(tree, c));

  const interSize = (idxs: number[]): number => {
    const sorted = [...idxs].sort((a, b) => sets[a].size - sets[b].size);
    let inter = sets[sorted[0]];
    for (let i = 1; i < sorted.length; i++) {
      const nxt = new Set<string>();
      for (const k of inter) {
        if (sets[sorted[i]].has(k)) { nxt.add(k); }
      }
      inter = nxt;
      if (inter.size === 0) { return 0; }
    }
    return inter.size;
  };

  /** 按候选顺序找第一个满足唯一性的 k 元组合 */
  const choose = (k: number): number[] | null => {
    const idxs: number[] = [];
    const dfs = (start: number): number[] | null => {
      if (idxs.length === k) { return interSize(idxs) === 1 ? [...idxs] : null; }
      for (let i = start; i < limited.length; i++) {
        idxs.push(i);
        const r = dfs(i + 1);
        if (r) { return r; }
        idxs.pop();
      }
      return null;
    };
    return dfs(0);
  };

  let chosen: number[] | null = null;
  for (const k of [1, 2, 3]) {
    chosen = choose(k);
    if (chosen) { break; }
  }

  let conds: Cond[];
  let hint: string | undefined;
  if (chosen) {
    conds = chosen.map((i) => limited[i]);
  } else {
    // 兜底：目标自身前 3 个条件（未验证唯一性，提示用户）
    conds = limited.filter((c) => c.dft === 0).slice(0, 3);
    hint = '未能验证唯一性，可手动增删条件';
  }
  // 输出顺序：父级条件在前、目标自身在后（贴合"从外到内"阅读顺序）
  conds.sort((a, b) => b.dft - a.dft || COND_PRIORITY[a.kind] - COND_PRIORITY[b.kind]);
  if (!hint && conds.some((c) => (c.kind === 'text' || c.kind === 'de') && /\d/.test(c.value) && c.value.length >= 4)) {
    hint = '文本含数字（可能动态变化），必要时可改用 textContains/descContains 截取固定部分';
  }

  const parts = conds.map((c) => `${COND_FN[c.kind]}("${luaEscape(c.value)}")`);
  const text = style === 'ui' ? `UiSelector():${parts.join(':')}` : parts.join(':');
  return { text, hint };
}

// ------------------------------------------------------------------
// 设备抓取与降级
// ------------------------------------------------------------------

/**
 * 经 execute 通道在设备上执行 getNodeXml() 取 XML。
 * 引擎约定：脚本把结果写入全局 _WS_RESULT，回包 data 即该字符串；
 * 引擎未实现（nil 全局调用）时回 error 回包（__ERROR:attempt to call a nil value...）。
 */
export async function fetchNodeXml(device: DeviceManager, includeInvisible: boolean): Promise<string> {
  const code = `_WS_RESULT = getNodeXml(${includeInvisible ? 'true' : 'false'})`;
  const ret = await device.request(Cmd.EXECUTE, { code }, Msg.RESULT, 30_000);
  const xml = typeof ret === 'string' ? ret : typeof ret?.result === 'string' ? ret.result : null;
  if (!xml || !xml.includes('<nodes')) {
    throw new Error('设备未返回节点树 XML（引擎可能未实现 getNodeXml，请更新引擎版本）');
  }
  return xml;
}

/** 降级路径：QuickPick 选择剪贴板 / 本地 .xml 文件 */
export async function grabNodeXmlFallback(): Promise<string | null> {
  const pick = await vscode.window.showQuickPick(
    [
      { label: '从剪贴板读取 XML', detail: '先在设备上执行 getNodeXml() 并复制结果' },
      { label: '选择本地 .xml 文件', detail: '读取之前保存的节点树文件' },
    ],
    { placeHolder: '可先在设备上执行 getNodeXml() 复制结果，再从剪贴板读取' },
  );
  if (!pick) { return null; }
  let text: string;
  if (pick.label.startsWith('从剪贴板')) {
    text = await vscode.env.clipboard.readText();
  } else {
    const files = await vscode.window.showOpenDialog({
      canSelectMany: false,
      filters: { XML: ['xml'] },
      title: '选择节点树 XML 文件',
    });
    if (!files || files.length === 0) { return null; }
    text = await fs.promises.readFile(files[0].fsPath, 'utf8');
  }
  if (!text.includes('<nodes')) {
    vscode.window.showWarningMessage('内容不是节点树 XML（缺少 <nodes 根节点）');
    return null;
  }
  return text;
}

// ------------------------------------------------------------------
// WebView 面板（单例）
// ------------------------------------------------------------------

type RefetchFn = (includeInvisible: boolean) => Promise<{ xml: string; source: string }>;

export class NodeTreePanel implements vscode.Disposable {
  /** includeInvisible 开关（命令与面板共享，会话内记忆） */
  static includeInvisible = false;

  private static current: NodeTreePanel | undefined;

  private readonly panel: vscode.WebviewPanel;
  private refetch: RefetchFn | undefined;
  private loaded = false;
  private pending: { xml: string; source: string } | null = null;
  private tree: NodeTree | null = null;
  private disposables: vscode.Disposable[] = [];

  private constructor(panel: vscode.WebviewPanel, refetch?: RefetchFn) {
    this.panel = panel;
    this.refetch = refetch;
    this.disposables.push(
      panel.webview.onDidReceiveMessage((m) => { void this.onMessage(m); }),
      panel.onDidDispose(() => this.dispose()),
    );
  }

  /** 打开（或复用）节点树面板并渲染 XML */
  static show(subs: vscode.Disposable[], xml: string, source: string, refetch?: RefetchFn): void {
    if (NodeTreePanel.current) {
      const cur = NodeTreePanel.current;
      if (refetch) { cur.refetch = refetch; }
      cur.panel.reveal(vscode.ViewColumn.Active);
      cur.accept(xml, source);
      return;
    }
    const panel = vscode.window.createWebviewPanel('autoluaNodeTree', 'AutoLua 节点树', vscode.ViewColumn.Active, {
      enableScripts: true,
      retainContextWhenHidden: true,
    });
    const inst = new NodeTreePanel(panel, refetch);
    NodeTreePanel.current = inst;
    subs.push(inst);
    inst.accept(xml, source);
  }

  private accept(xml: string, source: string): void {
    if (!this.loaded) {
      this.pending = { xml, source };
      return;
    }
    void this.publish(xml, source);
  }

  private async publish(xml: string, source: string): Promise<void> {
    try {
      this.tree = parseNodeXml(xml);
    } catch (e) {
      await this.notify(false, `XML 解析失败: ${(e as Error).message}`);
      return;
    }
    await this.panel.webview.postMessage({
      type: 'tree',
      // byKey/parentKey 是 Map，不下发；webview 自行按 root 建索引
      tree: { w: this.tree.w, h: this.tree.h, root: this.tree.root },
      total: this.tree.count,
      source,
      includeInvisible: NodeTreePanel.includeInvisible,
    });
  }

  private async notify(ok: boolean, text: string, hint?: string): Promise<void> {
    await this.panel.webview.postMessage({ type: 'notice', ok, text, hint });
  }

  private async setBusy(on: boolean): Promise<void> {
    await this.panel.webview.postMessage({ type: 'busy', on });
  }

  private async onMessage(m: { type: string; key?: string; style?: string; value?: boolean }): Promise<void> {
    switch (m.type) {
      case 'ready': {
        this.loaded = true;
        if (this.pending) {
          const p = this.pending;
          this.pending = null;
          await this.publish(p.xml, p.source);
        }
        return;
      }
      case 'refresh': {
        await this.doRefetch();
        return;
      }
      case 'toggleInvisible': {
        NodeTreePanel.includeInvisible = !!m.value;
        await this.doRefetch();
        return;
      }
      case 'genSelector': {
        if (!this.tree || !m.key) {
          await this.notify(false, '尚无节点树数据');
          return;
        }
        const sel = buildSelector(this.tree, m.key, m.style === 'ui' ? 'ui' : 'lazy');
        if (!sel) {
          await this.notify(false, '未找到该节点');
          return;
        }
        await vscode.env.clipboard.writeText(sel.text);
        await this.notify(true, `已复制：${sel.text}`, sel.hint);
        return;
      }
      default:
        return;
    }
  }

  private async doRefetch(): Promise<void> {
    if (!this.refetch) {
      await this.notify(false, '当前数据来自本地导入，无设备抓取通道；连接设备后重新执行「抓取节点树」即可刷新。');
      return;
    }
    try {
      await this.setBusy(true);
      const r = await this.refetch(NodeTreePanel.includeInvisible);
      await this.publish(r.xml, r.source);
    } catch (e) {
      const msg = (e as Error).message ?? String(e);
      await this.notify(false, msg.includes('设备未连接') ? '设备未连接，请先连接设备。' : `抓取失败: ${msg}`);
    } finally {
      await this.setBusy(false);
    }
  }

  dispose(): void {
    NodeTreePanel.current = undefined;
    for (const d of this.disposables) { d.dispose(); }
    this.disposables = [];
    this.panel.dispose();
  }

  // ----------------------------------------------------------------
  // WebView HTML（原生 HTML/JS/CSS 单文件，深浅色跟随 --vscode-* 变量）
  // ----------------------------------------------------------------

  private getHtml(): string {
    return webviewHtml(getNonce());
  }
}

function getNonce(): string {
  let text = '';
  const possible = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  for (let i = 0; i < 32; i++) {
    text += possible.charAt(Math.floor(Math.random() * possible.length));
  }
  return text;
}

/** WebView 单文件页面：原生 HTML/JS/CSS，深浅色全部走 --vscode-* 变量 */
function webviewHtml(nonce: string): string {
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}';">
<style>
* { box-sizing: border-box; }
html, body { height: 100%; margin: 0; }
body {
  display: flex; flex-direction: column;
  background: var(--vscode-editor-background);
  color: var(--vscode-editor-foreground);
  font-family: var(--vscode-font-family);
  font-size: var(--vscode-font-size, 13px);
}
#toolbar {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 6px 10px;
  border-bottom: 1px solid var(--vscode-editorWidget-border, rgba(128,128,128,.3));
}
button {
  background: var(--vscode-button-background); color: var(--vscode-button-foreground);
  border: none; padding: 4px 12px; cursor: pointer; font-size: 13px;
}
button:disabled { opacity: .6; cursor: default; }
button.sec {
  background: var(--vscode-button-secondaryBackground, var(--vscode-button-background));
  color: var(--vscode-button-secondaryForeground, var(--vscode-button-foreground));
}
#filterInput {
  flex: 1; min-width: 160px;
  background: var(--vscode-input-background); color: var(--vscode-input-foreground);
  border: 1px solid var(--vscode-input-border, transparent);
  padding: 3px 8px; outline: none;
}
.chk { display: flex; align-items: center; gap: 4px; white-space: nowrap; cursor: pointer; }
#stat, #src { color: var(--vscode-descriptionForeground); white-space: nowrap; }
#src { overflow: hidden; text-overflow: ellipsis; max-width: 280px; }
#busy { color: var(--vscode-editorWarning-foreground, #e2c08d); white-space: nowrap; }
#main { flex: 1; display: flex; min-height: 0; }
#treePane { flex: 1.25; overflow: auto; padding: 4px 0; }
#detailPane {
  flex: 1; overflow: auto; padding: 8px 12px;
  border-left: 1px solid var(--vscode-editorWidget-border, rgba(128,128,128,.3));
}
.row {
  display: flex; align-items: center; gap: 6px;
  padding: 1px 8px; line-height: 1.55;
  white-space: nowrap; cursor: pointer;
}
.row:hover { background: var(--vscode-list-hoverBackground); }
.row.sel {
  background: var(--vscode-list-activeSelectionBackground, rgba(0,120,215,.4));
  color: var(--vscode-list-activeSelectionForeground, inherit);
}
.row.dim { opacity: .55; }
.tw { display: inline-block; width: 12px; flex: none; color: var(--vscode-descriptionForeground); }
.cls { flex: none; color: var(--vscode-descriptionForeground); }
.main { overflow: hidden; text-overflow: ellipsis; }
.marks { flex: none; font-size: 11px; color: var(--vscode-terminal-ansiYellow, #ddb100); }
.bnd { flex: none; margin-left: auto; padding-left: 10px; font-size: 11px; color: var(--vscode-descriptionForeground); }
mark {
  background: var(--vscode-editor-findMatchHighlightBackground, rgba(234,196,65,.4));
  color: inherit; padding: 0;
}
.empty { color: var(--vscode-descriptionForeground); padding: 20px; }
.detailHead { font-weight: 600; margin-bottom: 6px; }
.kv { display: flex; gap: 8px; padding: 1px 0; align-items: baseline; }
.kv .k { flex: none; min-width: 150px; font-size: 12px; color: var(--vscode-descriptionForeground); }
.kv .v { word-break: break-all; user-select: text; }
.btns { display: flex; gap: 8px; margin: 10px 0 8px; }
#selBox { margin-top: 8px; }
.ok { color: var(--vscode-terminal-ansiGreen, #23d18b); word-break: break-all; user-select: text; }
.err { color: var(--vscode-errorForeground, #f48771); word-break: break-all; }
.hint { margin-top: 4px; font-size: 12px; color: var(--vscode-descriptionForeground); }
</style>
</head>
<body>
<div id="toolbar">
  <button id="btnRefresh" title="重新从设备抓取节点树">刷新</button>
  <label class="chk" title="勾选后执行 getNodeXml(true)，包含不可见节点"><input type="checkbox" id="chkInvisible">includeInvisible</label>
  <input id="filterInput" type="text" placeholder="过滤：id / text / desc / className（前端过滤并高亮）" spellcheck="false">
  <span id="stat"></span>
  <span id="busy"></span>
  <span id="src"></span>
</div>
<div id="main">
  <div id="treePane"><div class="empty">等待节点树数据…</div></div>
  <div id="detailPane"><div class="empty">点击左侧节点查看属性</div></div>
</div>
<script nonce="${nonce}">
'use strict';
var vscode = acquireVsCodeApi();
var tree = null;
var total = 0;
var byKey = {};
var parentOf = {};
var collapsed = {};
var selectedKey = null;
var filterText = '';
var hitCache = {};

var ATTR_ORDER = ['i','d','c','t','de','id','p','b','bp','ck','cc','ch','en','fo','fc','sc','lc','se','ed','vt','pw','do'];
var ATTR_LABEL = {
  i:'index', d:'depth', c:'className', t:'text', de:'contentDescription', id:'viewId',
  p:'packageName', b:'boundsInScreen', bp:'boundsInParent',
  ck:'clickable', cc:'checkable', ch:'checked', en:'enabled', fo:'focusable', fc:'focused',
  sc:'scrollable', lc:'longClickable', se:'selected', ed:'editable', vt:'visibleToUser',
  pw:'password', do:'drawingOrder'
};

function esc(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
/** 过滤命中子串高亮（先分段再转义，避免实体错位） */
function hlText(raw) {
  raw = String(raw == null ? '' : raw);
  if (!filterText) { return esc(raw); }
  var i = raw.toLowerCase().indexOf(filterText.toLowerCase());
  if (i < 0) { return esc(raw); }
  return esc(raw.slice(0, i)) + '<mark>' + esc(raw.slice(i, i + filterText.length)) + '</mark>' + esc(raw.slice(i + filterText.length));
}
function nodeMatches(n) {
  if (!filterText) { return true; }
  var f = filterText.toLowerCase();
  var a = n.attrs || {};
  return (a.id || '').toLowerCase().indexOf(f) >= 0
    || (a.t || '').toLowerCase().indexOf(f) >= 0
    || (a.de || '').toLowerCase().indexOf(f) >= 0
    || (a.c || '').toLowerCase().indexOf(f) >= 0;
}
/** 过滤模式下：key → 该节点子树（含自身）是否有匹配，渲染时只保留匹配链 */
function buildHitCache() {
  hitCache = {};
  if (!filterText || !tree || !tree.root) { return; }
  (function walk(n) {
    var hit = nodeMatches(n);
    for (var i = 0; i < n.children.length; i++) {
      if (walk(n.children[i])) { hit = true; }
    }
    hitCache[n.key] = hit;
    return hit;
  })(tree.root);
}
function buildIndex() {
  byKey = {}; parentOf = {}; collapsed = {}; selectedKey = null;
  if (!tree || !tree.root) { return; }
  (function walk(n, pk) {
    byKey[n.key] = n;
    parentOf[n.key] = pk;
    if (n.depth >= 3) { collapsed[n.key] = true; } // 初始展开前 3 层
    for (var i = 0; i < n.children.length; i++) { walk(n.children[i], n.key); }
  })(tree.root, null);
}
function shortId(id) {
  if (!id) { return ''; }
  var p = id.split(':');
  return p.length > 1 ? p[p.length - 1] : id;
}
function marks(n) {
  var a = n.attrs || {};
  var m = '';
  if (a.ck === '1') { m += '[C]'; }
  if (a.sc === '1') { m += '[S]'; }
  if (a.lc === '1') { m += '[L]'; }
  if (a.ed === '1') { m += '[E]'; }
  if (a.vt === '0') { m += '[H]'; }
  return m;
}
function render() {
  var out = [];
  if (tree && tree.root) { renderNode(tree.root, out); }
  document.getElementById('treePane').innerHTML =
    out.length ? out.join('') : '<div class="empty">' + (tree ? '无匹配节点' : '等待节点树数据…') + '</div>';
  document.getElementById('stat').textContent = tree ? ('节点 ' + total) : '';
  renderDetail();
}
function renderNode(n, out) {
  if (filterText && !hitCache[n.key]) { return; }
  var a = n.attrs || {};
  var clsTail = (a.c || '').split('.').pop() || '?';
  var main = a.t || a.de || shortId(a.id) || clsTail;
  var mk = marks(n);
  var tw = n.children.length ? ((collapsed[n.key] && !filterText) ? '▸' : '▾') : '';
  out.push('<div class="row' + (n.key === selectedKey ? ' sel' : '') + (a.vt === '0' ? ' dim' : '')
    + '" data-key="' + n.key + '" style="padding-left:' + (8 + n.depth * 14) + 'px">'
    + '<span class="tw">' + tw + '</span>'
    + '<span class="cls">' + hlText(clsTail) + '</span>'
    + '<span class="main">' + hlText(main) + '</span>'
    + (mk ? '<span class="marks">' + mk + '</span>' : '')
    + '<span class="bnd">' + hlText(a.b || '') + '</span>'
    + '</div>');
  if (!filterText && collapsed[n.key]) { return; }
  for (var i = 0; i < n.children.length; i++) { renderNode(n.children[i], out); }
}
function renderDetail() {
  var pane = document.getElementById('detailPane');
  var n = selectedKey ? byKey[selectedKey] : null;
  if (!n) {
    pane.innerHTML = '<div class="empty">点击左侧节点查看属性</div>';
    return;
  }
  var a = n.attrs || {};
  var rows = ['<div class="kv"><span class="k">key（路径）</span><span class="v">' + esc(n.key) + '</span></div>'];
  for (var i = 0; i < ATTR_ORDER.length; i++) {
    var k = ATTR_ORDER[i];
    if (!(k in a)) { continue; }
    rows.push('<div class="kv"><span class="k">' + esc((ATTR_LABEL[k] || k) + ' (' + k + ')') + '</span><span class="v">' + hlText(a[k]) + '</span></div>');
  }
  rows.push('<div class="kv"><span class="k">children</span><span class="v">' + n.children.length + '</span></div>');
  pane.innerHTML = '<div class="detailHead">节点属性</div>'
    + '<div class="kvList">' + rows.join('') + '</div>'
    + '<div class="btns">'
    + '<button id="btnLazy">复制懒人选择器</button>'
    + '<button id="btnUi" class="sec">复制 UiSelector 选择器</button>'
    + '</div>'
    + '<div id="selBox"></div>';
  document.getElementById('btnLazy').onclick = function () {
    vscode.postMessage({ type: 'genSelector', key: n.key, style: 'lazy' });
  };
  document.getElementById('btnUi').onclick = function () {
    vscode.postMessage({ type: 'genSelector', key: n.key, style: 'ui' });
  };
}
function showNotice(ok, text, hint) {
  var box = document.getElementById('selBox') || document.getElementById('detailPane');
  box.innerHTML = '<div class="' + (ok ? 'ok' : 'err') + '">' + esc(text) + '</div>'
    + (hint ? '<div class="hint">' + esc(hint) + '</div>' : '');
}
function setBusy(on) {
  document.getElementById('busy').textContent = on ? '抓取中…' : '';
  document.getElementById('btnRefresh').disabled = on;
}

window.addEventListener('message', function (e) {
  var m = e.data || {};
  if (m.type === 'tree') {
    tree = m.tree || null;
    total = m.total || 0;
    var src = document.getElementById('src');
    src.textContent = m.source || '';
    src.title = m.source || '';
    document.getElementById('chkInvisible').checked = !!m.includeInvisible;
    buildIndex();
    buildHitCache();
    render();
  } else if (m.type === 'notice') {
    showNotice(!!m.ok, m.text || '', m.hint);
  } else if (m.type === 'busy') {
    setBusy(!!m.on);
  }
});

document.getElementById('treePane').addEventListener('click', function (e) {
  var row = e.target.closest ? e.target.closest('.row') : null;
  if (!row) { return; }
  var key = row.getAttribute('data-key');
  if (e.target.classList && e.target.classList.contains('tw') && e.target.textContent && !filterText) {
    collapsed[key] = !collapsed[key];
    render();
    return;
  }
  selectedKey = key;
  render();
});
document.getElementById('btnRefresh').onclick = function () {
  vscode.postMessage({ type: 'refresh' });
};
document.getElementById('chkInvisible').onchange = function () {
  vscode.postMessage({ type: 'toggleInvisible', value: document.getElementById('chkInvisible').checked });
};
document.getElementById('filterInput').oninput = function () {
  filterText = document.getElementById('filterInput').value.trim();
  buildHitCache();
  render();
};

vscode.postMessage({ type: 'ready' });
</script>
</body>
</html>`;
}
