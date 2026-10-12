//
// Created by lizhi on 2022/4/22.
//

#include "Display.h"

#include <lua.hpp>
#include <memory.h>
#include <mutex>
#include <csetjmp>
#include "util.h"
#include "lua_vision.h"
#include "vision_extra.h"
#include "vision_util.h"
#include <lodepng.h>
#include <unistd.h>
#include "lua_context.h"
#include "mlua.h"
#include "my_log.h"
#define DISPLAY_CLASS_NAME "com/autolua/engine/extension/display/Display"
#define BYTEBUFFER_CLASS_NAME "java/nio/ByteBuffer"
#define CLASS_ARG(className) "L" className ";"
#define ARGS(args) "(" args ")"

static jmp_buf panic_jump;


Display::Display(jobject obj)
    : Bitmap(), keepDisplay_(false){
    JNIEnv*env = GetJNIEnv();
    // 必须强引用：weak ref 指向的 Java DisplayImplement 可被 GC 回收，
    // 之后 CallIntMethod(obj_) 直接 SIGSEGV（Lua pcall 拦不住 native crash，整个进程死）
    obj_ = env->NewGlobalRef(obj);

#define SET_JAVA_METHOD(methodName,classID,result,...) methodName##MethodID = env->GetMethodID(classID,#methodName,ARGS(__VA_ARGS__)result)
    jobject local = env->FindClass(DISPLAY_CLASS_NAME);
    displayClassID_ = (jclass)env->NewWeakGlobalRef(local);
    env->DeleteLocalRef(local);
    SET_JAVA_METHOD(getRotation, displayClassID_, "I");
    SET_JAVA_METHOD(initialize, displayClassID_, "Z", "II");
    SET_JAVA_METHOD(getDisplayBuffer, displayClassID_, CLASS_ARG(BYTEBUFFER_CLASS_NAME));
    SET_JAVA_METHOD(getHeight, displayClassID_, "I");
    SET_JAVA_METHOD(getWidth, displayClassID_, "I");
    SET_JAVA_METHOD(getRowStride, displayClassID_, "I");
    SET_JAVA_METHOD(getPixelStride, displayClassID_, "I");
    SET_JAVA_METHOD(isChangeDirection, displayClassID_, "Z");
    SET_JAVA_METHOD(update, displayClassID_, "V");
    SET_JAVA_METHOD(hasRealFrame, displayClassID_, "Z");
    SET_JAVA_METHOD(isRgbaOrder, displayClassID_, "Z");
#undef SET_JAVA_METHOD


    jmethodID method = env->GetMethodID(displayClassID_,"getBaseWidth", "()I");
    baseWidth_ = env->CallIntMethod(obj_,method);
    method = env->GetMethodID(displayClassID_,"getBaseHeight","()I");
    baseHeight_ = env->CallIntMethod(obj_,method);
    method = env->GetMethodID(displayClassID_,"getBaseDensity","()I");
    baseDensity_ = env->CallIntMethod(obj_,method);
    method = env->GetMethodID(displayClassID_,"getBaseDirection","()I");
    baseDirection_ = env->CallIntMethod(obj_,method);
    update();
}

bool Display::isChangeDirection() {
    JNIEnv*env = GetJNIEnv();
    return env->CallBooleanMethod(obj_,isChangeDirectionMethodID);
}

bool Display::hasRealFrame() {
    JNIEnv*env = GetJNIEnv();
    return env->CallBooleanMethod(obj_,hasRealFrameMethodID);
}

bool Display::isRgbaOrder() {
    JNIEnv*env = GetJNIEnv();
    return env->CallBooleanMethod(obj_,isRgbaOrderMethodID);
}

bool Display::localReset(int w, int h) {
    JNIEnv*env = GetJNIEnv();
    int result = env->CallBooleanMethod(obj_, initializeMethodID, w, h);
    update();
    return  result;
}




