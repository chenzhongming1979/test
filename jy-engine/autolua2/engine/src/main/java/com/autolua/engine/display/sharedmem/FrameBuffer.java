package com.autolua.engine.display.sharedmem;

import android.util.Log;

/**
 * 共享内存读侧辅助：从共享内存拷出 byte[]（读前读后比对 counter / seqlock 语义）。
 *
 * <p>对照文档 2.3.4 读协议：拷贝前后各取一次帧计数，计数未变 = 读到完整帧，
 * 最多重试 {@link #READ_RETRY} 次（与 Lua FFI seqlock 读侧等价的 Java 实现）。</p>
 */
public final class FrameBuffer {
    private static final String TAG = "FrameBuffer";

    /** seqlock 重试次数（对照 Lua 侧 retry=1,8） */
    public static final int READ_RETRY = 8;

    private final SharedMemoryManager manager;

    /** 最近一次成功拷贝的缓存与时间戳（TTL 内直接回缓存） */
    private byte[] cache;
    private long cacheTime;
    /** 缓存对应的帧计数（freshOnly 判定依据） */
    private long cacheCounter = Long.MIN_VALUE;

    /** 从帧内容解析出的尺寸（App 模式取自 9 字节头，Root 模式取自管理器） */
    private volatile int frameWidth;
    private volatile int frameHeight;

    public FrameBuffer(SharedMemoryManager manager) {
        this.manager = manager;
    }

    /**
     * 读一帧（带 seqlock 一致性比对）：counter 前后一致才返回，最多重试 8 次。
     *
     * @return 原样帧字节（App 模式含 9 字节自描述头），失败返回 null
     */
    public byte[] readFrame() {
        for (int i = 0; i < READ_RETRY; i++) {
            long c1 = manager.getFrameCounter();
            byte[] raw = manager.readFrameRaw();
            long c2 = manager.getFrameCounter();
            if (raw == null) {
                return null;
            }
            if (c1 == c2) {
                // counter 未变 = 读到完整帧
                parseHeader(raw);
                return raw;
            }
            Log.w(TAG, "readFrame retry #" + (i + 1) + " (counter " + c1 + " -> " + c2 + ")");
        }
        return null;
    }

    /**
     * 取最新帧（带缓存）。
     *
     * @param freshOnly true=仅当自上次返回后有新帧才返回（无新帧返回 null）；
     *                  false=尽量返回最近一帧
     * @param ttlMs     缓存 TTL：<TTL 内直接回缓存不做拷贝（调用方常用 1000ms）
     * @return 原样帧字节（App 模式含 9 字节自描述头），无帧返回 null
     */
    public synchronized byte[] getLatestFrame(boolean freshOnly, long ttlMs) {
        long now = System.currentTimeMillis();
        long current = manager.getFrameCounter();
        if (freshOnly && cacheCounter == current) {
            return null; // 无新帧
        }
        if (!freshOnly && cache != null && ttlMs > 0 && now - cacheTime < ttlMs) {
            return cache; // TTL 内直接回缓存
        }
        byte[] frame = readFrame();
        if (frame == null) {
            return cache; // 拷贝失败回退上次缓存
        }
        cache = frame;
        cacheTime = now;
        cacheCounter = current;
        return frame;
    }

    /** 解析帧尺寸：App 模式读 9 字节头，Root seqlock 模式取管理器记录尺寸 */
    private void parseHeader(byte[] raw) {
        if (!manager.isSeqlockMode() && raw.length >= SharedMemoryManager.HEADER_SIZE) {
            frameWidth = ((raw[1] & 0xFF) << 24) | ((raw[2] & 0xFF) << 16)
                    | ((raw[3] & 0xFF) << 8) | (raw[4] & 0xFF);
            frameHeight = ((raw[5] & 0xFF) << 24) | ((raw[6] & 0xFF) << 16)
                    | ((raw[7] & 0xFF) << 8) | (raw[8] & 0xFF);
        } else {
            frameWidth = manager.getScreenWidth();
            frameHeight = manager.getScreenHeight();
        }
    }

    /** 帧宽（像素） */
    public int getFrameWidth() {
        return frameWidth;
    }

    /** 帧高（像素） */
    public int getFrameHeight() {
        return frameHeight;
    }

    /**
     * 帧数据区相对帧字节 starting 偏移：App ping-pong 模式帧首 9 字节是自描述头，
     * Root seqlock 模式无头。
     */
    public int getHeaderOffset() {
        if (manager.isSeqlockMode()) {
            return 0;
        }
        return manager.getSharedMemorySize() > 0 ? SharedMemoryManager.HEADER_SIZE : 0;
    }
}
