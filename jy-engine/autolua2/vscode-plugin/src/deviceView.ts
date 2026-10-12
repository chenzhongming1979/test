/**
 * 设备树视图：已连接 / 发现的设备 / 历史设备 三区展示，
 * 每项带 inline 操作按钮（连接/运行/停止/断开/移除）。
 */

import * as vscode from 'vscode';
import { DiscoveredDevice } from './discovery';

/** 设备地址统一形式：`ip:port` 或 `adb://serial/devicePort` */
export type DeviceAddr = string;

export interface HistoryEntry {
  addr: DeviceAddr;
  label: string;
}

/** 分区节点（可折叠组头） */
export class PartitionItem extends vscode.TreeItem {
  constructor(
    label: string,
    public readonly kind: 'connected' | 'adb' | 'discovered' | 'history',
    expanded: boolean,
  ) {
    super(label, expanded ? vscode.TreeItemCollapsibleState.Expanded : vscode.TreeItemCollapsibleState.Collapsed);
    this.contextValue = `partition_${kind}`;
  }
}

/** 设备节点 */
export class DeviceItem extends vscode.TreeItem {
  constructor(
    readonly addr: DeviceAddr,
    readonly device: { ip: string; port: number; source?: string },
    readonly kind: 'connected' | 'connectedRunning' | 'device' | 'historyDevice',
  ) {
    super(DeviceItem.display(addr), vscode.TreeItemCollapsibleState.None);
    this.contextValue = kind;
    this.description = kind === 'connected' ? '已连接'
      : kind === 'connectedRunning' ? '运行中'
        : kind === 'historyDevice' ? (addr.startsWith('adb://') ? 'adb' : '历史')
          : sourceText(device.source);
    this.iconPath = new vscode.ThemeIcon(
      kind === 'connectedRunning' ? 'debug-disconnect'
        : kind === 'connected' ? 'vm-active' : 'device-mobile',
    );
    if (kind === 'device' || kind === 'historyDevice') {
      this.command = { command: 'autolua.connect', title: '连接设备', arguments: [this] };
      this.tooltip = new vscode.MarkdownString(`**${addr}**\n\n点击或悬停按钮连接`);
    } else {
      this.tooltip = new vscode.MarkdownString(
        kind === 'connectedRunning'
          ? `**${addr}**（运行中）\n\n按钮：⏹停止 / 断开`
          : `**${addr}**（空闲）\n\n按钮：▶运行 / 断开`,
      );
    }
  }

  static display(addr: DeviceAddr): string {
    if (addr.startsWith('adb://')) {
      const [, serial, port] = addr.split('/');
      return `${serial} (adb→:${port})`;
    }
    return addr;
  }
}

function sourceText(source?: string): string {
  switch (source) {
    case 'broadcast': return 'UDP 广播';
    case 'subnet': return '子网扫描';
    case 'manual': return '手动';
    default: return '';
  }
}

/** adb 在线设备信息 */
export interface AdbDeviceInfo {
  serial: string;
  state: string;
  /** 设备型号（ro.product.model，查询失败用 serial） */
  model?: string;
}

/** adb 设备节点 */
export class AdbItem extends vscode.TreeItem {
  constructor(readonly info: AdbDeviceInfo) {
    super(info.model || info.serial, vscode.TreeItemCollapsibleState.None);
    this.contextValue = 'adbDevice';
    this.description = `${info.serial}${info.state === 'device' ? '' : ` (${info.state})`}`;
    this.iconPath = new vscode.ThemeIcon('device-mobile');
    this.command = { command: 'autolua.connectAdb', title: '连接 adb 设备', arguments: [this] };
    this.tooltip = new vscode.MarkdownString(
      `**${info.model || info.serial}**\n\nserial: \`${info.serial}\`\n\n点击自动转发端口并连接（设备需开启 IDE 服务）`,
    );
  }
}

export class DeviceProvider implements vscode.TreeDataProvider<vscode.TreeItem> {
  private _onDidChange = new vscode.EventEmitter<vscode.TreeItem | undefined>();
  readonly onDidChangeTreeData = this._onDidChange.event;

  discovered: DiscoveredDevice[] = [];
  history: HistoryEntry[] = [];
  adbDevices: AdbDeviceInfo[] = [];
  scanning = false;

  constructor(
    private connectedAddr: () => DeviceAddr | null,
    private status: () => 'disconnected' | 'connecting' | 'connected',
    private running: () => boolean,
  ) { }

  getTreeItem(item: vscode.TreeItem): vscode.TreeItem { return item; }

  async getChildren(el?: vscode.TreeItem): Promise<vscode.TreeItem[]> {
    // 顶层：分区结构
    if (!el) {
      const items: vscode.TreeItem[] = [];

      const connAddr = this.connectedAddr();
      if (connAddr) {
        const { ip, port } = parseAddr(connAddr);
        const kind = this.running() ? 'connectedRunning' : 'connected';
        items.push(new DeviceItem(connAddr, { ip, port }, kind));
      }

      if (this.adbDevices.length > 0) {
        items.push(new PartitionItem(`adb 设备 (${this.adbDevices.length})`, 'adb', true));
      }
      if (this.discovered.length > 0) {
        items.push(new PartitionItem(`WiFi 设备 (${this.discovered.length})`, 'discovered', true));
      }
      if (this.history.length > 0) {
        items.push(new PartitionItem(`历史设备 (${this.history.length})`, 'history', true));
      }
      return items;
    }

    // 分区子节点
    if (el instanceof PartitionItem) {
      if (el.kind === 'adb') {
        return this.adbDevices.map((d) => new AdbItem(d));
      }
      if (el.kind === 'discovered') {
        return this.discovered.map((d) => {
          const addr = `${d.ip}:${d.port}`;
          const isHistory = this.history.some((h) => h.addr === addr);
          return new DeviceItem(addr, { ip: d.ip, port: d.port, source: d.source },
            isHistory ? 'historyDevice' : 'device');
        });
      }
      if (el.kind === 'history') {
        return this.history
          .filter((h) => h.addr !== this.connectedAddr())
          .map((h) => {
            const { ip, port } = parseAddr(h.addr);
            return new DeviceItem(h.addr, { ip, port }, 'historyDevice');
          });
      }
    }
    return [];
  }

  refresh(): void {
    this._onDidChange.fire(undefined);
  }

  /** 发现/扫描结果合并进列表（去重，subnet 不覆盖 broadcast 标记） */
  mergeDiscovered(devices: DiscoveredDevice[]): void {
    for (const d of devices) {
      const exist = this.discovered.find((x) => x.ip === d.ip && x.port === d.port);
      if (exist && exist.source === 'broadcast') { continue; }
      if (exist) {
        exist.source = d.source;
      } else {
        this.discovered.push(d);
      }
    }
  }

  /** 记录到历史（置顶去重，最多 12 条） */
  touchHistory(addr: DeviceAddr): void {
    this.history = [{ addr, label: DeviceItem.display(addr) },
      ...this.history.filter((h) => h.addr !== addr)].slice(0, 12);
  }

  removeHistory(addr: DeviceAddr): void {
    this.history = this.history.filter((h) => h.addr !== addr);
    this.discovered = this.discovered.filter((d) => `${d.ip}:${d.port}` !== addr);
  }
}

/** 解析设备地址为 ip/port（adb:// 形式返回转发信息） */
export function parseAddr(addr: DeviceAddr): { ip: string; port: number } {
  if (addr.startsWith('adb://')) {
    return { ip: addr.split('/')[1], port: Number(addr.split('/')[2]) };
  }
  const [ip, port] = addr.split(':');
  return { ip, port: Number(port) };
}
