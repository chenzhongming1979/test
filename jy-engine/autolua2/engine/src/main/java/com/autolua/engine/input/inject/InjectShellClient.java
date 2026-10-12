package com.autolua.engine.input.inject;

import android.content.Context;
import android.util.Log;

/**
 * 输入注入统一门面（对照派大星 TouchShellClient）：
 * 四级降级链 DIRECT_ROOT → ROOT(app_process) → SHIZUKU → ACCESSIBILITY → NONE。
 * <p>
 * start() 优先级（对照 TouchShellClient.start:322-344 与方案二 3.3.1）：
 * <ol>
 *     <li>{@code Process.myUid()==0} 且进程内反射可达 → DIRECT_ROOT（零 fork）</li>
 *     <li>Shizuku 已授权 → SHIZUKU</li>
 *     <li>{@code Runtime.exec("su")} 探测成功（id 输出含 uid=0）→ ROOT</li>
 *     <li>无障碍服务已连接 → ACCESSIBILITY（{@link AccessibilityChannel} 直连
 *         {@link com.autolua.engine.accessibility.GestureQueueManager}）</li>
 *     <li>NONE</li>
 * </ol>
 * 每类动作执行失败时：当前通道失败 → 按降级序尝试下一通道（对照 :44-78）。
 * 注入器状态机全部 synchronized，线程安全。
 */
public final class InjectShellClient {

    private static final String TAG = "InjectShellClient";

    private static InjectShellClient instance;

    private InjectMode mode = InjectMode.NONE;

    /**
     * 手动偏好模式（对照派大星 run_mode 概念）：null=自动降级链；
     * 设定后 start()/dispatch 优先尝试该通道（不可用仍回落自动链，保证可用性）。
     * 由 app 层权限引导面板设置并持久化。
     */
    private volatile InjectMode preferredMode;

    private final DirectRootChannel directRootChannel = new DirectRootChannel();
    private final RootInjectorChannel rootInjectorChannel = new RootInjectorChannel();
    private final ShizukuInjectorChannel shizukuInjectorChannel = new ShizukuInjectorChannel();
    /** 无障碍手势通道（构造时注册，参与降级链） */
    private final AccessibilityChannel accessibilityChannel = new AccessibilityChannel();

    private String apkPath;
    private Context appContext;

    private InjectShellClient() {
    }

