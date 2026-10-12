//
// Created by 截图管线（方案一 P0）：共享内存 A/B 双缓冲下沉状态 + JNI 注册。
// 协议见集成文档 2.3.4；Java 侧声明见 SharedMemoryManager。
//
#include "SharedFrame.h"

#include <android/log.h>
#include <cerrno>
#include <sys/mman.h>
#include <unistd.h>

#define LOG_TAG "SharedFrame"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

    uint8_t *g_addrA = nullptr;
    uint8_t *g_addrB = nullptr;
    int g_size = 0;
    int g_width = 0;
    int g_height = 0;
    // 上次成功从 Java 同步的帧计数（env 不可用时的回退值）
    int64_t g_lastCounter = 0;

    // Java SharedMemoryManager.getFrameCounter() 的缓存（首次调用时解析）
    jclass g_counterClass = nullptr;
    jmethodID g_counterMethod = nullptr;

} // namespace

namespace SharedFrame {

    void setSharedMemoryInfo(uint8_t *addrA, uint8_t *addrB, int size, int width, int height) {
        g_addrA = addrA;
        g_addrB = addrB;
        g_size = size;
        g_width = width;
        g_height = height;
    }

    uint8_t *getAddressA() {
        return g_addrA;
    }

    uint8_t *getAddressB() {
        return g_addrB;
    }

    int getSize() {
        return g_size;
    }

    int getWidth() {
        return g_width;
    }

    int getHeight() {
        return g_height;
    }

    int64_t getFrameCounter(JNIEnv *env) {
        if (env == nullptr) {
            return g_lastCounter;
        }
        if (g_counterClass == nullptr) {
            jclass local = env->FindClass("com/autolua/engine/display/sharedmem/SharedMemoryManager");
            if (local == nullptr) {
                env->ExceptionClear();
                return g_lastCounter;
            }
            g_counterClass = (jclass) env->NewGlobalRef(local);
            env->DeleteLocalRef(local);
            g_counterMethod = env->GetStaticMethodID(g_counterClass, "getFrameCounter", "()J");
            if (g_counterMethod == nullptr) {
                env->ExceptionClear();
                return g_lastCounter;
            }
        }
        // 写侧计数器在 Java（AtomicLong），经 JNI 回调取实时值
        jlong counter = env->CallStaticLongMethod(g_counterClass, g_counterMethod);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return g_lastCounter;
        }
        g_lastCounter = (int64_t) counter;
        return g_lastCounter;
    }

} // namespace SharedFrame

extern "C" {

JNIEXPORT jobject JNICALL
Java_com_autolua_engine_display_sharedmem_SharedMemoryManager_mapFdToByteBuffer(
        JNIEnv *env, jclass clazz, jint fd, jint size) {
    if (fd <= 0 || size <= 0) {
        ALOGW("mapFdToByteBuffer invalid fd=%d size=%d", fd, size);
        return nullptr;
    }
    // ashmem fd → MAP_SHARED 直接映射（写读两侧同址）
    void *addr = mmap(nullptr, (size_t) size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (addr == MAP_FAILED) {
        ALOGE("mmap fd=%d size=%d failed errno=%d", fd, size, errno);
        return nullptr;
    }
    return env->NewDirectByteBuffer(addr, (jlong) size);
}

JNIEXPORT jlong JNICALL
Java_com_autolua_engine_display_sharedmem_SharedMemoryManager_getDirectBufferAddress(
        JNIEnv *env, jclass clazz, jobject buffer) {
    if (buffer == nullptr) {
        return 0;
    }
    void *addr = env->GetDirectBufferAddress(buffer);
    return (jlong) (intptr_t) addr;
}

JNIEXPORT void JNICALL
Java_com_autolua_engine_display_sharedmem_SharedMemoryManager_nativeSetSharedMemoryInfo(
        JNIEnv *env, jclass clazz, jlong addrA, jlong addrB, jint size, jint width, jint height) {
    SharedFrame::setSharedMemoryInfo((uint8_t *) (intptr_t) addrA,
                                     (uint8_t *) (intptr_t) addrB,
                                     (int) size, (int) width, (int) height);
    ALOGW("shared memory info set: addrA=%p addrB=%p size=%d %dx%d",
          (void *) (intptr_t) addrA, (void *) (intptr_t) addrB, (int) size, (int) width, (int) height);
}

JNIEXPORT jlong JNICALL
Java_com_autolua_engine_display_sharedmem_SharedMemoryManager_nativeGetFrameCounter(
        JNIEnv *env, jclass clazz) {
    return (jlong) SharedFrame::getFrameCounter(env);
}

} // extern "C"
