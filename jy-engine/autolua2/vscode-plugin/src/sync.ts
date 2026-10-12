/**
 * 工程同步：工作区目录 ↔ 设备 ide_push/。
 *  - 增量同步：get_file_md5_list 拉基线 → diff → push_files 推变更 → delete_files 删多余
 *  - 全量打包：工作区打 zip（zip.ts）→ push_zip 三段分块推送 → 设备端解压直装
 */

import * as fs from 'fs';
import * as path from 'path';
import * as crypto from 'crypto';
import { Cmd, Msg } from './protocol';
import { DeviceManager } from './deviceManager';
import { createZip, ZipEntry } from './zip';
import { toDeviceRel, toLocalAbs } from './workspaceLayout';

/** 忽略目录/文件（相对路径前缀或文件名匹配；config/script 由目录规格映射排除） */
const IGNORE_DIRS = new Set(['.git', '.vscode', 'node_modules', 'dist', 'out', '.github', 'build', '.autolua']);
const IGNORE_FILES = new Set(['.DS_Store', 'desktop.ini']);
const IGNORE_EXTS = new Set(['.zip', '.apk', '.log']);
/**
 * 设备端运行时生成物：本地工作区没有对应文件时不参与"删除多余文件"
 * （云控 boot 以 root 引擎进程写入，IDE 通道 app 进程权限删不动 → Permission denied）
 */
const DEVICE_GENERATED = new Set(['main.config']);
/** 设备端生成物前缀（按文件名匹配）：脚本运行期动态生成的文件，如 UISet配置1.txt */
const DEVICE_GENERATED_PREFIXES = ['UISet'];

function isDeviceGenerated(rel: string): boolean {
  if (DEVICE_GENERATED.has(rel)) { return true; }
  const base = path.basename(rel);
  return DEVICE_GENERATED_PREFIXES.some((pre) => base.startsWith(pre));
}

export interface SyncResult {
  pushed: string[];
  deleted: string[];
  failed: string[];
}

/** 同步文件项：rel = 设备端相对路径（映射后），abs = 本地源文件绝对路径 */
export interface SyncFile { rel: string; abs: string; }

/**
 * 递归收集工作区文件并映射为设备端布局。
 * frame/ 剥前缀平铺到根，config/script 排除，res/ 原样；同名时 frame/ 优先。
 */
export function collectFiles(root: string): SyncFile[] {
  const raw: Array<{ rel: string; abs: string }> = [];
  const walk = (dir: string): void => {
    for (const name of fs.readdirSync(dir)) {
      const abs = path.join(dir, name);
      const st = fs.statSync(abs);
      if (st.isDirectory()) {
        if (!IGNORE_DIRS.has(name)) { walk(abs); }
      } else if (!IGNORE_FILES.has(name) && !IGNORE_EXTS.has(path.extname(name))) {
        raw.push({ rel: path.relative(root, abs).replace(/\\/g, '/'), abs });
      }
    }
  };
  walk(root);
  const byDeviceRel = new Map<string, string>();
  for (const { rel, abs } of raw) {
    const dev = toDeviceRel(rel);
    if (!dev) { continue; }
    // frame/ 优先：后处理 frame/ 条目覆盖根级旧布局同名文件
    const isFrame = rel === 'frame' || rel.startsWith('frame/');
    if (!byDeviceRel.has(dev) || isFrame) { byDeviceRel.set(dev, abs); }
  }
  return [...byDeviceRel].map(([rel, abs]) => ({ rel, abs }));
}

function md5File(abs: string): string {
  return crypto.createHash('md5').update(fs.readFileSync(abs)).digest('hex');
}

/** 文本类文件按 utf8 推送（省流量），其余 base64 */
function isTextFile(ext: string): boolean {
  return ['.lua', '.json', '.txt', '.md', '.xml', '.html', '.js', '.css', '.csv', '.ini', '.cfg'].includes(ext);
}

/**
 * 增量同步：与设备端 ide_push 基线对比，只推送差异文件、删除多余文件。
 * onProgress 回调用于日志展示。
 */
