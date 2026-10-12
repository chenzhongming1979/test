package com.autolua.engine.input.inject;

import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

import com.autolua.engine.accessibility.AutoLuaAccessibilityService;
import com.autolua.engine.accessibility.GestureQueueManager;
import com.autolua.engine.accessibility.TouchStroke;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;

/**
 * ACCESSIBILITY 注入通道（降级链第 4 级，免 root）：
 * 直接持有无障碍 {@link GestureQueueManager}，把 IInjectChannel 的 down/move/up
 * 指针模型适配为"客户端累积 Path、up 一次派发整条"的无障碍手势模型
 * （对照按键 ya\a.java：down/move 仅在客户端累积，up 时
 * {@code StrokeDescription(path, 0, duration)} 整条重放——系统会按时间轴重演按下/移动/抬起）。
 *
 * <ul>
 *     <li>injectDown：登记单点驻留笔划（暂不派发，无障碍手势必须 down→up 成对）；</li>
 *     <li>injectMove：lineTo 累积路径（步进 dt 按 uptimeMs 计，TouchStroke 内部下限 10ms）；</li>
 *     <li>injectUp：以驻留时长定 duration（+10ms 收尾，长按场景天然成立），经
 *         GestureQueueManager 串行队列一次派发；</li>
 *     <li>tap/swipe：直接映射 GestureQueueManager.tap/swipe（同步 wait）；</li>
 *     <li>pinch：起始双指间距 radius、目标间距 angle（&lt;=0 时 zoomIn ? 2r : r/2），
 *         两指两笔划同帧派发（dispatchStrokes）；</li>
 *     <li>gestureJson：多笔划 [{"points":[...],"duration":300,"delay":0}...]，
 *         兼容单笔划点序列 [{"x":..,"y":..,"delay":..}...]（对照 InjectorCore.gesture 格式，
 *         笔划间串行执行）；每笔划支持可选 "continue":true（本笔划派发结束后保持按下等待续写，
 *         API 26 持续手势变体）与 "strokeId"（续写关联键，缺省用笔划下标），后续笔划或后续
 *         gestureJson 调用携带相同 strokeId 即以上次保留的 StrokeDescription.continueStroke 续写；</li>
 *     <li>持续手势：{@link #markStrokeContinue}（指针模型 up 前标记）、
 *         {@link #continueStroke}（续写/新起持续笔划）、{@link #finishStroke}（收尾抬起），
 *         框架 StrokeDescription 无公开 id，续写依赖"同 strokeId 上一次返回的
 *         StrokeDescription 对象"，由 {@link #continuingStrokes} 登记。</li>
 * </ul>
 * isAvailable() = AutoLuaAccessibilityService.isRunning()（API&lt;24 直接不可用，
 * 由降级链兜底其他通道）。
 */
public class AccessibilityChannel implements IInjectChannel {
    private static final String TAG = "AccessibilityChannel";

    /** 支持的指编号上限（IInjectChannel 约定 pointerId 0..9） */
    private static final int MAX_POINTERS = 10;
    /** 默认滑动时长（IInjectChannel 约定 durationMs&lt;=0 用 300ms） */
    private static final int DEFAULT_SWIPE_MS = 300;
    /** 默认点击按压时长（人化 50ms，同 GestureQueueManager.MIN_TAP_SWIPE_DURATION_MS） */
    private static final int DEFAULT_TAP_MS = 50;
    /** 默认捏合时长 */
    private static final int DEFAULT_PINCH_MS = 300;

    /** 每指在途笔划：down→move 客户端累积，up 时一次派发（对照按键 ya\a.java 模式） */
    private final SparseArray<TouchStroke> activeStrokes = new SparseArray<>();
    /** 每指最近事件时刻（uptimeMillis），供 move 步进 dt 与 up 驻留时长换算 */
    private final long[] lastEventUptimes = new long[MAX_POINTERS];
    /** 每指最后坐标（x,y 成对存放），供持续笔划登记最后位置 */
    private final float[] lastPointerPoints = new float[MAX_POINTERS * 2];
    /**
     * 在途持续笔划：strokeId → 最近一次派发的 willContinue StrokeDescription。
     * 框架 StrokeDescription 无公开 getId()，continueStroke 必须由同笔划上一次返回的
     * 描述对象发起（内部携带续写链 id），故按逻辑 strokeId 登记保留。
     */
    private final SparseArray<GestureDescription.StrokeDescription> continuingStrokes = new SparseArray<>();
    /** 在途持续笔划最后坐标：strokeId → {x,y}（收尾/续写无新点时按最后位置保持） */
    private final SparseArray<float[]> continuingLastPoints = new SparseArray<>();

