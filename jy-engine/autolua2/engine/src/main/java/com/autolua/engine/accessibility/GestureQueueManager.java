package com.autolua.engine.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.util.Log;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 手势串行队列（对照按键 GestureQueueManager / c\b.java）：
 * 所有手势经全局队列串行派发，同一时刻最多一个"在飞"手势；
 * {@link #dispatch(GestureDescription, boolean)} 在 wait 模式下以 CountDownLatch 同步等待，
 * 超时公式：500 + min(手势末尾时刻, 最大手势时长) + 1000（毫秒）（对照 c\b.java:162-174）。
 *
 * <p>注意：wait=true 时禁止在主线程调用（完成回调在主线程执行，主线程等待会一直超时）。</p>
 */
public class GestureQueueManager {
    private static final String TAG = "GestureQueueManager";

    /** tap/swipe 最小时长（毫秒） */
    public static final long MIN_TAP_SWIPE_DURATION_MS = TouchStroke.MIN_TAP_SWIPE_DURATION_MS;
    /** pinch 最小时长（毫秒） */
    public static final long MIN_PINCH_DURATION_MS = TouchStroke.MIN_PINCH_DURATION_MS;

    private static volatile GestureQueueManager sInstance;

    public static GestureQueueManager getInstance() {
        if (sInstance == null) {
            synchronized (GestureQueueManager.class) {
                if (sInstance == null) {
                    sInstance = new GestureQueueManager();
                }
            }
        }
        return sInstance;
    }

    private GestureQueueManager() {
    }

    /** 全局串行队列 */
    private final ConcurrentLinkedQueue<GestureTask> mQueue = new ConcurrentLinkedQueue<>();
    /** 是否有手势正在执行（单飞标志） */
    private volatile boolean mExecuting = false;

    /** 队列任务：手势描述 + 完成闭锁（对照按键 C0034b） */
    public static final class GestureTask {
        public final GestureDescription description;
        public final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean done = false;

        GestureTask(GestureDescription description) {
            this.description = description;
        }
    }

    /**
     * 派发手势：入队 → pump()；wait=true 时按超时公式阻塞等待。
     *
     * @return false = 未派发（API&lt;24 / 服务未连接 / 描述为空）；
     *         true = 已入队（wait 模式下等到完成/取消/超时后返回）
     */
    public boolean dispatch(GestureDescription description, boolean wait) {
        if (Build.VERSION.SDK_INT < 24 || description == null) {
            return false; // API 24 硬闸：手势体系起点
        }
        if (AutoLuaAccessibilityService.getInstance() == null) {
            return false; // 服务未连接快速失败
        }
        GestureTask task = new GestureTask(description);
        mQueue.offer(task);
        pump();
        if (!wait) {
            return true;
        }
        try {
            long timeout = 500 + Math.min(lastStrokeEndMs(description),
                    GestureDescription.getMaxGestureDuration()) + 1000;
            task.latch.await(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    /** 手势末尾时刻 = max(startTime + duration)（对照按键 c\b.java:162-174 的 t()） */
    private static long lastStrokeEndMs(GestureDescription description) {
        long max = 0;
        for (int i = 0; i < description.getStrokeCount(); i++) {
            GestureDescription.StrokeDescription stroke = description.getStroke(i);
            if (stroke != null) {
                max = Math.max(max, stroke.getStartTime() + stroke.getDuration());
            }
        }
        return max;
    }

    /** 串行泵（synchronized）：单飞手势；派发被拒立即放行并递归泵下一条（对照按键 B()） */
    private synchronized void pump() {
        if (Build.VERSION.SDK_INT < 24) {
            return; // 保险闸（正常路径已在 dispatch 拦截）
        }
        if (mExecuting || mQueue.isEmpty()) {
            return;
        }
        AccessibilityService service = AutoLuaAccessibilityService.getInstance();
        if (service == null) {
            // 服务断开 → 队列所有 latch 全部放行（快速失败）
            drainQueue();
            return;
        }
        final GestureTask task = mQueue.poll();
        if (task == null) {
            return;
        }
        mExecuting = true;
        boolean accepted;
        try {
            accepted = service.dispatchGesture(task.description,
                    new AccessibilityService.GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription gestureDescription) {
                            finish(task);
                        }

                        @Override
                        public void onCancelled(GestureDescription gestureDescription) {
                            finish(task);
                        }
                    }, null);
        } catch (Throwable t) {
            Log.w(TAG, "dispatchGesture failed", t);
            accepted = false;
        }
        if (!accepted) {
            // dispatchGesture 返回 false → 立即 countDown + 递归泵下一条
            finish(task);
        }
    }

    /** 手势结束（完成/取消/被拒）：放行等待者并继续泵队列 */
    private void finish(GestureTask task) {
        if (!task.done) {
            task.done = true;
            task.latch.countDown();
        }
        mExecuting = false;
        pump();
    }

    /** 排空队列：所有等待者放行 */
    private void drainQueue() {
        GestureTask task;
        while ((task = mQueue.poll()) != null) {
            if (!task.done) {
                task.done = true;
                task.latch.countDown();
            }
        }
    }

    /** 服务断开时由 AutoLuaAccessibilityService 调用：放行所有等待者并复位单飞标志 */
    public synchronized void onServiceDisconnected() {
        drainQueue();
        mExecuting = false;
    }

    // ==================== 便捷手势（对照按键实现 + 文档 3.3.2） ====================

    /** 点击：duration 最小 50ms（对照按键 c\b.java:298-307） */
    public boolean tap(float x, float y, int durationMs, boolean wait) {
        if (Build.VERSION.SDK_INT < 24) {
            return false;
        }
        return dispatch(TouchStroke.tap(x, y,
                Math.max(durationMs, MIN_TAP_SWIPE_DURATION_MS)).toGestureDescription(), wait);
    }

    /** 带随机落点偏移的点击：坐标 ±range/2 均匀随机（对照按键 c\b.java:236-253） */
    public boolean tap(float x, float y, int durationMs, int randomRange, boolean wait) {
        float rx = x;
        float ry = y;
        if (randomRange > 0) {
            rx = x + (int) (Math.random() * randomRange - randomRange / 2.0);
            ry = y + (int) (Math.random() * randomRange - randomRange / 2.0);
        }
        return tap(rx, ry, durationMs, wait);
    }

    /** 滑动：duration 最小 50ms（对照按键 c\b.java:286-296） */
    public boolean swipe(float x1, float y1, float x2, float y2, int durationMs, boolean wait) {
        if (Build.VERSION.SDK_INT < 24) {
            return false;
        }
        return dispatch(TouchStroke.swipe(x1, y1, x2, y2,
                Math.max(durationMs, MIN_TAP_SWIPE_DURATION_MS)).toGestureDescription(), wait);
    }

    /** 长按：duration 下限 50ms，长按时长由调用方保证（建议 ≥500ms） */
    public boolean longClick(float x, float y, int durationMs, boolean wait) {
        if (Build.VERSION.SDK_INT < 24) {
            return false;
        }
        return dispatch(TouchStroke.longClick(x, y,
                Math.max(durationMs, MIN_TAP_SWIPE_DURATION_MS)).toGestureDescription(), wait);
    }

    /**
     * 双指张开(spread=true)/捏合(spread=false)：duration 最小 100ms
     * （对照按键 c\b.java:203-230 与 :358-386：起止点取中心与端点间 10% 位置）
     */
    public boolean pinch(float x1, float y1, float x2, float y2, int durationMs,
                         boolean spread, boolean wait) {
        if (Build.VERSION.SDK_INT < 24) {
            return false;
        }
        long duration = Math.max(durationMs, MIN_PINCH_DURATION_MS);
        float cx = (x1 + x2) / 2f;
        float cy = (y1 + y2) / 2f;
        float ax1 = cx + (x1 - cx) * 0.1f;
        float ay1 = cy + (y1 - cy) * 0.1f;
        float ax2 = cx + (x2 - cx) * 0.1f;
        float ay2 = cy + (y2 - cy) * 0.1f;
        TouchStroke stroke1 = spread
                ? TouchStroke.swipe(ax1, ay1, x1, y1, duration)
                : TouchStroke.swipe(x1, y1, ax1, ay1, duration);
        TouchStroke stroke2 = spread
                ? TouchStroke.swipe(ax2, ay2, x2, y2, duration)
                : TouchStroke.swipe(x2, y2, ax2, ay2, duration);
        return dispatchStrokes(Arrays.asList(stroke1, stroke2), wait);
    }

    /**
     * 多指同帧点击：多 StrokeDescription 加入同一 Builder，startTime 递增错峰
     * （API 26 硬闸，对照按键 c\b.java:331-356 的 q()）
     */
    public boolean multiTap(float[][] points, int durationMs, int staggerMs, boolean wait) {
        if (Build.VERSION.SDK_INT < 26) {
            return false; // API 26 硬闸：多指同帧
        }
        if (points == null || points.length == 0) {
            return false;
        }
        long duration = Math.max(durationMs, 10);
        long stagger = Math.max(staggerMs, 0);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        long startTime = 0;
        for (float[] point : points) {
            if (point == null || point.length < 2) {
                continue;
            }
            Path path = new Path();
            path.moveTo(point[0], point[1]);
            builder.addStroke(new GestureDescription.StrokeDescription(path, startTime, duration));
            startTime += duration + stagger; // 递增错峰
        }
        return dispatch(builder.build(), wait);
    }

    /** 多笔划一次派发：多 TouchStroke 加入同一 Builder（对照懒人 I1I.java:268-273） */
    public boolean dispatchStrokes(List<TouchStroke> strokes, boolean wait) {
        if (Build.VERSION.SDK_INT < 24) {
            return false;
        }
        if (strokes == null || strokes.isEmpty()) {
            return false;
        }
        GestureDescription.Builder builder = new GestureDescription.Builder();
        for (TouchStroke stroke : strokes) {
            if (stroke != null) {
                builder.addStroke(stroke.toStrokeDescription());
            }
        }
        return dispatch(builder.build(), wait);
    }
}
