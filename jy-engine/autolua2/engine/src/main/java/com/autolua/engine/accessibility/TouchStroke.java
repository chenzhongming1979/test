package com.autolua.engine.accessibility;

import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;

/**
 * 手势构造辅助类（对照按键 ya\a.java / AccessibilityTouchObj）：
 * down/move 在客户端累积 Path，up 时 {@code new StrokeDescription(path, 0, duration)} 一次派发整条路径。
 *
 * <p>仅可在 API 24+ 使用（StrokeDescription/GestureDescription 的版本要求），
 * 调用入口由 GestureQueueManager 统一做 SDK 硬闸。</p>
 */
public class TouchStroke {
    /** move 步长最小计时（毫秒），对照 ya\a.java:46 {@code duration += max(dt, 10)} */
    public static final long MIN_MOVE_STEP_MS = 10;
    /** up 收尾时长（毫秒），对照文档 3.3.2：up 时 duration += 10 */
    public static final long UP_TAIL_MS = 10;
    /** tap/swipe 最小时长（毫秒），对照文档 3.3.2 */
    public static final long MIN_TAP_SWIPE_DURATION_MS = 50;
    /** pinch 最小时长（毫秒），对照文档 3.3.2 */
    public static final long MIN_PINCH_DURATION_MS = 100;

    private final Path mPath = new Path();
    private long mDurationMs = 0;
    /** 本笔划是否以 willContinue 派发（派发结束后保持按下等待续写；持续手势 API 26+） */
    private boolean mWillContinue = false;
    /** 逻辑笔划 id（框架内部 id 不对外，由调用侧 pointerId / gestureJson strokeId 指定，供 continueStroke 关联） */
    private int mStrokeId = -1;

    /** 手指按下：path.reset() + moveTo（对照 ya\a.java:34-40） */
    public TouchStroke down(float x, float y) {
        mPath.reset();
        mPath.moveTo(x, y);
        mDurationMs = 0;
        return this;
    }

    /** 手指移动：lineTo 客户端累积路径，dt 最小按 10ms 计（对照 ya\a.java:42-47） */
    public TouchStroke move(float x, float y, long dtMs) {
        mPath.lineTo(x, y);
        mDurationMs += Math.max(dtMs, MIN_MOVE_STEP_MS);
        return this;
    }

    /** 直接设置本笔时长（tap 场景：按下即定时长） */
    public TouchStroke duration(long durationMs) {
        mDurationMs = Math.max(durationMs, 0);
        return this;
    }

    /** 手指抬起：追加收尾时长（对照文档 3.3.2：up 时 duration += 10） */
    public TouchStroke up() {
        mDurationMs += UP_TAIL_MS;
        return this;
    }

    public long getDurationMs() {
        return mDurationMs;
    }

    /** 标记本笔划以 willContinue 派发（派发结束后保持按下等待续写，API 26+） */
    public TouchStroke willContinue(boolean willContinue) {
        mWillContinue = willContinue;
        return this;
    }

    public boolean isWillContinue() {
        return mWillContinue;
    }

    /** 设置逻辑笔划 id（调用侧 pointerId / gestureJson strokeId，供续写关联） */
    public TouchStroke strokeId(int strokeId) {
        mStrokeId = strokeId;
        return this;
    }

    public int getStrokeId() {
        return mStrokeId;
    }

    public Path getPath() {
        return mPath;
    }

    /**
     * up 后一次性生成 StrokeDescription（对照 ya\a.java:57-60 的 e()），时长下限 50ms；
     * {@code mWillContinue=true} 时走 API 26 持续笔划变体（willContinue=true，派发结束后保持按下）。
     */
    public GestureDescription.StrokeDescription toStrokeDescription() {
        return toStrokeDescription(mWillContinue);
    }

    /**
     * 生成指定 willContinue 标志的 StrokeDescription：
     * willContinue=true 需要 API 26（持续手势变体），低于 26 显式抛
     * {@link UnsupportedOperationException}（与 multiTap 的 API 26 硬闸风格一致，消息为中文）。
     */
    public GestureDescription.StrokeDescription toStrokeDescription(boolean willContinue) {
        long duration = Math.max(mDurationMs, MIN_TAP_SWIPE_DURATION_MS);
        if (willContinue) {
            if (Build.VERSION.SDK_INT < 26) {
                throw new UnsupportedOperationException(
                        "持续手势(continueStroke)需要 Android 8.0(API 26)+，当前 API "
                                + Build.VERSION.SDK_INT);
            }
            return new GestureDescription.StrokeDescription(mPath, 0, duration, true);
        }
        return new GestureDescription.StrokeDescription(mPath, 0, duration);
    }

    /** 单笔整条手势（一次派发） */
    public GestureDescription toGestureDescription() {
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(toStrokeDescription());
        return builder.build();
    }

    // ==================== 静态便捷构造 ====================

    /** 点击笔划：单点按下即抬起 */
    public static TouchStroke tap(float x, float y, long durationMs) {
        return new TouchStroke().down(x, y).duration(durationMs);
    }

    /** 滑动笔划：线性一笔从起点到终点 */
    public static TouchStroke swipe(float x1, float y1, float x2, float y2, long durationMs) {
        return new TouchStroke().down(x1, y1).move(x2, y2, durationMs);
    }

    /** 长按笔划：单点按下保持 durationMs */
    public static TouchStroke longClick(float x, float y, long durationMs) {
        return new TouchStroke().down(x, y).duration(durationMs);
    }
}
