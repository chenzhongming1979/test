package com.autolua.engine.ide;

import org.json.JSONObject;

/**
 * type=execute：通过 {@link EngineExecutor} 沙箱执行 Lua 代码。
 * <p>
 * data：{@code {code}}；
 * 结果回 {@code {"type":"result","data":<_WS_RESULT>}}（_WS_RESULT 为 nil 时回不带 data 的 result）；
 * 失败回 {@code {"type":"error","data":{"msg":"__ERROR:..."}}}。
 */
public class ExecuteHandler implements IdeCommandHandler {

    @Override
    public String handle(JSONObject data, String clientId) throws Exception {
        EngineExecutor engine = EngineExecutorHolder.get();
        if (engine == null || !engine.isEngineAttached()) {
            throw new IllegalStateException("engine not attached");
        }
        String code = data.optString("code", "");
        if (code.isEmpty()) {
            throw new IllegalArgumentException("code is empty");
        }
        try {
            String result = engine.execute(code);
            // 沙箱约定：执行失败时 _WS_RESULT 以 __ERROR: 开头，转为 error 回包
            if (result != null && result.startsWith(WsProtocol.ERROR_PREFIX)) {
                throw new IllegalStateException(result);
            }
            // result 可能为 null（脚本未写 _WS_RESULT）→ 回 {"type":"result"} 不带 data
            return result;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage();
            throw new IllegalStateException(WsProtocol.ERROR_PREFIX
                    + (msg != null ? msg : e.getClass().getSimpleName()));
        }
    }

    /** 单命令注册到路由器 */
    public void register(IdeMessageRouter router) {
        router.register(WsProtocol.CMD_EXECUTE, this);
    }
}
