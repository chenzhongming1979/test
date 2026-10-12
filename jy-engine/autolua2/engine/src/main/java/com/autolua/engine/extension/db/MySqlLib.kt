package com.autolua.engine.extension.db

import com.autolua.engine.base.LuaContext
import com.autolua.engine.base.LuaEnvContributor
import com.autolua.engine.base.LuaObjectAdapter
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/**
 * MySQL 协议客户端（零依赖单文件，对齐懒人 connectSQL/executeQuerySQL/executeSQL/closeSQL）。
 *
 * 支持 MySQL 4.1+ 握手：
 * - mysql_native_password（SHA1 三段异或）
 * - caching_sha2_password 快速认证（SHA256 scramble；完整认证需 SSL/RSA，不支持时报错提示）
 *
 * Lua 面：
 * - 对象式：local conn = mysql.connect(host, port, user, pwd, db[, timeoutMs])
 *   conn:query(sql) → rows / nil, err；conn:execute(sql) → affected / nil, err
 *   conn:ping() → bool；conn:close()；conn.connected / conn.affectedRows / conn.lastInsertId
 * - mysql.escape(str) → str
 * - 懒人全局（隐式当前连接）：connectSQL(host, port, user, pwd, db[, timeout]) → bool, err
 *   executeQuerySQL(sql) → rows / nil, err；executeSQL(sql) → affected / nil, err；closeSQL() → bool
 *
 * rows 每行为表：[1..n] 按列序 + [列名]=值 双形态；NULL 为 nil。
 * 连接非并发安全（Lua worker 单线程语义下串行使用）。
 */
open class MySqlLib : LuaEnvContributor {

  // ---------------- 协议实现 ----------------