    @Override
    public boolean start() {
        // 无障碍通道无连接过程：服务已连接即视为启动成功
        return isAvailable();
    }

    @Override
    public void stop() {
        releaseContinuations();
        synchronized (activeStrokes) {
            activeStrokes.clear();
        }
        Arrays.fill(lastEventUptimes, 0L);
        Arrays.fill(lastPointerPoints, 0f);
    }

    @Override
    public boolean isAvailable() {
        return Build.VERSION.SDK_INT >= 24 && AutoLuaAccessibilityService.isRunning();
    }

    @Override
    public String name() {
        return "ACCESSIBILITY";
    }

    // ------------------------------------------------------------------
    // 指针级 down/move/up（客户端累积，up 一次派发）
    // ------------------------------------------------------------------

    @Override
    public boolean injectDown(int pointerId, float x, float y, long downTime) {
        if (!checkPointer(pointerId)) {
            return false;
        }
        // down = 单点驻留 stroke：仅登记起点不派发（无障碍手势必须 down→up 成对，
        // 整条路径在 up 时以 StrokeDescription(path,0,duration) 一次性重放）
        synchronized (activeStrokes) {
            activeStrokes.put(pointerId, new TouchStroke().down(x, y));
        }
        lastEventUptimes[pointerId] = SystemClock.uptimeMillis();
        lastPointerPoints[pointerId * 2] = x;
        lastPointerPoints[pointerId * 2 + 1] = y;
        return true;
    }

    @Override
    public boolean injectMove(int pointerId, float x, float y, long downTime) {
        if (!checkPointer(pointerId)) {
            return false;
        }
        TouchStroke stroke;
        synchronized (activeStrokes) {
            stroke = activeStrokes.get(pointerId);
        }
        if (stroke == null) {
            return false; // 未 down 先 move：非法序列
        }
        long now = SystemClock.uptimeMillis();
        stroke.move(x, y, Math.max(0, now - lastEventUptimes[pointerId]));
        lastEventUptimes[pointerId] = now;
        lastPointerPoints[pointerId * 2] = x;
        lastPointerPoints[pointerId * 2 + 1] = y;
        return true;
    }

    @Override
    public boolean injectUp(int pointerId, float x, float y, long downTime) {
        if (!checkPointer(pointerId)) {
            return false;
        }
        TouchStroke stroke;
        synchronized (activeStrokes) {
            stroke = activeStrokes.get(pointerId);
            activeStrokes.remove(pointerId);
        }
        if (stroke == null) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        if (x != 0 || y != 0) {
            // x/y 传 0 表示沿用最后位置（IInjectChannel 契约），不追加路径
            stroke.move(x, y, Math.max(0, now - lastEventUptimes[pointerId]));
            lastPointerPoints[pointerId * 2] = x;
            lastPointerPoints[pointerId * 2 + 1] = y;
        }
        // 驻留时长即按压时长（down 后停留再 up = 真实长按），+10ms 收尾（文档 3.3.2）
        stroke.duration(Math.max(now - lastEventUptimes[pointerId], stroke.getDurationMs()));
        lastEventUptimes[pointerId] = 0;
        stroke.up();
        if (stroke.isWillContinue()) {
            // 持续笔划：willContinue=true 派发（API 26 变体，up 后保持按下等待续写），
            // 保留返回的 StrokeDescription 供同 strokeId 的 continueStroke 续写
            stroke.strokeId(pointerId);
            GestureDescription.StrokeDescription desc = stroke.toStrokeDescription();
            synchronized (activeStrokes) {
                continuingStrokes.put(pointerId, desc);
                continuingLastPoints.put(pointerId, new float[]{
                        lastPointerPoints[pointerId * 2], lastPointerPoints[pointerId * 2 + 1]});
            }
            return GestureQueueManager.getInstance().dispatch(
                    new GestureDescription.Builder().addStroke(desc).build(), true);
        }
        return GestureQueueManager.getInstance().dispatch(stroke.toGestureDescription(), true);
    }

