package com.autolua.autolua2.extension.ui

import com.autolua.engine.base.Releasable

/**
 * 动态 UI 服务接口（JSON op 协议，参考懒人 UIMgr / 派大星 FloatApi）。
 *
 * 跨进程安全：参数与返回值均为 String（root 模式下 Lua 所在 root 进程经
 * Client/Server 通道调用，UI 实例固定在 app 主进程——悬浮窗权限所在进程）。
 *
 * op(json)   请求帧：{"op":"win.create", ...} → 响应帧 {"ok":true,...| "err":"..."}
 * pollEvents() 事件帧队列排空：JSON 数组字符串（ui 事件 / dialog 结果），无事件返回 "[]"
 */
interface IUiMgr : Releasable {
  fun op(json: String): String
  fun pollEvents(): String

  /** 容器销毁兜底（仅弹窗+事件队列）：窗口由脚本 Lua OnStop 链主动关闭
   *  （loop 线程序列化保证不误杀新容器窗口）；本方法在 app 侧 IDLE 观察者调用 */
  fun releaseDialogs()
}
