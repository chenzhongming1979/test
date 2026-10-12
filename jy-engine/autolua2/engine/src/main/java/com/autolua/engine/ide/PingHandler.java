package com.autolua.engine.ide;

import org.json.JSONObject;

/**
 * type=ping：心跳探测，回 {@code {"type":"pong"}}。
 */
public class PingHandler implements IdeCommandHandler {

    public void register(IdeMessageRouter router) {
        router.register(WsProtocol.CMD_PING, this);
    }

    @Override
    public String handle(JSONObject data, String clientId) {
        return null;
    }

    /** 回包 type 固定为 pong */
    @Override
    public String responseType() {
        return WsProtocol.MSG_PONG;
    }

    /** handle 返回 null 时仍回空包（即 {"type":"pong"}） */
    @Override
    public boolean replyOnNull() {
        return true;
    }
}
