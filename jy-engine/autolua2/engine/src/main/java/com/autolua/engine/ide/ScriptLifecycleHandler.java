package com.autolua.engine.ide;

import org.json.JSONObject;

/**
 * type=stop_script / pause_script / resume_script：脚本生命周期控制。
 * <p>
 * 调 {@link EngineExecutor} 对应方法；引擎未注入时回 error "engine not attached"。
 * 本类不直接实现 {@link IdeCommandHandler}，注册时以 lambda 按命令适配
 * （三个命令共用回包语义：type=result）。
 */
public class ScriptLifecycleHandler {

    public void register(IdeMessageRouter router) {
        router.register(WsProtocol.CMD_STOP_SCRIPT, (data, clientId) -> lifecycle(false, false));
        router.register(WsProtocol.CMD_PAUSE_SCRIPT, (data, clientId) -> lifecycle(true, false));
        router.register(WsProtocol.CMD_RESUME_SCRIPT, (data, clientId) -> lifecycle(false, true));
    }

    private String lifecycle(boolean pause, boolean resume) {
        EngineExecutor engine = EngineExecutorHolder.get();
        if (engine == null || !engine.isEngineAttached()) {
            throw new IllegalStateException("engine not attached");
        }
        if (pause) {
            engine.pause();
        } else if (resume) {
            engine.resume();
        } else {
            engine.stop();
        }
        return "{\"ok\":true}";
    }
}
