package com.autolua.engine.ide;

import android.content.Context;
import android.util.Log;

import com.autolua.engine.display.stream.ScreenStreamController;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * IDE 调试服务器单例门面：start / stop / restart、isRunning、getPort、attachEngine。
 * <p>
 * app 层接入示例：
 * <pre>
 *   IdeServerManager.getInstance().setApplicationContext(context);
 *   IdeServerManager.getInstance().start();          // 8080 占用自动回退 8081..8085
 *   IdeServerManager.getInstance().attachEngine(executor); // Lua 桥接完成后注入
 * </pre>
 */
public final class IdeServerManager {

    private static final String TAG = "AutoLuaIde";

    private static volatile IdeServerManager sInstance;

    private final IdeMessageRouter router;
    private volatile IdeWebSocketServer server;
    private volatile Context appContext;
    /** 连接断开回调（可配置；默认 null —— 不停止脚本） */
    private volatile Runnable disconnectListener;
    /** [IMG] 输出分片组装器（组装完成经 {@link #broadcast} 回传 image 包） */
    private final ImageChunkAssembler imageAssembler = new ImageChunkAssembler(this::broadcast);
    /** run_script 主引擎执行委托（app 层接线；null 时 run_script 回 error） */
    private volatile ScriptRunDelegate scriptRunDelegate;
    /** run_script 路径解析根（主引擎项目根 ROOT_DIR，MainApp 接线时注入） */
    private volatile String rootDir;
    /** UI 热重载触发委托（9.11 调试通道整合，app 层接线；null 时 ui_debug_* 回 error） */
    private volatile UiDebugDelegate uiDebugDelegate;

    private IdeServerManager() {
        router = new IdeMessageRouter((clientId, message) -> {
            IdeWebSocketServer s = server;
            if (s != null) {
                s.sendToClient(clientId, message);
            }
        });
        // 投屏发送通道（方案八）：binary 流帧 / stream_rotate 文本事件，均动态解析当前 server
        ScreenStreamController.getInstance().setSender(new ScreenStreamController.ConnectionSender() {
            @Override
            public boolean sendBinary(String clientId, byte[] data) {
                return IdeServerManager.this.sendBinaryToClient(clientId, data);
            }

            @Override
            public void sendText(String clientId, String message) {
                IdeWebSocketServer s = server;
                if (s != null) {
                    s.sendToClient(clientId, message);
                }
            }
        });
        registerBuiltinHandlers();
    }

