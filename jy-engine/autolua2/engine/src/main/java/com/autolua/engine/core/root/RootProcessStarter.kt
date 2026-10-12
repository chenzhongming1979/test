package com.autolua.engine.core.root

import android.util.Log
import java.io.File

class RootProcessStarter {
  private fun startRootProcess(): Process? {
    try{
      return Runtime.getRuntime().exec("su\n")
    }catch (e:Exception){
      Log.e("RootProcessStarter", "Start root process failed", e)
    }
    return null
  }
  private var packagePath:String = ""
  private var processName:String = ""
  private var targetClassName:String = ""

  fun setPackagePath(path:String): RootProcessStarter {
    packagePath = path
    return this
  }

  fun setProcessName(name:String): RootProcessStarter {
    processName = name
    return this
  }

  fun setStartClass(clazz: Class<*>): RootProcessStarter{
    targetClassName = clazz.name
    return this
  }

  private fun getLibraryRootPath():String {
    val root = packagePath.subSequence(0, packagePath.lastIndexOf("/"))
    return "$root/lib/"
  }

  private fun getLibraryPath():String {
    val root = getLibraryRootPath()
    val file = File(root)
    val files = file.listFiles()
    if (files == null || files.isEmpty()) {
      throw RuntimeException("No library found")
    }
    for(f in files){
      if(f.name.indexOf("64")>0){
        return f.absolutePath
      }
    }
    return files[0].absolutePath
  }



  private fun buildCommandLine(): String {
    val command = StringBuilder()
    val libPath = getLibraryPath()
    command.append("export LD_LIBRARY_PATH=\"$libPath:${System.getProperty("java.library.path")}\"\n")
    command.append("export CLASSPATH=$packagePath\n")
    if(libPath.contains("64")) {
      command.append("/system/bin/app_process64 /system/bin ")
    }else{
      command.append("/system/bin/app_process /system/bin ")
    }
    if(processName.isNotEmpty()){
      command.append("--nice-name=$processName ")
    }
    if(targetClassName.isEmpty()){
      throw RuntimeException("target class is empty")
    }
    command.append("$targetClassName \n")
    return command.toString()
  }

  /**
   * 清理残留的同类引擎进程：su 拉起的 app_process 脱离 app 生命周期（父管道由 su/sh
   * 守护链持有，app 退出不产生 EOF），force-stop/重装/重启后旧实例仍存活，会占住
   * 系统全局唯一的 shell UiTestAutomationService 注册位（shell UiAutomation 全机仅一连接），
   * 导致新引擎 "UiAutomationService already registered" 恒失败。
   * 拉起前先按目标类名清场（「最新实例胜出」语义）。
   */
  private fun killStaleServers() {
    if (targetClassName.isEmpty()) return
    try {
      val cleaner = startRootProcess() ?: return
      val script = "ps -A -o PID,ARGS | grep '$targetClassName' | grep -v grep " +
          "| awk '{print \$1}' | xargs kill -9\nexit\n"
      cleaner.outputStream.write(script.toByteArray())
      cleaner.outputStream.flush()
      cleaner.waitFor()
    } catch (e: Exception) {
      Log.w("RootProcessStarter", "kill stale servers failed (ignore)", e)
    }
  }

  fun start(): Process? {
    killStaleServers()
    val process = startRootProcess()
    if (process != null) {
      val command = buildCommandLine()
      Log.d("RootProcessStarter", "Command: $command")
      process.outputStream.write(command.toByteArray())
      process.outputStream.flush()
    }
    return process
  }

}