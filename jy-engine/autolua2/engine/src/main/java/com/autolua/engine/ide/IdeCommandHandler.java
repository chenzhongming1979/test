package com.autolua.engine.ide;

import org.json.JSONObject;

/**
 * IDE 命令处理器接口（函数式接口，方法引用可直接作为 handler 注册）。
 * <p>
 * 由 {@link IdeMessageRouter} 按 type 分发调用；调用发生在服务器消息处理线程池中，允许阻塞。
 */
public interface IdeCommandHandler {

    /**
     * 处理一条命令。
     *
     * @param data     消息的 data 字段（永不为 null；消息缺省时为空对象）
     * @param clientId 来源连接 ID（连接已断开时可能为 null，此时回包静默丢弃）
     * @return 回包 data 的 JSON 文本；返回 null 表示本条不携带 data
     *         （是否仍回空包由 {@link #replyOnNull()} 决定）
     * @throws Exception 处理失败；路由器会转成
     *                   {@code {"type":"error","data":{"msg":<getMessage()>}}} 回包
     */
    String handle(JSONObject data, String clientId) throws Exception;

    /** 成功回包的消息 type，默认 result（如 PingHandler 覆写为 pong） */
    default String responseType() {
        return WsProtocol.MSG_RESULT;
    }

    /**
     * {@code handle} 返回 null 时是否仍回一个不带 data 的空包。
     * 默认 true（execute/ping 等需要回执）；逐文件处理、异步回包类命令覆写为 false（静默）。
     */
    default boolean replyOnNull() {
        return true;
    }
}
