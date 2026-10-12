/**
 * 一键打包贴牌 APK：弹窗收集品牌配置 → 自组壳工程 zip（根注入 package.json/brand.json）
 * → 写工作区 brand.json → spawn pack.py --brand（gradle 贴牌构建）→ 产出品牌 APK。
 * 见 docs/plans/VSCode一键打包APK任务书.md
 */

import * as vscode from 'vscode';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as crypto from 'crypto';
import * as readline from 'readline';
import { spawn } from 'child_process';
import { createZip, ZipEntry } from './zip';
import { toDeviceRel } from './workspaceLayout';

/** 壳 zip 排除规则（基线同 sync.ts；config/script 由目录规格映射排除） */
const IGNORE_DIRS = new Set(['.git', '.vscode', 'node_modules', 'dist', 'out', '.github', '.autolua', 'build']);
const IGNORE_FILES = new Set(['.DS_Store', 'desktop.ini']);
const IGNORE_EXTS = new Set(['.zip', '.apk', '.log']);
/** zip 根描述文件由本流程注入，收集时跳过（避免 zip 内重名条目） */
const INJECTED_ROOT_FILES = new Set(['package.json', 'brand.json']);

/** 框架核心目录：工作区缺失时自动从框架源（autolua.packFrameSrc）注入 */
const FRAME_DIRS = ['JY', 'frameSdk-autolua2'];

const PKG_RE = /^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$/;

/** 打包日志通道（单例，随扩展生命周期销毁） */
let channel: vscode.OutputChannel | null = null;

function getChannel(ctx: vscode.ExtensionContext): vscode.OutputChannel {
  if (!channel) {
    channel = vscode.window.createOutputChannel('AutoLua 打包');
    ctx.subscriptions.push(channel);
  }
  return channel;
}

interface BrandConfig {
  appName: string;
  packageName: string;
  iconPath: string;
  iconIsDefault: boolean;
  secret: string;
  secretAuto: boolean;
  cloudUrl: string;
  frame: FramePlan;
}

/** 框架注入计划：壳 zip 内补齐框架核心，避免只打作者脚本跑不起来 */
interface FramePlan {
  srcRoot: string | null;      // 框架源目录（配置无效时为 null，不注入）
  injected: string[];          // 实际注入的框架目录（JY / frameSdk-autolua2）
  fileCount: number;           // 注入文件总数
  hasJy: boolean;              // 工作区已含 JY（以工作区为准，不覆盖）
  hasSdk: boolean;             // 工作区已含 frameSdk-autolua2
  missingMain: boolean;        // 工作区缺 main.lua（警告：APK 内工程无入口）
}

interface PackResult { code: number; apkPath: string | null; }

/**
 * 一键打包入口：弹窗 6 步收集配置 → 确认汇总 → withProgress 执行链。
 * 任一步取消（Esc）即整体中止。
 */
