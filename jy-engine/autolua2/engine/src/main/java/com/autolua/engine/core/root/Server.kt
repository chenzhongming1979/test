package com.autolua.engine.core.root

import android.annotation.SuppressLint
import android.os.Parcel
import android.os.Parcelable
import com.autolua.engine.base.MethodCache
import com.autolua.engine.base.Releasable
import com.autolua.engine.common.Observer
import com.autolua.engine.core.AutoLuaEngine
import com.autolua.engine.core.AutoLuaEngineOnLocal
import com.autolua.engine.common.Utils
import com.autolua.engine.extension.display.DisplayImplement
import com.autolua.engine.extension.input.InputManagerImp
import com.autolua.engine.extension.node.imp.root.UiAutomatorImp
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess


class Server {
  // 传输绑原始 stderr fd（而非 System.err 对象）：System.err 已在 main 里重定向到 /dev/null，
  // 任何 printStackTrace/杂散输出不再侵入传输管道（堆栈洪水打满管道 → pollEvents 15s 超时实测）
  private val transporter: Transporter = TransportImp(System.`in`,java.io.FileOutputStream(java.io.FileDescriptor.err),true)
  private lateinit var engine: AutoLuaEngine
  private val observer:Observer<AutoLuaEngine.State> = newService(InvokePackage.OBSERVER)
  private val codeProvider: AutoLuaEngine.CodeProvider = newService(InvokePackage.CODE_PROVIDER)
  private val resourceProvider: AutoLuaEngine.ResourceProvider = newService(InvokePackage.RESOURCE_PROVIDER)
  private val latch = CountDownLatch(1)
  private val methodCache = MethodCache()
  private val uiAutomator = UiAutomatorImp()

  fun run(){
    transporter.start()
    latch.await()
    onDestroy()
    transporter.rawWrite("exit".toByteArray())
  }

  private fun onDestroy(){
    engine.stop()
    Thread.sleep(3000)
    transporter.stop()
    uiAutomator.destroy()
  }

  private fun findMethod(name:String,args: Array<out Any>?):Method{
    val clazz = AutoLuaEngine::class.java
    return methodCache.findMethod(clazz,name) ?: throw NoSuchMethodException(name)
  }

  private fun onHandlerRequest(parcel: Parcel){
    val invokePackage: InvokePackage = parcel.readValue(InvokePackage::class.java.classLoader) as InvokePackage?
      ?: return
    val callId = invokePackage.callId
    val methodName = invokePackage.method
    // 热路径日志已移除（2026-09-23）：本线程是 transporter 读线程，stdout 管道满时
    // println 阻塞 → 响应读不回来 → pollEvents 等 waitAndRemote 15s 超时（实测踩坑）
    val args = invokePackage.args
    val response = Parcel.obtain()
    response.writeByte(2)
    response.writeInt(callId)
    try{
      if(methodName == "destroy"){
        latch.countDown()
        response.writeNoException()
        response.writeValue(null)
      }else{
        val method = findMethod(methodName,args)
        val result = method.invoke(engine, *args ?: emptyArray())
        response.writeNoException()
        response.writeValue(result)
      }
    }catch (e:Exception){
      if(e is Parcelable){
        response.writeException(e)
      }else{
        response.writeException(RuntimeException(e))
      }
    }
    val data = response.marshall()
    response.recycle()
    transporter.send(data)
  }

  private fun initTransporter(){
    transporter.setOnMessage {
      val parcel = Parcel.obtain()
      parcel.unmarshall(it,0,it.size)
      parcel.setDataPosition(0)
      val type = parcel.readByte().toInt()
      // 热路径日志已移除（2026-09-23）：读线程上逐消息 stdout 写会在管道满时阻塞响应处理
      if(type == 1){
        try{
          onHandlerRequest(parcel)
        }finally{
          parcel.recycle()
        }
      }else{
        val id = parcel.readInt()
        dataCallbackManager.notify(id,parcel)
      }
    }
  }
  private fun serviceInfo2LocalService(info: Client.LocalService):AutoLuaEngine.LocalService<*>{
    val interfaceClass = Class.forName(info.interfaceName)
    return if(info.serviceId!=null){
      val obj = newService(info.serviceId,interfaceClass)
      AutoLuaEngine.LocalService(info.name,obj,interfaceClass)
    }
    else{
      val r= AutoLuaEngine.LocalService<Any>(info.name, AutoLuaEngine.LocalService.Type.CLASS)
      r.mClass = Class.forName(info.className!!) as Class<Any>
      r.mInterface = interfaceClass
      r
    }
  }

