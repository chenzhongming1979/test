package com.autolua.autolua2

import com.autolua.engine.ide.IdeServerManager

/**
 * IDE 脚本输出 LocalService（9.12 主引擎输出回传）：
 * root 引擎进程内的脚本经全局 {@code IdeLog.log(line)} 调用本服务（跨进程），
 * 转发到 IdeServerManager 分流为 IDE WS 的 log 帧（老 8176 protobuf 通道退役后
 * 主引擎 print 的唯一回传路径）。MainApp 的 ScriptRunDelegate 在 run_script 提交时
 * 临时重定义脚本 print 指向本服务，不影响 App 内其余脚本的 print 行为。
 *
 * 注意：必须拆出接口 {@link IdeLog}——root 进程 Server.init 用 Proxy.newProxyInstance
 * 重建本地服务代理时要求 interface（传具体类会导致 root 引擎进程启动即崩溃，
 * "IdeLogService is not an interface"）。
 */
interface IdeLog {
  fun log(line: String)
}

class IdeLogService : IdeLog {
  override fun log(line: String) {
    IdeServerManager.getInstance().dispatchScriptOutput(line)
  }
}