export async function packApk(ctx: vscode.ExtensionContext): Promise<void> {
  const root = getWorkspaceRoot();
  const cfg = vscode.workspace.getConfiguration('autolua');
  const packScriptPath = cfg.get<string>('packScriptPath', '');
  const packEngineRepo = cfg.get<string>('packEngineRepo', '');

  // 前置校验（中文报错，避免跑到一半才失败）
  if (!packScriptPath || !fs.existsSync(packScriptPath)) {
    throw new Error(`pack.py 不存在：${packScriptPath}，请在设置中检查 autolua.packScriptPath`);
  }
  if (!packEngineRepo || !fs.existsSync(packEngineRepo)) {
    throw new Error(`引擎仓库不存在：${packEngineRepo}，请在设置中检查 autolua.packEngineRepo`);
  }
  // 默认图标：优先工作区 res/icon.png（目录规格），回落 pack.py 同目录
  const wsIcon = path.join(root, 'res', 'icon.png');
  const defaultIcon = fs.existsSync(wsIcon)
    ? wsIcon
    : path.join(path.dirname(packScriptPath), 'icon.png');
  const hasDefaultIcon = fs.existsSync(defaultIcon);

  // 框架注入计划：工作区缺 JY/frameSdk-autolua2 时从框架源补齐（先收集一次工作区文件）
  const rels = collectFiles(root);
  if (rels.length === 0) {
    throw new Error('工作区没有可打包的文件');
  }
  const frameSrc = cfg.get<string>('packFrameSrc', '');
  const frame = buildFramePlan(frameSrc, rels.map((f) => f.rel));

  const brand = await collectBrandConfig(ctx, hasDefaultIcon, defaultIcon);
  if (!brand) { return; }
  brand.frame = frame;
  if (!(await confirmSummary(brand))) { return; }

  const log = getChannel(ctx);
  log.show(true);
  log.appendLine(`[pack] ===== 打包贴牌 APK：${brand.appName}（${brand.packageName}）=====`);

  const res = await vscode.window.withProgress(
    { location: vscode.ProgressLocation.Notification, title: 'AutoLua 打包贴牌 APK', cancellable: false },
    async (progress) => {
      const report = (m: string): void => { progress.report({ message: m }); };
      return runChain(root, brand, packScriptPath, packEngineRepo, log, report);
    },
  );

  if (res.code !== 0) {
    throw new Error(`打包失败（exit=${res.code}），详见输出面板「AutoLua 打包」`);
  }
  const msg = res.apkPath
    ? `打包成功：${res.apkPath}`
    : '打包成功（产物路径见输出面板「AutoLua 打包」）';
  const act = await vscode.window.showInformationMessage(msg, '打开目录');
  if (act === '打开目录') {
    const dir = res.apkPath ? path.dirname(res.apkPath) : path.join(path.dirname(packScriptPath), 'dist');
    void vscode.env.openExternal(vscode.Uri.file(dir));
  }
}

/** 弹窗前 5 步（确认步独立），全部取到返回配置，任一取消返回 undefined */
async function collectBrandConfig(
  ctx: vscode.ExtensionContext, hasDefaultIcon: boolean, defaultIcon: string,
): Promise<BrandConfig | undefined> {
  const state = ctx.workspaceState;

  // ① 应用名（记忆上次值，非空校验）
  const appName = await vscode.window.showInputBox({
    prompt: '①/⑥ 应用名（APK 显示名，同时作为产物文件名）',
    placeHolder: '如：云控助手',
    value: state.get<string>('autolua.pack.appName', ''),
    validateInput: (v) => (v.trim() ? null : '应用名不能为空'),
  });
  if (appName === undefined) { return undefined; }
  const app = appName.trim();
  state.update('autolua.pack.appName', app);

  // ② 包名（正则即时校验，默认 com.<系统用户名>.<应用名>）
  const packageName = await vscode.window.showInputBox({
    prompt: '②/⑥ 包名（形如 com.xxx.yyy，需与设备上已装应用包名不同）',
    value: state.get<string>('autolua.pack.packageName', '') || defaultPackageName(app),
    validateInput: (v) => (PKG_RE.test(v) ? null : '包名非法：需形如 com.xxx.yyy（小写字母开头，段内字母/数字/下划线）'),
  });
  if (packageName === undefined) { return undefined; }
  state.update('autolua.pack.packageName', packageName);

  // ③ 图标（默认 or 选 png；取消选文件则回到本步重选）
  let iconPath = '';
  let iconIsDefault = false;
  for (; ;) {
    const pick = await vscode.window.showQuickPick([
      {
        label: '$(file-media) 使用默认图标',
        description: hasDefaultIcon ? defaultIcon : '（默认图标不存在，请选 png 文件）',
        act: 'default' as const,
      },
      { label: '$(go-to-file) 选择 png 文件…', act: 'file' as const },
    ], { placeHolder: '③/⑥ 应用图标' });
    if (!pick) { return undefined; }
    if (pick.act === 'default') {
      if (!hasDefaultIcon) { continue; }
      iconPath = defaultIcon;
      iconIsDefault = true;
      break;
    }
    const picked = await vscode.window.showOpenDialog({
      title: '选择应用图标（png）',
      openLabel: '选择图标',
      canSelectMany: false,
      filters: { 'png 图标': ['png'] },
    });
    if (picked && picked.length > 0) {
      iconPath = picked[0].fsPath;
      break;
    }
  }
  state.update('autolua.pack.icon', iconPath);

  // ④ secret（password 输入；留空自动生成 64 hex，确认步明示）
  const secretInput = await vscode.window.showInputBox({
    prompt: '④/⑥ 云控 PSK secret（留空自动生成；对接同一云控服务需与 ws_client --secret 一致）',
    password: true,
    value: state.get<string>('autolua.pack.secret', ''),
    placeHolder: '留空 = 自动生成 64 位 hex',
  });
  if (secretInput === undefined) { return undefined; }
  const secretAuto = !secretInput.trim();
  const secret = secretAuto ? crypto.randomBytes(32).toString('hex') : secretInput.trim();
  state.update('autolua.pack.secret', secret);

  // ⑤ 云控地址（可空）
  const cloudUrl = await vscode.window.showInputBox({
    prompt: '⑤/⑥ 云控服务器地址（可空，透传给工程内 Lua 读取）',
    placeHolder: '如 ws://192.168.1.10:9300',
    value: state.get<string>('autolua.pack.cloudUrl', ''),
  });
  if (cloudUrl === undefined) { return undefined; }
  state.update('autolua.pack.cloudUrl', cloudUrl.trim());

  return {
    appName: app, packageName, iconPath, iconIsDefault, secret, secretAuto, cloudUrl: cloudUrl.trim(),
    frame: { srcRoot: null, injected: [], fileCount: 0, hasJy: false, hasSdk: false, missingMain: false },
  };
}

