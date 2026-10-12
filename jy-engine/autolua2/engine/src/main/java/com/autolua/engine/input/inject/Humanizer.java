package com.autolua.engine.input.inject;

/**
 * 人化轨迹参数（对照方案二 3.3.6 参考值表）：
 * <ul>
 *     <li>tap 按压时长 50ms —— RootInputInjector.java:539</li>
 *     <li>pressure/size 扰动 nextFloat()*50+1 —— iil1.java:111-114（可配置关闭）</li>
 *     <li>move 合并阈值默认 30px（可 config 下发）—— RootInputInjector.java:35</li>
 *     <li>move 插值 ceil(dist/threshold) 步、每步 sleep 15ms —— RootInputInjector.java:216-295</li>
 *     <li>swipe steps 均分 duration（默认 300ms/20 步）—— RootInputInjector.java:461-530</li>
 *     <li>pinch/zoom 默认 20 步 300ms —— RootInputInjector.java:367-368</li>
 * </ul>
 */
public final class Humanizer {

    /** move 相邻事件合并阈值（px）：位移超过该值时按 ceil(dist/threshold) 步线性插值 */
    private int moveThreshold = 30;

    /** move 插值每步间隔（ms） */
    private int moveStepDelayMs = 15;

    /** tap 按压时长（ms） */
    private int tapDurationMs = 50;

    /** longtap 按压时长（ms） */
    private int longTapDurationMs = 1000;

    /** swipe 默认步数（duration 均分为 steps 段） */
    private int swipeSteps = 20;

    /** swipe 默认时长（ms，调用方未指定或 <=0 时使用） */
    private int defaultSwipeDurationMs = 300;

    /** pinch/zoom 默认步数 */
    private int pinchSteps = 20;

    /** pinch/zoom 默认时长（ms） */
    private int pinchDurationMs = 300;

    /**
     * pressure/size 随机扰动开关：开启时每个指坐标的 pressure/size 取
     * nextFloat()*50+1（对照懒人实现），关闭时固定 1.0f。
     * 对压力/接触面积敏感的目标应用可关闭。
     */
    private boolean randomizeCoords = true;

    public int getMoveThreshold() {
        return Math.max(1, moveThreshold);
    }

    public void setMoveThreshold(int moveThreshold) {
        this.moveThreshold = moveThreshold;
    }

    public int getMoveStepDelayMs() {
        return Math.max(0, moveStepDelayMs);
    }

    public void setMoveStepDelayMs(int moveStepDelayMs) {
        this.moveStepDelayMs = moveStepDelayMs;
    }

    public int getTapDurationMs() {
        return Math.max(1, tapDurationMs);
    }

    public void setTapDurationMs(int tapDurationMs) {
        this.tapDurationMs = tapDurationMs;
    }

    public int getLongTapDurationMs() {
        return Math.max(1, longTapDurationMs);
    }

    public void setLongTapDurationMs(int longTapDurationMs) {
        this.longTapDurationMs = longTapDurationMs;
    }

    public int getSwipeSteps() {
        return Math.max(1, swipeSteps);
    }

    public void setSwipeSteps(int swipeSteps) {
        this.swipeSteps = swipeSteps;
    }

    public int getDefaultSwipeDurationMs() {
        return Math.max(0, defaultSwipeDurationMs);
    }

    public void setDefaultSwipeDurationMs(int defaultSwipeDurationMs) {
        this.defaultSwipeDurationMs = defaultSwipeDurationMs;
    }

    public int getPinchSteps() {
        return Math.max(1, pinchSteps);
    }

    public void setPinchSteps(int pinchSteps) {
        this.pinchSteps = pinchSteps;
    }

    public int getPinchDurationMs() {
        return Math.max(0, pinchDurationMs);
    }

    public void setPinchDurationMs(int pinchDurationMs) {
        this.pinchDurationMs = pinchDurationMs;
    }

    public boolean isRandomizeCoords() {
        return randomizeCoords;
    }

    public void setRandomizeCoords(boolean randomizeCoords) {
        this.randomizeCoords = randomizeCoords;
    }

    /**
     * 行协议 config 下发（仅支持白名单键）。
     *
     * @return 是否识别并应用了该配置
     */
    public boolean applyConfig(String key, String value) {
        if (key == null || value == null) {
            return false;
        }
        try {
            if ("move_threshold".equals(key)) {
                setMoveThreshold(Integer.parseInt(value.trim()));
                return true;
            }
            if ("move_step_delay".equals(key)) {
                setMoveStepDelayMs(Integer.parseInt(value.trim()));
                return true;
            }
            if ("tap_duration".equals(key)) {
                setTapDurationMs(Integer.parseInt(value.trim()));
                return true;
            }
            if ("swipe_steps".equals(key)) {
                setSwipeSteps(Integer.parseInt(value.trim()));
                return true;
            }
            if ("pinch_steps".equals(key)) {
                setPinchSteps(Integer.parseInt(value.trim()));
                return true;
            }
            if ("pinch_duration".equals(key)) {
                setPinchDurationMs(Integer.parseInt(value.trim()));
                return true;
            }
            if ("randomize".equals(key)) {
                setRandomizeCoords(!"0".equals(value.trim()) && !"false".equals(value.trim()));
                return true;
            }
        } catch (NumberFormatException ignore) {
            // 配置值非法：按未识别处理
        }
        return false;
    }
}
