package com.autolua.engine.extension.display;

import android.content.res.Resources;
import android.util.Log;

import com.autolua.engine.display.sharedmem.SharedMemoryManager;

import java.nio.ByteBuffer;

/**
 * 无 root 图色地基：把 MediaProjection 共享内存帧管线（CaptureScreenService →
 * SharedMemoryManager seqlock A/B 双缓冲）适配成引擎 Display 接口的桥（虚拟屏任务书 §2）。
 *
 * <p>读侧协议（ipc/contracts.md §⑦）：App ping-pong 模式读 {@code readBufferIndex} 发布块，
 * 9 字节自描述头 [0]=bufferIndex(byte)、[1..4]=width(int BE)、[5..8]=height(int BE)，
 * 像素自第 9 字节偏移起；Root seqlock 模式（writeFrameSeqlock，固定写 A）像素自 0 起，
 * counter 奇数=写进行中、偶数=写完成。update() 读前读后两次取 frameCounter，不一致或写中
 * 则整帧作废重读（对齐 FrameBuffer.READ_RETRY=8 语义）；锁定"最近一次写完成"的块后暴露
 * 该块像素 DirectByteBuffer（零拷贝，经 ByteBuffer.slice 跳过 9 字节头，C++ 侧
 * GetDirectBufferAddress 直读）与 w/h。读不到帧时保持上一帧视图（不阻塞、不抛异常，
 * hasRealFrame=false）；连续无帧不做任何 sleep 等待（写侧 50ms 节拍自己会推帧）。</p>
 *
 * <p>生命周期自愈：CaptureScreenService 停止/重授权会 SharedMemoryManager.init 重入
 * （releaseInternal + 新建映射），update() 每次比对地址/尺寸快照，失效即作废旧视图重拉。</p>
 *
 * <p>字节序（设计关键，见 engine-core/design.md §字节序）：本管线帧为 RGBA、R 在 [0]
 * （VisionBridge.cpp:12-16 注释口径），isRgbaOrder() 返回 true——与 root 镜像
 * DisplayImplement 的 BGRA 历史口径相反，待真机实测复核。</p>
 */
public class SharedMemoryDisplay implements Display {

    private static final String TAG = "SharedMemoryDisplay";

    /** seqlock 读侧重试次数（对齐 FrameBuffer.READ_RETRY = 8） */
    private static final int READ_RETRY = 8;

    /** 方向语义与 DisplayImplement 一致：竖屏 1 / 横屏 -1 */
    private static final int DIRECTION_VERTICAL = 1;
    private static final int DIRECTION_LEVEL = -1;

    /** 无帧兜底空 buffer（非 direct，C++ GetDirectBufferAddress 返回 NULL，同 EmptyDisplay） */
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

    private final SharedMemoryManager manager = SharedMemoryManager.getInstance();

    /** 最近一次锁定成功的完整帧视图：像素起点 DirectByteBuffer（null = 尚未锁定过任何帧） */
    private volatile ByteBuffer frameBuffer;
    private volatile int frameWidth;
    private volatile int frameHeight;
    /** 锁定视图时的映射快照：CaptureScreenService 停止/重授权会 init 重入换映射，失效即重拉 */
    private volatile long viewAddressA;
    private volatile long viewAddressB;
    private volatile int viewSize;
    /** 是否成功锁定过至少一个完整帧 */
    private volatile boolean realFrame = false;
    /** initialize 请求尺寸（尺寸由写侧决定，不符仅告警） */
    private volatile int requestedWidth;
    private volatile int requestedHeight;

    @Override
    public int getBaseWidth() {
        return manager.getScreenWidth();
    }

    @Override
    public int getBaseHeight() {
        return manager.getScreenHeight();
    }

    @Override
    public int getBaseDirection() {
        int w = manager.getScreenWidth();
        int h = manager.getScreenHeight();
        return w > h ? DIRECTION_LEVEL : DIRECTION_VERTICAL;
    }

    @Override
    public int getRotation() {
        return 0;
    }

    @Override
    public int getBaseDensity() {
        return Resources.getSystem().getDisplayMetrics().densityDpi;
    }

    @Override
    public void destroy() {
        // 共享内存归 SharedMemoryManager 单例管理（写侧 CaptureScreenService 生命周期），此处不释放
    }

    @Override
    public int getDirection() {
        // 镜像管线帧按当前屏幕方向输出（写侧 onConfigurationChanged 自建管线），固定跟随不旋转
        return getBaseDirection();
    }

    @Override
    public boolean isChangeDirection() {
        return false;
    }

