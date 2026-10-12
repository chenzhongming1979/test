package com.autolua.engine.extension.event

import android.os.Process
import android.util.Log
import com.autolua.engine.base.LuaContext
import com.autolua.engine.base.LuaEnvContributor
import com.autolua.engine.base.LuaObjectAdapter

/**
 * 系统事件读取 Lua 桥（与 compat 事件总线配对）：
 *
 * - `__event_read()` → type, pkg, className, text[, keyCode, action]（无新事件返回 nil）
 *   读 EventBridge JSON-lines 队列中「自上次已读 seq 之后」的新事件（游标为本实例内存态，
 *   每次调用返回一条，内部缓冲排空后再触发一次 poll）；type="key" 的按键事件扩展返回
 *   第 5/6 个值 keyCode/action（compat 层取前 4 个值不受影响）。
 *   每次 poll 顺手更新心跳文件（60ms 一次），供无障碍服务判定「脚本正在消费事件」
 *   ——音量键智能拦截（onKeyEvent）的跨进程依据
 * - `__event_enable(on)` → bool
 *   转发开关：root 经 am broadcast 置宿主无障碍服务静态标志（服务端开启时会重置队列）；
 *   沙箱与宿主同进程，直接设静态字段并重置队列
 *
 * 多播注册/分发（event.on/onToast/onNotification/...）在 compat Lua 层实现。
 */
open class EventLib : LuaEnvContributor {

    /** 已 poll 未消费的事件缓冲（本实例 = 本 Lua 上下文私有，各上下文独立游标） */
    private val pending = ArrayDeque<EventBridge.Event>()
    /** 队列读端游标（维护已读 seq；自动处理截断重置/seq 回退） */
    private val reader = EventBridge.Reader()

    private fun exec(vararg cmd: String): Pair<Int, String> = try {
        val p = Runtime.getRuntime().exec(cmd)
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        val rc = p.waitFor()
        rc to (out + err)
    } catch (t: Throwable) {
        -1 to t.toString()
    }

    private fun isRoot(): Boolean = try {
        Process.myUid() == 0
    } catch (t: Throwable) {
        false
    }

    override fun contribute(ctx: LuaContext) {
        // __event_read()：读队列新事件（每次一条）+ 更新读端心跳
        ctx.push(adapter { L ->
            // 心跳先行：告知无障碍服务「脚本仍在消费事件」（音量键拦截判定依据）
            EventBridge.writeHeartbeat()
            var ev = pending.removeFirstOrNull()
            if (ev == null) {
                pending.addAll(reader.poll())
                ev = pending.removeFirstOrNull()
            }
            if (ev == null) {
                L.pushNil()
                1
            } else if (ev.key >= 0) {
                // 按键事件：扩展返回 keyCode/action（第 5/6 个返回值）
                L.push(ev.type); L.push(ev.pkg); L.push(ev.cls); L.push(ev.text)
                L.push(ev.key.toLong()); L.push(ev.action.toLong())
                6
            } else {
                L.push(ev.type); L.push(ev.pkg); L.push(ev.cls); L.push(ev.text)
                4
            }
        })
        ctx.setGlobal("__event_read")

        // __event_enable(on)：转发开关
        ctx.push(adapter { L ->
            val on = L.toBoolean(2)
            try {
                if (isRoot()) {
                    val (rc, _) = exec("am", "broadcast", "-a", EventBridge.ACTION_EVENT_FORWARD,
                        "--ei", EventBridge.EXTRA_ENABLE, if (on) "1" else "0",
                        "-p", "com.autolua.autolua2")
                    ctx.push(rc == 0)
                } else {
                    EventBridge.forwardEnabled = on
                    if (on) EventBridge.onForwardEnabled() // 开启即重置队列：新脚本不收上一轮残留
                    ctx.push(true)
                }
            } catch (t: Throwable) {
                Log.w("EventLib", "enable failed: $t")
                ctx.push(false)
            }
            1
        })
        ctx.setGlobal("__event_enable")
    }

    private fun adapter(body: (LuaContext) -> Int): LuaObjectAdapter = object : LuaObjectAdapter {
        override fun invoke(L: LuaContext): Int = body(L)
        override fun index(L: LuaContext): Int = 0
        override fun newIndex(L: LuaContext): Int = 0
        override fun call(L: LuaContext, name: String): Int = 0
        override fun release(L: LuaContext) {}
    }
}