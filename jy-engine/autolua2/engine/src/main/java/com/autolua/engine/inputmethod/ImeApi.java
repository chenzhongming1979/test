package com.autolua.engine.inputmethod;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.io.InputStream;

/**
 * 模拟输入法通道对外门面（方案五，对照参考实现 :95-115 的抢默认输入法逻辑）：
 * <ul>
 *     <li>查询/切换/恢复系统默认输入法（{@link Settings.Secure#DEFAULT_INPUT_METHOD}）；</li>
 *     <li>经同包自定向广播驱动 {@link AutoLuaInputMethodService} 完成文本/键码注入。</li>
 * </ul>
 * 切换默认输入法的权限边界：
 * <ol>
 *     <li>普通应用无法写 Settings.Secure，需要 WRITE_SECURE_SETTINGS（root 给 app 授权、
 *         或 adb pm grant 场景）——直接 {@link Settings.Secure#putString}；</li>
 *     <li>设备已 root 时经 {@code su -c settings put secure ...} shell 兜底；</li>
 *     <li>两者皆不可用时返回 false，需引导用户到系统设置手动启用 AutoLua IME。</li>
 * </ol>
 * 所有方法可能阻塞（root 探测、等待 IME 服务被系统拉起），请勿在主线程调用。
 */
public final class ImeApi {

    private static final String TAG = "ImeApi";

    /** sendText 等动作触发自动切换后，记录的原默认输入法（"pkg/cls"），供恢复 */
    private static volatile String sPreviousIme;

    private ImeApi() {
    }

    // ------------------------------------------------------------------
    // 状态查询
    // ------------------------------------------------------------------

