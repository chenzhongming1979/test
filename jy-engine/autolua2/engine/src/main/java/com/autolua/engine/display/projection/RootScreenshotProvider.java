package com.autolua.engine.display.projection;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.graphics.PixelFormat;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import com.autolua.engine.conceal.SurfaceControlWrap;
import com.autolua.engine.display.sharedmem.SharedMemoryManager;
import com.autolua.engine.input.inject.InjectorCore;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Root 免授权截图 Provider（对照派大星 RootScreenshotService.java）。
 *
 * <p>本批次只实现"进程内 uid==0 直跑"路径（配合 {@link InjectorCore} 已有环境伪造）；
 * 跨进程 fd 传输下一批接入。</p>
 * <ul>
 *     <li>initRoot：要求 Process.myUid()==0；InjectorCore.initFull() 伪造 ActivityThread 环境
 *         （UID=0 必需）；首选隐藏 API DisplayManagerGlobal.createVirtualDisplay(name,w,h,dpi,surface)，
 *         次选公开 DisplayManager，兜底 SurfaceControl 反射四件套
 *         （createDisplay/openTransaction/setDisplaySurface/setDisplayProjection/setDisplayLayerStack，
 *         :80-118）；</li>
 *     <li>ImageReader(3) 收帧 → convertImageToByteArray 快/慢双路径 →
 *         SharedMemoryManager.writeFrameSeqlock（固定写 A 的 seqlock 协议）；</li>
 *     <li>旋转：§6.2a 起主路径为 {@link DisplayManager.DisplayListener}（onDisplayChanged 处理
 *         rotation 变化，context 经 reflectContext），注册失败自动回退 500ms 轮询
 *         DisplayInfo.rotation；90/270° 宽高对调 resize（:42-69、288-334）。</li>
 * </ul>
 */
public final class RootScreenshotProvider {
    private static final String TAG = "RootScreenshotProvider";

    private static final String DISPLAY_NAME = "RootScreenshot";
    /** 旋转轮询间隔（ms） */
    private static final long ROTATION_POLL_MS = 500;

    private static volatile RootScreenshotProvider sInstance;

    public static RootScreenshotProvider getInstance() {
        if (sInstance == null) {
            synchronized (RootScreenshotProvider.class) {
                if (sInstance == null) {
                    sInstance = new RootScreenshotProvider();
                }
            }
        }
        return sInstance;
    }

    private ImageReader imageReader;
    /** 一致性守卫指针（重建/resize 期间旧回调丢弃） */
    private volatile ImageReader activeImageReader;
    private VirtualDisplay virtualDisplay;
    private IBinder displayToken; // SurfaceControl fallback 的虚拟屏 token
    private HandlerThread handlerThread;
    private volatile Handler handler;

