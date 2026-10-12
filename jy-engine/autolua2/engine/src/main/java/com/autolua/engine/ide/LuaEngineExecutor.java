package com.autolua.engine.ide;

import android.util.Log;

import com.autolua.engine.base.JavaObjectWrapper;
import com.autolua.engine.base.LuaContext;
import com.autolua.engine.base.LuaContextImplement;
import com.autolua.engine.base.LuaObjectAdapter;
import com.autolua.engine.base.MethodCache;
import com.autolua.engine.base.ObjectCacheImp;
import com.autolua.engine.core.AutoLuaEngineProxy;

import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IDE 沙箱执行器：基于 {@link LuaContextImplement}（libengine_base.so 现成 JNI）实现
 * {@link EngineExecutor}，在 Java 侧组装 {@link WsProtocol} 注释约定的沙箱执行序列。
 * <p>
 * 选型说明（探索结论）：
 * <ul>
 *     <li>引擎主桥 {@code AutoLuaEngineOnLocal.executeNative} 走 C++ LuaInterpreter，
 *         为异步 fire-and-forget（内部工作线程执行、无结果回调通道），
 *         无法满足 execute 命令「同步取回 _WS_RESULT」的语义；</li>
 *     <li>{@link LuaContextImplement} 的 companion JNI 恰好覆盖沙箱序列所需全部原语：
 *         createLuaContext（luaL_newstate + luaL_openlibs）、loadBuffer、pcall、
 *         push/setGlobal、getGlobal/toString —— 等价于「新增 nativeExecute/nativeSetGlobalString/
 *         nativeGetGlobal」方案，且 C++ 层零改动，故直接复用。</li>
 * </ul>
 * 沙箱为独立 Lua 虚拟机（含全部标准库），与引擎脚本运行环境不共享状态；
 * 执行为同步模型（{@code IdeWebSocketServer} 的消息处理线程池允许阻塞，心跳走独立线程不受影响），
 * 多客户端并发 execute 由内部锁串行化。
 * <p>
 * 二期新增 print 重定向：沙箱全局 print 经 LuaObjectAdapter 覆盖，逐行输出回调
 * {@link OutputListener}（[IMG] 分片协议与 log 回传通道的数据源）。
 * <p>
 * 三期新增：
 * <ul>
 *     <li>沙箱服务注入：{@link #registerService} 把 app 层服务实例包装为
 *         {@link JavaObjectWrapper}（与主引擎 AutoLuaEngineOnLocal.pushLocalServices 的
 *         wrapLocalService 注入序列一致：wrap → push(LuaObjectAdapter) → setGlobal），
 *         注册时立即注入常驻沙箱，execute 的脚本可直接寻址调用（如 UI、FloatController）；</li>
 *     <li>{@link #setRootDir} 注入 ROOT_DIR 全局变量，对齐主引擎 LuaStateFactory 的注入项
 *         （display/input 为 C++ 对象注入，engine_base 无对应 JNI，沙箱侧不可对齐）；</li>
 *     <li>pause/resume 经 {@link LifecycleDelegate} 透传引擎代理（engine 模块不依赖 app，
 *         由 app 层接线 AutoLuaEngineProxy；未接线时为内置 no-op 默认实现）。</li>
 * </ul>
 */
public class LuaEngineExecutor implements EngineExecutor {

    private static final String TAG = "AutoLuaIde";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    /** loadBuffer 的 chunk 名（出现在 Lua 错误消息中，便于定位 IDE 执行的代码） */
    private static final String CHUNK_NAME = "ide_exec";

    /** 独立沙箱 Lua 虚拟机；创建失败时为 null（isEngineAttached 随之为 false） */
    private final LuaContextImplement sandbox;
    /** 串行化对沙箱的访问（lua_State 非线程安全） */
    private final Object lock = new Object();
    /** 脚本输出监听器（print 重定向目标；[IMG] 分片协议行同样经此回传） */
    private volatile OutputListener outputListener;
    /** Java 对象包装为 LuaObjectAdapter 所需的 MethodCache（主引擎注入序列同样以 MethodCache 为前置） */
    private final MethodCache methodCache = new MethodCache();
    /** 服务注册表：name → 服务条目（注册时已同步注入沙箱；重复注册按 setGlobal 语义覆盖） */
    private final Map<String, ServiceEntry> serviceRegistry = new LinkedHashMap<>();

    /** 节点域全局是否已装配（惰性：无障碍服务连接后首个 execute 补装） */
    private volatile boolean nodeDomainInstalled = false;

    /** pause/resume 生命周期委托：engine 模块不依赖 app，经此回调透传到引擎代理 */
    public interface LifecycleDelegate {
        /** 暂停引擎侧正在执行的脚本（挂起点生效） */
        void pause();
        /** 恢复引擎侧被暂停的脚本 */
        void resume();
    }

    /** 内置默认委托：no-op（app 层未接线时保持静默） */
    private static final LifecycleDelegate NOOP_DELEGATE = new LifecycleDelegate() {
        @Override
        public void pause() {
            Log.d(TAG, "pause: lifecycle delegate not set, no-op");
        }

        @Override
        public void resume() {
            Log.d(TAG, "resume: lifecycle delegate not set, no-op");
        }
    };

    private volatile LifecycleDelegate lifecycleDelegate = NOOP_DELEGATE;

    /** 服务注册表条目：实例 + 暴露给 Lua 的接口 */
    private static final class ServiceEntry {
        final Object service;
        final Class<?> iface;

        ServiceEntry(Object service, Class<?> iface) {
            this.service = service;
            this.iface = iface;
        }
    }

    public LuaEngineExecutor() {
        LuaContextImplement impl = null;
        try {
            // 构造内部经 createLuaContext JNI 创建 lua_State 并打开全部标准库
            impl = new LuaContextImplement(new ObjectCacheImp(), new MethodCache());
        } catch (Throwable t) {
            Log.e(TAG, "create ide lua sandbox failed", t);
        }
        this.sandbox = impl;
        if (impl != null) {
            installPrintRedirect(impl);
            // 节点只读桥（懒人 nodeLib 命名对齐）：供 VSCode 插件 EXECUTE 通道
            // 调用 nodeLib.getNodeXml() 抓取节点树；仅只读能力，见 NodeLibBridge
            try {
                registerService("nodeLib", new com.autolua.engine.extension.node.NodeLibBridge(),
                        com.autolua.engine.extension.node.NodeLibBridge.class);
            } catch (Throwable t) {
                Log.w(TAG, "register nodeLib bridge failed: " + t);
            }
        }
    }

    // ------------------------------------------------------------------
    // EngineExecutor
    // ------------------------------------------------------------------

    @Override
    public String execute(String code) {
        if (sandbox == null) {
            return WsProtocol.ERROR_PREFIX + "lua sandbox unavailable";
        }
        synchronized (lock) {
            try {
                // ⓪ 节点域全局惰性装配：无障碍服务连接后首个 execute 自动补装
                //   （与 root 引擎 NodeDomainInstaller 同源，三环境一致；服务未连接时跳过，
                //    节点全局缺席由 compat 守卫给出中文提示）
                if (!nodeDomainInstalled
                        && com.autolua.engine.accessibility.AutoLuaAccessibilityService.isRunning()) {
                    nodeDomainInstalled = com.autolua.engine.extension.node.NodeDomainInstaller.INSTANCE
                            .installForSandboxIfReady(sandbox);
                }

                // ① 传入 _WS_PARAMS（execute 命令一期 data 仅含 code，params 固定空串占位，
                //    保证脚本侧 _WS_PARAMS 全局变量始终存在）
                sandbox.push("");
                sandbox.setGlobal(WsProtocol.GLOBAL_WS_PARAMS);

                // ② 先清空 _WS_RESULT 再执行用户代码
                String script = WsProtocol.GLOBAL_WS_RESULT + " = nil\n" + code;
                sandbox.loadBuffer(script.getBytes(UTF_8), CHUNK_NAME, LuaContext.CodeMode.TEXT);
                int r = sandbox.pcall(0, 0, 0);
                if (r != 0) {
                    // pcall 失败时错误消息压在栈顶；错误对象非字符串（table 等）时
                    // toString JNI 会抛 LuaTypeError，此处兜底为通用错误描述
                    String msg;
                    try {
                        msg = sandbox.toString(-1);
                    } catch (Exception e) {
                        msg = "lua error (pcall code=" + r + ")";
                    }
                    sandbox.pop(1);
                    return WsProtocol.ERROR_PREFIX + (msg != null ? msg : "lua error");
                }

                // ③ 取回 _WS_RESULT
                sandbox.getGlobal(WsProtocol.GLOBAL_WS_RESULT);
                LuaContext.ValueType t = sandbox.type(-1);
                String result;
                switch (t) {
                    case STRING:
                    case NUMBER:
                        // lua_tolstring 对 number 原位转字符串（如 _WS_RESULT = 1+1 → "2"）
                        result = sandbox.toString(-1);
                        break;
                    case BOOLEAN:
                        result = sandbox.toBoolean(-1) ? "true" : "false";
                        break;
                    case NIL:
                    case NONE:
                        result = null; // 脚本未写结果 → 回不带 data 的 result
                        break;
                    default:
                        // table/function/userdata 等不可序列化类型，回类型名占位（后续可扩展 JSON 序列化）
                        result = "<" + t.name().toLowerCase() + ">";
                        break;
                }
                sandbox.pop(1);
                return result;
            } catch (Throwable e) {
                Log.w(TAG, "ide sandbox execute failed: " + e);
                String msg = e.getMessage();
                return WsProtocol.ERROR_PREFIX
                        + (msg != null && !msg.isEmpty() ? msg : e.getClass().getSimpleName());
            }
        }
    }

    @Override
    public void stop() {
        // 映射引擎状态机：向引擎侧正在运行的脚本发 interrupt
        // （LuaInterpreter 在 sleep/长操作点抛 "interrupt" 错误；对沙箱自身同步执行的短代码无中断点）
        try {
            // Kotlin companion 属性，Java 侧经 Companion 访问
            AutoLuaEngineProxy.Companion.getInstance().interrupt();
        } catch (Exception e) {
            Log.w(TAG, "stop: engine interrupt failed: " + e);
        }
    }

    @Override
    public void pause() {
        // 三期：经 delegate 透传到引擎代理（AutoLuaEngineProxy.pause → 引擎 pause →
        // C++ LuaInterpreter pause 信号，脚本在 sleep 等挂起点响应）
        lifecycleDelegate.pause();
    }

    @Override
    public void resume() {
        // 三期：同 pause，经 delegate 透传
        lifecycleDelegate.resume();
    }

    @Override
    public boolean isEngineAttached() {
        return sandbox != null;
    }

    @Override
    public void setOutputListener(OutputListener listener) {
        this.outputListener = listener;
    }

    // ------------------------------------------------------------------
    // print 重定向
    // ------------------------------------------------------------------

    /**
     * 把沙箱全局 print 重定向到 {@link #outputListener}（每行输出经 listener 回传）。
     * <p>
     * 探索结论：LuaContextImplement 无内置 print hook / setOutput 类 API；
     * 但 {@code push(LuaObjectAdapter) + setGlobal} 可覆盖全局 print。
     * C++ 侧 LuaObjectAdapter 元表 {@code __call = invokeMethod}，调用时栈布局
     * index 1 = adapter 本体、参数从 index 2 起（与 KotlinFunctionWrapper 同约定），
     * invoke 返回值 = 压栈的结果个数（print 返回 0 个）。
     */
    private void installPrintRedirect(LuaContextImplement sandbox) {
        try {
            sandbox.push(new LuaObjectAdapter() {
                @Override
                public int invoke(LuaContext L) {
                    try {
                        int top = L.getTop();
                        StringBuilder sb = new StringBuilder();
                        for (int i = 2; i <= top; i++) {
                            if (sb.length() > 0) {
                                sb.append('\t');
                            }
                            String s = L.toString(i);
                            sb.append(s != null ? s : "nil");
                        }
                        OutputListener l = outputListener;
                        if (l != null && sb.length() > 0) {
                            l.onOutputLine(sb.toString());
                        }
                    } catch (Throwable ignored) {
                        // print 永不抛错（避免经 C++ catchAndPushJavaThrowable 转成 Lua 错误中断脚本）
                    }
                    return 0;
                }

                @Override
                public int index(LuaContext L) {
                    return 0;
                }

                @Override
                public int newIndex(LuaContext L) {
                    return 0;
                }

                @Override
                public int call(LuaContext L, String name) {
                    return 0;
                }

                @Override
                public void release(LuaContext L) {
                }
            });
            sandbox.setGlobal("print");
        } catch (Throwable t) {
            Log.w(TAG, "install print redirect failed: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 沙箱服务注入（三期）
    // ------------------------------------------------------------------

    /**
     * 注册 app 层服务为沙箱全局变量（自动探测暴露接口：取实现类的首个接口，
     * 无接口时按具体类包装）。注册即注入常驻沙箱，execute 的脚本立即可寻址。
     * <p>
     * 注入序列对齐主引擎 {@code AutoLuaEngineOnLocal.wrapLocalService/pushLocalServices}：
     * 服务对象（非 LuaObjectAdapter 时）包装为 {@link JavaObjectWrapper}(接口, 实例, methodCache)，
     * 经 push(LuaObjectAdapter) + setGlobal(name) 绑定为同名全局变量。
     *
     * @param name    Lua 全局变量名（建议与主引擎 addLocalService 的服务名一致，如 "UI"）
     * @param service 服务实例（app 层与主引擎共享同一实例）
     */
    public void registerService(String name, Object service) {
        registerService(name, service, null);
    }

    /**
     * 注册 app 层服务为沙箱全局变量（显式指定暴露给 Lua 的接口，语义与主引擎
     * {@code Client.Builder.addLocalService(name, service, thisInterface)} 一致）。
     *
     * @param thisInterface 服务暴露给 Lua 调用的接口（决定 Lua 侧可见的方法集）；null 时自动探测
     */
    public void registerService(String name, Object service, Class<?> thisInterface) {
        if (name == null || name.isEmpty() || service == null) {
            Log.w(TAG, "registerService: invalid arguments (name=" + name + ")");
            return;
        }
        synchronized (lock) {
            serviceRegistry.put(name, new ServiceEntry(service, thisInterface));
            injectService(name, service, thisInterface);
        }
    }

    /**
     * 注入 ROOT_DIR 全局变量（对齐主引擎 C++ LuaStateFactory::create 的 ROOT_DIR 注入，
     * 供沙箱脚本 loadfile 等相对路径解析使用）。
     */
    public void setRootDir(String rootDir) {
        if (rootDir == null || rootDir.isEmpty()) {
            return;
        }
        if (sandbox == null) {
            Log.w(TAG, "setRootDir: lua sandbox unavailable");
            return;
        }
        synchronized (lock) {
            try {
                sandbox.push(rootDir);
                sandbox.setGlobal("ROOT_DIR");
            } catch (Throwable t) {
                Log.w(TAG, "setRootDir('" + rootDir + "') failed: " + t);
            }
        }
    }

    /**
     * 插件 Lua API 贡献（对齐主引擎 pushLocalServices 末尾的插件贡献）：app 层在
     * 插件 loadAll 之后调用一次，实现 {@link com.autolua.engine.base.LuaEnvContributor}
     * 的插件向沙箱注入自定义全局（见 PluginLuaHelper）。插件后装需再次调用或重建沙箱。
     */
    public void contributePlugins() {
        if (sandbox == null) {
            Log.w(TAG, "contributePlugins: lua sandbox unavailable");
            return;
        }
        synchronized (lock) {
            try {
                com.autolua.engine.plugin.PluginManager.getInstance().contributeLua(sandbox);
            } catch (Throwable t) {
                Log.w(TAG, "contributePlugins failed: " + t);
            }
        }
    }

    /**
     * 设置 pause/resume 生命周期委托（app 层传 AutoLuaEngineProxy 实现）；
     * 传 null 恢复内置 no-op 默认实现。
     */
    public void setLifecycleDelegate(LifecycleDelegate delegate) {
        this.lifecycleDelegate = delegate != null ? delegate : NOOP_DELEGATE;
    }

    /** 把服务实例包装后注入沙箱为同名全局变量（调用方需已持有 {@link #lock}） */
    private void injectService(String name, Object service, Class<?> thisInterface) {
        if (sandbox == null) {
            Log.w(TAG, "injectService('" + name + "'): lua sandbox unavailable");
            return;
        }
        try {
            Object adapter = service;
            if (!(service instanceof LuaObjectAdapter)) {
                Class<?> iface = thisInterface != null ? thisInterface : detectInterface(service);
                if (iface == null) {
                    // 无接口实现：按具体类包装（JavaObjectWrapper 走反射调用具体类方法）
                    iface = service.getClass();
                }
                adapter = new JavaObjectWrapper(iface, service, methodCache);
        }
        sandbox.push((LuaObjectAdapter) adapter);
        sandbox.setGlobal(name);
        // LuaEnvContributor：服务可自行注册全局函数/常量（与主引擎 pushLocalServices 行为一致）
        if (service instanceof com.autolua.engine.base.LuaEnvContributor) {
            try {
                ((com.autolua.engine.base.LuaEnvContributor) service).contribute(sandbox);
            } catch (Throwable t) {
                Log.w(TAG, "contribute lua env from '" + name + "' failed: " + t);
            }
        }
            Log.d(TAG, "inject service '" + name + "' into sandbox ("
                    + service.getClass().getName() + ")");
        } catch (Throwable t) {
            Log.w(TAG, "inject service '" + name + "' into sandbox failed: " + t);
        }
    }

    /** 自动探测服务暴露接口：取实现类首个直接接口（无则返回 null，由调用方回退具体类） */
    private static Class<?> detectInterface(Object service) {
        Class<?>[] ifaces = service.getClass().getInterfaces();
        return ifaces.length > 0 ? ifaces[0] : null;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 释放沙箱 Lua 虚拟机（进程生命周期内可不调用；调用后执行器不可再用） */
    public void destroy() {
        synchronized (lock) {
            if (sandbox != null) {
                sandbox.destroy();
            }
        }
    }
}
