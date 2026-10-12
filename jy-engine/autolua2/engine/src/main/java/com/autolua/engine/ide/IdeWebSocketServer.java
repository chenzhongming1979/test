package com.autolua.engine.ide;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import com.google.gson.JsonObject;

import org.java_websocket.WebSocket;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IDE 调试 WebSocket 服务器（对照派大星 AutoLuaWebSocketServer 骨架）。
 * <p>
 * 线程模型：WebSocketServer 自带 selector 线程负责收发与回调；
 * 收到的文本消息转投 {@link #messageExecutor}（单线程串行）处理，避免阻塞 selector。
 * <p>
 * 注意必须串行：push_files/push_zip/plugin_push 等会话型命令依赖消息到达顺序
 * （start→chunk×n→end），多线程会乱序执行导致会话状态错乱（如 written=0）；
 * 耗时操作（run_script 的主引擎提交、图色计算）本身异步，串行不影响吞吐。
 * 心跳：自主调度器每 {@link WsProtocol#HEARTBEAT_INTERVAL_SECONDS} 秒向全部在线连接 ping。
 * 连接断开回调可配置（默认不停止脚本）。
 */
public class IdeWebSocketServer extends WebSocketServer {

    private static final String TAG = "AutoLuaIde";
    /** 消息处理线程池大小 */
    private static final int MESSAGE_THREADS = 1;

    /** clientId → 连接 */
    private final Map<String, WebSocket> clients = new ConcurrentHashMap<>();
    /** 连接 → clientId */
    private final Map<WebSocket, String> clientIds = new ConcurrentHashMap<>();
    /** 连接 → 最后活跃时间戳（收到消息或 pong 时更新；心跳循环据此踢掉半开连接） */
    private final Map<WebSocket, Long> lastSeen = new ConcurrentHashMap<>();
    /** 死连接判定：超过 2.5 个心跳周期无任何活跃即断开 */
    private static final long IDLE_TIMEOUT_MS = WsProtocol.HEARTBEAT_INTERVAL_SECONDS * 2500L;
    private final IdeMessageRouter router;
    /** 应用上下文（engine 无 app context 时可为 null，hello 中版本信息留空） */
    private final Context appContext;
    /** 消息处理线程池（单线程串行：会话型命令依赖消息到达顺序，见类注释） */
    private final ExecutorService messageExecutor;
    /** 心跳调度器 */
    private ScheduledExecutorService heartbeatExecutor;
    /** 连接断开回调（可配置；默认 null —— 不停止脚本） */
    private volatile Runnable disconnectListener;

    /**
     * @param port       监听端口（端口回退由 IdeServerManager 决定后传入）
     * @param router     消息路由器
     * @param appContext 应用上下文，可为 null
     */
    public IdeWebSocketServer(int port, IdeMessageRouter router, Context appContext) {
        super(new InetSocketAddress(port));
        this.router = router;
        this.appContext = appContext;
        setReuseAddr(true);
        messageExecutor = Executors.newFixedThreadPool(MESSAGE_THREADS, new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "AutoLuaIde-msg-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    /** 配置连接断开回调（全部连接断开后触发；传 null 表示不处理） */
    public void setDisconnectListener(Runnable listener) {
        this.disconnectListener = listener;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void onStart() {
        // 禁用库自带连接丢失检测，改用自主 30s 心跳（对照参考实现）
        setConnectionLostTimeout(0);
        startHeartbeat();
        Log.d(TAG, "ide websocket server started on port " + getPort());
    }

    @Override
    public void stop() throws InterruptedException {
        stopHeartbeat();
        messageExecutor.shutdownNow();
        clients.clear();
        clientIds.clear();
        lastSeen.clear();
        super.stop();
        Log.d(TAG, "ide websocket server stopped");
    }

    // ------------------------------------------------------------------
    // 连接回调
    // ------------------------------------------------------------------

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String clientId = UUID.randomUUID().toString().substring(0, 8);
        clients.put(clientId, conn);
        clientIds.put(conn, clientId);
        lastSeen.put(conn, System.currentTimeMillis());
        Log.d(TAG, "ide client connected: " + conn.getRemoteSocketAddress()
                + ", clientId=" + clientId + ", total=" + clients.size());
        sendHello(conn, clientId);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String clientId = clientIds.remove(conn);
        if (clientId != null) {
            clients.remove(clientId);
        }
        lastSeen.remove(conn);
        Log.d(TAG, "ide client disconnected: clientId=" + clientId + ", code=" + code
                + ", reason=" + reason + ", remote=" + remote + ", total=" + clients.size());
        Runnable cb = disconnectListener;
        if (cb != null) {
            try {
                cb.run();
            } catch (Exception e) {
                Log.w(TAG, "disconnect listener failed", e);
            }
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        Log.e(TAG, "ide websocket error, client=" + clientIds.get(conn), ex);
    }

    // ------------------------------------------------------------------
    // 消息处理
    // ------------------------------------------------------------------

    @Override
    public void onMessage(WebSocket conn, String message) {
        lastSeen.put(conn, System.currentTimeMillis());
        final String clientId = clientIds.get(conn);
        messageExecutor.execute(() -> router.dispatch(message, clientId));
    }

    @Override
    public void onWebsocketPong(WebSocket conn, Framedata f) {
        lastSeen.put(conn, System.currentTimeMillis());
    }

    /** 不处理收到的二进制帧（投屏数据只有服务器 → PC 方向；push 走 base64 文本），仅记录 */
    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        Log.d(TAG, "ignore binary frame, size=" + message.remaining());
    }

    // ------------------------------------------------------------------
    // 发送
    // ------------------------------------------------------------------

    /** 按 clientId 定向发送；连接不存在或已关闭时静默丢弃 */
    public void sendToClient(String clientId, String message) {
        if (clientId == null) {
            return;
        }
        WebSocket conn = clients.get(clientId);
        if (conn == null || !conn.isOpen()) {
            Log.w(TAG, "send skipped, client not open: " + clientId);
            return;
        }
        conn.send(message);
    }

    /** 向全部在线连接广播文本消息（log / image 回传） */
    public void broadcast(String message) {
        for (WebSocket conn : clients.values()) {
            if (conn.isOpen()) {
                try {
                    conn.send(message);
                } catch (Exception e) {
                    Log.w(TAG, "broadcast failed", e);
                }
            }
        }
    }

    /**
     * 按 clientId 定向发送二进制帧（方案八投屏数据；帧格式见 WsProtocol 注释）。
     *
     * @return 发送成功返回 true；连接不存在/已关闭/发送异常返回 false
     *         （调用方据此感知订阅者掉线，连续失败可结束流会话）
     */
    public boolean sendBinaryToClient(String clientId, byte[] data) {
        if (clientId == null || data == null) {
            return false;
        }
        WebSocket conn = clients.get(clientId);
        if (conn == null || !conn.isOpen()) {
            return false;
        }
        try {
            conn.send(data);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "send binary failed: " + clientId + ", size=" + data.length + ", " + e.getMessage());
            return false;
        }
    }

    /** 当前在线连接数（并发计数由 ConcurrentHashMap 保证） */
    public int getClientCount() {
        return clients.size();
    }

    /** 第一个在线客户端 ID（无连接返回 null；设备端投屏快捷开关订阅用） */
    public String getFirstClientId() {
        for (String id : clients.keySet()) {
            return id;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 连接建立后回 hello：{"type":"hello","data":{"clientId","device_name","app_version","app_version_code"}} */
    private void sendHello(WebSocket conn, String clientId) {
        JsonObject data = new JsonObject();
        data.addProperty("clientId", clientId);
        data.addProperty("device_name", Build.MODEL != null ? Build.MODEL : "");
        String[] version = readAppVersion();
        data.addProperty("app_version", version[0]);
        data.addProperty("app_version_code", version[1]);
        JsonObject hello = new JsonObject();
        hello.addProperty("type", WsProtocol.MSG_HELLO);
        hello.add("data", data);
        try {
            conn.send(hello.toString());
        } catch (Exception e) {
            Log.w(TAG, "send hello failed", e);
        }
    }

    /** 从 PackageManager 读版本信息；无 app context 时留空 */
    private String[] readAppVersion() {
        Context ctx = appContext;
        if (ctx != null) {
            try {
                PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                return new String[]{
                        pi.versionName != null ? pi.versionName : "",
                        String.valueOf(pi.versionCode)};
            } catch (Exception e) {
                Log.w(TAG, "read app version failed", e);
            }
        }
        return new String[]{"", "0"};
    }

    /** 启动自主心跳：每 30s 向全部在线连接 sendPing */
    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AutoLuaIde-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (WebSocket conn : clients.values()) {
                if (!conn.isOpen()) {
                    continue;
                }
                // 死连接清理：半开连接（客户端异常死亡未发 FIN）sendPing 写本地缓冲
                // 不报错、isOpen 恒真——按最后活跃时间踢掉，避免日志广播到僵尸连接
                Long last = lastSeen.get(conn);
                if (last != null && now - last > IDLE_TIMEOUT_MS) {
                    Log.w(TAG, "ide client idle timeout, closing: clientId=" + clientIds.get(conn)
                            + ", idle=" + (now - last) + "ms");
                    conn.closeConnection(4001, "heartbeat timeout");
                    continue;
                }
                try {
                    conn.sendPing();
                } catch (Exception e) {
                    Log.w(TAG, "heartbeat ping failed: " + e.getMessage());
                }
            }
        }, WsProtocol.HEARTBEAT_INTERVAL_SECONDS, WsProtocol.HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        ScheduledExecutorService heartbeat = heartbeatExecutor;
        heartbeatExecutor = null;
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
    }
}
