package com.autolua.engine.input.inject;

import android.os.Process;

/**
 * 通道一：DIRECT_ROOT —— uid==0 时进程内直接反射 injectInputEvent（零 fork）。
 * 复用 {@link InjectorCore}（与 RootInputInjectorMain / ShizukuUserServiceMain 共用核心）。
 */
public class DirectRootChannel implements IInjectChannel {

    @Override
    public String name() {
        return "DIRECT_ROOT";
    }

    @Override
    public synchronized boolean start() {
        if (Process.myUid() != 0) {
            return false;
        }
        if (!InjectorCore.isReady()) {
            // 进程内 uid==0：framework 已就绪，先尝试直接初始化；
            // 失败（如 engine 运行在无应用环境的 app_process）再走完整伪造
            if (!InjectorCore.initDirect()) {
                InjectorCore.initFull();
            }
        }
        return InjectorCore.isReady();
    }

    @Override
    public void stop() {
        InjectorCore.reset();
    }

    @Override
    public boolean isAvailable() {
        return Process.myUid() == 0 && InjectorCore.isReady();
    }

    @Override
    public boolean injectDown(int pointerId, float x, float y, long downTime) {
        return InjectorCore.down(x, y, pointerId, downTime);
    }

    @Override
    public boolean injectMove(int pointerId, float x, float y, long downTime) {
        return InjectorCore.move(x, y, pointerId, downTime);
    }

    @Override
    public boolean injectUp(int pointerId, float x, float y, long downTime) {
        return InjectorCore.up(pointerId, x, y, downTime);
    }

    @Override
    public boolean tap(float x, float y) {
        return InjectorCore.tap(x, y, -1, 0);
    }

    @Override
    public boolean swipe(float x1, float y1, float x2, float y2, int durationMs) {
        return InjectorCore.swipe(x1, y1, x2, y2, durationMs, 0, -1);
    }

    @Override
    public boolean pinch(float cx, float cy, float radius, float angle, int durationMs, boolean zoomIn) {
        return InjectorCore.pinch(cx, cy, radius, angle, durationMs, 0, zoomIn);
    }

    @Override
    public boolean gestureJson(String strokesJson) {
        return InjectorCore.gesture(strokesJson, -1);
    }

    @Override
    public boolean injectKey(int keycode) {
        return InjectorCore.key(keycode);
    }
}