    @Override
    public void update() {
        long addrA = manager.getAddressA();
        long addrB = manager.getAddressB();
        int size = manager.getSharedMemorySize();
        if (addrA != viewAddressA || addrB != viewAddressB || size != viewSize) {
            // 映射重建（服务停止/重授权 SharedMemoryManager.init 重入）：旧视图作废，重新锁定
            frameBuffer = null;
            viewAddressA = addrA;
            viewAddressB = addrB;
            viewSize = size;
        }
        if (addrA == 0 || addrB == 0 || size <= SharedMemoryManager.HEADER_SIZE) {
            return; // 尚未映射：保持无帧视图（不阻塞；写侧 init 后自动恢复）
        }
        for (int attempt = 0; attempt < READ_RETRY; attempt++) {
            long c1 = manager.getFrameCounter();
            if (c1 <= 0) {
                return; // 从未有帧（counter 写完成才递增）
            }
            boolean seqlockMode = manager.isSeqlockMode();
            int index;
            if (seqlockMode) {
                if ((c1 & 1L) != 0L) {
                    continue; // Root seqlock：奇数 = 写进行中，短暂重试
                }
                index = 0; // 固定写 A，像素自 0 起（无自描述头）
            } else {
                index = manager.getReadBufferIndex(); // App ping-pong：读当前发布块
            }
            ByteBuffer block = manager.getBuffer(index);
            if (block == null) {
                return;
            }
            int w;
            int h;
            if (seqlockMode) {
                w = manager.getScreenWidth();
                h = manager.getScreenHeight();
            } else {
                // 9 字节自描述头：[0]=bufferIndex(byte) [1..4]=w(int BE) [5..8]=h(int BE)
                ByteBuffer header = block.duplicate();
                int headerIndex = header.get(0) & 0xFF;
                w = header.getInt(1);
                h = header.getInt(5);
                // 头与发布块不一致（counter++ 后头写完成的瞬态窗口，ipc/contracts §⑦）或尺寸非法：整帧作废重读
                if (headerIndex != index || w <= 0 || h <= 0
                        || (long) w * h * 4L + SharedMemoryManager.HEADER_SIZE > size) {
                    continue;
                }
            }
            long c2 = manager.getFrameCounter();
            if (c1 != c2 || (!seqlockMode && manager.getReadBufferIndex() != index)) {
                continue; // 读窗口内有写完成/块翻转：整帧作废重读
            }
            // 锁定"最近一次写完成"的块：零拷贝像素视图（root 布局像素自 0；ping-pong 自第 9 字节起）。
            // slice() 保持 direct 语义，其地址 = 块基址 + 头长，C++ GetDirectBufferAddress 直读即像素起点
            ByteBuffer view;
            if (seqlockMode) {
                view = block.duplicate();
                view.limit(w * h * 4);
            } else {
                view = ((ByteBuffer) block.duplicate()
                        .position(SharedMemoryManager.HEADER_SIZE)).slice();
            }
            frameBuffer = view;
            frameWidth = w;
            frameHeight = h;
            if (!realFrame) {
                realFrame = true;
                Log.i(TAG, "first frame locked: " + w + "x" + h
                        + (seqlockMode ? " (root seqlock)" : " (ping-pong)"));
            }
            return;
        }
        // 重试耗尽：保持上一帧视图（不阻塞、不抛异常；写侧 50ms 节拍会继续推帧）
    }

    @Override
    public ByteBuffer getDisplayBuffer() {
        ByteBuffer buffer = frameBuffer;
        if (buffer == null) {
            update();
            buffer = frameBuffer;
        }
        return buffer != null ? buffer : EMPTY_BUFFER;
    }

    @Override
    public int getWidth() {
        return frameWidth;
    }

    @Override
    public int getHeight() {
        return frameHeight;
    }

    @Override
    public int getRowStride() {
        // 写侧 convertImageToByteArray 输出紧凑布局（rowStride==w*4 快路径整块拷贝 / 慢路径逐行紧凑写）
        return frameWidth * 4;
    }

    @Override
    public int getPixelStride() {
        return 4;
    }

    @Override
    public boolean initialize(int width, int height) {
        requestedWidth = width;
        requestedHeight = height;
        int w = manager.getScreenWidth();
        int h = manager.getScreenHeight();
        if (width > 0 && w > 0 && (w != width || h != height)) {
            Log.w(TAG, "initialize requested " + width + "x" + height
                    + " but capture pipeline outputs " + w + "x" + h + " (size decided by writer)");
        }
        // 尺寸由写侧决定（MediaProjection 采集分辨率），始终返回 true
        return true;
    }

    @Override
    public boolean hasRealFrame() {
        return realFrame;
    }

    @Override
    public boolean isRgbaOrder() {
        // 共享内存管线帧为 RGBA、R 在 [0]（VisionBridge.cpp:12-16 注释口径，已验证）；
        // 与 root 镜像 DisplayImplement 的 BGRA 历史口径相反，待真机实测复核。
        return true;
    }
}