int Display::screenshotToPng(int x, int y, int x1, int y1, std::vector<unsigned char> &out) {
    std::shared_lock<std::shared_mutex> lock(mutex_);
    int rowSize = (x1-x)* pixelStride_;
    int size = rowSize*(y1-y);
    std::vector<unsigned char> data(size);
    unsigned char* index = data.data();
    unsigned char* ptr = origin_+x*pixelStride_+y*rowShift_;
    for (int i = y; i < y1; ++i) {
        memcpy(index,ptr,rowSize);
        index += rowSize;
        ptr += rowShift_;
    }
    // 屏幕帧字节序逐实例适配（两条口径均待真机实测复核，见 docs/plans/虚拟屏功能实施任务书.md §2.3）：
    // - isRgbaOrder()==false：root 镜像 ImageReader 历史口径 BGRA/BGR，PNG 需 RGBA/RGB
    //   → 逐像素交换 R/B（现状路径，root 行为位级不变）；
    // - isRgbaOrder()==true ：MediaProjection 共享内存管线 RGBA（R 在 [0]，见 VisionBridge.cpp:12-16）
    //   → 帧已是 PNG 所需 RGBA/RGB 序，跳过交换。
    if (!isRgbaOrder()) {
        for (int i = 0; i + 2 < size; i += pixelStride_) {
            std::swap(data[i], data[i + 2]);
        }
    }
    lodepng::State state;
    state.info_raw.colortype  = pixelStride_ == 4? LodePNGColorType::LCT_RGBA:LodePNGColorType::LCT_RGB;
    auto r = lodepng::encode(out,data,x1-x,y1-y,state);
    if(r)
        return 2;
    return 0;
}


int Display::screenshot(int x,int y,int x1,int y1,SCREEN_SHOT_FORMAT format,std::vector<unsigned  char> &out) {
    if(isChangeDirection())
        localReset(0,0);
    else
        update();
    return _screenshot(x,y,x1,y1,format,out);
}


Display::~Display() {
    JNIEnv*env = GetJNIEnv();
    env->DeleteWeakGlobalRef(displayClassID_);
    env->DeleteGlobalRef(obj_);
}


void Display::update() {
    std::unique_lock<std::shared_mutex> lock(mutex_);
#define LocalCallIntMethod(env,method) env->CallIntMethod(obj_,method)
    JNIEnv*env = GetJNIEnv();
    env->CallVoidMethod(obj_,updateMethodID);
    width_ = LocalCallIntMethod(env, getWidthMethodID);
    height_ = LocalCallIntMethod(env, getHeightMethodID);
    pixelStride_ = LocalCallIntMethod(env, getPixelStrideMethodID);
    rowShift_ = LocalCallIntMethod(env, getRowStrideMethodID);
    jobject buffer = env->CallObjectMethod(obj_,getDisplayBufferMethodID);
    origin_ = (unsigned char*)env->GetDirectBufferAddress(buffer);
    env->DeleteLocalRef(buffer);
}

int Display::getBaseSize(lua_State*L)
{
    auto * display = (Display*) lua_touserdata(L, lua_upvalueindex(1));
    lua_pushinteger(L,display->baseWidth_);
    lua_pushinteger(L,display->baseHeight_);
    return 2;
}


int Display::getBaseDensity(lua_State *L) {
    auto * display = (Display*) lua_touserdata(L, lua_upvalueindex(1));
    lua_pushinteger(L,display->baseDensity_);
    return 1;
}

int Display::getBaseDirection(lua_State *L) {
    auto * display = (Display*) lua_touserdata(L, lua_upvalueindex(1));
    lua_pushinteger(L,display->baseDirection_);
    return 1;
}

int Display::getRotation(lua_State *L) {
    auto * display = (Display*) lua_touserdata(L, lua_upvalueindex(1));
    auto r = display->getRotation();
    lua_pushinteger(L,r);
    return 1;
}

int Display::isChangeDirection(lua_State *L) {
    auto * display = (Display*) lua_touserdata(L, lua_upvalueindex(1));
    lua_pushboolean(L, display->isChangeDirection());
    return 1;
}

