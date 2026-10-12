package com.autolua.engine.input.inject;

/**
 * 输入注入通道统一接口（四级降级链中每个通道都要实现）。
 * <p>
 * 实现类：
 * <ul>
 *     <li>{@link DirectRootChannel} —— uid==0 进程内直接反射注入</li>
 *     <li>{@link RootInjectorChannel} —— su + app_process 常驻子进程（stdin/stdout 行协议）</li>
 *     <li>{@link ShizukuInjectorChannel} —— Shizuku UserService（shell uid 2000）</li>
 *     <li>ACCESSIBILITY —— 另一 Agent 实现中（见 InjectShellClient 的反射兜底与 TODO）</li>
 * </ul>
 */
public interface IInjectChannel {

    /** 启动/连接通道（幂等，已启动直接返回 true） */
    boolean start();

    /** 释放通道资源（关闭进程/解绑服务） */
    void stop();

    /** 通道当前是否可用（已连接且注入核心就绪） */
    boolean isAvailable();

    /** 通道名（日志与降级链展示用） */
    String name();

    /**
     * 按下指定指。
     *
     * @param pointerId 指编号 0..9
     * @param x/y       落点坐标
     * @param downTime  手势统一的 downTime（uptimeMillis）；<=0 时由实现取当前时间
     */
    boolean injectDown(int pointerId, float x, float y, long downTime);

    /** 移动指定指（实现方可做人化插值） */
    boolean injectMove(int pointerId, float x, float y, long downTime);

    /** 抬起指定指（x/y 传 0 表示沿用最后位置） */
    boolean injectUp(int pointerId, float x, float y, long downTime);

    /** 单击（人化：默认按压 50ms） */
    boolean tap(float x, float y);

    /** 滑动（steps 均分 durationMs，durationMs<=0 时用默认 300ms） */
    boolean swipe(float x1, float y1, float x2, float y2, int durationMs);

    /**
     * 双指捏合/张开。
     *
     * @param radius   起始双指间距（px）
     * @param angle    目标双指间距（px）；<=0 时 zoomIn ? radius*2 : radius/2
     * @param durationMs 时长（<=0 用默认 300ms）
     * @param zoomIn   true=放大（两指张开），false=捏合
     */
    boolean pinch(float cx, float cy, float radius, float angle, int durationMs, boolean zoomIn);

    /**
     * 多笔划手势。
     * JSON 形如 [{"points":[{"x":1,"y":2}...],"duration":300,"delay":0}...]
     * 兼容单笔划点序列 [{"x":..,"y":..,"delay":..}...]（对照派大星行协议）。
     */
    boolean gestureJson(String strokesJson);

    /**
     * 键码注入（KeyEvent down+up）。仅 DIRECT_ROOT / ROOT 通道实现
     * （InputManager 反射注入任意 InputEvent）；无能力通道返回 false 由门面降级。
     */
    default boolean injectKey(int keycode) {
        return false;
    }
}
