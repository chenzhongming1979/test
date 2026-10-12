package com.autolua.engine.extension.virtualdisplay;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.autolua.engine.display.sharedmem.SharedMemoryManager;
import com.autolua.engine.input.inject.ShizukuInjectorChannel;

import java.io.FileDescriptor;
import java.nio.ByteBuffer;

/**
 * 虚拟屏客户端帧读取（虚拟屏任务书 §4.4）：
 * attach 时经 {@link ShizukuInjectorChannel#attachFrameSink} 拿 A/B 两块 fd
 * （ParcelFileDescriptor，binder 传输已 dup 至本进程）→
 * {@link SharedMemoryManager#mapFdToByteBuffer} 同进程 native mmap → getFrame() 按
 * 跨进程 seqlock 协议取完整帧像素。
 *
 * <p>读侧协议（服务端 ShizukuUserServiceMain.VdEntry.publishFrame 与
 * SharedMemoryManager.writeFrameSwap 同序）：写 inactive 块 → counter++ → 双块写 9 字节
 * 自描述头 [0]=bufferIndex(byte)、[1..4]=w(int BE)、[5..8]=h(int BE)，像素自第 9 字节起。
 * 读侧：AIDL 轮询 counter → 偶数 → 读 9 字节头校验（bufferIndex/w/h）→ 拷贝像素区 →
 * 再读 counter 前后一致才交付，重试 ≤8 次（对齐 FrameBuffer.READ_RETRY）；counter=0
 * （从未出帧）或校验失败返回 null。counter 轮询走 binder（读侧两个 binder 调用/帧），
 * 像素读走本进程 mmap 零中间拷贝。</p>
 */
public final class VdFrameClient {

    private static final String TAG = "VdFrameClient";

    /** seqlock 读侧重试次数（对齐 FrameBuffer.READ_RETRY = 8） */
    private static final int READ_RETRY = 8;

    private final ShizukuInjectorChannel channel;
    private final int displayId;
    private final int width;
    private final int height;
    private final int blockSize;
    /** PFD 持有引用：其 finalizer 会关闭所持 fd，必须与 mmap 生命周期同存 */
    private final ParcelFileDescriptor pfdA;
    private final ParcelFileDescriptor pfdB;
    /** A/B 块 mmap 视图（native 映射；null = 映射失败或已释放） */
    private volatile ByteBuffer blockA;
    private volatile ByteBuffer blockB;

    private VdFrameClient(ShizukuInjectorChannel channel, int displayId,
                          int width, int height,
                          ParcelFileDescriptor pfdA, ParcelFileDescriptor pfdB) {
        this.channel = channel;
        this.displayId = displayId;
        this.width = width;
        this.height = height;
        this.blockSize = width * height * 4 + SharedMemoryManager.HEADER_SIZE;
        this.pfdA = pfdA;
        this.pfdB = pfdB;
    }

    /**
     * attach：拿 fd + native 映射。任一步失败返回 null（调用方应销毁该屏并报可读错误）。
     */
    public static VdFrameClient attach(ShizukuInjectorChannel channel, int displayId,
                                       int width, int height) {
        if (!SharedMemoryManager.isNativeOk()) {
            Log.e(TAG, "attach failed: native libengine not loaded, mmap unavailable");
            return null;
        }
        ParcelFileDescriptor[] pfds = channel.attachFrameSink(displayId, new int[2]);
        if (pfds == null || pfds.length < 2 || pfds[0] == null || pfds[1] == null) {
            Log.e(TAG, "attach failed: displayId=" + displayId + " (sink not attached)");
            return null;
        }
        VdFrameClient client = new VdFrameClient(channel, displayId, width, height,
                pfds[0], pfds[1]);
        try {
            FileDescriptor fdA = pfds[0].getFileDescriptor();
            FileDescriptor fdB = pfds[1].getFileDescriptor();
            client.blockA = SharedMemoryManager.mapFdToByteBuffer(
                    SharedMemoryManager.getFdNumber(fdA), client.blockSize);
            client.blockB = SharedMemoryManager.mapFdToByteBuffer(
                    SharedMemoryManager.getFdNumber(fdB), client.blockSize);
        } catch (Throwable t) {
            Log.e(TAG, "mmap failed: " + t);
            client.blockA = null;
            client.blockB = null;
        }
        if (client.blockA == null || client.blockB == null) {
            Log.e(TAG, "attach failed: mapFdToByteBuffer returned null");
            return null;
        }
        Log.i(TAG, "attached: displayId=" + displayId + " " + client.width + "x" + client.height
                + " block=" + client.blockSize);
        return client;
    }

    public int getDisplayId() {
        return displayId;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /**
     * 取最新完整帧像素（w*h*4 紧凑 RGBA 拷贝）。
     *
     * @return 像素 byte[]；从未出帧（counter=0）、校验失败重试耗尽、通道不可用返回 null
     */
    public byte[] getFrame() {
        ByteBuffer a = blockA;
        ByteBuffer b = blockB;
        if (a == null || b == null) {
            return null;
        }
        for (int attempt = 0; attempt < READ_RETRY; attempt++) {
            long c1;
            try {
                c1 = channel.getFrameCounter(displayId);
            } catch (Throwable t) {
                // UserService 死亡/重绑（versionTag 失配、Shizuku 重启）：转 null 由组合层报可读错误
                Log.w(TAG, "getFrameCounter failed (displayId=" + displayId + "): " + t);
                return null;
            }
            if (c1 <= 0) {
                return null; // 从未有帧（counter 写完成才递增）
            }
            if ((c1 & 1L) != 0L) {
                continue; // 奇数 = 写进行中（跨进程视角），短暂重试
            }
            // 9 字节自描述头读自 A 块（协议保证双块头内容同步一致）
            int headerIndex = a.get(0) & 0xFF;
            int w = a.getInt(1);
            int h = a.getInt(5);
            if ((headerIndex != 0 && headerIndex != 1) || w != width || h != height) {
                // 头瞬态窗口（counter++ 后头写完成的跨进程传播延迟）或尺寸不符：整帧作废重读
                continue;
            }
            ByteBuffer src = (headerIndex == 0 ? a : b).duplicate();
            src.position(SharedMemoryManager.HEADER_SIZE);
            byte[] out = new byte[width * height * 4];
            src.get(out);
            long c2;
            try {
                c2 = channel.getFrameCounter(displayId);
            } catch (Throwable t) {
                Log.w(TAG, "getFrameCounter(c2) failed (displayId=" + displayId + "): " + t);
                return null;
            }
            if (c1 == c2) {
                return out; // 读窗口内无写完成：完整帧
            }
            // counter 变化：读到的可能是半新半旧，作废重读
        }
        Log.w(TAG, "getFrame: read retry exhausted (displayId=" + displayId + ")");
        return null;
    }

    /** 释放引用（服务端 MemoryFile 生命周期由 destroyVirtualDisplay 管理；mmap 随 buffer GC 归还） */
    public void release() {
        blockA = null;
        blockB = null;
    }
}

