package com.autolua.engine.input.inject;

import android.os.ParcelFileDescriptor;

// Shizuku UserService AIDL 接口（与 IInjectChannel 对齐）。
// 两端（app 进程的 ShizukuInjectorChannel 与 shell 进程的 ShizukuUserServiceMain）
// 通过相同描述符跨 binder 通信。

interface IInjectorService {
    /** 探针：注入核心是否就绪 */
    boolean ping();

    boolean down(int x, int y, int pointerId, long downTime);

    boolean move(int x, int y, int pointerId, long downTime);

    boolean up(int pointerId, int x, int y, long downTime);

    /** durationMs <= 0 时使用 UserService 进程内 Humanizer 默认值 */
    boolean tap(int x, int y, int durationMs);

    boolean longTap(int x, int y, int durationMs);

    boolean swipe(int x1, int y1, int x2, int y2, int durationMs);

    boolean pinch(int cx, int cy, int radius, int target, int durationMs, boolean zoomIn);

    boolean gesture(String strokesJson);

    boolean reset();

    boolean config(String key, String value);

    /** Shizuku 解绑服务时回调 */
    void destroy();

    // ------------------------------------------------------------------
    // 虚拟屏第一批（虚拟屏任务书 §4.1/§4.2，2026-09 新增）
    // ------------------------------------------------------------------

    /**
     * 创建虚拟屏（shell uid，服务端内部完成：DisplayControlWrap 建 token +
     * ImageReader(RGBA_8888, maxImages=3) 帧宿主 + display 域事务四件套 +
     * setDisplayLayerStack(0 默认主 stack)）。
     * 返回系统 displayId（优先经 DisplayManagerGlobal.getDisplayIds() 差集解析；-1 失败）。
     */
    int createVirtualDisplay(String name, int width, int height, int dpi);

    /** 销毁虚拟屏并释放帧宿主（DisplayControlWrap 双路径 destroy），displayId 不存在返回 false */
    boolean destroyVirtualDisplay(int displayId);

    /**
     * 按屏拉起 App：shell 身份 exec "am start --display &lt;id&gt; -n pkg/activity"。
     * activity 传空串时服务端自动 resolve 入口（PM getLaunchIntentForPackage，
     * 失败回退 cmd package resolve-activity --brief）。
     * 返回 0 成功 / 非 0 失败码（1 参数或入口解析失败，2 执行 am 失败，3 am 报告错误）。
     */
    int startAppOnDisplay(String packageName, String activity, int displayId);

    // ------------------------------------------------------------------
    // 虚拟屏第二批（任务书 §4.3 定向注入 / §4.4 按屏帧泵，2026-09 新增）
    // AIDL 追加方法属接口面变更，已同步 bump ShizukuInjectorChannel.USER_SERVICE_VERSION
    // （现值 3）触发旧 UserService 进程失配重绑。
    // 注入错误码（tapOnDisplay/longTapOnDisplay/swipeOnDisplay 共用，0 成功）：
    //   1 参数非法   2 displayId 未建屏（不注入，禁止静默点错屏）
    //   3 setDisplayId 不可用（已降级主屏注入，调用方应提示分屏能力缺失）
    //   4 注入被系统拒绝（InputManager.injectInputEvent 返回 false）
    // ------------------------------------------------------------------

    /**
     * attach 按屏帧泵：服务端为该屏创建/复用 A/B 两块 MemoryFile（单块 size =
     * w*h*4+9，9 字节自描述头布局与 SharedMemoryManager 一致），fd 经 binder dup
     * 跨进程返回。返回 [fdA, fdB]（ParcelFileDescriptor，跨 binder 传输时自动 dup 至
     * 客户端进程）；dims = [width, height]。
     * displayId 未建屏返回 null。
     * 注：任务书 §4.4 原定 out FileDescriptor[]，本工具链 AIDL 后端对其生成
     * writeRawFileDescriptorArray（本 compileSdk Parcel 无此 API）无法编译，改用
     * ParcelFileDescriptor[] 返回值形态（fd 传输语义一致，与 IScreenShareService 同款）。
     */
    ParcelFileDescriptor[] attachFrameSink(int displayId, out int[] dims);

    /** 跨进程 seqlock 帧计数器轮询（写侧 AtomicLong 在服务进程；displayId 未建屏返回 -1） */
    long getFrameCounter(int displayId);

    /** displayId 定向单击（MotionEvent.obtain 组装 + 反射 setDisplayId + 既有注入底座） */
    int tapOnDisplay(int displayId, int x, int y, int durationMs);

    /** displayId 定向长按（durationMs<=0 用服务端缺省 1000ms） */
    int longTapOnDisplay(int displayId, int x, int y, int durationMs);

    /** displayId 定向滑动（durationMs<=0 用服务端缺省 300ms） */
    int swipeOnDisplay(int displayId, int x1, int y1, int x2, int y2, int durationMs);
}
