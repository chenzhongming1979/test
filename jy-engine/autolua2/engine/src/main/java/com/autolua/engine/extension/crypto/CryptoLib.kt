package com.autolua.engine.extension.crypto

import com.autolua.engine.base.LuaContext
import com.autolua.engine.base.LuaEnvContributor
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 加解密域服务（注入为 Lua 全局 `crypt`，对齐懒人 cryptLib 命名），并经 contribute 注册全局别名：
 * MD5 / fileMD5 / encodeBase64 / decodeBase64 / getFileBase64 / encodeUrl / decodeUrl / sha256
 *
 * aesCrypt(mode, key, data [, iv])：
 * - mode: "E"=加密 / "D"=解密（懒人 aes_crypt 语义）
 * - key: 16/24/32 字节字符串或字节数组（AES-128/192/256），按 UTF-8 取字节
 * - data: 明文/密文（字符串按 UTF-8 取字节；解密时若为字符串按 Base64 先解码——
 *        懒人 aes_crypt 的密文习惯 Base64 传输）
 * - iv: 可选（有则 AES/CBC/PKCS5Padding，无则 AES/ECB/PKCS5Padding）
 * - 返回：加密=Base64 字符串，解密=UTF-8 字符串
 */
open class CryptoLib : LuaEnvContributor {

  // ---------------- 哈希 ----------------

  fun md5(data: String): String = hashHex("MD5", data.toByteArray(Charsets.UTF_8))

  fun md5Bytes(data: ByteArray): String = hashHex("MD5", data)

  fun sha1(data: String): String = hashHex("SHA-1", data.toByteArray(Charsets.UTF_8))

  fun sha256(data: String): String = hashHex("SHA-256", data.toByteArray(Charsets.UTF_8))

  fun sha512(data: String): String = hashHex("SHA-512", data.toByteArray(Charsets.UTF_8))

  fun fileMD5(path: String): String {
    val md = MessageDigest.getInstance("MD5")
    java.io.FileInputStream(path).use { input ->
      val buf = ByteArray(8192)
      while (true) {
        val n = input.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
      }
    }
    return toHex(md.digest())
  }

  private fun hashHex(algorithm: String, data: ByteArray): String {
    val md = MessageDigest.getInstance(algorithm)
    return toHex(md.digest(data))
  }

  private fun toHex(bytes: ByteArray): String =
    bytes.joinToString("") { String.format("%02x", it) }

  // ---------------- Base64 / Url ----------------

  fun encodeBase64(data: String): String =
    Base64.getEncoder().encodeToString(data.toByteArray(Charsets.UTF_8))

  fun decodeBase64(data: String): String =
    String(Base64.getDecoder().decode(data), Charsets.UTF_8)

  fun encodeBase64Bytes(data: ByteArray): String =
    Base64.getEncoder().encodeToString(data)

  fun decodeBase64Bytes(data: String): ByteArray = Base64.getDecoder().decode(data)

  fun getFileBase64(path: String): String =
    Base64.getEncoder().encodeToString(java.io.File(path).readBytes())

  fun encodeUrl(data: String): String = URLEncoder.encode(data, "UTF-8")

  fun decodeUrl(data: String): String = URLDecoder.decode(data, "UTF-8")

  // ---------------- AES ----------------

  fun aesCrypt(mode: String, key: String, data: String, iv: String? = null): String {
    val keyBytes = key.toByteArray(Charsets.UTF_8)
    val dataBytes: ByteArray
    val encrypt = mode.uppercase().startsWith("E")
    if (encrypt) {
      dataBytes = data.toByteArray(Charsets.UTF_8)
    } else {
      dataBytes = Base64.getDecoder().decode(data)
    }
    val out = aesCryptBytes(encrypt, keyBytes, dataBytes, iv?.toByteArray(Charsets.UTF_8))
    return if (encrypt) Base64.getEncoder().encodeToString(out)
    else String(out, Charsets.UTF_8)
  }

