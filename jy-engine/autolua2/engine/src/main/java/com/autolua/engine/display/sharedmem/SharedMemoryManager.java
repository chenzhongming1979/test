package com.autolua.engine.display.sharedmem;

import android.os.MemoryFile;
import android.util.Log;

import java.io.FileDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 共享内存 A/B 双缓冲管理器（单例），对照派大星 SharedMemoryManager.java。
 *
 * <p>协议与集成文档 2.3.4 严格一致：</p>
 * <ul>
 *     <li>建立：MemoryFile("autolua_shot_a"/"autolua_shot_b", w*h*4) → 反射取 fd →
 *         native mapFdToByteBuffer(fd, size) mmap → nativeSetSharedMemoryInfo 下沉给 C++ 引擎；
 *         失败回退 ByteBuffer.allocateDirect；</li>
 *     <li>Root 模式写（seqlock，固定写 A）：counter 奇 → 写 → counter 偶；</li>
 *     <li>App 模式写（ping-pong）：写 inactive → 翻 readBufferIndex → counter++ →
 *         9 字节自描述头 [bufferIndex byte][width int BE][height int BE] 写入两块。</li>
 * </ul>
 */
public final class SharedMemoryManager {
    private static final String TAG = "SharedMemoryManager";

    /** 两块共享内存的 ashmem 名字 */
    private static final String FILE_A = "autolua_shot_a";
    private static final String FILE_B = "autolua_shot_b";

    /** 9 字节自描述头：[0]=bufferIndex(byte)，[1..4]=width(int BE)，[5..8]=height(int BE) */
    public static final int HEADER_SIZE = 9;

    /** 写帧后回调（供 C++ 侧同步帧计数 / Java 读侧缓存失效） */
    public interface FrameListener {
        void onFrameWritten(long frameCounter, int width, int height);
    }

    private static volatile SharedMemoryManager sInstance;

    /** 引擎 so 是否加载成功（app_process 等 classloader 受限环境下可能失败） */
    private static final boolean NATIVE_OK;

    static {
        boolean ok = false;
        try {
            // 库名跟随引擎主 so（CMakeLists: project("engine")，AutoLuaEngineOnLocal 同名加载）
            System.loadLibrary("engine");
            ok = true;
        } catch (Throwable t) {
            Log.e(TAG, "load libengine failed, native mmap unavailable: " + t);
        }
        NATIVE_OK = ok;
    }

    private MemoryFile memoryFileA;
    private MemoryFile memoryFileB;
    private volatile ByteBuffer sharedMemoryA;
    private volatile ByteBuffer sharedMemoryB;
    private volatile long sharedMemoryAddressA;
    private volatile long sharedMemoryAddressB;
    private volatile int sharedMemorySize;
    private volatile int screenWidth;
    private volatile int screenHeight;
    private volatile int savedFdA = -1;
    private volatile int savedFdB = -1;
    /** 当前写模式：true=Root seqlock（固定写 A），false=App ping-pong */
    private volatile boolean seqlockMode;
    /** 读侧当前缓冲下标（ping-pong 用） */
    private volatile int readBufferIndex = 1;

    private final Object sharedMemoryLock = new Object();
    /** 帧计数器：Root seqlock 每帧 +2（奇=写进行中，偶=写完成）；App ping-pong 每帧 +1 */
    private final AtomicLong frameCounter = new AtomicLong(0);
    private volatile FrameListener frameListener;

    private SharedMemoryManager() {
    }

    public static SharedMemoryManager getInstance() {
        if (sInstance == null) {
            synchronized (SharedMemoryManager.class) {
                if (sInstance == null) {
                    sInstance = new SharedMemoryManager();
                }
            }
        }
        return sInstance;
    }

    // ------------------------------------------------------------------
    // 建立 / 释放
    // ------------------------------------------------------------------

