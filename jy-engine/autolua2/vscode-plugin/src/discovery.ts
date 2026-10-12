/**
 * UDP 局域网设备发现。
 *  1) 广播探测：向 255.255.255.255:8080..8085 发问句，收应答即设备（真机同网段）
 *  2) 子网单播扫描：模拟器/部分路由不转发广播，退化为对本机网段逐 IP 单播探测
 */

import * as dgram from 'dgram';
import * as os from 'os';
import { UDP_PROBE, UDP_ANSWER, WS_PORT_MIN, WS_PORT_MAX } from './protocol';

export interface DiscoveredDevice {
  ip: string;
  /** 设备 UDP/WS 端口（应答来源端口） */
  port: number;
  /** 发现来源：broadcast=UDP 广播，subnet=子网单播扫描，manual=手动，adb=adb 转发 */
  source: 'broadcast' | 'subnet' | 'manual' | 'adb';
}

/**
 * 广播探测一次，收集 timeoutMs 毫秒内的应答。
 */
export function discoverDevices(timeoutMs = 2000): Promise<DiscoveredDevice[]> {
  return new Promise((resolve) => {
    const found = new Map<string, DiscoveredDevice>();
    const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });

    const finish = () => {
      try { sock.close(); } catch { /* already closed */ }
      resolve([...found.values()]);
    };

    sock.on('error', () => finish());
    sock.on('message', (msg, rinfo) => {
      const text = msg.toString('utf8').trim();
      if (text.startsWith(UDP_ANSWER) && rinfo.address) {
        found.set(`${rinfo.address}:${rinfo.port}`,
          { ip: rinfo.address, port: rinfo.port, source: 'broadcast' });
      }
    });
    sock.bind(() => {
      sock.setBroadcast(true);
      const probe = Buffer.from(UDP_PROBE, 'utf8');
      for (let port = WS_PORT_MIN; port <= WS_PORT_MAX; port++) {
        sock.send(probe, port, '255.255.255.255');
      }
      setTimeout(finish, timeoutMs);
    });
  });
}

/** 取本机所有 IPv4 地址 */
export function localIpv4List(): string[] {
  const out: string[] = [];
  for (const ifaces of Object.values(os.networkInterfaces())) {
    for (const it of ifaces ?? []) {
      if (it.family === 'IPv4' && !it.internal) {
        out.push(it.address);
      }
    }
  }
  return out;
}

/** IPv4 → /24 网段前缀（a.b.c） */
function subnetPrefixOf(ip: string): string {
  return ip.split('.').slice(0, 3).join('.');
}

/**
 * 子网单播扫描：对本机每个网段（/24）逐 IP 发 UDP 单播问句，
 * 收应答即确认 AutoLua 设备。/24 约 254 个 IP × 5 端口，1.5-3 秒出结果。
 */
export function scanSubnets(timeoutMs = 3000): Promise<DiscoveredDevice[]> {
  return new Promise((resolve) => {
    const found = new Map<string, DiscoveredDevice>();
    const prefixes = [...new Set(localIpv4List().map(subnetPrefixOf)
      .filter((p) => p && p !== '127.0.0'))];
    if (prefixes.length === 0) {
      resolve([]);
      return;
    }

    const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    const finish = () => {
      try { sock.close(); } catch { /* already closed */ }
      resolve([...found.values()]);
    };

    sock.on('error', () => finish());
    sock.on('message', (msg, rinfo) => {
      const text = msg.toString('utf8').trim();
      if (text.startsWith(UDP_ANSWER) && rinfo.address) {
        found.set(`${rinfo.address}:${rinfo.port}`,
          { ip: rinfo.address, port: rinfo.port, source: 'subnet' });
      }
    });
    sock.bind(() => {
      const probe = Buffer.from(UDP_PROBE, 'utf8');
      for (const prefix of prefixes) {
        for (let host = 1; host <= 254; host++) {
          const ip = `${prefix}.${host}`;
          for (let port = WS_PORT_MIN; port <= WS_PORT_MAX; port++) {
            sock.send(probe, port, ip);
          }
        }
      }
      setTimeout(finish, timeoutMs);
    });
  });
}
