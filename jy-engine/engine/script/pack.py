# -*- coding: utf-8 -*-
"""
云控贴牌打包流水线（brand.json → 品牌 APK）

用法:
  python script/pack.py --make-zip [--zip-out dist/jyframe-cloud.zip] [--cloud-url URL]
      把云控壳工程（main/cloud/Funs.lua + frameSdk-autolua2/ + JY/）打成引擎工程 zip，
      zip 根自动注入 package.json 与 brand.json（TaskManager 自动导入的必需描述文件，
      缺 package.json 会报 invalid project）。

  python script/pack.py --brand brand.example.json [--out dist] [--cloud-url URL] [--engine-repo PATH]
      按 brand.json 一键出品牌 APK:
        校验 brand.json → 生成 brand_assets（工程 zip 内置 APK assets）
        → 图标替换 mipmap-hdpi/logo.jpg（自动备份/还原）→ gradle 贴牌构建
        （-PBRAND_APPID -PBRAND_NAME 注入包名/应用名）→ 产物复制 dist/<app_name>.apk
        → 还原 logo.jpg / 清理 brand_assets。

brand.json 字段:
  {
    "app_name": "云控助手",              # APK 应用名（同时作为输出文件名）
    "package_name": "com.test.cloudassist",  # 应用包名
    "icon": "./icon.png",               # 应用图标（png，转存为 mipmap-hdpi/logo.jpg）
    "project_zip": "dist/jyframe-cloud.zip",  # 内置云控壳工程 zip（--make-zip 产物）
    "cloud_url": "ws://192.168.1.10:9300",    # 可选，透传进工程 zip 根 brand.json 供 Lua 读取
    "secret": "<64位hex>"               # 可选，云控 PSK（云控 WS 消息加密；--make-zip 未提供则自动生成）
  }
"""
import argparse
import io
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import zipfile

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.dirname(SCRIPT_DIR)  # 工作区根
FRAME = os.path.join(BASE, "frame")      # Lua 工程（main.lua/JY/frameSdk-autolua2）
# 引擎仓库（联动仓库）：工作区并入 jy-frame-engine 后取同级 autolua2，随工程整体移动；
# 同级不存在（旧布局）回落原绝对路径。--engine-repo 仍可覆盖
_AUTOLUA2_SIBLING = os.path.join(os.path.dirname(BASE), "autolua2")
AUTOLUA2 = _AUTOLUA2_SIBLING if os.path.isdir(_AUTOLUA2_SIBLING) else \
    r"d:\Project\script\lrjl\jy-frame-lrjl\jy-frame-engine\autolua2"
# JY 核心：优先工作区 frame/JY，缺省回落 jy-frame-firefly 框架源
_JY_WORKSPACE = os.path.join(FRAME, "JY")
JY_SRC = _JY_WORKSPACE if os.path.isdir(_JY_WORKSPACE) else \
    r"d:\Project\script\lrjl\jy-frame-lrjl\jy-frame-firefly\脚本\JY"
LOGO_PATH = os.path.join(AUTOLUA2, "app", "src", "main", "res", "mipmap-hdpi", "logo.jpg")
BRAND_ASSETS_ROOT = os.path.join(AUTOLUA2, "brand_assets")
BRAND_ASSETS_BRAND = os.path.join(BRAND_ASSETS_ROOT, "brand")
APK_OUT_DIR = os.path.join(AUTOLUA2, "app", "build", "outputs", "apk", "debug")

PKG_RE = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$")
BAD_FILENAME_CHARS = set('\\/:*?"<>|')
DEFAULT_PACKAGE_JSON = {"name": "jyframe-cloud", "version": "v1.0",
                        "description": "jy-frame 云控框架"}

# 引擎仓库（gradle 构建 cwd / brand_assets / APK 产物目录基准），--engine-repo 可覆盖
ENGINE_REPO = AUTOLUA2


