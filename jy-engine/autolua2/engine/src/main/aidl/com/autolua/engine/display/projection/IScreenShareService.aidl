package com.autolua.engine.display.projection;

import android.os.Bundle;

// 截图共享内存跨进程 AIDL（CaptureScreenService 所在进程为服务端，对照集成文档 2.3.4/4.3）。
// Bundle 为 framework 类型，AIDL 直接可用（无需 parcelable 声明）。

interface IScreenShareService {
    // 返回共享内存两个 fd（dup 过的 ParcelFileDescriptor）+ 尺寸与模式：
    // Bundle{fdA:PFD, fdB:PFD, width:int, height:int, mode:String("app"/"root"), ready:boolean}
    Bundle getSharedFds();

    // 请求一帧（App 模式手动节拍时用）
    oneway void requestFrame();

    boolean isReady();
}
