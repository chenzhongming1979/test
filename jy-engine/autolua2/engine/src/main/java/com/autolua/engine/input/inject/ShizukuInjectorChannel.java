package com.autolua.engine.input.inject;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileDescriptor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * 通道三：SHIZUKU —— 绑定 UserService（shell uid 2000）注入
 * （对照 WrappedShizuku.java：授权 :120-126、绑定等待 :200-207、UserService :304-364）。
 * <p>
 * 依赖 dev.rikka.shizuku:api/provider:13.1.5（见 engine/build.gradle.kts 与
 * AndroidManifest 中追加的 ShizukuProvider 声明）。
 */
public class ShizukuInjectorChannel implements IInjectChannel {

    private static final String TAG = "ShizukuInjectorChannel";
    /** 自定义授权 requestCode（对照 WrappedShizuku.mRequestCode） */
    private static final int REQUEST_CODE = 0x00A55000;
    /** UserService 绑定等待时长（对照 WrappedShizuku.initializeShizukuServiceAndWait(5000)） */
    private static final long BIND_WAIT_MS = 5000L;
    /**
     * UserService 版本号（versionTag）：AIDL 接口面变更时必须 bump——旧版 UserService 进程因
     * versionTag 失配被 Shizuku 杀掉重绑新实例。
     * v2（2026-09）：IInjectorService 追加虚拟屏三方法 createVirtualDisplay /
     * destroyVirtualDisplay / startAppOnDisplay（虚拟屏任务书 §4.1/§4.2）。
     * v3（2026-09）：IInjectorService 追加按屏帧泵 attachFrameSink / getFrameCounter 与
     * displayId 定向注入 tapOnDisplay / longTapOnDisplay / swipeOnDisplay（任务书 §4.3/§4.4）。
     */
    private static final int USER_SERVICE_VERSION = 3;

    private final Object lock = new Object();

    private volatile Context appContext;
    /** UserServiceArgs 惰性构造（需要应用包名构造 ComponentName） */
    private volatile Shizuku.UserServiceArgs userServiceArgs;
    /** 远端 UserService binder 代理 */
    private volatile IInjectorService service;

    /** 可选：注入应用上下文（否则反射 ActivityThread.currentApplication 兜底） */
    public void setApplicationContext(Context context) {
        this.appContext = context != null ? context.getApplicationContext() : null;
    }

    @Override
    public String name() {
        return "SHIZUKU";
    }

    /**
     * Shizuku 已授权（内部吞掉 binder 未就绪等异常）。
     * 13.1.5 无 isPermissionGranted()，用 checkSelfPermission()==0 判断；
     * pre-v11 服务端不支持，直接拒绝（对照 WrappedShizuku pre-v11 拒绝逻辑）。
     */
    private static boolean granted() {
        try {
            if (Shizuku.isPreV11()) {
                return false;
            }
            return Shizuku.checkSelfPermission() == 0; // PackageManager.PERMISSION_GRANTED
        } catch (Throwable t) {
            return false;
        }
    }

    /** 触发 Shizuku 授权（异步弹窗；结果需再次 start() 或调用注入动作时生效） */
    public boolean requestPermission() {
        if (granted()) {
            return true;
        }
        try {
            Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable t) {
            Log.e(TAG, "requestPermission failed: " + t);
        }
        return false;
    }

    @Override
    public boolean start() {
        synchronized (lock) {
            if (!granted()) {
                return false;
            }
            return ensureServiceLocked();
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            service = null;
        }
    }

    @Override
    public boolean isAvailable() {
        return granted();
    }

    @Override
    public boolean injectDown(int pointerId, float x, float y, long downTime) {
        IInjectorService s = ensureService();
        if (s == null) {
            return false;
        }
        try {
            return s.down((int) x, (int) y, pointerId, downTime);
        } catch (Throwable t) {
            handleRemoteFailure(t);
            return false;
        }
    }

    @Override
    public boolean injectMove(int pointerId, float x, float y, long downTime) {
        IInjectorService s = ensureService();
        if (s == null) {
            return false;
        }
        try {
            return s.move((int) x, (int) y, pointerId, downTime);
        } catch (Throwable t) {
            handleRemoteFailure(t);
            return false;
        }
    }

    @Override
    public boolean injectUp(int pointerId, float x, float y, long downTime) {
        IInjectorService s = ensureService();
        if (s == null) {
            return false;
        }
        try {
            return s.up(pointerId, (int) x, (int) y, downTime);
        } catch (Throwable t) {
            handleRemoteFailure(t);
            return false;
        }
    }