    private volatile int screenWidth;
    private volatile int screenHeight;
    private volatile int screenDensity;
    private volatile int layerStack;
    private volatile int currentRotation = -1;
    private volatile boolean running;
    private volatile long lastFrameTime;
    private Thread rotationThread;
    /** §6.2a：DisplayListener 注册成功后的 DisplayManager 引用（stop 时反注册） */
    private DisplayManager displayManager;
    /**
     * §6.2a：旋转感知主路径——主屏变化（含 rotation）回调 checkRotationChange，
     * 回调运行在采集 handler 线程；虚拟屏自身变化不触发 resize。
     */
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                checkRotationChange();
            }
        }
    };
    private final ThreadLocal<byte[]> threadLocalBuffer = new ThreadLocal<>();
    private final AtomicLong frameCounter = new AtomicLong(0);

    private RootScreenshotProvider() {
    }

    // ------------------------------------------------------------------
    // 启动 / 停止
    // ------------------------------------------------------------------

    /**
     * 启动 root 免授权采集通道（进程内 uid==0 直跑）。
     *
     * @return true=已启动或本就运行中
     */
    public synchronized boolean initRoot() {
        if (running) {
            return true;
        }
        if (Process.myUid() != 0) {
            Log.e(TAG, "initRoot 需要 uid==0（root 进程内直跑）；当前 uid=" + Process.myUid());
            return false;
        }
        // 环境伪造（UID=0 必需，复用输入注入链已有的框架伪造）：失败不阻断（部分环境已就绪）
        try {
            InjectorCore.initFull();
        } catch (Throwable t) {
            Log.w(TAG, "InjectorCore.initFull: " + t);
        }
        if (!initScreenParams()) {
            Log.e(TAG, "initScreenParams failed");
            return false;
        }
        if (!SharedMemoryManager.getInstance().init(screenWidth, screenHeight)) {
            Log.e(TAG, "SharedMemoryManager init failed");
        }
        // 线程优先级 -4（对照 RootScreenshotService:418）
        handlerThread = new HandlerThread("RootScreenshot", -4);
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        if (!createVirtualDisplay()) {
            Log.e(TAG, "createVirtualDisplay failed");
            release();
            return false;
        }
        running = true;
        startRotationPoll();
        Log.i(TAG, "root 截图通道启动: " + screenWidth + "x" + screenHeight
                + ", rotation=" + currentRotation);
        return true;
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        release();
        Log.i(TAG, "root 截图通道停止");
    }

    public boolean isRunning() {
        return running;
    }

    private void release() {
        stopRotationPoll();
        stopVirtualDisplayOnly();
        HandlerThread thread = handlerThread;
        if (thread != null) {
            thread.quitSafely();
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            handlerThread = null;
            handler = null;
        }
        threadLocalBuffer.remove();
    }

    // ------------------------------------------------------------------
    // 虚拟屏建立（三级 fallback，对照 :80-118）
    // ------------------------------------------------------------------

    private boolean createVirtualDisplay() {
        try {
            synchronized (this) {
                imageReader = ImageReader.newInstance(screenWidth, screenHeight,
                        PixelFormat.RGBA_8888, 3);
                imageReader.setOnImageAvailableListener(frameListener, handler);
                activeImageReader = imageReader;
            }
            Surface surface = imageReader.getSurface();
            // 首选：隐藏 API DisplayManagerGlobal.createVirtualDisplay(name, w, h, dpi, surface)
            if (createViaHiddenApi(surface)) {
                Log.i(TAG, "VirtualDisplay via hidden DisplayManagerGlobal API");
                return true;
            }
            // 次选：公开 DisplayManager（uid==0 伪造环境后可用）
            if (createViaDisplayManager(surface)) {
                Log.i(TAG, "VirtualDisplay via public DisplayManager");
                return true;
            }
            // 兜底：SurfaceControl 反射四件套
            return createViaSurfaceControl(surface);
        } catch (Exception e) {
            Log.e(TAG, "createVirtualDisplay failed: " + e.getMessage(), e);
            return false;
        }
    }

    /** 隐藏签名重载：DisplayManagerGlobal.getInstance().createVirtualDisplay(String,int,int,int,Surface) */
    private boolean createViaHiddenApi(Surface surface) {
        try {
            Class<?> global = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object instance = global.getDeclaredMethod("getInstance").invoke(null);
            for (Method method : global.getDeclaredMethods()) {
                if (!"createVirtualDisplay".equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 5 && params[0] == String.class && params[1] == int.class
                        && params[2] == int.class && params[3] == int.class
                        && params[4] == Surface.class) {
                    method.setAccessible(true);
                    Object vd = method.invoke(instance, DISPLAY_NAME,
                            screenWidth, screenHeight, 0, surface);
                    if (vd instanceof VirtualDisplay) {
                        virtualDisplay = (VirtualDisplay) vd;
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "hidden createVirtualDisplay: " + t);
        }
        return false;
    }

    /** 公开 DisplayManager.createVirtualDisplay（uid==0 伪造环境后可直调） */
    private boolean createViaDisplayManager(Surface surface) {
        try {
            Context context = reflectContext();
            if (context == null) {
                return false;
            }
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                return false;
            }
            virtualDisplay = dm.createVirtualDisplay(DISPLAY_NAME, screenWidth, screenHeight,
                    0, surface, 0, null, handler);
            return virtualDisplay != null;
        } catch (Throwable t) {
            Log.w(TAG, "public createVirtualDisplay: " + t);
            return false;
        }
    }

    /** SurfaceControl 反射四件套（对照 :91-107，复用 engine/conceal/SurfaceControlWrap） */
    private boolean createViaSurfaceControl(Surface surface) {
        try {
            displayToken = SurfaceControlWrap.createDisplay(DISPLAY_NAME, false);
            SurfaceControlWrap.openTransaction();
            try {
                SurfaceControlWrap.setDisplaySurface(displayToken, surface);
                Rect rect = new Rect(0, 0, screenWidth, screenHeight);
                SurfaceControlWrap.setDisplayProjection(displayToken, 0, rect, rect);
                SurfaceControlWrap.setDisplayLayerStack(displayToken, layerStack);
            } finally {
                SurfaceControlWrap.closeTransaction();
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "SurfaceControl fallback failed: " + t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 屏幕参数 / 旋转（对照 :42-69、183-207、288-334）
    // ------------------------------------------------------------------

    /** 隐藏 API 取主屏 DisplayInfo（含 rotation/layerStack） */
    private static Object getMainDisplayInfo() throws Exception {
        Class<?> global = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object instance = global.getDeclaredMethod("getInstance").invoke(null);
        int[] ids = (int[]) global.getDeclaredMethod("getDisplayIds").invoke(instance);
        if (ids == null || ids.length == 0) {
            return null;
        }
        return global.getDeclaredMethod("getDisplayInfo", int.class).invoke(instance, ids[0]);
    }

    /** 从 ActivityThread 反射一个可用 Context（currentApplication → systemContext） */
    private static Context reflectContext() {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            try {
                Object app = atClass.getMethod("currentApplication").invoke(null);
                if (app instanceof Context) {
                    return (Context) app;
                }
            } catch (Throwable ignore) {
            }
            Object thread = atClass.getMethod("systemMain").invoke(null);
            if (thread != null) {
                return (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
            }
        } catch (Throwable t) {
            Log.w(TAG, "reflectContext: " + t);
        }
        return null;
    }

    private boolean initScreenParams() {
        try {
            Object info = getMainDisplayInfo();
            if (info != null) {
                Class<?> c = info.getClass();
                screenWidth = c.getField("width").getInt(info);
                screenHeight = c.getField("height").getInt(info);
                screenDensity = c.getField("densityDpi").getInt(info);
                currentRotation = c.getField("rotation").getInt(info);
                layerStack = c.getField("layerStack").getInt(info);
                return screenWidth > 0 && screenHeight > 0;
            }
        } catch (Throwable t) {
            Log.w(TAG, "hidden DisplayInfo failed: " + t);
        }
        // 回退：WindowManager RealMetrics（无 layerStack，默认 0）
        try {
            Context context = reflectContext();
            if (context == null) {
                return false;
            }
            android.view.Display display = ((WindowManager) context
                    .getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
            if (display == null) {
                return false;
            }
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
            screenDensity = metrics.densityDpi;
            currentRotation = display.getRotation();
            layerStack = 0;
            return screenWidth > 0 && screenHeight > 0;
        } catch (Throwable t) {
            Log.e(TAG, "initScreenParams fallback failed: " + t);
            return false;
        }
    }

    private int queryRotation() {
        try {
            Object info = getMainDisplayInfo();
            if (info != null) {
                return info.getClass().getField("rotation").getInt(info);
            }
            Context context = reflectContext();
            if (context != null) {
                WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null && wm.getDefaultDisplay() != null) {
                    return wm.getDefaultDisplay().getRotation();
                }
            }
        } catch (Throwable ignore) {
        }
        return currentRotation;
    }

    /** 轮询 DisplayInfo.rotation：90/270° 宽高对调 resize；180° 不 resize */
    private void checkRotationChange() {
        try {
            int rotation = queryRotation();
            int old = currentRotation;
            if (rotation == old || rotation < 0) {
                return;
            }
            currentRotation = rotation;
            Log.i(TAG, "rotation " + old + " -> " + rotation);
            if ((old % 2) != (rotation % 2)) {
                // 90/270°：宽高对调（对照 lambda$checkRotationChange:210-218）
                final int newW = screenHeight;
                final int newH = screenWidth;
                Handler h = handler;
                if (h != null) {
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            applyRotation(newW, newH);
                        }
                    });
                } else {
                    applyRotation(newW, newH);
                }
            }
            // 180° 不需要 resize
        } catch (Throwable t) {
            Log.w(TAG, "checkRotationChange: " + t);
        }
    }

    private void applyRotation(int newW, int newH) {
        if (!resizeVirtualDisplay(newW, newH)) {
            // resize 失败 → destroy + create 整体重建
            stopVirtualDisplayOnly();
            createVirtualDisplay();
        }
        screenWidth = newW;
        screenHeight = newH;
        // 旋转后必须重分配共享内存尺寸（文档 2.3.6）
        SharedMemoryManager.getInstance().init(newW, newH);
    }

    /** resize：新 reader → VirtualDisplay.resize + setSurface → 换旧 reader；失败回退 SurfaceControl（对照 :288-334） */
    private boolean resizeVirtualDisplay(int newW, int newH) {
        ImageReader newReader;
        try {
            newReader = ImageReader.newInstance(newW, newH, PixelFormat.RGBA_8888, 3);
            newReader.setOnImageAvailableListener(frameListener, handler);
        } catch (Exception e) {
            Log.w(TAG, "resize new reader failed: " + e);
            return false;
        }
        Surface surface = newReader.getSurface();
        VirtualDisplay vd = virtualDisplay;
        if (vd != null) {
            try {
                vd.resize(newW, newH, screenDensity);
                vd.setSurface(surface);
                swapReader(newReader);
                Log.i(TAG, "VirtualDisplay resized: " + newW + "x" + newH);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "VirtualDisplay.resize failed, fallback SurfaceControl: " + e);
            }
        }
        if (displayToken != null) {
            try {
                SurfaceControlWrap.openTransaction();
                SurfaceControlWrap.setDisplaySurface(displayToken, surface);
                Rect rect = new Rect(0, 0, newW, newH);
                SurfaceControlWrap.setDisplayProjection(displayToken, 0, rect, rect);
                SurfaceControlWrap.closeTransaction();
                swapReader(newReader);
                Log.i(TAG, "SurfaceControl display resized: " + newW + "x" + newH);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "SurfaceControl resize failed, fallback destroy+create: " + e);
                try {
                    SurfaceControlWrap.closeTransaction();
                } catch (Throwable ignore) {
                }
            }
        }
        try {
            newReader.close();
        } catch (Exception ignore) {
        }
        return false;
    }

    /** 换装新 reader（旧 reader 关闭，一致性守卫指针同步切换） */
    private void swapReader(ImageReader newReader) {
        ImageReader old = imageReader;
        if (old != null) {
            try {
                old.setOnImageAvailableListener(null, null);
            } catch (Exception ignore) {
            }
        }
        imageReader = newReader;
        activeImageReader = newReader;
        if (old != null) {
            try {
                old.close();
            } catch (Exception ignore) {
            }
        }
    }

    /**
     * §6.2a 旋转感知启动：优先注册 {@link DisplayManager.DisplayListener}（context 经
     * reflectContext：ActivityThread.systemMain → systemContext），注册失败（context 拿不到等）
     * 自动回退 500ms 轮询线程并 Log.w；轮询基础设施保留作为回退路径。
     */
    private void startRotationPoll() {
        stopRotationPoll();
        if (registerRotationListener()) {
            Log.i(TAG, "rotation aware via DisplayManager.DisplayListener");
            return;
        }
        Log.w(TAG, "DisplayListener register failed, fallback to " + ROTATION_POLL_MS + "ms rotation polling");
        rotationThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                while (running) {
                    SystemClock.sleep(ROTATION_POLL_MS);
                    if (running) {
                        checkRotationChange();
                    }
                }
            }
        }, "RootScreenshotRotation");
        rotationThread.setDaemon(true);
        rotationThread.start();
    }

    /** §6.2a：注册主屏变化监听（借采集 handler 线程派发），失败返回 false 走轮询回退 */
    private boolean registerRotationListener() {
        try {
            Context context = reflectContext();
            if (context == null || handler == null) {
                return false;
            }
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                return false;
            }
            dm.registerDisplayListener(displayListener, handler);
            displayManager = dm;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "registerDisplayListener: " + t);
            return false;
        }
    }

    private void stopRotationPoll() {
        // §6.2a：先反注册 DisplayListener（若已注册），再中断回退轮询线程
        DisplayManager dm = displayManager;
        if (dm != null) {
            displayManager = null;
            try {
                dm.unregisterDisplayListener(displayListener);
            } catch (Throwable t) {
                Log.w(TAG, "unregisterDisplayListener: " + t);
            }
        }
        Thread thread = rotationThread;
        rotationThread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    // ------------------------------------------------------------------
    // 帧回调（ImageReader(3) → writeFrameSeqlock）
    // ------------------------------------------------------------------

    private final ImageReader.OnImageAvailableListener frameListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    if (reader != activeImageReader) {
                        return; // 一致性守卫
                    }
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        if (image == null) {
                            return;
                        }
                        long count = frameCounter.incrementAndGet();
                        if (count % 100 == 1) {
                            Log.d(TAG, "frame #" + count + " " + image.getWidth()
                                    + "x" + image.getHeight());
                        }
                        byte[] frame = convertImageToByteArray(image);
                        if (frame != null) {
                            lastFrameTime = SystemClock.elapsedRealtime();
                            // Root 模式 seqlock 写（固定写 A）
                            SharedMemoryManager.getInstance().writeFrameSeqlock(frame);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "root frame error: " + t);
                    } finally {
                        // Image 立即 close（对照懒人 LIl.java）
                        if (image != null) {
                            try {
                                image.close();
                            } catch (Exception ignore) {
                            }
                        }
                    }
                }
            };

    /** Image → RGBA byte[]（快/慢双路径同 App 模式，对照 imageToBytes :135-181） */
    private byte[] convertImageToByteArray(Image image) {
        try {
            Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) {
                return null;
            }
            Image.Plane plane = planes[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int width = image.getWidth();
            int height = image.getHeight();
            int frameSize = width * height * 4;
            byte[] out = threadLocalBuffer.get();
            if (out == null || out.length < frameSize) {
                out = new byte[frameSize];
                threadLocalBuffer.set(out);
            }
            buffer.rewind();
            if (pixelStride == 4 && rowStride == width * 4) {
                // 快路径：整块拷贝
                buffer.get(out, 0, Math.min(buffer.remaining(), frameSize));
            } else {
                // 慢路径：逐像素拷贝 + alpha 强制 255
                int limit = buffer.limit();
                for (int row = 0; row < height; row++) {
                    int srcRow = row * rowStride;
                    int dstRow = row * width * 4;
                    for (int col = 0; col < width; col++) {
                        int src = srcRow + col * pixelStride;
                        if (src + 2 >= limit) {
                            break;
                        }
                        int dst = dstRow + col * 4;
                        out[dst] = buffer.get(src);
                        out[dst + 1] = buffer.get(src + 1);
                        out[dst + 2] = buffer.get(src + 2);
                        out[dst + 3] = pixelStride >= 4 ? buffer.get(src + 3) : (byte) 255;
                    }
                }
            }
            return out;
        } catch (Exception e) {
            Log.e(TAG, "convert image error: " + e.getMessage(), e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 状态查询
    // ------------------------------------------------------------------

    public int getScreenWidth() {
        checkRotationChange();
        return screenWidth;
    }

    public int getScreenHeight() {
        checkRotationChange();
        return screenHeight;
    }

    public long getLastFrameTime() {
        return lastFrameTime;
    }

    /** 释放虚拟屏（reader + VirtualDisplay / SurfaceControl token） */
    private void stopVirtualDisplayOnly() {
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (Exception e) {
                Log.e(TAG, "release VirtualDisplay: " + e);
            }
            virtualDisplay = null;
        }
        if (displayToken != null) {
            try {
                SurfaceControlWrap.destroyDisplay(displayToken);
            } catch (Exception e) {
                Log.e(TAG, "destroy display: " + e);
            }
            displayToken = null;
        }
        ImageReader reader = imageReader;
        if (reader != null) {
            activeImageReader = null;
            try {
                reader.setOnImageAvailableListener(null, null);
                reader.close();
            } catch (Exception e) {
                Log.e(TAG, "close ImageReader: " + e);
            }
            imageReader = null;
        }
    }
}
