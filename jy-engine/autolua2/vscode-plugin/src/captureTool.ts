import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import { spawn, ChildProcess } from 'child_process';

/**
 * 打开截图取色工具（抓抓，独立 Python Tkinter 窗口）。
 * 路径解析顺序：autolua.captureToolPath（绝对路径/工作区相对）→ 缺失时弹文件选择并回写配置。
 * 启动方式：pythonw 优先（免控制台黑窗），ENOENT 时回退 python；detached + unref，
 * 工具窗口随 VSCode 关闭继续存活；工具自行连接设备（adb forward 走独立端口段，不与本插件争抢）。
 */

export async function openCaptureTool(): Promise<void> {
  const tool = await resolveToolPath();
  if (!tool) { return; }
  const child = launch('pythonw', tool);
  child.on('error', () => {
    const fallback = launch('python', tool);
    fallback.on('error', (e) => {
      vscode.window.showErrorMessage(
        `启动截图取色工具失败：${e.message}（本机需安装 Python 及 pillow、websocket-client）`);
    });
  });
  child.unref();
  vscode.window.showInformationMessage(`截图取色工具已启动：${tool}`);
}

function launch(python: string, tool: string): ChildProcess {
  return spawn(python, [tool], {
    cwd: path.dirname(tool),
    env: { ...process.env, PYTHONIOENCODING: 'utf-8', PYTHONUTF8: '1' },
    detached: true,
    stdio: 'ignore',
    windowsHide: false,
  });
}

async function resolveToolPath(): Promise<string | null> {
  const cfg = vscode.workspace.getConfiguration('autolua');
  const configured = cfg.get<string>('captureToolPath') || 'tools/capture_tool.py';
  const abs = toAbs(configured);
  if (abs && fs.existsSync(abs)) { return abs; }
  const pick = await vscode.window.showWarningMessage(
    `未找到截图取色工具（capture_tool.py）：${configured}`,
    '选择文件…', '取消');
  if (pick !== '选择文件…') { return null; }
  const uris = await vscode.window.showOpenDialog({
    canSelectMany: false,
    filters: { 'Python 脚本': ['py'] },
    title: '选择 capture_tool.py',
    defaultUri: abs ? vscode.Uri.file(path.dirname(abs)) : undefined,
  });
  if (!uris || !uris[0]) { return null; }
  const chosen = uris[0].fsPath;
  await cfg.update('captureToolPath', chosen, vscode.ConfigurationTarget.Global);
  return chosen;
}

function toAbs(p: string): string | null {
  if (path.isAbsolute(p)) { return path.normalize(p); }
  const ws = vscode.workspace.workspaceFolders?.[0];
  if (!ws) { return null; }
  return path.normalize(path.join(ws.uri.fsPath, p));
}
