package com.autolua.engine.extension.net

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask

/**
 * 异步请求句柄：isDone() 轮询 + get() 取结果（返回值经 Object[] 底座展开为 Lua 多值），
 * cancel(true) 中断底层线程。get 在请求异常时抛出 Java 异常（Lua 侧 pcall 捕获）。
 */
class AsyncHandle(private val task: FutureTask<Array<Any?>>) {
  fun isDone(): Boolean = task.isDone
  fun get(): Array<Any?> = task.get()
  fun cancel(): Boolean = task.cancel(true)
}

/**
 * INetLib 实现：HttpURLConnection 同步请求。
 *
 * 返回约定：Object[]{body, status(Int), headers(Map<String,String>)}，
 * 由 JavaObjectWrapper 的 Object[] 多返回值底座展开为 Lua (body, code, headers)。
 *
 * 线程要求：必须在非主线程调用（Lua worker / IDE 消息线程池均满足），
 * 主线程调用会抛 NetworkOnMainThreadException（由 Lua 侧捕获为脚本错误）。
 */
open class NetLib : INetLib {

  companion object {
    private const val DEFAULT_TIMEOUT_MS = 10000
    private val UTF8: Charset = Charset.forName("UTF-8")
    private const val CRLF = "\r\n"
  }

  /** 全局超时/代理（fhttp.setTimeout / fhttp.setProxy） */
  @Volatile
  private var globalTimeoutMs = DEFAULT_TIMEOUT_MS

  @Volatile
  private var proxy: Proxy? = null

  override fun request(options: Map<*, *>): Any? {
    val url = options["url"] as? String
      ?: throw IllegalArgumentException("http.request: 'url' is required")
    val method = (options["method"] as? String)?.uppercase()?.ifEmpty { "GET" } ?: "GET"
    val headers = options["headers"] as? Map<*, *>
    val body = options["body"]
    val timeout = (options["timeout"] as? Number)?.toInt()?.coerceAtLeast(0) ?: globalTimeoutMs
    val binary = options["binary"] as? Boolean ?: false
    return doRequest(url, method, headers, body, timeout, binary)
  }

  override fun get(url: String): Any? {
    return doRequest(url, "GET", null, null, globalTimeoutMs, false)
  }

  override fun post(url: String, body: String): Any? {
    return doRequest(
      url, "POST",
      linkedMapOf("Content-Type" to "application/x-www-form-urlencoded"),
      body, globalTimeoutMs, false
    )
  }

  override fun requestMethod(url: String, method: String, body: String?): Any? {
    val m = method.uppercase()
    val headers = when {
      body != null && m != "POST" -> linkedMapOf(
        "Content-Type" to "application/x-www-form-urlencoded")
      m == "POST" -> linkedMapOf("Content-Type" to "application/x-www-form-urlencoded")
      else -> null
    }
    return doRequest(url, m, headers, body, globalTimeoutMs, false)
  }

  override fun download(url: String, path: String, headers: Map<*, *>?): Any? {
    val conn = open(url, "GET", headers, globalTimeoutMs)
    try {
      val code = conn.responseCode
      val stream = if (code in 200..399) conn.inputStream else conn.errorStream
      if (code !in 200..399 || stream == null) {
        stream?.use { it.readBytes() }
        return arrayOf(false, code)
      }
      val dst = File(path)
      dst.parentFile?.mkdirs()
      stream.use { input ->
        dst.outputStream().use { output -> input.copyTo(output) }
      }
      return arrayOf(true, code)
    } finally {
      conn.disconnect()
    }
  }

  override fun upload(
    url: String, path: String, fieldName: String, fileName: String?, form: Map<*, *>?
  ): Any? {
    val conn = open(url, "POST", null, globalTimeoutMs)
    try {
      val boundary = "----autolua" + System.currentTimeMillis().toString(16)
      conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
      conn.doOutput = true
      val name = fileName ?: File(path).name
      conn.outputStream.use { out ->
        if (form != null) {
          for ((k, v) in form) {
            if (k == null || v == null) continue
            out.write("--$boundary$CRLF".toByteArray(UTF8))
            out.write("Content-Disposition: form-data; name=\"${k}\"$CRLF$CRLF".toByteArray(UTF8))
            out.write(v.toString().toByteArray(UTF8))
            out.write(CRLF.toByteArray(UTF8))
          }
        }
        out.write("--$boundary$CRLF".toByteArray(UTF8))
        out.write(
          "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$name\"$CRLF".toByteArray(UTF8))
        out.write("Content-Type: application/octet-stream$CRLF$CRLF".toByteArray(UTF8))
        File(path).inputStream().use { it.copyTo(out) }
        out.write(CRLF.toByteArray(UTF8))
        out.write("--$boundary--$CRLF".toByteArray(UTF8))
      }
      val code = conn.responseCode
      val stream = if (code in 200..399) conn.inputStream else conn.errorStream
      val respHeaders = headerMap(conn)
      val body = String(readAll(stream), UTF8)
      return arrayOf(body, code, respHeaders)
    } finally {
      conn.disconnect()
    }
  }

