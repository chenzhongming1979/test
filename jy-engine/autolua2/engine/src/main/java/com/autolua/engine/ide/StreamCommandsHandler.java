package com.autolua.engine.ide;

import com.autolua.engine.display.stream.ScreenStreamController;

import org.json.JSONObject;

/**
 * type=stream_start / stream_stop：投屏会话控制（方案八 9.3/9.8）。
 * <p>
 * stream_start {@code {codec:"h264"|"jpeg", fps, bitrate, quality, scale}} →
 * 调 {@link ScreenStreamController#startSession}（帧源校验失败/参数非法抛错，
 * 路由器转 error 回包），成功回
 * {@code {"type":"stream_started","data":{"w":真实宽,"h":真实高}}}；
 * 后续画面经 binary 帧通道（帧格式见 WsProtocol 注释），旋转推
 * {@code stream_rotate} 事件。
 * <p>
 * stream_stop {@code {}} → 释放编码器结束会话，回默认 result。
 */
public class StreamCommandsHandler {

    public void register(IdeMessageRouter router) {
        router.register(WsProtocol.CMD_STREAM_START, new IdeCommandHandler() {
            @Override
            public String handle(JSONObject data, String clientId) throws Exception {
                return ScreenStreamController.getInstance().startSession(clientId, data);
            }

            @Override
            public String responseType() {
                return WsProtocol.MSG_STREAM_STARTED;
            }
        });
        router.register(WsProtocol.CMD_STREAM_STOP, new IdeCommandHandler() {
            @Override
            public String handle(JSONObject data, String clientId) throws Exception {
                ScreenStreamController.getInstance().stopSession(clientId);
                return "{\"stopped\":true}";
            }
        });
        // stream_sync：PC 端解码器重建后请求立即出 IDR（无 h264 会话回 requested:false，
        // 专属回包类型 stream_sync_result —— PC 端据此自动重启投屏会话自救）
        router.register(WsProtocol.CMD_STREAM_SYNC, new IdeCommandHandler() {
            @Override
            public String handle(JSONObject data, String clientId) throws Exception {
                boolean ok = ScreenStreamController.getInstance().requestKeyFrame();
                return "{\"requested\":" + ok + "}";
            }

            @Override
            public String responseType() {
                return WsProtocol.MSG_STREAM_SYNC_RESULT;
            }
        });
    }
}