    /**
     * 建立两块共享内存（尺寸变化时重建）。跨进程 fd 传输（dupPfd）下一批接入。
     *
     * @return 地址有效即视为成功
     */
    public boolean init(int width, int height) {
        synchronized (sharedMemoryLock) {
            if (sharedMemoryA != null && sharedMemoryAddressA != 0
                    && screenWidth == width && screenHeight == height) {
                return true;
            }
            releaseInternal();
            screenWidth = width;
            screenHeight = height;
            // buffer 总大小 = 9 字节自描述头 + 像素数据（App 模式头与像素共存于同一 buffer，
            // 头写 [0..8]，像素写 [9..]，此前像素从 0 写入被头覆盖前 9 字节导致读侧尺寸差 9）
            sharedMemorySize = width * height * 4 + HEADER_SIZE;
            Log.i(TAG, "init: " + width + "x" + height + ", size=" + sharedMemorySize);
            try {
                memoryFileA = new MemoryFile(FILE_A, sharedMemorySize);
                memoryFileB = new MemoryFile(FILE_B, sharedMemorySize);
                int fdA = getMemoryFileFd(memoryFileA);
                int fdB = getMemoryFileFd(memoryFileB);
                if (fdA <= 0 || fdB <= 0) {
                    throw new RuntimeException("Failed to get MemoryFile fd");
                }
                if (!NATIVE_OK) {
                    throw new RuntimeException("native libengine not loaded");
                }
                sharedMemoryA = mapFdToByteBuffer(fdA, sharedMemorySize);
                sharedMemoryB = mapFdToByteBuffer(fdB, sharedMemorySize);
                if (sharedMemoryA == null || sharedMemoryB == null) {
                    throw new RuntimeException("mapFdToByteBuffer failed");
                }
                sharedMemoryAddressA = getDirectBufferAddress(sharedMemoryA);
                sharedMemoryAddressB = getDirectBufferAddress(sharedMemoryB);
                savedFdA = fdA;
                savedFdB = fdB;
                readBufferIndex = 1;
                Log.i(TAG, "MemoryFile created: fdA=" + fdA + ", fdB=" + fdB
                        + ", addrA=" + sharedMemoryAddressA + ", addrB=" + sharedMemoryAddressB);
                pushNativeInfo();
                return sharedMemoryAddressA != 0;
            } catch (Exception e) {
                Log.w(TAG, "MemoryFile failed, falling back to ByteBuffer: " + e.getMessage());
                closeMemoryFilesLocked();
                // 回退：进程内直接 ByteBuffer（无 fd，跨进程不可用）
                sharedMemoryA = ByteBuffer.allocateDirect(sharedMemorySize);
                sharedMemoryB = ByteBuffer.allocateDirect(sharedMemorySize);
                sharedMemoryAddressA = NATIVE_OK ? getDirectBufferAddress(sharedMemoryA) : 0;
                sharedMemoryAddressB = NATIVE_OK ? getDirectBufferAddress(sharedMemoryB) : 0;
                savedFdA = -1;
                savedFdB = -1;
                readBufferIndex = 1;
                pushNativeInfo();
                return sharedMemoryAddressA != 0;
            }
        }
    }

    /** 把共享内存信息（地址/尺寸）下沉给 C++ 引擎侧（SharedFrame） */
    private void pushNativeInfo() {
        if (!NATIVE_OK) {
            return;
        }
        try {
            nativeSetSharedMemoryInfo(sharedMemoryAddressA, sharedMemoryAddressB,
                    sharedMemorySize, screenWidth, screenHeight);
        } catch (Throwable t) {
            Log.e(TAG, "setSharedMemoryInfoNative failed: " + t);
        }
    }

    /** 关闭并复位（fd / 缓冲 / 地址）。帧计数器不复位，保证 seqlock 语义连续 */
    public void close() {
        synchronized (sharedMemoryLock) {
            releaseInternal();
        }
    }

    private void releaseInternal() {
        sharedMemoryA = null;
        sharedMemoryB = null;
        sharedMemoryAddressA = 0;
        sharedMemoryAddressB = 0;
        sharedMemorySize = 0;
        screenWidth = 0;
        screenHeight = 0;
        savedFdA = -1;
        savedFdB = -1;
        closeMemoryFilesLocked();
        if (NATIVE_OK) {
            try {
                nativeSetSharedMemoryInfo(0, 0, 0, 0, 0);
            } catch (Throwable ignore) {
            }
        }
        Log.i(TAG, "Shared memory released");
    }

    private void closeMemoryFilesLocked() {
        if (memoryFileA != null) {
            try {
                memoryFileA.close();
            } catch (Exception ignore) {
            }
            memoryFileA = null;
        }
        if (memoryFileB != null) {
            try {
                memoryFileB.close();
            } catch (Exception ignore) {
            }
            memoryFileB = null;
        }
    }

    // ------------------------------------------------------------------
    // fd
    // ------------------------------------------------------------------

    /**
     * 返回两块共享内存的 fd（跨进程传输下一批接：ParcelFileDescriptor.dup(pfd)）。
     * 返回 [fdA, fdB]，无效为 -1。
     */
    public int[] getFds() {
        synchronized (sharedMemoryLock) {
            if (savedFdA <= 0 && memoryFileA != null) {
                savedFdA = getMemoryFileFd(memoryFileA);
            }
            if (savedFdB <= 0 && memoryFileB != null) {
                savedFdB = getMemoryFileFd(memoryFileB);
            }
            return new int[]{savedFdA, savedFdB};
        }
    }

