# det ONNX 对照诊断：复现 ncnn_ocr.cpp 的 det 前处理 → 推理 → 概率图统计 → DB 后处理
# 用法：venv python diag_det_onnx.py [变体]
# 目的：二分"模型/参数问题" vs "cpp 实现问题"——Python 复现 cpp 同款逻辑，
#       若 Python 也 0 框则再跑变体（通道序/归一化）定位参数
import sys, os
import numpy as np
from PIL import Image
import onnxruntime as ort

HERE = os.path.dirname(os.path.abspath(__file__))
ONNX = os.path.join(HERE, "..", "plugins", "ncnn-ocr", "third_party", "_downloads", "det.onnx")
PNG = os.path.join(HERE, "selftest", "diag_ocr.png")

MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)  # ImageNet RGB 顺序统计量
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)
DB_THRESH = 0.3
DB_BOX_THRESH = 0.5

so = ort.SessionOptions()
sess = ort.InferenceSession(ONNX, so, providers=["CPUExecutionProvider"])
print("inputs :", [(i.name, i.shape, i.type) for i in sess.get_inputs()])
print("outputs:", [(o.name, o.shape, o.type) for o in sess.get_outputs()])

img = Image.open(PNG).convert("RGB")  # PIL 读为 RGB
w, h = img.size
# cpp detTargetSize: 480x200 -> 448x192
dw = max(w // 32 * 32, 32)
dh = max(h // 32 * 32, 32)
print("src %dx%d -> det input %dx%d" % (w, h, dw, dh))

arr = np.array(img.resize((dw, dh), Image.BILINEAR), dtype=np.float32)  # HxWx3 RGB


def run(tag, chw):
    out = sess.run(None, {sess.get_inputs()[0].name: chw[None]})[0]
    p = out[0, 0] if out.ndim == 4 else out.reshape(out.shape[-2], out.shape[-1])
    n_above = int((p > DB_THRESH).sum())
    print("[%s] prob min=%.4f max=%.4f mean=%.4f  >%.2f px=%d/%d" %
          (tag, p.min(), p.max(), p.mean(), DB_THRESH, n_above, p.size))
    if n_above == 0:
        return 0
    # 简易连通域计数（4 邻域，BFS）
    binm = (p > DB_THRESH).astype(np.uint8)
    visited = np.zeros_like(binm)
    boxes = 0
    H, W = binm.shape
    for yy in range(H):
        for xx in range(W):
            if binm[yy, xx] and not visited[yy, xx]:
                stack = [(yy, xx)]
                visited[yy, xx] = 1
                cnt, psum, y0, y1, x0, x1 = 0, 0.0, yy, yy, xx, xx
                while stack:
                    cy, cx = stack.pop()
                    cnt += 1
                    psum += p[cy, cx]
                    y0, y1 = min(y0, cy), max(y1, cy)
                    x0, x1 = min(x0, cx), max(x1, cx)
                    for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                        ny, nx = cy + dy, cx + dx
                        if 0 <= ny < H and 0 <= nx < W and binm[ny, nx] and not visited[ny, nx]:
                            visited[ny, nx] = 1
                            stack.append((ny, nx))
                score = psum / cnt
                if cnt >= 3 and score >= DB_BOX_THRESH:
                    boxes += 1
                    print("   box (%d,%d,%d,%d) score=%.3f" % (x0, y0, x1 - x0 + 1, y1 - y0 + 1, score))
    return boxes


# 变体1（cpp 同款）：BGR 通道序 + ImageNet RGB 统计量
bgr = arr[:, :, ::-1].copy()
x = (bgr / 255.0 - MEAN) / STD
chw = x.transpose(2, 0, 1).copy()
n = run("cpp-style BGR+ImageNetRGB", chw)

if n == 0:
    # 变体2：RGB 通道序 + ImageNet RGB 统计量
    x = (arr / 255.0 - MEAN) / STD
    run("RGB+ImageNetRGB", x.transpose(2, 0, 1).copy())
    # 变体3：BGR + 仅 /255（部分 RapidOCR det 用 mean=std=0）
    x = (bgr / 255.0 - 0.5) / 0.5
    run("BGR+(x/255-0.5)/0.5", x.transpose(2, 0, 1).copy())
    # 变体4：RGB + (x/255-0.5)/0.5
    x = (arr / 255.0 - 0.5) / 0.5
    run("RGB+(x/255-0.5)/0.5", x.transpose(2, 0, 1).copy())
