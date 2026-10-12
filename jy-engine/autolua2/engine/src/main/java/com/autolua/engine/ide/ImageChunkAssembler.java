package com.autolua.engine.ide;

import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import com.google.gson.JsonObject;

/**
 * [IMG] 输出分片组装器（协议对照派大星 print_wrapper.lua:28-48 / WebSocketApi.handleImageChunk）：
 * <pre>
 *   [IMG]S|w|h|totalChunks —— 开始收集（totalChunks 可缺省，w/h 可为 0）
 *   [IMG]D|i|base64        —— 追加第 i 片（i 从 1 起，严格顺序校验，乱序丢弃会话）
 *   [IMG]E                 —— 完成：校验分片数、base64 解码 PNG 校验魔数后，
 *                             广播 {"type":"image","data":{"width","height","base64"}}
 * </pre>
 * 防护：单帧 base64 累计超 {@link WsProtocol#IMG_MAX_BASE64_BYTES}（8MB）丢弃；
 * 会话 {@link WsProtocol#IMG_SESSION_TIMEOUT_MS}（30s）内未完成在下一事件到达时丢弃
 * （惰性超时清理，无需独立调度线程）。
 * 组装结果为超大 base64 文本帧，Java-WebSocket 支持大帧，直接广播给全部在线连接。
 */
public class ImageChunkAssembler {

    private static final String TAG = "AutoLuaIde";
    /** PNG 文件魔数（89 50 4E 47 0D 0A 1A 0A） */
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    /** 组装完成回调（IdeServerManager 注入：向全部在线连接广播） */
    interface ImageBroadcaster {
        void onImage(String messageJson);
    }

    private final ImageBroadcaster broadcaster;

    // ---- 会话状态（feed 同步串行，消息处理线程池并发安全）----
    private int width;
    private int height;
    private int totalChunks;
    private int received;
    private long startElapsed;
    private boolean active;
    private final StringBuilder base64 = new StringBuilder();

    public ImageChunkAssembler(ImageBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    /** 处理一行 [IMG] 协议输出（非 [IMG] 前缀的行不进入此方法，由 IdeServerManager 分流） */
    public synchronized void feed(String line) {
        try {
            if (line.startsWith("[IMG]S|")) {
                handleStart(line);
            } else if (line.startsWith("[IMG]D|")) {
                handleData(line);
            } else if (line.startsWith("[IMG]E")) {
                handleEnd();
            }
        } catch (Exception e) {
            Log.w(TAG, "image chunk feed failed: " + e);
            reset();
        }
    }

    /** S：开始新会话（覆盖未完成的旧会话） */
    private void handleStart(String line) {
        String[] parts = line.split("\\|");
        reset();
        width = parts.length >= 2 ? parseInt(parts[1], 0) : 0;
        height = parts.length >= 3 ? parseInt(parts[2], 0) : 0;
        totalChunks = parts.length >= 4 ? parseInt(parts[3], 0) : 0;
        received = 0;
        startElapsed = SystemClock.elapsedRealtime();
        active = true;
    }

    /** D：校验 index 顺序后追加分片 base64 */
    private void handleData(String line) {
        if (!active) {
            return;
        }
        if (expired()) {
            Log.w(TAG, "image chunk session expired (>30s), discarded");
            reset();
            return;
        }
        // "[IMG]D|i|data"：index 7 起是 i，第二个 '|' 后是 data
        int sep = line.indexOf('|', 7);
        if (sep <= 7) {
            throw new IllegalArgumentException("bad [IMG]D line");
        }
        int index = Integer.parseInt(line.substring(7, sep).trim());
        if (index != received + 1) {
            throw new IllegalStateException("image chunk index out of order, expect "
                    + (received + 1) + " got " + index);
        }
        String data = line.substring(sep + 1);
        if (data.isEmpty()) {
            throw new IllegalArgumentException("empty image chunk data");
        }
        if (base64.length() + data.length() > WsProtocol.IMG_MAX_BASE64_BYTES) {
            throw new IllegalStateException("image base64 exceeds "
                    + WsProtocol.IMG_MAX_BASE64_BYTES + " bytes");
        }
        base64.append(data);
        received++;
    }

    /** E：校验完整性 → 解码校验 PNG 魔数 → 广播 image 回包 */
    private void handleEnd() {
        if (!active) {
            return;
        }
        try {
            if (expired()) {
                Log.w(TAG, "image chunk session expired (>30s), discarded");
                return;
            }
            if (base64.length() == 0) {
                Log.w(TAG, "[IMG]E without data, discarded");
                return;
            }
            if (totalChunks > 0 && received != totalChunks) {
                Log.w(TAG, "image chunks incomplete: expect " + totalChunks
                        + " got " + received + ", discarded");
                return;
            }
            // base64 解码得到 PNG 字节并校验魔数（防垃圾数据占用广播带宽）
            byte[] png = Base64.decode(base64.toString(), Base64.DEFAULT);
            if (png.length < PNG_MAGIC.length || !startsWith(png, PNG_MAGIC)) {
                Log.w(TAG, "assembled image is not a PNG, discarded");
                return;
            }
            JsonObject data = new JsonObject();
            data.addProperty("width", width);
            data.addProperty("height", height);
            data.addProperty("base64", base64.toString());
            JsonObject msg = new JsonObject();
            msg.addProperty("type", WsProtocol.MSG_IMAGE);
            msg.add("data", data);
            if (broadcaster != null) {
                broadcaster.onImage(msg.toString());
            }
            Log.d(TAG, "image assembled: " + width + "x" + height
                    + ", base64=" + base64.length());
        } finally {
            reset();
        }
    }

    /** 会话是否已超时（30s 内未完成） */
    private boolean expired() {
        return SystemClock.elapsedRealtime() - startElapsed > WsProtocol.IMG_SESSION_TIMEOUT_MS;
    }

    /** 清空会话状态 */
    private void reset() {
        active = false;
        width = 0;
        height = 0;
        totalChunks = 0;
        received = 0;
        base64.setLength(0);
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
