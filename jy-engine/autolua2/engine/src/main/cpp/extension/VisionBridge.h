//
// 三期：IDE 图色远程命令 C++ 加速通道（com.autolua.engine.ide.VisionJni 的静态 JNI 实现）。
// 命名跟随 SharedFrame.cpp：无 RegisterNatives，直接 Java_com_autolua_engine_... 导出，
// 由 VisionJni 的静态块 System.loadLibrary("engine") 加载。
//
#ifndef ENGINE_EXTENSION_VISION_BRIDGE_H
#define ENGINE_EXTENSION_VISION_BRIDGE_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

// 各函数签名与返回值约定见 VisionBridge.cpp 内实现注释：
//   findImage / findMultiColor → int[]{found, x, y, similarity千分比}
//   cmpColor                  → int[]{match:0/1}
//   返回 null 表示参数非法（Java 侧捕获后回退纯 Java 实现）

JNIEXPORT jintArray JNICALL
Java_com_autolua_engine_ide_VisionJni_nativeFindImage(
        JNIEnv *env, jclass clazz,
        jbyteArray frame, jint frameW, jint frameH,
        jbyteArray templateRgba, jint templateW, jint templateH,
        jdouble threshold,
        jint rectX, jint rectY, jint rectW, jint rectH);

JNIEXPORT jintArray JNICALL
Java_com_autolua_engine_ide_VisionJni_nativeCmpColor(
        JNIEnv *env, jclass clazz,
        jbyteArray frame, jint frameW, jint frameH,
        jintArray points, jintArray colors, jdouble similarity);

JNIEXPORT jintArray JNICALL
Java_com_autolua_engine_ide_VisionJni_nativeFindMultiColor(
        JNIEnv *env, jclass clazz,
        jbyteArray frame, jint frameW, jint frameH,
        jintArray offsets, jintArray colors, jdouble similarity,
        jint rectX, jint rectY, jint rectW, jint rectH);

#ifdef __cplusplus
}
#endif

#endif // ENGINE_EXTENSION_VISION_BRIDGE_H