  fun aesCryptBytes(encrypt: Boolean, keyBytes: ByteArray, dataBytes: ByteArray, ivBytes: ByteArray?): ByteArray {
    require(keyBytes.size in intArrayOf(16, 24, 32)) { "aes: key must be 16/24/32 bytes" }
    val transformation = if (ivBytes != null) "AES/CBC/PKCS5Padding" else "AES/ECB/PKCS5Padding"
    val cipher = Cipher.getInstance(transformation)
    val keySpec = SecretKeySpec(keyBytes, "AES")
    if (ivBytes != null) {
      require(ivBytes.size == 16) { "aes: iv must be 16 bytes" }
      cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(ivBytes))
    } else {
      cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, keySpec)
    }
    return cipher.doFinal(dataBytes)
  }

  @JvmOverloads
  fun aesKeygen(size: Int = 16): String {
    val bytes = ByteArray(size)
    java.security.SecureRandom().nextBytes(bytes)
    return String(bytes, Charsets.ISO_8859_1)
  }

  fun aesIvgen(): String = aesKeygen(16)

  // ---------------- RSA（JCA） ----------------

  /** 生成 RSA 密钥对，返回 {publicKey=base64, privateKey=base64} */
  @JvmOverloads
  fun rsaGenerateKey(keySize: Int = 2048): Map<String, String> {
    val kpg = java.security.KeyPairGenerator.getInstance("RSA")
    kpg.initialize(keySize)
    val kp = kpg.generateKeyPair()
    return linkedMapOf(
      "publicKey" to Base64.getEncoder().encodeToString(kp.public.encoded),
      "privateKey" to Base64.getEncoder().encodeToString(kp.private.encoded)
    )
  }

  /** RSA 公钥加密（输入/输出均 Base64），privateKey=true 时用私钥（签名语义） */
  @JvmOverloads
  fun rsaEncrypt(data: String, keyBase64: String, privateKey: Boolean = false): String {
    val key = decodeRsaKey(keyBase64, privateKey)
    val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
    cipher.init(Cipher.ENCRYPT_MODE, key)
    return Base64.getEncoder().encodeToString(cipher.doFinal(data.toByteArray(Charsets.UTF_8)))
  }

  /** RSA 解密（输入 Base64），privateKey=true 表示用私钥解密 */
  @JvmOverloads
  fun rsaDecrypt(dataBase64: String, keyBase64: String, privateKey: Boolean = true): String {
    val key = decodeRsaKey(keyBase64, privateKey)
    val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
    cipher.init(Cipher.DECRYPT_MODE, key)
    return String(cipher.doFinal(Base64.getDecoder().decode(dataBase64)), Charsets.UTF_8)
  }

  private fun decodeRsaKey(keyBase64: String, privateKey: Boolean): java.security.Key {
    val spec = java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(keyBase64))
    return if (privateKey) {
      java.security.KeyFactory.getInstance("RSA")
        .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(keyBase64)))
    } else {
      java.security.KeyFactory.getInstance("RSA").generatePublic(spec)
    }
  }

  // ---------------- LuaEnvContributor：全局别名 ----------------

  override fun contribute(ctx: LuaContext) {
    reg(ctx, "MD5") { L -> ctx.push(md5(L.toString(2) ?: "")); 1 }
    reg(ctx, "fileMD5") { L -> ctx.push(fileMD5(L.toString(2) ?: "")); 1 }
    reg(ctx, "sha256") { L -> ctx.push(sha256(L.toString(2) ?: "")); 1 }
    reg(ctx, "encodeBase64") { L -> ctx.push(encodeBase64(L.toString(2) ?: "")); 1 }
    reg(ctx, "decodeBase64") { L -> ctx.push(decodeBase64(L.toString(2) ?: "")); 1 }
    reg(ctx, "getFileBase64") { L -> ctx.push(getFileBase64(L.toString(2) ?: "")); 1 }
    reg(ctx, "encodeUrl") { L -> ctx.push(encodeUrl(L.toString(2) ?: "")); 1 }
    reg(ctx, "decodeUrl") { L -> ctx.push(decodeUrl(L.toString(2) ?: "")); 1 }
  }

  private fun reg(ctx: LuaContext, name: String, body: (LuaContext) -> Int) {
    ctx.push(object : com.autolua.engine.base.LuaObjectAdapter {
      override fun invoke(L: LuaContext): Int = body(L)
      override fun index(L: LuaContext): Int = 0
      override fun newIndex(L: LuaContext): Int = 0
      override fun call(L: LuaContext, name: String): Int = 0
      override fun release(L: LuaContext) {}
    })
    ctx.setGlobal(name)
  }
}
