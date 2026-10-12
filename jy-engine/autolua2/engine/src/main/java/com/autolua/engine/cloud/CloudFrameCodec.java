package com.autolua.engine.cloud;

import com.autolua.engine.extension.crypto.CryptoLib;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 云控 PSK 帧编解码（Java 下沉，与原 cloud.lua 帧协议逐字兼容；docs/features/cloud/contracts.md §5）。
 * <p>
 * 帧格式 v1：{@code {"e":1,"iv":"<16位hex>","d":"<AES-256-CBC密文Base64>","s":"<sha256前16hex>"}}
 * <ul>
 *   <li>encKey = sha256(psk + "|enc1") 前 32 字符（32 字节 ASCII = AES-256 key）</li>
 *   <li>sigKey = sha256(psk + "|sig1") 前 32 字符；s = sha256(sigKey|iv|d) 前 16 位 hex</li>
 *   <li>key/iv 全程可打印 ASCII（对齐 CryptoLib String(UTF8) 往返约束）；PKCS5/PKCS7 同算法</li>
 * </ul>
 * 兼容矩阵：psk 空 → 明文直通（收 e=1 帧回 err="plaintext"）；psk 有 → 非 e=1 帧回 "encrypted"，
 * 错误帧 {"e":0,"err":..} 恒明文。
 */
public final class CloudFrameCodec {

    /** 解帧结果：ok=true 时 plain 为明文 JSON；ok=false 时 err 为错误语义（plaintext/encrypted/bad frame） */
    public static final class Result {
        public final boolean ok;
        public final String plain;
        public final String err;

        private Result(boolean ok, String plain, String err) {
            this.ok = ok;
            this.plain = plain;
            this.err = err;
        }

        static Result ok(String plain) {
            return new Result(true, plain, null);
        }

        static Result err(String err) {
            return new Result(false, null, err);
        }
    }

    private static final Gson GSON = new Gson();
    /** IV 计数器（跨帧单调，拼进 iv hex 保证同毫秒不重复） */
    private static final AtomicLong IV_COUNTER = new AtomicLong();

    private final String psk;      // null = 明文模式
    private final String encKey;   // 32 字符 hex ASCII
    private final String sigKey;   // 32 字符 hex ASCII
    private final CryptoLib crypto = new CryptoLib();

    private CloudFrameCodec(String psk, String encKey, String sigKey) {
        this.psk = psk;
        this.encKey = encKey;
        this.sigKey = sigKey;
    }

    /**
     * 创建 codec：psk 非空 → 加密模式（派生失败抛 IllegalArgumentException，调用方决定回退明文或报错）；
     * psk 空/null → 明文直通模式。
     */
    public static CloudFrameCodec create(String psk) {
        if (psk == null || psk.isEmpty()) {
            return new CloudFrameCodec(null, null, null);
        }
        CryptoLib crypto = new CryptoLib();
        String encKey = derive(crypto, psk, "enc1");
        String sigKey = derive(crypto, psk, "sig1");
        return new CloudFrameCodec(psk, encKey, sigKey);
    }

    private static String derive(CryptoLib crypto, String psk, String tag) {
        String full = crypto.sha256(psk + "|" + tag);
        if (full == null || full.length() < 32) {
            throw new IllegalArgumentException("sha256 derive failed for " + tag);
        }
        return full.substring(0, 32);
    }

    /** 是否加密模式 */
    public boolean isEncrypted() {
        return psk != null;
    }

    /**
     * 编帧：加密模式 → 组 e/iv/d/s 帧；明文模式 → 原样返回。
     * 加密失败返回 null（调用方回退错误帧，不落明文——对齐原 encodeFrame 语义）。
     */
    public String encode(String plainJson) {
        if (psk == null) {
            return plainJson;
        }
        try {
            String iv = nextIv();
            byte[] plain = plainJson.getBytes(StandardCharsets.UTF_8);
            byte[] ct = crypto.aesCryptBytes(true,
                    encKey.getBytes(StandardCharsets.UTF_8), plain, iv.getBytes(StandardCharsets.UTF_8));
            if (ct == null || ct.length == 0) {
                return null;
            }
            String d = android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP);
            String sig = crypto.sha256(sigKey + "|" + iv + "|" + d);
            if (sig == null) {
                return null;
            }
            JsonObject frame = new JsonObject();
            frame.addProperty("e", 1);
            frame.addProperty("iv", iv);
            frame.addProperty("d", d);
            frame.addProperty("s", sig.substring(0, 16));
            return GSON.toJson(frame);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 解帧（recv 侧）：原始 WS 文本 → 明文 JSON。
     * 明文模式：e=1 帧 → err="plaintext"；其余直通。
     * 加密模式：非 JSON/e≠1 → "encrypted"；s 校验失败/解密失败 → "bad frame"。
     */
    public Result decode(String wire) {
        if (wire == null || wire.isEmpty()) {
            return Result.err("bad frame");
        }
        JsonObject outer;
        try {
            outer = GSON.fromJson(wire, JsonObject.class);
        } catch (Throwable t) {
            outer = null;
        }
        if (psk == null) {
            // 明文服务器：加密帧显式回 plaintext；其余直通（零回归）
            if (outer != null && outer.has("e") && outer.get("e").getAsInt() == 1) {
                return Result.err("plaintext");
            }
            return Result.ok(wire);
        }
        if (outer == null || !outer.has("e") || outer.get("e").getAsInt() != 1) {
            return Result.err("encrypted");
        }
        try {
            String iv = outer.get("iv").getAsString();
            String d = outer.get("d").getAsString();
            String s = outer.get("s").getAsString();
            if (iv.isEmpty() || d.isEmpty() || s.isEmpty()) {
                return Result.err("bad frame");
            }
            String sig = crypto.sha256(sigKey + "|" + iv + "|" + d);
            if (sig == null || !s.equals(sig.substring(0, 16))) {
                return Result.err("bad frame");
            }
            byte[] plain = crypto.aesCryptBytes(false,
                    encKey.getBytes(StandardCharsets.UTF_8),
                    android.util.Base64.decode(d, android.util.Base64.NO_WRAP),
                    iv.getBytes(StandardCharsets.UTF_8));
            if (plain == null || plain.length == 0) {
                return Result.err("bad frame");
            }
            // PKCS5 去填充校验（CryptoLib 底层已处理，此处仅转串）
            return Result.ok(new String(plain, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return Result.err("bad frame");
        }
    }

    /** 组明文错误帧（加密/明文模式共用，恒明文——对齐原 errWire） */
    public static String errorWire(String err) {
        JsonObject o = new JsonObject();
        o.addProperty("e", 0);
        o.addProperty("err", err == null ? "bad frame" : err);
        return GSON.toJson(o);
    }

    /** IV：时间 hex + 微秒 + 计数器，16 位小写 hex（可打印 ASCII，对齐原 nextIv 约束） */
    private static String nextIv() {
        long counter = IV_COUNTER.incrementAndGet() & 0xFFFFL;
        String raw = Long.toHexString(System.currentTimeMillis())
                + Long.toHexString(System.nanoTime() % 0xFFFFFFL)
                + String.format("%04x", counter);
        StringBuilder sb = new StringBuilder(raw.toLowerCase());
        sb.setLength(Math.min(sb.length(), 16));
        while (sb.length() < 16) {
            sb.append('0');
        }
        return sb.toString();
    }
}
