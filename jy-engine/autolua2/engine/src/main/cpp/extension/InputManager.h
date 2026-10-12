//
// Created by lizhi on 2024/7/3.
//

#ifndef AUTOLUA2_INPUTMANAGER_H
#define AUTOLUA2_INPUTMANAGER_H

#include "JavaInputManager.h"
#include "NativeInputManager.h"
#include "Input.h"
#include"NormalRandomNumber.h"
#include <lua.hpp>
#include <map>

namespace autolua {

    class InputManager; // 前置声明（Pointer.manager 引用）

    // 多指触控便捷层的持续按压指针（原定义于 InputManager.cpp，上移供头文件 map 使用）
    struct Pointer:public Input::PointerState{
        InputManager* manager = nullptr;
    };

    class InputManager {
    public:
        explicit InputManager(Display* display, jobject obj,bool isRoot =false);
        ~InputManager();
        void injectToLua(struct lua_State*L);
        int syncPointer(Input::PointerState* pointerState);
        int releasePointer(int id);
    private:
        JavaInputManager javaInputManager_;
        NativeInputManager nativeInputManager_;

        NormalRandomNumber majorDown_;
        NormalRandomNumber minorDown_;
        NormalRandomNumber majorMove_;
        NormalRandomNumber minorMove_;
        NormalRandomNumber majorUp_;
        NormalRandomNumber minorUp_;
        NormalRandomNumber pressKey_;
        NormalRandomNumber tapTime_;

        std::map<int,int> keyDownMap_;
        // 多指触控便捷层：lua fingerId -> 持续按住的 Pointer（touchDown/touchMove/touchUp）
        std::map<int,Pointer> touchDownMap_;
        bool isRoot_ = false;
        bool isUseHardware_ = true;
        int keyDown(int key);
        int keyUp(int key);

        bool shouldUseHardware() const{
            return isRoot_ && isUseHardware_;
        }

        static int createPointer(lua_State*L);

        static int keyDown(lua_State*L);
        static int keyUp(lua_State*L);
        static int keyPress(lua_State*L);
        static int releaseAllDown(lua_State*L);

        static int swipe(lua_State*L);
        static int tap(lua_State*L);
        static int useHardwareInput(lua_State*L);

        // 懒人/派大星风格便捷触控 API
        static int touchDown(lua_State*L);
        static int touchMove(lua_State*L);
        static int touchUp(lua_State*L);
        static int longTap(lua_State*L);

    };

} // autolua

#endif //AUTOLUA2_INPUTMANAGER_H