    /** 读取当前系统默认输入法（"包名/服务全名"），读取失败返回 null */
    public static String getCurrentDefaultIme(Context context) {
        try {
            return Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD);
        } catch (Throwable t) {
            Log.w(TAG, "读取默认输入法失败: " + t);
            return null;
        }
    }

    /** 当前默认输入法是否为本应用的 IME（按包名匹配，兼容宿主多进程 applicationId） */
    public static boolean isDefaultIme(Context context) {
        String current = getCurrentDefaultIme(context);
        return current != null && current.contains(context.getPackageName());
    }

    // ------------------------------------------------------------------
    // 切换 / 恢复
    // ------------------------------------------------------------------

    /**
     * 将默认输入法切换为本 IME（"包名/服务全名"）。
     * 首次切换前记录原输入法（供 {@link #restoreIme(Context, String)} 恢复）。
     *
     * @return 切换并回读验证成功返回 true；无 root 且无 WRITE_SECURE_SETTINGS 返回 false
     */
    public static boolean switchToThisIme(Context context) {
        String imeId = context.getPackageName()
                + "/" + AutoLuaInputMethodService.class.getName();
        String current = getCurrentDefaultIme(context);
        if (current != null && !current.equals(imeId)
                && (sPreviousIme == null || sPreviousIme.isEmpty())) {
            sPreviousIme = current;
        }
        return writeDefaultIme(context, imeId);
    }

    /** 恢复指定输入法为系统默认 */
    public static boolean restoreIme(Context context, String previous) {
        if (previous == null || previous.isEmpty()) {
            Log.w(TAG, "无原输入法记录可恢复");
            return false;
        }
        boolean ok = writeDefaultIme(context, previous);
        if (ok) {
            sPreviousIme = null;
        }
        return ok;
    }

    /** 恢复内部记录的原输入法（自动切换模式使用） */
    public static boolean restoreIme(Context context) {
        return restoreIme(context, sPreviousIme);
    }

    /**
     * 写默认输入法：优先直接写 Settings.Secure（app 已被授予 WRITE_SECURE_SETTINGS 时可用），
     * 失败走 root shell 兜底，最终回读验证。
     */
    private static boolean writeDefaultIme(Context context, String imeId) {
        // 1) 直接写 Secure 表（需 WRITE_SECURE_SETTINGS 权限）
        try {
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD, imeId);
            if (imeId.equals(getCurrentDefaultIme(context))) {
                return true;
            }
        } catch (Throwable t) {
            Log.i(TAG, "直接写入默认输入法失败（无 WRITE_SECURE_SETTINGS 时预期）: " + t);
        }
        // 2) root shell 兜底：settings put secure default_input_method <pkg/cls>
        if (suExec("settings put secure default_input_method " + imeId)
                && imeId.equals(getCurrentDefaultIme(context))) {
            return true;
        }
        Log.w(TAG, "切换默认输入法失败（无 root），请引导用户在系统设置中手动启用 AutoLua IME");
        return false;
    }

    /** root 执行单条命令：su -c <cmd>，吸收输出防管道阻塞，退出码 0 视为成功 */
    private static boolean suExec(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            InputStream in = p.getInputStream();
            byte[] buf = new byte[256];
            while (in.read(buf) >= 0) {
                // 丢弃标准输出直至流结束
            }
            InputStream err = p.getErrorStream();
            while (err.read(buf) >= 0) {
                // 丢弃标准错误直至流结束
            }
            return p.waitFor() == 0;
        } catch (Throwable t) {
            Log.w(TAG, "su 执行失败: " + cmd + " -> " + t);
            return false;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    // ------------------------------------------------------------------
    // 注入动作（广播驱动）
    // ------------------------------------------------------------------

    /**
     * 注入文本：已是默认 IME 时直接广播；否则尝试 root 切换（内部记录原 IME）后广播。
     * 切换后系统拉起 IME 需要输入焦点配合，无焦点场景广播会被 receiver 丢弃（告警日志）。
     *
     * @return 广播已发出返回 true（不代表注入成功）；无法建立通道返回 false
     */
    public static boolean sendText(Context context, String text) {
        if (text == null || !ensureChannel(context)) {
            return false;
        }
        Intent intent = baseIntent(context, AutoLuaInputMethodService.ACTION_INPUT_TEXT);
        intent.putExtra(AutoLuaInputMethodService.EXTRA_TEXT, text);
        context.sendBroadcast(intent);
        return true;
    }

    /** 注入键码（KeyEvent down+up，VIRTUAL_KEYBOARD 设备） */
    public static boolean sendKey(Context context, int keycode) {
        if (!ensureChannel(context)) {
            return false;
        }
        Intent intent = baseIntent(context, AutoLuaInputMethodService.ACTION_INPUT_KEYCODE);
        intent.putExtra(AutoLuaInputMethodService.EXTRA_KEYCODE, keycode);
        context.sendBroadcast(intent);
        return true;
    }

    /**
     * 清空输入框：删除光标前 count 个字符；count <= 0 时由 receiver 兜底删除光标前 1024 字符。
     */
    public static boolean clearText(Context context, int count) {
        if (!ensureChannel(context)) {
            return false;
        }
        Intent intent = baseIntent(context, AutoLuaInputMethodService.ACTION_CLEAR_TEXT);
        if (count > 0) {
            intent.putExtra(AutoLuaInputMethodService.EXTRA_COUNT, count);
        }
        context.sendBroadcast(intent);
        return true;
    }

    /** 收起 IME 键盘（不要求本 IME 为默认输入法；receiver 未在线时广播静默丢弃） */
    public static void hide(Context context) {
        context.sendBroadcast(baseIntent(context, AutoLuaInputMethodService.ACTION_HIDE_IME));
    }

    /** 构造自定向广播：setPackage 本包，同包广播不受 RECEIVER_NOT_EXPORTED 限制 */
    private static Intent baseIntent(Context context, String action) {
        Intent intent = new Intent(action);
        intent.setPackage(context.getPackageName());
        return intent;
    }

    /**
     * 确保注入通道就绪：本 IME 已是默认输入法直接通过；否则尝试 root 切换，
     * 并短暂等待系统把 IME 服务拉起（receiver 注册完成），避免广播早于注册而丢失。
     */
    private static boolean ensureChannel(Context context) {
        if (isDefaultIme(context)) {
            return true;
        }
        if (!switchToThisIme(context)) {
            return false;
        }
        // 切换默认输入法后系统会解绑旧 IME 并拉起本 IME；最多等待 2s（100ms x 20）
        for (int i = 0; i < 20; i++) {
            if (AutoLuaInputMethodService.getInstance() != null) {
                return true;
            }
            SystemClock.sleep(100);
        }
        Log.w(TAG, "等待 AutoLua IME 服务就绪超时（可能无输入焦点，注入可能无效）");
        return true;
    }
}
