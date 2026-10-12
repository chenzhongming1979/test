package com.autolua.engine.accessibility;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * IAuxDumpService 宿主 Service（AIDL 服务端挂载点）。
 *
 * <p>注意：框架 {@link android.accessibilityservice.AccessibilityService#onBind} 为
 * final（android-34 jar 经 javap 验证），无法在 AutoLuaAccessibilityService 内挂载自定义
 * binder，故由本独立 Service 承载 IAuxDumpService（Manifest action
 * "com.autolua.engine.AUX_DUMP"）。本服务与无障碍服务同进程（未声明 android:process），
 * dump 能力仍依赖 AutoLuaAccessibilityService 已连接（isReady() 校验）。</p>
 *
 * <p>单进程直用不经 Binder，直接用 {@link AuxDumpServiceLocal#get()}。</p>
 */
public class AuxDumpService extends Service {

    @Override
    public void onCreate() {
        super.onCreate();
        // 预热同进程 dump 实现单例
        AuxDumpServiceLocal.get();
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (intent != null && AutoLuaAccessibilityService.ACTION_AUX_DUMP.equals(intent.getAction())) {
            return AuxDumpServiceLocal.get().asBinder();
        }
        return null;
    }
}