    /**
     * 反射取 MemoryFile 的 fd（对照 SharedMemoryManager.java:69-86）：
     * MemoryFile.getFileDescriptor()（隐藏方法）→ FileDescriptor.descriptor 字段。
     * public：虚拟屏按屏帧泵（ShizukuUserServiceMain）与客户端映射（VdFrameClient）复用同一提取逻辑。
     */
    public static int getMemoryFileFd(MemoryFile memoryFile) {
        if (memoryFile == null) {
            return -1;
        }
        try {
            Method getFileDescriptor = MemoryFile.class.getDeclaredMethod("getFileDescriptor");
            getFileDescriptor.setAccessible(true);
            FileDescriptor fd = (FileDescriptor) getFileDescriptor.invoke(memoryFile);
            if (fd != null) {
                return getFdNumber(fd);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get MemoryFile fd: " + e.getMessage());
        }
        return -1;
    }

    /**
     * FileDescriptor → int fd（descriptor 字段反射，public）。
     * 虚拟屏客户端（VdFrameClient）：AIDL attachFrameSink 收到的 fd（binder 已 dup 至本进程）
     * 需转 int 后交给 mapFdToByteBuffer native 映射。
     */
    public static int getFdNumber(FileDescriptor fd) {
        if (fd == null) {
            return -1;
        }
        try {
            Field descriptor = FileDescriptor.class.getDeclaredField("descriptor");
            descriptor.setAccessible(true);
            return descriptor.getInt(fd);
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract fd number: " + e.getMessage());
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // 写协议（与文档 2.3.4 严格一致）
    // ------------------------------------------------------------------

    /**
     * Root 模式写帧（seqlock，固定写 A，对照 :417-438）：
     * counter++（奇数=写进行中）→ 写入 A → counter++（偶数=写完成）。
     */
    public boolean writeFrameSeqlock(byte[] frame) {
        if (frame == null) {
            return false;
        }
        synchronized (sharedMemoryLock) {
            try {
                if (sharedMemoryA == null) {
                    return false;
                }
                // Root 布局：像素自 0 开始（无自描述头），buffer 尾部 9 字节为预留
                if (frame.length != sharedMemorySize - HEADER_SIZE) {
                    Log.w(TAG, "writeFrameSeqlock: size mismatch, expected=" + (sharedMemorySize - HEADER_SIZE)
                            + ", got=" + frame.length);
                    return false;
                }
                seqlockMode = true;
                frameCounter.incrementAndGet(); // 写前置 → 奇数 = 写进行中
                sharedMemoryA.rewind();
                sharedMemoryA.put(frame, 0, sharedMemorySize - HEADER_SIZE);
                long done = frameCounter.incrementAndGet(); // 写后置 → 偶数 = 写完成
                notifyFrameWritten(done, screenWidth, screenHeight);
                return true;
            } catch (Throwable t) {
                Log.e(TAG, "writeFrameSeqlock error: " + t);
                return false;
            }
        }
    }

    /**
     * App 模式写帧（ping-pong，对照 writeFrameWithSwap :440-474）：
     * 写 inactive → 翻 readBufferIndex → counter++ → 9 字节自描述头写入两块。
     * 头部会覆盖帧首 9 字节（与参考实现一致，读侧按自描述头解析）。
     */
    public boolean writeFrameSwap(byte[] frame, int w, int h) {
        if (frame == null) {
            return false;
        }
        int needed = w * h * 4;
        synchronized (sharedMemoryLock) {
            try {
                if (sharedMemoryA == null || sharedMemoryB == null) {
                    return false;
                }
                if (sharedMemoryA.capacity() < needed + HEADER_SIZE) {
                    Log.w(TAG, "writeFrameSwap: capacity too small, capacity=" + sharedMemoryA.capacity()
                            + ", needed=" + (needed + HEADER_SIZE));
                    return false;
                }
                seqlockMode = false;
                // 1) 写 inactive 块（像素自 HEADER_SIZE 偏移起，[0..8] 留给自描述头）
                int target = 1 - readBufferIndex;
                ByteBuffer targetBuffer = target == 0 ? sharedMemoryA : sharedMemoryB;
                targetBuffer.clear();
                targetBuffer.position(HEADER_SIZE);
                targetBuffer.put(frame, 0, Math.min(frame.length, needed));
                targetBuffer.rewind();
                // 2) 翻 readBufferIndex
                readBufferIndex = target;
                // 3) counter++
                long counter = frameCounter.incrementAndGet();
                // 4) 9 字节自描述头写入两块（putInt 默认大端，符合协议 int BE）
                targetBuffer.put(0, (byte) readBufferIndex);
                targetBuffer.putInt(1, w);
                targetBuffer.putInt(5, h);
                ByteBuffer other = target == 0 ? sharedMemoryB : sharedMemoryA;
                if (other != null && other.capacity() >= HEADER_SIZE) {
                    other.put(0, (byte) readBufferIndex);
                    other.putInt(1, w);
                    other.putInt(5, h);
                }
                notifyFrameWritten(counter, w, h);
                return true;
            } catch (Throwable t) {
                Log.e(TAG, "writeFrameSwap error: " + t);
                return false;
            }
        }
    }

    /** 读侧原样拷贝当前读缓冲（Root 模式=A，App 模式=readBufferIndex 指向块） */
    public byte[] readFrameRaw() {
        synchronized (sharedMemoryLock) {
            try {
                if (sharedMemoryA == null || sharedMemoryB == null || sharedMemorySize <= 0) {
                    return null;
                }
                ByteBuffer buffer = seqlockMode
                        ? sharedMemoryA
                        : (readBufferIndex == 0 ? sharedMemoryA : sharedMemoryB);
                byte[] out = new byte[sharedMemorySize];
                buffer.rewind();
                buffer.get(out);
                return out;
            } catch (Throwable t) {
                Log.e(TAG, "readFrameRaw error: " + t);
                return null;
            }
        }
    }

    private void notifyFrameWritten(long counter, int w, int h) {
        FrameListener listener = frameListener;
        if (listener != null) {
            try {
                listener.onFrameWritten(counter, w, h);
            } catch (Throwable ignore) {
            }
        }
    }

    /** 注册写帧回调（写帧后回调，供 C++ 侧同步帧计数） */
    public void setFrameListener(FrameListener listener) {
        frameListener = listener;
    }

    // ------------------------------------------------------------------
    // 状态查询
    // ------------------------------------------------------------------

    /** 帧计数器（seqlock/ping-pong 共用，Lua FFI seqlock 读侧依据） */
    public long getFrameCounter() {
        return frameCounter.get();
    }

    /** 读侧当前缓冲下标（ping-pong） */
    public int getReadBufferIndex() {
        return readBufferIndex;
    }

    /**
     * 读侧取指定块的 mmap 映射视图（0=A，1=B），未映射返回 null。
     * 零拷贝消费入口（SharedMemoryDisplay 零拷贝视图，虚拟屏任务书 §2）；
     * 与 readFrameRaw() 的整块拷贝路径互不影响。
     */
    public ByteBuffer getBuffer(int index) {
        synchronized (sharedMemoryLock) {
            return index == 0 ? sharedMemoryA : sharedMemoryB;
        }
    }

    public long getAddressA() {
        return sharedMemoryAddressA;
    }

    public long getAddressB() {
        return sharedMemoryAddressB;
    }

    public int getSharedMemorySize() {
        return sharedMemorySize;
    }

    public int getScreenWidth() {
        return screenWidth;
    }

    public int getScreenHeight() {
        return screenHeight;
    }

    /** 是否 Root seqlock 模式（读侧选择缓冲与头部解析依据） */
    public boolean isSeqlockMode() {
        return seqlockMode;
    }

    /** native 库是否可用 */
    public static boolean isNativeOk() {
        return NATIVE_OK;
    }

    // ------------------------------------------------------------------
    // native（实现见 cpp/extension/SharedFrame.cpp）
    // ------------------------------------------------------------------

    /**
     * mmap fd → direct ByteBuffer（对照 libfast_screenshot 的同名符号）。
     * public：虚拟屏客户端 VdFrameClient（engine\extension\virtualdisplay）attach 后
     * 用同款 native 映射 A/B 块（同进程 native 可用，虚拟屏任务书 §4.4）。
     */
    public static native ByteBuffer mapFdToByteBuffer(int fd, int size);

    /** 取 direct ByteBuffer 的裸地址 */
    private static native long getDirectBufferAddress(ByteBuffer buffer);

    /** 下发共享内存信息（addrA/addrB/size/w/h）给 C++ 引擎，存全局指针 */
    private static native void nativeSetSharedMemoryInfo(long addrA, long addrB, int size, int w, int h);

    /** C++ 侧取实时帧计数（经 JNI 回调本类 getFrameCounter()） */
    public static native long nativeGetFrameCounter();
}