    @Override
    public boolean tap(float x, float y) {
        IInjectorService s = ensureService();
        if (s == null) {
            return false;
        }
        try {
            return s.tap((int) x, (int) y, 0);
        } catch (Throwable t) {
            handleRemoteFailure(t);
            return false;
        }
    }

    @Override
    public boolean swipe(float x1, float y1, float x2, float y2, int durationMs) {
        IInjectorService s = ensureService();
        if (s == null) {
            return false;
        }
        try {
            return s.swipe((int) x1, (int) y1, (int) x2, (int) y2, durationMs);
        } catch (Throwable t) {
            handleRemoteFailure(t);
            return false;
        }
    }

    @Override
    public boolean pinch(float cx, float cy, float radius, float angle, int durationMs, boolean zoomIn) {
        IInjectorService s = ensureService();
        if (s == null) {
            return false;
        }
        try {
            return s.pinch((int) cx, (int) cy, (int) radius, (int) angle, durationMs, zoomIn);
        } catch (Throwable t) {
            handleRemoteFailure(t);
            return false;
        }
    }

    @Override
    public boolean gestureJson(String strokesJson) {
        IInjectorService s = ensureService();
        if (s == null) {
            return false;
        }
        try {
            return s.gesture(strokesJson);
        } catch (Throwable t) {
            handleRemoteFailure(t);
            return false;
        }
    }

    /** 复位 UserService 进程内指针状态（AIDL reset） */
    public boolean resetState() {
        IInjectorService s = ensureService();
        if (s == null) {
            return false;
        }
        try {
            return s.reset();
        } catch (Throwable t) {
            handleRemoteFailure(t);
            return false;
        }
    }

