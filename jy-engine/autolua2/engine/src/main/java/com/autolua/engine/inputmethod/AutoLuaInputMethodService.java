package com.autolua.engine.inputmethod;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import androidx.core.content.ContextCompat;

/**
 * 模拟输入法通道（方案五，对照 paidaxing FireflyInputMethodService）：
 * 自研 IME 注册动态 receiver，接收同包广播后经 {@link InputConnection} 写入文本/键码、
 * 删除文本、收起键盘——免 root、免无障碍的文本注入通道，适用于密码框、游戏聊天框等
 * 无障碍 PASTE 失效场景。
 * <p>
 * receiver 在 {@link #onCreate()} 注册（早于 {@link #onCreateInputView()}，确保切默认 IME
 * 后系统拉起服务即可收到广播）；广播由 {@link ImeApi} 以 setPackage 自定向发送，
 * 同包广播不受 RECEIVER_NOT_EXPORTED 限制。
 */
public class AutoLuaInputMethodService extends InputMethodService {

    private static final String TAG = "AutoLuaIME";

    /** 广播动作：注入文本（extras.text） */
    public static final String ACTION_INPUT_TEXT = "com.autolua.action.INPUT_TEXT";
    /** 广播动作：注入键码（extras.code，经 VIRTUAL_KEYBOARD 设备构造 KeyEvent down+up） */
    public static final String ACTION_INPUT_KEYCODE = "com.autolua.action.INPUT_KEYCODE";
    /** 广播动作：清空输入框（extras.count 指定删除字符数，缺省时删除光标前 1024 字符） */
    public static final String ACTION_CLEAR_TEXT = "com.autolua.action.CLEAR_TEXT";
    /** 广播动作：收起 IME 键盘 */
    public static final String ACTION_HIDE_IME = "com.autolua.action.HIDE_IME";
    /** 广播动作：弹出 IME 键盘（requestShowSelf，无需输入焦点连接） */
    public static final String ACTION_SHOW_IME = "com.autolua.action.SHOW_IME";
    /** 广播动作：弹出系统输入法选择器（宿主进程调 InputMethodManager.showInputMethodPicker） */
    public static final String ACTION_SHOW_PICKER = "com.autolua.action.SHOW_PICKER";

    /** extras 键：待注入文本 */
    public static final String EXTRA_TEXT = "text";
    /** extras 键：待注入键码 */
    public static final String EXTRA_KEYCODE = "code";
    /** extras 键：待删除字符数 */
    public static final String EXTRA_COUNT = "count";

    /** 当前在线的 IME 服务实例（供 ImeApi 切换默认输入法后等待 receiver 就绪） */
    private static volatile AutoLuaInputMethodService sInstance;

    /** 动作广播 receiver（onCreate 注册，onDestroy 注销） */
    private BroadcastReceiver mReceiver;

    /** 获取当前在线的 IME 服务实例；未拉起时返回 null */
    public static AutoLuaInputMethodService getInstance() {
        return sInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        registerActionsReceiver();
    }

    /**
     * 注册动作广播 receiver。
     * <ul>
     *     <li>API 33+：{@link ContextCompat#registerReceiver} 四参重载，显式声明
     *         RECEIVER_NOT_EXPORTED（值 4）——targetSdk 34 强制要求指定导出状态，
     *         同包（自定向 setPackage）广播仍可送达；</li>
     *     <li>API 26~32：三参重载，flags 传 0（低版本平台不支持 NOT_EXPORTED 语义，
     *         传未知 flags 位有 IllegalArgumentException 风险）；</li>
     *     <li>API 22~25：仅存在两参重载，直接注册。</li>
     * </ul>
     */
    private void registerActionsReceiver() {
        if (mReceiver != null) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_INPUT_TEXT);
        filter.addAction(ACTION_INPUT_KEYCODE);
        filter.addAction(ACTION_CLEAR_TEXT);
        filter.addAction(ACTION_HIDE_IME);
        filter.addAction(ACTION_SHOW_IME);
        filter.addAction(ACTION_SHOW_PICKER);

        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleAction(intent);
            }
        };

        // receiver 需接收 root 引擎进程（uid 0，经 `am broadcast` shell 通道）发来的动作，
        // 必须声明 EXPORTED；动作仅注入文本/键码/键盘显隐，无敏感数据，风险可控
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.registerReceiver(this, mReceiver, filter,
                    ContextCompat.RECEIVER_EXPORTED);
        } else if (Build.VERSION.SDK_INT >= 26) {
            registerReceiver(mReceiver, filter, 0);
        } else {
            registerReceiver(mReceiver, filter);
        }
    }

    /** 按动作分发到 InputConnection（无焦点连接时丢弃并告警） */
    private void handleAction(Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        // SHOW 动作不依赖 InputConnection（键盘弹出前通常无焦点连接），前置处理
        if (ACTION_SHOW_IME.equals(action)) {
            requestShowSelf(0);
            return;
        }
        // 系统输入法选择器由宿主进程（本服务在线时）调 IMM 弹出
        if (ACTION_SHOW_PICKER.equals(action)) {
            try {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showInputMethodPicker();
                }
            } catch (Throwable t) {
                Log.w(TAG, "showInputMethodPicker failed: " + t);
            }
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            Log.w(TAG, "无可用 InputConnection（输入框未获得焦点），丢弃动作: " + action);
            return;
        }
        switch (action) {
            case ACTION_INPUT_TEXT: {
                String text = intent.getStringExtra(EXTRA_TEXT);
                if (text != null) {
                    ic.commitText(text, 1);
                }
                break;
            }
            case ACTION_INPUT_KEYCODE: {
                int code = intent.getIntExtra(EXTRA_KEYCODE, -1);
                if (code != -1) {
                    sendKeycode(ic, code);
                }
                break;
            }
            case ACTION_CLEAR_TEXT: {
                int count = intent.getIntExtra(EXTRA_COUNT, 0);
                if (count <= 0) {
                    // 未指定删除长度时兜底：删除光标前 1024 字符（对照参考实现）
                    CharSequence before = ic.getTextBeforeCursor(1024, 0);
                    count = before == null ? 0 : before.length();
                }
                if (count > 0) {
                    ic.deleteSurroundingText(count, 0);
                }
                break;
            }
            case ACTION_HIDE_IME:
                requestHideSelf(0);
                break;
            default:
                break;
        }
    }

    /** 经 InputConnection 发送键事件：VIRTUAL_KEYBOARD 设备构造 down+up 两个 KeyEvent */
    private static void sendKeycode(InputConnection ic, int keyCode) {
        long now = SystemClock.uptimeMillis();
        int flags = KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE;
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD);
        KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD);
        ic.sendKeyEvent(down);
        ic.sendKeyEvent(up);
    }

    @Override
    public void onDestroy() {
        if (mReceiver != null) {
            try {
                unregisterReceiver(mReceiver);
            } catch (Throwable ignored) {
                // receiver 已被注销/上下文异常时忽略，保证 super.onDestroy 正常执行
            }
            mReceiver = null;
        }
        sInstance = null;
        super.onDestroy();
    }
}
