# VSCode 插件一键打包 APK（贴牌）任务书

> 域：[features/cloud](../features/cloud/README.md)　状态：✅ 已完成（2026-09-20 实测，见 §5）
> 插件源码：`autolua2\vscode-plugin\`（TypeScript + esbuild）　打包工具：`D:\Project\script\lrjl\engine\pack.py`

## 1. 背景与目标

贴牌打包（brand.json + pack.py）已在 CLI 落地。目标：VSCode 插件内**一键打包**——弹窗收集品牌配置，自动组装壳工程 zip + 调 pack.py 构建，产出品牌 APK。面向插件用户（脚本作者）。

设计决策（已定）：
- 壳 zip 由插件自组（工作区文件 + 注入 package.json/brand.json），**不走 pack.py --make-zip**（其文件列表硬编码 engine 测试目录，不适配任意作者工作区）
- pack.py 需加 `--engine-repo` 参数（gradle 仓库路径可配，默认现值）——作者机器无 autolua2 源码仓库时打包不可用，属预期（配置指向有效仓库才能打）
- 弹窗流：逐项 InputBox/QuickPick 收集（记忆上次值）→ 确认汇总 → withProgress 执行 → OutputChannel 实时日志

## 2. 实现清单

### 2.1 插件侧（autolua2\vscode-plugin\）
| 项 | 内容 |
|---|---|
| package.json | ① commands 加 `autolua.packApk`（title "打包贴牌 APK"，category AutoLua，icon $(cloud-download)）；② view/title 菜单挂 autoluaDevices（2_run@6）；③ configuration 加 `autolua.packScriptPath`（默认 `D:\Project\script\lrjl\engine\pack.py`）与 `autolua.packEngineRepo`（默认 `d:\Project\script\lrjl\jy-frame-lrjl\jy-frame-engine\autolua2`） |
| src/packApk.ts（新） | 全部打包逻辑，导出 `packApk(ctx: vscode.ExtensionContext)` |
| src/extension.ts | `import` + `reg('autolua.packApk', () => packApk(context))` |

### 2.2 packApk.ts 弹窗流（用户要求的核心交互）
```
1. app_name      ShowInputBox（默认上次值，workspaceState 记忆；非空校验）
2. package_name  ShowInputBox（validateInput: ^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$，默认 com.<作者名>.<app>）
3. icon          QuickPick [使用默认图标 / 选择 png 文件…] → showOpenDialog({png})
4. secret        ShowInputBox({password:true, 留空=自动生成})；自动生成用 crypto.randomBytes(32).toString('hex')，生成后在确认步明示
5. cloud_url     ShowInputBox（可空）
6. 确认          QuickPick [✔ 开始打包 / 取消] 汇总全部配置
7. 执行          window.withProgress + 独立 OutputChannel("AutoLua 打包") 实时逐行输出
```

### 2.3 执行链（packApk.ts 内部）
```
① 壳 zip：复用 zip.ts 既有打包能力（先读 zip.ts/sync.ts 摸清 packageAndPushZip 如何列文件，同构复用其工程文件收集逻辑，排除 .git/node_modules/dist 等），zip 根注入：
   package.json = {"name":"jyframe-cloud","version":"v1.0","description":"jy-frame 云控框架"}
   brand.json   = {app_name,package_name,cloud_url,secret}
   产物： <workspace>/.autolua/dist/jyframe-cloud.zip
② brand.json 写 <workspace>/brand.json（project_zip 指向 ① 产物，icon 为用户所选绝对路径或缺省）
③ spawn python "<packScriptPath>" --brand "<brand.json>" --engine-repo "<packEngineRepo>"
   （python 可执行：shell 环境 python；cwd=workspace；stdout/stderr 逐行转 OutputChannel）
④ 成功：产物路径信息弹窗（ShowInformationMessage 带 "打开目录" 按钮 reveal dist）；
   失败：ShowErrorMessage + OutputChannel 指引
