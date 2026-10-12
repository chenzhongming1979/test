package com.autolua.engine.ide;

/**
 * IDE 调试 WebSocket 协议常量（方案六一期，对照 docs/features/engine-core/INTEGRATION_PLAN.md 7.3 协议规范）。
 * <p>
 * 消息统一格式：{@code {"type": "<cmd>", "data": {...}}}；
 * 未识别 type 回 {@code {"type":"error","data":{"msg":"unknown type"}}}。
 */
public final class WsProtocol {

    private WsProtocol() {
    }

    // ------------------------------------------------------------------
    // 服务器 → 客户端 消息 type
    // ------------------------------------------------------------------

    /** 连接建立后的握手回包 */
    public static final String MSG_HELLO = "hello";
    /** 命令执行成功回包（默认） */
    public static final String MSG_RESULT = "result";
    /** 错误回包：{"type":"error","data":{"msg":"..."}} */
    public static final String MSG_ERROR = "error";
    /** ping 命令的回包 */
    public static final String MSG_PONG = "pong";
    /** push_files_end 落盘结果回包：{"written":n,"failed":[...]} */
    public static final String MSG_PUSH_RESULT = "push_result";
    /** delete_files 结果回包：{"deleted":n,"failed":[...]} */
    public static final String MSG_DELETE_RESULT = "delete_result";
    /** 脚本输出回传：{"type":"log","data":{"line":...}} */
    public static final String MSG_LOG = "log";
    /** [IMG] 分片组装完成回传：{"type":"image","data":{"width","height","base64"}} */
    public static final String MSG_IMAGE = "image";
    /** capabilities 能力协商回包 */
    public static final String MSG_CAPABILITIES = "capabilities";

    // ------------------------------------------------------------------
    // 客户端 → 服务器 命令 type（一期命令集）
    // ------------------------------------------------------------------

    /** 沙箱执行 Lua 代码 */
    public static final String CMD_EXECUTE = "execute";
    /** 停止脚本 */
    public static final String CMD_STOP_SCRIPT = "stop_script";
    /** 暂停脚本 */
    public static final String CMD_PAUSE_SCRIPT = "pause_script";
    /** 恢复脚本 */
    public static final String CMD_RESUME_SCRIPT = "resume_script";
    /** 推文件会话开始（清空会话缓冲） */
    public static final String CMD_PUSH_FILES_START = "push_files_start";
    /** 推送单个文件：{path, content, encoding?}（encoding 默认 base64，可选 utf8） */
    public static final String CMD_PUSH_FILE = "push_file";
    /** 推文件会话结束（统一落盘到 getFilesDir()/ide_push/） */
    public static final String CMD_PUSH_FILES_END = "push_files_end";
    /** 增量同步基线：返回 {path: md5} 映射 */
    public static final String CMD_GET_FILE_MD5_LIST = "get_file_md5_list";
    /** 删除文件：{paths:[...]} */
    public static final String CMD_DELETE_FILES = "delete_files";
    /** 心跳探测 */
    public static final String CMD_PING = "ping";

    // ------------------------------------------------------------------
    // 客户端 → 服务器 命令 type（二期命令集）
    // ------------------------------------------------------------------

    /** push_zip 分块推送会话开始：{name, totalChunks, totalSize} */
    public static final String CMD_PUSH_ZIP_START = "push_zip_start";
    /** push_zip 分块推送：{name, index(从 1 起，严格顺序), data(base64)} */
    public static final String CMD_PUSH_ZIP_CHUNK = "push_zip_chunk";
    /** push_zip 分块推送完成：{name, expectedMd5}；校验通过后解压到 ide_push/ */
    public static final String CMD_PUSH_ZIP_COMPLETE = "push_zip_complete";
    /** 找图：{templateBase64 或 path, threshold?, rect?} */
    public static final String CMD_FIND_IMAGE = "find_image";
    /** 多点找色：{points:[{x,y,color}]（首点为锚点，其余为相对锚点的校验点）, similarity?, rect?} */
    public static final String CMD_FIND_MULTI_COLOR = "find_multi_color";
    /** 比色：{points:[{x,y,color}], similarity?, rect?} */
    public static final String CMD_CMP_COLOR = "cmp_color";
    /** OCR（当前版本无实现，回 error，见 {@link #ERR_OCR_UNAVAILABLE}） */
    public static final String CMD_OCR = "ocr";
    /** 能力协商：回当前注册的全部命令 type 与图色能力 */
    public static final String CMD_CAPABILITIES = "capabilities";

    // ------------------------------------------------------------------
    // 客户端 → 服务器 命令 type（四期：插件动态加载）
    // ------------------------------------------------------------------

