package com.autolua.engine.conceal;

import android.app.Service;
import android.graphics.Point;
import android.os.Build;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;

import java.lang.reflect.Method;

public final class IWindowManager {
    private static final String TAG = "IWindowManager";
    public static final int MAIN_DISPLAY_TOKEN = 0;
    private static Object iWindowManagerObject ;
    private static Method getBaseDisplaySize ;
    private static Method getBaseDisplayDensity;
    private static Method getRotation;
    static{
        try{
            Class iWindowManager = Class.forName("android.view.IWindowManager");
            Class iWindowManagerStub = Class.forName("android.view.IWindowManager$Stub");
            Method asInterface = iWindowManagerStub.getDeclaredMethod(
                    "asInterface", IBinder.class);
            IBinder iWindowManagerIBinder = ServiceManager.getService(Service.WINDOW_SERVICE);
            iWindowManagerObject = asInterface.invoke(null,iWindowManagerIBinder);
            getBaseDisplaySize = iWindowManager.getDeclaredMethod(
                    "getBaseDisplaySize",int.class, Point.class);
            getBaseDisplayDensity = iWindowManager.getDeclaredMethod(
                    "getBaseDisplayDensity",int.class);
            if(Build.VERSION.SDK_INT >=26)
                getRotation = iWindowManager.getDeclaredMethod("getDefaultDisplayRotation");
            else
                getRotation = iWindowManager.getDeclaredMethod("getRotation");
        }catch(Exception e)
        {
            e.printStackTrace(System.err);
        }
    }

    /** DisplayManagerGlobal fallback 顺带取到的主屏 densityDpi（-1 = 未知），供 getBaseDisplayDensity 复用 */
    private static volatile int fallbackDensity = -1;

    public static void getBaseDisplaySize(int token, Point point)
    {
        try{
            getBaseDisplaySize.invoke(iWindowManagerObject,token,point);
            if (point != null && point.x > 0 && point.y > 0)
                return;
        }catch(Exception e)
        {
            e.printStackTrace(System.err);
        }
        // §6.1b：IWindowManager 隐藏签名失效（15/16 ROM 变更）时不再静默留 0——
        // 经 DisplayManagerGlobal.getDisplayIds/getDisplayInfo 取主屏 w/h；
        // 仍失败保留 0，由调用方（DisplayImplement.initialize）显式抛可读错误
        if (point != null)
            fillFromDisplayManagerGlobal(token, point);
    }

    /**
     * DisplayManagerGlobal fallback（任务书 §6.1b，参考 RootScreenshotProvider.initScreenParams 的
     * getMainDisplayInfo）：getDisplayIds 后优先匹配请求的 displayId（MAIN_DISPLAY_TOKEN=0 即
     * DEFAULT_DISPLAY，通常 ids[0]），不在列表再退回首个可用屏；取 DisplayInfo 的
     * width/height/densityDpi。成功填充 point 并刷新 {@link #fallbackDensity}，失败返回 false。
     */
    private static boolean fillFromDisplayManagerGlobal(int displayId, Point point)
    {
        try{
            Class<?> global = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object instance = global.getDeclaredMethod("getInstance").invoke(null);
            int[] ids = (int[]) global.getDeclaredMethod("getDisplayIds").invoke(instance);
            if (ids == null || ids.length == 0)
                return false;
            Object info = null;
            Method getDisplayInfo = global.getDeclaredMethod("getDisplayInfo", int.class);
            for (int id : ids)
            {
                if (id == displayId)
                {
                    info = getDisplayInfo.invoke(instance, id);
                    break;
                }
            }
            if (info == null)
                info = getDisplayInfo.invoke(instance, ids[0]);
            if (info == null)
                return false;
            Class<?> c = info.getClass();
            int w = c.getField("width").getInt(info);
            int h = c.getField("height").getInt(info);
            if (w <= 0 || h <= 0)
                return false;
            point.set(w, h);
            try{
                fallbackDensity = c.getField("densityDpi").getInt(info);
            }catch (Throwable ignore)
            {
            }
            Log.i(TAG, "base display size via DisplayManagerGlobal fallback: " + w + "x" + h);
            return true;
        }catch (Throwable t)
        {
            Log.w(TAG, "DisplayManagerGlobal fallback failed: " + t);
            return false;
        }
    }



    public static int getRotation()
    {
        int result = -1;
        try{
            result =(int)getRotation.invoke(iWindowManagerObject);
        }catch(Exception e)
        {
            e.printStackTrace(System.err);
        }
        if (result >= 0)
            return result;
        // §6.1b：getDefaultDisplayRotation 反射失效时经 DisplayManagerGlobal 取主屏 rotation
        try{
            Class<?> global = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object instance = global.getDeclaredMethod("getInstance").invoke(null);
            int[] ids = (int[]) global.getDeclaredMethod("getDisplayIds").invoke(instance);
            if (ids != null && ids.length > 0)
            {
                Object info = global.getDeclaredMethod("getDisplayInfo", int.class).invoke(instance, ids[0]);
                if (info != null)
                    result = info.getClass().getField("rotation").getInt(info);
            }
        }catch (Throwable t)
        {
            Log.w(TAG, "rotation DisplayManagerGlobal fallback failed: " + t);
        }
        return result;
    }
    public static int getBaseDisplayDensity(int displayId)
    {
        int result = -1;
        try{
            result =(int)getBaseDisplayDensity.invoke(iWindowManagerObject,displayId);
        }catch(Exception e)
        {
            e.printStackTrace(System.err);
        }
        if (result > 0)
            return result;
        // §6.1b：随尺寸 fallback 一并取到的 densityDpi（未取过则补拉一次 DisplayInfo）
        if (fallbackDensity <= 0)
        {
            Point p = new Point();
            fillFromDisplayManagerGlobal(displayId, p);
        }
        return fallbackDensity > 0 ? fallbackDensity : result;
    }
}