export async function incrementalSync(
  device: DeviceManager, workspaceRoot: string,
  onProgress?: (line: string) => void,
): Promise<SyncResult> {
  const log = onProgress ?? (() => { });
  const remote = (await device.request(Cmd.GET_FILE_MD5_LIST, {}, Msg.RESULT)) as Record<string, string>;

  const rels = collectFiles(workspaceRoot);
  const toPush: string[] = [];
  for (const { rel, abs } of rels) {
    const localMd5 = md5File(abs);
    if (remote[rel] !== localMd5) {
      toPush.push(rel);
    }
  }
  const localSet = new Set(rels.map((f) => f.rel));
  // 运行时生成物保护：设备端有、本地无 → 不删（root 属主文件 IDE 通道删不动；
  // 且 UISet* 是用户配置数据，本地无≠可删）
  const toDelete = Object.keys(remote).filter((p) => !localSet.has(p) && !isDeviceGenerated(p));

  const failed: string[] = [];
  let pushed: string[] = [];

  if (toPush.length > 0) {
    log(`推送 ${toPush.length} 个变更文件...`);
    // push_files_start/push_file 是静默命令（设备端不回包，仅 end 回 push_result），
    // 必须传 null 走 fire-and-forget，否则等待回包必然 15s 超时
    await device.request(Cmd.PUSH_FILES_START, {}, null);
    for (const rel of toPush) {
      const abs = rels.find((f) => f.rel === rel)!.abs;
      const ext = path.extname(rel).toLowerCase();
      const encoding = isTextFile(ext) ? 'utf8' : 'base64';
      const content = encoding === 'utf8'
        ? fs.readFileSync(abs, 'utf8')
        : fs.readFileSync(abs).toString('base64');
      try {
        await device.request(Cmd.PUSH_FILE, { path: rel, content, encoding }, null);
        pushed.push(rel);
      } catch (e) {
        failed.push(rel);
        log(`推送失败 ${rel}: ${(e as Error).message}`);
      }
    }
    const ret = await device.request(Cmd.PUSH_FILES_END, {}, Msg.PUSH_RESULT) as { written?: number; failed?: string[] };
    if (ret?.failed?.length) {
      failed.push(...ret.failed);
      log(`设备端落盘失败: ${ret.failed.join(', ')}`);
    }
    log(`设备端已写入 ${ret?.written ?? 0} 个文件`);
  }

  if (toDelete.length > 0) {
    log(`删除设备端多余文件 ${toDelete.length} 个: ${toDelete.join(', ')}`);
    const ret = await device.request(Cmd.DELETE_FILES, { paths: toDelete }, Msg.DELETE_RESULT) as { deleted?: number };
    log(`已删除 ${ret?.deleted ?? toDelete.length} 个`);
  }

  if (toPush.length === 0 && toDelete.length === 0) {
    log('工程已与设备一致，无变更。');
  }
  return { pushed, deleted: toDelete, failed };
}

/**
 * 校验指定文件已同步到设备端（md5 与本地一致）。
 * 运行入口脚本前调用，避免"推失败还去跑"的静默错误。
 */
export async function verifyFileOnDevice(
  device: DeviceManager, workspaceRoot: string, relPath: string,
): Promise<{ ok: boolean; deviceHas: boolean; md5Match: boolean }> {
  // relPath 为设备端视角相对路径；本地源文件按目录规格定位（frame/ 优先）
  const localAbs = toLocalAbs(workspaceRoot, relPath);
  if (!fs.existsSync(localAbs)) {
    return { ok: false, deviceHas: false, md5Match: false };
  }
  const remote = (await device.request(Cmd.GET_FILE_MD5_LIST, {}, Msg.RESULT)) as Record<string, string>;
  const deviceHas = Object.prototype.hasOwnProperty.call(remote, relPath);
  const localMd5 = md5File(localAbs);
  const md5Match = deviceHas && remote[relPath] === localMd5;
  return { ok: deviceHas && md5Match, deviceHas, md5Match };
}

/**
 * 打包工程 zip 并分块推送到设备（push_zip 三段），设备端校验 MD5 后解压到 ide_push/。
 * 返回本地 zip 路径（留存产物）。
 */
export async function packageAndPushZip(
  device: DeviceManager, workspaceRoot: string, zipName: string,
  onProgress?: (line: string) => void,
): Promise<string> {
  const log = onProgress ?? (() => { });

  const rels = collectFiles(workspaceRoot);
  if (rels.length === 0) {
    throw new Error('工作区没有可打包的文件');
  }
  const entries: ZipEntry[] = rels.map(({ rel, abs }) => ({
    name: rel,
    data: fs.readFileSync(abs),
  }));
  const zip = createZip(entries);

  // 产物留存到工作区外缓存目录
  const outDir = path.join(workspaceRoot, 'build');
  if (!fs.existsSync(outDir)) { fs.mkdirSync(outDir, { recursive: true }); }
  const zipPath = path.join(outDir, `${zipName}.zip`);
  fs.writeFileSync(zipPath, zip);
  log(`已打包 ${entries.length} 个文件 → ${zipPath}（${(zip.length / 1024).toFixed(1)} KB）`);

  // 分块推送（256KB/块，base64）；start/chunk 静默不回包，complete 回 push_result
  const CHUNK = 256 * 1024;
  const totalChunks = Math.ceil(zip.length / CHUNK);
  const md5 = crypto.createHash('md5').update(zip).digest('hex');
  log(`推送到设备 ${device.target}（${totalChunks} 块）...`);

  await device.request(Cmd.PUSH_ZIP_START, { name: zipName, totalChunks, totalSize: zip.length }, null);
  for (let i = 0; i < totalChunks; i++) {
    const chunk = zip.subarray(i * CHUNK, Math.min((i + 1) * CHUNK, zip.length));
    await device.request(Cmd.PUSH_ZIP_CHUNK, {
      name: zipName, index: i + 1, data: chunk.toString('base64'),
    }, null);
  }
  const ret = await device.request(
    Cmd.PUSH_ZIP_COMPLETE, { name: zipName, expectedMd5: md5 }, Msg.PUSH_RESULT, 60_000,
  ) as { unzipped?: number; failed?: string[] };
  if (ret?.failed?.length) {
    throw new Error(`解压部分失败: ${ret.failed.join(', ')}`);
  }
  log(`设备端解压完成，共 ${ret?.unzipped ?? 0} 个文件。`);
  return zipPath;
}