    public static synchronized InjectShellClient getInstance() {
        if (instance == null) {
            instance = new InjectShellClient();
        }
        return instance;
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    /**
     * 注入 APK 路径（ROOT 通道 app_process 的 CLASSPATH）。
     * engine 是 library，需由 app 层传 {@code context.getPackageCodePath()}；
     * 未注入时通道内部会反射 ActivityThread.currentApplication 兜底。
     */
    public synchronized void setApkPath(String path) {
        this.apkPath = path;
        rootInjectorChannel.setApkPath(path);
    }

    /** 注入应用上下文（Shizuku ComponentName / apkPath 兜底均会用到） */
    public synchronized void setApplicationContext(Context context) {
        Context app = context != null ? context.getApplicationContext() : null;
        this.appContext = app;
        rootInjectorChannel.setApplicationContext(app);
        shizukuInjectorChannel.setApplicationContext(app);
    }

    /** 当前生效通道 */
    public synchronized InjectMode getMode() {
        return mode;
    }

    /** 手动偏好注入模式（null=自动降级链） */
    public synchronized void setPreferredMode(InjectMode preferredMode) {
        this.preferredMode = preferredMode;
        Log.i(TAG, "preferred inject mode set to: " + preferredMode);
    }

    /** 当前手动偏好模式（null=自动） */
    public synchronized InjectMode getPreferredMode() {
        return preferredMode;
    }

    /** 模式 → 通道实例映射 */
    private IInjectChannel channelOfMode(InjectMode m) {
        if (m == null) {
            return null;
        }
        switch (m) {
            case DIRECT_ROOT:
                return directRootChannel;
            case ROOT:
                return rootInjectorChannel;
            case SHIZUKU:
                return shizukuInjectorChannel;
            case ACCESSIBILITY:
                return accessibilityChannel;
            default:
                return null;
        }
    }

    /** 是否已有可用通道 */
    public synchronized boolean isStarted() {
        return mode != InjectMode.NONE;
    }

    /** 触发 Shizuku 授权（未授权时弹系统授权框，异步） */
    public boolean requestShizukuPermission() {
        return shizukuInjectorChannel.requestPermission();
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 启动注入体系（幂等）。手动偏好模式优先，否则按 DIRECT_ROOT → SHIZUKU → ROOT → (ACCESSIBILITY) → NONE 择优。
     */
    public synchronized boolean start() {
        if (mode != InjectMode.NONE) {
            return true;
        }
        // 0. 手动偏好模式优先尝试（用户显式选择；不可用回落自动链）
        InjectMode pref = preferredMode;
        if (pref != null) {
            IInjectChannel ch = channelOfMode(pref);
            if (ch != null && ch.start()) {
                mode = pref;
                Log.i(TAG, "Using preferred " + mode + " mode");
                return true;
            }
            Log.w(TAG, "preferred mode " + pref + " unavailable, fallback to auto chain");
        }
        // 1. uid==0 且进程内反射可达 → DIRECT_ROOT
        if (directRootChannel.start()) {
            mode = InjectMode.DIRECT_ROOT;
            Log.i(TAG, "Using DIRECT_ROOT mode (in-process, no fork)");
            return true;
        }
        // 2. Shizuku 已授权 → SHIZUKU
        if (shizukuInjectorChannel.start()) {
            mode = InjectMode.SHIZUKU;
            Log.i(TAG, "Using SHIZUKU mode");
            return true;
        }
        // 3. su 可执行 → ROOT(app_process)
        if (rootInjectorChannel.start()) {
            mode = InjectMode.ROOT;
            Log.i(TAG, "Using ROOT mode (separate process)");
            return true;
        }
        // 4. 无障碍服务已连接 → ACCESSIBILITY（免 root 兜底，直连 GestureQueueManager）
        if (accessibilityChannel.start()) {
            mode = InjectMode.ACCESSIBILITY;
            Log.i(TAG, "Using ACCESSIBILITY mode");
            return true;
        }
        // 5. NONE
        Log.w(TAG, "No inject channel available");
        return false;
    }

    /** 停止全部通道并复位状态 */
    public synchronized void stop() {
        rootInjectorChannel.stop();
        shizukuInjectorChannel.stop();
        directRootChannel.stop();
        accessibilityChannel.stop();
        mode = InjectMode.NONE;
    }

    // ------------------------------------------------------------------
    // 动作（当前通道失败自动按降级序尝试下一通道）
    // ------------------------------------------------------------------

    public boolean injectDown(final int pointerId, final float x, final float y, final long downTime) {
        return dispatch(new Action() {
            @Override
            public boolean run(IInjectChannel ch) {
                return ch.injectDown(pointerId, x, y, downTime);
            }
        });
    }

    public boolean injectMove(final int pointerId, final float x, final float y, final long downTime) {
        return dispatch(new Action() {
            @Override
            public boolean run(IInjectChannel ch) {
                return ch.injectMove(pointerId, x, y, downTime);
            }
        });
    }

    public boolean injectUp(final int pointerId, final float x, final float y, final long downTime) {
        return dispatch(new Action() {
            @Override
            public boolean run(IInjectChannel ch) {
                return ch.injectUp(pointerId, x, y, downTime);
            }
        });
    }

    public boolean tap(final float x, final float y) {
        return dispatch(new Action() {
            @Override
            public boolean run(IInjectChannel ch) {
                return ch.tap(x, y);
            }
        });
    }

    public boolean swipe(final float x1, final float y1, final float x2, final float y2, final int durationMs) {
        return dispatch(new Action() {
            @Override
            public boolean run(IInjectChannel ch) {
                return ch.swipe(x1, y1, x2, y2, durationMs);
            }
        });
    }

    public boolean pinch(final float cx, final float cy, final float radius, final float angle,
                         final int durationMs, final boolean zoomIn) {
        return dispatch(new Action() {
            @Override
            public boolean run(IInjectChannel ch) {
                return ch.pinch(cx, cy, radius, angle, durationMs, zoomIn);
            }
        });
    }

    public boolean gestureJson(final String strokesJson) {
        return dispatch(new Action() {
            @Override
            public boolean run(IInjectChannel ch) {
                return ch.gestureJson(strokesJson);
            }
        });
    }

    /**
     * 键码注入（KeyEvent down+up）：走统一降级链，DIRECT_ROOT/ROOT 通道经
     * InputManager 反射注入（同系统 input keyevent）；其余通道不支持回 false，
     * 由调用方走 ImeApi（模拟输入法）兜底。
     */
    public boolean key(final int keycode) {
        return dispatch(new Action() {
            @Override
            public boolean run(IInjectChannel ch) {
                return ch.injectKey(keycode);
            }
        });
    }

    /**
     * 复位当前通道的指针状态（对照 TouchShellClient.reset）。
     */
    public synchronized boolean reset() {
        try {
            switch (mode) {
                case ROOT:
                    return rootInjectorChannel.resetState();
                case SHIZUKU:
                    return shizukuInjectorChannel.resetState();
                case DIRECT_ROOT:
                    return InjectorCore.reset();
                case ACCESSIBILITY:
                    // 无障碍手势为一次性整条派发，无远端指针状态，仅丢弃在途笔划
                    accessibilityChannel.resetState();
                    return true;
                default:
                    return false;
            }
        } catch (Throwable t) {
            Log.w(TAG, "reset failed: " + t);
            return false;
        }
    }

    /** 设置 move 插值合并阈值（行协议 config move_threshold，对照 TouchShellClient.setMoveThreshold） */
    public boolean setMoveThreshold(int threshold) {
        return config("move_threshold", String.valueOf(threshold));
    }

    /** 通用配置下发：当前通道优先，进程内 Humanizer 同步生效 */
    public synchronized boolean config(String key, String value) {
        if (key == null || value == null) {
            return false;
        }
        boolean ok = false;
        try {
            switch (mode) {
                case ROOT:
                    ok = rootInjectorChannel.applyConfig(key, value);
                    break;
                case SHIZUKU:
                    ok = shizukuInjectorChannel.applyConfig(key, value);
                    break;
                case DIRECT_ROOT:
                    ok = InjectorCore.config(key, value);
                    break;
                case ACCESSIBILITY:
                    // 人化由客户端 Path 累积实现，无远端配置可下发，直接视为生效
                    ok = true;
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "config failed: " + t);
        }
        return ok;
    }

    // ------------------------------------------------------------------
    // 降级调度
    // ------------------------------------------------------------------

    private interface Action {
        boolean run(IInjectChannel channel);
    }

    /**
     * 动作调度：先执行当前通道；失败按 DIRECT_ROOT → ROOT → SHIZUKU → ACCESSIBILITY
     * 降级序尝试其余"已就绪"通道（不惰性拉起未启动的通道，避免失败路径被 su 探测拖慢）。
     */
    private synchronized boolean dispatch(Action action) {
        if (mode == InjectMode.NONE) {
            if (!start()) {
                return false;
            }
        }
        // 0. 手动偏好模式优先（通道未启动则惰性 start；失败仍回落自动链）
        InjectMode pref = preferredMode;
        if (pref != null) {
            IInjectChannel prefCh = channelOfMode(pref);
            if (prefCh != null) {
                if (!prefCh.isAvailable()) {
                    safeRun(ch -> ch.start(), prefCh); // 惰性启动（ROOT=拉起注入进程等）
                }
                if (prefCh.isAvailable() && safeRun(action, prefCh)) {
                    if (mode != pref) {
                        mode = pref;
                        Log.i(TAG, "Switched to preferred " + mode + " channel");
                    }
                    return true;
                }
            }
        }
        IInjectChannel[] order = {directRootChannel, rootInjectorChannel,
                shizukuInjectorChannel, accessibilityChannel};
        int currentIdx = -1;
        for (int i = 0; i < order.length; i++) {
            if (channelMatchesMode(order[i])) {
                currentIdx = i;
                break;
            }
        }
        // 1. 当前通道
        if (currentIdx >= 0 && order[currentIdx].isAvailable()
                && safeRun(action, order[currentIdx])) {
            return true;
        }
        // 2. 降级序后续通道
        for (int i = currentIdx + 1; i < order.length; i++) {
            IInjectChannel ch = order[i];
            if (!ch.isAvailable()) {
                continue;
            }
            if (safeRun(action, ch)) {
                mode = modeOfChannel(ch);
                Log.i(TAG, "Switched to " + mode + " channel");
                return true;
            }
        }
        Log.w(TAG, "inject action failed on ALL channels: mode=" + mode
                + " (DIRECT_ROOT avail=" + directRootChannel.isAvailable()
                + ", ROOT avail=" + rootInjectorChannel.isAvailable()
                + ", SHIZUKU avail=" + shizukuInjectorChannel.isAvailable()
                + ", ACCESSIBILITY avail=" + accessibilityChannel.isAvailable() + ")");
        return false;
    }

    private boolean safeRun(Action action, IInjectChannel ch) {
        try {
            return action.run(ch);
        } catch (Throwable t) {
            Log.w(TAG, ch.name() + " 动作执行异常: " + t);
            return false;
        }
    }

    private boolean channelMatchesMode(IInjectChannel ch) {
        return mode == modeOfChannel(ch);
    }

    private InjectMode modeOfChannel(IInjectChannel ch) {
        if (ch == directRootChannel) {
            return InjectMode.DIRECT_ROOT;
        }
        if (ch == rootInjectorChannel) {
            return InjectMode.ROOT;
        }
        if (ch == shizukuInjectorChannel) {
            return InjectMode.SHIZUKU;
        }
        if (ch == accessibilityChannel) {
            return InjectMode.ACCESSIBILITY;
        }
        return InjectMode.NONE;
    }
}
