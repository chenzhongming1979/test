package com.autolua.engine.input.inject;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.MemoryFile;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.Surface;

import com.autolua.engine.conceal.DisplayControlWrap;
import com.autolua.engine.display.sharedmem.SharedMemoryManager;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shizuku UserService 入口（运行在 Shizuku 管理的 shell uid 2000 进程内）。
 * 执行与 {@link InjectorCore#initDirect()} 相同的三层 InputManager fallback +
 * injectInputEvent 反射（此进程无 ActivityThread 应用环境，无需伪造）。
 * <p>
 * 注意：此进程无 app classloader 之外的依赖，仅允许 android.* framework 与 java.*。
 * Shizuku 约定：UserService 可提供带 Context 的构造函数，由 Shizuku 传入。
 * <p>
 * 虚拟屏（任务书 §4.1/§4.2）：服务端内部持有
 * {@code SparseArray<VdEntry>}（token + ImageReader 帧宿主 + displayId + w/h/dpi）；
 * 建屏经 {@link DisplayControlWrap} 双路径（SurfaceControl 静态 / DisplayControl+Transaction），
 * 真实 displayId 由建屏前后 {@code DisplayManagerGlobal.getDisplayIds()} 差集轮询解析。
 * 异常一律 catch 住返回错误码，不抛穿 AIDL。
 * <p>
 * 第二批（任务书 §4.3/§4.4）：每屏扩展 A/B MemoryFile 帧泵（attachFrameSink 时创建，
 * ImageReader onImageAvailable → 紧凑 RGBA 转换 → ping-pong 写 + AtomicLong counter，
 * 协议对齐 SharedMemoryManager.writeFrameSwap 与 9 字节自描述头）+ displayId 定向注入
 * （MotionEvent.obtain + 反射 setDisplayId + InjectorCore.injectEventRaw 底座）。
 */
public class ShizukuUserServiceMain extends IInjectorService.Stub {

    private static final String TAG = "ShizukuUserServiceMain";

    // ---- startAppOnDisplay 失败码（AIDL 契约：0 成功 / 非 0 失败）----
    /** 参数非法或入口解析失败 */
    public static final int ERR_ARGS = 1;
    /** exec am 进程失败（IOException 等） */
    public static final int ERR_EXEC = 2;
    /** am start 报告错误（退出码非 0 或输出含 Error/Exception 等） */
    public static final int ERR_AM = 3;

    // ---- displayId 定向注入失败码（AIDL 契约：0 成功，见 IInjectorService.aidl 注释）----
    /** 参数非法（坐标越界为系统侧拒绝，不在此拦截） */
    public static final int ERR_INJ_ARGS = 1;
    /** displayId 未建屏：不注入（禁止静默点错屏，任务书 §4.3 降级语义显式报错） */
    public static final int ERR_INJ_DISPLAY_UNKNOWN = 2;
    /** InputEvent.setDisplayId 隐藏方法缺失：已降级主屏注入，返回此错误码提示分屏能力缺失 */
    public static final int ERR_INJ_SET_DISPLAY_ID_MISSING = 3;
    /** InputManager.injectInputEvent 返回 false（系统拒绝） */
    public static final int ERR_INJ_REJECTED = 4;

    /** 建屏后等待 DMS 注册新 displayId 的窗口（模拟器/真机实测后可调，虚拟屏任务书 §4.1） */
    private static final long DISPLAY_ID_WAIT_MS = 1500L;
    private static final long DISPLAY_ID_POLL_MS = 60L;
    /** 默认绑主屏 layerStack（Display.DEFAULT_DISPLAY = 0），虚拟屏内容来源默认屏 */
    private static final int DEFAULT_LAYER_STACK = 0;
    /** fallback 内部句柄基址：远离真实 displayId 取值域（0..255），避免与系统 id 冲突 */
    private static final int FALLBACK_ID_BASE = 0x40000000;
    /** exec 子进程输出读取上限（防异常进程刷屏拖死 binder 线程） */
    private static final int EXEC_OUTPUT_LIMIT = 8192;

    /** shell 进程上下文（仅用于入口解析 getLaunchIntentForPackage，可为 null） */
    private final Context context;

    /** displayId → 虚拟屏条目；全部 create/destroy 经 vdLock 串行化（建屏含全局事务与轮询） */
    private final SparseArray<VdEntry> displays = new SparseArray<>();
    private final Object vdLock = new Object();
    /** 差集解析失败时的自增内部句柄（从 FALLBACK_ID_BASE 起） */
    private int fallbackId = FALLBACK_ID_BASE;

    @SuppressWarnings("unused")
    public ShizukuUserServiceMain(Context context) {
        // shell 进程内直接初始化 InputManager（uid=2000）
        InjectorCore.initDirect();
        this.context = context;
        System.err.println("[ShizukuUserServiceMain] created, uid=" + android.os.Process.myUid()
                + ", ready=" + InjectorCore.isReady());
    }

    @Override
    public boolean ping() {
        return InjectorCore.isReady();
    }

    @Override
    public boolean down(int x, int y, int pointerId, long downTime) {
        return InjectorCore.down(x, y, pointerId, downTime);
    }

    @Override
    public boolean move(int x, int y, int pointerId, long downTime) {
        return InjectorCore.move(x, y, pointerId, downTime);
    }

    @Override
    public boolean up(int pointerId, int x, int y, long downTime) {
        return InjectorCore.up(pointerId, x, y, downTime);
    }

    @Override
    public boolean tap(int x, int y, int durationMs) {
        return InjectorCore.tap(x, y, -1, durationMs);
    }

    @Override
    public boolean longTap(int x, int y, int durationMs) {
        return InjectorCore.tap(x, y, -1, durationMs);
    }

    @Override
    public boolean swipe(int x1, int y1, int x2, int y2, int durationMs) {
        return InjectorCore.swipe(x1, y1, x2, y2, durationMs, 0, -1);
    }

    @Override
    public boolean pinch(int cx, int cy, int radius, int target, int durationMs, boolean zoomIn) {
        return InjectorCore.pinch(cx, cy, radius, target, durationMs, 0, zoomIn);
    }

    @Override
    public boolean gesture(String strokesJson) {
        return InjectorCore.gesture(strokesJson, -1);
    }

    @Override
    public boolean reset() {
        return InjectorCore.reset();
    }

    @Override
    public boolean config(String key, String value) {
        return InjectorCore.config(key, value);
    }

    @Override
    public void destroy() {
        InjectorCore.reset();
        releaseAllDisplays();
    }

    // ------------------------------------------------------------------
    // 虚拟屏（任务书 §4.1 建屏 / §4.2 拉起）
    // ------------------------------------------------------------------

    @Override
    public int createVirtualDisplay(String name, int width, int height, int dpi) {
        if (name == null || name.trim().isEmpty()) {
            name = "autolua-vd";
        }
        if (width <= 0 || height <= 0) {
            Log.e(TAG, "createVirtualDisplay: bad size " + width + "x" + height);
            return -1;
        }
        if (dpi <= 0) {
            dpi = 320; // 与 02-Lua-API规范 §20 virtualDisplay.create 缺省口径一致
        }
        synchronized (vdLock) {
            // 重复同名 → 先销毁旧实例再重建（任务书 §4.1）
            for (int i = displays.size() - 1; i >= 0; i--) {
                VdEntry old = displays.valueAt(i);
                if (name.equals(old.name)) {
                    Log.w(TAG, "createVirtualDisplay: same-name '" + name
                            + "' exists, destroy old id=" + old.displayId);
                    releaseEntryLocked(old);
                    displays.remove(old.displayId);
                }
            }
            int[] before = snapshotDisplayIds();
            ImageReader reader = null;
            IBinder token = null;
            try {
                reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
                ensureFrameDrain();
                reader.setOnImageAvailableListener(DRAIN_LISTENER, frameHandler);
                Surface surface = reader.getSurface();
                token = DisplayControlWrap.createVirtualDisplay(name, false);
                Object tx = DisplayControlWrap.openTransaction();
                try {
                    DisplayControlWrap.setDisplaySurface(tx, token, surface);
                    Rect rect = new Rect(0, 0, width, height);
                    DisplayControlWrap.setDisplayProjection(tx, token, 0, rect, rect);
                    DisplayControlWrap.setDisplayLayerStack(tx, token, DEFAULT_LAYER_STACK);
                    DisplayControlWrap.setDisplaySize(tx, token, width, height);
                } finally {
                    DisplayControlWrap.closeTransaction(tx);
                }
                // 真实 displayId：建屏前后对比 DisplayManagerGlobal.getDisplayIds() 取新增项
                //（SurfaceControl/DisplayControl 直建的 SF display 会被 DMS LogicalDisplayMapper
                // 收编出逻辑 displayId；差集轮询是 shell uid 下唯一不依赖隐藏签名状态的取法）
                int displayId = waitForNewDisplayId(before, DISPLAY_ID_WAIT_MS);
                if (displayId < 0) {
                    // 兜底内部句柄：此时 startAppOnDisplay 的 am start --display 无法命中该屏
                    displayId = fallbackId++;
                    Log.w(TAG, "displayId not resolved via DisplayManager diff, use fallback handle "
                            + displayId + " (startAppOnDisplay will fail until DMS registers it)");
                }
                VdEntry entry = new VdEntry(displayId, name, width, height, dpi, token, reader);
                displays.put(displayId, entry);
                // 帧泵真分发替换 drain（§4.4）：listener 持 entry 引用，onImageAvailable 直接
                // 转紧凑 RGBA 并 ping-pong 写 A/B MemoryFile；替换前由 DRAIN_LISTENER 兜底防饿死
                reader.setOnImageAvailableListener(new FramePumpListener(entry), frameHandler);
                Log.i(TAG, "virtual display created: id=" + displayId + " name=" + name
                        + " " + width + "x" + height + "@" + dpi
                        + " path=" + DisplayControlWrap.currentMode());
                return displayId;
            } catch (Throwable t) {
                Log.e(TAG, "createVirtualDisplay failed: " + t);
                // 失败回滚：已建 token/reader 全部释放，不抛穿 AIDL
                if (token != null) {
                    try {
                        DisplayControlWrap.destroyVirtualDisplay(token);
                    } catch (Throwable t2) {
                        Log.e(TAG, "rollback destroy token failed: " + t2);
                    }
                }
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (Throwable t2) {
                        Log.e(TAG, "rollback close reader failed: " + t2);
                    }
                }
                return -1;
            }
        }
    }

    @Override
    public boolean destroyVirtualDisplay(int displayId) {
        synchronized (vdLock) {
            VdEntry entry = displays.get(displayId);
            if (entry == null) {
                Log.w(TAG, "destroyVirtualDisplay: unknown displayId=" + displayId);
                return false;
            }
            displays.remove(displayId);
            releaseEntryLocked(entry);
            Log.i(TAG, "virtual display destroyed: id=" + displayId);
            return true;
        }
    }

    @Override
    public int startAppOnDisplay(String packageName, String activity, int displayId) {
        try {
            if (packageName == null || packageName.trim().isEmpty()) {
                Log.e(TAG, "startAppOnDisplay: packageName empty");
                return ERR_ARGS;
            }
            packageName = packageName.trim();
            String component;
            if (activity != null && !activity.trim().isEmpty()) {
                component = activity.contains("/") ? activity.trim() : packageName + "/" + activity.trim();
            } else {
                component = resolveLauncherComponent(packageName);
            }
            if (component == null || component.isEmpty()) {
                Log.e(TAG, "startAppOnDisplay: cannot resolve entry for " + packageName);
                return ERR_ARGS;
            }
            String[] cmd = {"/system/bin/am", "start", "--display", String.valueOf(displayId),
                    "-n", component};
            Log.i(TAG, "exec: " + joinArray(cmd));
            // shell 身份（uid 2000）执行；输出极小（一行级），顺序读 stdout/stderr 无死锁风险
            Process p = Runtime.getRuntime().exec(cmd);
            String out = readStream(p.getInputStream());
            String err = readStream(p.getErrorStream());
            int code = p.waitFor();
            if (code != 0 || looksLikeAmFailure(out) || looksLikeAmFailure(err)) {
                Log.e(TAG, "am start failed (exit=" + code + ") out=" + out.trim()
                        + " err=" + err.trim());
                return ERR_AM;
            }
            Log.i(TAG, "am start ok: " + out.trim());
            return 0;
        } catch (Throwable t) {
            Log.e(TAG, "startAppOnDisplay error: " + t);
            return ERR_EXEC;
        }
    }

    // ------------------------------------------------------------------
    // 按屏帧泵 + 定向注入（任务书 §4.3/§4.4）
    // ------------------------------------------------------------------

    @Override
    public ParcelFileDescriptor[] attachFrameSink(int displayId, int[] dims) {
        synchronized (vdLock) {
            VdEntry entry = displays.get(displayId);
            if (entry == null) {
                Log.w(TAG, "attachFrameSink: unknown displayId=" + displayId);
                return null;
            }
            try {
                entry.ensureSinkFiles();
                FileDescriptor fdA = entry.sinkFdA;
                FileDescriptor fdB = entry.sinkFdB;
                if (fdA == null || fdB == null) {
                    Log.e(TAG, "attachFrameSink: fd extract failed (id=" + displayId + ")");
                    return null;
                }
                if (dims != null && dims.length >= 2) {
                    dims[0] = entry.width;
                    dims[1] = entry.height;
                }
                // dup 出独立 fd 交 PFD 持有（不可 adoptFd：那会接管 MemoryFile 自身 fd，
                // PFD 回收时把服务端写侧 fd 一并关闭）；binder 传输再 dup 至客户端进程
                //（对照 IScreenShareService.getSharedFds 的 PFD 通道）
                Log.i(TAG, "attachFrameSink: id=" + displayId + " " + entry.width + "x" + entry.height
                        + " block=" + entry.blockSize);
                return new ParcelFileDescriptor[]{
                        ParcelFileDescriptor.dup(fdA),
                        ParcelFileDescriptor.dup(fdB)};
            } catch (Throwable t) {
                Log.e(TAG, "attachFrameSink error (id=" + displayId + "): " + t);
                return null;
            }
        }
    }

    @Override
    public long getFrameCounter(int displayId) {
        synchronized (vdLock) {
            VdEntry entry = displays.get(displayId);
            return entry != null ? entry.frameCounter.get() : -1L;
        }
    }

    @Override
    public int tapOnDisplay(int displayId, int x, int y, int durationMs) {
        VdEntry entry = lookupForInject(displayId);
        if (entry == null) {
            return ERR_INJ_DISPLAY_UNKNOWN;
        }
        if (durationMs <= 0) {
            durationMs = 50; // 对齐 Humanizer.tapDurationMs 缺省
        }
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
        int r = injectOnDisplay(down, displayId);
        down.recycle();
        if (r != 0) {
            return r;
        }
        SystemClock.sleep(durationMs);
        MotionEvent up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP, x, y, 0);
        r = injectOnDisplay(up, displayId);
        up.recycle();
        return r;
    }

    @Override
    public int longTapOnDisplay(int displayId, int x, int y, int durationMs) {
        VdEntry entry = lookupForInject(displayId);
        if (entry == null) {
            return ERR_INJ_DISPLAY_UNKNOWN;
        }
        if (durationMs <= 0) {
            durationMs = 1000; // 对齐 Humanizer.longTapDurationMs 缺省
        }
        return tapOnDisplay(displayId, x, y, durationMs);
    }

    @Override
    public int swipeOnDisplay(int displayId, int x1, int y1, int x2, int y2, int durationMs) {
        VdEntry entry = lookupForInject(displayId);
        if (entry == null) {
            return ERR_INJ_DISPLAY_UNKNOWN;
        }
        int duration = durationMs > 0 ? durationMs : 300; // 对齐 Humanizer.defaultSwipeDurationMs
        int steps = 20;                                   // 对齐 Humanizer.swipeSteps
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x1, y1, 0);
        int r = injectOnDisplay(down, displayId);
        down.recycle();
        if (r != 0) {
            return r;
        }
        long stepMs = duration / steps;
        for (int i = 1; i <= steps; i++) {
            float t = (float) i / steps;
            float ix = x1 + (x2 - x1) * t;
            float iy = y1 + (y2 - y1) * t;
            MotionEvent move = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_MOVE, ix, iy, 0);
            r = injectOnDisplay(move, displayId);
            move.recycle();
            if (r != 0) {
                // 中断滑动：原坐标抬指，防该屏残留按下态
                MotionEvent cancel = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_UP, x1, y1, 0);
                injectOnDisplay(cancel, displayId);
                cancel.recycle();
                return r;
            }
            if (i < steps && stepMs > 0) {
                SystemClock.sleep(stepMs);
            }
        }
        MotionEvent up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP, x2, y2, 0);
        r = injectOnDisplay(up, displayId);
        up.recycle();
        return r;
    }

    /** 定向注入前置校验（displayId 未建屏直接拒绝，禁止静默点错屏） */
    private VdEntry lookupForInject(int displayId) {
        synchronized (vdLock) {
            VdEntry entry = displays.get(displayId);
            if (entry == null) {
                Log.w(TAG, "injectOnDisplay: unknown displayId=" + displayId + ", rejected");
            }
            return entry;
        }
    }

    /**
     * 单事件定向注入：反射 {@code InputEvent.setDisplayId(int)}（隐藏方法，shell uid 可用），
     * 再经 {@link InjectorCore#injectEventRaw} 既有注入底座提交。
     * Method 结果静态缓存（每事件一次 getDeclaredMethod 在 swipe 20 步场景开销显著）；
     * **方法缺失时直接拒绝注入并返回 ERR_INJ_SET_DISPLAY_ID_MISSING——绝不降级主屏注入**
     * （先注后报会让"点虚拟屏"的调用实际点到主屏，属静默错屏，违反 02 §20.4 契约）。
     */
    private static volatile Method sSetDisplayIdMethod;
    private static volatile boolean sSetDisplayIdResolved;

    private static int injectOnDisplay(MotionEvent event, int displayId) {
        Method setDisplayId = sSetDisplayIdMethod;
        if (!sSetDisplayIdResolved) {
            try {
                setDisplayId = InputEvent.class.getDeclaredMethod("setDisplayId", int.class);
                setDisplayId.setAccessible(true);
            } catch (Throwable t) {
                Log.w(TAG, "InputEvent.setDisplayId unavailable, display-targeted injection refused: " + t);
                setDisplayId = null;
            }
            sSetDisplayIdMethod = setDisplayId;
            sSetDisplayIdResolved = true;
        }
        if (setDisplayId == null) {
            // 契约：预检缺失 → 拒绝注入（不产生任何主屏副作用），调用方转可读错误
            return ERR_INJ_SET_DISPLAY_ID_MISSING;
        }
        try {
            setDisplayId.invoke(event, displayId);
        } catch (Throwable t) {
            Log.w(TAG, "setDisplayId(" + displayId + ") failed: " + t);
            return ERR_INJ_SET_DISPLAY_ID_MISSING;
        }
        if (!InjectorCore.injectEventRaw(event)) {
            return ERR_INJ_REJECTED;
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // 帧宿主（§4.4 帧泵：每屏 A/B MemoryFile + ImageReader 真分发）
    // ------------------------------------------------------------------

    /** maxImages=3：无人消费会堵死 SurfaceFlinger 合成出队，先挂 drain 监听防饿死
     *  （建屏 → displayId 差集解析窗口内兜底；entry 建好后被 FramePumpListener 替换） */
    private static HandlerThread frameThread;
    private static Handler frameHandler;

    private static void ensureFrameDrain() {
        if (frameHandler != null) {
            return;
        }
        HandlerThread thread = new HandlerThread("autolua-vd-frame");
        thread.start();
        frameThread = thread;
        frameHandler = new Handler(thread.getLooper());
    }

    private static final ImageReader.OnImageAvailableListener DRAIN_LISTENER =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                    } catch (Throwable ignore) {
                    }
                    if (image != null) {
                        try {
                            image.close();
                        } catch (Throwable ignore) {
                        }
                    }
                }
            };

    /**
     * 按屏帧泵（§4.4）：onImageAvailable → acquireLatestImage → 紧凑 RGBA 转换 →
     * entry.publishFrame ping-pong 写。共享 frameHandler（多屏写帧在同一线程序列化，
     * 单帧写自包含：像素 → counter++ → 双块头，无跨屏顺序依赖）。无消费端也持续写
     * （脚本随时 attach 开始读）。
     */
    private static final class FramePumpListener implements ImageReader.OnImageAvailableListener {
        private final VdEntry entry;

        FramePumpListener(VdEntry entry) {
            this.entry = entry;
        }

        @Override
        public void onImageAvailable(ImageReader reader) {
            // 一致性守卫：同屏重建/销毁后旧 reader 的迟到回调直接丢弃
            if (reader != entry.reader) {
                return;
            }
            Image image = null;
            try {
                image = reader.acquireLatestImage(); // 只取最新帧，积压帧直接跳过
            } catch (Throwable ignore) {
            }
            if (image == null) {
                return;
            }
            try {
                int w = image.getWidth();
                int h = image.getHeight();
                byte[] frame = convertImageToByteArray(image);
                if (frame != null && !isBlankFrame(frame)) {
                    entry.publishFrame(frame, w, h);
                }
            } catch (Throwable t) {
                Log.e(TAG, "frame pump error (id=" + entry.displayId + "): " + t);
            } finally {
                // Image 用后即刻归还生产者（对照 CaptureScreenService 同款纪律）
                try {
                    image.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /**
     * Image → 紧凑 RGBA byte[]：stride 快路径（rowStride==w*4 且 pixelStride==4 整块拷贝）/
     * 慢路径（逐行拷贝 + alpha 强制 255），ThreadLocal 缓冲复用
     * （对照 CaptureScreenService.convertImageToByteArray 的 stride 处理；虚拟屏
     * ImageReader 固定 RGBA_8888，无 RGB_565 解包路径）。
     */
    private static final ThreadLocal<byte[]> PUMP_BUFFER = new ThreadLocal<>();

    private static byte[] convertImageToByteArray(Image image) {
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
            byte[] out = PUMP_BUFFER.get();
            if (out == null || out.length < frameSize) {
                out = new byte[frameSize];
                PUMP_BUFFER.set(out);
            }
            buffer.rewind();
            if (pixelStride == 4 && rowStride == width * 4) {
                // 快路径：整块拷贝
                buffer.get(out, 0, Math.min(buffer.remaining(), frameSize));
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
        } catch (Throwable t) {
            Log.e(TAG, "convert image error: " + t);
            return null;
        }
    }

    /** 空帧过滤：前 1000 字节内非零字节 < 1% 判无效丢弃（对照 CaptureScreenService 同款） */
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
    // 内部实现
    // ------------------------------------------------------------------

    /** 释放单实例（须持 vdLock）：先双路径销毁 display token，再关 ImageReader 帧宿主与帧泵 MemoryFile */
    private static void releaseEntryLocked(VdEntry entry) {
        try {
            DisplayControlWrap.destroyVirtualDisplay(entry.token);
        } catch (Throwable t) {
            Log.e(TAG, "destroy display token failed (id=" + entry.displayId + "): " + t);
        }
        try {
            entry.reader.close();
        } catch (Throwable t) {
            Log.e(TAG, "close ImageReader failed (id=" + entry.displayId + "): " + t);
        }
        entry.releaseSinkFiles();
    }

    private void releaseAllDisplays() {
        synchronized (vdLock) {
            for (int i = displays.size() - 1; i >= 0; i--) {
                releaseEntryLocked(displays.valueAt(i));
            }
            displays.clear();
        }
    }

    /**
     * 真实 displayId 解析：轮询 {@code DisplayManagerGlobal.getInstance().getDisplayIds()}
     * 与建屏前快照的差集，超时返回 -1（调用方落 fallback 内部句柄）。
     */
    private static int waitForNewDisplayId(int[] before, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(DISPLAY_ID_POLL_MS);
            int[] now = snapshotDisplayIds();
            for (int id : now) {
                boolean existed = false;
                for (int b : before) {
                    if (b == id) {
                        existed = true;
                        break;
                    }
                }
                if (!existed) {
                    return id;
                }
            }
        }
        return -1;
    }

    /** DisplayManagerGlobal.getDisplayIds() 反射快照（shell uid 可直查全部 display） */
    private static int[] snapshotDisplayIds() {
        try {
            Class<?> global = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object instance = global.getDeclaredMethod("getInstance").invoke(null);
            int[] ids = (int[]) global.getDeclaredMethod("getDisplayIds").invoke(instance);
            return ids != null ? ids : new int[0];
        } catch (Throwable t) {
            Log.w(TAG, "getDisplayIds failed: " + t);
            return new int[0];
        }
    }

    /**
     * 仅给包名时自动找入口：先经 Context PackageManager.getLaunchIntentForPackage；
     * 失败回退 shell 通道 exec "cmd package resolve-activity --brief" 解析组件行。
     * 找不到返回 null（调用方回 ERR_ARGS）。
     */
    private String resolveLauncherComponent(String packageName) {
        if (context != null) {
            try {
                Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
                if (intent != null && intent.getComponent() != null) {
                    return intent.getComponent().flattenToString();
                }
            } catch (Throwable t) {
                Log.w(TAG, "resolve via PackageManager failed: " + t);
            }
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "/system/bin/cmd", "package", "resolve-activity", "--brief",
                    "-a", "android.intent.action.MAIN",
                    "-c", "android.intent.category.LAUNCHER", packageName});
            String out = readStream(p.getInputStream());
            String err = readStream(p.getErrorStream());
            p.waitFor();
            String line = pickComponentLine(out);
            if (line != null) {
                return expandShortComponent(line);
            }
            Log.w(TAG, "resolve-activity no component line, out=" + out.trim() + " err=" + err.trim());
        } catch (Throwable t) {
            Log.w(TAG, "resolve via cmd failed: " + t);
        }
        return null;
    }

    /** 从 resolve-activity --brief 输出里取组件行：含 '/' 且排除 key=value / "No activity" 噪音，取最后一条 */
    private static String pickComponentLine(String out) {
        if (out == null) {
            return null;
        }
        String found = null;
        for (String line : out.split("\n")) {
            String s = line.trim();
            if (s.contains("/") && !s.contains("=") && !s.contains("No activity")
                    && !s.startsWith("priority") && !s.startsWith("preferredOrder")) {
                found = s;
            }
        }
        return found;
    }

    /** 短组件名展开：pkg/.Cls → pkg/pkg.Cls */
    private static String expandShortComponent(String component) {
        int slash = component.indexOf('/');
        if (slash <= 0 || slash == component.length() - 1) {
            return null;
        }
        String pkg = component.substring(0, slash);
        String cls = component.substring(slash + 1);
        if (cls.startsWith(".")) {
            cls = pkg + cls;
        }
        return pkg + "/" + cls;
    }

    /** am 失败嗅探：退出码之外的输出特征（Error/Exception/does not exist 等，大小写不敏感） */
    private static boolean looksLikeAmFailure(String output) {
        if (output == null || output.isEmpty()) {
            return false;
        }
        String s = output.toLowerCase();
        return s.contains("error") || s.contains("exception") || s.contains("does not exist")
                || s.contains("not found") || s.contains("security exception") || s.contains("failure");
    }

    private static String readStream(InputStream is) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[512];
        try {
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() >= EXEC_OUTPUT_LIMIT) {
                    break; // 截断防刷屏
                }
            }
        } catch (Throwable ignore) {
        } finally {
            try {
                is.close();
            } catch (Throwable ignore) {
            }
        }
        return bos.toString();
    }

    private static String joinArray(String[] arr) {
        StringBuilder sb = new StringBuilder();
        for (String s : arr) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * 虚拟屏条目：displayId（真实或 fallback 句柄）+ token + 帧宿主 + 建屏参数
     * + §4.4 按屏帧泵（A/B MemoryFile + AtomicLong frameCounter + ping-pong 下标）。
     */
    private static final class VdEntry {
        final int displayId;
        final String name;
        final int width;
        final int height;
        final int dpi;
        final IBinder token;
        final ImageReader reader;

        // ---- §4.4 帧泵状态（publishFrame/ensureSinkFiles 串行于 frameHandler 线程；
        //      ensureSinkFiles/releaseSinkFiles 额外受 vdLock 保护）----
        /** 单块尺寸 = w*h*4 + 9（与 SharedMemoryManager 布局一致） */
        final int blockSize;
        /** 跨进程 seqlock 计数器：读侧经 AIDL getFrameCounter 轮询 */
        final AtomicLong frameCounter = new AtomicLong(0);
        /** ping-pong 当前发布块下标（0=A，1=B） */
        int readBufferIndex = 1;
        MemoryFile fileA;
        MemoryFile fileB;
        FileDescriptor sinkFdA;
        FileDescriptor sinkFdB;
        /** 9 字节自描述头暂存（[0]=bufferIndex [1..4]=w BE [5..8]=h BE） */
        private final byte[] header = new byte[SharedMemoryManager.HEADER_SIZE];

        VdEntry(int displayId, String name, int width, int height, int dpi,
                IBinder token, ImageReader reader) {
            this.displayId = displayId;
            this.name = name;
            this.width = width;
            this.height = height;
            this.dpi = dpi;
            this.token = token;
            this.reader = reader;
            this.blockSize = width * height * 4 + SharedMemoryManager.HEADER_SIZE;
        }

        /** 懒建帧泵双缓冲（首次 attachFrameSink 调用；重复调用复用，须持 vdLock） */
        void ensureSinkFiles() {
            if (fileA != null && fileB != null) {
                return;
            }
            try {
                if (fileA == null) {
                    fileA = new MemoryFile("autolua_vd_" + displayId + "_a", blockSize);
                    sinkFdA = extractFd(fileA);
                }
                if (fileB == null) {
                    fileB = new MemoryFile("autolua_vd_" + displayId + "_b", blockSize);
                    sinkFdB = extractFd(fileB);
                }
                Log.i(TAG, "frame sink ready: id=" + displayId + " block=" + blockSize);
            } catch (Throwable t) {
                Log.e(TAG, "ensureSinkFiles failed (id=" + displayId + "): " + t);
                releaseSinkFiles();
            }
        }

        /** 释放帧泵双缓冲（须持 vdLock；fd 对象随 MemoryFile 一起失效） */
        void releaseSinkFiles() {
            for (MemoryFile f : new MemoryFile[]{fileA, fileB}) {
                if (f != null) {
                    try {
                        f.close();
                    } catch (Throwable ignore) {
                    }
                }
            }
            fileA = null;
            fileB = null;
            sinkFdA = null;
            sinkFdB = null;
        }

        /**
         * ping-pong 写帧（对齐 SharedMemoryManager.writeFrameSwap 协议顺序）：
         * 1) 像素写 inactive 块（自 9 字节头偏移）→ 2) 翻 readBufferIndex →
         * 3) counter++（跨进程发布点）→ 4) 9 字节自描述头写两块。
         * 读侧 seqlock 语义（AIDL counter 前后一致 + 头校验）依赖此顺序。
         */
        void publishFrame(byte[] frame, int w, int h) {
            synchronized (this) {
                MemoryFile targetFile;
                MemoryFile otherFile;
                if (fileA != null && fileB != null) {
                    int target = 1 - readBufferIndex;
                    targetFile = target == 0 ? fileA : fileB;
                    otherFile = target == 0 ? fileB : fileA;
                    try {
                        // 1) 像素写 inactive 块（协议规定像素自 HEADER_SIZE 偏移起）
                        int len = Math.min(frame.length, w * h * 4);
                        targetFile.writeBytes(frame, 0, SharedMemoryManager.HEADER_SIZE, len);
                        // 2) 翻发布块
                        readBufferIndex = target;
                        // 3) counter++（发布点：读侧 c1==c2 且偶数 = 完整帧）
                        frameCounter.incrementAndGet();
                        // 4) 9 字节自描述头写两块（[0]=bufferIndex [1..4]=w BE [5..8]=h BE）
                        header[0] = (byte) readBufferIndex;
                        putIntBE(header, 1, w);
                        putIntBE(header, 5, h);
                        targetFile.writeBytes(header, 0, 0, SharedMemoryManager.HEADER_SIZE);
                        otherFile.writeBytes(header, 0, 0, SharedMemoryManager.HEADER_SIZE);
                    } catch (Throwable t) {
                        Log.e(TAG, "publishFrame failed (id=" + displayId + "): " + t);
                    }
                }
                // 帧泵 sink 未 attach：仅 drain（Image 已在 listener 侧 close），不落盘
            }
        }

        private static void putIntBE(byte[] b, int off, int v) {
            b[off] = (byte) (v >>> 24);
            b[off + 1] = (byte) (v >>> 16);
            b[off + 2] = (byte) (v >>> 8);
            b[off + 3] = (byte) v;
        }

        /** MemoryFile fd 提取（复用 SharedMemoryManager 同款反射逻辑） */
        private static FileDescriptor extractFd(MemoryFile file) {
            try {
                Method getFileDescriptor = MemoryFile.class.getDeclaredMethod("getFileDescriptor");
                getFileDescriptor.setAccessible(true);
                return (FileDescriptor) getFileDescriptor.invoke(file);
            } catch (Throwable t) {
                Log.e(TAG, "extract MemoryFile fd failed: " + t);
                return null;
            }
        }
    }
}
