package com.autolua.engine.plugin;

import java.io.File;

/**
 * 宿主 → 插件的能力注入接口：onInit 时由宿主构造并传入。
 * <p>
 * 能力面刻意收敛（截图帧 / 私有目录 / 日志 / ABI / so 加载），
 * 插件不持有 Context、不触碰宿主内部组件。
 */
public interface IPluginContext {

    /**
     * 按矩形截屏（RGBA 像素流，w*h*4 字节）。
     *
     * @return 帧数据；截图通道未启动/无帧时返回 null
     */
    byte[] captureScreen(int x, int y, int w, int h);

    /** 整屏截帧（RGBA 像素流）；无帧返回 null */
    byte[] captureFull();

    /** 插件私有数据目录（宿主创建，卸载时清理）：plugin_data/&lt;pluginId&gt;/ */
    File getPluginDir();

    /** 打日志（tag 自动带插件 id 前缀，便于 logcat 过滤） */
    void log(String msg);

    /** 打日志（自定义子 tag） */
    void log(String tag, String msg);

    /** 设备主 ABI（Build.SUPPORTED_ABIS[0]），插件初始化时选 so 用 */
    String getAbi();

    /**
     * 插件 so 根目录（zip 内 {@code lib/<abi>/*.so} 的宿主解压目标，平铺）。
     * 插件可经自身 classloader 对其中的 so 做 {@code System.load(绝对路径)}——
     * 库注册到插件类加载器，native 方法可正常绑定。
     */
    File getNativeLibraryDir();

    /**
     * 加载宿主已解压到位的插件 so（方案七 so 交付模型：宿主解压、插件主动加载）。
     * <p>
     * 传纯名称时在插件 so 根目录解析 {@code <name>.so}（名称需与 zip 内
     * {@code lib/<abi>/} 的文件名一致，如 {@code loadNativeLibrary("libpaddle_lite")}）；
     * 传含路径分隔符的绝对路径时按绝对路径 {@code System.load}。
     * <p>
     * <b>注意</b>：本方法的 {@code System.load} 调用方是宿主类，库会注册到 app
     * classloader；ART 按<b>类加载器</b>匹配 native 方法所属库——插件类（DexClassLoader）
     * 的 native 方法绑定不到宿主注册的库。需要绑定插件自身 native 方法时，请改在
     * 插件代码内直接 {@code System.loadLibrary}（DexClassLoader 搜索路径由宿主注入，
     * 含宿主 nativeLibraryDir + 插件 so 根目录）。本方法适用于"纯资源型 so"或
     * 宿主已注册过、仅需共享符号的场景。
     *
     * @return true 加载成功；false 失败（宿主已记日志，插件应自行降级）
     */
    boolean loadNativeLibrary(String soNameOrPath);
}
