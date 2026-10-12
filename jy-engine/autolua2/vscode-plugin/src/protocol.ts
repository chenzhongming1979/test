/**
 * AutoLua IDE WebSocket 协议镜像（对照 engine/ide/WsProtocol.java）。
 * 消息统一格式：{"type":"<cmd>","data":{...}}
 */

export interface WsMessage {
  type: string;
  data?: any;
}

/** 客户端 → 设备 命令 type */
export const Cmd = {
  /** 沙箱执行 Lua 代码 */
  EXECUTE: 'execute',
  STOP_SCRIPT: 'stop_script',
  PAUSE_SCRIPT: 'pause_script',
  RESUME_SCRIPT: 'resume_script',
  /** 推文件会话开始（清空会话缓冲） */
  PUSH_FILES_START: 'push_files_start',
  /** 推单个文件 {path, content, encoding:'base64'|'utf8'} */
  PUSH_FILE: 'push_file',
  /** 推文件会话结束，统一落盘 ide_push/ */
  PUSH_FILES_END: 'push_files_end',
  /** 增量同步基线 {dir?} → result {<path>: <md5>} */
  GET_FILE_MD5_LIST: 'get_file_md5_list',
  DELETE_FILES: 'delete_files',
  PING: 'ping',
  PUSH_ZIP_START: 'push_zip_start',
  PUSH_ZIP_CHUNK: 'push_zip_chunk',
  PUSH_ZIP_COMPLETE: 'push_zip_complete',
  FIND_IMAGE: 'find_image',
  FIND_MULTI_COLOR: 'find_multi_color',
  CMP_COLOR: 'cmp_color',
  OCR: 'ocr',
  CAPABILITIES: 'capabilities',
  PLUGIN_PUSH_START: 'plugin_push_start',
  PLUGIN_PUSH_CHUNK: 'plugin_push_chunk',
  PLUGIN_PUSH_COMPLETE: 'plugin_push_complete',
  PLUGIN_LIST: 'plugin_list',
  PLUGIN_REMOVE: 'plugin_remove',
  STREAM_START: 'stream_start',
  STREAM_STOP: 'stream_stop',
  STREAM_SYNC: 'stream_sync',
  INJECT_TOUCH: 'inject_touch',
  INJECT_SWIPE: 'inject_swipe',
  INJECT_KEY: 'inject_key',
  INJECT_TEXT: 'inject_text',
  /** 远程启动工程脚本 {path}（相对根目录，如 /main.lua） */
  RUN_SCRIPT: 'run_script',
  UI_DEBUG_START: 'ui_debug_start',
  UI_DEBUG_STOP: 'ui_debug_stop',
} as const;

/** 设备 → 客户端 消息 type */
export const Msg = {
  /** 连接建立后的握手回包 */
  HELLO: 'hello',
  /** 命令执行成功回包（默认） */
  RESULT: 'result',
  ERROR: 'error',
  PONG: 'pong',
  /** push_files_end 回包 {written, failed}；push_zip_complete 回包 {unzipped, failed} */
  PUSH_RESULT: 'push_result',
  DELETE_RESULT: 'delete_result',
  /** 脚本输出回传 {line} */
  LOG: 'log',
  /** [IMG] 分帧组装完成 {width,height,base64} */
  IMAGE: 'image',
  CAPABILITIES: 'capabilities',
  STREAM_STARTED: 'stream_started',
  STREAM_ROTATE: 'stream_rotate',
  INJECT_RESULT: 'inject_result',
  /** run_script 提交成功 {path, ok} */
  SCRIPT_STARTED: 'script_started',
  /** 主引擎脚本状态广播 {state:'running'|'idle'} */
  SCRIPT_STATE: 'script_state',
  UI_DEBUG_RESULT: 'ui_debug_result',
  PLUGIN_RESULT: 'plugin_result',
} as const;

/** 设备端 WS 首选端口与回退上限（8080..8085） */
export const WS_PORT_MIN = 8080;
export const WS_PORT_MAX = 8085;

/** UDP 设备发现：PC 端问句 / 设备端应答 */
export const UDP_PROBE = 'AutoLuaEngine,Where are you?';
export const UDP_ANSWER = 'AutoLuaKit,I am here!';

/** 单条命令等待回包超时（ms） */
export const CMD_TIMEOUT_MS = 15_000;
/** 心跳间隔（ms），对齐设备端 30s */
export const HEARTBEAT_MS = 30_000;
