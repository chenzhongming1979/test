package com.autolua.engine.conceal;

import android.graphics.Rect;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 虚拟屏建屏内核（虚拟屏任务书 §4.1）：shell uid（Shizuku UserService / root）下创建/销毁
 * 虚拟屏 + display 域事务四件套的双路径自适应反射封装，风格对齐 {@link SurfaceControlWrap}。
 * <p>
 * 双分支口径（scrcpy 2.4+ 同款适配，待真机实测）：
 * <ul>
 *     <li>路径A（Android ≤14）：{@code SurfaceControl} 静态 createDisplay/destroyDisplay +
 *         全局 openTransaction/closeTransaction + 静态 display 域四件套
 *         setDisplaySurface/setDisplayProjection/setDisplayLayerStack/setDisplaySize（与
 *         {@link SurfaceControlWrap} 同口径）；</li>
 *     <li>路径B（Android 15+/SDK 35：上述静态方法整体移除）：隐藏类
 *         {@code android.view.DisplayControl} 的 createVirtualDisplay/destroyVirtualDisplay +
 *         {@code SurfaceControl$Transaction} 实例方法（display 四件套移到了 Transaction 上，
 *         事务经 new Transaction() + apply() 提交；DisplayControl 无 secure 参数，旧路径的
 *         boolean secure 在此路径被忽略）。</li>
 * </ul>
 * 自适应规则：优先路径A——方法句柄解析失败（NoSuchMethod/SecurityException）或调用被拒时
 * 自动切换路径B；DisplayControl 类加载双重尝试（boot classloader 直载 → 经 SurfaceControl 的
 * classloader 显式加载）。全部方法句柄静态缓存、懒加载，调用方无需关心系统版本。
 * <p>
 * 事务句柄契约：{@link #openTransaction()} 路径A 返回 null（开启的是全局事务，四件套走静态方法、
 * 句柄参数被忽略），路径B 返回 Transaction 实例；四件套方法把句柄原样回传；
 * {@link #closeTransaction(Object)} 路径A 调 closeTransaction（全局），路径B 调 tx.apply() 提交。
 */
public final class DisplayControlWrap {

    private static final String TAG = "DisplayControlWrap";

    /** 0 = 路径A（SurfaceControl 静态）；1 = 路径B（DisplayControl + Transaction）；-1 = 未解析 */
    private static volatile int mode = -1;
    private static final Object RESOLVE_LOCK = new Object();

    // ---- 路径A 句柄（android.view.SurfaceControl 静态方法，Android ≤14）----
    private static Method legacyCreateDisplay;        // static IBinder createDisplay(String,boolean)
    private static Method legacyDestroyDisplay;       // static void destroyDisplay(IBinder)
    private static Method legacySetDisplaySurface;    // static void setDisplaySurface(IBinder,Surface)
    private static Method legacySetDisplayProjection; // static void setDisplayProjection(IBinder,int,Rect,Rect)
    private static Method legacySetDisplayLayerStack; // static void setDisplayLayerStack(IBinder,int)
    private static Method legacySetDisplaySize;       // static void setDisplaySize(IBinder,int,int)
    private static Method legacyOpenTransaction;      // static void openTransaction()
    private static Method legacyCloseTransaction;     // static void closeTransaction()

    // ---- 路径B 句柄（android.view.DisplayControl + SurfaceControl$Transaction，Android 15+）----
    private static Method dcCreateVirtualDisplay;     // static IBinder createVirtualDisplay(String)
    private static Method dcDestroyVirtualDisplay;    // static void destroyVirtualDisplay(IBinder)
    private static Constructor<?> txCtor;             // SurfaceControl$Transaction()
    private static Method txSetDisplaySurface;        // Transaction setDisplaySurface(IBinder,Surface)
    private static Method txSetDisplayProjection;     // Transaction setDisplayProjection(IBinder,int,Rect,Rect)
    private static Method txSetDisplayLayerStack;     // Transaction setDisplayLayerStack(IBinder,int)
    private static Method txSetDisplaySize;           // Transaction setDisplaySize(IBinder,int,int)（个别 ROM 可能缺，容错为可选）
    private static Method txApply;                    // Transaction apply()

    private DisplayControlWrap() {
    }

    /** 当前生效路径（0=SurfaceControl 静态 / 1=DisplayControl+Transaction / -1=未解析），诊断日志用 */
    public static int currentMode() {
        return mode;
    }

    // ------------------------------------------------------------------
    // 建屏 / 销屏
    // ------------------------------------------------------------------

    /**
     * 创建虚拟屏 token。路径A：SurfaceControl.createDisplay(name, secure)；
     * 路径B：DisplayControl.createVirtualDisplay(name)（无 secure 参数）。
     * 失败抛 RuntimeException（原因见 cause）。
     */
    public static IBinder createVirtualDisplay(String name, boolean secure) {
        resolve();
        if (mode == 0) {
            try {
                return (IBinder) legacyCreateDisplay.invoke(null, name, secure);
            } catch (Throwable t) {
                // 句柄在但调用被拒（SecurityException 等）：按自适应规则切 DisplayControl 路径
                Log.w(TAG, "legacy createDisplay invoke failed, fallback to DisplayControl: " + t);
                switchToDisplayControlMode(t);
            }
        }
        try {
            return (IBinder) dcCreateVirtualDisplay.invoke(null, name);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * 销毁虚拟屏 token（自动按当前路径 + 另一路径兜底，兼容运行中途发生路径切换的历史 token）。
     */
    public static void destroyVirtualDisplay(IBinder token) {
        resolve();
        Throwable primary;
        try {
            if (mode == 0) {
                legacyDestroyDisplay.invoke(null, token);
            } else {
                dcDestroyVirtualDisplay.invoke(null, token);
            }
            return;
        } catch (Throwable t) {
            primary = t;
            Log.w(TAG, "destroyVirtualDisplay via mode " + mode + " failed: " + t);
        }
        try {
            Method other = mode == 0 ? dcDestroyVirtualDisplay : legacyDestroyDisplay;
            if (other != null) {
                other.invoke(null, token);
                return;
            }
        } catch (Throwable t) {
            primary = t;
        }
        throw new RuntimeException(primary);
    }

    // ------------------------------------------------------------------
    // display 域事务四件套（句柄契约见类注释）
    // ------------------------------------------------------------------

    /** 开启事务：路径A 开全局事务并返回 null 句柄；路径B 返回 new SurfaceControl.Transaction() */
    public static Object openTransaction() {
        resolve();
        try {
            if (mode == 0) {
                legacyOpenTransaction.invoke(null);
                return null;
            }
            return txCtor.newInstance();
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /** 提交事务：路径A closeTransaction（全局）；路径B tx.apply() */
    public static void closeTransaction(Object transaction) {
        resolve();
        try {
            if (mode == 0) {
                legacyCloseTransaction.invoke(null);
            } else {
                txApply.invoke(transaction);
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void setDisplaySurface(Object transaction, IBinder displayToken, Surface surface) {
        resolve();
        try {
            if (mode == 0) {
                legacySetDisplaySurface.invoke(null, displayToken, surface);
            } else {
                txSetDisplaySurface.invoke(transaction, displayToken, surface);
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void setDisplayProjection(Object transaction, IBinder displayToken,
                                            int orientation, Rect layerStackRect, Rect displayRect) {
        resolve();
        try {
            if (mode == 0) {
                legacySetDisplayProjection.invoke(null, displayToken, orientation, layerStackRect, displayRect);
            } else {
                txSetDisplayProjection.invoke(transaction, displayToken, orientation, layerStackRect, displayRect);
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void setDisplayLayerStack(Object transaction, IBinder displayToken, int layerStack) {
        resolve();
        try {
            if (mode == 0) {
                legacySetDisplayLayerStack.invoke(null, displayToken, layerStack);
            } else {
                txSetDisplayLayerStack.invoke(transaction, displayToken, layerStack);
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void setDisplaySize(Object transaction, IBinder displayToken, int width, int height) {
        resolve();
        try {
            if (mode == 0) {
                legacySetDisplaySize.invoke(null, displayToken, width, height);
            } else if (txSetDisplaySize != null) {
                txSetDisplaySize.invoke(transaction, displayToken, width, height);
            } else {
                // 个别 ROM Transaction 缺 setDisplaySize：尺寸由 setDisplayProjection 的
                // layerStackRect 表达，不致命，跳过（待真机实测记录进 07）
                Log.w(TAG, "Transaction.setDisplaySize unavailable, skip");
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    // ------------------------------------------------------------------
    // 内部实现：句柄解析与缓存
    // ------------------------------------------------------------------

    private static void resolve() {
        if (mode >= 0) {
            return;
        }
        synchronized (RESOLVE_LOCK) {
            if (mode >= 0) {
                return;
            }
            if (resolveLegacy()) {
                mode = 0;
                Log.i(TAG, "virtual display path = SurfaceControl static (Android <= 14)");
                return;
            }
            if (resolveDisplayControl()) {
                mode = 1;
                Log.i(TAG, "virtual display path = DisplayControl + Transaction (Android 15+)");
                return;
            }
            throw new RuntimeException(
                    "no virtual display API available (SurfaceControl statics removed and DisplayControl unresolvable)");
        }
    }

    /** 路径A 解析：Android ≤14 静态四件套齐全即生效（SDK 35 起整体移除 → NoSuchMethod） */
    private static boolean resolveLegacy() {
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            legacyCreateDisplay = sc.getMethod("createDisplay", String.class, boolean.class);
            legacyDestroyDisplay = sc.getMethod("destroyDisplay", IBinder.class);
            legacySetDisplaySurface = sc.getMethod("setDisplaySurface", IBinder.class, Surface.class);
            legacySetDisplayProjection = sc.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class);
            legacySetDisplayLayerStack = sc.getMethod("setDisplayLayerStack", IBinder.class, int.class);
            legacySetDisplaySize = sc.getMethod("setDisplaySize", IBinder.class, int.class, int.class);
            legacyOpenTransaction = sc.getMethod("openTransaction");
            legacyCloseTransaction = sc.getMethod("closeTransaction");
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "legacy SurfaceControl static path unavailable: " + t);
            return false;
        }
    }

    /** 路径B 解析：DisplayControl + Transaction（scrcpy 2.4+ 同款，待真机实测） */
    private static boolean resolveDisplayControl() {
        try {
            Class<?> dc = loadDisplayControlClass();
            dcCreateVirtualDisplay = findStringMethod(dc, "createVirtualDisplay");
            dcDestroyVirtualDisplay = dc.getDeclaredMethod("destroyVirtualDisplay", IBinder.class);
            if (dcCreateVirtualDisplay == null) {
                throw new NoSuchMethodException("DisplayControl.createVirtualDisplay(String) not found");
            }
            dcCreateVirtualDisplay.setAccessible(true);
            dcDestroyVirtualDisplay.setAccessible(true);

            Class<?> tx = Class.forName("android.view.SurfaceControl$Transaction");
            txCtor = tx.getDeclaredConstructor();
            txCtor.setAccessible(true);
            txSetDisplaySurface = tx.getDeclaredMethod("setDisplaySurface", IBinder.class, Surface.class);
            txSetDisplayProjection = tx.getDeclaredMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class);
            txSetDisplayLayerStack = tx.getDeclaredMethod("setDisplayLayerStack", IBinder.class, int.class);
            txApply = tx.getDeclaredMethod("apply");
            Method[] required = {txSetDisplaySurface, txSetDisplayProjection, txSetDisplayLayerStack, txApply};
            for (Method m : required) {
                m.setAccessible(true);
            }
            // setDisplaySize 属可选增强（缺省时由 projection 的 layerStackRect 表达尺寸）
            try {
                txSetDisplaySize = tx.getDeclaredMethod("setDisplaySize", IBinder.class, int.class, int.class);
                txSetDisplaySize.setAccessible(true);
            } catch (Throwable t) {
                txSetDisplaySize = null;
                Log.i(TAG, "Transaction.setDisplaySize not found (optional): " + t);
            }
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "DisplayControl path unavailable: " + t);
            return false;
        }
    }

    /**
     * DisplayControl 双重尝试：先 boot classloader 直载；失败再经 SurfaceControl 的
     * classloader 显式加载（部分 app_process 派生环境 forName 委派异常，待真机实测）。
     */
    private static Class<?> loadDisplayControlClass() throws ClassNotFoundException {
        try {
            return Class.forName("android.view.DisplayControl");
        } catch (ClassNotFoundException first) {
            ClassLoader boot = Class.forName("android.view.SurfaceControl").getClassLoader();
            return Class.forName("android.view.DisplayControl", true, boot);
        }
    }

    /** 防御式匹配：static 单 String 参数的同名方法（不硬编码返回类型/所属 SDK 差异） */
    private static Method findStringMethod(Class<?> clazz, String name) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (!name.equals(m.getName())) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 1 && params[0] == String.class) {
                return m;
            }
        }
        return null;
    }

    /** 路径A 调用被拒时切换路径B；切换失败抛 RuntimeException（含两路原因） */
    private static void switchToDisplayControlMode(Throwable cause) {
        synchronized (RESOLVE_LOCK) {
            if (mode == 1) {
                return;
            }
            if (resolveDisplayControl()) {
                mode = 1;
                Log.i(TAG, "switched to DisplayControl + Transaction path");
                return;
            }
        }
        throw new RuntimeException("both virtual display paths failed", cause);
    }
}
