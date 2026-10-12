package com.autolua.engine.ide;

import android.content.Context;
import android.os.SystemClock;

import com.autolua.engine.input.inject.InjectShellClient;
import com.autolua.engine.inputmethod.ImeApi;

import org.json.JSONObject;

import java.util.function.Supplier;

/**
 * type=inject_touch / inject_swipe / inject_key / inject_text：远程操控命令（方案八 9.4）。
 * <p>
 * 实现通道：
 * <ul>
 *     <li>touch/swipe → {@link InjectShellClient} 四通道降级链（第一次 inject_* 时
 *         懒 start，见 dispatch 的 mode==NONE 分支）；坐标为设备屏幕绝对坐标（不缩放）；</li>
 *     <li>key → 优先 {@link InjectShellClient}（InputManager 反射注入 KeyEvent，
 *         同 input keyevent），失败降级 {@link ImeApi}（模拟输入法通道）；
 *     <li>text → {@link ImeApi}（模拟输入法通道：root 切默认 IME 后广播驱动）。</li>
 * </ul>
 * 统一回包 {@code {"type":"inject_result","data":{"ok":bool}}}；
 * handler 抛错（如 invalid action）由路由器转 error 回包。
 * <p>
 * ImeApi 可能阻塞（root 探测 + 等待 IME 服务拉起，最长 ~2s），
 * 消息处理线程池允许阻塞，心跳走独立线程不受影响。
 */
public class InjectCommandsHandler {

    private static final String TAG = "AutoLuaIde";

    /** 应用上下文供应器（ImeApi 广播与 root 切 IME 需要；engine 无 app context） */
    private final Supplier<Context> contextSupplier;

    /**
     * 当前触点 downTime（inject_down 时建立，up 后清零；单触点一期足够）。
     * move/up 沿用同一 downTime 保证系统侧手势时间轴一致；
     * 未 down 先收到 move/up 时取当前时间兜底（系统侧状态机容错）。
     */
    private long touchDownTime;

    public InjectCommandsHandler(Supplier<Context> contextSupplier) {
        this.contextSupplier = contextSupplier;
    }

    public void register(IdeMessageRouter router) {
        router.register(WsProtocol.CMD_INJECT_TOUCH, new IdeCommandHandler() {
            @Override
            public String handle(JSONObject data, String clientId) throws Exception {
                return touch(data);
            }

            @Override
            public String responseType() {
                return WsProtocol.MSG_INJECT_RESULT;
            }
        });
        router.register(WsProtocol.CMD_INJECT_SWIPE, new IdeCommandHandler() {
            @Override
            public String handle(JSONObject data, String clientId) throws Exception {
                return swipe(data);
            }

            @Override
            public String responseType() {
                return WsProtocol.MSG_INJECT_RESULT;
            }
        });
        router.register(WsProtocol.CMD_INJECT_KEY, new IdeCommandHandler() {
            @Override
            public String handle(JSONObject data, String clientId) throws Exception {
                return key(data);
            }

            @Override
            public String responseType() {
                return WsProtocol.MSG_INJECT_RESULT;
            }
        });
        router.register(WsProtocol.CMD_INJECT_TEXT, new IdeCommandHandler() {
            @Override
            public String handle(JSONObject data, String clientId) throws Exception {
                return text(data);
            }

            @Override
            public String responseType() {
                return WsProtocol.MSG_INJECT_RESULT;
            }
        });
    }

    // ------------------------------------------------------------------
    // 各命令实现（返回回包 data JSON）
    // ------------------------------------------------------------------

    /**
     * inject_touch：{action:"down"/"move"/"up", x, y}。
     * synchronized：touchDownTime 为跨消息状态（消息线程池 2 线程），防 down/up 交叉。
     */
    private synchronized String touch(JSONObject data) throws Exception {
        InjectShellClient client = InjectShellClient.getInstance();
        if (!client.isStarted() && !client.start()) {
            return "{\"ok\":false,\"msg\":\"no inject channel available\"}";
        }
        String action = data.optString("action", "");
        float x = (float) data.optDouble("x", 0);
        float y = (float) data.optDouble("y", 0);
        boolean ok;
        switch (action) {
            case "down":
                touchDownTime = SystemClock.uptimeMillis();
                ok = client.injectDown(0, x, y, touchDownTime);
                break;
            case "move":
                ok = client.injectMove(0, x, y, touchDownTime > 0 ? touchDownTime : SystemClock.uptimeMillis());
                break;
            case "up":
                ok = client.injectUp(0, x, y, touchDownTime > 0 ? touchDownTime : SystemClock.uptimeMillis());
                touchDownTime = 0;
                break;
            default:
                throw new IllegalArgumentException("invalid action: " + action + " (down/move/up)");
        }
        return ok ? "{\"ok\":true}"
                : "{\"ok\":false,\"msg\":\"" + action + " failed (mode=" + client.getMode() + ", see logcat)\"}";
    }

    /** inject_swipe：{x1,y1,x2,y2,duration?}（Humanizer 轨迹已内置） */
    private String swipe(JSONObject data) throws Exception {
        InjectShellClient client = InjectShellClient.getInstance();
        if (!client.isStarted() && !client.start()) {
            return "{\"ok\":false,\"msg\":\"no inject channel available\"}";
        }
        float x1 = (float) data.optDouble("x1", 0);
        float y1 = (float) data.optDouble("y1", 0);
        float x2 = (float) data.optDouble("x2", 0);
        float y2 = (float) data.optDouble("y2", 0);
        int duration = data.optInt("duration", 0);
        boolean ok = client.swipe(x1, y1, x2, y2, duration > 0 ? duration : 300);
        return ok ? "{\"ok\":true}"
                : "{\"ok\":false,\"msg\":\"swipe failed (mode=" + client.getMode() + ", see logcat)\"}";
    }

    /**
     * inject_key：{keycode}。
     * 优先 root 注入通道（InputManager 反射注入 KeyEvent，同 input keyevent，
     * 秒回不阻塞）；失败再走 ImeApi（模拟输入法通道，需切默认 IME，可能阻塞 ~2s）。
     */
    private String key(JSONObject data) throws Exception {
        int keycode = data.optInt("keycode", -1);
        if (keycode < 0) {
            throw new IllegalArgumentException("keycode required");
        }
        // root 通道：touch 已就绪时零开销直通；未启动时懒 start（仅一次 su 探测）
        InjectShellClient client = InjectShellClient.getInstance();
        if (client.key(keycode)) {
            return "{\"ok\":true}";
        }
        // IME 通道兜底（无 root 设备）
        Context ctx = contextSupplier.get();
        if (ctx == null) {
            throw new IllegalStateException("app context not attached");
        }
        if (!ImeApi.sendKey(ctx, keycode)) {
            String ime = ImeApi.getCurrentDefaultIme(ctx);
            return "{\"ok\":false,\"msg\":\"root inject failed (mode=" + client.getMode()
                    + ", see logcat) and IME channel failed (defaultIme="
                    + (ime == null ? "null" : ime) + ")\"}";
        }
        return "{\"ok\":true}";
    }

    /** inject_text：{text}（ImeApi sendText，root 切默认输入法后广播） */
    private String text(JSONObject data) throws Exception {
        Context ctx = contextSupplier.get();
        if (ctx == null) {
            throw new IllegalStateException("app context not attached");
        }
        String text = data.optString("text", null);
        boolean ok = ImeApi.sendText(ctx, text);
        return "{\"ok\":" + ok + "}";
    }
}
