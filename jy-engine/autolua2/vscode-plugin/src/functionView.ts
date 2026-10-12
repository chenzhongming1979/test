/**
 * 函数文档树视图：分组 → 函数，点击节点插入调用模板到当前编辑器，
 * inline 按钮：插入模板 / 查看详情；面板标题搜索命令（QuickPick 过滤）。
 */

import * as vscode from 'vscode';
import { LUA_API_DOCS, LuaApiDoc } from './luaApiData';

export class ApiGroupItem extends vscode.TreeItem {
  constructor(readonly group: string, readonly funcs: LuaApiDoc[]) {
    super(group, vscode.TreeItemCollapsibleState.Expanded);
    this.contextValue = 'apiGroup';
    this.iconPath = new vscode.ThemeIcon('symbol-module');
  }
}

export class ApiFuncItem extends vscode.TreeItem {
  constructor(readonly doc: LuaApiDoc) {
    // 列表主显示=label（功能族_变体，如"多点找色_首点"，无 label 回退 desc），
    // 函数名退为副显示；名称/签名/模板完整信息在 tooltip 与详情里
    super(doc.label ?? doc.desc, vscode.TreeItemCollapsibleState.None);
    this.contextValue = 'apiFunc';
    this.description = doc.name;
    this.iconPath = new vscode.ThemeIcon('symbol-function');
    this.tooltip = new vscode.MarkdownString(
      `**${doc.name}** ${doc.signature}\n\n${doc.desc}\n\n\`\`\`lua\n${doc.template}\n\`\`\`\n\n点击插入模板`,
    );
    this.command = { command: 'autolua.api.insert', title: '插入函数模板', arguments: [this] };
  }
}

export class ApiProvider implements vscode.TreeDataProvider<vscode.TreeItem> {
  private _onDidChange = new vscode.EventEmitter<vscode.TreeItem | undefined>();
  readonly onDidChangeTreeData = this._onDidChange.event;

  /** 搜索关键字（空=全部） */
  filter = '';

  getTreeItem(item: vscode.TreeItem): vscode.TreeItem { return item; }

  getChildren(el?: vscode.TreeItem): vscode.TreeItem[] {
    if (!el) {
      const kw = this.filter.toLowerCase();
      const groups = new Map<string, LuaApiDoc[]>();
      for (const doc of LUA_API_DOCS) {
        if (kw
          && !doc.name.toLowerCase().includes(kw)
          && !doc.desc.toLowerCase().includes(kw)
          && !doc.group.toLowerCase().includes(kw)) {
          continue;
        }
        if (!groups.has(doc.group)) { groups.set(doc.group, []); }
        groups.get(doc.group)!.push(doc);
      }
      return [...groups.entries()].map(([g, funcs]) => new ApiGroupItem(g, funcs));
    }
    if (el instanceof ApiGroupItem) {
      return el.funcs.map((d) => new ApiFuncItem(d));
    }
    return [];
  }

  refresh(): void {
    this._onDidChange.fire(undefined);
  }
}

/** 把函数模板插入当前编辑器光标处（缩进对齐当前行） */
export async function insertApiTemplate(item?: ApiFuncItem): Promise<void> {
  let doc: LuaApiDoc | undefined = item?.doc;
  if (!doc) {
    doc = await pickApiDoc('选择要插入的函数');
    if (!doc) { return; }
  }
  const editor = vscode.window.activeTextEditor;
  if (!editor || editor.document.languageId !== 'lua') {
    // 无 Lua 编辑器也允许：打开提示
    throw new Error('请先打开一个 Lua 文件再插入模板');
  }
  // 对齐当前行缩进
  const line = editor.document.lineAt(editor.selection.active);
  const indent = line.text.match(/^\s*/)?.[0] ?? '';
  const snippet = doc.template.split('\n').map((l, i) => (i === 0 ? l : indent + l)).join('\n');
  await editor.insertSnippet(new vscode.SnippetString(snippet));
}

/** 弹出函数详情（签名+说明+模板），可直接插入 */
export async function showApiDetail(item?: ApiFuncItem): Promise<void> {
  const doc = item?.doc ?? await pickApiDoc('查看函数详情');
  if (!doc) { return; }
  const act = await vscode.window.showInformationMessage(
    `${doc.signature}`, { modal: false, detail: `${doc.desc}\n\n模板：\n${doc.template}` },
    '插入到编辑器', '关闭',
  );
  if (act === '插入到编辑器') {
    await insertApiTemplate(new ApiFuncItem(doc));
  }
}

/** QuickPick 搜索函数（含中文描述匹配） */
export async function searchApi(): Promise<void> {
  const doc = await pickApiDoc('搜索函数（输入名称或描述关键字）');
  if (doc) {
    await showApiDetail(new ApiFuncItem(doc));
  }
}

async function pickApiDoc(placeHolder: string): Promise<LuaApiDoc | undefined> {
  const pick = await vscode.window.showQuickPick(
    LUA_API_DOCS.map((d) => ({
      label: `$(symbol-function) ${d.name}`,
      description: d.signature,
      detail: `[${d.group}] ${d.desc}`,
      doc: d,
    })),
    { placeHolder, matchOnDescription: true, matchOnDetail: true },
  );
  return pick?.doc;
}