/** 包名默认值：com.<系统用户名清洗>.<应用名清洗>（清洗为空段用 app/myapp 兜底） */
function defaultPackageName(appName: string): string {
  const seg = (s: string, fallback: string): string => {
    const c = s.toLowerCase().replace(/[^a-z0-9_]/g, '');
    return c || fallback;
  };
  let user = 'app';
  try { user = seg(os.userInfo().username, 'app'); } catch { /* 取不到用户名时用兜底 */ }
  return `com.${user}.${seg(appName, 'myapp')}`;
}

/** ⑥ 确认：QuickPick 汇总全部配置（点击配置行重新展示，Esc/取消中止） */
async function confirmSummary(b: BrandConfig): Promise<boolean> {
  type ConfirmItem = vscode.QuickPickItem & { act?: 'go' | 'cancel' };
  const frameDesc = b.frame.injected.length > 0
    ? `${b.frame.injected.join(' + ')}（自动注入 ${b.frame.fileCount} 文件）`
    : b.frame.srcRoot
      ? '工作区已含（不覆盖）'
      : '⚠ 框架源未配置（autolua.packFrameSrc），若工作区缺框架将无法运行';
  for (; ;) {
    const items: ConfirmItem[] = [
      { label: '应用名', description: b.appName },
      { label: '包名', description: b.packageName },
      { label: '图标', description: b.iconPath + (b.iconIsDefault ? '（默认图标）' : '') },
      { label: 'secret', description: b.secretAuto ? `${b.secret}（自动生成，请抄存）` : `${b.secret}（手动输入）` },
      { label: '云控地址', description: b.cloudUrl || '（空）' },
      { label: '框架核心', description: frameDesc },
      ...(b.frame.missingMain
        ? [{ label: '⚠ 缺 main.lua', description: '工作区没有入口脚本，APK 内工程将无法启动' } as ConfirmItem]
        : []),
      { label: '──────────', kind: vscode.QuickPickItemKind.Separator },
      { label: '$(check) ✔ 开始打包', act: 'go' },
      { label: '$(close) ✖ 取消', act: 'cancel' },
    ];
    const pick = await vscode.window.showQuickPick(items, {
      placeHolder: '⑥/⑥ 确认打包配置：核对后点「✔ 开始打包」',
    });
    if (!pick) { return false; }
    if (pick.act === 'go') { return true; }
    if (pick.act === 'cancel') { return false; }
    // 点了配置汇总行 → 重新展示
  }
}