def set_engine_repo(repo):
    """设置引擎仓库路径并重算派生路径（VSCode 插件指定仓库时用；非法路径中文报错）。"""
    global ENGINE_REPO, LOGO_PATH, BRAND_ASSETS_ROOT, BRAND_ASSETS_BRAND, APK_OUT_DIR
    repo_abs = os.path.abspath(repo)
    if not os.path.isdir(repo_abs):
        die("engine-repo 目录不存在: %s" % repo)
    if not os.path.isfile(os.path.join(repo_abs, "gradlew.bat")):
        die("engine-repo 不是 autolua2 引擎仓库根目录（缺 gradlew.bat）: %s" % repo_abs)
    ENGINE_REPO = repo_abs
    LOGO_PATH = os.path.join(repo_abs, "app", "src", "main", "res", "mipmap-hdpi", "logo.jpg")
    BRAND_ASSETS_ROOT = os.path.join(repo_abs, "brand_assets")
    BRAND_ASSETS_BRAND = os.path.join(BRAND_ASSETS_ROOT, "brand")
    APK_OUT_DIR = os.path.join(repo_abs, "app", "build", "outputs", "apk", "debug")
    log("引擎仓库: %s" % repo_abs)


def log(msg):
    print("[pack] %s" % msg, flush=True)


def die(msg):
    print("[pack] 错误: %s" % msg, file=sys.stderr, flush=True)
    sys.exit(1)

# ---------------------------------------------------------------- 工程 zip 打包
def collect_shell_files():
    """收集云控壳工程源文件（→ zip 根）: main/jyframe/jy_protocol/Funs.lua（根优先，回落 frame/）+ frameSdk-autolua2/*.lua + JY/**.lua"""
    files = []
    for name in ("main.lua", "jyframe.lua", "jy_protocol.lua", "Funs.lua", "JUI.lua"):
        p = os.path.join(BASE, name)
        if not os.path.isfile(p):
            p = os.path.join(FRAME, name)
        if not os.path.isfile(p):
            die("壳工程缺少 %s（应在 %s 或 %s）" % (name, BASE, FRAME))
        files.append((name, p))
    sdk = os.path.join(FRAME, "frameSdk-autolua2")
    if not os.path.isdir(sdk):
        die("缺少 frameSdk-autolua2 目录: %s" % sdk)
    for n in sorted(os.listdir(sdk)):
        if n.endswith(".lua"):
            files.append(("frameSdk-autolua2/" + n, os.path.join(sdk, n)))
    if not os.path.isdir(JY_SRC):
        die("缺少 JY 核心源目录: %s" % JY_SRC)
    count = 0
    for dirpath, _, names in os.walk(JY_SRC):
        for n in sorted(names):
            if n.endswith(".lua"):
                ap = os.path.join(dirpath, n)
                rel = os.path.relpath(ap, JY_SRC).replace("\\", "/")
                files.append(("JY/" + rel, ap))
                count += 1
    log("JY 核心: %s（%d 个文件）" % (JY_SRC, count))
    return files


def make_zip(zip_out, cloud_url=None, secret=None):
    files = collect_shell_files()
    os.makedirs(os.path.dirname(os.path.abspath(zip_out)), exist_ok=True)
    # 云控 PSK（brand.json secret）：显式指定 > brand 文件透传；zip 根 brand.json 随工程落
    # 设备 DIR/，cloud.lua 启动时读取并启用 AES-256-CBC 全帧加密（云控消息加密任务书 §2）
    brand_out = {"cloud_url": cloud_url or ""}
    if secret:
        brand_out["secret"] = secret
    with zipfile.ZipFile(zip_out, "w", zipfile.ZIP_DEFLATED) as z:
        # zip 根注入 package.json（TaskManager 导入必需）与 brand.json（Lua 读 cloud_url/secret）
        z.writestr("package.json",
                   json.dumps(DEFAULT_PACKAGE_JSON, ensure_ascii=False, indent=2))
        z.writestr("brand.json",
                   json.dumps(brand_out, ensure_ascii=False, indent=2))
        for arc, ap in files:
            z.write(ap, arc)
    log("工程 zip 完成: %s（%d 个文件，含根 package.json/brand.json）"
        % (zip_out, len(files) + 2))
    if secret:
        log("云控 PSK（secret）已写入 zip 根 brand.json，配 ws_client 使用: %s" % secret)