  override fun setTimeout(ms: Int) {
    globalTimeoutMs = ms.coerceAtLeast(0)
  }

  override fun setProxy(host: String, port: Int) {
    proxy = if (host.isBlank()) null else Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
  }

  // ------------------------------------------------------------------
  // 异步族：独立 Java 线程执行网络 IO，句柄轮询取结果。
  // 设计要点：fhttp 异步族此前用 thread 协程 + 同步阻塞实现，协程内 Java 阻塞
  // 调用会持有 per-state 递归锁饿死其他协程；本实现把阻塞移出 Lua 线程，
  // Lua 侧 thread.sleep(15) 轮询 isDone()（sleep 挂起时解锁），不再卡 worker。
  // ------------------------------------------------------------------

  override fun requestAsync(options: Map<*, *>): AsyncHandle {
    val task = FutureTask(Callable { request(options) as Array<Any?> })
    Thread(task, "autolua-http-async").start()
    return AsyncHandle(task)
  }

  override fun downloadAsync(url: String, path: String, headers: Map<*, *>?): AsyncHandle {
    val task = FutureTask(Callable { download(url, path, headers) as Array<Any?> })
    Thread(task, "autolua-http-async-dl").start()
    return AsyncHandle(task)
  }

  override fun uploadAsync(
    url: String, path: String, fieldName: String, fileName: String?, form: Map<*, *>?
  ): AsyncHandle {
    val task = FutureTask(Callable { upload(url, path, fieldName, fileName, form) as Array<Any?> })
    Thread(task, "autolua-http-async-up").start()
    return AsyncHandle(task)
  }

  private fun open(urlStr: String, method: String, headers: Map<*, *>?, timeoutMs: Int): HttpURLConnection {
    val conn = URL(urlStr).openConnection(proxy ?: Proxy.NO_PROXY) as HttpURLConnection
    conn.requestMethod = method
    conn.connectTimeout = timeoutMs
    conn.readTimeout = timeoutMs
    conn.instanceFollowRedirects = true
    if (headers != null) {
      for ((k, v) in headers) {
        if (k != null && v != null) {
          conn.setRequestProperty(k.toString(), v.toString())
        }
      }
    }
    return conn
  }

  private fun doRequest(
    urlStr: String,
    method: String,
    headers: Map<*, *>?,
    body: Any?,
    timeoutMs: Int,
    binary: Boolean
  ): Array<Any?> {
    val conn = open(urlStr, method, headers, timeoutMs)
    try {
      val hasBody = body != null && method != "GET" && method != "HEAD"
      if (hasBody) {
        conn.doOutput = true
        val bytes = when (body) {
          is ByteArray -> body
          else -> body.toString().toByteArray(UTF8)
        }
        conn.outputStream.use { it.write(bytes) }
      } else if (method == "POST" || method == "PUT" || method == "PATCH") {
        conn.doOutput = true
      }
      val code = conn.responseCode
      val respHeaders = headerMap(conn)
      val stream = if (code in 200..399) conn.inputStream else conn.errorStream
      val data = readAll(stream)
      val payload: Any = if (binary) data else String(data, UTF8)
      return arrayOf(payload, code, respHeaders)
    } finally {
      conn.disconnect()
    }
  }

  private fun headerMap(conn: HttpURLConnection): LinkedHashMap<String, String> {
    val respHeaders = LinkedHashMap<String, String>()
    for ((k, v) in conn.headerFields) {
      if (k != null && v != null && v.isNotEmpty()) {
        respHeaders[k] = v.joinToString(", ")
      }
    }
    return respHeaders
  }

  private fun readAll(stream: java.io.InputStream?): ByteArray {
    if (stream == null) return ByteArray(0)
    return try {
      stream.use { input ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
          val n = input.read(buf)
          if (n < 0) break
          out.write(buf, 0, n)
        }
        out.toByteArray()
      }
    } catch (e: IOException) {
      // 部分服务器错误响应体不可读：返回已读内容（超时场景返回空），避免吞掉状态码
      ByteArray(0)
    }
  }
}
