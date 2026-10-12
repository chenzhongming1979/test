package com.autolua.engine.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍服务宿主（方案三：obtainXml 整树方案 + 通道 A 无障碍手势）。
 *
 * <p>职责（对照派大星 AutoLuaAccessibilityService.onServiceConnected）：</p>
 * <ul>
 *   <li>连接时重写 serviceInfo：eventTypes=-1、feedbackType=FEEDBACK_GENERIC(16)、
 *       flags |= 0x72、notificationTimeout=100，并保存原始 flags 供还原；</li>
 *   <li>事件白名单：只处理 TYPE_WINDOW_STATE_CHANGED 维护前台包名缓存（300ms TTL），
 *       其余事件一律忽略——杜绝高频事件回调造成 ANR；</li>
 *   <li>按键过滤：onKeyEvent 智能拦截音量三键——仅当「脚本正在消费事件」
 *       （EventBridge 读端心跳 3 秒内）时吞键并转发为 type=key 事件，否则放行；</li>
 *   <li>静态实例引用 + isRunning() + 禁用状态广播（com.autolua.ACCESSIBILITY_DISABLED）。</li>
 * </ul>
 */
public class AutoLuaAccessibilityService extends AccessibilityService {
    private static final String TAG = "AutoLuaAccessibility";

    /** 服务被禁用（用户在系统设置关闭 / 系统解绑 / 进程销毁）时发送的广播 Action */
    public static final String ACTION_ACCESSIBILITY_DISABLED = "com.autolua.ACCESSIBILITY_DISABLED";

    /**
     * obtainXml 整树 dump 跨进程绑定 action（Manifest intent-filter）。
     * 注意：框架 AccessibilityService.onBind 为 final（android-34 验证），
     * IAuxDumpService 由同进程的 {@link AuxDumpService} 承载本 action。
     */
    public static final String ACTION_AUX_DUMP = "com.autolua.engine.AUX_DUMP";

    /** 前台包名缓存 TTL（毫秒）：超时后 getForegroundPackage() 返回 null */
    private static final long FOREGROUND_PACKAGE_TTL_MS = 300;

    /**
     * 连接时基线 flags（对照派大星：flags |= 114 即 0x72）：
     * FLAG_INCLUDE_NOT_IMPORTANT_VIEWS(0x2) | FLAG_REPORT_VIEW_IDS(0x10)
     * | FLAG_REQUEST_FILTER_KEY_EVENTS(0x20) | FLAG_RETRIEVE_INTERACTIVE_WINDOWS(0x40)
     */
    private static final int BASELINE_FLAGS = 0x72;

    /** 静态实例引用 */
    private static volatile AutoLuaAccessibilityService sInstance = null;
    private static volatile boolean sConnected = false;

    /** 连接时保存的原始 flags，供 setNodeMode(false) 还原（对照懒人 SelectToSpeakService.setMode） */
    private int mOriginalFlags = -1;