def read_brand_light(brand_path):
    """轻量读取 brand.json（--make-zip 用：只取 cloud_url/secret，不做图标/zip 校验）。"""
    if not os.path.isfile(brand_path):
        die("brand.json 不存在: %s" % brand_path)
    try:
        with open(brand_path, "r", encoding="utf-8-sig") as f:
            brand = json.load(f)
    except Exception as e:
        die("brand.json 不是合法 JSON: %s" % e)
    if not isinstance(brand, dict):
        die("brand.json 根必须是 JSON 对象")
    return brand


# ---------------------------------------------------------------- brand 校验
def load_brand(brand_path):
    if not os.path.isfile(brand_path):
        die("brand.json 不存在: %s" % brand_path)
    try:
        with open(brand_path, "r", encoding="utf-8-sig") as f:
            brand = json.load(f)
    except Exception as e:
        die("brand.json 不是合法 JSON: %s" % e)
    if not isinstance(brand, dict):
        die("brand.json 根必须是 JSON 对象")

    app_name = brand.get("app_name")
    if not isinstance(app_name, str) or not app_name.strip():
        die("app_name 缺失或为空（应用名，如「云控助手」）")
    app_name = app_name.strip()
    if set(app_name) & BAD_FILENAME_CHARS or "'" in app_name or '"' in app_name:
        die("app_name 含非法字符（禁用 \\ / : * ? \" < > | 与引号）: %s" % app_name)

    pkg = brand.get("package_name")
    if not isinstance(pkg, str) or not PKG_RE.match(pkg):
        die("package_name 非法（需形如 com.xxx.yyy，小写字母/数字/下划线段）: %s" % pkg)

    icon = brand.get("icon")
    if not icon:
        die("icon 缺失（png 图标路径）")
    project_zip = brand.get("project_zip")
    if not project_zip:
        die("project_zip 缺失（云控壳工程 zip，先执行 python pack.py --make-zip 生成）")

    base_dir = os.path.dirname(os.path.abspath(brand_path))
    icon = icon if os.path.isabs(icon) else os.path.normpath(os.path.join(base_dir, icon))
    project_zip = (project_zip if os.path.isabs(project_zip)
                   else os.path.normpath(os.path.join(base_dir, project_zip)))
    if not os.path.isfile(icon):
        die("图标文件不存在: %s" % icon)
    if not os.path.isfile(project_zip):
        die("工程 zip 不存在: %s（先执行 python pack.py --make-zip）" % project_zip)

    brand["app_name"], brand["package_name"] = app_name, pkg
    brand["_icon_path"], brand["_zip_path"] = icon, project_zip
    return brand


def normalize_zip(zip_path, cloud_url=None):
    """确保 zip 根有 package.json（缺则自动注入默认值）与 brand.json（cloud_url 透传）。
    返回 (最终 zip 字节, 工程名)。"""
    with zipfile.ZipFile(zip_path, "r") as z:
        infos = [(i.filename, z.read(i.filename))
                 for i in z.infolist() if not i.is_dir()]
    names = {n for n, _ in infos}
    out = []
    if "package.json" not in names:
        log("工程 zip 缺根 package.json，自动注入默认值（jyframe-cloud/v1.0）")
        out.append(("package.json",
                    json.dumps(DEFAULT_PACKAGE_JSON, ensure_ascii=False, indent=2).encode("utf-8")))
    orig_brand = {}
    for name, data in infos:
        if name == "brand.json":
            try:
                orig_brand = json.loads(data.decode("utf-8"))
            except Exception:
                orig_brand = {}
            continue  # brand.json 原地重建（cloud_url 透传）
        out.append((name, data))
    brand_out = dict(orig_brand) if isinstance(orig_brand, dict) else {}
    if cloud_url is not None:
        brand_out["cloud_url"] = cloud_url
    brand_out.setdefault("cloud_url", "")
    out.append(("brand.json",
                json.dumps(brand_out, ensure_ascii=False, indent=2).encode("utf-8")))

    project_name = DEFAULT_PACKAGE_JSON["name"]
    for name, data in out:
        if name == "package.json":
            try:
                project_name = json.loads(data.decode("utf-8")).get("name") or project_name
            except Exception:
                pass

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in out:
            z.writestr(name, data)
    return buf.getvalue(), project_name


