/**
 * adb 集成：模拟器（NAT 网络，UDP 广播不可达）通过 adb 端口转发连接。
 * adb forward tcp:<localPort> tcp:<devicePort> 后连 127.0.0.1:<localPort>。
 */

import { spawn } from 'child_process';
import * as vscode from 'vscode';

export interface AdbDevice {
  serial: string;
  state: string;
}

function adbPath(): string {
  return vscode.workspace.getConfiguration('autolua').get<string>('adbPath') || 'adb';
}

/** 执行 adb 子命令，返回 stdout（失败抛错带 stderr） */
export function adb(args: string[], timeoutMs = 8000): Promise<string> {
  return new Promise((resolve, reject) => {
    const proc = spawn(adbPath(), args, { windowsHide: true });
    let out = '';
    let err = '';
    proc.stdout.on('data', (d: Buffer) => { out += d.toString('utf8'); });
    proc.stderr.on('data', (d: Buffer) => { err += d.toString('utf8'); });
    const timer = setTimeout(() => {
      proc.kill();
      reject(new Error(`adb ${args.join(' ')} 超时`));
    }, timeoutMs);
    proc.on('error', (e) => {
      clearTimeout(timer);
      reject(new Error(`adb 不可用（${e.message}）。请在设置 autolua.adbPath 配置完整路径`));
    });
    proc.on('close', (code) => {
      clearTimeout(timer);
      if (code === 0) { resolve(out); } else { reject(new Error(`adb ${args.join(' ')} 失败: ${err || `exit ${code}`}`)); }
    });
  });
}

/** 列出 adb 设备（serial + state） */
export async function listAdbDevices(): Promise<AdbDevice[]> {
  const out = await adb(['devices']);
  const devices: AdbDevice[] = [];
  for (const line of out.split(/\r?\n/).slice(1)) {
    const m = line.match(/^(\S+)\s+(\S+)$/);
    if (m) {
      devices.push({ serial: m[1], state: m[2] });
    }
  }
  return devices;
}

/** 查询设备型号（失败返回 undefined） */
export async function queryModel(serial: string): Promise<string | undefined> {
  try {
    const model = (await adb(['-s', serial, 'shell', 'getprop', 'ro.product.model'], 5000)).trim();
    return model || undefined;
  } catch {
    return undefined;
  }
}

/**
 * 对指定 adb 设备建立端口转发：PC localPort → 设备 devicePort。
 * localPort 从 19080 起自动找空闲端口。
 */
export async function forwardPort(serial: string, devicePort: number): Promise<number> {
  for (let local = 19080; local < 19099; local++) {
    try {
      await adb(['-s', serial, 'forward', `tcp:${local}`, `tcp:${devicePort}`], 5000);
      return local;
    } catch {
      // 端口被占（forward 失败）继续尝试下一个
    }
  }
  throw new Error('19080..19098 均无法转发');
}

/** 移除某设备全部转发（断开时清理，可忽略失败） */
export async function removeForwards(serial: string): Promise<void> {
  try {
    await adb(['-s', serial, 'forward', '--remove-all'], 5000);
  } catch { /* ignore */ }
}