  class MySqlConnection(
    private val host: String, private val port: Int,
    private val user: String, private val password: String,
    private val database: String?, private val timeoutMs: Int
  ) : LuaObjectAdapter {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: java.io.OutputStream? = null
    private var seq = 0
    var connected = false; private set
    var affectedRows = 0L; private set
    var lastInsertId = 0L; private set

    private class ProtoException(msg: String) : Exception(msg)

    /** 字节游标：LCB/LencStr/NUL 串解析 */
    private class Buf(val b: ByteArray) {
      var p = 0
      fun u8(): Int = (b[p++].toInt() and 0xFF)
      fun u16(): Int { val v = u8(); return v or (u8() shl 8) }
      fun lcb(): Long {
        val h = u8()
        return when {
          h < 0xFB -> h.toLong()
          h == 0xFB -> -1L
          h == 0xFC -> u16().toLong()
          h == 0xFD -> { var v = 0L; for (i in 0 until 3) v = v or ((u8().toLong()) shl (8 * i)); v }
          else -> { var v = 0L; for (i in 0 until 8) v = v or ((u8().toLong()) shl (8 * i)); v }
        }
      }
      fun lencStr(): String {
        val len = lcb().toInt()
        val s = String(b, p, len, Charsets.UTF_8); p += len
        return s
      }
      fun nulStr(): String {
        var e = p
        while (e < b.size && b[e] != 0.toByte()) e++
        val s = String(b, p, e - p, Charsets.UTF_8)
        p = if (e < b.size) e + 1 else e
        return s
      }
    }

    private fun readPacket(): ByteArray {
      val ins = input ?: throw ProtoException("mysql: not connected")
      val b0 = ins.read(); val b1 = ins.read(); val b2 = ins.read(); val sq = ins.read()
      if ((b0 or b1 or b2 or sq) < 0) throw EOFException("mysql: connection closed")
      val len = b0 or (b1 shl 8) or (b2 shl 16)
      seq = (sq + 1) and 0xFF
      val payload = ByteArray(len)
      ins.readFully(payload)
      return payload
    }

    private fun writePacket(p: ByteArray) {
      val out = output ?: throw ProtoException("mysql: not connected")
      var off = 0
      do {
        val chunk = Math.min(p.size - off, 0xFFFFFF)
        out.write(byteArrayOf((chunk and 0xFF).toByte(), ((chunk shr 8) and 0xFF).toByte(),
          ((chunk shr 16) and 0xFF).toByte(), seq.toByte()))
        if (chunk > 0) out.write(p, off, chunk)
        off += chunk
        seq = (seq + 1) and 0xFF
      } while (off < p.size || p.isEmpty())
      out.flush()
    }

    private fun parseOk(r: Buf) {
      r.p = 1
      affectedRows = r.lcb()
      lastInsertId = r.lcb()
    }

    private fun errMsg(p: ByteArray): String {
      if (p.size < 4) return "mysql: error packet"
      val errno = (p[1].toInt() and 0xFF) or ((p[2].toInt() and 0xFF) shl 8)
      var off = 3
      if (p.size > 3 && p[off] == '#'.code.toByte()) off += 6
      val msg = String(p, off, p.size - off, Charsets.UTF_8)
      return "mysql: $errno $msg"
    }

    private fun authData(plugin: String, nonce: ByteArray, password: String): ByteArray? {
      if (password.isEmpty()) return ByteArray(0)
      return when (plugin) {
        "mysql_native_password" -> {
          val sha = MessageDigest.getInstance("SHA-1")
          val s1 = sha.digest(password.toByteArray(Charsets.UTF_8))
          sha.reset(); val s2 = sha.digest(s1)
          sha.reset(); sha.update(nonce, 0, Math.min(20, nonce.size)); sha.update(s2)
          val s3 = sha.digest()
          ByteArray(20) { i -> (s1[i].toInt() xor s3[i].toInt()).toByte() }
        }
        "caching_sha2_password" -> {
          val sha = MessageDigest.getInstance("SHA-256")
          val h1 = sha.digest(password.toByteArray(Charsets.UTF_8))
          sha.reset(); val h2 = sha.digest(h1)
          sha.reset(); sha.update(h2); sha.update(nonce, 0, Math.min(32, nonce.size))
          val h3 = sha.digest()
          ByteArray(h1.size) { i -> (h1[i].toInt() xor h3[i].toInt()).toByte() }
        }
        else -> null
      }
    }

    /** 建立连接并完成认证；成功返回 null，失败返回错误描述 */
    @Synchronized
    fun open(): String? {
      if (connected) return null
      try {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        socket = s
        input = DataInputStream(s.getInputStream().buffered())
        output = s.getOutputStream()
        seq = 0
        // 握手包
        val g = readPacket()
        val r = Buf(g)
        val proto = r.u8()
        if (proto != 10) return fail("unsupported protocol $proto")
        r.nulStr()                 // server version
        r.p += 4                   // thread id
        val nonce = ByteArrayOutputStream()
        nonce.write(g, r.p, 8); r.p += 8   // auth-plugin-data-part-1
        r.p += 1                   // filler
        r.u16()                    // capability flags lower
        r.u8()                     // charset
        r.u16()                    // status flags
        r.u16()                    // capability flags upper
        val authLen = r.u8()
        r.p += 10                  // reserved
        val part2Len = Math.max(13, authLen - 8)
        nonce.write(g, r.p, Math.min(part2Len, g.size - r.p)); r.p += Math.min(part2Len, g.size - r.p)
        var plugin = "mysql_native_password"
        if (g.size > r.p) plugin = r.nulStr()  // CLIENT_PLUGIN_AUTH: 插件名
        var n = nonce.toByteArray()
        if (n.isNotEmpty() && n[n.size - 1] == 0.toByte()) n = n.copyOf(n.size - 1)
        // 握手响应 HandshakeResponse41
        seq = 1
        val caps = (0x1 or 0x4 or 0x200 or 0x2000 or 0x8000 or 0x80000
          or (if (database != null) 0x8 else 0))
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf((caps and 0xFF).toByte(), ((caps shr 8) and 0xFF).toByte(),
          ((caps shr 16) and 0xFF).toByte(), ((caps shr 24) and 0xFF).toByte()))
        body.write(0x00); body.write(0x00); body.write(0x00); body.write(0x01) // max packet 16MB
        body.write(45)             // utf8mb4_general_ci
        body.write(ByteArray(23))  // reserved
        body.write(user.toByteArray(Charsets.UTF_8)); body.write(0)
        val auth = authData(plugin, n, password)
          ?: return fail("unsupported auth plugin: $plugin (caching_sha2_password 完整认证需 SSL/RSA，请将用户认证方式改为 mysql_native_password)")
        body.write(auth.size); body.write(auth)
        if (database != null) { body.write(database.toByteArray(Charsets.UTF_8)); body.write(0) }
        body.write(plugin.toByteArray(Charsets.UTF_8)); body.write(0)
        writePacket(body.toByteArray())
        // 认证循环
        while (true) {
          val resp = readPacket()
          when (resp[0].toInt() and 0xFF) {
            0x00 -> { parseOk(Buf(resp)); connected = true; return null }
            0xFF -> return fail(errMsg(resp))
            0xFE -> {  // AuthSwitchRequest
              val sr = Buf(resp); sr.p = 1
              val newPlugin = sr.nulStr()
              val a = authData(newPlugin, resp.copyOfRange(sr.p, resp.size), password)
                ?: return fail("unsupported auth plugin: $newPlugin (完整认证需 SSL/RSA，请改用 mysql_native_password)")
              writePacket(a)
            }
            0x01 -> {  // AuthMoreData（caching_sha2 快速路径）
              val t = if (resp.size >= 2) resp[1].toInt() and 0xFF else -1
              if (t == 0x03) { /* fast auth ok，下一个包应为 OK */ }
              else if (t == 0x04) return fail("caching_sha2_password full auth required (SSL/RSA unsupported)，请将用户改为 mysql_native_password")
              else return fail("unexpected auth packet 0x$t")
            }
            else -> return fail("unexpected packet 0x${resp[0].toInt() and 0xFF} during auth")
          }
        }
      } catch (t: Throwable) {
        closeQuietly()
        return t.toString()
      }
    }