  @SuppressLint("Recycle")
  private fun initEngine():Boolean{
    val data = transporter.rawRead()
    if(data == null || data.isEmpty()){
      exitProcess(1)
    }
    val parcel = Parcel.obtain()
    parcel.unmarshall(data,0,data.size)
    parcel.setDataPosition(0)
    val environments = parcel.readValue(AutoLuaEngine.Environment::class.java.classLoader) as List<AutoLuaEngine.Environment<*>>
    val localServices = parcel.readValue(Client.LocalService::class.java.classLoader) as List<Client.LocalService>
    val remoteServices = parcel.readValue(AutoLuaEngine.RemoteServerConfigure::class.java.classLoader) as List<AutoLuaEngine.RemoteServerConfigure>
    // 协议（与 Client.build 配对）：三个 writeValue 之后追加 writeInt = 调用方（app 主进程）pid，
    // 供父死自退出 watchdog 探测（su 守护链持有管道副本，app 退出后 EOF 不可靠）
    val callerPid = parcel.readInt()
    val builder = AutoLuaEngineOnLocal.Builder()
    for(env in environments){
      builder.addEnvironment(env.key,env.value)
    }
    for(info in localServices){
      builder.addLocalService(serviceInfo2LocalService(info))
    }
    for(info in remoteServices){
      builder.addRemoteService(info)
    }
    builder.addCodeProvider(codeProvider)
    builder.addResourceProvider(resourceProvider)
    builder.setDisplay(DisplayImplement())
    builder.setInputManager(InputManagerImp.instance)
    builder.setUiAutomator(uiAutomator)
    builder.isRoot(true)
    // 基础能力三件套（root 进程本地注册：实例在 Lua 所在进程内，跨进程实例无法返回 LuaObjectAdapter）
    builder.addLocalService("http", com.autolua.engine.extension.net.NetLib::class.java,
      com.autolua.engine.extension.net.INetLib::class.java)
    builder.addLocalService("luajava", com.autolua.engine.extension.bridge.JavaBridgeLib::class.java,
      com.autolua.engine.extension.bridge.IJavaBridge::class.java)
    builder.addLocalService("LuaEngine", com.autolua.engine.extension.bridge.LuaEngineLib::class.java,
      com.autolua.engine.extension.bridge.ILuaEngineLib::class.java)
    // 商业引擎兼容域库（file/device/crypt/app 对象 + 各自 contribute 的懒人风格全局函数）
    builder.addLocalService("file", com.autolua.engine.extension.file.FileLib::class.java,
      com.autolua.engine.extension.file.FileLib::class.java)
    builder.addLocalService("device", com.autolua.engine.extension.device.DeviceLib::class.java,
      com.autolua.engine.extension.device.DeviceLib::class.java)
    builder.addLocalService("crypt", com.autolua.engine.extension.crypto.CryptoLib::class.java,
      com.autolua.engine.extension.crypto.CryptoLib::class.java)
    // 云控通道（Java 下沉）：WS 9300 + PSK 帧加解密在 Java 层，Lua 只见明文 JSON 收发。
    // 必须在 compat 之后注册——CLOUD_LUA 装载时包装 OnStop（cloud.close+pending 会话清理），
    // 若先于 compat 装载会被 CompatLib 的 OnStop 定义覆盖，pendingObs 跨运行累积（每条
    // 消息 dispatch N 遍）且 9300 泄漏
    builder.addLocalService("compat", com.autolua.engine.extension.compat.CompatLib::class.java,
      com.autolua.engine.extension.compat.CompatLib::class.java)
    builder.addLocalService("cloudServer", com.autolua.engine.cloud.CloudService::class.java,
      com.autolua.engine.cloud.ICloudService::class.java)
    builder.addLocalService("app", com.autolua.engine.extension.app.AppLib::class.java,
      com.autolua.engine.extension.app.AppLib::class.java)
    // 插件运行时（root 进程本地）：注入宿主 app 插件目录（uid 0 直读）后加载，
    // plugin/ocr Lua 口才能在本进程工作（帧数据 ~3.7MB 超 Binder 1MB 限制，不走跨进程）
    run {
      val pkgData = "/data/data/com.autolua.autolua2"
      val pm = com.autolua.engine.plugin.PluginManager.getInstance()
      pm.setDirectoryOverrides(
        java.io.File("$pkgData/app_plugins"),
        java.io.File("$pkgData/app_plugin_libs"),
        java.io.File("$pkgData/app_plugin_data"),
        java.io.File("$pkgData/cache/plugin_odex_root"))
      try {
        pm.loadAll()
      } catch (t: Throwable) {
        Utils.log("server", "plugin loadAll failed: $t")
      }
    }
    builder.addLocalService("plugin", com.autolua.engine.extension.plugin.PluginLib::class.java,
      com.autolua.engine.extension.plugin.PluginLib::class.java)
    builder.addLocalService("ocr", com.autolua.engine.extension.ocr.OcrLib::class.java,
      com.autolua.engine.extension.ocr.OcrLib::class.java)
    builder.addLocalService("media", com.autolua.engine.extension.media.MediaLib::class.java,
      com.autolua.engine.extension.media.MediaLib::class.java)
    builder.addLocalService("ime", com.autolua.engine.extension.input.ImeLib::class.java,
      com.autolua.engine.extension.input.ImeLib::class.java)
    builder.addLocalService("mysql", com.autolua.engine.extension.db.MySqlLib::class.java,
      com.autolua.engine.extension.db.MySqlLib::class.java)
    builder.addLocalService("event", com.autolua.engine.extension.event.EventLib::class.java,
      com.autolua.engine.extension.event.EventLib::class.java)
    // 虚拟屏（Shizuku 路径，任务书 §4/§5）：virtualDisplay 表 + vd 对象（帧源/注入按屏路由；
    // create 无 Shizuku 时抛可读错误）。三环境一致注册，compat 之前
    builder.addLocalService("virtualDisplay", com.autolua.engine.extension.virtualdisplay.VirtualDisplayLib::class.java,
      com.autolua.engine.extension.virtualdisplay.VirtualDisplayLib::class.java)
    // 动态 UI Lua 层（ui_* API 全集；引用跨进程 uimgr 服务与 thread/json，惰性寻址不依赖注册顺序）
    builder.addLocalService("uilib", com.autolua.engine.extension.ui.UiLib::class.java,
      com.autolua.engine.extension.ui.UiLib::class.java)
    engine = builder.build()
    AutoLuaEngineOnLocal.changeLogChannel(1)
    engine.addObserver(observer)
    // 父死自退出：app 进程消失即自杀（退出前 graceful 断开 UiAutomation，防注册位泄漏）。
    // su 守护链持有管道副本，管道 EOF 不可靠，pid 探测是唯一可靠信号。
    ParentWatchdog.start(callerPid) { uiAutomator.destroy() }
    return true
  }

