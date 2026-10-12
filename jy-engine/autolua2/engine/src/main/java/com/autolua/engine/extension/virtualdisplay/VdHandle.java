package com.autolua.engine.extension.virtualdisplay;

import com.autolua.engine.input.inject.ShizukuInjectorChannel;

/**
 * 虚拟屏句柄（虚拟屏任务书 §4/§5）：VirtualDisplayLib.create 成功后返回，经引擎
 * JavaObjectWrapper 自动包装为 Lua userdata——脚本 {@code vd:tap(x, y)} 冒号调用直达
 * 下列 Java 方法（对象绑 displayId，坐标即虚拟屏自身坐标，脚本作者无感知）。
 *
 * <p>方法返回 int 错误码（0 成功）的语义由 VirtualDisplayLib 内嵌 Lua 组合层翻译为
 * true / error()，对齐 02-Lua-API规范 §20。图色系方法（findColor/findImage/…）不在
 * Java 侧：由组合层 {@code vd:__frame()} 取原始帧字节 + images.fromData + Bitmap
 * 同名方法拼装（见 VirtualDisplayLib.VD_LUA）。</p>
 */
public final class VdHandle {

    private final ShizukuInjectorChannel channel;
    private final VdFrameClient frame;
    private final int displayId;
    private final int width;
    private final int height;
    private volatile boolean destroyed;

    VdHandle(ShizukuInjectorChannel channel, VdFrameClient frame,
             int displayId, int width, int height) {
        this.channel = channel;
        this.frame = frame;
        this.displayId = displayId;
        this.width = width;
        this.height = height;
    }

    /** 在该屏拉起应用（shell am start --display；activity 空/缺省走包默认入口）。返回 0 成功 */
    public int runApp(String pkg, String activity) {
        checkAlive();
        return channel.startAppOnDisplay(pkg, activity, displayId);
    }

    /** 单击。返回 0 成功 / 非 0 错误码（语义见 IInjectorService.aidl 注释） */
    public int tap(int x, int y) {
        checkAlive();
        return channel.tapOnDisplay(displayId, x, y, 0);
    }

    /** 长按（ms<=0 服务端缺省 1000ms）。返回码同 tap */
    public int longTap(int x, int y, int durationMs) {
        checkAlive();
        return channel.longTapOnDisplay(displayId, x, y, durationMs);
    }

    /** 滑动（durationMs<=0 服务端缺省 300ms）。返回码同 tap */
    public int swipe(int x1, int y1, int x2, int y2, int durationMs) {
        checkAlive();
        return channel.swipeOnDisplay(displayId, x1, y1, x2, y2, durationMs);
    }

    /** 虚拟屏 displayId（fallback 句柄时为 0x40000000+ 内部值，am start/注入均不可用） */
    public int getDisplayId() {
        return displayId;
    }

    /** 尺寸 → Lua 多返回值 w, h（JavaObjectWrapper 对 Object[] 展开） */
    public Object[] getSize() {
        return new Object[]{width, height};
    }

    /**
     * 帧原始字节（组合层内部原语）：返回 {像素 byte[]（w*h*4 紧凑 RGBA）, w, h} 经
     * JavaObjectWrapper 展开为 data, w, h 三值；从未出帧/重试耗尽/已销毁返回 null。
     */
    public Object[] __frame() {
        if (destroyed || frame == null) {
            return null;
        }
        byte[] pixels = frame.getFrame();
        if (pixels == null) {
            return null;
        }
        return new Object[]{pixels, width, height};
    }

    /** 销毁虚拟屏并释放帧泵（幂等；从实例注册表摘除）。返回 false = 服务端销毁失败 */
    public boolean destroy() {
        if (destroyed) {
            return true;
        }
        destroyed = true;
        VirtualDisplayLib.remove(this);
        if (frame != null) {
            frame.release();
        }
        return channel.destroyVirtualDisplay(displayId);
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    VdFrameClient getFrameClient() {
        return frame;
    }

    private void checkAlive() {
        if (destroyed) {
            throw new IllegalStateException("virtual display destroyed (displayId=" + displayId + ")");
        }
    }
}