/** 执行链：①壳 zip（工程文件 + 框架注入 + 根描述）→ ②brand.json → ③spawn pack.py */
async function runChain(
  root: string, b: BrandConfig, packScriptPath: string, packEngineRepo: string,
  log: vscode.OutputChannel, report: (m: string) => void,
): Promise<PackResult> {
  // ① 壳 zip：工作区文件 + 框架核心补齐 + 根注入 package.json/brand.json
  report('组装壳工程 zip...');
  const rels = collectFiles(root);
  if (rels.length === 0) {
    throw new Error('工作区没有可打包的文件');
  }
  const entries: ZipEntry[] = [
    {
      name: 'package.json',
      data: Buffer.from(JSON.stringify(
        { name: 'jyframe-cloud', version: 'v1.0', description: 'jy-frame 云控框架' }, null, 2), 'utf8'),
    },
    {
      name: 'brand.json',
      data: Buffer.from(JSON.stringify({ cloud_url: b.cloudUrl, secret: b.secret }, null, 2), 'utf8'),
    },
  ];
  for (const { rel, abs } of rels) {
    entries.push({ name: rel, data: fs.readFileSync(abs) });
  }
  // 框架核心注入（JY / frameSdk-autolua2，工作区已含的目录不覆盖）
  let injectedCount = 0;
  for (const dir of b.frame.injected) {
    const files = collectFrameFiles(path.join(b.frame.srcRoot!, dir));
    for (const f of files) {
      entries.push({ name: `${dir}/${f.rel}`, data: fs.readFileSync(f.abs) });
    }
    injectedCount += files.length;
    log.appendLine(`[zip] 框架注入 ${dir}：${files.length} 个文件（来自 ${b.frame.srcRoot}）`);
  }
  const zip = createZip(entries);
  const distDir = path.join(root, '.autolua', 'dist');
  fs.mkdirSync(distDir, { recursive: true });
  const zipPath = path.join(distDir, 'jyframe-cloud.zip');
  fs.writeFileSync(zipPath, zip);
  log.appendLine(`[zip] 壳工程：${zipPath}（${rels.length} 个工程文件 + ${injectedCount} 个框架注入 + 根 package.json/brand.json，${(zip.length / 1024).toFixed(1)} KB）`);

  // ② brand.json 写工作区 config/（目录规格；project_zip 用绝对路径，pack.py 按绝对直取）
  const configDir = path.join(root, 'config');
  fs.mkdirSync(configDir, { recursive: true });
  const brandPath = path.join(configDir, 'brand.json');
  fs.writeFileSync(brandPath, JSON.stringify({
    app_name: b.appName,
    package_name: b.packageName,
    icon: b.iconPath,
    project_zip: zipPath,
    cloud_url: b.cloudUrl,
    secret: b.secret,
  }, null, 2), 'utf8');
  log.appendLine(`[brand] 配置已写入 ${brandPath}`);

  // ③ spawn pack.py（stdout/stderr 逐行转 OutputChannel）
  report('调用 pack.py 贴牌构建...');
  const args = [packScriptPath, '--brand', brandPath, '--engine-repo', packEngineRepo];
  log.appendLine(`[pack] $ python ${args.map(quoteArg).join(' ')}`);
  const res = await runPython(args, root, log, report);
  log.appendLine(`[pack] pack.py 退出码 ${res.code}`);
  return res;
}

/** spawn python pack.py：逐行转发日志，捕获 pack.py 报出的产物 APK 路径 */
function runPython(
  args: string[], cwd: string, log: vscode.OutputChannel, report: (m: string) => void,
): Promise<PackResult> {
  return new Promise((resolve) => {
    let apkPath: string | null = null;
    let settled = false;
    const finish = (code: number): void => {
      if (!settled) { settled = true; resolve({ code, apkPath }); }
    };
    const child = spawn('python', args, {
      cwd,
      env: { ...process.env, PYTHONIOENCODING: 'utf-8', PYTHONUTF8: '1' },
    });
    child.on('error', (e) => {
      log.appendLine(`[pack] 启动 python 失败：${e.message}（请确认已安装 python 并加入 PATH）`);
      finish(-1);
    });
    const onLine = (line: string): void => {
      log.appendLine(line);
      const m = line.match(/产物已复制:\s*(.+?)（/);
      if (m) { apkPath = m[1].trim(); }
      if (line.includes('gradle 贴牌构建中')) { report('gradle 贴牌构建中（约 1 分钟）...'); }
    };
    for (const stream of [child.stdout, child.stderr]) {
      readline.createInterface({ input: stream }).on('line', onLine);
    }
    child.on('close', (code) => finish(code ?? -1));
  });
}

