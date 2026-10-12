/**
 * 设备连接管理：WS 连接（8080..8085 端口扫描握手）、串行化命令请求、
 * 心跳保活、断线自动重连（指数退避）。
 *
 * 协议无请求 id 字段，回包按 type 匹配，故命令串行发送（一次一个 pending）。
 * 服务器主动推送帧（log/image/stream_* 等）经事件抛给上层。
 */

import { EventEmitter } from 'events';
import WebSocket from 'ws';
import { Cmd, Msg, WsMessage, WS_PORT_MIN, WS_PORT_MAX, CMD_TIMEOUT_MS, HEARTBEAT_MS } from './protocol';

export type DeviceStatus = 'disconnected' | 'connecting' | 'connected';

export class DeviceManager extends EventEmitter {
  /** 当前连接 ip:port（未连接为 null） */
  target: string | null = null;
  status: DeviceStatus = 'disconnected';
  capabilities: any = null;

  private ws: WebSocket | null = null;
  /** 串行化命令队列 */
  private pending: {
    type: string;
    resolve: (data: any) => void;
    reject: (err: Error) => void;
    timer: NodeJS.Timeout;
  } | null = null;
  private queue: Array<() => void> = [];
  private heartbeatTimer: NodeJS.Timeout | null = null;
  private reconnectTimer: NodeJS.Timeout | null = null;
  private reconnectAttempts = 0;
  /** 用户主动断开时不自动重连 */
  private manualClose = false;

  constructor() {
    super();
    this.setMaxListeners(0);
  }

  /** 连接指定 ip（端口未知时扫描 8080..8085） */
  async connect(ip: string, port?: number): Promise<void> {
    this.manualClose = false;
    // 防重入：已有旧连接先关闭（重复点"连接"会泄漏半开 ws，设备端日志双份广播）
    if (this.ws) {
      this.manualClose = true;   // 阻止旧 ws 的 close 事件触发自动重连
      this.stopReconnect();
      this.cleanup();
      this.manualClose = false;
    }
    this.setStatus('connecting');
    const ports = port ? [port] : range(WS_PORT_MIN, WS_PORT_MAX);
    for (const p of ports) {
      const ws = await this.tryHandshake(ip, p);
      if (ws) {
        this.attach(ip, p, ws);
        return;
      }
    }
    this.setStatus('disconnected');
    throw new Error(`${ip} 上 ${WS_PORT_MIN}..${WS_PORT_MAX} 均未发现 AutoLua IDE 服务`);
  }

  /** 主动断开（不自动重连） */
  disconnect(): void {
    this.manualClose = true;
    this.stopReconnect();
    this.cleanup();
    this.setStatus('disconnected');
  }

  /**
   * 发送命令并等待对应回包（串行化）。
   * expected：期望的回包 type，默认 result；传 null 表示静默命令（设备端不回包，
   * 如 push_files_start/push_file/push_zip_start/push_zip_chunk），立即返回。
   */
  request(type: string, data?: any, expected?: string | null, timeoutMs = CMD_TIMEOUT_MS): Promise<any> {
    if (this.status !== 'connected' || !this.ws) {
      return Promise.reject(new Error('设备未连接'));
    }
    // 静默命令：直接发送不等回包
    if (expected === null) {
      this.send({ type, data: data ?? {} });
      return Promise.resolve(undefined);
    }
    const replyType = expected ?? Msg.RESULT;
    return new Promise((resolve, reject) => {
      const run = () => {
        const timer = setTimeout(() => {
          this.pending = null;
          this.pump();
          reject(new Error(`命令 ${type} 超时（${timeoutMs}ms）`));
        }, timeoutMs);
        this.pending = { type: replyType, resolve, reject, timer };
        this.send({ type, data: data ?? {} });
      };
      if (this.pending) {
        this.queue.push(run);
      } else {
        run();
      }
    });
  }

  private send(msg: WsMessage): void {
    this.ws?.send(JSON.stringify(msg));
  }