    /** 下发 UserService 进程内运行时配置（AIDL config） */
    public boolean applyConfig(String key, String value) {
        IInjectorService s = ensureService();
        if (s == null) {
            return false;
        }
        try {
            return s.config(key, value);
        } catch (Throwable t) {
            handleRemoteFailure(t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 虚拟屏（虚拟屏任务书 §4.1/§4.2，供下一批 VirtualDisplayLib 调用）
    // ------------------------------------------------------------------

    /** 创建虚拟屏（shell uid），返回系统 displayId；-1 失败（AIDL createVirtualDisplay） */
    public int createVirtualDisplay(String name, int width, int height, int dpi) {
        IInjectorService s = ensureService();
        if (s == null) {
            return -1;
        }
        try {
            return s.createVirtualDisplay(name, width, height, dpi);
        } catch (Throwable t) {
            handleRemoteFailure(t);
            return -1;
        }
    }

    /** 销毁虚拟屏并释放服务端帧宿主（AIDL destroyVirtualDisplay） */
    public boolean destroyVirtualDisplay(int displayId) {
        IInjectorService s = ensureService();
        if (s == null) {
            return false;
        }
        try {
            return s.destroyVirtualDisplay(displayId);
        } catch (Throwable t) {
            handleRemoteFailure(t);
            return false;
        }
    }

    /**
     * 按屏拉起 App（AIDL startAppOnDisplay）：0 成功 / 非 0 失败码
     * （1 参数或入口解析失败，2 执行失败，3 am 报告错误；activity 传空串服务端自动 resolve）。
     */
    public int startAppOnDisplay(String packageName, String activity, int displayId) {
        IInjectorService s = ensureService();
        if (s == null) {
            return ShizukuUserServiceMain.ERR_EXEC;
        }
        try {
            return s.startAppOnDisplay(packageName, activity, displayId);
        } catch (Throwable t) {
            handleRemoteFailure(t);
            return ShizukuUserServiceMain.ERR_EXEC;
        }
    }

    // ------------------------------------------------------------------
    // 虚拟屏第二批（任务书 §4.3/§4.4，供 VirtualDisplayLib/VdFrameClient/VdHandle 调用）
    // ------------------------------------------------------------------

    /**
     * attach 按屏帧泵（AIDL attachFrameSink）：成功返回 [fdA, fdB]
     * （ParcelFileDescriptor，binder 传输已在客户端进程 dup）；失败返回 null。
     * dimsOut 传 2 元素数组时由服务端填 [width, height]。
     */
    public ParcelFileDescriptor[] attachFrameSink(int displayId, int[] dimsOut) {
        IInjectorService s = ensureService();
        if (s == null) {
            return null;
        }
        try {
            return s.attachFrameSink(displayId, dimsOut != null ? dimsOut : new int[0]);
        } catch (Throwable t) {
            handleRemoteFailure(t);
            return null;
        }
    }

    /** 跨进程帧计数器轮询（AIDL getFrameCounter；服务不可用/displayId 未建屏返回 -1） */
    public long getFrameCounter(int displayId) {
        IInjectorService s = ensureService();
        if (s == null) {
            return -1L;
        }
        try {
            return s.getFrameCounter(displayId);
        } catch (Throwable t) {
            handleRemoteFailure(t);
            return -1L;
        }
    }

    /**
     * displayId 定向注入（AIDL tapOnDisplay/longTapOnDisplay/swipeOnDisplay）。
     * 返回 0 成功 / 1 参数非法 / 2 displayId 未建屏 / 3 setDisplayId 缺失已降级主屏注入 /
     * 4 注入被系统拒绝（服务不可用折算 4）。
     */
    public int tapOnDisplay(int displayId, int x, int y, int durationMs) {
        IInjectorService s = ensureService();
        if (s == null) {
            return ShizukuUserServiceMain.ERR_INJ_REJECTED;
        }
        try {
            return s.tapOnDisplay(displayId, x, y, durationMs);
        } catch (Throwable t) {
            handleRemoteFailure(t);
            return ShizukuUserServiceMain.ERR_INJ_REJECTED;
        }
    }

    public int longTapOnDisplay(int displayId, int x, int y, int durationMs) {
        IInjectorService s = ensureService();
        if (s == null) {
            return ShizukuUserServiceMain.ERR_INJ_REJECTED;
        }
        try {
            return s.longTapOnDisplay(displayId, x, y, durationMs);
        } catch (Throwable t) {
            handleRemoteFailure(t);
            return ShizukuUserServiceMain.ERR_INJ_REJECTED;
        }
    }

    public int swipeOnDisplay(int displayId, int x1, int y1, int x2, int y2, int durationMs) {
        IInjectorService s = ensureService();
        if (s == null) {
            return ShizukuUserServiceMain.ERR_INJ_REJECTED;
        }
        try {
            return s.swipeOnDisplay(displayId, x1, y1, x2, y2, durationMs);
        } catch (Throwable t) {
            handleRemoteFailure(t);
            return ShizukuUserServiceMain.ERR_INJ_REJECTED;
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 懒初始化：service 不可用时先绑定再执行（对照 WrappedShizuku 各动作的 service==null 分支） */
    private IInjectorService ensureService() {
        synchronized (lock) {
            if (!ensureServiceLocked()) {
                return null;
            }
            return service;
        }
    }

    private boolean ensureServiceLocked() {
        if (service != null) {
            try {
                if (service.asBinder().pingBinder()) {
                    return true;
                }
            } catch (Throwable ignore) {
            }
            service = null;
        }
        if (!granted()) {
            return false;
        }
        try {
            CountDownLatch latch = new CountDownLatch(1);
            ServiceConnection conn = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    try {
                        IInjectorService s = IInjectorService.Stub.asInterface(binder);
                        if (binder != null && binder.pingBinder()) {
                            service = s;
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "asInterface failed: " + t);
                    }
                    latch.countDown();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    // 服务断开：保留引用，下次调用 pingBinder 失败后自动重绑
                }
            };
            Shizuku.bindUserService(userServiceArgs(), conn);
            latch.await(BIND_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            Log.e(TAG, "bindUserService failed: " + t);
        }
        return service != null;
    }

    /**
     * UserServiceArgs（对照 WrappedShizuku :271-275 的 processName/versioned 语义）。
     * 13.1.5 的 UserServiceArgs 只有 processNameSuffix/version，
     * 用 suffix "autolua-shizuku" + USER_SERVICE_VERSION 保证服务实例唯一；
     * AIDL 接口面变更时 bump 版本号触发重绑（见常量注释）。
     */
    private Shizuku.UserServiceArgs userServiceArgs() {
        Shizuku.UserServiceArgs args = userServiceArgs;
        if (args == null) {
            String pkg = resolvePackageName();
            args = new Shizuku.UserServiceArgs(new ComponentName(pkg,
                    ShizukuUserServiceMain.class.getName()))
                    .processNameSuffix("autolua-shizuku")
                    .version(USER_SERVICE_VERSION);
            userServiceArgs = args;
        }
        return args;
    }

    private String resolvePackageName() {
        if (appContext != null) {
            return appContext.getPackageName();
        }
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app != null) {
                Object pkg = app.getClass().getMethod("getPackageName").invoke(app);
                if (pkg instanceof String) {
                    return (String) pkg;
                }
            }
        } catch (Throwable ignore) {
        }
        // 兜底：宿主包名不可知时退化为 engine namespace（app 层建议通过 setApplicationContext 注入）
        return "com.autolua.engine";
    }

    /** binder 异常：丢弃代理触发下次重绑 */
    private void handleRemoteFailure(Throwable t) {
        Log.e(TAG, "remote inject failed: " + t);
        service = null;
    }
}
