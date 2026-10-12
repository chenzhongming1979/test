/**
 * 初始化项目：在当前工作区创建 AutoLua 工程模板。
 * 已有 main.lua 时不覆盖（提示确认）。
 */

import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';

const MAIN_LUA = `-- AutoLua 工程入口脚本
-- 目录规格：根目录 = 常用操作文件（main.lua/Funs.lua/业务脚本）
--          frame/ = 框架底层（JY/、frameSdk-*）｜res/ = 资产（图片/插件）
--          config/ = 配置（仅 PC 侧）｜script/ = PC 工具脚本（仅 PC 侧）
-- 运行：VSCode 中 Ctrl+F5（需先连接设备）

print("hello AutoLua!")

-- 示例：循环 10 次输出
for i = 1, 10 do
    print("count: " .. i)
    sleep(1000)
end
`;

const PROJECT_JSON = `{
  "name": "MyAutoLuaProject",
  "version": "1.0.0",
  "entry": "main.lua",
  "description": "AutoLua 自动化工程"
}
`;

const README_MD = `# AutoLua 工程

## 目录规格
- 根目录   常用操作文件：入口 main.lua、Funs.lua、业务脚本（主要编辑区）
- frame/   框架底层：JY/、frameSdk-*（一般不改）
- res/     资产：图片、插件等（随工程同步，脚本内引用 res/xxx）
- config/  配置：brand.json 等（仅 PC 侧，不入设备）
- script/  PC 工具脚本：run_test.py、pack.py 等（仅 PC 侧）

## 使用
1. VSCode 侧边栏 AutoLua 面板连接设备（需设备与本机同局域网，App 内打开 IDE 服务）
2. 保存文件自动增量同步到设备（与设备工程根 1:1 映射）
3. Ctrl+F5 运行入口脚本，Shift+F5 停止；日志见输出面板 "AutoLua 日志"
4. 命令面板执行 "AutoLua: 打包工程并安装到设备" 可整包推送
`;

export async function initProject(target?: vscode.Uri): Promise<vscode.Uri> {
  let root: vscode.Uri | undefined = target;
  if (!root) {
    const folders = vscode.workspace.workspaceFolders;
    if (folders && folders.length > 0) {
      const pick = await vscode.window.showQuickPick(
        folders.map((f) => ({ label: f.name, folder: f })),
        { placeHolder: '选择要初始化为 AutoLua 工程的文件夹' },
      );
      root = pick?.folder.uri;
    } else {
      const dir = await vscode.window.showOpenDialog({
        canSelectFolders: true, canSelectFiles: false, canSelectMany: false,
        openLabel: '选择工程目录（没有则先新建）',
      });
      root = dir?.[0];
    }
  }
  if (!root) {
    throw new Error('未选择工程目录');
  }

  const rootPath = root.fsPath;
  const mainPath = path.join(rootPath, 'main.lua');
  if (fs.existsSync(mainPath) || fs.existsSync(path.join(rootPath, 'frame', 'main.lua'))) {
    const overwrite = await vscode.window.showWarningMessage(
      '当前目录已存在工程入口 main.lua，仍要写入模板文件吗？（已有文件不会被覆盖）',
      '继续',
    );
    if (overwrite !== '继续') {
      throw new Error('已取消');
    }
  }

  // 目录规格：frame/（框架底层）+ res/（资产）+ config/（配置）+ script/（PC 工具）；根 = 常用操作文件
  for (const dir of ['frame', 'res', 'config', 'script']) {
    const dirPath = path.join(rootPath, dir);
    if (!fs.existsSync(dirPath)) {
      fs.mkdirSync(dirPath, { recursive: true });
      fs.writeFileSync(path.join(dirPath, '.gitkeep'), '');
    }
  }
  writeFileIfAbsent(mainPath, MAIN_LUA);
  writeFileIfAbsent(path.join(rootPath, 'project.json'), PROJECT_JSON);
  writeFileIfAbsent(path.join(rootPath, 'README.md'), README_MD);

  const open = await vscode.window.showInformationMessage(
    `AutoLua 工程已初始化（frame/res/config/script）：${path.basename(rootPath)}`, '打开 main.lua',
  );
  if (open === '打开 main.lua') {
    const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(mainPath));
    await vscode.window.showTextDocument(doc);
  }
  return root;
}

function writeFileIfAbsent(file: string, content: string): void {
  if (!fs.existsSync(file)) {
    fs.writeFileSync(file, content, 'utf8');
  }
}