    public static synchronized IdeServerManager getInstance() {
        if (sInstance == null) {
            sInstance = new IdeServerManager();
        }
        return sInstance;
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    /** 注入应用上下文（hello 读版本信息、推送文件落盘依赖；engine 自身无 app context） */
    public void setApplicationContext(Context context) {
        this.appContext = context != null ? context.getApplicationContext() : null;
    }

    /** 配置连接断开回调（全部连接断开后触发；默认不停止脚本，传 null 恢复默认） */
    public void setDisconnectListener(Runnable listener) {
        this.disconnectListener = listener;
    }

    /**
     * 注入 run_script 主引擎执行委托（方案八 9.5，app 层接线：
     * MainApp 传 AutoLuaEngineProxy.execute("loadfile('<abs>')()") 实现）；
     * 传 null 恢复未接线状态（run_script 回 error）。
     */
    public void setScriptRunDelegate(ScriptRunDelegate delegate) {
        this.scriptRunDelegate = delegate;
    }

    /** run_script 委托读取（capabilities 的 run_script 能力判定也用） */
    public ScriptRunDelegate getScriptRunDelegate() {
        return scriptRunDelegate;
    }

    /**
     * 注入主引擎项目根 ROOT_DIR（方案八 9.5：run_script 相对路径解析基准，
     * 取 MainApp 已注入沙箱的同款值 {@code <externalFilesDir>/project}）。
     */
    public void setRootDir(String rootDir) {
        this.rootDir = rootDir;
    }

    /**
     * 注入 UI 热重载触发委托（9.11 调试通道整合：MainApp 实现
     * DebugActivity 拉起/关闭，原老通道 DebugServiceImp 逻辑迁移）；传 null 恢复未接线状态。
     */
    public void setUiDebugDelegate(UiDebugDelegate delegate) {
        this.uiDebugDelegate = delegate;
    }

    /** UI 热重载委托读取 */
    public UiDebugDelegate getUiDebugDelegate() {
        return uiDebugDelegate;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 启动服务器：优先 {@link WsProtocol#DEFAULT_PORT}，占用自动回退 {8081..{@link WsProtocol#MAX_PORT}}。
     *
     * @return 已在运行或启动成功返回 true；候选端口全部占用返回 false
     */
    public synchronized boolean start() {
        if (isRunning()) {
            return true;
        }
        int port = findAvailablePort();
        if (port < 0) {
            Log.e(TAG, "no available port in [" + WsProtocol.DEFAULT_PORT + "," + WsProtocol.MAX_PORT + "]");
            return false;
        }
        IdeWebSocketServer s = new IdeWebSocketServer(port, router, appContext);
        s.setDisconnectListener(disconnectListener);
        s.start();
        server = s;
        Log.d(TAG, "ide server starting on port " + port);
        return true;
    }

    /** 停止服务器（幂等） */
    public synchronized void stop() {
        IdeWebSocketServer s = server;
        server = null;
        if (s == null) {
            return;
        }
        try {
            s.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "ide server stop interrupted");
        }
        Log.d(TAG, "ide server stopped");
    }

    /** 重启：先 stop 再 start，返回 start 结果 */
    public synchronized boolean restart() {
        stop();
        return start();
    }

    /** 是否运行中（start 后、stop 前为 true） */
    public boolean isRunning() {
        return server != null;
    }

    /** 当前监听端口；未运行返回 -1 */
    public int getPort() {
        IdeWebSocketServer s = server;
        return s != null ? s.getPort() : -1;
    }

    /** 当前在线客户端数；未运行返回 0（并发计数由 ConcurrentHashMap 保证） */
    public int getClientCount() {
        IdeWebSocketServer s = server;
        return s != null ? s.getClientCount() : 0;
    }

    /** 第一个在线客户端 ID（无连接返回 null；设备端投屏快捷开关订阅用） */
    public String getFirstClientId() {
        IdeWebSocketServer s = server;
        return s != null ? s.getFirstClientId() : null;
    }

    // ------------------------------------------------------------------
    // 引擎接入
    // ------------------------------------------------------------------

    /**
     * 注入引擎执行器（Lua 桥接完成后由 app 层调用；传 null 恢复 no-op）。
     * 实际写入 {@link EngineExecutorHolder}，供各命令处理器读取。
     * 二期：同时挂输出监听器——脚本 print 逐行回传（log 包），[IMG] 分片行交
     * {@link ImageChunkAssembler} 组装后广播 image 包。
     */
    public void attachEngine(EngineExecutor executor) {
        EngineExecutorHolder.set(executor);
        if (executor != null) {
            executor.setOutputListener(this::dispatchScriptOutput);
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private Context appContext() {
        return appContext;
    }

    /** 注册一期/二期内置命令处理器 */
    private void registerBuiltinHandlers() {
        new ExecuteHandler().register(router);
        new ScriptLifecycleHandler().register(router);
        new PushFilesHandler(this::appContext).register(router);
        new FileMd5Handler(this::appContext).register(router);
        new DeleteFilesHandler(this::appContext).register(router);
        new PingHandler().register(router);
        // 二期：push_zip 分块推送、图色远程命令、能力协商
        new PushZipHandler(this::appContext).register(router);
        new VisionCommandHandler(this::appContext).register(router);
        new CapabilitiesHandler(router, () -> rootDir).register(router);
        // 四期：插件动态加载（plugin_push_* / plugin_list / plugin_remove）
        new PluginCommandHandler(this::appContext).register(router);
        // 五期（方案八）：远程操控 + 投屏 + run_script 主引擎通道
        new InjectCommandsHandler(this::appContext).register(router);
        new StreamCommandsHandler().register(router);
        new RunScriptHandler(() -> scriptRunDelegate, () -> rootDir, this::appContext).register(router);
        // 六期（9.11 调试通道整合）：UI 热重载触发迁移到新通道
        new UiDebugHandler(() -> uiDebugDelegate).register(router);
    }

    /**
     * 脚本输出分流：[IMG] 前缀行交分片组装器，其余行设备端落盘 + 广播 log 包
     * {@code {"type":"log","data":{"line":...}}}。
     * 注：云控实时日志不在此广播——cloudServer 与脚本同进程（root/app），本类固定在 app 进程，
     * 跨进程够不到；由 cloud.lua boot 内的 print 包装经 cloudServer 广播（进程内一致）。
     */
    private void onOutputLine(String line) {
        if (line != null && line.startsWith(WsProtocol.IMG_PREFIX)) {
            imageAssembler.feed(line);
            return;
        }
        // 设备端日志落盘（ScriptLogPersister：Pictures 优先/应用目录兜底，按天滚动）
        ScriptLogPersister.getInstance().append(line);
        JsonObject data = new JsonObject();
        data.addProperty("line", line);
        JsonObject msg = new JsonObject();
        msg.addProperty("type", WsProtocol.MSG_LOG);
        msg.add("data", data);
        broadcast(msg.toString());
    }

    /** 脚本日志专用单线程：WS 广播可能因背压变慢，异步化避免阻塞传输读线程/脚本线程；单线程保证帧序 */
    private final ExecutorService scriptLogExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ide-script-log");
        t.setDaemon(true);
        return t;
    });

    /**
     * 主引擎脚本输出入口（app 层 IdeLog LocalService 接线）：
     * run_script 提交的主引擎脚本 print 经此分流到 IDE log 帧
     * （老 8176 通道退役后主引擎 print 的唯一回传路径）。
     * 异步有序派发：调用方（传输读线程）只入队，广播在专用线程串行执行。
     */
    public void dispatchScriptOutput(String line) {
        scriptLogExecutor.execute(() -> onOutputLine(line));
    }

    /**
     * 广播主引擎脚本状态帧 {@code {"type":"script_state","data":{"state":"running"|"idle"}}}
     * （MainApp 的 WORKER 状态观察者接线；IDE 端据此切换运行/停止按钮）。
     */
    public void broadcastScriptState(String state) {
        JsonObject data = new JsonObject();
        data.addProperty("state", state);
        JsonObject msg = new JsonObject();
        msg.addProperty("type", WsProtocol.MSG_SCRIPT_STATE);
        msg.add("data", data);
        broadcast(msg.toString());
    }

    /** 向全部在线连接广播文本消息（服务器未运行时静默丢弃） */
    private void broadcast(String message) {
        IdeWebSocketServer s = server;
        if (s != null) {
            s.broadcast(message);
        }
    }

    /**
     * 定向发送二进制帧（方案八投屏流数据，帧格式见 WsProtocol 注释）。
     *
     * @return 发送成功 true；服务器未运行/连接已断/发送异常 false
     */
    boolean sendBinaryToClient(String clientId, byte[] data) {
        IdeWebSocketServer s = server;
        return s != null && s.sendBinaryToClient(clientId, data);
    }

    /** 依次探测 8080..8085，返回第一个可用端口；全部占用返回 -1 */
    private static int findAvailablePort() {
        for (int port = WsProtocol.DEFAULT_PORT; port <= WsProtocol.MAX_PORT; port++) {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(port));
                return port;
            } catch (IOException ignored) {
            } finally {
                if (ss != null) {
                    try {
                        ss.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        return -1;
    }
}
