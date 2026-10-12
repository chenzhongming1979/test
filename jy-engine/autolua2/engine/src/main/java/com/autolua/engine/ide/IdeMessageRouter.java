package com.autolua.engine.ide;

import android.os.SystemClock;
import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * IDE 消息路由器：解析 {@code {"type","data"}} 并分发到注册的 {@link IdeCommandHandler}。
 * <p>
 * 解析使用 engine 已有依赖 gson（容错）：畸形 JSON 回 error 包但不断连。
 * {@link #dispatch} 由 {@link IdeWebSocketServer} 在消息处理线程池中调用，允许阻塞。
 */
public class IdeMessageRouter {

    private static final String TAG = "AutoLuaIde";

    /** 命令注册表：type → handler */
    private final Map<String, IdeCommandHandler> handlers = new LinkedHashMap<>();

    /** 回包发送通道：(clientId, message) */
    private final BiConsumer<String, String> sender;

    /**
     * @param sender 回包发送通道，通常为 {@code server::sendToClient}
     */
    public IdeMessageRouter(BiConsumer<String, String> sender) {
        this.sender = sender;
    }

    /** 注册命令处理器（同名 type 覆盖） */
    public void register(String type, IdeCommandHandler handler) {
        handlers.put(type, handler);
        Log.d(TAG, "register ide command: " + type + " -> " + handler.getClass().getSimpleName());
    }

    /** 已注册命令 type 快照（注册顺序，供 capabilities 能力协商） */
    public synchronized List<String> registeredTypes() {
        return new ArrayList<>(handlers.keySet());
    }

    /**
     * 分发一条文本消息。
     * 任何异常（含畸形 JSON / 未知 type / handler 抛错）都转为
     * {@code {"type":"error","data":{"msg":...}}} 回包，不断开连接。
     *
     * @param text     原始消息文本
     * @param clientId 来源连接 ID
     */
    public void dispatch(String text, String clientId) {
        long start = SystemClock.elapsedRealtime();
        try {
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) {
                sendError(clientId, "invalid message: not a json object");
                return;
            }
            JsonObject obj = root.getAsJsonObject();
            String type = obj.has("type") && obj.get("type").isJsonPrimitive()
                    ? obj.get("type").getAsString() : "";
            IdeCommandHandler handler = type.isEmpty() ? null : handlers.get(type);
            if (handler == null) {
                // 协议要求：未识别 type 忽略并回 unknown type
                sendError(clientId, "unknown type");
                return;
            }
            JsonElement dataEl = obj.get("data");
            JSONObject data = (dataEl == null || dataEl.isJsonNull())
                    ? new JSONObject() : new JSONObject(dataEl.toString());
            String ret = handler.handle(data, clientId);
            reply(clientId, handler.responseType(), ret, handler.replyOnNull());
        } catch (Exception e) {
            String msg = e.getMessage();
            sendError(clientId, msg != null && !msg.isEmpty() ? msg : "internal error");
            Log.w(TAG, "handle ide command failed: " + e);
        } finally {
            Log.d(TAG, "ide command cost " + (SystemClock.elapsedRealtime() - start)
                    + "ms, client=" + clientId);
        }
    }

    /** 构造成功回包并发送；{@code dataJson==null} 时按 {@code replyOnNull} 决定回空包或静默 */
    private void reply(String clientId, String type, String dataJson, boolean replyOnNull) {
        if (dataJson == null && !replyOnNull) {
            return;
        }
        JsonObject resp = new JsonObject();
        resp.addProperty("type", type);
        if (dataJson != null) {
            // data 尽量原样嵌入（Lua 结果通常是 JSON 文本）；不是合法 JSON 时退化为字符串值
            try {
                resp.add("data", JsonParser.parseString(dataJson));
            } catch (Exception e) {
                resp.addProperty("data", dataJson);
            }
        }
        send(clientId, resp.toString());
    }

    /** 构造 error 回包：{"type":"error","data":{"msg":...}} */
    private void sendError(String clientId, String msg) {
        JsonObject resp = new JsonObject();
        resp.addProperty("type", WsProtocol.MSG_ERROR);
        JsonObject data = new JsonObject();
        data.addProperty("msg", msg);
        resp.add("data", data);
        send(clientId, resp.toString());
    }

    private void send(String clientId, String message) {
        if (sender != null) {
            sender.accept(clientId, message);
        }
    }
}