/**
 * 递归收集工作区文件并映射为设备端布局（rel = zip 内路径 '/' 分隔）。
 * frame/ 剥前缀平铺到根，config/script 排除，res/ 原样；同名时 frame/ 优先。
 */
function collectFiles(root: string): Array<{ rel: string; abs: string }> {
  const raw: string[] = [];
  const walk = (dir: string): void => {
    for (const name of fs.readdirSync(dir)) {
      const abs = path.join(dir, name);
      const st = fs.statSync(abs);
      if (st.isDirectory()) {
        if (!IGNORE_DIRS.has(name)) { walk(abs); }
      } else if (!IGNORE_FILES.has(name) && !IGNORE_EXTS.has(path.extname(name))) {
        raw.push(path.relative(root, abs).replace(/\\/g, '/'));
      }
    }
  };
  walk(root);
  const byDeviceRel = new Map<string, string>();
  for (const rel of raw) {
    const dev = toDeviceRel(rel);
    if (!dev || INJECTED_ROOT_FILES.has(dev)) { continue; }
    // frame/ 优先：后处理 frame/ 条目覆盖根级旧布局同名文件
    const isFrame = rel === 'frame' || rel.startsWith('frame/');
    if (!byDeviceRel.has(dev) || isFrame) { byDeviceRel.set(dev, path.join(root, rel)); }
  }
  return [...byDeviceRel].map(([rel, abs]) => ({ rel, abs }));
}

/** 框架注入计划：工作区缺 JY/frameSdk-autolua2 时标记从框架源补齐；工作区已有以工作区为准 */
function buildFramePlan(frameSrc: string, rels: string[]): FramePlan {
  const hasJy = rels.some((r) => r === 'JY' || r.startsWith('JY/'));
  const hasSdk = rels.some((r) => r === 'frameSdk-autolua2' || r.startsWith('frameSdk-autolua2/'));
  const plan: FramePlan = {
    srcRoot: frameSrc && fs.existsSync(frameSrc) ? frameSrc : null,
    injected: [], fileCount: 0, hasJy, hasSdk,
    missingMain: !rels.some((r) => r === 'main.lua'),
  };
  if (!plan.srcRoot) { return plan; }
  for (const dir of FRAME_DIRS) {
    if ((dir === 'JY' && hasJy) || (dir === 'frameSdk-autolua2' && hasSdk)) { continue; }
    const abs = path.join(frameSrc, dir);
    if (fs.existsSync(abs)) {
      plan.injected.push(dir);
      plan.fileCount += collectFrameFiles(abs).length;
    }
  }
  return plan;
}

/** 递归收集框架源目录下的 .lua 文件（rel 为目录内相对路径，'/' 分隔） */
function collectFrameFiles(dir: string): Array<{ rel: string; abs: string }> {
  const out: Array<{ rel: string; abs: string }> = [];
  const walk = (d: string): void => {
    for (const name of fs.readdirSync(d)) {
      const abs = path.join(d, name);
      const st = fs.statSync(abs);
      if (st.isDirectory()) { walk(abs); }
      else if (name.endsWith('.lua')) {
        out.push({ rel: path.relative(dir, abs).replace(/\\/g, '/'), abs });
      }
    }
  };
  walk(dir);
  return out;
}

/** 日志展示用：含空格/引号的参数加引号 */
function quoteArg(a: string): string {
  return /[\s"]/.test(a) ? `"${a.replace(/"/g, '\\"')}"` : a;
}

function getWorkspaceRoot(): string {
  const folders = vscode.workspace.workspaceFolders;
  if (!folders || folders.length === 0) {
    throw new Error('请先打开工程文件夹');
  }
  return folders[0].uri.fsPath;
}
