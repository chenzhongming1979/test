package com.autolua.engine.input.inject;

import android.view.InputDevice;
import android.view.MotionEvent;

import java.util.Map;
import java.util.Random;

/**
 * MotionEvent 组装器（对照懒人 iil1.java:38-143）：
 * <ul>
 *     <li>预分配 PointerProperties[10] / PointerCoords[10] 复用，避免高频注入时的 GC 压力</li>
 *     <li>多指 action 位运算：ACTION_POINTER_DOWN = 5|(idx&lt;&lt;8)、ACTION_POINTER_UP = 6|(idx&lt;&lt;8)</li>
 *     <li>source 固定 SOURCE_TOUCHSCREEN(4098)，toolType 固定 TOOL_TYPE_FINGER</li>
 *     <li>downTime 由调用方统一（一次手势内共享同一 uptimeMillis）</li>
 *     <li>pressure/size 随机扰动 nextFloat()*50+1（可经 {@link Humanizer#setRandomizeCoords(boolean)} 关闭）</li>
 * </ul>
 * 注意：build() 内部只写预分配数组，事件对象由调用方用后严格 {@link MotionEvent#recycle()}。
 */
public final class MotionEventBuilder {

    private static final int SOURCE_TOUCHSCREEN = InputDevice.SOURCE_TOUCHSCREEN; // 4098

    /** 预分配 10 指属性数组 */
    private final MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[10];
    /** 预分配 10 指坐标数组 */
    private final MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[10];

    private final Random random = new Random();
    private final Humanizer humanizer;

    public MotionEventBuilder(Humanizer humanizer) {
        this.humanizer = humanizer;
        for (int i = 0; i < props.length; i++) {
            MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
            p.id = i;
            p.toolType = MotionEvent.TOOL_TYPE_FINGER;
            props[i] = p;
            MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
            c.orientation = 0.0f;
            c.size = 1.0f;
            c.pressure = 1.0f;
            coords[i] = c;
        }
    }

    /**
     * 组装注入事件。
     *
     * @param downTime 手势起始时间（uptimeMillis），全程统一
     * @param action   基础动作：ACTION_DOWN/ACTION_MOVE/ACTION_UP/ACTION_POINTER_DOWN/ACTION_POINTER_UP
     * @param active   当前按下指针快照（pointerId -&gt; {x,y}，LinkedHashMap 保持按下顺序决定 idx）
     * @param targetId 本次重点更新坐标的指（POINTER_DOWN/POINTER_UP 的 index 来源）
     * @param tx/ty    targetId 的最新坐标
     */
    public synchronized MotionEvent build(long downTime, int action,
                                          Map<Integer, float[]> active,
                                          int targetId, float tx, float ty) {
        int count = Math.max(1, active.size());
        int targetIndex = -1;
        int i = 0;
        for (Map.Entry<Integer, float[]> e : active.entrySet()) {
            if (i >= props.length) {
                break;
            }
            int id = e.getKey();
            props[i].id = id;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            if (id == targetId) {
                targetIndex = i;
                coords[i].x = tx;
                coords[i].y = ty;
            } else {
                float[] pos = e.getValue();
                coords[i].x = pos[0];
                coords[i].y = pos[1];
            }
            applyRandom(coords[i]);
            i++;
        }
        // 多指 action 位运算（对照 iil1.java:121-124）
        int act = action;
        if (action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_POINTER_UP) {
            act |= (targetIndex >= 0 ? targetIndex : 0) << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
        }
        long eventTime = android.os.SystemClock.uptimeMillis();
        return MotionEvent.obtain(downTime, eventTime, act, count, props, coords,
                0, 0, 1.0f, 1.0f, 0, 0, SOURCE_TOUCHSCREEN, 0);
    }

    /** 按人化配置施加 pressure/size 扰动（nextFloat()*50+1，iil1.java:111-114） */
    private void applyRandom(MotionEvent.PointerCoords c) {
        if (humanizer.isRandomizeCoords()) {
            c.pressure = random.nextFloat() * 50.0f + 1.0f;
            c.size = random.nextFloat() * 50.0f + 1.0f;
        } else {
            c.pressure = 1.0f;
            c.size = 1.0f;
        }
    }
}
