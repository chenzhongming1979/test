# 插件体系（plugins）

> 状态：框架稳定；ncnn-ocr 模拟器实测通过（x86_64 转译模拟器 + arm64 产物就绪）；签名体系未完成（sign-plugin.ps1 签名工具与 PluginManager 验签代码已就绪，宿主侧公钥注入接线未落地）。
> 本域权威范围：插件运行时框架（PluginManager 加载/安装/原子交换、IPlugin 契约族、PluginLuaHelper）、三个插件实例（demo-luaapi / demo-ocr / ncnn-ocr）、签名工具（sign-plugin.ps1 + signing/dev-key.json）与 OCR Lua 面（OcrLib）。包格式、manifest 字段、sha256 算法、ABI、so 交付、生命周期、接口完整签名、WS 命令、Lua 口与三种本地安装的平台契约**权威在 [../../04-插件包规范.md](../../04-插件包规范.md)**，本域文档只做域内摘要与对码落点。

## 本域文档

| 文档 | 内容 |
|---|---|
| [contracts.md](contracts.md) | 域内契约：PluginMeta 字段对码、IPlugin 接口族签名摘要、安装原子交换协议、Lua 贡献契约、ocr.use 切换语义、同进程 so 单次加载约束 |
| [design.md](design.md) | 设计：架构总述、加载与安装机制、生命周期、OCR 插件通道、ncnn-ocr 案例、代码映射表、演进 |
| [manual.md](manual.md) | 手册：插件开发步骤、三种本地安装、签名工具用法、ncnn-ocr 构建与验证、常见问题 |

## 全局真相源交叉引用

| 文档 | 本域相关章节 |
|---|---|
| [04-插件包规范.md](../../04-插件包规范.md) | §1 包格式、§2 manifest 字段、§3 sha256 内容清单、§4 ABI 匹配、§5 so 交付模型、§6 生命周期、§7 接口契约完整签名、§8 WS 命令、§9 安全模型、§11 本地安装（三种方式）、§12 Lua 调用口（plugin/ocr 表、Lua API 贡献）、§13 未完成项 |
| [02-Lua-API规范.md](../../02-Lua-API规范.md) | §19.8 plugin 表、§19.9 ocr 表 |
| [01-架构总览.md](../../01-架构总览.md) | §7 模块地图：app / vscode-plugin / tools / plugins |
| [../luaapi/contracts.md](../luaapi/contracts.md) | 插件 Lua API 贡献注册点（`PluginManager.contributeLua` → `AutoLuaEngineOnLocal.pushLocalServices` 末尾接线） |
| `../../plans/`（待建） | 签名体系落地、插件市场 API 等进行中演进的任务书（目录尚未建立，暂以 [04-插件包规范.md](../../04-插件包规范.md) §9/§13 为准） |

## 代码入口

| 分类 | 路径 | 职责 |
|---|---|---|
| 框架 | `autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java` | 插件管理器单例：zip 校验安装、DexClassLoader 加载、so 解压、contributeLua、.tmp/.old 原子交换、loadAll/loadOne/unload、OCR 激活选择 |
| 框架 | `autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginMeta.java` | manifest.json 反序列化（id/name/version/mainClass/provides[]/minHostVersion/sha256/signature） |
| 框架 | `autolua2\engine\src\main\java\com\autolua\engine\plugin\IPlugin.java` | 插件宿主接口（onInit/onRelease/getId/getVersion/getProvides） |
| 框架 | `autolua2\engine\src\main\java\com\autolua\engine\plugin\IPluginContext.java` | 宿主 → 插件能力注入接口（截图/私有目录/日志/ABI/so 加载） |
| 框架 | `autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginContext.java` | IPluginContext 默认实现（转调 ScreenshotProviderManager，loadNativeLibrary） |
| 框架 | `autolua2\engine\src\main\java\com\autolua\engine\plugin\IOcrPlugin.java` | OCR 插件契约（recognize） |
| 框架 | `autolua2\engine\src\main\java\com\autolua\engine\plugin\OcrTextBlock.java` | OCR 识别结果文本块 POJO |
| 框架 | `autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginLuaHelper.kt` | 插件贡献 Lua API 的便捷工具（putGlobal） |
| 框架 | `autolua2\engine\src\main\java\com\autolua\engine\extension\ocr\OcrLib.kt` | Lua 全局 `ocr` 表（use/list/active/recognizeRaw/recognizeFile） |
| 插件 | `autolua2\plugins\demo-luaapi\` | Lua API 贡献活体案例（DemoLuaApiPlugin：IPlugin + LuaEnvContributor 双实现，注册全局 `plugindemo`） |
| 插件 | `autolua2\plugins\demo-ocr\` | mock OCR 插件（DemoOcrPlugin）+ MakeZip.java 打包工具；产物 zip 预置于 `autolua2\app\src\main\assets\plugins\demo-ocr.zip` |
| 插件 | `autolua2\plugins\ncnn-ocr\` | PP-OCR 真实 OCR 插件（NcnnOcrPlugin.java 壳 + cpp\ncnn_ocr.cpp ncnn det/rec）；build.ps1 一键构建；MODEL_CONVERT.md 模型转换 |
| 签名 | `autolua2\plugins\sign-plugin.ps1` | 插件包 ECDSA P-256 签名工具（生成密钥对 / 签 zip） |
| 签名 | `autolua2\plugins\signing\dev-key.json` | 开发签名密钥对（secp256r1：d/qx/qy，仅开发用，勿入发布流程） |

## 何时读本文档

- 要开发一个新插件（目录结构、manifest、打包、安装验证）→ 先读 [manual.md](manual.md)，契约细节回查 [contracts.md](contracts.md) 与 [04-插件包规范.md](../../04-插件包规范.md)；
- 要理解插件如何被加载、安装为什么用 .tmp/.old 原子交换、为什么覆盖安装后要重启才生效 → [design.md](design.md) 与 [contracts.md](contracts.md)；
- 要让插件向 Lua 注入自定义全局函数，或要排查 `ocr.use` 切换不生效 → [contracts.md](contracts.md)（Lua 贡献契约 / ocr.use 语义）与 [../luaapi/contracts.md](../luaapi/contracts.md)；
- 要构建或替换 ncnn-ocr 模型（pnnx fp16=0 踩坑）→ [manual.md](manual.md) 与 `autolua2\plugins\ncnn-ocr\MODEL_CONVERT.md`；
- 要给插件包签名（ECDSA P-256）→ [manual.md](manual.md) 签名章节；
- 写 WS 客户端对接 plugin_push / ocr 命令 → 权威在 [04-插件包规范.md](../../04-插件包规范.md) §8，本域不重复。