int Display::reset(lua_State*L)
{
    auto * display = (Display*) lua_touserdata(L, lua_upvalueindex(1));
    jint width = luaL_checkinteger(L,1);
    jint height = luaL_checkinteger(L,2);
    bool result= display->localReset(width,height);
    lua_pushboolean(L,result);
    return 1;
};

int Display::update(lua_State *L) {
    auto * display = (Display*) lua_touserdata(L, lua_upvalueindex(1));
    display->update();
    return 0;
}

int Display::keepDisplay(lua_State *L) {
    auto * display = (Display*) lua_touserdata(L, lua_upvalueindex(1));
    if(lua_isboolean(L,1)){
        display->keepDisplay_ = lua_toboolean(L,1);
        return 0;
    }
    lua_pushboolean(L,display->keepDisplay_);
    return 1;
}

int Display::updateAndKeepDisplay(lua_State *L) {
    auto * display = (Display*) lua_touserdata(L, lua_upvalueindex(1));
    display->keepDisplay_= true;
    display->update();
    return 0;
}

int Display::save(lua_State*L){
    auto * display = (Display*) lua_touserdata(L, lua_upvalueindex(1));
    int x = luaL_checkinteger(L,1);
    int y = luaL_checkinteger(L,2);
    int x1 = luaL_checkinteger(L,3);
    int y1 = luaL_checkinteger(L,4);
    const char* path = luaL_checkstring(L,5);
    Display::SCREEN_SHOT_FORMAT format = Display::kPng;
    if(lua_isstring(L,6)){
        const char* formatStr = lua_tostring(L,6);
        if(strcmp(formatStr,"png") == 0)
            format = Display::kPng;
        else if(strcmp(formatStr,"jpeg") == 0)
            format = Display::kJpeg;
        else
            luaL_error(L,"unknown format %s",formatStr);
    }
    // §3.2 jpeg 假成功修复：_screenshot 仅实现 kPng，此前 kJpeg 走到这会写 0 字节文件返回 true。
    // 显式返回 false + 错误信息（jpeg 编码长期项按需求另行立项，见任务书 §3.2）。
    if(format == Display::kJpeg){
        lua_pushboolean(L,false);
        lua_pushstring(L,"saveScreen: jpeg not supported (png only)");
        return 2;
    }
    // §3.3 与 transitMethod 行为对齐：旋转自动恢复 + 刷帧 + 无帧守卫（此前 save 完全不刷帧，行为不一致）
    if(display->isChangeDirection())
        display->localReset(0,0);
    else
        display->update();
    if (!display->hasRealFrame())
        luaL_error(L, "screen frame unavailable (screen off or capture stopped?)");
    std::vector<unsigned char> data;
    int result = display->_screenshot(x,y,x1,y1,format,data);
    if(result != 0){
        lua_pushboolean(L,false);
        return 1;
    }
    FILE* file = fopen(path,"wb");
    if(file == nullptr){
        lua_pushboolean(L,false);
        return 1;
    }
    fwrite(data.data(),1,data.size(),file);
    fclose(file);
    lua_pushboolean(L,true);
    return 1;
}


struct Text{
    ~Text(){
        LOGE("delete text %p",this);
    }
};

// 区域字节拷贝（行间按 rowShift_，紧凑输出）
static void copyDisplayRegion(Display *display, int x, int y, int x1, int y1,
                              std::vector<unsigned char> &out) {
    int rowSize = (x1 - x + 1) * display->pixelStride_;
    out.resize((size_t) rowSize * (y1 - y + 1));
    unsigned char *dst = out.data();
    for (int row = y; row <= y1; row++) {
        memcpy(dst, display->origin_ + row * display->rowShift_ + x * display->pixelStride_,
               (size_t) rowSize);
        dst += rowSize;
    }
}