    /** plugin_push_start：{name, totalChunks, totalSize, sha256(整包 zip 文件 sha256)} */
    public static final String CMD_PLUGIN_PUSH_START = "plugin_push_start";
    /** plugin_push_chunk：{name, index(从 1 起严格顺序), data(base64)} */
    public static final String CMD_PLUGIN_PUSH_CHUNK = "plugin_push_chunk";
    /** plugin_push_complete：{name}；文件级 sha256 校验通过后调 PluginManager.install，回 plugin_result */
    public static final String CMD_PLUGIN_PUSH_COMPLETE = "plugin_push_complete";
    /** plugin_list：查询已安装插件，回 {plugins:[{id,version,provides}...]} */
    public static final String CMD_PLUGIN_LIST = "plugin_list";
    /** plugin_remove：{id}，回 {removed:bool,id} */
    public static final String CMD_PLUGIN_REMOVE = "plugin_remove";
    /** 插件命令回包类型：{"type":"plugin_result","data":{...}} */
    public static final String MSG_PLUGIN_RESULT = "plugin_result";
    /** 插件推送临时 zip 目录：cache/ide_plugin_push/&lt;name&gt;.zip(.tmp) */
    public static final String IDE_PLUGIN_PUSH_DIR = "ide_plugin_push";
    /** OCR 插件识别失败错误消息前缀（recognize 抛错时回包） */
    public static final String ERR_OCR_PLUGIN = "__ERROR:ocr plugin error: ";
    /** 插件推送分块会话超时（ms）：超时未完成的会话丢弃 */
    public static final long PLUGIN_PUSH_SESSION_TIMEOUT_MS = 30_000;

    // ------------------------------------------------------------------
    // 客户端 → 服务器 命令 type（五期：方案八 远程控制与投屏）
    // ------------------------------------------------------------------

    /**
     * 开启投屏会话：{codec:"h264"|"jpeg", fps, bitrate(h264, bps), quality(jpeg 1-100), scale(0.1-1.0)}。
     * 成功回 {@link #MSG_STREAM_STARTED}（data 含真实屏幕 w/h，PC 端坐标映射用）；
     * 帧源未就绪（App 模式未授权投影 / root 探测失败）回 error。
     */
    public static final String CMD_STREAM_START = "stream_start";
    /** stream_start 成功回包：{"type":"stream_started","data":{"w":..,"h":..}} */
    public static final String MSG_STREAM_STARTED = "stream_started";
    /** 停止投屏并释放编码器：{}，回 result */
    public static final String CMD_STREAM_STOP = "stream_stop";
    /**
     * 请求立即出 IDR 关键帧：{}，回 result（{"requested":true/false}）。
     * PC 端解码器出错重建后必须从 IDR 起解，而 I 帧间隔按提交帧数计
     * （静止画面不合成新帧会拖很久）——此命令经 MediaCodec request-sync-frame
     * 让设备端下一帧即为 IDR，秒级恢复画面。
     */
    public static final String CMD_STREAM_SYNC = "stream_sync";
    /** stream_sync 回包：{"type":"stream_sync_result","data":{"requested":bool}}（false=设备端无 h264 会话） */
    public static final String MSG_STREAM_SYNC_RESULT = "stream_sync_result";
    /** 设备 → PC 旋转事件：{"type":"stream_rotate","data":{"w":..,"h":..}}（新分辨率） */
    public static final String MSG_STREAM_ROTATE = "stream_rotate";
    /** 远程触控：{action:"down"/"move"/"up", x, y}（设备屏幕绝对坐标，不缩放） */
    public static final String CMD_INJECT_TOUCH = "inject_touch";
    /** 远程滑动：{x1,y1,x2,y2,duration?}（duration 默认 300ms） */
    public static final String CMD_INJECT_SWIPE = "inject_swipe";
    /** 远程键码：{keycode}（经 ImeApi sendKey，KeyEvent down+up） */
    public static final String CMD_INJECT_KEY = "inject_key";
    /** 远程文本：{text}（经 ImeApi sendText，root 切默认输入法） */
    public static final String CMD_INJECT_TEXT = "inject_text";
    /** 注入命令统一回包：{"type":"inject_result","data":{"ok":bool}} */
    public static final String MSG_INJECT_RESULT = "inject_result";
    /** 远程启动工程脚本：{path}（相对 ROOT_DIR，如 /main.lua），走主引擎异步执行 */
    public static final String CMD_RUN_SCRIPT = "run_script";
    /** run_script 提交成功回包：{"type":"script_started","data":{"path":..,"ok":true}} */
    public static final String MSG_SCRIPT_STARTED = "script_started";
    /** 主引擎脚本状态广播：{"type":"script_state","data":{"state":"running"|"idle"}}（WORKER 观察者接线） */
    public static final String MSG_SCRIPT_STATE = "script_state";