# ---------------------------------------------------------------- 图标替换/还原
def replace_icon(icon_path):
    # 备份放系统临时目录（res 目录内任何非 xml/png 文件都会被资源合并器拒绝）
    logo_bak = os.path.join(tempfile.gettempdir(), "jyf_pack_logo_backup.jpg")
    if not os.path.exists(logo_bak):
        shutil.copy2(LOGO_PATH, logo_bak)
    try:
        from PIL import Image
        with Image.open(icon_path) as img:
            img.convert("RGB").save(LOGO_PATH, "JPEG", quality=95)
        log("图标已替换（PIL → mipmap-hdpi/logo.jpg）: %s" % icon_path)
    except ImportError:
        with open(icon_path, "rb") as fi, open(LOGO_PATH, "wb") as fo:
            fo.write(fi.read())
        log("图标已替换（原始字节直写，PIL 不可用）: %s" % icon_path)
    return logo_bak


def restore_icon(logo_bak):
    if logo_bak and os.path.exists(logo_bak):
        shutil.move(logo_bak, LOGO_PATH)
        log("logo.jpg 已还原")


# ---------------------------------------------------------------- brand_assets/构建
def gen_brand_assets(zip_bytes, project_name, brand):
    if os.path.isdir(BRAND_ASSETS_ROOT):
        shutil.rmtree(BRAND_ASSETS_ROOT)
    os.makedirs(BRAND_ASSETS_BRAND, exist_ok=True)
    with open(os.path.join(BRAND_ASSETS_BRAND, "%s.zip" % project_name), "wb") as f:
        f.write(zip_bytes)
    with open(os.path.join(BRAND_ASSETS_BRAND, "brand.json"), "w", encoding="utf-8") as f:
        json.dump({"app_name": brand["app_name"],
                   "package_name": brand["package_name"],
                   "cloud_url": brand.get("cloud_url", "")},
                  f, ensure_ascii=False, indent=2)
    log("brand_assets 已生成: brand_assets/brand/{%s.zip, brand.json}" % project_name)