    private fun fail(msg: String): String { closeQuietly(); return msg }

    /** COM_QUERY；SELECT 返回 (列名, 行集)，非查询返回 (空, 空) 并更新 affectedRows/lastInsertId */
    @Synchronized
    fun query(sql: String): Pair<Array<String>, List<Array<String?>>> {
      if (!connected) throw ProtoException("mysql: not connected")
      seq = 0
      val q = sql.toByteArray(Charsets.UTF_8)
      val p = ByteArray(q.size + 1); p[0] = 0x03
      System.arraycopy(q, 0, p, 1, q.size)
      writePacket(p)
      val first = readPacket()
      when (first[0].toInt() and 0xFF) {
        0xFF -> throw ProtoException(errMsg(first))
        0x00 -> { parseOk(Buf(first)); return Pair(emptyArray(), emptyList()) }
        else -> {
          val colCount = Buf(first).lcb().toInt()
          val cols = Array(colCount) {
            val cp = readPacket()
            val br = Buf(cp)
            br.lcb(); br.lcb(); br.lcb(); br.lcb()   // catalog/schema/table/org_table
            br.lencStr()                              // name
          }
          val eof = readPacket()  // 列定义后的 EOF（未启用 CLIENT_DEPRECATE_EOF）
          if (!(eof.size < 9 && eof[0].toInt() and 0xFF == 0xFE)) throw ProtoException("mysql: expected EOF after columns")
          val rows = ArrayList<Array<String?>>()
          while (true) {
            val rp = readPacket()
            val h = rp[0].toInt() and 0xFF
            if (h == 0xFE && rp.size < 9) break        // 行集结束 EOF
            if (h == 0x00) { parseOk(Buf(rp)); break }
            if (h == 0xFF) throw ProtoException(errMsg(rp))
            val br = Buf(rp)
            val row = arrayOfNulls<String?>(colCount)
            for (i in 0 until colCount) {
              row[i] = if (rp[br.p].toInt() and 0xFF == 0xFB) { br.p++; null } else br.lencStr()
            }
            rows.add(row)
          }
          return Pair(cols, rows)
        }
      }
    }

