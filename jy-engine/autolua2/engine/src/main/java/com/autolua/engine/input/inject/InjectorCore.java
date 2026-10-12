package com.autolua.engine.input.inject;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 注入核心：DIRECT_ROOT（uid==0 进程内）/ ROOT(app_process) / SHIZUKU(UserService)
 * 三种运行环境共用的单类实现，对照派大星 RootInputInjector.java：
 * <ul>
 *     <li>环境伪造 initFramework（:881-924）：反射 ActivityThread.newInstance、sCurrentActivityThread、
 *         mSystemThread=true、AppBindData.packageName="com.android.shell"、FakeContext、mInitialApplication</li>
 *     <li>InputManager 三层 fallback（:927-959）：
 *         ServiceManager.getService("input") → ActivityThread.getSystemContext().getSystemService("input")
 *         → InputManager.getInstance()，全反射</li>
 *     <li>多指状态机 + 人化轨迹（down/move 插值/up/tap/swipe/pinch/gesture/multitouch/reset）</li>
 *     <li>行协议命令解析 executeCommandLine（:612-705）</li>
 * </ul>
 * 注意：本类运行于无 app classloader 环境（app_process / Shizuku UserService），
 * 只允许依赖 android.* framework 与 java.*，不得引用 androidx/support。
 */
public final class InjectorCore {

    /** injectInputEvent 的异步模式标志（对照 :34） */
    private static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;
    /** 伪装包名：shell */
    public static final String PACKAGE_NAME = "com.android.shell";

    /** InputManager 实例（可能是隐藏构造产物，统一按 Object 反射持有） */
    private static volatile Object inputManager;
    private static volatile Method injectInputEventMethod;
    /** InputManager 获取来源（诊断注入被拒用：layer1-binder / layer2-systemService / layer3-getInstance） */
    private static volatile String inputManagerSource = "none";

    private static final Humanizer HUMANIZER = new Humanizer();
    private static final MotionEventBuilder BUILDER = new MotionEventBuilder(HUMANIZER);

    /** 当前按下指针：pointerId -> {x,y}（LinkedHashMap 保持按下顺序，决定多指事件里的 index） */
    private static final LinkedHashMap<Integer, float[]> ACTIVE_POINTERS = new LinkedHashMap<>();
    /** 每指 downTime */
    private static final Map<Integer, Long> FINGER_DOWN_TIMES = new ConcurrentHashMap<Integer, Long>();
    /** 每指最近位置（用于 move 插值起点与 up 沿用） */
    private static final Map<Integer, float[]> FINGER_POSITIONS = new ConcurrentHashMap<Integer, float[]>();

    private InjectorCore() {
    }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    /**
     * 完整初始化：伪造 ActivityThread 环境 + InputManager 三层 fallback。
     * app_process 独立进程（RootInputInjectorMain）入口使用。
     */
    public static synchronized boolean initFull() {
        ensureLooperPrepared();
        try {
            initFramework();
        } catch (Throwable t) {
            logErr("initFramework failed: " + t);
        }
        return initInputManager();
    }

    /**
     * 直接初始化：仅 InputManager（uid==0 的应用进程内、Shizuku shell 进程内，
     * framework 环境已由系统就绪，无需伪造）。
     */
    public static synchronized boolean initDirect() {
        ensureLooperPrepared();
        return initInputManager();
    }

    /** 注入核心是否就绪（持有可用 InputManager 与 injectInputEvent 方法） */
    public static boolean isReady() {
        return inputManager != null && injectInputEventMethod != null;
    }

    /** 主线程无 Looper 时补一个（反射调用 InputManager 内部 Handler 需要） */
    private static void ensureLooperPrepared() {
        try {
            if (Looper.myLooper() == null) {
                Looper.prepare();
            }
        } catch (Throwable ignore) {
            // 非 main 线程已存在 Looper 等场景忽略
        }
    }