def gradle_build(app_id, app_name):
    cmd = ["cmd.exe", "/c", "gradlew.bat", "assembleDebug",
           "-PBRAND_APPID=%s" % app_id, "-PBRAND_NAME=%s" % app_name]
    log("gradle 贴牌构建中（耗时较长，请耐心等待）: assembleDebug %s %s"
        % (cmd[-2], cmd[-1]))
    r = subprocess.run(cmd, cwd=ENGINE_REPO, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    out = (r.stdout or "") + "\n" + (r.stderr or "")
    tail = "\n".join(out.strip().splitlines()[-15:])
    if r.returncode != 0:
        print(tail, file=sys.stderr)
        die("gradle 构建失败（exit=%d），日志见上方" % r.returncode)
    log("gradle 构建成功，末尾输出:\n%s" % tail)


def find_apk(app_name):
    if not os.path.isdir(APK_OUT_DIR):
        die("构建产物目录不存在: %s" % APK_OUT_DIR)
    apks = [f for f in os.listdir(APK_OUT_DIR) if f.endswith(".apk")]
    prefer = [f for f in apks if f.startswith(app_name + "_")]
    pool = prefer or apks
    if not pool:
        die("未找到构建产物 APK（%s）" % APK_OUT_DIR)
    pool.sort(key=lambda f: os.path.getmtime(os.path.join(APK_OUT_DIR, f)))
    return os.path.join(APK_OUT_DIR, pool[-1])


def pack_brand(brand_path, out_dir, cloud_url_override=None):
    brand = load_brand(brand_path)
    app_name, app_id = brand["app_name"], brand["package_name"]
    cloud_url = (cloud_url_override if cloud_url_override is not None
                 else brand.get("cloud_url"))
    log("品牌配置: app_name=%s / package_name=%s" % (app_name, app_id))
    zip_bytes, project_name = normalize_zip(brand["_zip_path"], cloud_url)
    logo_bak = None
    try:
        gen_brand_assets(zip_bytes, project_name, brand)
        logo_bak = replace_icon(brand["_icon_path"])
        gradle_build(app_id, app_name)
        apk = find_apk(app_name)
        os.makedirs(out_dir, exist_ok=True)
        dst = os.path.join(out_dir, "%s.apk" % app_name)
        shutil.copy2(apk, dst)
        log("产物已复制: %s（源: %s）" % (dst, os.path.basename(apk)))
    finally:
        restore_icon(logo_bak)
        if os.path.isdir(BRAND_ASSETS_ROOT):
            shutil.rmtree(BRAND_ASSETS_ROOT)
            log("brand_assets 已清理")


def main():
    ap = argparse.ArgumentParser(
        description="云控贴牌打包流水线（brand.json → 品牌 APK）")
    ap.add_argument("--brand", help="brand.json 路径（一键品牌 APK）")
    ap.add_argument("--out", default="dist", help="APK 产物目录（默认 dist/）")
    ap.add_argument("--make-zip", action="store_true",
                    help="仅打包云控壳工程 zip（根注入 package.json/brand.json）")
    ap.add_argument("--zip-out", default=None,
                    help="make-zip 输出路径（默认 dist/jyframe-cloud.zip）")
    ap.add_argument("--cloud-url", default=None,
                    help="透传进 zip 根 brand.json 的云控地址（优先于 brand.json 的 cloud_url）")
    ap.add_argument("--engine-repo", default=None,
                    help="autolua2 引擎仓库路径（gradle 构建 cwd 与 brand_assets 位置；"
                         "默认内置仓库路径，VSCode 插件打包时指向本机仓库）")
    ap.add_argument("--secret", default=None,
                    help="云控 PSK（写进 zip 根 brand.json 的 secret；缺省时 --brand 文件有 secret 则透传，"
                         "否则自动生成 64 hex 并打印到终端）")
    args = ap.parse_args()

    if args.engine_repo:
        set_engine_repo(args.engine_repo)

    if args.make_zip:
        out = args.zip_out or os.path.join(BASE, "dist", "jyframe-cloud.zip")
        cloud_url = args.cloud_url
        secret = args.secret
        if args.brand:
            light = read_brand_light(args.brand)
            if cloud_url is None:
                cloud_url = light.get("cloud_url") or ""
            if secret is None and isinstance(light.get("secret"), str) and light.get("secret"):
                secret = light["secret"]
                log("secret 已从 brand 文件透传: %s" % args.brand)
        if not secret:
            secret = secrets.token_hex(32)
            log("brand.json 无 secret，已自动生成（抄给 ws_client --secret）: %s" % secret)
        make_zip(out, cloud_url, secret)
        return
    if args.brand:
        out_dir = (args.out if os.path.isabs(args.out)
                   else os.path.join(BASE, args.out))
        pack_brand(args.brand, out_dir, args.cloud_url)
        return
    ap.print_help()
    die("请指定 --brand <brand.json> 或 --make-zip")


if __name__ == "__main__":
    main()
