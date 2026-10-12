package com.autolua.engine.input.inject;

/**
 * 输入注入通道模式（对照派大星 TouchShellClient.Mode:30-35，
 * 按方案二 3.3.1 扩展 ACCESSIBILITY 通道位）。
 */
public enum InjectMode {
    /** 进程内直接注入（uid==0 且进程内反射可达，零 fork，最强） */
    DIRECT_ROOT,
    /** root app_process 常驻子进程注入 */
    ROOT,
    /** Shizuku UserService 注入（shell uid 2000，免 root 次优） */
    SHIZUKU,
    /** 无障碍手势注入（另一 Agent 实现中，门面预留接口位） */
    ACCESSIBILITY,
    /** 无可用通道 */
    NONE
}