    /**
     * 进程内环境伪造（UID=0 必需，对照 RootInputInjector:881-924）。
     */
    private static void initFramework() throws Exception {
        Class<?> atClass = Class.forName("android.app.ActivityThread");
        Constructor<?> ctor = atClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object at = ctor.newInstance();

        // sCurrentActivityThread = at
        Field currentField = atClass.getDeclaredField("sCurrentActivityThread");
        currentField.setAccessible(true);
        currentField.set(null, at);

        // mSystemThread = true
        try {
            Field sysThreadField = atClass.getDeclaredField("mSystemThread");
            sysThreadField.setAccessible(true);
            sysThreadField.setBoolean(at, true);
        } catch (Throwable t) {
            logErr("initFramework mSystemThread: " + t);
        }

        // mBoundApplication = AppBindData{appInfo.packageName = "com.android.shell"}
        try {
            Class<?> abdClass = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> abdCtor = abdClass.getDeclaredConstructor();
            abdCtor.setAccessible(true);
            Object abd = abdCtor.newInstance();
            ApplicationInfo appInfo = new ApplicationInfo();
            appInfo.packageName = PACKAGE_NAME;
            Field appInfoField = abdClass.getDeclaredField("appInfo");
            appInfoField.setAccessible(true);
            appInfoField.set(abd, appInfo);
            Field boundField = atClass.getDeclaredField("mBoundApplication");
            boundField.setAccessible(true);
            boundField.set(at, abd);
        } catch (Throwable t) {
            logErr("initFramework AppBindData: " + t);
        }

        // mInitialApplication = Instrumentation.newApplication(Application.class, new FakeContext(systemContext))
        try {
            Context systemContext = getSystemContext(atClass, at);
            if (systemContext != null) {
                Application app = Instrumentation.newApplication(Application.class,
                        new FakeContext(systemContext));
                Field initAppField = atClass.getDeclaredField("mInitialApplication");
                initAppField.setAccessible(true);
                initAppField.set(at, app);
            }
        } catch (Throwable t) {
            logErr("initFramework mInitialApplication: " + t);
        }
    }

