package com.autolua.engine.accessibility;

import android.os.Bundle;

// obtainXml 整树 dump 跨进程 AIDL（无障碍服务进程为服务端，对照集成文档 4.3.3）。
// Bundle 为 framework 类型，AIDL 直接可用（无需 parcelable 声明）。

interface IAuxDumpService {
    // dump 整树 XML，经 ParcelFileDescriptor 管道流式返回；includeNotImportant 控制节点模式
    Bundle dumpXml(boolean includeNotImportant, int targetW, int targetH);

    // 节点模式切换（持久）
    oneway void setNodeMode(boolean includeNotImportant);

    boolean isReady();
}