    // ------------------------------------------------------------------
    // 便捷手势（直接映射 GestureQueueManager 现有 API）
    // ------------------------------------------------------------------

    @Override
    public boolean tap(float x, float y) {
        if (!isAvailable()) {
            return false;
        }
        return GestureQueueManager.getInstance().tap(x, y, DEFAULT_TAP_MS, true);
    }

    @Override
    public boolean swipe(float x1, float y1, float x2, float y2, int durationMs) {
        if (!isAvailable()) {
            return false;
        }
        return GestureQueueManager.getInstance().swipe(x1, y1, x2, y2,
                durationMs > 0 ? durationMs : DEFAULT_SWIPE_MS, true);
    }

    @Override
    public boolean pinch(float cx, float cy, float radius, float angle,
                         int durationMs, boolean zoomIn) {
        if (!isAvailable() || radius <= 0) {
            return false;
        }
        // 目标双指间距：angle<=0 时 zoomIn ? 2r : r/2（IInjectChannel 契约）
        float target = angle > 0 ? angle : (zoomIn ? radius * 2 : radius / 2);
        long duration = Math.max(durationMs > 0 ? durationMs : DEFAULT_PINCH_MS,
                GestureQueueManager.MIN_PINCH_DURATION_MS);
        // 起始两指位于中心 ± radius/2（水平展开），终点 ± target/2；放大=外扩，捏合=内收
        TouchStroke stroke1 = TouchStroke.swipe(cx - radius / 2f, cy, cx - target / 2f, cy, duration);
        TouchStroke stroke2 = TouchStroke.swipe(cx + radius / 2f, cy, cx + target / 2f, cy, duration);
        return GestureQueueManager.getInstance().dispatchStrokes(Arrays.asList(stroke1, stroke2), true);
    }

    @Override
    public boolean gestureJson(String strokesJson) {
        if (!isAvailable() || strokesJson == null || strokesJson.trim().isEmpty()) {
            return false;
        }
        try {
            JSONArray arr = new JSONArray(strokesJson);
            if (arr.length() == 0) {
                return false;
            }
            GestureQueueManager gqm = GestureQueueManager.getInstance();
            JSONObject first = arr.optJSONObject(0);
            if (first != null && first.has("points")) {
                // 多笔划：笔划间串行执行（对照 InjectorCore.gesture）
                for (int s = 0; s < arr.length(); s++) {
                    JSONObject strokeObj = arr.getJSONObject(s);
                    int delay = strokeObj.optInt("delay", 0);
                    if (delay > 0) {
                        SystemClock.sleep(delay);
                    }
                    // 协议扩展（旧 JSON 兼容：不传即行为不变）：
                    // "continue":true → 本笔划派发结束后保持按下等待续写（API 26 持续手势变体）；
                    // "strokeId" → 续写关联键（缺省用笔划下标 s），有在途持续笔划时自动续写
                    JSONArray points = strokeObj.getJSONArray("points");
                    boolean keepDown = strokeObj.optBoolean("continue", false);
                    int strokeId = strokeObj.optInt("strokeId", s);
                    TouchStroke stroke = buildStroke(points, strokeObj.optInt("duration", 0));
                    if (stroke == null) {
                        return false;
                    }
                    stroke.willContinue(keepDown).strokeId(strokeId);
                    GestureDescription.StrokeDescription pending = getContinuation(strokeId);
                    GestureDescription description;
                    if (pending != null) {
                        // 续写：必须由同笔划上一次保留的 StrokeDescription 发起
                        //（API<26 时不存在的 pending 已在源头被硬闸拦截）
                        description = new GestureDescription.Builder()
                                .addStroke(pending.continueStroke(stroke.getPath(), 0,
                                        Math.max(stroke.getDurationMs(),
                                                GestureQueueManager.MIN_TAP_SWIPE_DURATION_MS),
                                        keepDown))
                                .build();
                    } else {
                        // 新起一笔：keepDown=true 时走 willContinue 变体（API<26 显式抛异常，
                        // 由下方 catch 记日志并返回 false）
                        description = stroke.toGestureDescription();
                    }
                    // 登记/清除续写上下文（continueStroke 依赖同 strokeId 上次对象）
                    synchronized (activeStrokes) {
                        if (keepDown) {
                            continuingStrokes.put(strokeId, description.getStroke(0));
                            JSONObject lastPoint = points.getJSONObject(points.length() - 1);
                            continuingLastPoints.put(strokeId, new float[]{
                                    (float) lastPoint.optDouble("x", 0),
                                    (float) lastPoint.optDouble("y", 0)});
                        } else if (pending != null) {
                            continuingStrokes.remove(strokeId);
                            continuingLastPoints.remove(strokeId);
                        }
                    }
                    if (!gqm.dispatch(description, true)) {
                        return false;
                    }
                }
                return true;
            }
            // 单笔划兼容（点序列 [{"x":..,"y":..,"delay":..}...]）
            TouchStroke stroke = buildStroke(arr, 0);
            if (stroke == null) {
                return false;
            }
            return gqm.dispatch(stroke.toGestureDescription(), true);
        } catch (Throwable t) {
            Log.w(TAG, "gestureJson error: " + t);
            return false;
        }
    }