    private static Context getSystemContext(Class<?> atClass, Object at) {
        try {
            Method m = atClass.getDeclaredMethod("getSystemContext");
            m.setAccessible(true);
            return (Context) m.invoke(at);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * InputManager 三层 fallback（对照 :927-959）：
     * 1. IInputManager.Stub.asInterface(ServiceManager.getService("input")) → 反射构造 InputManager
     * 2. ActivityThread.getSystemContext().getSystemService("input")
     * 3. InputManager.getInstance()（新版本公开 API）
     */
    private static synchronized boolean initInputManager() {
        if (isReady()) {
            return true;
        }
        inputManagerSource = "none";
        // --- 层 1：ServiceManager.getService("input") ---
        try {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getDeclaredMethod("getService", String.class).invoke(null, "input");
            if (binder != null) {
                Object proxy = Class.forName("android.hardware.input.IInputManager$Stub")
                        .getDeclaredMethod("asInterface", IBinder.class).invoke(null, binder);
                Class<?> imClass = Class.forName("android.hardware.input.InputManager");
                // 按参数运行时类型匹配构造函数（兼容不同版本签名差异）
                Constructor<?> matched = null;
                for (Constructor<?> c : imClass.getDeclaredConstructors()) {
                    Class<?>[] pts = c.getParameterTypes();
                    if (pts.length == 1 && pts[0].isInstance(proxy)) {
                        matched = c;
                        break;
                    }
                }
                if (matched != null) {
                    matched.setAccessible(true);
                    Object im = matched.newInstance(proxy);
                    bindInjectMethod(imClass, im);
                    if (isReady()) {
                        inputManagerSource = "layer1-binder(IInputManager)";
                        return true;
                    }
                }
            }
        } catch (Throwable ignore) {
        }

        // --- 层 2：ActivityThread.getSystemContext().getSystemService("input") ---
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = null;
            try {
                Field f = atClass.getDeclaredField("sCurrentActivityThread");
                f.setAccessible(true);
                at = f.get(null);
            } catch (Throwable ignore) {
            }
            if (at == null) {
                // 未伪造过环境时兜底 systemMain（shell 进程可用）
                Method systemMain = atClass.getDeclaredMethod("systemMain");
                systemMain.setAccessible(true);
                at = systemMain.invoke(null);
            }
            if (at != null) {
                Context sysCtx = getSystemContext(atClass, at);
                if (sysCtx != null) {
                    Object im = sysCtx.getSystemService("input");
                    if (im != null) {
                        bindInjectMethod(im.getClass(), im);
                        if (isReady()) {
                            inputManagerSource = "layer2-systemService";
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignore) {
        }

        // --- 层 3：InputManager.getInstance() ---
        try {
            Class<?> imClass = Class.forName("android.hardware.input.InputManager");
            Method getInstance = imClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object im = getInstance.invoke(null);
            if (im != null) {
                bindInjectMethod(imClass, im);
                if (isReady()) {
                    inputManagerSource = "layer3-getInstance";
                }
            }
        } catch (Throwable ignore) {
        }
        return isReady();
    }

    private static void bindInjectMethod(Class<?> cls, Object im) throws Exception {
        inputManager = im;
        Method m = cls.getMethod("injectInputEvent", InputEvent.class, int.class);
        m.setAccessible(true);
        injectInputEventMethod = m;
    }

    // ------------------------------------------------------------------
    // 事件注入
    // ------------------------------------------------------------------

    private static boolean injectEvent(InputEvent event) {
        try {
            Object r = injectInputEventMethod.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC);
            boolean ok = r instanceof Boolean && (Boolean) r;
            if (!ok) {
                // 系统静默拒绝（权限/deviceId 校验不过），打全事件字段便于定位
                logErr("inject REJECTED by system: " + describe(event)
                        + " | im=" + inputManagerSource);
            }
            return ok;
        } catch (Throwable t) {
            logErr("inject FAILED (throw): " + describe(event)
                    + " | im=" + inputManagerSource + " | ex=" + t);
            return false;
        }
    }

    /**
     * displayId 定向注入入口（虚拟屏任务书 §4.3，ShizukuUserServiceMain 专用）：
     * 事件由调用方自行组装（MotionEvent.obtain + 反射 setDisplayId），此处仅复用
     * 既有 injectInputEvent 反射底座。与主屏多指状态机完全解耦，互不影响。
     */
    static boolean injectEventRaw(InputEvent event) {
        return injectEvent(event);
    }

    /**
     * 事件全字段描述（诊断注入被拒用）：
     * KeyEvent → action/keyCode/metaState/scanCode/flags/deviceId/source/时间戳；
     * MotionEvent → action/pointerCount/各指 id 与坐标/downTime/deviceId/source/时间戳。
     * 仅用公开 API（actionToString/getFlags(InputEvent)/getDownTime(InputEvent) 为 hidden）。
     */
    private static String describe(InputEvent event) {
        StringBuilder sb = new StringBuilder(200);
        sb.append(event.getClass().getSimpleName()).append('{');
        if (event instanceof KeyEvent) {
            KeyEvent ke = (KeyEvent) event;
            sb.append("action=").append(keyActionName(ke.getAction()))
                    .append(", keyCode=").append(ke.getKeyCode())
                    .append('(').append(KeyEvent.keyCodeToString(ke.getKeyCode())).append(')')
                    .append(", repeat=").append(ke.getRepeatCount())
                    .append(", metaState=0x").append(Integer.toHexString(ke.getMetaState()))
                    .append(", scanCode=").append(ke.getScanCode())
                    .append(", flags=0x").append(Integer.toHexString(ke.getFlags()))
                    .append(", downTime=").append(ke.getDownTime());
        } else if (event instanceof MotionEvent) {
            MotionEvent me = (MotionEvent) event;
            sb.append("action=").append(me.actionToString(me.getAction()))
                    .append(", pointerCount=").append(me.getPointerCount())
                    .append(", pointers=[");
            for (int i = 0; i < me.getPointerCount(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append("id").append(me.getPointerId(i))
                        .append("=(x=").append(me.getX(i))
                        .append(",y=").append(me.getY(i)).append(')');
            }
            sb.append(']')
                    .append(", downTime=").append(me.getDownTime());
        }
        sb.append(", deviceId=").append(event.getDeviceId())
                .append(", source=0x").append(Integer.toHexString(event.getSource()))
                .append(", eventTime=").append(event.getEventTime())
                .append('}');
        return sb.toString();
    }

    /** KeyEvent action 名（KeyEvent.actionToString 为 hidden API，自行映射常用值） */
    private static String keyActionName(int action) {
        switch (action) {
            case KeyEvent.ACTION_DOWN:
                return "DOWN";
            case KeyEvent.ACTION_UP:
                return "UP";
            case KeyEvent.ACTION_MULTIPLE:
                return "MULTIPLE";
            default:
                return String.valueOf(action);
        }
    }

    // ------------------------------------------------------------------
    // 多指动作（全部 synchronized，状态机线程安全）
    // ------------------------------------------------------------------

    /** 按下（对照 doDown :104-124）。activePointers 为空发 ACTION_DOWN，否则 ACTION_POINTER_DOWN */
    public static synchronized boolean down(float x, float y, int pointerId, long downTime) {
        if (!isReady()) {
            logNotReady("down");
            return false;
        }
        if (pointerId < 0 || pointerId > 9) {
            logErr("down rejected: invalid pointerId=" + pointerId);
            return false;
        }
        if (ACTIVE_POINTERS.containsKey(pointerId)) {
            return true; // 幂等：已按下
        }
        if (downTime <= 0) {
            downTime = SystemClock.uptimeMillis();
        }
        FINGER_DOWN_TIMES.put(pointerId, downTime);
        boolean first = ACTIVE_POINTERS.isEmpty();
        ACTIVE_POINTERS.put(pointerId, new float[]{x, y});
        FINGER_POSITIONS.put(pointerId, new float[]{x, y});
        int action = first ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_POINTER_DOWN;
        MotionEvent ev = BUILDER.build(downTime, action, ACTIVE_POINTERS, pointerId, x, y);
        boolean ok = injectEvent(ev);
        ev.recycle();
        if (!ok) {
            // 注入失败回滚状态，保持状态机与系统侧一致
            ACTIVE_POINTERS.remove(pointerId);
            FINGER_POSITIONS.remove(pointerId);
            FINGER_DOWN_TIMES.remove(pointerId);
        }
        return ok;
    }

    /** 抬起（对照 doUp :569-603）。最后一指发 ACTION_UP，否则 ACTION_POINTER_UP */
    public static synchronized boolean up(int pointerId, float x, float y, long downTime) {
        if (!isReady()) {
            logNotReady("up");
            return false;
        }
        float[] ap = ACTIVE_POINTERS.get(pointerId);
        if (ap == null) {
            // 孤儿 up（down 未发生/注入失败已回滚）：系统侧无该指状态，等效 no-op。
            // 返回 true 避免误报失败；真实手势断裂会在 down/move 的 REJECTED 日志里体现。
            System.err.println("[InjectorCore] up orphan (pointer not active id=" + pointerId
                    + ", active=" + stateString() + ") -> no-op");
            return true;
        }
        if (x == 0 && y == 0) {
            float[] fp = FINGER_POSITIONS.get(pointerId);
            if (fp != null) {
                x = fp[0];
                y = fp[1];
            } else {
                x = ap[0];
                y = ap[1];
            }
        }
        if (downTime <= 0) {
            Long dt = FINGER_DOWN_TIMES.get(pointerId);
            downTime = dt != null ? dt : SystemClock.uptimeMillis();
        }
        int action = ACTIVE_POINTERS.size() == 1 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_POINTER_UP;
        MotionEvent ev = BUILDER.build(downTime, action, ACTIVE_POINTERS, pointerId, x, y);
        boolean ok = injectEvent(ev);
        ev.recycle();
        ACTIVE_POINTERS.remove(pointerId);
        FINGER_POSITIONS.remove(pointerId);
        FINGER_DOWN_TIMES.remove(pointerId);
        return ok;
    }

    /**
     * 移动（对照 doMove :216-295）：位移超过阈值时按 ceil(dist/threshold)
     * 步线性插值，每步 sleep 15ms；否则单步直发。
     */
    public static synchronized boolean move(float x, float y, int pointerId, long downTime) {
        if (!isReady()) {
            logNotReady("move");
            return false;
        }
        if (!ACTIVE_POINTERS.containsKey(pointerId)) {
            logErr("move rejected: pointer not active id=" + pointerId
                    + ", active=" + stateString());
            return false;
        }
        if (downTime <= 0) {
            Long dt = FINGER_DOWN_TIMES.get(pointerId);
            downTime = dt != null ? dt : SystemClock.uptimeMillis();
        }
        float[] fp = FINGER_POSITIONS.get(pointerId);
        if (fp != null) {
            float dx = x - fp[0];
            float dy = y - fp[1];
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            int threshold = HUMANIZER.getMoveThreshold();
            if (dist > threshold) {
                int steps = (int) Math.ceil(dist / threshold);
                for (int i = 1; i <= steps; i++) {
                    float t = (float) i / steps;
                    float ix = fp[0] + dx * t;
                    float iy = fp[1] + dy * t;
                    float[] ap = ACTIVE_POINTERS.get(pointerId);
                    if (ap != null) {
                        ap[0] = ix;
                        ap[1] = iy;
                    }
                    if (!injectMovePoint(pointerId, ix, iy, downTime)) {
                        return false;
                    }
                    try {
                        Thread.sleep(HUMANIZER.getMoveStepDelayMs());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                FINGER_POSITIONS.put(pointerId, new float[]{x, y});
                return true;
            }
        }
        if (!injectMovePoint(pointerId, x, y, downTime)) {
            return false;
        }
        FINGER_POSITIONS.put(pointerId, new float[]{x, y});
        return true;
    }

    /** 单步 move（无插值），供插值/滑动/手势复用 */
    private static boolean injectMovePoint(int pointerId, float x, float y, long downTime) {
        MotionEvent ev = BUILDER.build(downTime, MotionEvent.ACTION_MOVE,
                ACTIVE_POINTERS, pointerId, x, y);
        boolean ok = injectEvent(ev);
        ev.recycle();
        return ok;
    }

    /** 单击/长按（对照 doTap/doLongTap）：down → sleep(duration) → up */
    public static synchronized boolean tap(float x, float y, int pointerId, int durationMs) {
        int id = findFreePointerId(pointerId);
        if (id < 0 || !isReady()) {
            return false;
        }
        if (durationMs <= 0) {
            durationMs = HUMANIZER.getTapDurationMs();
        }
        long downTime = SystemClock.uptimeMillis();
        if (!down(x, y, id, downTime)) {
            return false;
        }
        quietSleep(durationMs);
        return up(id, x, y, downTime);
    }

    /**
     * 滑动（对照 doSwipe :461-530）：steps 均分 duration，逐步插值。
     */
    public static synchronized boolean swipe(float x1, float y1, float x2, float y2,
                                             int durationMs, int steps, int pointerId) {
        if (!isReady()) {
            return false;
        }
        int duration = durationMs > 0 ? durationMs : HUMANIZER.getDefaultSwipeDurationMs();
        if (steps <= 0) {
            steps = HUMANIZER.getSwipeSteps();
        }
        int id = findFreePointerId(pointerId);
        if (id < 0) {
            return false;
        }
        long downTime = SystemClock.uptimeMillis();
        if (!down(x1, y1, id, downTime)) {
            return false;
        }
        long stepMs = duration / steps;
        for (int i = 1; i <= steps; i++) {
            float t = (float) i / steps;
            float ix = x1 + (x2 - x1) * t;
            float iy = y1 + (y2 - y1) * t;
            if (!injectMovePoint(id, ix, iy, downTime)) {
                up(id, x1, y1, downTime);
                return false;
            }
            if (i < steps) {
                quietSleep(stepMs);
            }
        }
        return up(id, x2, y2, downTime);
    }

    /**
     * 双指捏合/张开（对照 doPinch :359-415）：两指沿垂直方向从 radius/2 过渡到 target/2。
     *
     * @param zoomIn true=放大（两指张开）；target 无效时按该语义推导目标间距
     */
    public static synchronized boolean pinch(float cx, float cy, float radius, float target,
                                             int durationMs, int steps, boolean zoomIn) {
        if (!isReady()) {
            return false;
        }
        if (radius <= 0) {
            radius = 200;
        }
        if (target <= 0) {
            target = zoomIn ? radius * 2 : radius / 2;
        }
        if (steps <= 0) {
            steps = HUMANIZER.getPinchSteps();
        }
        int duration = durationMs > 0 ? durationMs : HUMANIZER.getPinchDurationMs();
        long stepMs = duration / steps;
        float startOff = radius / 2f;
        float endOff = target / 2f;

        int idA = findFreePointerId(-1);
        if (idA < 0) {
            return false;
        }
        int idB = findFreePointerId(idA);
        if (idB < 0) {
            return false;
        }
        long downTime = SystemClock.uptimeMillis();
        if (!down(cx, cy - startOff, idA, downTime)) {
            return false;
        }
        if (!down(cx, cy + startOff, idB, downTime)) {
            up(idA, cx, cy - startOff, downTime);
            return false;
        }
        for (int i = 1; i <= steps; i++) {
            float off = startOff + (endOff - startOff) * i / steps;
            if (!injectMovePoint(idA, cx, cy - off, downTime)
                    || !injectMovePoint(idB, cx, cy + off, downTime)) {
                up(idB, 0, 0, downTime);
                up(idA, 0, 0, downTime);
                return false;
            }
            if (i < steps) {
                quietSleep(stepMs);
            }
        }
        // 先抬非首指（ACTION_POINTER_UP），再抬首指（ACTION_UP）
        up(idB, 0, 0, downTime);
        return up(idA, 0, 0, downTime);
    }

    /**
     * 多笔划手势。JSON：
     * <pre>
     * 多笔划：[{"points":[{"x":..,"y":..}...],"duration":300,"delay":0}...]
     * 单笔划（兼容派大星行协议）：[{"x":..,"y":..,"delay":..}...]
     * </pre>
     * 笔划间串行执行，笔划内按 duration 均分点间隔。
     */
    public static synchronized boolean gesture(String strokesJson, int pointerId) {
        if (strokesJson == null || strokesJson.trim().isEmpty()) {
            return false;
        }
        try {
            JSONArray arr = new JSONArray(strokesJson);
            if (arr.length() == 0) {
                return false;
            }
            JSONObject first = arr.optJSONObject(0);
            boolean multiStroke = first != null && first.has("points");
            if (multiStroke) {
                for (int s = 0; s < arr.length(); s++) {
                    JSONObject stroke = arr.getJSONObject(s);
                    int delay = stroke.optInt("delay", 0);
                    if (delay > 0) {
                        quietSleep(delay);
                    }
                    int id = findFreePointerId(pointerId);
                    if (!runStroke(stroke.getJSONArray("points"),
                            stroke.optInt("duration", 0), id)) {
                        return false;
                    }
                }
                return true;
            }
            return runStroke(arr, 0, findFreePointerId(pointerId));
        } catch (Throwable t) {
            logErr("gesture error: " + t);
            return false;
        }
    }

    /** 执行一笔划：down → 逐点 move（duration 均分） → up */
    private static boolean runStroke(JSONArray points, int durationMs, int id) throws Exception {
        if (id < 0 || !isReady() || points.length() == 0) {
            return false;
        }
        JSONObject p0 = points.getJSONObject(0);
        float x0 = (float) p0.optDouble("x", 0);
        float y0 = (float) p0.optDouble("y", 0);
        long downTime = SystemClock.uptimeMillis();
        if (!down(x0, y0, id, downTime)) {
            return false;
        }
        int segs = Math.max(1, points.length() - 1);
        long stepMs = durationMs > 0 ? durationMs / segs : 0;
        for (int i = 1; i < points.length(); i++) {
            JSONObject p = points.getJSONObject(i);
            float px = (float) p.optDouble("x", 0);
            float py = (float) p.optDouble("y", 0);
            if (!injectMovePoint(id, px, py, downTime)) {
                up(id, 0, 0, downTime);
                return false;
            }
            if (stepMs > 0) {
                quietSleep(stepMs);
            }
            int d = p.optInt("delay", 0);
            if (d > 0) {
                quietSleep(d);
            }
        }
        return up(id, 0, 0, downTime);
    }

    /**
     * 多指同帧（对照 doMultiTouch :297-357）：
     * {"duration":100,"touches":[{"id":0,"x":..,"y":..}...]}
     * 全部按下（POINTER_DOWN 递增）→ 保持 duration → 逆序抬起。
     */
    public static synchronized boolean multitouch(String json) {
        if (json == null || json.trim().isEmpty()) {
            return false;
        }
        try {
            JSONObject cfg = new JSONObject(json);
            int duration = cfg.optInt("duration", 100);
            JSONArray touches = cfg.getJSONArray("touches");
            int n = touches.length();
            if (n <= 0 || n > 10) {
                return false;
            }
            long downTime = SystemClock.uptimeMillis();
            int[] ids = new int[n];
            int done = 0;
            for (int i = 0; i < n; i++) {
                JSONObject t = touches.getJSONObject(i);
                int reqId = t.optInt("id", -1);
                int id = findFreePointerId(reqId);
                if (id < 0) {
                    break;
                }
                if (!down((float) t.optDouble("x", 0), (float) t.optDouble("y", 0), id, downTime)) {
                    break;
                }
                ids[i] = id;
                done = i + 1;
            }
            if (done < n) {
                // 未全部按下的失败路径：回收已按下的指
                for (int i = done - 1; i >= 0; i--) {
                    up(ids[i], 0, 0, downTime);
                }
                return false;
            }
            quietSleep(duration);
            for (int i = n - 1; i >= 0; i--) {
                up(ids[i], 0, 0, downTime);
            }
            return true;
        } catch (Throwable t) {
            logErr("multitouch error: " + t);
            return false;
        }
    }

    /**
     * 复位（对照 doReset :417-459）：逐个抬起活动指，
     * 再对 id 0-9 清扫 ACTION_UP 消除系统侧残留状态。
     */
    public static synchronized boolean reset() {
        long downTime = SystemClock.uptimeMillis();
        while (!ACTIVE_POINTERS.isEmpty()) {
            Integer id = ACTIVE_POINTERS.keySet().iterator().next();
            up(id, 0, 0, downTime);
        }
        for (int i = 0; i < 10; i++) {
            LinkedHashMap<Integer, float[]> single = new LinkedHashMap<Integer, float[]>();
            single.put(i, new float[]{0f, 0f});
            MotionEvent ev = BUILDER.build(downTime, MotionEvent.ACTION_UP, single, i, 0, 0);
            injectEvent(ev);
            ev.recycle();
        }
        FINGER_POSITIONS.clear();
        FINGER_DOWN_TIMES.clear();
        return true;
    }

    /** 运行时配置（行协议 config）：move_threshold / randomize 等 */
    public static synchronized boolean config(String key, String value) {
        return HUMANIZER.applyConfig(key, value);
    }

    /**
     * 键码注入（KeyEvent down+up，对齐系统 input keyevent 命令实现）：
     * deviceId=VIRTUAL_KEYBOARD + FLAG_VIRTUAL_HARD_KEY——缺省 deviceId（=0）在部分
     * 设备上会被 InputDispatcher 静默拒绝（返回 false 不抛异常，MotionEvent 不受影响）。
     */
    public static synchronized boolean key(int keycode) {
        if (!isReady()) {
            logNotReady("key");
            return false;
        }
        if (keycode <= 0) {
            logErr("key rejected: invalid keycode=" + keycode);
            return false;
        }
        long now = SystemClock.uptimeMillis();
        int flags = KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY;
        KeyEvent downEv = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keycode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags);
        if (!injectEvent(downEv)) {
            return false;
        }
        quietSleep(40);
        long upTime = SystemClock.uptimeMillis();
        KeyEvent upEv = new KeyEvent(now, upTime, KeyEvent.ACTION_UP, keycode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags);
        return injectEvent(upEv);
    }

    /** 当前活动指快照（日志/调试用） */
    public static synchronized String stateString() {
        return ACTIVE_POINTERS.keySet().toString();
    }

    // ------------------------------------------------------------------
    // 行协议命令（stdin/stdout，对照 executeCommand :709-849）
    // ------------------------------------------------------------------

    /**
     * 执行一行协议命令。
     * <pre>
     * down x y id [downTime] / move x y id [downTime] / up id x y [downTime]
     * tap x y [id] [duration] / longtap x y [duration] [id]
     * swipe x1 y1 x2 y2 [duration] [steps] [id]
     * pinch cx cy radius target [duration] [steps] / zoom ...
     * multitouch &lt;json&gt; / gesture &lt;json&gt; [id] / key &lt;keycode&gt; / reset / config k v / ping
     * </pre>
     * gesture/multitouch 的 JSON 参数可能含空格，整体取"行内第一个空格之后的内容"。
     */
    public static boolean executeCommandLine(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        try {
            String[] args;
            String cmd;
            int sp = trimmed.indexOf(' ');
            if (sp < 0) {
                cmd = trimmed;
                args = new String[]{cmd};
            } else {
                cmd = trimmed.substring(0, sp);
                String rest = trimmed.substring(sp + 1).trim();
                if ("gesture".equals(cmd) || "multitouch".equals(cmd)) {
                    args = new String[]{cmd, rest};
                } else {
                    args = trimmed.split("\\s+");
                }
            }
            if ("ping".equals(cmd)) {
                return true;
            }
            if ("reset".equals(cmd)) {
                return reset();
            }
            if ("config".equals(cmd)) {
                return args.length >= 3 && config(args[1], args[2]);
            }
            if ("down".equals(cmd)) {
                return args.length >= 4
                        && down(parseFloat(args[1]), parseFloat(args[2]),
                        parseInt(args[3]), args.length >= 5 ? parseLong(args[4]) : 0L);
            }
            if ("move".equals(cmd)) {
                return args.length >= 4
                        && move(parseFloat(args[1]), parseFloat(args[2]),
                        parseInt(args[3]), args.length >= 5 ? parseLong(args[4]) : 0L);
            }
            if ("up".equals(cmd)) {
                return args.length >= 4
                        && up(parseInt(args[1]), parseFloat(args[2]), parseFloat(args[3]),
                        args.length >= 5 ? parseLong(args[4]) : 0L);
            }
            if ("tap".equals(cmd)) {
                return args.length >= 3 && tap(parseFloat(args[1]), parseFloat(args[2]),
                        args.length >= 4 ? parseInt(args[3]) : -1,
                        args.length >= 5 ? parseInt(args[4]) : 0);
            }
            if ("longtap".equals(cmd)) {
                return args.length >= 3 && tap(parseFloat(args[1]), parseFloat(args[2]),
                        args.length >= 5 ? parseInt(args[5]) : -1,
                        args.length >= 4 ? parseInt(args[4]) : HUMANIZER.getLongTapDurationMs());
            }
            if ("swipe".equals(cmd)) {
                return args.length >= 5 && swipe(parseFloat(args[1]), parseFloat(args[2]),
                        parseFloat(args[3]), parseFloat(args[4]),
                        args.length >= 6 ? parseInt(args[5]) : 0,
                        args.length >= 7 ? parseInt(args[6]) : 0,
                        args.length >= 8 ? parseInt(args[7]) : -1);
            }
            if ("pinch".equals(cmd)) {
                return args.length >= 5 && pinch(parseFloat(args[1]), parseFloat(args[2]),
                        parseFloat(args[3]), parseFloat(args[4]),
                        args.length >= 6 ? parseInt(args[5]) : 0,
                        args.length >= 7 ? parseInt(args[6]) : 0,
                        true);
            }
            if ("zoom".equals(cmd)) {
                return args.length >= 5 && pinch(parseFloat(args[1]), parseFloat(args[2]),
                        parseFloat(args[3]), parseFloat(args[4]),
                        args.length >= 6 ? parseInt(args[5]) : 0,
                        args.length >= 7 ? parseInt(args[6]) : 0,
                        false);
            }
            if ("multitouch".equals(cmd)) {
                return args.length >= 2 && multitouch(args[1]);
            }
            if ("key".equals(cmd)) {
                return args.length >= 2 && key(parseInt(args[1]));
            }
            if ("gesture".equals(cmd)) {
                return args.length >= 2
                        && gesture(args[1], args.length >= 3 ? parseInt(args[2]) : -1);
            }
            return false;
        } catch (Throwable t) {
            logErr("executeCommandLine error: " + t);
            return false;
        }
    }

    private static float parseFloat(String s) {
        return Float.parseFloat(s);
    }

    private static int parseInt(String s) {
        return Integer.parseInt(s.trim());
    }

    private static long parseLong(String s) {
        return Long.parseLong(s.trim());
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 找一个空闲 pointerId：优先 prefer（>=0 且未被占用），否则取最小空闲 id */
    private static int findFreePointerId(int prefer) {
        if (prefer >= 0 && prefer < 10 && !ACTIVE_POINTERS.containsKey(prefer)) {
            return prefer;
        }
        for (int i = 0; i < 10; i++) {
            if (!ACTIVE_POINTERS.containsKey(i)) {
                return i;
            }
        }
        return -1;
    }

    private static void quietSleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void logErr(String msg) {
        // stderr 由宿主（app_process 客户端 / Shizuku 日志）透传
        System.err.println("[InjectorCore] " + msg);
    }

    /** 注入核心未就绪诊断（InputManager 实例/方法缺失时命令必败，必须有日志可查） */
    private static void logNotReady(String op) {
        System.err.println("[InjectorCore] " + op + " rejected: core not ready"
                + " (inputManager=" + (inputManager == null ? "null" : "present")
                + ", method=" + (injectInputEventMethod == null ? "null" : "present")
                + ", source=" + inputManagerSource + ")");
    }
}
