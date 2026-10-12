package com.autolua.engine.ide;

/**
 * {@link EngineExecutor} 静态持有器。
 * <p>
 * 默认挂 no-op 实现（{@code isEngineAttached()==false}，execute 回 {@code __ERROR:engine not attached}）；
 * Lua 桥接完成后由 app 层调用 {@code IdeServerManager.attachEngine(executor)} 注入真实实现，
 * 调用 {@link #clear()} 可恢复 no-op。
 */
public final class EngineExecutorHolder {

    private EngineExecutorHolder() {
    }

    /** 默认空实现：所有生命周期方法 no-op，execute 返回错误串 */
    private static final EngineExecutor NOOP = new EngineExecutor() {
        @Override
        public String execute(String code) {
            return WsProtocol.ERROR_PREFIX + "engine not attached";
        }

        @Override
        public void stop() {
        }

        @Override
        public void pause() {
        }

        @Override
        public void resume() {
        }

        @Override
        public boolean isEngineAttached() {
            return false;
        }
    };

    private static volatile EngineExecutor executor = NOOP;

    /** 注入实现；传 null 等价于 {@link #clear()} */
    public static void set(EngineExecutor impl) {
        executor = impl != null ? impl : NOOP;
    }

    /** 清除注入，恢复 no-op 默认实现 */
    public static void clear() {
        executor = NOOP;
    }

    /** 当前实现（永不为 null） */
    public static EngineExecutor get() {
        return executor;
    }
}