    /** COM_PING */
    @Synchronized
    fun ping(): Boolean = try {
      if (!connected) false else {
        seq = 0
        writePacket(byteArrayOf(0x0E))
        val rp = readPacket()
        rp[0].toInt() and 0xFF == 0x00
      }
    } catch (t: Throwable) { false }

    @Synchronized
    fun close() { closeQuietly() }

    private fun closeQuietly() {
      connected = false
      try { socket?.close() } catch (ignore: Throwable) {}
      socket = null; input = null; output = null
    }

    // ---------------- LuaObjectAdapter（conn 对象） ----------------

    override fun index(L: LuaContext): Int {
      when (L.toString(2)) {
        "query", "execute", "ping", "close" -> return 2
        "connected" -> { L.push(connected); return 1 }
        "affectedRows" -> { L.push(affectedRows); return 1 }
        "lastInsertId" -> { L.push(lastInsertId); return 1 }
      }
      L.pushNil()
      return 1
    }

    override fun call(L: LuaContext, name: String): Int {
      return try {
        when (name) {
          "query", "execute" -> {
            val sql = L.toString(2) ?: ""
            val (cols, rows) = query(sql)
            if (name == "execute") {
              L.push(affectedRows); 1
            } else {
              // rows：外层表 n 行；每行 [1..n] + [列名] 双键
              ctxRows(L, cols, rows); 1
            }
          }
          "ping" -> { L.push(ping()); 1 }
          "close" -> { close(); L.push(true); 1 }
          else -> { L.pushNil(); 1 }
        }
      } catch (t: Throwable) {
        L.pushNil(); L.push(t.toString()); 2
      }
    }

