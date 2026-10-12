# ncnn-ocr 插件 native 库构建（独立于主工程，NDK 单独编译）
#
# 前置：ncnn Android 预编译包（CPU 版）解压到本插件 third_party/ncnn-android，
# 目录布局（官方 release zip 原样，头文件在每个 ABI 目录内）：
#   ncnn-android/
#   ├── arm64-v8a/include/ncnn/*.h
#   ├── arm64-v8a/lib/libncnn.a
#   └── armeabi-v7a/{include/ncnn/*.h, lib/libncnn.a}
# 下载地址见 MODEL_CONVERT.md 第 0 步。
#
# 由 build.ps1 调用，手动构建示例：
#   cmake -G Ninja -B build/jni/arm64-v8a \
#     -DCMAKE_TOOLCHAIN_FILE=$env:NDK\build\cmake\android.toolchain.cmake \
#     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-22 \
#     -DANDROID_STL=c++_static -DNCNN_DIR=third_party/ncnn-android
#   cmake --build build/jni/arm64-v8a
cmake_minimum_required(VERSION 3.18)
project(ncnnocr CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(NCNN_DIR "" CACHE PATH "ncnn android 预编译包根目录")

add_library(ncnnocr SHARED ncnn_ocr.cpp)

target_include_directories(ncnnocr PRIVATE
        ${NCNN_DIR}/${ANDROID_ABI}/include
        ${NCNN_DIR}/${ANDROID_ABI}/include/ncnn)

add_library(ncnn STATIC IMPORTED)
set_target_properties(ncnn PROPERTIES IMPORTED_LOCATION
        ${NCNN_DIR}/${ANDROID_ABI}/lib/libncnn.a)

find_library(log-lib log)
find_library(z-lib z)
find_library(android-lib android)

target_link_libraries(ncnnocr
        ncnn
        ${log-lib}
        ${z-lib}
        ${android-lib})

# ncnn 依赖 OpenMP；NDK r21+ 用 -static-openmp 静态链入，避免插件 so 带动态 libomp 依赖。
# 注意链接侧也必须带 -fopenmp，clang 驱动才会把 libomp 注入链接。
target_compile_options(ncnnocr PRIVATE -fopenmp)
target_link_options(ncnnocr PRIVATE -fopenmp -static-openmp)
