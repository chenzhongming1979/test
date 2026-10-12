package com.autolua.engine.plugin;

/**
 * 插件宿主接口（方案七 插件动态加载框架）。
 * <p>
 * 插件包（dex/jar）内的入口类必须实现本接口；宿主经 {@code DexClassLoader}
 * 反射实例化 mainClass 后强转为 {@code IPlugin} 调用。
 * 生命周期：
 * <ol>
 *     <li>安装：{@link #onInit(IPluginContext)} —— 插件在此 {@code System.load} 自己的
 *     so（经 {@link IPluginContext#loadNativeLibrary}）、初始化模型；抛异常即安装失败回滚</li>
 *     <li>卸载/覆盖/进程退出前：{@link #onRelease()}</li>
 * </ol>
 * so 交付模型：宿主只负责把 zip 内 {@code lib/<abi>/*.so} 解压到插件 so 根目录，
 * 加载时机由插件在 onInit 里主动调用 {@link IPluginContext#loadNativeLibrary}（Android 9+
 * 不能直接 System.load apk/zip 内 so，解压到 app 私有目录后按绝对路径 load 是最稳方式）。
 */
public interface IPlugin {

    /** 宿主调：初始化（加载 so / 模型等）；抛异常则安装失败回滚 */
    void onInit(IPluginContext ctx) throws Exception;

    /** 卸载/版本覆盖前调：释放模型、卸载监听等（宿主容忍其异常） */
    void onRelease();

    /** 插件 id，如 "com.autolua.plugin.ocr"（与 manifest.json 的 id 一致） */
    String getId();

    /** 插件版本，如 "1.0.0" */
    String getVersion();

    /** 提供的能力标签，如 {"ocr"}（宿主按标签查找，如 getOcrPlugin） */
    String[] getProvides();
}
