
cmake_minimum_required(VERSION 3.22.0)

project(extension)

# libjpeg-turbo 3.0.4 (no SIMD, static config headers jconfig.h/jconfigint.h)
set(LJPEG_DIR ../3rd/libjpeg)
set(LJPEG_SOURCES
        ${LJPEG_DIR}/jcapimin.c ${LJPEG_DIR}/jcapistd.c ${LJPEG_DIR}/jcarith.c
        ${LJPEG_DIR}/jccoefct.c ${LJPEG_DIR}/jccolor.c ${LJPEG_DIR}/jcdctmgr.c
        ${LJPEG_DIR}/jchuff.c ${LJPEG_DIR}/jcicc.c ${LJPEG_DIR}/jcinit.c
        ${LJPEG_DIR}/jclhuff.c ${LJPEG_DIR}/jcmainct.c ${LJPEG_DIR}/jcmarker.c
        ${LJPEG_DIR}/jcmaster.c ${LJPEG_DIR}/jcomapi.c ${LJPEG_DIR}/jcparam.c
        ${LJPEG_DIR}/jcphuff.c ${LJPEG_DIR}/jcprepct.c ${LJPEG_DIR}/jcsample.c
        ${LJPEG_DIR}/jctrans.c ${LJPEG_DIR}/jdapimin.c ${LJPEG_DIR}/jdapistd.c
        ${LJPEG_DIR}/jdcoefct.c ${LJPEG_DIR}/jdcolor.c ${LJPEG_DIR}/jdlhuff.c
        ${LJPEG_DIR}/jddctmgr.c ${LJPEG_DIR}/jdhuff.c ${LJPEG_DIR}/jdicc.c
        ${LJPEG_DIR}/jdinput.c ${LJPEG_DIR}/jdmainct.c ${LJPEG_DIR}/jdmarker.c
        ${LJPEG_DIR}/jdmaster.c ${LJPEG_DIR}/jdmerge.c ${LJPEG_DIR}/jdphuff.c
        ${LJPEG_DIR}/jdpostct.c ${LJPEG_DIR}/jdsample.c ${LJPEG_DIR}/jdtrans.c
        ${LJPEG_DIR}/jerror.c ${LJPEG_DIR}/jfdctflt.c ${LJPEG_DIR}/jfdctfst.c
        ${LJPEG_DIR}/jfdctint.c ${LJPEG_DIR}/jidctflt.c ${LJPEG_DIR}/jidctfst.c
        ${LJPEG_DIR}/jidctint.c ${LJPEG_DIR}/jidctred.c ${LJPEG_DIR}/jquant1.c
        ${LJPEG_DIR}/jquant2.c ${LJPEG_DIR}/jutils.c ${LJPEG_DIR}/jmemmgr.c
        ${LJPEG_DIR}/jmemnobs.c ${LJPEG_DIR}/jaricom.c ${LJPEG_DIR}/jcarith.c
        ${LJPEG_DIR}/jdarith.c ${LJPEG_DIR}/jdatadst.c ${LJPEG_DIR}/jdatasrc.c
        ${LJPEG_DIR}/jcdiffct.c ${LJPEG_DIR}/jddiffct.c ${LJPEG_DIR}/jclossls.c
        ${LJPEG_DIR}/jdlossls.c ${LJPEG_DIR}/jpeg_nbits.c
)
# 12-bit / 16-bit precision variants (shared sources compiled with BITS_IN_JSAMPLE override)
set(LJPEG16_SOURCES
        ${LJPEG_DIR}/jcapistd.c ${LJPEG_DIR}/jccolor.c ${LJPEG_DIR}/jcdiffct.c
        ${LJPEG_DIR}/jclossls.c ${LJPEG_DIR}/jcmainct.c ${LJPEG_DIR}/jcprepct.c
        ${LJPEG_DIR}/jcsample.c ${LJPEG_DIR}/jdapistd.c ${LJPEG_DIR}/jdcolor.c
        ${LJPEG_DIR}/jddiffct.c ${LJPEG_DIR}/jdlossls.c ${LJPEG_DIR}/jdmainct.c
        ${LJPEG_DIR}/jdpostct.c ${LJPEG_DIR}/jdsample.c ${LJPEG_DIR}/jutils.c
)
set(LJPEG12_SOURCES ${LJPEG16_SOURCES}
        ${LJPEG_DIR}/jccoefct.c ${LJPEG_DIR}/jcdctmgr.c ${LJPEG_DIR}/jdcoefct.c
        ${LJPEG_DIR}/jddctmgr.c ${LJPEG_DIR}/jdmerge.c ${LJPEG_DIR}/jfdctfst.c
        ${LJPEG_DIR}/jfdctint.c ${LJPEG_DIR}/jidctflt.c ${LJPEG_DIR}/jidctfst.c
        ${LJPEG_DIR}/jidctint.c ${LJPEG_DIR}/jidctred.c ${LJPEG_DIR}/jquant1.c
        ${LJPEG_DIR}/jquant2.c
)
add_library(jpeg12_static OBJECT ${LJPEG12_SOURCES})
set_property(TARGET jpeg12_static PROPERTY COMPILE_FLAGS "-DBITS_IN_JSAMPLE=12")
add_library(jpeg16_static OBJECT ${LJPEG16_SOURCES})
set_property(TARGET jpeg16_static PROPERTY COMPILE_FLAGS "-DBITS_IN_JSAMPLE=16")

add_library(jpeg_static STATIC ${LJPEG_SOURCES}
        $<TARGET_OBJECTS:jpeg12_static> $<TARGET_OBJECTS:jpeg16_static>)
target_include_directories(jpeg_static PUBLIC ${LJPEG_DIR})
target_compile_options(jpeg_static PRIVATE -Wno-unused-parameter -Wno-sign-compare)

add_library(extension STATIC
        Display.cpp
        JavaInputManager.cpp
        NativeInputManager.cpp
        InputManager.cpp
        SharedFrame.cpp
        VisionBridge.cpp
        vision_extra.cpp
        image_lib.cpp
        ws_lib.cpp
)

target_include_directories(extension PUBLIC ../3rd/autolua_vision/src
        ../3rd/autolua_vision/lodepng)
target_link_libraries(extension PUBLIC alv_static hv_static jpeg_static)
