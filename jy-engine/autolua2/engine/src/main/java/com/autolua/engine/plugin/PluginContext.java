package com.autolua.engine.plugin;

import android.os.Build;
import android.util.Log;

import java.io.File;

/**
 * {@link IPluginContext} 默认实现：包装宿主能力供插件调用。
 * <ul>
 *     <li>截图：转调 {@link com.autolua.engine.display.projection.ScreenshotProviderManager} 单例
 *     （engine 内可直接引用，RGBA 帧数据源）；</li>
 *     <li>日志：tag 固定 {@code AutoLuaPlugin:<id>}，logcat 按插件过滤；</li>
 *     <li>so 加载：插件 so 根目录（plugin_libs/&lt;pluginId&gt;/）内按文件名 System.load 绝对路径。</li>
 * </ul>
 */
public class PluginContext implements IPluginContext {

    private static final String TAG_PREFIX = "AutoLuaPlugin:";

    private final String pluginId;
    private final File pluginDir;
    /** 插件 so 根目录（宿主解压 lib/<abi>/*.so 的落点） */
    private final File soDir;

    public PluginContext(String pluginId, File pluginDir, File soDir) {
        this.pluginId = pluginId;
        this.pluginDir = pluginDir;
        this.soDir = soDir;
    }

    @Override
    public byte[] captureScreen(int x, int y, int w, int h) {
        return com.autolua.engine.display.projection.ScreenshotProviderManager
                .getInstance().captureScreen(x, y, w, h);
    }

    @Override
    public byte[] captureFull() {
        return com.autolua.engine.display.projection.ScreenshotProviderManager
                .getInstance().captureFull();
    }

    @Override
    public File getPluginDir() {
        return pluginDir;
    }

    @Override
    public void log(String msg) {
        Log.i(TAG_PREFIX + pluginId, msg);
    }

    @Override
    public void log(String tag, String msg) {
        Log.i(TAG_PREFIX + pluginId + ":" + tag, msg);
    }

    @Override
    public String getAbi() {
        String[] abis = Build.SUPPORTED_ABIS;
        return (abis != null && abis.length > 0) ? abis[0] : "unknown";
    }

    @Override
    public File getNativeLibraryDir() {
        return soDir;
    }

    @Override
    public boolean loadNativeLibrary(String soNameOrPath) {
        try {
            if (soNameOrPath == null || soNameOrPath.trim().isEmpty()) {
                Log.w(tag(), "loadNativeLibrary: name/path is empty");
                return false;
            }
            String name = soNameOrPath.trim();
            File so;
            if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
                // 含路径分隔符：按绝对路径加载（Android 9+ 要求 so 落在 app 私有目录内）
                so = new File(name);
            } else {
                // 纯名称：在插件 so 根目录解析 <name>.so
                if (!name.endsWith(".so")) {
                    name = name + ".so";
                }
                so = new File(soDir, name);
            }
            System.load(so.getAbsolutePath());
            Log.i(tag(), "loaded native library: " + so.getAbsolutePath()
                    + " (" + so.length() + " bytes)");
            return true;
        } catch (Throwable t) {
            // 插件自管降级：宿主不抛出，只记日志
            Log.w(tag(), "loadNativeLibrary failed: " + soNameOrPath + ", " + t);
            return false;
        }
    }

    private String tag() {
        return TAG_PREFIX + pluginId;
    }
}