// isDisplayDead(x1, y1, x2, y2[, delayMs]) -> bool
// 强制刷新两帧（间隔 delayMs，默认 1000），区域内像素完全一致则判定死屏。
// 经 transitMethod 包装注册（包装层首刷无害，函数内再强制两刷采样）。
int Display::isDisplayDead(lua_State *L) {
    auto *display = (Display *) lua_touserdata(L, lua_upvalueindex(1));
    int x = (int) luaL_checkinteger(L, 1);
    int y = (int) luaL_checkinteger(L, 2);
    int x1 = (int) luaL_checkinteger(L, 3);
    int y1 = (int) luaL_checkinteger(L, 4);
    if (x1 < x || y1 < y || !autolua::isInBitmapScope(display, x, y, x1, y1))
        luaL_error(L, "The coordinates are off screen");
    int delayMs = (int) luaL_optinteger(L, 5, 1000);
    if (delayMs < 0) delayMs = 0;

    // 旋转自动恢复 + 无帧守卫（§3.3）：本函数不能套 transitMethod ——
    // transitMethod 持 shared_lock 后内部 update() 要 unique_lock，同线程锁升级死锁；
    // 故保持主动刷帧结构，仅在开头补守卫逻辑（刷帧发生在 unique_lock 之外，无死锁风险）。
    if (display->isChangeDirection())
        display->localReset(0, 0);
    display->update();
    if (!display->hasRealFrame())
        luaL_error(L, "screen frame unavailable (screen off or capture stopped?)");

    std::vector<unsigned char> before, after;
    {
        display->update();
        std::shared_lock<std::shared_mutex> lock(display->mutex_);
        copyDisplayRegion(display, x, y, x1, y1, before);
    }
    usleep(delayMs * 1000);
    {
        display->update();
        std::shared_lock<std::shared_mutex> lock(display->mutex_);
        copyDisplayRegion(display, x, y, x1, y1, after);
    }
    lua_pushboolean(L, before == after);
    return 1;
}

int Display::transitMethod(lua_State *L) {
    auto *display = (Display*) lua_touserdata(L, lua_upvalueindex(1));
    if(display->isChangeDirection()){
        // §3.3 旋转自动跟随：重建虚拟屏（Java initialize+update），不再向脚本抛 "display rotate"；
        // isChangeDirection/resetScreen 公开 API 保留（02 已承诺兼容）
        display->localReset(0,0);
    } else if (!display->isKeepDisplay()) {
        display->update();
    }
    // §3.1 无帧守卫：熄屏/采集停止时快速失败，抛 pcall 可捕获错误（守卫在 shared_lock 之前，无锁升级问题）
    if (!display->hasRealFrame())
        luaL_error(L, "screen frame unavailable (screen off or capture stopped?)");
    std::shared_lock<std::shared_mutex> lock(display->mutex_);
    auto method = (lua_CFunction) lua_touserdata(L,lua_upvalueindex(2));
    auto r= method(L);
    return r;
}

struct WrapCompareColorMethodContext{
    int tableIndex;
    lua_State *L;
    Display *display;
};

