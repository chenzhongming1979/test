package com.autolua.engine.ide;

import org.json.JSONObject;

import java.util.function.Supplier;

/**
 * type=ui_debug_start / ui_debug_stop：UI 热重载触发（9.11 调试通道整合）。
 * <p>
 * 老通道（8176 MLN Kit）独有能力迁移：PC 端经新 WS 命令远程拉起/关闭
 * DebugActivity，实际动作经 {@link UiDebugDelegate} 由 app 层执行。
 * <p>
 * 成功回 {@code {"type":"ui_debug_result","data":{"ok":true,"action":"started"/"stopped"}}}；
 * 未接线（delegate 为 null）回 error "ui debug delegate not attached"。
 */
public class UiDebugHandler {

    /** UI 热重载委托供应器（app 层接线；未接线时回 error） */
    private final Supplier<UiDebugDelegate> delegateSupplier;

    public UiDebugHandler(Supplier<UiDebugDelegate> delegateSupplier) {
        this.delegateSupplier = delegateSupplier;
    }

    public void register(IdeMessageRouter router) {
        // ui_debug_start：拉起 DebugActivity
        router.register(WsProtocol.CMD_UI_DEBUG_START, new IdeCommandHandler() {
            @Override
            public String handle(JSONObject data, String clientId) throws Exception {
                UiDebugDelegate delegate = requireDelegate();
                delegate.start();
                return payload("started");
            }

            @Override
            public String responseType() {
                return WsProtocol.MSG_UI_DEBUG_RESULT;
            }
        });
        // ui_debug_stop：广播关闭 DebugActivity
        router.register(WsProtocol.CMD_UI_DEBUG_STOP, new IdeCommandHandler() {
            @Override
            public String handle(JSONObject data, String clientId) throws Exception {
                UiDebugDelegate delegate = requireDelegate();
                delegate.stop();
                return payload("stopped");
            }

            @Override
            public String responseType() {
                return WsProtocol.MSG_UI_DEBUG_RESULT;
            }
        });
    }

    /** 取委托；未接线抛错（路由器转 error 回包） */
    private UiDebugDelegate requireDelegate() {
        UiDebugDelegate delegate = delegateSupplier.get();
        if (delegate == null) {
            throw new IllegalStateException("ui debug delegate not attached");
        }
        return delegate;
    }

    /** 回包 data：{"ok":true,"action":<started|stopped>}（action 为固定字面量，手拼避免受检异常） */
    private static String payload(String action) {
        return "{\"ok\":true,\"action\":\"" + action + "\"}";
    }
}
