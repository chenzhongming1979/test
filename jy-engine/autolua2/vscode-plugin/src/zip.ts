/**
 * 最小 zip 打包器（deflate，Node 内置 zlib，零第三方依赖）。
 * 产物兼容 java.util.zip.ZipInputStream（设备端 PushZipHandler 解压）。
 * 中文文件名置 UTF-8 标志位（general purpose bit 11）。
 */

import * as zlib from 'zlib';

/** CRC32 查表 */
const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(buf: Buffer): number {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  }
  return (c ^ 0xffffffff) >>> 0;
}

/** DOS 时间（本地时间，2 秒精度） */
function dosDateTime(d: Date): { time: number; date: number } {
  const time = ((d.getHours() & 0x1f) << 11) | ((d.getMinutes() & 0x3f) << 5) | ((d.getSeconds() / 2) & 0x1f);
  const date = (((d.getFullYear() - 1980) & 0x7f) << 9) | (((d.getMonth() + 1) & 0x0f) << 5) | (d.getDate() & 0x1f);
  return { time, date };
}

export interface ZipEntry {
  /** zip 内路径，'/' 分隔 */
  name: string;
  data: Buffer;
}

/** 打包为 zip（deflate 压缩，目录条目可选） */
export function createZip(entries: ZipEntry[]): Buffer {
  const now = dosDateTime(new Date());
  const localParts: Uint8Array[] = [];
  const centralParts: Uint8Array[] = [];
  let offset = 0;

  for (const entry of entries) {
    const nameBytes = Buffer.from(entry.name, 'utf8');
    const crc = crc32(entry.data);
    let method = 8; // deflate
    let compressed: Uint8Array = zlib.deflateRawSync(entry.data, { level: 6 });
    if (compressed.length >= entry.data.length) {
      // 小文件压缩可能反而变大，退回 store
      method = 0;
      compressed = entry.data;
    }

    // local file header
    const lh = Buffer.alloc(30);
    lh.writeUInt32LE(0x04034b50, 0);
    lh.writeUInt16LE(20, 4); // version needed
    lh.writeUInt16LE(0x0800, 6); // UTF-8 名字标志
    lh.writeUInt16LE(method, 8);
    lh.writeUInt16LE(now.time, 10);
    lh.writeUInt16LE(now.date, 12);
    lh.writeUInt32LE(crc, 14);
    lh.writeUInt32LE(compressed.length, 18);
    lh.writeUInt32LE(entry.data.length, 22);
    lh.writeUInt16LE(nameBytes.length, 26);
    lh.writeUInt16LE(0, 28); // extra len
    localParts.push(lh, nameBytes, compressed);

    // central directory header
    const ch = Buffer.alloc(46);
    ch.writeUInt32LE(0x02014b50, 0);
    ch.writeUInt16LE(20, 4); // version made by
    ch.writeUInt16LE(20, 6); // version needed
    ch.writeUInt16LE(0x0800, 8);
    ch.writeUInt16LE(method, 10);
    ch.writeUInt16LE(now.time, 12);
    ch.writeUInt16LE(now.date, 14);
    ch.writeUInt32LE(crc, 16);
    ch.writeUInt32LE(compressed.length, 20);
    ch.writeUInt32LE(entry.data.length, 24);
    ch.writeUInt16LE(nameBytes.length, 28);
    ch.writeUInt16LE(0, 30); // extra
    ch.writeUInt16LE(0, 32); // comment
    ch.writeUInt16LE(0, 34); // disk number
    ch.writeUInt16LE(0, 36); // internal attrs
    ch.writeUInt32LE(0, 38); // external attrs
    ch.writeUInt32LE(offset, 42); // local header offset
    centralParts.push(ch, nameBytes);

    offset += lh.length + nameBytes.length + compressed.length;
  }

  const centralBuf = Buffer.concat(centralParts);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4);
  eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(centralBuf.length, 12);
  eocd.writeUInt32LE(offset, 16);
  eocd.writeUInt16LE(0, 20);

  return Buffer.concat([...localParts, centralBuf, eocd]);
}
