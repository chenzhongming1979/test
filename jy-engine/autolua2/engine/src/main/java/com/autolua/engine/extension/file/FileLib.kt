package com.autolua.engine.extension.file

import com.autolua.engine.base.LuaContext
import com.autolua.engine.base.LuaEnvContributor
import java.io.File

/**
 * 文件域服务（注入为 Lua 全局 `file`），并经 contribute 注册懒人风格全局函数：
 * fileExist / mkdir / delfile / readFile / writeFile / fileSize / listDir
 *
 * 所有方法必须在非主线程调用（Lua worker / IDE 消息线程池满足）。
 */
open class FileLib : LuaEnvContributor {

  // ---------------- 对象式 API（file.*） ----------------

  fun exists(path: String): Boolean = File(path).exists()

  fun isFile(path: String): Boolean = File(path).isFile

  fun isDirectory(path: String): Boolean = File(path).isDirectory

  fun size(path: String): Long {
    val f = File(path)
    return if (f.isFile) f.length() else -1L
  }

  /** 递归创建目录（对齐懒人 mkdir 多级语义） */
  fun mkdir(path: String): Boolean = File(path).let { it.mkdirs() || it.isDirectory }

  fun mkdirs(path: String): Boolean = File(path).mkdirs()

  /** 单层删除：空目录或文件 */
  fun remove(path: String): Boolean = File(path).delete()

  /** 递归删除目录/文件 */
  fun deleteDir(path: String): Boolean = deleteRecursive(File(path))

  fun rename(from: String, to: String): Boolean = File(from).renameTo(File(to))

  fun copy(from: String, to: String): Boolean = try {
    val src = File(from)
    val dst = File(to)
    if (src.isDirectory) {
      dst.mkdirs()
      var ok = true
      for (child in src.listFiles() ?: emptyArray()) {
        if (!copy(child.absolutePath, File(dst, child.name).absolutePath)) ok = false
      }
      ok
    } else {
      dst.parentFile?.mkdirs()
      src.inputStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
      true
    }
  } catch (t: Throwable) {
    false
  }

  fun mTime(path: String): Long = File(path).lastModified()

  /** 目录列举（返回名字数组；文件时返回单元素） */
  fun listDir(path: String): Array<String> {
    val f = File(path)
    val names = f.listFiles()?.map { it.name } ?: (if (f.exists()) listOf(f.name) else emptyList())
    return names.toTypedArray()
  }

  /** 文本读取（UTF-8；binary=true 时返回字节数组） */
  @JvmOverloads
  fun read(path: String, binary: Boolean = false): Any? {
    val f = File(path)
    if (!f.isFile) return null
    return if (binary) f.readBytes() else f.readText(Charsets.UTF_8)
  }

  /** 文本写入（append=true 追加） */
  @JvmOverloads
  fun write(path: String, content: String, append: Boolean = false): Boolean = try {
    val f = File(path)
    f.parentFile?.mkdirs()
    if (append) f.appendText(content, Charsets.UTF_8) else f.writeText(content, Charsets.UTF_8)
    true
  } catch (t: Throwable) {
    false
  }

  fun append(path: String, content: String): Boolean = write(path, content, true)

  // ---------------- 压缩（懒人 zip/unZip） ----------------

