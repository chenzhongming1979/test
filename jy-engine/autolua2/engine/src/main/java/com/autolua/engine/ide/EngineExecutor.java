package com.autolua.engine.ide;

/**
 * IDE 调试通道的引擎执行接口。
 * <p>
 * engine 模块不直接依赖 Lua 引擎（Java↔C++ 引擎桥由后续任务建立），
 * 由 app 层在 Lua 桥接完成后通过 {@code IdeServerManager.attachEngine(executor)} 注入真实实现；
 * 未注入时 {@link EngineExecutorHolder} 保持 no-op 默认实现（{@code isEngineAttached()==false}）。
 */
public interface EngineExecutor {

    /**
     * 输出监听器：接收沙箱脚本逐行输出（print 重定向），
     * 是 [IMG] 图片分片协议与 log 回传通道的数据源。
     */
    interface OutputListener {
        /** 一行脚本输出（print 参数以 \t 拼接，不含换行符）；[IMG] 分片协议行同样经此回调 */
        void onOutputLine(String line);
    }

    /**
     * 设置输出监听器（传 null 取消）。
     * default 空实现：既有实现类无需修改即可编译，真实输出来源由实现方自行接通。
     */
    default void setOutputListener(OutputListener listener) {
    }

    /**
     * 在沙箱中执行一段 Lua 代码（序列约定见 {@link WsProtocol} 的沙箱执行序列注释）：
     * <ol>
     *     <li>pushString(paramsJson) + setGlobal("_WS_PARAMS")</li>
     *     <li>executeScript("_WS_RESULT = nil\n" + code)</li>
     *     <li>getGlobal("_WS_RESULT") 取回结果</li>
     * </ol>
     *
     * @param code 待执行的 Lua 代码
     * @return _WS_RESULT 的值（可为 null，表示脚本未写结果）；
     *         执行失败时返回以 {@link WsProtocol#ERROR_PREFIX} 开头的错误串
     */
    String execute(String code);

    /** 停止脚本 */
    void stop();

    /** 暂停脚本 */
    void pause();

    /** 恢复脚本 */
    void resume();

    /** 引擎是否已就绪（Lua 桥接注入后才为 true） */
    boolean isEngineAttached();
}