  fun init():Boolean{
    initTransporter()
    if(initEngine()){
      transporter.rawWrite("ok".toByteArray())
      return true
    }else{
      transporter.rawWrite("abort".toByteArray())
    }
    return false
  }

  companion object{
    @JvmStatic
    fun main(args: Array<String>){
      Utils.inRootProcess = true
      // System.err → /dev/null（2026-09-23）：stderr 的原始 fd 是传输管道（TransportImp 直绑），
      // 库/反射代码的 printStackTrace 会把堆栈灌进传输流 → 管道打满 → pollEvents 超时全 Lua 冻结
      try {
        System.setErr(java.io.PrintStream(java.io.FileOutputStream("/dev/null"), true))
      } catch (ignored: Throwable) {}
      val server = Server()
      if(!server.init()){
        exitProcess(1)
      }else{
        Utils.log("Server","start")
        server.run()
        exitProcess(0)
      }
    }
  }

  private fun newService(serviceId:UInt,serviceInterface:Class<*>):Any{
    val interfaces = if(!serviceInterface.isAssignableFrom(Releasable::class.java)) arrayOf(serviceInterface,Releasable::class.java) else arrayOf(serviceInterface)
    return Proxy.newProxyInstance(serviceInterface.classLoader, interfaces,ServiceInvocationHandler(serviceId))
  }