// 【§3.2/任务书 D：compareColor 系目标色字节序逐实例适配 —— 追踪结论登记，待真机实测复核】
// 目标色 0xRRGGBB 的字节分解点不在 lua_vision.cc / vision_extra.cpp（二者均只构造
// Color{uint32}，无显式拆字节），而在 3rd/autolua_vision/src/vision_color.h 的
// computeColorShiftSum(color, Color*)：直接把 uint32 按小端内存解释为字节序
// [B,G,R]（byte0=LSB=BB）。
// 该分解结果与 VisionBridge.cpp:12-16 已验证的适配做法完全一致——VisionBridge 对
// RGBA 帧就是把目标色按 rgbToBgrBytes 拆成 [B,G,R] 传入。配合 vision_util.h
// UNORDERED_PIXEL 交叉映射 |目标[0]-帧[2]|+|目标[1]-帧[1]|+|目标[2]-帧[0]|：
//   - 帧为 RGBA（R 在 [0]，isRgbaOrder()==true，共享内存管线）：
//     逐通道 |B-B|+|G-G|+|R-R| 精确对齐，与 VisionBridge 等效 —— 即"目标色字节序
//     对调（RGB→[B,G,R]）"的适配效果已由小端分解天然达成，此处无需再做对调
//     （再对调会退回 [R,G,B]，仅匹配 BGR 帧，反而破坏 RGBA 匹配）；
//   - 帧为 BGRA（isRgbaOrder()==false，root 历史口径）：保持现状位级不变。
// 故 compareColor 包装链（getColor/isColor/whichColor/findColor/isFeature/findFeature/...）
// 不做功能性改动，仅登记 per-instance 判断依据；两条口径待真机实测复核后再定是否单点矫正。
void Display::compareColorWrapper(const char* name, lua_CFunction method, void *data){
    auto context = (WrapCompareColorMethodContext*) data;
    lua_pushlightuserdata(context->L, context->display);
    lua_pushlightuserdata(context->L, (void*)method);
    lua_pushcclosure(context->L, transitMethod, 2);
    lua_setfield(context->L, context->tableIndex, name);
}



int Display::_screenshot(int x, int y, int x1, int y1, Display::SCREEN_SHOT_FORMAT format,
                         std::vector<unsigned char> &out) {
    if(x == -1 && y == -1)
    {
        x = y = 0;
        x1 = width_;
        y1 = height_;
    }
    if (format == kPng)
        return screenshotToPng(x,y,x1,y1,out);
    return 0;
}

int Display::getRotation() {
    JNIEnv*env = GetJNIEnv();
    jint r = env->CallIntMethod(obj_,getRotationMethodID);
    return r;
}

void Display::injectToLua(lua_State *L) {
    luaL_Reg  method[] = {
            {"getBaseSize",getBaseSize},
            {"getBaseDensity",getBaseDensity},
            {"getBaseDirection",getBaseDirection},
            {"getRotation",getRotation},
            {"isChangeDirection",isChangeDirection},
            {"resetScreen",reset},
            {"updateScreen",update},
            {"keepScreen",keepDisplay},
            {"updateAndKeepScreen",updateAndKeepDisplay},
            {"saveScreen",save},
            {nullptr,nullptr}
    };
    lua_geti(L,LUA_REGISTRYINDEX,LUA_RIDX_GLOBALS);
    lua_pushlightuserdata(L,this);
    luaL_setfuncs(L,method,1);
    // 图色增强（vision_extra）：走 transitMethod 同款包装（自动刷帧 + shared_lock）
    luaL_Reg extra[] = {
            {"findColorAllPoint", autolua::visionFindColorAllPoint},
            {"findFeatureAll",    autolua::visionFindFeatureAll},
            {"findImageAll",      autolua::visionFindImageAll},
            {"getScreenPixel",    autolua::visionGetScreenPixel},
            {"getScreenFrame",    autolua::visionGetScreenFrame},
            {nullptr,nullptr}
    };
    for (auto &m : extra) {
        if (m.name == nullptr) break;
        lua_pushlightuserdata(L, this);
        lua_pushlightuserdata(L, (void *) m.func);
        lua_pushcclosure(L, transitMethod, 2);
        lua_setfield(L, -2, m.name);
    }
    // isDisplayDead：自带两次主动刷帧，不能套 transitMethod ——
    // transitMethod 持 shared_lock 后内部 update() 要 unique_lock，同线程锁升级死锁
    lua_pushlightuserdata(L, this);
    lua_pushcclosure(L, (lua_CFunction) &Display::isDisplayDead, 1);
    lua_setfield(L, -2, "isDisplayDead");
    WrapCompareColorMethodContext s{};
    s.L = L;
    s.tableIndex = lua_absindex(L,-1);
    s.display = this;
    eachCompareColorMethodByUpData(compareColorWrapper, &s);
    lua_pop(L,1);
}


