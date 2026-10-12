package com.autolua.engine.core


import android.view.KeyEvent
import android.view.WindowManager
import androidx.collection.LongSparseArray
import com.autolua.engine.base.JavaObjectWrapper
import com.autolua.engine.base.LuaContext
import com.autolua.engine.base.LuaContextImplement
import com.autolua.engine.base.LuaObjectAdapter
import com.autolua.engine.base.MethodCache
import com.autolua.engine.base.ObjectCacheImp
import com.autolua.engine.base.ScriptContextLifecycleListener
import com.autolua.engine.accessibility.AutoLuaAccessibilityService
import com.autolua.engine.common.Observable
import com.autolua.engine.common.ObservableImp
import com.autolua.engine.core.AutoLuaEngine.*
import com.autolua.engine.extension.display.Display
import com.autolua.engine.extension.display.EmptyDisplay
import com.autolua.engine.extension.input.EmptyInputManager
import com.autolua.engine.extension.input.InputManager
import com.autolua.engine.extension.node.NodeDomainInstaller
import com.autolua.engine.extension.node.NodeLib
import com.autolua.engine.extension.node.UiAutomator
import com.autolua.engine.extension.node.imp.accessibility.AccessibilityImp
import kotlin.reflect.full.memberProperties
import kotlin.reflect.javaType


/**
 * luaContext 初始化步骤
 * 1. luaContext create
 * 2. push environment
 * 3. push local services
 * 4. push remote services proxy
 */