  /** 打包文件或整个目录为 zip（懒人 zip(srcPath, zipPath)） */
  fun zip(srcPath: String, zipPath: String): Boolean = try {
    val src = File(srcPath)
    val out = File(zipPath)
    out.parentFile?.mkdirs()
    val prefix = if (src.isDirectory) srcPath.trimEnd('/') + "/" else srcPath.trimEnd('/').substringBeforeLast('/') + "/"
    java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zos ->
      fun put(f: File) {
        val entryName = f.absolutePath.removePrefix(prefix).replace('\\', '/')
        if (f.isDirectory) {
          if (entryName.isNotEmpty()) {
            zos.putNextEntry(java.util.zip.ZipEntry("$entryName/"))
            zos.closeEntry()
          }
          for (child in f.listFiles() ?: emptyArray()) put(child)
        } else {
          zos.putNextEntry(java.util.zip.ZipEntry(entryName))
          f.inputStream().use { it.copyTo(zos) }
          zos.closeEntry()
        }
      }
      put(src)
    }
    true
  } catch (t: Throwable) { false }

  /** 解压 zip 到目标目录（懒人 unZip(zipPath, destDir)） */
  fun unZip(zipPath: String, destDir: String): Boolean = try {
    val dest = File(destDir)
    dest.mkdirs()
    val destCanonical = dest.canonicalPath + File.separator
    java.util.zip.ZipInputStream(File(zipPath).inputStream().buffered()).use { zis ->
      while (true) {
        val entry = zis.nextEntry ?: break
        val out = File(dest, entry.name)
        // 防 zip path traversal：解压目标必须仍在 destDir 内
        if (!out.canonicalPath.startsWith(destCanonical)) {
          zis.closeEntry()
          continue
        }
        if (entry.isDirectory) {
          out.mkdirs()
        } else {
          out.parentFile?.mkdirs()
          out.outputStream().use { zis.copyTo(it) }
        }
        zis.closeEntry()
      }
    }
    true
  } catch (t: Throwable) { false }

  // ---------------- LuaEnvContributor：懒人风格全局函数 ----------------

  override fun contribute(ctx: LuaContext) {
    registerGlobal(ctx, "fileExist") { L -> push(L, exists(L.toString(2)!!)) }
    registerGlobal(ctx, "mkdir") { L -> push(L, mkdirs(L.toString(2)!!)) }
    registerGlobal(ctx, "delfile") { L -> push(L, deleteDir(L.toString(2)!!)) }
    registerGlobal(ctx, "fileSize") { L -> push(L, size(L.toString(2)!!)) }
    registerGlobal(ctx, "readFile") { L ->
      val data = read(L.toString(2)!!, false)
      ctx.push(data as? String)
      1
    }
    registerGlobal(ctx, "writeFile") { L ->
      push(L, write(L.toString(2)!!, L.toString(3) ?: "", false))
    }
    registerGlobal(ctx, "listDir") { L ->
      val names = listDir(L.toString(2) ?: ".")
      ctx.createTable(names.size, 0)
      for ((i, n) in names.withIndex()) {
        ctx.push((i + 1).toLong())
        ctx.push(n)
        ctx.setTable(-3)
      }
      1
    }
    // 压缩（懒人 zip/unZip）
    registerGlobal(ctx, "zip") { L -> push(L, zip(L.toString(2) ?: "", L.toString(3) ?: "")) }
    registerGlobal(ctx, "unZip") { L -> push(L, unZip(L.toString(2) ?: "", L.toString(3) ?: "")) }
  }

  private fun push(ctx: LuaContext, b: Boolean): Int {
    ctx.push(b)
    return 1
  }

  private fun push(ctx: LuaContext, l: Long): Int {
    ctx.push(l)
    return 1
  }

  private fun registerGlobal(ctx: LuaContext, name: String, body: (LuaContext) -> Int) {
    ctx.push(object : com.autolua.engine.base.LuaObjectAdapter {
      override fun invoke(L: LuaContext): Int = body(L)
      override fun index(L: LuaContext): Int = 0
      override fun newIndex(L: LuaContext): Int = 0
      override fun call(L: LuaContext, name: String): Int = 0
      override fun release(L: LuaContext) {}
    })
    ctx.setGlobal(name)
  }

  companion object {
    private fun deleteRecursive(f: File): Boolean {
      if (f.isDirectory) {
        for (child in f.listFiles() ?: emptyArray()) {
          if (!deleteRecursive(child)) return false
        }
      }
      return f.delete()
    }
  }
}
