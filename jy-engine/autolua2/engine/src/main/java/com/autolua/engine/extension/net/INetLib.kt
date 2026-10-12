package com.autolua.engine.extension.net

/**
 * HTTP 网络服务（luasocket 风格），注入为 Lua 全局 `http`。
 * 必须注册在 Lua 所在进程本地（Server.kt root 进程 / LuaEngineExecutor 沙箱），
 * 不得经 Client.Builder 跨进程注册。
 */
interface INetLib {
  /**
   * 通用请求：options 表字段
   * - url（必填）、method（默认 GET）、headers（表）、body（string/bytes）、timeout（毫秒，默认 10000）、binary（true 时 body 返回字节数组）
   * 返回 Object[]{body, status, headers} → Lua 多返回值 (body, code, headers)
   */
  fun request(options: Map<*, *>): Any?

  /** 便捷 GET：返回 (body, code, headers) */
  fun get(url: String): Any?

  /** 便捷 POST（form 编码）：返回 (body, code, headers) */
  fun post(url: String, body: String): Any?

  /** 任意 method 请求（PUT/DELETE/PATCH/HEAD/OPTIONS...），body 可 null：返回 (body, code, headers) */
  fun requestMethod(url: String, method: String, body: String?): Any?

  /** 流式下载到文件：返回 (bool, code) */
  fun download(url: String, path: String, headers: Map<*, *>?): Any?

  /**
   * multipart/form-data 文件上传：返回 (body, code, headers)。
   * form 为额外文本字段表（可 null）；fileName 缺省取 path 的文件名。
   */
  fun upload(url: String, path: String, fieldName: String, fileName: String?, form: Map<*, *>?): Any?

  /** 全局连接/读取超时（毫秒）；影响后续所有请求（request 内显式 timeout 覆盖） */
  fun setTimeout(ms: Int)

  /** 全局代理（host 传空字符串清除） */
  fun setProxy(host: String, port: Int)

  /**
   * 异步请求：网络 IO 在独立 Java 线程执行，立即返回 {@link AsyncHandle} 句柄。
   * Lua 侧用 handle:isDone() 轮询 + handle:get() 取结果（get 返回 (body, code, headers) 多值，
   * 请求异常时 get 抛 Lua 错误），轮询期间 thread.sleep 挂起解锁，不阻塞其他协程。
   */
  fun requestAsync(options: Map<*, *>): AsyncHandle

  /** 异步流式下载：返回句柄，get 返回 (bool, code) */
  fun downloadAsync(url: String, path: String, headers: Map<*, *>?): AsyncHandle

  /** 异步 multipart 上传：返回句柄，get 返回 (body, code, headers) */
  fun uploadAsync(url: String, path: String, fieldName: String, fileName: String?, form: Map<*, *>?): AsyncHandle
}