    /** 构造一笔划：down 首点 → 逐点 move（duration 均分步进，点级 delay 串行等待） */
    private static TouchStroke buildStroke(JSONArray points, int durationMs) throws Exception {
        if (points == null || points.length() == 0) {
            return null;
        }
        JSONObject p0 = points.getJSONObject(0);
        TouchStroke stroke = new TouchStroke()
                .down((float) p0.optDouble("x", 0), (float) p0.optDouble("y", 0));
        int segs = Math.max(1, points.length() - 1);
        long stepMs = durationMs > 0 ? durationMs / segs : 0;
        for (int i = 1; i < points.length(); i++) {
            JSONObject p = points.getJSONObject(i);
            stroke.move((float) p.optDouble("x", 0), (float) p.optDouble("y", 0), stepMs);
            int d = p.optInt("delay", 0);
            if (d > 0) {
                SystemClock.sleep(d);
            }
        }
        return stroke;
    }

    // ------------------------------------------------------------------
    // 持续手势（API 26 continueStroke：派发结束后保持按下，分段续写同一笔划）
    // ------------------------------------------------------------------

    /**
     * 标记指定指针当前在途笔划 up 后保持按下等待续写（须在 injectUp 之前调用；
     * up 后以 strokeId = pointerId 登记，经 {@link #continueStroke} 续写）。
     */
    public void markStrokeContinue(int pointerId, boolean willContinue) {
        if (!checkPointer(pointerId)) {
            return;
        }
        TouchStroke stroke;
        synchronized (activeStrokes) {
            stroke = activeStrokes.get(pointerId);
        }
        if (stroke != null) {
            stroke.willContinue(willContinue);
        }
    }

