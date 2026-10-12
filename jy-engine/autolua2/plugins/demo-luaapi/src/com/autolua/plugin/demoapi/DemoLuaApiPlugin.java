package com.autolua.plugin.demoapi;

import com.autolua.engine.base.LuaContext;
import com.autolua.engine.base.LuaEnvContributor;
import com.autolua.engine.plugin.IPlugin;
import com.autolua.engine.plugin.IPluginContext;
import com.autolua.engine.plugin.PluginLuaHelper;

import java.util.List;

/**
 * 插件 Lua API 贡献示例插件（P1 机制活体案例）。
 * <p>
 * 实现 {@link IPlugin} + {@link LuaEnvContributor}：onInit 后引擎在建 LuaContext 时
 * 回调 {@link #contribute}，经 {@link PluginLuaHelper#putGlobal} 把 {@link Api} 注册为
 * Lua 全局表 {@code plugindemo}。脚本侧直接调用：
 * <pre>
 *     print(plugindemo.hello("world"))   -- → "hello from plugin, world"
 *     print(plugindemo.add(1, 2))        -- → 3
 *     print(plugindemo.pluginDir())      -- → 本插件私有数据目录
 * </pre>
 * 无 so、无模型，仅验证"插件定义函数 → 脚本直接调用"链路。
 */
public class DemoLuaApiPlugin implements IPlugin, LuaEnvContributor {

    private static final String TAG = "DemoLuaApi";

    private volatile IPluginContext ctx;

    @Override
    public void onInit(IPluginContext ctx) {
        this.ctx = ctx;
        ctx.log(TAG, "onInit, abi=" + ctx.getAbi());
    }

    @Override
    public void onRelease() {
        ctx = null;
    }

    @Override
    public String getId() {
        return "com.autolua.plugin.demoapi";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String[] getProvides() {
        return new String[]{"demo"};
    }

    /** Lua API 贡献：注册全局表 plugindemo（Api 的公有方法即 Lua 可见方法面） */
    @Override
    public void contribute(LuaContext lua) {
        IPluginContext c = ctx;
        // Kotlin object 在 Java 侧经 INSTANCE 调用
        PluginLuaHelper.INSTANCE.putGlobal(lua, "plugindemo", new Api(c), Api.class);
        if (c != null) {
            c.log(TAG, "contributed global 'plugindemo'");
        }
    }

    /** 暴露给 Lua 的 API 对象（普通 POJO，JavaObjectWrapper 按此类反射桥接） */
    public static class Api {

        private final IPluginContext ctx;

        public Api(IPluginContext ctx) {
            this.ctx = ctx;
        }

        /** plugindemo.hello(name) → "hello from plugin, <name>" */
        public String hello(String name) {
            return "hello from plugin, " + (name == null ? "anon" : name);
        }

        /** plugindemo.add(a, b) → a + b（数字参数桥接验证） */
        public double add(double a, double b) {
            return a + b;
        }

        /** plugindemo.pluginDir() → 本插件私有数据目录（null-safe） */
        public String pluginDir() {
            IPluginContext c = ctx;
            return c == null ? null : c.getPluginDir().getAbsolutePath();
        }

        /** plugindemo.sum(list) → 数字数组求和（表参数桥接验证，table2List 链路） */
        public double sum(List<Double> numbers) {
            double s = 0;
            if (numbers != null) {
                for (Double n : numbers) {
                    if (n != null) {
                        s += n;
                    }
                }
            }
            return s;
        }
    }
}
