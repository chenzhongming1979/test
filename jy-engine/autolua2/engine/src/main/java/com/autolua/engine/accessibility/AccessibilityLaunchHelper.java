package com.autolua.engine.accessibility;

import android.content.Context;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 无障碍自拉活调度器（方案八 9.6 双保险第一重）。
 * <p>
 * 背景：WS 服务器随 app 进程同生死——app 被系统杀/崩溃后，system_server 会自动重建
 * 已启用的无障碍服务进程（顺带复活全 app），这是免 root 场景唯一的自恢复入口。
 * engine 模块不依赖 app 模块，故以委托注入模式接线：
 * <ul>
 *     <li>engine 侧：{@link AutoLuaAccessibilityService#onServiceConnected()} 调
 *         {@link #dispatch(Context)}；</li>
 *     <li>app 侧：MainApp 注入 {@link Delegate}（KeepAliveManager.start 拉保活体系 +
 *         IdeServerManager.start 重建 IDE WebSocket 服务器）。</li>
 * </ul>
 * 安全约束：dispatch 后台线程执行且 try-catch(Throwable) 全包裹——
 * 任何拉起逻辑异常都不得影响无障碍服务与系统 binder 的绑定。
 */
public final class AccessibilityLaunchHelper {

    private static final String TAG = "AccessibilityLaunch";

    /** 自拉活委托（app 层注入；engine 无 KeepAliveManager/IdeServerManager 启动入口） */
    public interface Delegate {
        /**
         * 无障碍服务已连接，执行自拉活（后台线程调用）。
         * 实现须自行容错（建议每项动作单独 try-catch），不得抛出异常。
         */
        void onLaunch(Context context);
    }

    private static volatile Delegate sDelegate;
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    /** 单后台线程：binder 线程只投递任务，不执行拉起逻辑 */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AutoLuaAccLaunch");
        t.setDaemon(true);
        return t;
    });

    private AccessibilityLaunchHelper() {
    }

    /** 注入自拉活委托（MainApp onCreate 时调用；传 null 清除） */
    public static void setDelegate(Delegate delegate) {
        sDelegate = delegate;
    }

    /**
     * 无障碍服务连接后的自拉活入口：post 后台线程执行委托，Throwable 全包裹。
     * 首次连接只执行一次（系统重绑会多次回调 onServiceConnected，重复 start 均幂等，
     * 但减少无谓调度）；后续强制重拉可再注入 delegate 后无感知。
     */
    public static void dispatch(Context context) {
        final Delegate d = sDelegate;
        final Context app = context != null ? context.getApplicationContext() : null;
        if (d == null || app == null) {
            return;
        }
        // 首次连接只调度一次；重复 onServiceConnected（系统重绑）时幂等跳过
        if (!STARTED.compareAndSet(false, true)) {
            Log.d(TAG, "accessibility launch already dispatched, skip");
            return;
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    d.onLaunch(app);
                    Log.i(TAG, "accessibility self-launch done");
                } catch (Throwable t) {
                    // 无障碍自拉活绝不能影响系统绑定
                    Log.w(TAG, "accessibility self-launch failed: " + t);
                }
            }
        });
    }
}
