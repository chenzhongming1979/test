package com.autolua.engine.accessibility;

/**
 * IAuxDumpService 单进程直用路径：同进程调用不经 Binder，直接复用
 * {@link AuxDumpServiceImpl}（extends Stub，本地调用走直派发，零序列化开销）。
 *
 * <p>AutoLuaAccessibilityService.onCreate 与 AuxDumpService.onCreate 时预热单例；
 * 跨进程则经 AuxDumpService 绑定后拿同一逻辑实现的 Binder 代理。</p>
 */
public final class AuxDumpServiceLocal {

    private static volatile IAuxDumpService sInstance;

    private AuxDumpServiceLocal() {
    }

    /** 返回同进程可直调的 IAuxDumpService 实现（惰性单例） */
    public static IAuxDumpService get() {
        if (sInstance == null) {
            synchronized (AuxDumpServiceLocal.class) {
                if (sInstance == null) {
                    sInstance = new AuxDumpServiceImpl();
                }
            }
        }
        return sInstance;
    }
}
