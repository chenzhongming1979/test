/**
 * AutoLua VSCode 插件入口。
 * 职责：命令注册、分区设备树视图、状态栏、保存自动增量同步、adb 转发连接。
 */

import * as vscode from 'vscode';
import * as path from 'path';
import { DeviceManager, DeviceStatus } from './deviceManager';
import { DiscoveredDevice, discoverDevices, scanSubnets } from './discovery';
import { incrementalSync, packageAndPushZip, verifyFileOnDevice } from './sync';
import { packApk } from './packApk';
import { toDeviceRel } from './workspaceLayout';
import { ScriptRunner } from './runner';
import { initProject } from './initProject';
import { DeviceProvider, DeviceItem, DeviceAddr, parseAddr, AdbItem } from './deviceView';
import { ApiProvider, ApiFuncItem, insertApiTemplate, showApiDetail, searchApi } from './functionView';
import { NodeTreePanel, fetchNodeXml, grabNodeXmlFallback } from './nodeTreePanel';
import { listAdbDevices, queryModel, forwardPort, adb } from './adb';

let device: DeviceManager;
let runner: ScriptRunner;
let statusItem: vscode.StatusBarItem;
let deviceProvider: DeviceProvider;
let treeView: vscode.TreeView<vscode.TreeItem>;
let historyState: vscode.Memento;

