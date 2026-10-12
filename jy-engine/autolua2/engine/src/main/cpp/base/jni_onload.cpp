//
// libengine_base 自带 JNI_OnLoad：System.loadLibrary("engine_base") 时即初始化 G_JavaVM。
//
// 背景：InitJavaVM 原本只在 engine.cpp（libengine）的 JNI_OnLoad 调用；IDE 沙箱等
// 仅加载 base 的场景不会执行它。lua_context.cpp 改为跨线程安全取 env（GetJNIEnv）
// 后，首个 LuaContext 注入服务（setGlobal）时 G_JavaVM 仍为 NULL → SIGSEGV。
// base 必须自初始化：JNI_OnLoad 保证先于本库任何 JNI 函数执行。
//

#include <jni.h>

#include "util.h"

extern "C" jint JNI_OnLoad(JavaVM *vm, void *) {
    InitJavaVM(vm);
    return JNI_VERSION_1_6;
}