  /** 对某端口发起 WS 握手，收到 hello 即成功 */
  private tryHandshake(ip: string, port: number): Promise<WebSocket | null> {
    return new Promise((resolve) => {
      let settled = false;
      const done = (ws: WebSocket | null) => {
        if (settled) { return; }
        settled = true;
        clearTimeout(timer);
        if (!ws) {
          try { raw.close(); } catch { /* ignore */ }
        }
        resolve(ws);
      };
      const url = `ws://${ip}:${port}`;
      const raw = new WebSocket(url, { handshakeTimeout: 3000 });
      const timer = setTimeout(() => done(null), 5000);
      raw.on('open', () => {
        // 等首帧 hello 确认是 AutoLua 服务
        raw.once('message', (buf: WebSocket.RawData) => {
          try {
            const msg = JSON.parse(buf.toString('utf8')) as WsMessage;
            if (msg.type === Msg.HELLO) {
              done(raw);
            } else {
              done(null);
            }
          } catch {
            done(null);
          }
        });
        // 若 3 秒内无 hello 视为非目标服务
        setTimeout(() => done(null), 3000);
      });
      raw.on('error', () => done(null));
    });
  }

  private attach(ip: string, port: number, ws: WebSocket): void {
    this.ws = ws;
    this.target = `${ip}:${port}`;
    this.reconnectAttempts = 0;
    this.setStatus('connected');

    ws.on('message', (buf: WebSocket.RawData, isBinary: boolean) => {
      if (isBinary) {
        this.emit('binary', buf as Buffer);
        return;
      }
      this.handleText(buf.toString('utf8'));
    });
    ws.on('close', () => this.onClosed());
    ws.on('error', () => { /* close 事件随后触发 */ });

    // 拉取能力清单（失败不影响连接）
    this.request(Cmd.CAPABILITIES, {}, Msg.CAPABILITIES)
      .then((cap) => { this.capabilities = cap; this.emit('capabilities', cap); })
      .catch(() => { this.capabilities = null; });

    this.startHeartbeat();
  }

  private handleText(text: string): void {
    let msg: WsMessage;
    try {
      msg = JSON.parse(text) as WsMessage;
    } catch {
      return;
    }
    if (this.pending && msg.type === this.pending.type) {
      clearTimeout(this.pending.timer);
      const p = this.pending;
      this.pending = null;
      this.pump();
      p.resolve(msg.data);
      return;
    }
    if (msg.type === Msg.ERROR && this.pending) {
      clearTimeout(this.pending.timer);
      const p = this.pending;
      this.pending = null;
      this.pump();
      p.reject(new Error(msg.data?.msg ?? '设备返回错误'));
      return;
    }
    // 服务器主动推送
    this.emit('message', msg);
  }

  /** 队列取出下一条命令 */
  private pump(): void {
    const next = this.queue.shift();
    if (next) { next(); }
  }

  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      this.request(Cmd.PING, {}, Msg.PONG, 8000).catch(() => {
        // 心跳失败视为断线
        this.ws?.terminate();
      });
    }, HEARTBEAT_MS);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  private onClosed(): void {
    const wasConnected = this.status === 'connected';
    this.cleanup();
    this.setStatus('disconnected');
    if (this.manualClose || !wasConnected || !this.target) {
      return;
    }
    // 断线自动重连：3s 起指数退避，上限 30s
    const delay = Math.min(3000 * 2 ** this.reconnectAttempts, 30000);
    this.reconnectAttempts++;
    this.emit('reconnecting', this.target, delay);
    this.reconnectTimer = setTimeout(() => {
      const [ip, port] = this.target!.split(':');
      this.connect(ip, Number(port)).catch(() => { /* 状态已置 disconnected */ });
    }, delay);
  }

  private stopReconnect(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private cleanup(): void {
    this.stopHeartbeat();
    if (this.pending) {
      clearTimeout(this.pending.timer);
      this.pending.reject(new Error('连接已断开'));
      this.pending = null;
    }
    this.queue = [];
    this.capabilities = null;
    try { this.ws?.close(); } catch { /* ignore */ }
    this.ws = null;
  }

  private setStatus(s: DeviceStatus): void {
    this.status = s;
    this.emit('status', s, this.target);
  }
}

function range(min: number, max: number): number[] {
  const out: number[] = [];
  for (let i = min; i <= max; i++) { out.push(i); }
  return out;
}
