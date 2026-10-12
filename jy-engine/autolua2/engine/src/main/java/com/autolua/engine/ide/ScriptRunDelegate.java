package com.autolua.engine.ide;

/**
 * run_script 主引擎执行委托（方案八 9.5，注入模式同 {@link LuaEngineExecutor.LifecycleDelegate}）。
 * <p>
 * engine 模块不依赖 app 模块，远程启动工程脚本必须由 app 层接线：
 * MainApp 注入实现，内部调 {@code AutoLuaEngineProxy.instance.execute("loadfile('<absPath>')()")}
 * （对齐 app 现有 startTask 的 {@code loadfile('init.lua')() loadfile('main.lua')()} 模式），
 * 走主引擎完整环境（root 进程注入服务 + C++ 图色绑定），非沙箱。
 * <p>
 * 未接线时 {@code run_script} 回 error "script run delegate not set"。
 */
public interface ScriptRunDelegate {

    /**
     * 向主引擎提交脚本执行（异步 fire-and-forget）。
     *
     * @param absPath 脚本绝对路径（设备端已由 ROOT_DIR/ide_push 解析拼好，文件已确认存在）
     * @return 提交成功返回 true（不代表脚本执行成功，执行日志经主引擎输出回调回传）
     */
    boolean runScript(String absPath);
}