export function activate(context: vscode.ExtensionContext): void {
  device = new DeviceManager();
  runner = new ScriptRunner(device);
  historyState = context.globalState;
  deviceProvider = new DeviceProvider(
    () => device.target,
    () => device.status,
    () => runner.isDeviceRunning,
  );
  deviceProvider.history = historyState.get<import('./deviceView').HistoryEntry[]>('autolua.history', []);

  // ---------- 状态栏 ----------
  statusItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
  statusItem.command = 'autolua.pickDevice';
  updateStatus('disconnected', null);
  statusItem.show();

  // ---------- 设备树 ----------
  treeView = vscode.window.createTreeView('autoluaDevices', {
    treeDataProvider: deviceProvider,
    showCollapseAll: true,
  });
  context.subscriptions.push(treeView);

  // ---------- 函数文档树 ----------
  const apiProvider = new ApiProvider();
  context.subscriptions.push(vscode.window.createTreeView('autoluaApi', {
    treeDataProvider: apiProvider,
    showCollapseAll: true,
  }));

  // ---------- 设备事件 ----------
  device.on('status', (s: DeviceStatus, target: string | null) => {
    updateStatus(s, target);
    if (s === 'connected' && target) {
      deviceProvider.touchHistory(target);
      persistHistory();
      revealConnected();
    }
    deviceProvider.refresh();
  });
  device.on('reconnecting', (target: string, delay: number) => {
    runner.appendLog(`[ide] 连接断开，${(delay / 1000) | 0}s 后重连 ${target} ...`);
  });
  // 脚本状态变化 → 设备树按钮切换（▶运行 ⇄ ⏹停止）
  runner.onRunningChange(() => deviceProvider.refresh());

  // ---------- 命令注册 ----------
  const reg = (id: string, fn: (...args: any[]) => any) =>
    context.subscriptions.push(vscode.commands.registerCommand(id, wrap(fn)));

  reg('autolua.initProject', () => initProject());

  /** 刷新 adb 设备列表（serial + 型号并发查询），失败静默（adb 不可用时面板无该区） */
  async function refreshAdbDevices(): Promise<void> {
    try {
      const list = await listAdbDevices();
      const infos = await Promise.all(list.map(async (d) => ({
        serial: d.serial,
        state: d.state,
        model: await queryModel(d.serial),
      })));
      deviceProvider.adbDevices = infos;
    } catch {
      deviceProvider.adbDevices = [];
    }
    deviceProvider.refresh();
  }

  reg('autolua.refreshAdb', () => refreshAdbDevices());

  // 视图首次展开/可见时自动刷 adb 列表（面板打开即见设备）
  let adbRefreshed = false;
  treeView.onDidChangeVisibility((e) => {
    if (e.visible && !adbRefreshed) {
      adbRefreshed = true;
      refreshAdbDevices();
      discoverDevices().then((found) => {
        deviceProvider.mergeDiscovered(found);
        deviceProvider.refresh();
      });
    }
  });
  refreshAdbDevices();

  reg('autolua.refreshDevices', async () => {
    runner.appendLog('[ide] 刷新设备列表（adb + UDP 广播）...');
    void refreshAdbDevices();
    const found = await discoverDevices();
    deviceProvider.mergeDiscovered(found);
    deviceProvider.refresh();
    runner.appendLog(`[ide] UDP 广播发现 ${found.length} 台 WiFi 设备。`);
  });

  reg('autolua.scanSubnet', async () => {
    runner.appendLog('[ide] 子网单播扫描中（本机网段逐 IP，约 3 秒）...');
    const found = await scanSubnets();
    deviceProvider.mergeDiscovered(found);
    deviceProvider.refresh();
    runner.appendLog(`[ide] 子网扫描发现 ${found.length} 台设备。`);
    if (found.length > 0) { deviceProvider.refresh(); }
  });

  reg('autolua.adbConnect', () => adbConnectFlow());

  /** 树中点击某台 adb 设备 → 直接转发连接 */
  reg('autolua.connectAdb', async (item?: AdbItem) => {
    if (!(item instanceof AdbItem)) {
      await adbConnectFlow();
      return;
    }
    if (item.info.state !== 'device') {
      vscode.window.showWarningMessage(`${item.info.serial} 不在线（${item.info.state}）`);
      return;
    }
    await adbConnectDevice(item.info.serial);
  });

  reg('autolua.addDevice', async () => {
    const ip = await vscode.window.showInputBox({
      prompt: '输入设备 IP（可带端口，如 192.168.1.23:8081；不带端口则扫描 8080-8085）',
      placeHolder: '192.168.1.23',
    });
    if (!ip) { return; }
    await connectTo(ip.trim());
  });

  reg('autolua.connect', async (item?: DeviceItem) => {
    if (item instanceof DeviceItem) {
      await connectTo(item.addr);
    } else {
      await vscode.commands.executeCommand('autolua.pickDevice');
    }
  });

  reg('autolua.pickDevice', async () => {
    if (device.status === 'connected') {
      const act = await vscode.window.showQuickPick(
        ['断开连接', '打开日志面板'], { placeHolder: `已连接 ${device.target}` },
      );
      if (act === '断开连接') { device.disconnect(); }
      if (act === '打开日志面板') { runner.show(); }
      return;
    }
    const devices = await discoverDevices();
    deviceProvider.mergeDiscovered(devices);
    deviceProvider.refresh();
    if (devices.length === 0) {
      vscode.window.showQuickPick(
        ['扫描子网（逐 IP 探测）', '连接模拟器（adb 转发）', '手动输入 IP'],
        { placeHolder: 'UDP 广播未发现设备，选择其他方式' },
      ).then((act) => {
        if (act?.startsWith('扫描子网')) { vscode.commands.executeCommand('autolua.scanSubnet'); }
        if (act?.startsWith('连接模拟器')) { vscode.commands.executeCommand('autolua.adbConnect'); }
        if (act?.startsWith('手动输入')) { vscode.commands.executeCommand('autolua.addDevice'); }
      });
      return;
    }
    const pick = await vscode.window.showQuickPick(
      devices.map((d) => ({
        label: `${d.ip}:${d.port}`,
        description: d.source === 'broadcast' ? 'UDP 广播' : '子网扫描',
        device: d,
      })),
      { placeHolder: '选择要连接的设备' },
    );
    if (pick) { await connectTo(`${pick.device.ip}:${pick.device.port}`); }
  });

  reg('autolua.disconnect', () => {
    device.disconnect();
    runner.appendLog('[ide] 已断开连接。');
  });

  reg('autolua.removeHistory', (item?: DeviceItem) => {
    if (item instanceof DeviceItem) {
      deviceProvider.removeHistory(item.addr);
      persistHistory();
      deviceProvider.refresh();
    }
  });

  reg('autolua.run', async (uri?: vscode.Uri) => {
    await requireConnected();
    // 未保存的修改先落盘：增量同步读的是磁盘内容，不保存会跑旧代码
    await vscode.workspace.saveAll(false);
    let target: string | undefined;
    if (uri) {
      // 工作区视角 → 设备端视角（frame/ 剥前缀；config/script 下不可运行）
      target = toDeviceRel(path.relative(getWorkspaceRoot(), uri.fsPath)) ?? undefined;
      if (!target) {
        vscode.window.showWarningMessage('config/script 目录为 PC 侧专用，不能作为脚本运行');
        return;
      }
    }
    await runWithVerify(target);
  });

  reg('autolua.runFile', async (uri?: vscode.Uri) => {
    await requireConnected();
    const file = uri?.fsPath ?? vscode.window.activeTextEditor?.document.uri.fsPath;
    if (!file) {
      vscode.window.showWarningMessage('没有活动的 Lua 文件');
      return;
    }
    await vscode.workspace.saveAll(false);
    const rel = toDeviceRel(path.relative(getWorkspaceRoot(), file));
    if (!rel) {
      vscode.window.showWarningMessage('config/script 目录为 PC 侧专用，不能作为脚本运行');
      return;
    }
    await runWithVerify(rel);
  });

  reg('autolua.stop', () => runner.stop());
  reg('autolua.pause', () => runner.pause());
  reg('autolua.resume', () => runner.resume());

  reg('autolua.syncNow', () => requireConnected().then(() => syncAndLog()));

  reg('autolua.packageZip', async () => {
    await requireConnected();
    const root = getWorkspaceRoot();
    const name = path.basename(root).replace(/[^A-Za-z0-9._-]/g, '_') || 'project';
    await packageAndPushZip(device, root, name, (l) => runner.appendLog(`[zip] ${l}`));
    vscode.window.showInformationMessage('工程已打包并安装到设备（ide_push/），可运行 main.lua');
  });

  reg('autolua.showLog', () => runner.show());

  reg('autolua.packApk', () => packApk(context));

  // ---------- 函数文档 ----------
  reg('autolua.api.insert', (item?: ApiFuncItem) => insertApiTemplate(item));
  reg('autolua.api.detail', (item?: ApiFuncItem) => showApiDetail(item));
  reg('autolua.api.search', () => searchApi());

  // ---------- 节点树抓取 ----------
  reg('autolua2.grabNodeTree', () => grabNodeTreeFlow());

  // ---------- 保存自动增量同步 ----------
  context.subscriptions.push(vscode.workspace.onDidSaveTextDocument(async (doc) => {
    if (!vscode.workspace.getConfiguration('autolua').get<boolean>('autoSyncOnSave')) { return; }
    if (device.status !== 'connected') { return; }
    if (doc.uri.scheme !== 'file') { return; }
    const ext = path.extname(doc.uri.fsPath).toLowerCase();
    if (!['.lua', '.json', '.txt', '.xml'].includes(ext)) { return; }
    try {
      await incrementalSync(device, getWorkspaceRoot(), (l) => runner.appendLog(`[sync] ${l}`));
    } catch (e) {
      runner.appendLog(`[sync] 失败: ${(e as Error).message}`);
    }
  }));

  /** 抓取节点树：优先走设备 execute 通道执行 getNodeXml()，未连接/失败走剪贴板/本地文件降级 */
  async function grabNodeTreeFlow(): Promise<void> {
    let xml: string | null = null;
    let source = '';
    if (device.status === 'connected') {
      try {
        runner.appendLog(`[ide] 抓取节点树 getNodeXml(${NodeTreePanel.includeInvisible ? 'true' : 'false'}) ...`);
        xml = await fetchNodeXml(device, NodeTreePanel.includeInvisible);
        source = `设备 ${device.target}`;
        runner.appendLog(`[ide] 节点树抓取成功（${xml.length} 字符）。`);
      } catch (e) {
        runner.appendLog(`[ide] 抓取节点树失败: ${(e as Error).message}`);
        vscode.window.showWarningMessage(`从设备抓取节点树失败：${(e as Error).message}`);
      }
    }
    if (xml === null) {
      vscode.window.showInformationMessage('可先在设备上执行 getNodeXml() 复制结果，再从剪贴板读取。');
      xml = await grabNodeXmlFallback();
      if (xml === null) { return; }
      source = '本地导入';
    }
    NodeTreePanel.show(context.subscriptions, xml, source, async (inc) => {
      const x = await fetchNodeXml(device, inc);
      return { xml: x, source: `设备 ${device.target}` };
    });
  }

  context.subscriptions.push(statusItem);
  deviceProvider.refresh();

  runner.appendLog('[ide] AutoLua 插件已激活。侧边栏连接设备后：Ctrl+F5 运行，Shift+F5 停止。');
  runner.appendLog('[ide] 提示：模拟器（NAT 网络）请用「连接模拟器（adb 转发）」；真机用 UDP 广播或子网扫描。');
}