  private inline fun <reified T : Any> newService(serviceId:UInt): T {
    return newService(serviceId,T::class.java) as T
  }

  private fun invoke(serviceId:UInt, method:Method, args:Array<out Any>?):Any?{
    val t0 = android.os.SystemClock.elapsedRealtime()
    val parcel = Parcel.obtain()
    val callId = dataCallbackManager.create()
    val invokePackage = InvokePackage(serviceId,callId,method.name,args)
    parcel.writeByte(1)
    parcel.writeValue(invokePackage)
    val data = parcel.marshall()
    parcel.recycle()
    // IdeLog（print）走后台单线程发送（2026-09-23 根治）：此前 Lua 线程同步写管道，
    // print 洪水打满管道 → 持 LuaState 锁阻塞 → 全 Lua 冻结 + pollEvents 15s 超时（实测）
    if (method.declaringClass.name.endsWith("IdeLog")) {
      dataCallbackManager.remove(callId)
      logSendExecutor.execute { try { transporter.send(data) } catch (t: Throwable) {} }
      return null
    }
    transporter.send(data)
    // 其余远程调用超时上限（2026-09-23 15000→3000）：等待期间持 LuaState 锁，
    // app 侧排队/拥堵时 15s = 全 Lua 冻结 15s（实测多次）。fail-fast：超时抛错由
    // 调用方 pcall 吸收（fastSave/fastLoad/dispatch 均已兜底），冻结上限压到 3s
    val response = dataCallbackManager.waitAndRemote(callId,3000)
      ?: throw RuntimeException("remote invoke timeout: ${method.name}")
    // 慢调用观测（写 logcat，不进 IdeLog——Lua 冻结期它仍可见）：>300ms 即记录，
    // 单调用不慢但"连续快速调用排队持锁"的盲区靠序列还原（配合 app 端对偶记录）
    run {
      val dt = android.os.SystemClock.elapsedRealtime() - t0
      if (dt > 300) android.util.Log.w("EngineSlowCall",
        "remote ${method.name} service=$serviceId took ${dt}ms")
    }
    try{
      response.readException()
      if(method.returnType.isInterface){
        val result = response.readValue(Long::class.java.classLoader) as Long?
        return if(result!=null && result >0){
          newService(result.toUInt(),method.returnType)
        }else
          null
      }
      val result = response.readValue(method.returnType.classLoader)
      return result
    }finally {
      response.recycle()
    }

  }

  /** print（IdeLog）专用异步发送线程：单线程保序，管道背压时阻塞它而不阻塞 Lua 线程 */
  private val logSendExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
    Thread(r, "EngineLogSender").apply { isDaemon = true }
  }

  private val dataCallbackManager = DataCallbackManager<Parcel>()

  inner class ServiceInvocationHandler(private val serviceId:UInt): InvocationHandler {
    override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
      // 热路径日志已移除（2026-09-23）：此处跑在 Lua 线程上且持有 LuaState 锁，
      // 逐调用 stdout println 在管道满时阻塞 → 全 Lua 冻结（print 洪水实测根源之一）
      return this@Server.invoke(serviceId,method,args)
    }
  }
}