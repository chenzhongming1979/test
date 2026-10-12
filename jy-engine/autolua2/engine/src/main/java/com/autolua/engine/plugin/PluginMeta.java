package com.autolua.engine.plugin;

import com.google.gson.annotations.SerializedName;

/**
 * 插件包清单（对应插件包 zip 内 manifest.json，gson 反序列化）。
 * <p>
 * 字段定义详见 docs/04-插件包规范.md；{@code sha256} 为"内容清单 hash"——
 * 对除 manifest.json 外所有 zip entry 按名称排序后逐条
 * {@code name:size:entrySha256} 串接再整体 sha256（计算规则见 spec）。
 */
public class PluginMeta {

    /** 插件 id（必填），如 "com.autolua.plugin.ocr.demo"；仅允许 [A-Za-z0-9._-] 且不含 ".." */
    public String id;
    /** 插件显示名（可选） */
    public String name;
    /** 插件版本（必填），如 "1.0.0"；作为安装版本目录名 */
    @SerializedName(value = "version", alternate = {"versionName"})
    public String version;
    /** 入口类全限定名（必填），实现 IPlugin */
    @SerializedName(value = "mainClass", alternate = {"main"})
    public String mainClass;
    /** 提供的能力标签（建议必填），如 ["ocr"] */
    public String[] provides;
    /** 最低宿主版本要求（可选，当前框架未校验，预留） */
    public String minHostVersion;
    /** 内容清单 sha256（必填，hex 小写，见 docs/04-插件包规范.md 计算规则） */
    public String sha256;
    /** 内容清单 sha256 的 ECDSA P-256 签名（base64 DER ECDSA-Sig-Value，可选——
     *  宿主注入验签公钥后为必填，见 PluginManager.setSignaturePublicKeyHex） */
    public String signature;

    /** provides 是否包含指定能力标签 */
    public boolean provides(String tag) {
        if (provides == null || tag == null) {
            return false;
        }
        for (String p : provides) {
            if (tag.equals(p)) {
                return true;
            }
        }
        return false;
    }
}
