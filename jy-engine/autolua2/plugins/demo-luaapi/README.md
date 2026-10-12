# Demo Lua API 插件（P1 插件 Lua 贡献机制活体案例）

`com.autolua.plugin.demoapi` —— 实现 `IPlugin` + `LuaEnvContributor`，
验证"**插件定义函数 → 脚本直接调用**"链路（宿主引擎在建 LuaContext 时经
`PluginManager.contributeLua` 回调插件，`PluginLuaHelper.putGlobal` 把普通 Java 对象
注册为 Lua 全局表）。

安装后脚本可直接调用：

```lua
print(plugindemo.hello("world"))  -- hello from plugin, world
print(plugindemo.add(1, 2))       -- 3.0
print(plugindemo.pluginDir())     -- 本插件私有数据目录绝对路径
print(plugindemo.sum({1, 2, 3}))  -- 6.0（表参数 → List 桥接验证）
```

> 主引擎（root 进程）为长生命周期 LuaContext：**装完本插件需重启引擎**（或重建
> context）后 `plugindemo` 才出现；IDE 沙箱由 app 启动流程自动补偿触发。

## 目录结构

```
plugins/demo-luaapi/
├── README.md
├── manifest.json                      # sha256 占位，打包后回填
└── src/com/autolua/plugin/demoapi/
    └── DemoLuaApiPlugin.java          # 入口 + Api 表
```

## 编译打包（同 demo-ocr 流程）

依赖：JDK 8+、Android SDK；engine 的 classes.jar（先 `gradlew :engine:assembleDebug`）。
在仓库根 `autolua2\` 下执行（PowerShell）：

```powershell
$SDK = "G:\Android\SDK"
$AJ  = "$SDK\platforms\android-34\android.jar"
$ENG = "engine\build\intermediates\compile_library_classes_jar\debug\bundleLibCompileToJarDebug\classes.jar"

javac -source 8 -target 8 -encoding UTF-8 -cp "$AJ;$ENG" `
      -d plugins\demo-luaapi\build\classes `
      plugins\demo-luaapi\src\com\autolua\plugin\demoapi\DemoLuaApiPlugin.java

& "$SDK\build-tools\36.0.0\d8.bat" --release --min-api 22 `
      --lib $AJ --classpath $ENG --output plugins\demo-luaapi\build `
      (Get-ChildItem plugins\demo-luaapi\build\classes -Recurse -Filter *.class).FullName
```

打包 zip + 内容清单 sha256 回填：函数与流程见 demo-ocr README
（zip = manifest.json + classes.dex；hash 算法两端同源，mismatch 时把
computed 值回填 manifest 重打即可）。
