package com.autolua.engine.cloud;

import android.util.Log;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 云控 WebSocket 服务器（9300；骨架镜像 IdeWebSocketServer：selector 线程回调 + 30s ping 心跳）。
 * <p>
 * 职责只做通道：解帧（{@link CloudFrameCodec}）→ 明文 JSON 入队（Lua poll 拉取）；
 * 发送侧由 Lua 调 CloudService.send → 编帧（自动加密）→ 广播。
 * 解帧失败定向回错误帧（恒明文，对齐原 cloud.lua 兼容矩阵）。
 */
public class CloudWebSocketServer extends WebSocketServer {

    private static final String TAG = "AutoLuaCloud";
    private static final int HEARTBEAT_SECONDS = 30;

    /** 已解密的明文消息队列（Lua poll 消费；容量上限防无消费者时膨胀） */
    private final LinkedBlockingQueue<String> inbox = new LinkedBlockingQueue<>(4096);
    /** clientId → 连接（发送/计数用） */
    private final Map<String, WebSocket> clients = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> clientIds = new ConcurrentHashMap<>();
    private final CloudFrameCodec codec;
    private ScheduledExecutorService heartbeatExecutor;
    /** 启动确认：start 异步绑定端口，onStart/onError 置位后 awaitStarted 返回 */
    private final java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
    private volatile boolean startOk = false;

    public CloudWebSocketServer(int port, CloudFrameCodec codec) {
        super(new InetSocketAddress(port));
        this.codec = codec;
        setReuseAddr(true);
    }

    @Override
    public void onStart() {
        setConnectionLostTimeout(0); // 禁用库自带检测，改自主 30s ping
        startOk = true;
        startLatch.countDown();
        startHeartbeat();
        Log.d(TAG, "cloud websocket server started on port " + getPort()
                + " (encrypted=" + codec.isEncrypted() + ")");
    }

    @Override
    public void stop() throws InterruptedException {
        stopHeartbeat();
        inbox.clear();
        clients.clear();
        clientIds.clear();
        super.stop();
        Log.d(TAG, "cloud websocket server stopped");
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String clientId = String.format("%08x", System.nanoTime() & 0xFFFFFFFFL);
        clients.put(clientId, conn);
        clientIds.put(conn, clientId);
        Log.d(TAG, "cloud client connected: " + conn.getRemoteSocketAddress()
                + ", clientId=" + clientId + ", total=" + clients.size());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String clientId = clientIds.remove(conn);
        if (clientId != null) {
            clients.remove(clientId);
        }
        Log.d(TAG, "cloud client disconnected: clientId=" + clientId + ", code=" + code
                + ", total=" + clients.size());
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        Log.w(TAG, "cloud websocket error: " + ex.getMessage());
        // conn==null 的 error 是 server 级故障（典型：端口绑定失败）——放行启动确认，
        // 避免 CloudCore.start 误报成功而留下未监听的空壳 server
        if (conn == null) {
            startLatch.countDown();
        }
    }

    /**
     * 同步等待端口绑定结果（start 后调用）。
     *
     * @return true=onStart 已回调（监听就绪）；false=超时或 server 级 error（绑定失败）
     */
    public boolean awaitStarted(long timeoutMs) {
        try {
            startLatch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return startOk;
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // selector 线程内完成解帧（纯内存计算，微秒级，不阻塞收发）
        CloudFrameCodec.Result r = codec.decode(message);
        if (!r.ok) {
            try {
                conn.send(CloudFrameCodec.errorWire(r.err));
            } catch (Exception e) {
                Log.w(TAG, "send error frame failed: " + e.getMessage());
            }
            return;
        }
        inbox.offer(r.plain); // 队列满（4096）丢弃最旧——防无消费者膨胀
        if (inbox.remainingCapacity() == 0) {
            inbox.poll();
        }
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        Log.d(TAG, "ignore binary frame, size=" + message.remaining());
    }

    // ------------------------------------------------------------------
    // 对 CloudService 的通道操作
    // ------------------------------------------------------------------

    /** Lua poll：取一条明文消息（毫秒阻塞）；无消息返回 null */
    public String poll(long timeoutMs) throws InterruptedException {
        String msg = inbox.poll(Math.max(0, timeoutMs), TimeUnit.MILLISECONDS);
        return msg;
    }

    /**
     * 广播明文 JSON：由 codec 编帧（加密模式组 e/iv/d/s 帧）后发全部在线连接。
     *
     * @return 实际送达的连接数（编帧失败返回 -1——对齐原 encodeFrame 失败不落明文语义）
     */
    public int broadcastPlain(String plainJson) {
        String wire = codec.encode(plainJson);
        if (wire == null) {
            return -1;
        }
        int n = 0;
        for (WebSocket conn : clients.values()) {
            if (conn.isOpen()) {
                try {
                    conn.send(wire);
                    n++;
                } catch (Exception e) {
                    Log.w(TAG, "broadcast failed: " + e.getMessage());
                }
            }
        }
        return n;
    }

    /** 当前在线连接数 */
    public int getClientCount() {
        return clients.size();
    }

    // ------------------------------------------------------------------
    // 心跳
    // ------------------------------------------------------------------

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AutoLuaCloud-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            for (WebSocket conn : clients.values()) {
                if (conn.isOpen()) {
                    try {
                        conn.sendPing();
                    } catch (Exception e) {
                        Log.w(TAG, "heartbeat ping failed: " + e.getMessage());
                    }
                }
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        ScheduledExecutorService heartbeat = heartbeatExecutor;
        heartbeatExecutor = null;
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
    }
}
