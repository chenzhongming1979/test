package com.autolua.engine.ide;

import java.io.File;
import java.io.IOException;

/**
 * ide 包内路径工具：推送文件相对路径解析 + {@code ../} 越界防护。
 */
final class IdePaths {

    private IdePaths() {
    }

    /**
     * 把相对路径解析到 root 目录内，拒绝越出根目录的路径。
     *
     * @param root    根目录（ide_push/）
     * @param relPath 相对路径（容错统一 / 与 \ 分隔符，剥离开头多余 /）
     * @return root 内的目标文件
     * @throws IOException 路径为空或规范化后越出 root
     */
    static File safeResolve(File root, String relPath) throws IOException {
        if (relPath == null || relPath.isEmpty()) {
            throw new IOException("empty path");
        }
        String rel = relPath.replace('\\', '/');
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        if (rel.isEmpty()) {
            throw new IOException("empty path");
        }
        File target = new File(root, rel);
        String canon = target.getCanonicalPath();
        String rootCanon = root.getCanonicalPath();
        if (!canon.equals(rootCanon) && !canon.startsWith(rootCanon + File.separator)) {
            throw new IOException("illegal path outside " + WsProtocol.IDE_PUSH_DIR + ": " + relPath);
        }
        return target;
    }

    /** 以 base 为根计算相对路径，统一使用 '/' 分隔（返回给 IDE 端展示） */
    static String relPath(File base, File file) {
        String p = file.getAbsolutePath().substring(base.getAbsolutePath().length());
        if (p.startsWith(File.separator)) {
            p = p.substring(1);
        }
        return p.replace('\\', '/');
    }
}