    // ------------------------------------------------------------------
    // 客户端 → 服务器 命令 type（六期 9.11：调试通道整合，老 8176 通道退役，
    // UI 热重载触发方式迁移到新 WS 命令；DebugActivity 本体保留）
    // ------------------------------------------------------------------

    /** 启动 UI 热重载（弹 DebugActivity，经 UiDebugDelegate 由 app 层接线） */
    public static final String CMD_UI_DEBUG_START = "ui_debug_start";
    /** 关闭 UI 热重载（关 DebugActivity，经 UiDebugDelegate 由 app 层接线） */
    public static final String CMD_UI_DEBUG_STOP = "ui_debug_stop";
    /** ui_debug_start/stop 回包：{"type":"ui_debug_result","data":{"ok":bool,"action":"started"/"stopped"}} */
    public static final String MSG_UI_DEBUG_RESULT = "ui_debug_result";

    // ------------------------------------------------------------------
    // 投屏 binary 帧格式（大端，方案八 9.8）
    // ------------------------------------------------------------------
    /**
     * <pre>
     * h264  → [4B seq][4B flag][4B len][payload]
     *          flag=0：payload 为 avcC 配置集（SPS/PPS，avcC box 结构，
     *                  WebCodecs 直接作为 VideoDecoder.configure 的 description）
     *          flag=1：payload 为视频帧 NALU（AVCC 格式：每个 NALU 前 4B 大端长度，
     *                  无起始码；首 NALU type&0x1F==5 为 I 帧，PC 端据此构造 key chunk）
     * jpeg  → [4B seq][4B w][4B h][4B len][jpeg]
     *          w/h 为缩放后图像实际宽高（drawImage 直接使用）
     * </pre>
     * 文本 JSON 命令与二进制帧共存于同一 WS 连接。
     */

    // ------------------------------------------------------------------
    // execute 沙箱约定（供未来 Lua 桥接对接）
    // ------------------------------------------------------------------

    /** Lua 全局变量：execute 命令的参数 JSON（未来对接时 pushString + setGlobal 写入） */
    public static final String GLOBAL_WS_PARAMS = "_WS_PARAMS";
    /** Lua 全局变量：脚本执行结果（执行前先置 nil，执行后 getGlobal 取回） */
    public static final String GLOBAL_WS_RESULT = "_WS_RESULT";
    /** 沙箱执行错误前缀（_WS_RESULT 或 error 回包 msg 以此开头表示脚本错误） */
    public static final String ERROR_PREFIX = "__ERROR:";

    // ------------------------------------------------------------------
    // 服务器参数
    // ------------------------------------------------------------------

    /** 首选端口，占用时自动回退到 {@link #MAX_PORT} */
    public static final int DEFAULT_PORT = 8080;
    /** 端口回退上限（候选 8080..8085） */
    public static final int MAX_PORT = 8085;
    /** 心跳间隔（秒） */
    public static final int HEARTBEAT_INTERVAL_SECONDS = 30;
    /** 推送文件在应用私有目录下的根目录名：getFilesDir()/ide_push/ */
    public static final String IDE_PUSH_DIR = "ide_push";
    /** push_zip 临时 zip 目录：cache/ide_push_zip/（解压前落盘 <name>.zip.tmp） */
    public static final String IDE_PUSH_ZIP_DIR = "ide_push_zip";
    /** OCR 能力不可用错误消息（对应 capabilities.vision.ocr=false） */
    public static final String ERR_OCR_UNAVAILABLE = "__ERROR:ocr not available in this engine version";
    /** [IMG] 输出分片协议行前缀 */
    public static final String IMG_PREFIX = "[IMG]";
    /** [IMG] 单帧 base64 累计上限（8MB，防超大帧撑爆内存） */
    public static final int IMG_MAX_BASE64_BYTES = 8 * 1024 * 1024;
    /** [IMG] 分片会话超时（ms）：超时未完成的会话丢弃 */
    public static final long IMG_SESSION_TIMEOUT_MS = 30_000;

    /**
     * execute 沙箱执行序列（对照派大星 WebSocketApi.executeLuaModule:10070-10192，
     * 未来 Lua 桥接完成 {@link EngineExecutor} 真实实现时按此约定对接）：
     * <pre>
     *   engine.pushString(paramsJson);                       // 参数 JSON（可选）
     *   engine.setGlobal("_WS_PARAMS");                      // ① 传入 _WS_PARAMS
     *   engine.executeScript("_WS_RESULT = nil\n" + code);   // ② 先清空 _WS_RESULT 再执行
     *   String result = engine.getGlobalString("_WS_RESULT");// ③ 取回 _WS_RESULT
     * </pre>
     * 结果约定：脚本把返回值写入 _WS_RESULT；执行失败时 result 以 {@link #ERROR_PREFIX} 开头。
     */
    // （约定说明以注释形式固定于此，避免散落在实现里漂移）
}
