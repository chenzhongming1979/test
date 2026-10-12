package com.autolua.engine.accessibility;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

/**
 * IAuxDumpService 实现（AIDL 服务端，运行在无障碍服务所在进程）。
 *
 * <p>dumpXml：内部走 {@link AccessibilityNodeTree#dumpToPipe}（NodeXmlDumper 序列化 →
 * 管道写端异步写），返回 {@code Bundle{status:int(0 成功), pfd:ParcelFileDescriptor(读端)}}，
 * 大 XML 规避 Binder 1MB 事务缓冲（对照集成文档 4.3.3）。</p>
 *
 * <p>跨进程经 {@link AuxDumpService} 绑定调用；单进程直用不经 Binder，直接用
 * {@link AuxDumpServiceLocal#get()}（同一实例）。</p>
 */
public class AuxDumpServiceImpl extends IAuxDumpService.Stub {
    private static final String TAG = "AuxDumpServiceImpl";

    /** 主线程 Handler：setNodeMode 切 serviceInfo 需回主线程执行 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public Bundle dumpXml(boolean includeNotImportant, int targetW, int targetH)
            throws RemoteException {
        AutoLuaAccessibilityService service = AutoLuaAccessibilityService.getInstance();
        Bundle result = new Bundle();
        if (service == null) {
            // 无障碍服务未连接：无法 dump
            result.putInt(AccessibilityNodeTree.KEY_STATUS, AccessibilityNodeTree.STATUS_ERROR);
            return result;
        }
        // includeNotImportant 同时作为可见性剪枝开关传入 dumpToPipe
        //（true=包含不可见节点整树更全；false=可见性剪枝，量小更快）
        Bundle pipe = AccessibilityNodeTree.dumpToPipe(service, includeNotImportant,
                targetW, targetH);
        int status = pipe.getInt(AccessibilityNodeTree.KEY_STATUS,
                AccessibilityNodeTree.STATUS_ERROR);
        result.putInt(AccessibilityNodeTree.KEY_STATUS, status);
        if (status == AccessibilityNodeTree.STATUS_OK) {
            ParcelFileDescriptor pfd = pipe.getParcelable(AccessibilityNodeTree.KEY_PFD);
            if (pfd != null) {
                result.putParcelable(AccessibilityNodeTree.KEY_PFD, pfd);
            } else {
                result.putInt(AccessibilityNodeTree.KEY_STATUS, AccessibilityNodeTree.STATUS_ERROR);
            }
        }
        return result;
    }

    @Override
    public void setNodeMode(final boolean includeNotImportant) throws RemoteException {
        // oneway：Binder 线程到达，setServiceInfo 回主线程执行更安全
        final AutoLuaAccessibilityService service = AutoLuaAccessibilityService.getInstance();
        if (service != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    service.setNodeMode(includeNotImportant);
                }
            });
        } else {
            Log.w(TAG, "setNodeMode: accessibility service not connected");
        }
    }

    @Override
    public boolean isReady() throws RemoteException {
        return AutoLuaAccessibilityService.isRunning();
    }
}
