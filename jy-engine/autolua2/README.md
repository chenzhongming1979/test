# autolua2（JY-Frame）

基于 Lua 的 Android 自动化开发框架：图色识别、触控注入、无障碍节点操作、应用/设备控制、网络/数据库、插件扩展，配套 VSCode 插件实现远程开发调试（推文件 → 运行 → 日志回传 → 投屏操控）。

## 核心特性

- **Lua 5.4 脚本引擎**：C++ 原生解释器 + 14 个 Java 扩展域库 + 纯 Lua 兼容层，API 命名对齐《懒人精灵》《派大星》（商业引擎脚本迁移友好）
- **双执行模式**：root 引擎（app_process，uid 0，能力全量）/ IDE 沙箱（app 进程内联调）
- **图色**：共享内存 seqlock 双缓冲截图（root 免授权 / MediaProjection）、找色找图、cv.Mat 图像加工、images 编解码（PNG/JPEG/Base64）
- **触控**：四级注入降级链（root 直连 → root 子进程 → Shizuku → 无障碍）+ 拟人化轨迹；自研 IME 文本通道
- **节点**：UiSelector/UiObject 封装 + obtainXml 整树快取（AIDL 跨进程）
- **IDE**：WebSocket 调试服务（8080..8085）：增量同步、沙箱执行、主引擎运行、print 回传、图色远程计算、H264/JPEG 投屏、注入回传、插件推送
- **插件**：dex/jar + so 动态加载（DexClassLoader），sha256 清单强校验，OCR 插件契约
- **其他**：事件总线（toast/notification/touch）、thread 协程多线程、MySQL 桥、Fire 热更、luajava 反射桥、ws 客户端/服务端

## 快速开始

```powershell
# 构建（环境要求见 ../docs/05-构建与自测指南.md §1）
git submodule update --init --recursive
powershell -File tools\build_app.ps1

# 模拟器联调三步（详细见 ../docs/05-构建与自测指南.md §3）
adb install --no-streaming app\build\outputs\apk\debug\autolua2_*.apk
adb shell am start -n com.autolua.autolua2/.activity.SplashActivity   # 开启无障碍后等 ENGINE RUNNING
adb forward tcp:19080 tcp:8080                                        # VSCode 插件连 127.0.0.1:19080
```

- 写脚本：安装 `vscode-plugin\` （vsix 打包或 F5 调试），新建工程后 F5 运行；API 手册 [../docs/02-Lua-API规范.md](../docs/02-Lua-API规范.md)
- 浏览器控制台（免安装）：打开 `tools\remote.html`

## 仓库结构

| 目录 | 内容 |
|---|---|
| `engine\` | 引擎库（C++ Lua 解释器 + Java 桥接层，6 个 3rd 子模块） |
| `app\` | Android 客户端宿主（装配引擎、MLN Lua UI、悬浮球、IDE 服务） |
| `vscode-plugin\` | VSCode 开发插件（autolua-vscode） |
| `plugins\demo-ocr\` | OCR 插件示例（插件契约参考实现） |
| `tools\` | 构建/自测/联调脚本（build_app.ps1、selftest\、remote.html） |

## 文档

- **架构总览**：[../docs/01-架构总览.md](../docs/01-架构总览.md)（进程模型/模块地图/服务注入矩阵/核心数据流）
- **Lua API 手册**：[../docs/02-Lua-API规范.md](../docs/02-Lua-API规范.md)（权威，全部签名从源码核实）
- **WS 协议**：[../docs/03-WS协议规范.md](../docs/03-WS协议规范.md) · **构建自测**：[../docs/05-构建与自测指南.md](../docs/05-构建与自测指南.md) · **踩坑手册**：[../docs/06-开发铁律与踩坑手册.md](../docs/06-开发铁律与踩坑手册.md)
- 文档中心完整索引（项目根 docs\ 三层路由）：[../docs/README.md](../docs/README.md)

## License

见 [LICENSE](LICENSE)。
