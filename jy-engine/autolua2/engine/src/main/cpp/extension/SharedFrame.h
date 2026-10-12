//
// 共享内存 A/B 双缓冲下沉状态（对应 Java com.autolua.engine.display.sharedmem.SharedMemoryManager）。
// Java init() 成功后经 nativeSetSharedMemoryInfo 把 mmap 基址/尺寸下沉到本模块全局指针，
// C++ / Lua 读侧（如 Display 绑定、images.captureScreen）直接访问，实现文档 2.3.4 的
// seqlock 无锁读协议（读前读后比对 getFrameCounter()）。
//
#ifndef AUTOLUA_ENGINE_EXTENSION_SHARED_FRAME_H
#define AUTOLUA_ENGINE_EXTENSION_SHARED_FRAME_H

#include <jni.h>
#include <cstdint>

namespace SharedFrame {

// 写入共享内存信息（addr 为 mmap 基址；全 0 表示释放）
void setSharedMemoryInfo(uint8_t *addrA, uint8_t *addrB, int size, int width, int height);

uint8_t *getAddressA();

uint8_t *getAddressB();

int getSize();

int getWidth();

int getHeight();

// 取实时帧计数（seqlock/ping-pong 写协议共用计数器）。
// 经 JNI 回调 Java SharedMemoryManager.getFrameCounter()（写入方在 Java 侧），
// env 为空（纯 C++ 线程）或回调失败时返回上次缓存值。
int64_t getFrameCounter(JNIEnv *env);

} // namespace SharedFrame

// ---------------------------------------------------------------------------
// JNI 静态注册入口（跟随引擎现有约定：无 RegisterNatives，静态 JNI 命名）
// 由 Java SharedMemoryManager 声明：
//   private static native ByteBuffer mapFdToByteBuffer(int fd, int size);
//   private static native long getDirectBufferAddress(ByteBuffer buffer);
//   private static native void nativeSetSharedMemoryInfo(long addrA, long addrB, int size, int w, int h);
//   public  static native long nativeGetFrameCounter();
// 库名跟随引擎主 so（CMakeLists project("engine") → libengine.so）
// ---------------------------------------------------------------------------

extern "C" {

// mmap fd → direct ByteBuffer（对照 libfast_screenshot 的 mapFdToByteBuffer）
JNIEXPORT jobject JNICALL
Java_com_autolua_engine_display_sharedmem_SharedMemoryManager_mapFdToByteBuffer(
        JNIEnv *env, jclass clazz, jint fd, jint size);

// 取 direct ByteBuffer 裸地址
JNIEXPORT jlong JNICALL
Java_com_autolua_engine_display_sharedmem_SharedMemoryManager_getDirectBufferAddress(
        JNIEnv *env, jclass clazz, jobject buffer);

// 下发共享内存信息（存全局指针）
JNIEXPORT void JNICALL
Java_com_autolua_engine_display_sharedmem_SharedMemoryManager_nativeSetSharedMemoryInfo(
        JNIEnv *env, jclass clazz, jlong addrA, jlong addrB, jint size, jint width, jint height);

// C++ 侧取实时帧计数（JNI 回调 Java）
JNIEXPORT jlong JNICALL
Java_com_autolua_engine_display_sharedmem_SharedMemoryManager_nativeGetFrameCounter(
        JNIEnv *env, jclass clazz);

} // extern "C"

#endif // AUTOLUA_ENGINE_EXTENSION_SHARED_FRAME_H