```

### 2.4 pack.py 适配（D:\Project\script\lrjl\engine\pack.py）
- 加 `--engine-repo <path>` 参数（argparse），gradle 构建的 cwd/brand_assets 生成路径改用该值；默认保持现有仓库路径（CLI 行为向后兼容）
- `--make-zip` 不动（engine 测试目录专用）

## 3. 测试计划

1. `npm run compile`（esbuild）零错误；`npx tsc --noEmit` 类型检查通过
2. **CLI 等价链路实测**（无法驱动 VSCode UI，用 node 脚本模拟弹窗产物）：
   - 模拟脚本（临时 node 文件）调 zip 打包 + 注入 → 产出壳 zip（根含 package.json/brand.json）
   - `python pack.py --brand <该brand.json> --engine-repo <autolua2仓库>` → dist/云控助手.apk 产出
   - 该 APK 装 emulator-5558 → 包名/名称正确（复用 §贴牌任务书验证法）
3. pack.py --engine-repo 传非法路径 → 中文报错
4. UI 弹窗流代码走查（编译+类型过即视为交付，实际弹窗由用户 VSCode 手测）

## 4. 验收标准

- [x] 插件编译/类型检查通过；命令出现在命令面板与设备面板菜单
- [x] 弹窗流 6 步齐全且记忆上次值；包名非法即时提示
- [x] 一键链路（CLI 等价）：工作区 → 壳 zip → brand.json → pack.py → 品牌APK 实测产出并可安装
- [x] pack.py --engine-repo 参数化且向后兼容
- [x] 任务书勾选 + 实测记录

## 5. 实测记录（2026-09-20）

| 项 | 结果 |
|---|---|
| `npm run compile` + `npx tsc --noEmit` | ✅ 全过（esbuild build done；tsc exit=0） |
| 弹窗流代码走查 | 6 步齐全（§2.2 逐条实现）：app_name 非空校验 / 包名正则即时校验（默认 com.<用户名>.<应用名>）/ 图标 QuickPick→showOpenDialog(png)（取消选文件回本步）/ secret password 留空自动 crypto.randomBytes(32).hex（确认步明示"自动生成，请抄存"）/ cloud_url 可空 / 确认 QuickPick 汇总+分隔线+✔开始打包/✖取消（点配置行重展示，Esc 中止）。记忆键 autolua.pack.{appName,packageName,icon,secret,cloudUrl}（workspaceState）。**UI 实际弹窗留待用户 VSCode 手测** |
| CLI 等价链路（模拟弹窗产物） | ✅ 临时 node 脚本（%TEMP%\packapk-test，esbuild 打包插件 src/zip.ts 真实编译产物 + 同构 collect 排除规则）产出壳 zip：15 条目 = 13 工程文件（main/cloud/Funs.lua + frameSdk-autolua2）+ 根 package.json（内容与规范逐字一致）/brand.json（cloud_url+secret），50.6 KB |
| pack.py 贴牌构建 | ✅ `python pack.py --brand <ws>/brand.json --engine-repo <autolua2> --out <ws>/dist`：日志确认"引擎仓库: d:\...\autolua2"（--engine-repo 生效）→ gradle BUILD SUCCESSFUL 1m27s → dist/云控测试助手.apk（产物行"[pack] 产物已复制: <绝对路径>（源: …）"与插件解析正则匹配，"打开目录"可定位） |
| adb 安装验证（emulator-5558） | ✅ `adb install -r` → Success；`pm list packages` → `package:com.test.vscodepack`；versionName=0.0.1 |
| pack.py --engine-repo 非法路径 | ✅ 不存在目录 → `[pack] 错误: engine-repo 目录不存在: D:\不存在的目录\xyz`；非仓库根 → `engine-repo 不是 autolua2 引擎仓库根目录（缺 gradlew.bat）`，均 exit=1 |
| 向后兼容 | ✅ 不带 --engine-repo 的 `--make-zip` 走默认仓库路径正常出 zip |
| git 范围 | ✅ 仅 vscode-plugin/package.json、src/extension.ts、src/packApk.ts（新）、dist/（编译再生）；pack.py 位于 engine 目录不在仓库；临时脚本已清理 |

实现落点：插件 `autolua2\vscode-plugin\`（package.json 命令/菜单 2_run@6/配置项 autolua.packScriptPath·autolua.packEngineRepo；src/packApk.ts 全部逻辑；extension.ts reg 注册）。壳 zip 收集在 packApk.ts 内实现 collect（基线同 sync.ts 排除规则，另排 .autolua/build，跳过工作区根 package.json/brand.json 防重名条目），zip 编解码复用 src/zip.ts（createZip）。
