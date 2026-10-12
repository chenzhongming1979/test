/**
 * 工作区目录规格 ↔ 设备端布局映射。
 *
 * 工作区规格：
 *   frame/   Lua 工程（框架 JY/frameSdk-* + main.lua/jyframe.lua/jy_protocol.lua + 业务脚本）
 *   res/     资产（图片/插件等），随工程同步，脚本内引用 res/xxx
 *   config/  配置（brand.json 等），仅 PC 侧使用，不入设备
 *   script/  PC 工具脚本（run_test.py 等），不入设备
 *
 * 设备端布局保持引擎约定：工程根 = main.lua + JY/ + frameSdk-autolua2/ + 业务脚本 + res/。
 * 打包/同步时 frame/ 内容剥前缀平铺到工程根，config/script 排除，res/ 原样保留。
 */

import * as fs from 'fs';
import * as path from 'path';

export const DIR_FRAME = 'frame';
export const DIR_RES = 'res';
export const DIR_CONFIG = 'config';
export const DIR_SCRIPT = 'script';

/** 不同步到设备的目录（PC 侧专用） */
export const LOCAL_ONLY_DIRS = new Set([DIR_CONFIG, DIR_SCRIPT]);

/**
 * 工作区相对路径（'/' 分隔）→ 设备端相对路径。
 * frame/x → x（剥前缀）；config/、script/ 下 → null（不入设备）；其余原样直通。
 */
export function toDeviceRel(rel: string): string | null {
  const r = rel.replace(/\\/g, '/');
  if (r === DIR_FRAME || r.startsWith(DIR_FRAME + '/')) {
    const inner = r.slice(DIR_FRAME.length + 1);
    return inner || null;
  }
  if (LOCAL_ONLY_DIRS.has(r.split('/')[0])) { return null; }
  return r;
}

/**
 * 设备端相对路径 → 本地绝对路径（定位源文件）。
 * 优先 frame/<rel>（新规格），不存在回落 <root>/<rel>（兼容旧布局根级文件）。
 */
export function toLocalAbs(root: string, deviceRel: string): string {
  const inFrame = path.join(root, DIR_FRAME, deviceRel);
  if (fs.existsSync(inFrame)) { return inFrame; }
  return path.join(root, deviceRel);
}