class AutoLuaEngineOnLocal (private val codeProvider: com.autolua.engine.core.composite.CodeProvider = com.autolua.engine.core.composite.CodeProvider(),
                            private val resourceProvider: com.autolua.engine.core.composite.ResourceProvider = com.autolua.engine.core.composite.ResourceProvider(),
  private val environment: List<Environment<*>> = listOf(),
  private val localServices: List<LocalService<*>> = listOf(),
  private val remoteServices: List<RemoteServerConfigure> = listOf(),
  display:Display = EmptyDisplay(),
  inputManager: InputManager = EmptyInputManager(),
  isRoot:Boolean = false,
  private val uiAutomator: UiAutomator? = null)
  : AutoLuaEngine,CodeProvider by codeProvider,
    ResourceProvider by resourceProvider,
    Observable<State> by ObservableImp()
{

  /** root 进程标记（沙箱惰性装配节点域时判定用） */
  private val isRootEngine: Boolean = isRoot

  private val messageObserver = com.autolua.engine.core.composite.MessageObserver()
  private var nativePtr:Long
  /***
   *  engine 相关的方法
   */

  private external fun createNative(display:Display,inputManager: InputManager,isRoot:Boolean):Long
  private external fun releaseNative(ptr:Long)
  private external fun getState(ptr:Long,target:Int):Int
  init {
    nativePtr = createNative(display,inputManager,isRoot)
    for (remoteService in remoteServices){
      addRemoteServiceNative(nativePtr,remoteService)
    }
  }
  companion object {
    init {
      System.loadLibrary("engine")
    }
    private const val TAG = "AutoLuaEngineOnLocal"
    external fun changeLogChannel(channel: Int)
//    fun debuggerConfigure2RemoteServerConfigure(configure: DebuggerConfigure):RemoteServerConfigure{
//      val remoteServerConfigure = RemoteServerConfigure("debugger",configure.port)
//      remoteServerConfigure.auth = configure.auth
//      remoteServerConfigure.host = configure.host
//      remoteServerConfigure.services = RemoteServerConfigure.OBSERVER or
//              RemoteServerConfigure.CODE_PROVIDER or
//              RemoteServerConfigure.RESOURCE_PROVIDER or
//              RemoteServerConfigure.CONTROLLER
//      remoteServerConfigure.rpcServices.addAll(configure.rpcServices)
//      return remoteServerConfigure
//    }
  }
  private fun onStateChanged(state:Int,target:Int){
    com.autolua.engine.common.Utils.log(TAG,"onStateChanged  ${AutoLuaEngine.Target.fromInt(target)}  ${State.fromInt(state)}")
    notifyObservers(State.fromInt(state),target)
    // 脚本容器销毁广播（正常退出 / exitScript / interrupt 强杀全路径汇聚 kIdle）：
    // 通知各服务释放该容器运行期间创建的跨层资源。本方法由 C++ loop 主线程经 JNI 调入，
    // 回调内禁止阻塞由实现方契约保证（重清理异步）；逆序遍历（后注册先回收），
    // 逐个 try-catch 吞异常，单个服务回收失败不影响其他服务
    if(State.fromInt(state) == State.IDLE && AutoLuaEngine.Target.fromInt(target) == AutoLuaEngine.Target.WORKER){
      val listeners = synchronized(localServices){ lifecycleListeners.toList() }
      for(i in listeners.indices.reversed()){
        try{
          listeners[i].onScriptContextDestroyed()
        }catch (t:Throwable){
          com.autolua.engine.common.Utils.log(TAG,"onScriptContextDestroyed failed from ${listeners[i]::class.java.name}\n${android.util.Log.getStackTraceString(t)}")
        }
      }
    }
  }
  private external fun start(ptr:Long):Int
  private external fun setRootDir(ptr:Long,rootDir:String)
  override fun setRootDir(rootDir: String) {
    // 同值注入：NodeLib.rootDir 对齐 Lua 全局 ROOT_DIR（saveNode 缺省落盘根）
    NodeLib.rootDir = rootDir
    setRootDir(nativePtr,rootDir)
  }

  override fun start(callback: Callback?) {
    if(start(nativePtr) == 0){
      callback?.invoke(ResultCode.SUCCESS)
    }else{
      callback?.invoke(ResultCode.RUNNING)
    }
  }
  private external fun stopNative(ptr:Long)
  override fun stop() {
    stopNative(nativePtr)
  }
  private external fun waitForStop(ptr:Long)
  fun waitForStop(){
    waitForStop(nativePtr)
  }

  override fun getState(target:AutoLuaEngine.Target): State {
    return State.fromInt(getState(nativePtr, target.value))
  }

  override fun destroy() {
    synchronized(this){
      if(nativePtr == 0L)
        return
      val ptr = nativePtr
      nativePtr = 0L
      releaseNative(ptr)
    }
  }

  private fun onWarning(message:String){
    messageObserver.onWarning(message)
  }
  private fun onError(message:String){
    messageObserver.onError(message)
  }

  private external fun startFatherService(ptr:Long,configure: RemoteServerConfigure)
  fun startFatherService(configure: RemoteServerConfigure){
    startFatherService(nativePtr,configure)
  }

  /**
   * lua 环境配置
   */
  private external fun addRemoteServiceNative(ptr:Long,remoteServerConfigure:RemoteServerConfigure)
  private external fun removeRemoteServiceNative(ptr:Long,name:String)

  /**
   * push environment to lua context
   */
  private fun pushEnvironment(luaContext: LuaContext){
    synchronized(environment){
      environment.forEach {
        when(it.type){
          Environment.Type.STRING ->{
            luaContext.push(it.value as String)
            luaContext.setGlobal(it.key)
          }
          Environment.Type.LONG -> {
            luaContext.push(it.value as Long)
            luaContext.setGlobal(it.key)
          }
          Environment.Type.DOUBLE -> {
            luaContext.push(it.value as Double)
            luaContext.setGlobal(it.key)
          }
          Environment.Type.BOOLEAN -> {
            luaContext.push(it.value as Boolean)
            luaContext.setGlobal(it.key)
          }
          Environment.Type.BYTE_ARRAY -> {
            luaContext.push(it.value as ByteArray)
            luaContext.setGlobal(it.key)
          }
          Environment.Type.INT -> {
            luaContext.push((it.value as Int).toLong())
            luaContext.setGlobal(it.key)
          }
          Environment.Type.FLOAT -> {
            luaContext.push((it.value as Float).toDouble())
            luaContext.setGlobal(it.key)
          }
        }
      }
    }
  }
  /**
   * push local services to lua context
   */
  private fun newLocalServiceObj(L:LuaContext,clazz:Class<*>):Any?{
    try{
      val c = clazz.getConstructor(L::class.java)
      return c.newInstance(L)
    }catch (e:Exception){
    }
    try{
      val c = clazz.getConstructor()
      return c.newInstance()
    }catch (e:Exception){}
    return null
  }
  private val methodCache = MethodCache()
  /** 容器销毁广播的回收回调列表：pushLocalServices 时从实现了 ScriptContextLifecycleListener 的服务收集（与 localServices 同锁保护） */
  private val lifecycleListeners = mutableListOf<ScriptContextLifecycleListener>()
  /** 返回 (Lua 侧 adapter, 原始实例)；contribute 检查必须用原始实例（CLASS 服务会被包成 JavaObjectWrapper） */
  private fun wrapLocalService(L:LuaContext, service:LocalService<*>):Pair<LuaObjectAdapter?,Any?>{
    var obj = service.service
    if(service.type == LocalService.Type.CLASS){
      obj = newLocalServiceObj(L, service.mClass!!)
    }
    if(obj == null) return Pair(null,null)
    val raw = obj
    if(obj !is LuaObjectAdapter){
      obj = JavaObjectWrapper(service.mInterface!!,obj,methodCache)
    }
    return Pair(obj as LuaObjectAdapter?, raw)
  }
  private fun pushLocalServices(luaContext: LuaContext){
    synchronized(localServices){
      localServices.forEach {
        val (adapter,raw) = wrapLocalService(luaContext,it)
        if(adapter != null){
          luaContext.push(adapter)
          luaContext.setGlobal(it.name)
          // LuaEnvContributor：服务可自行注册全局函数/常量（如 luajava 的全局 import）；
          // 检查原始实例——CLASS 型服务经 JavaObjectWrapper 包装后自身不再是 LuaEnvContributor
          if(raw is com.autolua.engine.base.LuaEnvContributor){
            try{
              raw.contribute(luaContext)
            }catch (e:Exception){
              onError("Failed to contribute lua env from ${it.name}: $e")
            }
          }
          // ScriptContextLifecycleListener：容器销毁（一次脚本运行结束）时的资源回收回调；
          // 同样检查原始实例（与 LuaEnvContributor 同构），contains 去重防 OBJECT 型服务重复注册
          if(raw is ScriptContextLifecycleListener && !lifecycleListeners.contains(raw)){
            lifecycleListeners.add(raw)
          }
        }else{
          onError("Failed to wrap local service ${it.name}")
        }
      }
      // 插件 Lua API 贡献：已加载插件实现 LuaEnvContributor 的，向每个新建 LuaContext
      // 注入自定义全局（PluginLuaHelper 约定）。插件晚于本 context 加载的需重建 context 生效。
      try{
        com.autolua.engine.plugin.PluginManager.getInstance().contributeLua(luaContext)
      }catch (e:Throwable){
        onError("Failed to contribute lua env from plugins: $e")
      }
    }
  }

  /**
   * init lua context
   */
  private val objectCache = ObjectCacheImp()
  private val contextCache = LongSparseArray<LuaContext>()
  private fun newLuaContext():Long{
    try{
      return rawNewLuaContext()
    }catch (e:Exception){
      // 打印完整堆栈：NPE 无栈时 root 引擎 LuaContext 创建失败只能看到异常类名，无法定位
      com.autolua.engine.common.Utils.log(TAG,"Failed to create lua context\n${android.util.Log.getStackTraceString(e)}")
      return 0
    }
  }

  /**
   * C++ LuaStateFactory 在 injectOther/display/input/images 注入完成后回调：
   * Java 侧服务（含 compat contribute）必须在原生图色函数就绪后注入，
   * 否则 compat 包装层捕获 getColor/isColor/findColor 等会拿到 nil（cmpColor 断链）。
   */
  private fun postNativeInject(ptr:Long){
    try{
      val luaContext = contextCache.get(ptr)
      if(luaContext != null){
        pushLocalServices(luaContext)
      }
    }catch (e:Exception){
      com.autolua.engine.common.Utils.log(TAG,"postNativeInject failed\n${android.util.Log.getStackTraceString(e)}")
    }
  }
  private object KeyCode {
    val HOME = KeyEvent.KEYCODE_HOME
    val BACK = KeyEvent.KEYCODE_BACK
    val MENU = KeyEvent.KEYCODE_MENU
    val VOLUME_UP = KeyEvent.KEYCODE_VOLUME_UP
    val VOLUME_DOWN = KeyEvent.KEYCODE_VOLUME_DOWN
    val POWER = KeyEvent.KEYCODE_POWER
    // 派大星 touch.* 按键面扩充
    val RECENT = KeyEvent.KEYCODE_APP_SWITCH
    val VOLUME_MUTE = KeyEvent.KEYCODE_VOLUME_MUTE
    val DPAD_UP = KeyEvent.KEYCODE_DPAD_UP
    val DPAD_DOWN = KeyEvent.KEYCODE_DPAD_DOWN
    val DPAD_LEFT = KeyEvent.KEYCODE_DPAD_LEFT
    val DPAD_RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT
    val DPAD_CENTER = KeyEvent.KEYCODE_DPAD_CENTER
    val ENTER = KeyEvent.KEYCODE_ENTER
    val TAB = KeyEvent.KEYCODE_TAB
    val DEL = KeyEvent.KEYCODE_DEL
    val SEARCH = KeyEvent.KEYCODE_SEARCH
    val CAMERA = KeyEvent.KEYCODE_CAMERA
    val CALL = KeyEvent.KEYCODE_CALL
    val ENDCALL = KeyEvent.KEYCODE_ENDCALL
  }

  private object LayoutParamsFlag {
    val NOT_FOCUSABLE = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    val NOT_TOUCHABLE = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    val NOT_TOUCH_MODAL = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
  }

  private fun rawNewLuaContext():Long{
    val luaContext = LuaContextImplement(objectCache)
    contextCache.put(luaContext.getNativeLua() ,luaContext)
    pushEnvironment(luaContext)
    // 节点域全局注册收敛到 NodeDomainInstaller（与 IDE 沙箱执行器共用，三环境一致）：
    // NodeLib 接线 + UiSelector/getRootInActiveWindow/setText + NodeLib 全族 + waitFor
    // + 无条件注册的 screenshot（root 引擎进程无服务实例时优雅返回 nil）。
    // 沙箱惰性装配：引擎构建时无障碍可能尚未连接（per-context 兜底消除时序竞争）——
    // 未注入 uiAutomator 且非 root 且服务已连接时，本 context 自动补装 AccessibilityImp
    val nodeAutomator = uiAutomator
      ?: if (!isRootEngine && AutoLuaAccessibilityService.isRunning()) AccessibilityImp() else null
    NodeDomainInstaller.install(luaContext, nodeAutomator)
    luaContext.pushTable(KeyCode)
    luaContext.setGlobal("KeyCode")
    luaContext.pushTable(LayoutParamsFlag)
    luaContext.setGlobal("LayoutParamsFlag")


    return luaContext.getNativeLua()
  }

  private fun releaseContext(ptr:Long){
    val l = contextCache.get(ptr)
    if(l != null){
      contextCache.remove(ptr)
      l.destroy()
    }
  }

  /***
   * worker 相关的方法
   */
  private external fun executeNative(ptr:Long, script:ByteArray,codeType:Int,flags:Int):Int
  override fun execute(script: String,flags:Int) {
    executeNative(nativePtr,script.toByteArray(),LuaContext.CodeMode.TEXT.value,flags)
  }

  override fun execute(
    script: ByteArray,
    codeMode: LuaContext.CodeMode,
    flags: Int
  ) {
    executeNative(nativePtr,script,codeMode.value,flags)
  }
  private external fun interruptNative(ptr:Long)
  override fun interrupt() {
    interruptNative(nativePtr)
  }

  // 三期：pause/resume JNI 入口（engine.cpp 的 pauseNative/resumeNative），
  // 返回发信号后的 worker 状态（ALEState：0=kIdle 2=kRunning 4=kPausing 5=kPaused 6=kResuming）
  private external fun pauseNative(ptr:Long):Int
  override fun pause() {
    val state = pauseNative(nativePtr)
    com.autolua.engine.common.Utils.log(TAG,"pause -> worker state $state")
  }
  private external fun resumeNative(ptr:Long):Int
  override fun resume() {
    val state = resumeNative(nativePtr)
    com.autolua.engine.common.Utils.log(TAG,"resume -> worker state $state")
  }


  /***
   * debugger 相关的方法
   */
  /***
   * jni 调用
   */

  private external fun startDebugService(ptr: Long,configure: RemoteServerConfigure)
  override fun startDebugger(debuggerConfigure:DebuggerConfigure) {
    startDebugService(nativePtr, Utils.debuggerConfigure2RemoteServerConfigure(debuggerConfigure))
  }

  private external fun stopDebugService(ptr:Long)
  override fun stopDebugger() {
    stopDebugService(nativePtr)
  }

  class Builder :AutoLuaEngine.Builder{
    private val remoteServices = mutableListOf<RemoteServerConfigure>()
    override fun addRemoteService(remoteServerConfigure: RemoteServerConfigure): AutoLuaEngine.Builder {
      remoteServices.add(remoteServerConfigure)
      return this
    }

    private val localServices = mutableListOf<LocalService<*>>()
    override fun addLocalService(localService: LocalService<*>): AutoLuaEngine.Builder {
      localServices.add(localService)
      return this
    }

    override fun addLocalService(
      name: String,
      service: Any,
      thisInterface: Class<*>
    ): AutoLuaEngine.Builder {
      localServices.add(LocalService(name,service,thisInterface))
      return this
    }

    override fun addLocalService(
      name: String,
      serviceClass: Class<*>,
      thisInterface: Class<*>
    ): AutoLuaEngine.Builder {
      val localService = LocalService<Any>(name, LocalService.Type.CLASS)
      localService.mClass = serviceClass as Class<Any>
      // 接口必须写入 mInterface：pushLocalServices 构建 JavaObjectWrapper 时用
      // mInterface 反射方法表，漏写会在创建 LuaContext 时 NPE
      localService.mInterface = thisInterface
      localServices.add(localService)
      return this
    }

    private val environment = mutableListOf<Environment<*>>()
    override fun addEnvironment(key: String, value: Any): AutoLuaEngine.Builder {
      environment.add(Environment(key,value))
      return this
    }

    private val codeProvider = com.autolua.engine.core.composite.CodeProvider()
    override fun addCodeProvider(codeProvider: CodeProvider): AutoLuaEngine.Builder {
      this.codeProvider.addProvider(codeProvider)
      return this
    }

    private val resourceProvider = com.autolua.engine.core.composite.ResourceProvider()
    override fun addResourceProvider(resourceProvider: ResourceProvider): AutoLuaEngine.Builder {
      this.resourceProvider.addProvider(resourceProvider)
      return this
    }

    private var display: Display? = null
    override fun setDisplay(display: Display): AutoLuaEngine.Builder {
      this.display = display
      return this
    }

    private var inputManager: InputManager? = null
    override fun setInputManager(inputManager: InputManager): AutoLuaEngine.Builder {
      this.inputManager = inputManager
      return this
    }
    private var uiAutomator: UiAutomator? = null
    override fun setUiAutomator(uiAutomator: UiAutomator): AutoLuaEngine.Builder {
      this.uiAutomator = uiAutomator
      return this
    }

    private var isRoot = false
    override fun isRoot(isRoot: Boolean): AutoLuaEngine.Builder {
      this.isRoot = isRoot
      return this
    }

    override fun build(): AutoLuaEngine {
      // 沙箱（非 root）装配兜底：未显式注入 uiAutomator 且无障碍服务已连接时，
      // 自动创建无障碍节点实现（app 侧构建沙箱引擎不感知服务连接状态，由引擎侧接线；
      // 服务未连接则不装配，节点全局不注册，Lua 层经 compat 守卫给出中文提示）
      val ua = uiAutomator
        ?: if (!isRoot && AutoLuaAccessibilityService.isRunning()) AccessibilityImp() else null
      return AutoLuaEngineOnLocal(codeProvider,resourceProvider,
        environment,localServices,
        remoteServices,
        display ?: EmptyDisplay(),
        inputManager ?: EmptyInputManager(),
        isRoot,ua)
    }

  }
}