export function deactivate(): void {
  device?.disconnect();
}

// ------------------------------------------------------------------

async function connectTo(addr: DeviceAddr): Promise<void> {
  if (addr.startsWith('adb://')) {
    // 历史中的 adb 设备：重新建立转发再连
    const { ip: serial, port } = parseAddr(addr);
    runner.appendLog(`[ide] adb 转发重连 ${serial}:${port} ...`);
    const local = await forwardPort(serial, port);
    await connectNetwork(`127.0.0.1:${local}`);
    return;
  }
  await connectNetwork(addr);
}

async function connectNetwork(addr: DeviceAddr): Promise<void> {
  const [ip, portStr] = addr.split(':');
  const port = portStr ? Number(portStr) : undefined;
  runner.appendLog(`[ide] 连接 ${addr} ...`);
  await device.connect(ip, port);
  runner.appendLog(`[ide] 已连接 ${device.target}。Ctrl+F5 运行，Shift+F5 停止。`);
  runner.show();
}

/** adb 转发连接流程：列设备 → 选 serial → 逐端口 forward+握手 */
async function adbConnectFlow(): Promise<void> {
  let devices;
  try {
    devices = await listAdbDevices();
  } catch (e) {
    vscode.window.showErrorMessage((e as Error).message);
    return;
  }
  const online = devices.filter((d) => d.state === 'device');
  if (online.length === 0) {
    vscode.window.showWarningMessage(`adb 未发现在线设备（共 ${devices.length} 条）。请先启动模拟器或连接手机。`);
    return;
  }
  runner.appendLog(`[ide] adb 在线设备: ${online.map((d) => d.serial).join(', ')}`);

  let serial = online[0].serial;
  if (online.length > 1) {
    const pick = await vscode.window.showQuickPick(
      online.map((d) => ({ label: d.serial })), { placeHolder: '选择 adb 设备' },
    );
    if (!pick) { return; }
    serial = pick.label;
  }
  await adbConnectDevice(serial);
}