    override fun invoke(L: LuaContext): Int = 0
    override fun newIndex(L: LuaContext): Int = 0
    override fun release(L: LuaContext) { closeQuietly() }
  }

  // ---------------- LuaEnvContributor：mysql 表 + 懒人全局 ----------------

  override fun contribute(ctx: LuaContext) {
    ctx.createTable(0, 4)
    put(ctx, "connect") { L ->
      val conn = fromLua(L)
      val err = conn.open()
      if (err != null) { ctx.pushNil(); ctx.push(err); 2 } else { ctx.push(conn as LuaObjectAdapter); 1 }
    }
    put(ctx, "escape") { L ->
      ctx.push(escape(L.toString(2) ?: "")); 1
    }
    // 懒人全局隐式连接的 SQL 方法（挂表内别名，方便统一）
    put(ctx, "query") { L -> currentConn?.let { ctxCallQuery(L, it) } ?: run { ctx.pushNil(); ctx.push("mysql: not connected (先 mysql.connect/connectSQL)"); 2 } }
    put(ctx, "execute") { L -> currentConn?.let { ctxCallExecute(L, it) } ?: run { ctx.pushNil(); ctx.push("mysql: not connected (先 mysql.connect/connectSQL)"); 2 } }
    ctx.setGlobal("mysql")

    registerGlobal(ctx, "connectSQL") { L ->
      val conn = fromLua(L)
      val err = conn.open()
      if (err != null) { currentConn = null; ctx.push(false); ctx.push(err); 2 }
      else { currentConn = conn; ctx.push(true); 1 }
    }
    registerGlobal(ctx, "executeQuerySQL") { L ->
      currentConn?.let { ctxCallQuery(L, it) }
        ?: run { ctx.pushNil(); ctx.push("mysql: not connected (先 connectSQL)"); 2 }
    }
    registerGlobal(ctx, "executeSQL") { L ->
      currentConn?.let { ctxCallExecute(L, it) }
        ?: run { ctx.pushNil(); ctx.push("mysql: not connected (先 connectSQL)"); 2 }
    }
    registerGlobal(ctx, "closeSQL") { L ->
      val c = currentConn
      currentConn = null
      c?.close()
      ctx.push(c != null); 1
    }
  }

  companion object {
    @Volatile
    private var currentConn: MySqlConnection? = null

    /** 可选参数安全读取：C++ toString 对 nil 会抛 LuaTypeError，须先判型 */
    private fun optStr(L: LuaContext, i: Int): String? =
      if (L.top >= i && (L.type(i) == LuaContext.ValueType.STRING || L.type(i) == LuaContext.ValueType.NUMBER))
        L.toString(i) else null

    private fun optInt(L: LuaContext, i: Int, def: Int): Int =
      if (L.top >= i && L.type(i) == LuaContext.ValueType.NUMBER) L.toLong(i).toInt() else def

    private fun fromLua(L: LuaContext): MySqlConnection {
      val host = optStr(L, 2) ?: "127.0.0.1"
      val port = optInt(L, 3, 3306)
      val user = optStr(L, 4) ?: ""
      val pwd = optStr(L, 5) ?: ""
      val db = optStr(L, 6)
      val timeout = optInt(L, 7, 10000)
      return MySqlConnection(host, port, user, pwd, db, timeout)
    }

    private fun ctxCallQuery(L: LuaContext, conn: MySqlConnection): Int = try {
      val sql = optStr(L, 2) ?: ""
      val (cols, rows) = conn.query(sql)
      ctxRows(L, cols, rows); 1
    } catch (t: Throwable) { L.pushNil(); L.push(t.toString()); 2 }

    private fun ctxCallExecute(L: LuaContext, conn: MySqlConnection): Int = try {
      val sql = optStr(L, 2) ?: ""
      conn.query(sql)
      L.push(conn.affectedRows); 1
    } catch (t: Throwable) { L.pushNil(); L.push(t.toString()); 2 }

    /** rows 外层表；每行 [1..n] 按列序 + [列名]=值 */
    private fun ctxRows(L: LuaContext, cols: Array<String>, rows: List<Array<String?>>) {
      L.createTable(rows.size, 0)
      for ((ri, row) in rows.withIndex()) {
        L.push((ri + 1).toLong())
        L.createTable(row.size, cols.size)
        for (i in row.indices) {
          L.push((i + 1).toLong()); L.push(row[i]); L.setTable(-3)
          if (i < cols.size && cols[i].isNotEmpty()) {
            L.push(cols[i]); L.push(row[i]); L.setTable(-3)
          }
        }
        L.setTable(-3)
      }
    }

    /** 懒人风格 SQL 转义：\0 \n \r \ ' " \x1a */
    fun escape(s: String): String {
      val sb = StringBuilder(s.length + 8)
      for (c in s) {
        when (c) {
          '\u0000' -> sb.append("\\0")
          '\n' -> sb.append("\\n")
          '\r' -> sb.append("\\r")
          '\\' -> sb.append("\\\\")
          '\'' -> sb.append("\\'")
          '"' -> sb.append("\\\"")
          '\u001A' -> sb.append("\\Z")
          else -> sb.append(c)
        }
      }
      return sb.toString()
    }
  }

  // ---------------- 工具 ----------------

  private fun put(ctx: LuaContext, name: String, body: (LuaContext) -> Int) {
    ctx.push(name)
    ctx.push(adapter(body))
    ctx.setTable(-3)
  }

  private fun adapter(body: (LuaContext) -> Int): LuaObjectAdapter = object : LuaObjectAdapter {
    override fun invoke(L: LuaContext): Int = body(L)
    override fun index(L: LuaContext): Int = 0
    override fun newIndex(L: LuaContext): Int = 0
    override fun call(L: LuaContext, name: String): Int = 0
    override fun release(L: LuaContext) {}
  }

  private fun registerGlobal(ctx: LuaContext, name: String, body: (LuaContext) -> Int) {
    ctx.push(adapter(body))
    ctx.setGlobal(name)
  }
}
