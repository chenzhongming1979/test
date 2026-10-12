/**
 * 脚本运行与日志：run_script/stop/pause/resume + log 帧 → OutputChannel。
 */

import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import { Cmd, Msg } from './protocol';
import { DeviceManager } from './deviceManager';

export class ScriptRunner {
  private channel: vscode.OutputChannel;
  private running = false;
  private runningListeners: Array<(running: boolean) => void> = [];
  /** 当前日志文件句柄（配置了保存路径时启用） */
  private logStream: fs.WriteStream | null = null;
  private logDate = '';

  constructor(private device: DeviceManager) {
    this.channel = vscode.window.createOutputChannel('AutoLua 日志');
    // 订阅设备 log 帧 + 脚本状态帧
    device.on('message', (msg: { type: string; data?: any }) => {
      if (msg.type === Msg.LOG) {
        this.appendLog(String(msg.data?.line ?? ''));
      } else if (msg.type === Msg.SCRIPT_STATE) {
        this.setRunning(msg.data?.state === 'running');
      }
    });
  }

  get isDeviceRunning(): boolean { return this.running; }

  /** 运行状态变化回调（设备端 script_state 帧驱动） */
  onRunningChange(fn: (running: boolean) => void): void {
    this.runningListeners.push(fn);
  }

  private setRunning(r: boolean): void {
    const changed = this.running !== r;
    this.running = r;
    // context 每次都同步（幂等）：stop() 等直接赋值路径曾绕过这里导致按钮卡在停止态
    void vscode.commands.executeCommand('setContext', 'autolua.scriptRunning', r);
    if (changed) {
      this.appendLog(r ? '[ide] 脚本开始运行。' : '[ide] 脚本已结束（IDLE）。');
      for (const fn of this.runningListeners) {
        try { fn(r); } catch { /* listener 异常不影响主流程 */ }
      }
    }
  }

  appendLog(line: string): void {
    const withTs = vscode.workspace.getConfiguration('autolua').get<boolean>('log.timestamp', true)
      ? `[${new Date().toLocaleTimeString('zh-CN', { hour12: false })}] ${line}` : line;
    this.channel.appendLine(withTs);
    this.saveToFile(withTs);
  }

  /** 按配置落盘日志：目录/设备名_YYYYMMDD.log */
  private saveToFile(line: string): void {
    const dir = vscode.workspace.getConfiguration('autolua').get<string>('log.savePath', '');
    if (!dir) {
      if (this.logStream) { this.logStream.end(); this.logStream = null; }
      return;
    }
    try {
      const date = new Date().toISOString().slice(0, 10).replace(/-/g, '');
      if (!this.logStream || this.logDate !== date) {
        this.logStream?.end();
        const deviceTag = (this.device.target ?? 'device').replace(/[^\w.-]/g, '_');
        this.logDate = date;
        if (!fs.existsSync(dir)) { fs.mkdirSync(dir, { recursive: true }); }
        this.logStream = fs.createWriteStream(path.join(dir, `${deviceTag}_${date}.log`), { flags: 'a' });
      }
      this.logStream.write(line + '\n');
    } catch { /* 日志落盘失败不影响主流程 */ }
  }

  show(): void {
    this.channel.show(true);
  }

  /** 轮询等待脚本进入目标运行态（script_state 帧驱动 running 标志） */
  private async waitFor(target: boolean, timeoutMs: number): Promise<boolean> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (this.running === target) { return true; }
      await new Promise((r) => setTimeout(r, 50));
    }
    return this.running === target;
  }

  /** 运行脚本（relPath 相对工作区根，'/' 分隔；空则跑配置的入口脚本）。
   *  自愈：script_started 仅代表"提交成功"——引擎忙态（上次脚本未停止干净）会静默拒绝，
   *  此处等待真实 running 帧确认；未启动则自动 stop → 等 Idle → 重提交一次。 */
  async run(relPath?: string): Promise<void> {
    const script = relPath
      ?? (vscode.workspace.getConfiguration('autolua').get<string>('mainScript') || 'main.lua');
    const devicePath = '/' + script.replace(/\\/g, '/').replace(/^\/+/, '');
    this.show();
    this.appendLog(`[ide] 运行脚本 ${devicePath} ...`);
    try {
      for (let attempt = 1; attempt <= 2; attempt++) {
        const ret = await this.device.request(Cmd.RUN_SCRIPT, { path: devicePath }, Msg.SCRIPT_STARTED);
        if (ret?.ok === false) {
          this.appendLog('[ide] 设备端提交失败（引擎未就绪或脚本拒绝）。');
          vscode.window.showWarningMessage('脚本提交失败，详见 AutoLua 日志');
          return;
        }
        // 等待真实运行态（忙态拒绝时引擎不会有 running 帧）
        if (await this.waitFor(true, 3000)) {
          this.appendLog('[ide] 已提交主引擎执行。停止请按 F6 或点击 ⏹。');
          return;
        }
        if (attempt === 1) {
          this.appendLog('[ide] 提交未启动（疑似上次脚本未停止干净），自动恢复：stop → 等 Idle → 重试...');
          try {
            await this.device.request(Cmd.STOP_SCRIPT);
          } catch { /* 停止失败继续等状态 */ }
          await this.waitFor(false, 5000);
        }
      }
      this.appendLog('[ide] 自动恢复失败：引擎未能进入运行态，请重启 app（script/restart_app.ps1）。');
      vscode.window.showWarningMessage('脚本启动失败：引擎忙态自动恢复无效，请重启 app 后重试');
    } catch (e) {
      this.appendLog(`[ide] 运行失败: ${(e as Error).message}`);
      throw e;
    }
  }

  /** 在设备端沙箱执行一段 Lua 代码（调试片段用） */
  async execute(code: string): Promise<any> {
    return this.device.request(Cmd.EXECUTE, { code });
  }

  async stop(): Promise<void> {
    this.appendLog('[ide] 停止脚本...');
    try {
      await this.device.request(Cmd.STOP_SCRIPT);
      this.setRunning(false);   // 必须走状态中枢：直接赋值会绕过按钮 context 同步
      this.appendLog('[ide] 已停止。');
    } catch (e) {
      this.appendLog(`[ide] 停止失败: ${(e as Error).message}`);
    }
  }

  async pause(): Promise<void> {
    await this.device.request(Cmd.PAUSE_SCRIPT);
    this.appendLog('[ide] 已暂停。');
  }

  async resume(): Promise<void> {
    await this.device.request(Cmd.RESUME_SCRIPT);
    this.appendLog('[ide] 已恢复。');
  }
}