/** 对单台 adb 设备逐端口转发并握手（设备 WS 端口 8080..8085） */
async function adbConnectDevice(serial: string): Promise<void> {
  for (let p = 8080; p <= 8085; p++) {
    let local: number;
    try {
      local = await forwardPort(serial, p);
    } catch {
      continue;
    }
    try {
      await connectNetwork(`127.0.0.1:${local}`);
      deviceProvider.touchHistory(`adb://${serial}/${p}`);
      persistHistory();
      deviceProvider.refresh();
      vscode.window.showInformationMessage(`已通过 adb 连接 ${serial}（设备端口 ${p} → 本机 ${local}）`);
      return;
    } catch {
      // 该端口不是 AutoLua，清理转发继续
      adb(['-s', serial, 'forward', '--remove', `tcp:${local}`]).catch(() => { /* ignore */ });
    }
  }
  vscode.window.showErrorMessage(
    `${serial} 上 8080-8085 均未探测到 AutoLua IDE 服务。请确认 App 内 IDE 服务已开启。`,
  );
}

function persistHistory(): void {
  historyState.update('autolua.history', deviceProvider.history);
}

function revealConnected(): void {
  // 连接成功后展开树定位到已连接节点（容错：视图未显示时静默）
  const addr = device.target;
  if (!addr) { return; }
  const { ip, port } = parseAddr(addr);
  const item = new DeviceItem(addr, { ip, port }, 'connected');
  Promise.resolve(treeView.reveal(item, { select: true, focus: false, expand: true }))
    .catch(() => { /* ignore */ });
}