    /** 前台包名缓存：包名 + 更新时间戳 */
    private static volatile String sForegroundPackage = null;
    private static volatile long sForegroundPackageTime = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        // 预热 obtainXml 服务端实现（AuxDumpServiceLocal 单例）：
        // 同进程直用不经 Binder；跨进程经 AuxDumpService 绑定拿同一逻辑实现的代理
        AuxDumpServiceLocal.get();
        // 事件转发开关 receiver（root 引擎经 am broadcast 置位；本服务导出接收）
        registerEventForwardReceiver();
    }

    /** 注册事件转发开关 receiver（root shell am broadcast 通道；receiver 需导出） */
    private void registerEventForwardReceiver() {
        try {
            android.content.IntentFilter filter =
                    new android.content.IntentFilter(com.autolua.engine.extension.event.EventBridge.ACTION_EVENT_FORWARD);
            android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, android.content.Intent intent) {
                    boolean enable = intent.getIntExtra(
                            com.autolua.engine.extension.event.EventBridge.EXTRA_ENABLE, 0) != 0;
                    com.autolua.engine.extension.event.EventBridge.forwardEnabled = enable;
                    if (enable) {
                        // 开启即截断重置队列并写 reset 标记：读端（脚本侧）不收上一轮残留事件
                        com.autolua.engine.extension.event.EventBridge.onForwardEnabled();
                    }
                    Log.d(TAG, "event forward " + (enable ? "enabled" : "disabled"));
                }
            };
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                androidx.core.content.ContextCompat.registerReceiver(this, receiver, filter,
                        androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
            } else if (android.os.Build.VERSION.SDK_INT >= 26) {
                registerReceiver(receiver, filter, 0);
            } else {
                registerReceiver(receiver, filter);
            }
        } catch (Throwable t) {
            Log.w(TAG, "register event forward receiver failed: " + t);
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            mOriginalFlags = info.flags;
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;          // -1：全部事件类型
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC; // 16
            info.flags = info.flags | BASELINE_FLAGS;                      // |= 0x72
            info.notificationTimeout = 100;
            setServiceInfo(info);
        }
        sInstance = this;
        sConnected = true;
        Log.i(TAG, "accessibility service connected, flags=" + (info != null ? info.flags : -1));
        // 方案八 9.6：无障碍自拉活——app 被杀后 system_server 自动重建本服务进程（复活全 app），
        // 顺带拉起保活体系与 IDE WebSocket 服务器（远程唤醒第一重保险）。
        // try-catch(Throwable) 全包裹且 post 后台线程执行（不阻塞 binder 线程），
        // 任何异常都不得影响无障碍服务与系统的绑定。
        try {
            AccessibilityLaunchHelper.dispatch(this);
        } catch (Throwable t) {
            Log.w(TAG, "accessibility self-launch dispatch failed: " + t);
        }
    }

    /**
     * 动态切换节点获取模式（对照懒人 SelectToSpeakService.setMode）：
     * includeNotImportant=true 时动态追加 FLAG_INCLUDE_NOT_IMPORTANT_VIEWS（整树 dump 更全）；
     * false 时还原为连接时保存的原始 flags。
     */
    public void setNodeMode(boolean includeNotImportant) {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            return;
        }
        if (includeNotImportant) {
            info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        } else if (mOriginalFlags >= 0) {
            info.flags = mOriginalFlags;
        } else {
            info.flags &= ~AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        }
        setServiceInfo(info);
    }

    /**
     * 事件白名单：只处理 TYPE_WINDOW_STATE_CHANGED(32) 更新前台包名缓存，
     * 其余事件一律忽略——避免高频事件回调拖垮主线程造成 ANR。
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 系统事件转发（event.onToast/onNotification/onTouch/onClick；开关关闭时零开销）
            forwardSystemEvent(event);
            return;
        }
        CharSequence pkg = event.getPackageName();
        sForegroundPackage = pkg != null ? pkg.toString() : null;
        sForegroundPackageTime = SystemClock.uptimeMillis();
    }

    /**
     * 按键过滤（FLAG_REQUEST_FILTER_KEY_EVENTS 已由基线 flags 置位）：
     * 仅音量三键（VOLUME_UP/DOWN/MUTE）参与智能拦截——「脚本正在消费事件」
     * （转发开启且 EventBridge 读端心跳在 3 秒内）时返回 true 吞掉并转发为 type=key 事件，
     * 否则一律返回 false 放行，避免无障碍常开导致用户音量键全局失灵。
     * 其余按键不做处理直接放行。
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        try {
            int keyCode = event.getKeyCode();
            if (keyCode != KeyEvent.KEYCODE_VOLUME_UP
                    && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN
                    && keyCode != KeyEvent.KEYCODE_VOLUME_MUTE) {
                return false; // 非音量键一律放行
            }
            if (!com.autolua.engine.extension.event.EventBridge.forwardEnabled
                    || !com.autolua.engine.extension.event.EventBridge.isScriptConsuming()) {
                return false; // 脚本未监听（开关关闭或心跳过期）：放行，不抢用户音量键
            }
            com.autolua.engine.extension.event.EventBridge.writeKey(keyCode, event.getAction(),
                    getForegroundPackage());
            return true; // 吞掉按键（DOWN/UP 连同系统重复键全部拦截）
        } catch (Throwable t) {
            // 按键链路异常绝不影响无障碍主流程
            Log.w(TAG, "onKeyEvent failed", t);
            return false;
        }
    }
    /**
     * 系统事件 → 事件队列（EventBridge）：
     * TYPE_NOTIFICATION_STATE_CHANGED 按 className 细分为 toast/notification；
     * TYPE_VIEW_CLICKED → click；TYPE_TOUCH_INTERACTION_START(API26+) → touch。
     * 高频事件（touch/click）追加进 JSON-lines 队列，读端轮询按 seq 取新事件尽力而为。
     */
    private void forwardSystemEvent(AccessibilityEvent event) {
        if (!com.autolua.engine.extension.event.EventBridge.forwardEnabled) {
            return;
        }
        try {
            int type = event.getEventType();
            String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
            String cls = event.getClassName() != null ? event.getClassName().toString() : "";
            StringBuilder text = new StringBuilder();
            if (event.getText() != null) {
                for (int i = 0; i < event.getText().size(); i++) {
                    CharSequence t = event.getText().get(i);
                    if (t != null && t.length() > 0) {
                        if (text.length() > 0) text.append(" | ");
                        text.append(t);
                    }
                }
            }
            if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                boolean isToast = cls.contains("Toast") || cls.contains("Toast$TN");
                com.autolua.engine.extension.event.EventBridge.writeEvent(
                        isToast ? "toast" : "notification", pkg, cls, text.toString());
            } else if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                com.autolua.engine.extension.event.EventBridge.writeEvent("click", pkg, cls, text.toString());
            } else if (type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
                    && android.os.Build.VERSION.SDK_INT >= 26) {
                com.autolua.engine.extension.event.EventBridge.writeEvent("touch", pkg, cls, text.toString());
            }
        } catch (Throwable t) {
            // 事件转发绝不能影响无障碍主流程，但需留痕便于排障（带异常栈）
            Log.w(TAG, "forwardSystemEvent failed", t);
        }
    }

    /** 读取前台包名缓存；超过 300ms TTL 视为过期返回 null */
    public static String getForegroundPackage() {
        String pkg = sForegroundPackage;
        if (pkg == null) {
            return null;
        }
        if (SystemClock.uptimeMillis() - sForegroundPackageTime > FOREGROUND_PACKAGE_TTL_MS) {
            return null;
        }
        return pkg;
    }

    /** 当前服务实例（未连接时返回 null） */
    public static AutoLuaAccessibilityService getInstance() {
        return sConnected ? sInstance : null;
    }

    /** 服务是否已连接并可用 */
    public static boolean isRunning() {
        return sConnected && sInstance != null;
    }

    @Override
    public void onInterrupt() {
        // 系统中断反馈通道，无需处理
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // 服务被解绑（含用户在设置中关闭）：放行手势队列中的所有等待者
        GestureQueueManager.getInstance().onServiceDisconnected();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sInstance = null;
        sConnected = false;
        GestureQueueManager.getInstance().onServiceDisconnected();
        // 禁用状态广播（服务被用户关闭时通知引擎侧降级/提示）
        try {
            Intent intent = new Intent(ACTION_ACCESSIBILITY_DISABLED);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        } catch (Throwable t) {
            Log.w(TAG, "send disabled broadcast failed", t);
        }
        Log.i(TAG, "accessibility service destroyed");
    }
}