    /**
     * 续写持续笔划：
     * <ul>
     *     <li>strokeId 已有在途持续笔划 → 以上次保留的 StrokeDescription.continueStroke 续写
     *         （willContinue=true 继续保持，false 收尾抬起）；</li>
     *     <li>无在途笔划且 willContinue=true → 新起一笔持续笔划（首段 willContinue 派发并登记）；</li>
     *     <li>无在途笔划且 willContinue=false → 无可收尾，返回 false。</li>
     * </ul>
     *
     * @param strokeId     续写关联键（指针模型 = pointerId；gestureJson = 笔划 strokeId）
     * @param points       新段路径点 [x0,y0,x1,y1,...]；null/空 = 在最后位置保持（驻留段）
     * @param durationMs   新段时长（下限 50ms）
     * @param willContinue true = 本段结束后继续保持按下；false = 收尾抬起并解除登记
     * @return 是否成功派发；API&lt;26 且需要持续手势变体时抛 UnsupportedOperationException（中文消息）
     */
    public boolean continueStroke(int strokeId, float[] points, long durationMs, boolean willContinue) {
        if (strokeId < 0 || !isAvailable()) {
            return false;
        }
        GestureDescription.StrokeDescription pending;
        float[] lastPoint;
        synchronized (activeStrokes) {
            pending = continuingStrokes.get(strokeId);
            lastPoint = continuingLastPoints.get(strokeId);
        }
        Path path = new Path();
        if (points != null && points.length >= 2) {
            path.moveTo(points[0], points[1]);
            for (int i = 2; i + 1 < points.length; i += 2) {
                path.lineTo(points[i], points[i + 1]);
            }
            lastPoint = new float[]{points[points.length - 2], points[points.length - 1]};
        } else if (lastPoint != null && lastPoint.length >= 2) {
            path.moveTo(lastPoint[0], lastPoint[1]); // 无新点：在最后位置保持
        } else {
            return false; // 既无新点也无历史位置：无起点
        }
        long duration = Math.max(durationMs, GestureQueueManager.MIN_TAP_SWIPE_DURATION_MS);
        GestureDescription.StrokeDescription desc;
        if (pending != null || willContinue) {
            // willContinue 变体（4 参构造 / continueStroke）均为 API 26：
            // 显式硬闸（与 multiTap 的 API 26 硬闸风格一致），中文消息
            if (Build.VERSION.SDK_INT < 26) {
                throw new UnsupportedOperationException(
                        "持续手势(continueStroke)需要 Android 8.0(API 26)+，当前 API "
                                + Build.VERSION.SDK_INT);
            }
            desc = pending != null
                    ? pending.continueStroke(path, 0, duration, willContinue)
                    : new GestureDescription.StrokeDescription(path, 0, duration, true);
        } else {
            return false; // 无在途持续笔划却要求收尾：无可收尾
        }
        synchronized (activeStrokes) {
            if (willContinue) {
                continuingStrokes.put(strokeId, desc);
                continuingLastPoints.put(strokeId, lastPoint);
            } else {
                continuingStrokes.remove(strokeId);
                continuingLastPoints.remove(strokeId);
            }
        }
        return GestureQueueManager.getInstance().dispatch(
                new GestureDescription.Builder().addStroke(desc).build(), true);
    }

    /** 收尾持续笔划：在最后位置保持 durationMs（下限 50ms）后抬起（willContinue=false） */
    public boolean finishStroke(int strokeId, long durationMs) {
        return continueStroke(strokeId, null, durationMs, false);
    }

    /** 读取指定 strokeId 在途持续笔划的上次 StrokeDescription（无则 null） */
    private GestureDescription.StrokeDescription getContinuation(int strokeId) {
        synchronized (activeStrokes) {
            return continuingStrokes.get(strokeId);
        }
    }

    /**
     * 收尾所有在途持续笔划（willContinue=false 派发抬起，防止脚本中止后手指逻辑卡死），
     * 并解除登记。通道 stop()/resetState() 调用；服务不可用时仅清登记。
     */
    private void releaseContinuations() {
        SparseArray<GestureDescription.StrokeDescription> pending;
        SparseArray<float[]> points;
        synchronized (activeStrokes) {
            if (continuingStrokes.size() == 0) {
                return;
            }
            pending = continuingStrokes.clone();
            points = continuingLastPoints.clone();
            continuingStrokes.clear();
            continuingLastPoints.clear();
        }
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        GestureQueueManager gqm = GestureQueueManager.getInstance();
        for (int i = 0; i < pending.size(); i++) {
            try {
                float[] pt = points.get(pending.keyAt(i));
                if (pt == null || pt.length < 2) {
                    continue; // 无最后位置则无从收尾（正常登记路径必带）
                }
                Path path = new Path();
                path.moveTo(pt[0], pt[1]);
                gqm.dispatch(new GestureDescription.Builder()
                        .addStroke(pending.valueAt(i).continueStroke(path, 0,
                                GestureQueueManager.MIN_TAP_SWIPE_DURATION_MS, false))
                        .build(), false);
            } catch (Throwable t) {
                Log.w(TAG, "release continuation failed: " + t);
            }
        }
    }

    /** 指编号与通道状态校验 */
    private boolean checkPointer(int pointerId) {
        return isAvailable() && pointerId >= 0 && pointerId < MAX_POINTERS;
    }

    /** 丢弃在途笔划（脚本异常中断未 up 时由门面 reset 调用，无障碍手势无远端指针状态） */
    public void resetState() {
        releaseContinuations();
        synchronized (activeStrokes) {
            activeStrokes.clear();
        }
        Arrays.fill(lastEventUptimes, 0L);
        Arrays.fill(lastPointerPoints, 0f);
    }
}