/** 同步 → 校验入口文件在设备端 → 运行。校验失败时阻止运行并给出修复建议。 */
async function runWithVerify(relPath?: string): Promise<void> {
  const root = getWorkspaceRoot();
  const script = relPath
    ?? (vscode.workspace.getConfiguration('autolua').get<string>('mainScript') || 'main.lua');

  await syncAndLog(true);

  const v = await verifyFileOnDevice(device, root, script);
  if (!v.ok) {
    const reason = !v.deviceHas
      ? `设备端不存在 ${script}（推送未落盘）`
      : `设备端 ${script} 与本地 md5 不一致（内容未同步成功）`;
    runner.appendLog(`[run] 校验失败: ${reason}`);
    const act = await vscode.window.showErrorMessage(
      `运行中止：${reason}。可用「打包工程并安装到设备」(F8) 走 zip 通道全量重推。`,
      '执行 F8 全量打包', '取消',
    );
    if (act === '执行 F8 全量打包') {
      await vscode.commands.executeCommand('autolua.packageZip');
      const v2 = await verifyFileOnDevice(device, root, script);
      if (!v2.ok) {
        throw new Error(`全量打包后仍未同步成功，请查看设备端日志`);
      }
    } else {
      return;
    }
  }
  await runner.run(relPath);
}

/** 运行前增量同步。quiet=运行路径：无变更/成功不打日志，仅失败可见 */
async function syncAndLog(quiet = false): Promise<void> {
  try {
    await incrementalSync(device, getWorkspaceRoot(),
      quiet ? undefined : (l) => runner.appendLog(`[sync] ${l}`));
  } catch (e) {
    runner.appendLog(`[sync] 同步失败（继续运行）: ${(e as Error).message}`);
  }
}

async function requireConnected(): Promise<void> {
  if (device.status === 'connected') { return; }
  await vscode.commands.executeCommand('autolua.pickDevice');
  const st = (): DeviceStatus => device.status;
  if (st() !== 'connected') {
    throw new Error('设备未连接');
  }
}

function getWorkspaceRoot(): string {
  const folders = vscode.workspace.workspaceFolders;
  if (!folders || folders.length === 0) {
    throw new Error('请先打开工程文件夹');
  }
  return folders[0].uri.fsPath;
}

function updateStatus(s: DeviceStatus, target: string | null): void {
  switch (s) {
    case 'connected':
      statusItem.text = `$(vm-connect) AutoLua: ${target}`;
      statusItem.tooltip = '已连接（点击断开/打开日志）';
      break;
    case 'connecting':
      statusItem.text = `$(sync~spin) AutoLua: 连接中...`;
      statusItem.tooltip = '正在连接设备';
      break;
    default:
      statusItem.text = `$(circle-slash) AutoLua: 未连接`;
      statusItem.tooltip = '点击发现并连接设备';
  }
}

/** 命令异常统一弹窗（requireConnected 抛出的提示已在内部处理） */
function wrap(fn: (...args: any[]) => any): (...args: any[]) => any {
  return async (...args: any[]) => {
    try {
      await fn(...args);
    } catch (e) {
      const msg = (e as Error).message ?? String(e);
      if (msg !== '设备未连接') {
        vscode.window.showErrorMessage(msg);
      }
    }
  };
}
