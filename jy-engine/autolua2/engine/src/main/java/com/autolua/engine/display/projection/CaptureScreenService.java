package com.autolua.engine.display.projection;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import com.autolua.engine.display.sharedmem.SharedMemoryManager;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * App 模式常驻采集服务（MediaProjection + ImageReader），前台服务。
 * 对照派大星 CaptureScreenService.java：
 * <ul>
 *     <li>ImageReader.newInstance(w, h, RGBA_8888, 8)（maxImages=8 抗瞬时峰值，:490-495）；</li>
 *     <li>createVirtualDisplay("AutoLuaShot", w, h, density, 17 = PUBLIC|AUTO_MIRROR, ...）；</li>
 *     <li>帧回调：activeImageReader 指针一致性守卫（:549-556）、acquireLatestImage、
 *         stride 快/慢双路径 + alpha=255（:224-277）、ThreadLocal 缓冲、空帧过滤（:430-444）；</li>
 *     <li>采集节拍循环线程：sleep(max(1, interval-耗时))，默认 50ms（:637-671）；</li>
 *     <li>帧看狗：3000ms 无帧 → 整链重建（rebuildCount 可观测，:684-728）；</li>
 *     <li>投影 onStop：延迟 600ms 用保存的授权令牌重试一次（API35 兼容，:617-634/954-958）；</li>
 *     <li>线程优先级 -4（:975）；旋转：onConfigurationChanged + manifest configChanges（:730-774）。</li>
 * </ul>
 */
public class CaptureScreenService extends Service {
    private static final String TAG = "CaptureScreenService";

    /** 投屏授权请求码（app 层 onActivityResult 用同值转发） */
    public static final int REQUEST_CODE = 6001;
    /** 节拍间隔 intent extra（可选） */
    public static final String EXTRA_INTERVAL_MS = "intervalMs";
    /** 跨进程绑定 action（IScreenShareService 共享内存 fd 服务端） */
    public static final String ACTION_SCREEN_SHARE = "com.autolua.engine.SCREEN_SHARE";

    private static final String EXTRA_RESULT_CODE = "resultCode";
    private static final String EXTRA_DATA = "data";
    private static final String NOTIFICATION_CHANNEL_ID = "autolua_capture";
    private static final int NOTIFICATION_ID = 1001;
    /** VIRTUAL_DISPLAY_FLAG_PUBLIC(1) | VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR(16) */
    private static final int VIRTUAL_DISPLAY_FLAGS = 17;
    /** 帧看狗阈值（ms）：无新帧且无积压 → 重建管线 */
    private static final long FRAME_TIMEOUT_MS = 3000;
    /** 默认采集节拍 ≈20fps */
    private static final long DEFAULT_INTERVAL_MS = 50;
    /** 投影停止后延迟重试间隔（API 35 每会话重授权兼容） */
    private static final long REESTABLISH_DELAY_MS = 600;

    private static volatile CaptureScreenService sInstance;
    /** startCapture 时缓存的 app context，供 onActivityResult 启动本服务 */
    private static volatile Context sAppContext;

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private MediaProjection.Callback projectionCallback;
    private ImageReader screenshotImageReader;
    /** 一致性守卫：只有等于当前 active reader 的回调才处理（防重建竞态） */
    private volatile ImageReader activeImageReader;
    private VirtualDisplay virtualDisplay;
    private HandlerThread handlerThread;
    private volatile Handler handler;
    private final Object imageReaderLock = new Object();

    private volatile int screenWidth;
    private volatile int screenHeight;
    private volatile int screenDensity;
    /** 0=无新帧（看狗不动）；首帧到达后开始计时 */
    private volatile long lastFrameCallbackTime;
    private volatile boolean projectionActive;
    private volatile long intervalMs = DEFAULT_INTERVAL_MS;
    /** 当前捕获格式（降级一次：RGBA_8888 ↔ RGB_565） */
    private volatile int captureFormat = PixelFormat.RGBA_8888;
    private volatile boolean formatDowngraded;

    private final AtomicBoolean looping = new AtomicBoolean(false);
    private final AtomicBoolean reestablishing = new AtomicBoolean(false);
    private final AtomicLong rebuildCount = new AtomicLong(0);
    private final AtomicLong callbackCounter = new AtomicLong(0);
    private Thread loopThread;
    private Handler reestablishHandler;
    private int savedResultCode;
    private Intent savedResultData;
    /** 旋转重建期间使用的 ThreadLocal 缓冲（重建时 remove） */
    private final ThreadLocal<byte[]> threadLocalBuffer = new ThreadLocal<>();

    // ------------------------------------------------------------------
    // 静态接线（app 层调用）
    // ------------------------------------------------------------------

    /** 发起投屏授权（app 层在 Activity 中调用） */
    public static void startCapture(Activity activity) {
        sAppContext = activity.getApplicationContext();
        MediaProjectionManager mpm = (MediaProjectionManager)
                activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            Log.e(TAG, "MediaProjectionManager unavailable");
            return;
        }
        activity.startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CODE);
    }

    /**
     * 接收授权结果（app 层在 onActivityResult(requestCode==REQUEST_CODE) 转发）。
     * 拉起前台服务，在 onStartCommand 内先 startForeground 再 getMediaProjection
     * （Android 14 要求 FGS type=mediaProjection 先于获取投影运行）。
     */
    public static void onActivityResult(int resultCode, Intent data) {
        Context context = sAppContext;
        if (context == null) {
            Log.e(TAG, "onActivityResult: startCapture 未先调用");
            return;
        }
        Intent service = new Intent(context, CaptureScreenService.class);
        service.putExtra(EXTRA_RESULT_CODE, resultCode);
        service.putExtra(EXTRA_DATA, data);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }

    public static CaptureScreenService getInstance() {
        return sInstance;
    }

    public static boolean isRunning() {
        CaptureScreenService service = sInstance;
        return service != null && service.projectionActive;
    }

    /** 启动/调整采集节拍（ms） */
    public static void startLoopScreenshot(long interval) {
        CaptureScreenService service = sInstance;
        if (service != null) {
            service.startLoop(interval);
        }
    }

    public static void stopLoopScreenshot() {
        CaptureScreenService service = sInstance;
        if (service != null) {
            service.stopLoop();
        }
    }

    /**
     * 立即巡检一拍（IScreenShareService.requestFrame 用，App 模式手动节拍预留位）。
     * 帧实际由 VirtualDisplay→ImageReader 回调持续写入共享内存；此处立即取出积压帧并释放，
     * 让生产者尽快产出新帧（与帧看狗同款巡检行为，不做整链重建）。
     */
    public static void requestFrameOnce() {
        CaptureScreenService service = sInstance;
        if (service == null || !service.projectionActive) {
            return;
        }
        Image image = null;
        try {
            synchronized (service.imageReaderLock) {
                if (service.screenshotImageReader != null) {
                    image = service.screenshotImageReader.acquireLatestImage();
                }
            }
        } catch (Throwable ignore) {
        }
        if (image != null) {
            try {
                image.close();
            } catch (Throwable ignore) {
            }
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        reestablishHandler = new Handler(Looper.getMainLooper());
    }

    /** IScreenShareService binder（跨进程共享内存 fd 服务端，随服务创建） */
    private final IBinder screenShareBinder = new ScreenShareServiceImpl();

    @Override
    public IBinder onBind(Intent intent) {
        if (intent != null && ACTION_SCREEN_SHARE.equals(intent.getAction())) {
            // SCREEN_SHARE 通道：跨进程取共享内存 fd / 请求一帧
            return screenShareBinder;
        }
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Android 14：必须先以 mediaProjection 类型进入前台，才能 getMediaProjection
        startForegroundWithType();
        if (intent != null && intent.hasExtra(EXTRA_RESULT_CODE)) {
            savedResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            savedResultData = intent.getParcelableExtra(EXTRA_DATA);
            if (intent.hasExtra(EXTRA_INTERVAL_MS)) {
                intervalMs = Math.max(1, intent.getLongExtra(EXTRA_INTERVAL_MS, DEFAULT_INTERVAL_MS));
            }
        }
        if (savedResultData != null && !projectionActive) {
            startProjection(savedResultCode, savedResultData);
        }
        // 授权令牌不可跨进程死亡复用，不粘性重启
        return START_NOT_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!projectionActive) {
            return;
        }
        int oldW = screenWidth;
        int oldH = screenHeight;
        initScreenParams();
        if (oldW != screenWidth || oldH != screenHeight) {
            Log.i(TAG, "旋转: " + oldW + "x" + oldH + " -> " + screenWidth + "x" + screenHeight);
            recreateImageReader();
        }
    }

    @Override
    public void onDestroy() {
        stopInternal();
        if (ScreenshotProviderManager.getInstance().getMode()
                != ScreenshotProviderManager.Mode.ROOT) {
            // App 模式退出时释放共享内存（root 通道仍在用时保留）
            SharedMemoryManager.getInstance().close();
        }
        if (sInstance == this) {
            sInstance = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 投影建立
    // ------------------------------------------------------------------

    /** 建立投影 → 共享内存 → ImageReader + VirtualDisplay → 启动节拍循环 */
    public synchronized boolean startProjection(int resultCode, Intent data) {
        try {
            releaseProjectionPipeline();
            initScreenParams();
            if (!SharedMemoryManager.getInstance().init(screenWidth, screenHeight)) {
                Log.e(TAG, "SharedMemoryManager init failed");
            }
            MediaProjection projection = getMediaProjection(resultCode, data);
            if (projection == null) {
                Log.e(TAG, "getMediaProjection failed");
                return false;
            }
            mediaProjection = projection;
            registerProjectionCallback();
            boolean ok = initScreenshotImageReader();
            projectionActive = ok;
            if (ok) {
                lastFrameCallbackTime = 0; // 首帧后开始看狗计时
                startLoop(intervalMs);
                Log.i(TAG, "projection started: " + screenWidth + "x" + screenHeight);
            }
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "startProjection failed: " + e.getMessage(), e);
            return false;
        }
    }

    /** 获取 MediaProjection（须在 startForeground 之后调用，Android 14 强制） */
    private MediaProjection getMediaProjection(int resultCode, Intent data) {
        try {
            if (projectionManager == null) {
                projectionManager = (MediaProjectionManager)
                        getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            }
            if (projectionManager == null) {
                return null;
            }
            return projectionManager.getMediaProjection(resultCode, data);
        } catch (Throwable t) {
            Log.e(TAG, "getMediaProjection error: " + t);
            return null;
        }
    }

    private void registerProjectionCallback() {
        if (mediaProjection == null) {
            return;
        }
        if (projectionCallback == null) {
            projectionCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.w(TAG, "MediaProjection onStop");
                    projectionActive = false;
                    // API 35 兼容：延迟 600ms 用保存的授权令牌重授权重试一次
                    scheduleReestablish();
                }
            };
        }
        mediaProjection.registerCallback(projectionCallback, null);
    }

    private final Runnable reestablishRunnable = new Runnable() {
        @Override
        public void run() {
            reestablishing.set(false);
            if (projectionActive || savedResultData == null) {
                return;
            }
            Log.i(TAG, "onStop: 600ms 后用保存的授权令牌重建 MediaProjection（仅重试一次）");
            try {
                MediaProjection projection = getMediaProjection(savedResultCode, savedResultData);
                mediaProjection = projection;
                if (projection != null) {
                    registerProjectionCallback();
                    projectionActive = initScreenshotImageReader();
                    if (projectionActive) {
                        lastFrameCallbackTime = 0;
                    }
                }
            } catch (Exception e) {
                // 重试失败不再重试（避免授权弹窗循环）
                Log.e(TAG, "onStop: re-establish failed: " + e.getMessage(), e);
            }
        }
    };

    private void scheduleReestablish() {
        if (reestablishing.compareAndSet(false, true)) {
            reestablishHandler.removeCallbacks(reestablishRunnable);
            reestablishHandler.postDelayed(reestablishRunnable, REESTABLISH_DELAY_MS);
        }
    }

    /** ImageReader(8) + VirtualDisplay("AutoLuaShot", w, h, density, 17, surface) */
    private boolean initScreenshotImageReader() {
        if (handlerThread == null || handler == null) {
            // 优先级 -4（THREAD_PRIORITY_URGENT_DISPLAY，对照 :975）
            handlerThread = new HandlerThread("AutoLuaShotHandler", -4);
            handlerThread.start();
            handler = new Handler(handlerThread.getLooper());
        }
        try {
            synchronized (imageReaderLock) {
                screenshotImageReader = ImageReader.newInstance(
                        screenWidth, screenHeight, captureFormat, 8);
                screenshotImageReader.setOnImageAvailableListener(imageAvailableListener, handler);
            }
            virtualDisplay = mediaProjection.createVirtualDisplay("AutoLuaShot",
                    screenWidth, screenHeight, screenDensity, VIRTUAL_DISPLAY_FLAGS,
                    screenshotImageReader.getSurface(), null, handler);
            activeImageReader = screenshotImageReader;
            Log.i(TAG, "pipeline ok: " + screenWidth + "x" + screenHeight
                    + " fmt=" + captureFormat + " vd=" + (virtualDisplay != null));
            return virtualDisplay != null;
        } catch (Exception e) {
            Log.e(TAG, "initScreenshotImageReader failed: " + e.getMessage(), e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 帧回调
    // ------------------------------------------------------------------

    private final ImageReader.OnImageAvailableListener imageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    callbackCounter.incrementAndGet();
                    lastFrameCallbackTime = System.currentTimeMillis();
                    // 一致性守卫：旧 reader 的迟到回调直接丢弃（对照 :549-556）
                    if (reader != activeImageReader) {
                        return;
                    }
                    Image image = null;
                    try {
                        synchronized (imageReaderLock) {
                            if (screenshotImageReader != null) {
                                image = reader.acquireLatestImage(); // 只取最新帧
                            }
                        }
                        if (image == null) {
                            return;
                        }
                        byte[] frame = convertImageToByteArray(image);
                        if (frame != null && !isBlankFrame(frame)) {
                            // App 模式 ping-pong 写共享内存（9 字节自描述头）
                            SharedMemoryManager.getInstance()
                                    .writeFrameSwap(frame, image.getWidth(), image.getHeight());
                        }
                    } catch (IllegalStateException e) {
                        handleFormatReject(e);
                    } catch (UnsupportedOperationException e) {
                        handleFormatReject(e);
                    } catch (Exception e) {
                        Log.e(TAG, "process frame error: " + e.getMessage(), e);
                    } finally {
                        // Image 立即 close（对照懒人 LIl.java：用后即刻归还生产者）
                        if (image != null) {
                            try {
                                image.close();
                            } catch (Exception ignore) {
                            }
                        }
                    }
                }
            };

    /**
     * 格式被拒（异常消息含 "producer output buffer format"，对照按键 :143-147）：
     * RGBA ↔ RGB_565 降级一次后整链重建；Android 15（SDK 35）拒绝 RGB_565 虚拟屏输出，跳过降级。
     */
    private void handleFormatReject(Throwable t) {
        String message = t.getMessage();
        if (message == null || !message.contains("producer output buffer format")) {
            Log.e(TAG, "frame error: " + message, t);
            return;
        }
        Log.w(TAG, "producer format rejected: " + message);
        if (Build.VERSION.SDK_INT == 35) {
            Log.w(TAG, "Android 15 强制 RGBA_8888，跳过格式降级（2.4-④）");
            rebuildScreenshotPipeline();
            return;
        }
        if (formatDowngraded) {
            Log.w(TAG, "格式已降级过，仅整链重建");
            rebuildScreenshotPipeline();
            return;
        }
        formatDowngraded = true;
        captureFormat = captureFormat == PixelFormat.RGBA_8888
                ? PixelFormat.RGB_565 : PixelFormat.RGBA_8888;
        Log.w(TAG, "format downgrade -> " + captureFormat);
        rebuildScreenshotPipeline();
    }

    /**
     * Image → RGBA byte[]：stride 快路径（rowStride==w*4 且 pixelStride==4 整块 get）/
     * 慢路径（逐行拷贝 + alpha=255）/ RGB_565 解包路径，ThreadLocal 缓冲复用（对照 :224-277）。
     */
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
            } else if (pixelStride == 2) {
                // RGB_565（降级格式）→ RGBA 解包
                int limit = buffer.limit();
                for (int row = 0; row < height; row++) {
                    int srcRow = row * rowStride;
                    int dstRow = row * width * 4;
                    for (int col = 0; col < width; col++) {
                        int src = srcRow + col * 2;
                        if (src + 1 >= limit) {
                            break;
                        }
                        int v = ((buffer.get(src + 1) & 0xFF) << 8) | (buffer.get(src) & 0xFF);
                        int dst = dstRow + col * 4;
                        out[dst] = (byte) ((((v >> 11) & 0x1F) * 255 + 15) / 31);
                        out[dst + 1] = (byte) ((((v >> 5) & 0x3F) * 255 + 31) / 63);
                        out[dst + 2] = (byte) (((v & 0x1F) * 255 + 15) / 31);
                        out[dst + 3] = (byte) 255;
                    }
                }
            } else {
                // 慢路径：逐行拷贝 + alpha 强制 255
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

    /** 空帧过滤：前 1000 字节内非零字节 < 1% 判无效丢弃（对照 :430-444） */
    private static boolean isBlankFrame(byte[] frame) {
        int check = Math.min(1000, frame.length);
        if (check == 0) {
            return true;
        }
        int nonZero = 0;
        for (int i = 0; i < check; i++) {
            if (frame[i] != 0) {
                nonZero++;
            }
        }
        return nonZero < check / 100;
    }

    // ------------------------------------------------------------------
    // 采集节拍循环 + 帧看狗（对照 :637-671）
    // ------------------------------------------------------------------

    private void startLoop(long interval) {
        if (interval > 0) {
            intervalMs = interval;
        }
        if (!looping.compareAndSet(false, true)) {
            return;
        }
        loopThread = new Thread(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(-4);
                long lastTick = System.currentTimeMillis();
                while (looping.get()) {
                    SystemClock.sleep(Math.max(1, intervalMs - (System.currentTimeMillis() - lastTick)));
                    lastTick = System.currentTimeMillis();
                    watchdogTick();
                }
            }
        }, "AutoLuaShotLoop");
        loopThread.start();
    }

    public void stopLoop() {
        looping.set(false);
        Thread thread = loopThread;
        loopThread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** 看狗一拍：3000ms 无帧 → 试拉积压帧，仍无帧则整链重建 */
    private void watchdogTick() {
        long now = System.currentTimeMillis();
        if (!(lastFrameCallbackTime > 0 && now - lastFrameCallbackTime > FRAME_TIMEOUT_MS)) {
            return;
        }
        Image image = null;
        try {
            synchronized (imageReaderLock) {
                if (screenshotImageReader != null) {
                    image = screenshotImageReader.acquireLatestImage();
                }
            }
        } catch (Exception ignore) {
        }
        if (image != null) {
            try {
                image.close();
            } catch (Exception ignore) {
            }
            lastFrameCallbackTime = now; // 有积压帧：链路活着，仅回调迟到
        } else {
            Log.w(TAG, "frame watchdog: " + (now - lastFrameCallbackTime)
                    + "ms 无帧且无积压，重建管线 #" + rebuildCount.incrementAndGet());
            lastFrameCallbackTime = now;
            rebuildScreenshotPipeline();
        }
    }

    /** 整链重建（对照 rebuildScreenshotPipeline :684-728） */
    private void rebuildScreenshotPipeline() {
        if (mediaProjection == null || !projectionActive) {
            Log.w(TAG, "rebuild skipped (projection=" + (mediaProjection != null)
                    + ", active=" + projectionActive + ")");
            return;
        }
        try {
            ImageReader oldReader = screenshotImageReader;
            synchronized (imageReaderLock) {
                if (oldReader != null) {
                    activeImageReader = null;
                    oldReader.setOnImageAvailableListener(null, null);
                }
            }
            if (virtualDisplay != null) {
                try {
                    virtualDisplay.release();
                } catch (Exception ignore) {
                }
                virtualDisplay = null;
            }
            if (oldReader != null) {
                try {
                    oldReader.close();
                } catch (Exception ignore) {
                }
            }
            initScreenshotImageReader();
        } catch (Exception e) {
            Log.e(TAG, "rebuildScreenshotPipeline failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 旋转重建（对照 recreateImageReaderForOrientation :730-774）
    // ------------------------------------------------------------------

    /** CountDownLatch 排空 handler → 新 reader → resize + setSurface → 旧 reader 500ms 后关 */
    private void recreateImageReader() {
        if (mediaProjection == null || !projectionActive) {
            return;
        }
        final ImageReader oldReader;
        synchronized (imageReaderLock) {
            oldReader = screenshotImageReader;
            if (oldReader != null) {
                activeImageReader = null;
                oldReader.setOnImageAvailableListener(null, null);
            }
        }
        if (handler != null && oldReader != null) {
            final CountDownLatch latch = new CountDownLatch(1);
            handler.post(new Runnable() {
                @Override
                public void run() {
                    latch.countDown();
                }
            });
            try {
                latch.await(1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        threadLocalBuffer.remove(); // 旋转重建时清 ThreadLocal 缓冲
        try {
            synchronized (imageReaderLock) {
                screenshotImageReader = ImageReader.newInstance(
                        screenWidth, screenHeight, captureFormat, 8);
                screenshotImageReader.setOnImageAvailableListener(imageAvailableListener, handler);
            }
            if (virtualDisplay != null) {
                virtualDisplay.resize(screenWidth, screenHeight, screenDensity);
                virtualDisplay.setSurface(screenshotImageReader.getSurface());
            }
            activeImageReader = screenshotImageReader;
        } catch (Exception e) {
            Log.e(TAG, "recreate reader failed, fallback full rebuild: " + e.getMessage(), e);
            initScreenshotImageReader();
        }
        if (oldReader != null) {
            if (handler != null) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            oldReader.close();
                        } catch (Exception ignore) {
                        }
                    }
                }, 500);
            } else {
                try {
                    oldReader.close();
                } catch (Exception ignore) {
                }
            }
        }
        // 旋转后必须重分配共享内存尺寸（文档 2.3.6）
        SharedMemoryManager.getInstance().init(screenWidth, screenHeight);
    }

    // ------------------------------------------------------------------
    // 屏幕参数 / 清理
    // ------------------------------------------------------------------

    /** getRealMetrics 取真实分辨率（含导航栏，API 17+） */
    private void initScreenParams() {
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics metrics = new DisplayMetrics();
            if (Build.VERSION.SDK_INT >= 17) {
                wm.getDefaultDisplay().getRealMetrics(metrics);
            } else {
                wm.getDefaultDisplay().getMetrics(metrics);
            }
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
            screenDensity = metrics.densityDpi;
        } catch (Exception e) {
            Log.e(TAG, "initScreenParams failed: " + e.getMessage(), e);
        }
    }

    private void startForegroundWithType() {
        createNotificationChannel();
        Notification notification = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14+：必须带 mediaProjection 类型启动前台
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION /* 29 */);
            } else {
                // API 29~33：Manifest 已声明 foregroundServiceType
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "startForeground failed: " + e.getMessage(), e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                    "屏幕捕获服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("用于屏幕内容捕获的前台服务");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("AutoLua 屏幕捕获")
                .setContentText("正在捕获屏幕内容")
                .setOngoing(true);
        return builder.build();
    }

    private void releaseProjectionPipeline() {
        ImageReader oldReader = screenshotImageReader;
        synchronized (imageReaderLock) {
            if (oldReader != null) {
                activeImageReader = null;
                oldReader.setOnImageAvailableListener(null, null);
            }
        }
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (Exception ignore) {
            }
            virtualDisplay = null;
        }
        if (oldReader != null) {
            try {
                oldReader.close();
            } catch (Exception ignore) {
            }
            screenshotImageReader = null;
        }
        if (mediaProjection != null) {
            try {
                mediaProjection.stop();
            } catch (Exception ignore) {
            }
            mediaProjection = null;
        }
    }

    private void stopInternal() {
        projectionActive = false;
        stopLoop();
        if (reestablishHandler != null) {
            reestablishHandler.removeCallbacks(reestablishRunnable);
        }
        releaseProjectionPipeline();
        threadLocalBuffer.remove();
        if (handlerThread != null) {
            handlerThread.quitSafely();
            try {
                handlerThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            handlerThread = null;
            handler = null;
        }
    }

    // ------------------------------------------------------------------
    // 采集参数
    // ------------------------------------------------------------------

    public int getScreenWidth() {
        return screenWidth;
    }

    public int getScreenHeight() {
        return screenHeight;
    }

    public long getRebuildCount() {
        return rebuildCount.get();
    }
}
