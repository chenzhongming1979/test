/**
 * Lua API 文档数据（与 docs/02-Lua-API规范.md 同步，2026-09-14 / 5cd1ff4 批次：ime/mysql/event/Fire/cv 全量；
 * 2026-09-23 与引擎核对补齐：动态 UI 全族（ui 与 console 全部函数，UiLib）+ 节点域全局 waitFor/keepNode/getNodeXml/
 * openAccessibility/screenshot 等（NodeDomainInstaller））：
 * 覆盖 C++ 全局 + Java 注入服务 + compat 兼容层全部脚本可调用 API。
 * 分组按功能域归并：网络(含 ws/fhttp)、图色(含图像处理/坐标换算)、触控(含输入法/auto 动作)、
 * 节点(含 autoCollection/auto 查找)、文件(含 path)、设备(含 media)、线程(含 timer)、插件与OCR。
 * 点击插入模板 → 编辑器当前光标；函数视图列表以中文描述为主显示、函数名为副。
 */

export interface LuaApiDoc {
  group: string;
  name: string;
  /** 列表主显示：功能族_变体（如 多点找色_首点），缺省回退 desc */
  label?: string;
  signature: string;
  desc: string;
  template: string;
}

export const LUA_API_DOCS: LuaApiDoc[] = [
  // ================= 基础 =================
  {
    group: '基础', name: 'print', signature: 'print(...)',
    label: '日志输出',
    desc: '输出日志到 IDE 日志面板与设备端调试输出',
    template: 'print("hello AutoLua")',
  },
  {
    group: '基础', name: 'sleep', signature: 'sleep(ms)',
    label: '延时等待',
    desc: '毫秒级延时；可被脚本 interrupt 打断',
    template: 'sleep(1000)',
  },
  {
    group: '基础', name: 'loadfile', signature: 'loadfile(path)',
    label: '脚本_加载文件',
    desc: '加载脚本文件（自定义 CodeProvider 查找）',
    template: 'loadfile(ROOT_DIR .. "/lib/util.lua")()',
  },
  {
    group: '基础', name: 'loadresource', signature: 'loadresource(name)',
    label: '脚本_加载资源',
    desc: '加载资源脚本',
    template: 'loadresource("util")',
  },
  {
    group: '基础', name: 'require', signature: 'require(mod)',
    label: '脚本_模块加载',
    desc: '标准模块加载（Lua 5.4）',
    template: 'local util = require("util")',
  },
  {
    group: '基础', name: 'ROOT_DIR', signature: 'ROOT_DIR（全局常量）',
    label: '脚本_根目录',
    desc: '脚本项目根目录字符串，可与其拼接文件路径',
    template: 'print(ROOT_DIR)',
  },

  // ================= 图色-屏幕控制 =================
  {
    group: '设备', name: 'getBaseSize', signature: 'getBaseSize() → w, h',
    label: '设备_基准分辨率',
    desc: '获取基准分辨率（宽、高）',
    template: 'local w, h = getBaseSize()\nprint("screen:", w, h)',
  },
  {
    group: '设备', name: 'getBaseDensity', signature: 'getBaseDensity() → density',
    label: '设备_基准密度',
    desc: '获取基准屏幕密度',
    template: 'local d = getBaseDensity()',
  },
  {
    group: '设备', name: 'getBaseDirection', signature: 'getBaseDirection() → dir',
    label: '设备_基准方向',
    desc: '获取屏幕方向',
    template: 'local dir = getBaseDirection()',
  },
  {
    group: '设备', name: 'getRotation', signature: 'getRotation() → rotation',
    label: '设备_当前旋转',
    desc: '获取当前屏幕旋转方向',
    template: 'local r = getRotation()',
  },
  {
    group: '设备', name: 'isChangeDirection', signature: 'isChangeDirection() → bool',
    label: '设备_旋转检测',
    desc: '检测屏幕方向是否发生变化（横竖屏切换）',
    template: 'if isChangeDirection() then\n    updateScreen()\nend',
  },
  {
    group: '设备', name: 'updateScreen', signature: 'updateScreen()',
    label: '设备_抓屏刷新',
    desc: '刷新截屏（重新截取一帧，释放旧帧）',
    template: 'updateScreen()',
  },
  {
    group: '设备', name: 'keepScreen', signature: 'keepScreen()',
    label: '设备_抓屏保持',
    desc: '保持当前帧不再重新截图（同一帧内批量取色提效）',
    template: 'keepScreen()\n-- 批量取色...\nupdateScreen()',
  },
  {
    group: '设备', name: 'updateAndKeepScreen', signature: 'updateAndKeepScreen()',
    label: '设备_抓屏刷新并保持',
    desc: '刷新一帧并保持（先拍后冻结）',
    template: 'updateAndKeepScreen()',
  },
  {
    group: '设备', name: 'saveScreen', signature: 'saveScreen(path)',
    label: '设备_截屏保存',
    desc: '整屏保存为图片文件',
    template: 'saveScreen(ROOT_DIR .. "/screen.png")',
  },
  {
    group: '设备', name: 'resetScreen', signature: 'resetScreen()',
    label: '设备_抓屏重置',
    desc: '重置屏幕截屏状态',
    template: 'resetScreen()',
  },

  // ================= 图色-取色找色 =================
  {
    group: '图色', name: 'getColor', signature: 'getColor(x, y) → color',
    label: '取色_单点',
    desc: '取指定点颜色值（整数）',
    template: 'local c = getColor(500, 300)\nprint("color:", c)',
  },
  {
    group: '图色', name: 'getColorCount', signature: 'getColorCount(color, sim[, region]) → n',
    label: '找色_数量统计',
    desc: '指定颜色在区域内的像素数量',
    template: 'local n = getColorCount("ffff00", 0.9)\nprint("yellow px:", n)',
  },
  {
    group: '图色', name: 'isColor', signature: 'isColor(x, y, color, sim) → bool',
    label: '找色_单点判断',
    desc: '指定点是否为某色',
    template: 'if isColor(500, 300, 0xffff00, 0.9) then\n    print("颜色匹配")\nend',
  },
  {
    group: '图色', name: 'whichColor', signature: 'whichColor(x, y, colorTable) → name, matched',
    label: '找色_多点匹配判断',
    desc: '指定点匹配颜色表中的哪一项',
    template: 'local name = whichColor(500, 300, {red = 0xff0000, green = 0x00ff00})\nprint(name)',
  },
  {
    group: '图色', name: 'findColor', signature: 'findColor(x1, y1, x2, y2, color, sim[, order]) → x, y',
    label: '找色_范围首点',
    desc: '区域查找单色，返回 x, y（找不到 -1,-1）；findColorEx 语义相同',
    template: 'local x, y = findColor(0, 0, 720, 1280, 0xffff00, 0.9)\nif x ~= -1 then tap(x, y) end',
  },
  {
    group: '图色', name: 'findColorEx', signature: 'findColorEx(x1, y1, x2, y2, color, sim[, order]) → x, y',
    label: '找色_范围首点别名',
    desc: '同 findColor（懒人命名）',
    template: 'local x, y = findColorEx(0, 0, 720, 1280, 0xffff00, 0.9)',
  },
  {
    group: '图色', name: 'findColorAllPoint', signature: 'findColorAllPoint(x1, y1, x2, y2, color, sim[, order]) → {{x,y},...}',
    label: '找色_范围全部',
    desc: '区域内全部单色匹配位置',
    template: 'local points = findColorAllPoint(0, 0, 720, 1280, 0xffff00, 0.9)\nfor _, p in ipairs(points) do print(p[1], p[2]) end',
  },
  {
    group: '图色', name: 'isFeature', signature: 'isFeature(x, y, featureStr, sim) → bool',
    label: '找色_特征判断',
    desc: '特征多点比色判定。特征串格式 "x|y|色值,x|y|色值"（首点为锚）',
    template: 'if isFeature(500, 300, "0|0|ffff00,10|0|ff0000", 0.9) then\n    print("特征匹配")\nend',
  },
  {
    group: '图色', name: 'findFeature', signature: 'findFeature(x1, y1, x2, y2, featureStr, sim[, order]) → x, y',
    label: '找色_特征首点',
    desc: '多点特征查找，返回首点 x, y',
    template: 'local x, y = findFeature(0, 0, 720, 1280, "0|0|ffff00,10|0|ff0000", 0.9)\nif x ~= -1 then tap(x, y) end',
  },
  {
    group: '图色', name: 'findFeatureAll', signature: 'findFeatureAll(x1, y1, x2, y2, featureStr, sim[, order]) → {{x,y},...}',
    label: '找色_特征全部',
    desc: '区域内全部特征匹配原点',
    template: 'local pts = findFeatureAll(0, 0, 720, 1280, "0|0|ffff00,10|0|ff0000", 0.9)\nprint(#pts)',
  },
  {
    group: '图色', name: 'findMultiColor', signature: 'findMultiColor(x1,y1,x2,y2, firstColor, offsetStr, sim[, order]) → x, y',
    label: '多点找色_首点',
    desc: '懒人多点找色（firstColor 首色，offsetStr 偏移校验色串），返回首点',
    template: 'local x, y = findMultiColor(0, 0, 720, 1280, "ffff00", "10|0|ff0000|-10|5|0000ff", 0.9)\nif x ~= -1 then tap(x, y) end',
  },
  {
    group: '图色', name: 'findMultiColorAll', signature: 'findMultiColorAll(x1,y1,x2,y2, firstColor, offsetStr, sim[, order]) → {{x,y},...}',
    label: '多点找色_全部',
    desc: '懒人多点找色全部位置',
    template: 'local pts = findMultiColorAll(0, 0, 720, 1280, "ffff00", "10|0|ff0000", 0.9)\nprint(#pts)',
  },
  {
    group: '图色', name: 'findImage', signature: 'findImage(img, sim[, order]) → x, y, idx',
    label: '找图_首点',
    desc: '模板图找图。img 为 loadImage 的 Bitmap 或路径字符串（逗号分隔多路径轮询），返回坐标与命中序号',
    template: 'local x, y = findImage(ROOT_DIR .. "/btn.png", 0.9)\nif x ~= -1 then tap(x, y) end',
  },
  {
    group: '图色', name: 'isImage', signature: 'isImage(x, y, img, sim) → bool',
    label: '找图_区域判断',
    desc: '指定坐标校验图片模板是否匹配',
    template: 'if isImage(500, 300, ROOT_DIR .. "/btn.png", 0.9) then\n    print("匹配")\nend',
  },
  {
    group: '图色', name: 'whichImage', signature: 'whichImage(imgTable, sim) → name, x, y',
    label: '找图_多图判断',
    desc: '多图依次查找，返回命中的图片名与坐标',
    template: 'local name, x, y = whichImage({\n    ok = ROOT_DIR .. "/ok.png",\n    cancel = ROOT_DIR .. "/cancel.png",\n}, 0.9)\nprint(name, x, y)',
  },
  {
    group: '图色', name: 'getScreenPixel', signature: 'getScreenPixel(x1, y1, x2, y2) → {{c,...},...}',
    label: '取色_区域像素块',
    desc: '区域像素导出为二维数组（每行一表，元素 0xRRGGBB）',
    template: 'local px = getScreenPixel(0, 0, 100, 100)\nprint(px[1][1])',
  },
  {
    group: '设备', name: 'isDisplayDead', signature: 'isDisplayDead(x1, y1, x2, y2[, delayMs]) → bool',
    label: '设备_屏幕死机检测',
    desc: '区域静止检测：间隔 delayMs（默认 1000ms）两次采样，像素完全一致返回 true',
    template: 'if isDisplayDead(0, 0, 720, 1280, 1000) then\n    print("画面静止")\nend',
  },

  // ================= 图色-Bitmap =================
  {
    group: '图色', name: 'loadImage', signature: 'loadImage(path) → bmp',
    label: '图像_加载',
    desc: '加载图片为 Bitmap 对象',
    template: 'local img = loadImage(ROOT_DIR .. "/btn.png")',
  },
  {
    group: '图色', name: 'bmp:getColor', signature: 'bmp:getColor(x, y) → color',
    label: '图像_位图取色',
    desc: 'Bitmap 上取色（与全局同名图色方法一致，作用于该图）',
    template: 'local img = loadImage(ROOT_DIR .. "/btn.png")\nprint(img:getColor(10, 10))',
  },
  {
    group: '图色', name: 'saveImage', signature: 'saveImage(bmp, path) / bmp:save(path)',
    label: '图像_保存',
    desc: '保存 Bitmap 到文件',
    template: 'local img = loadImage(ROOT_DIR .. "/btn.png")\nimg:save(ROOT_DIR .. "/copy.png")',
  },
  {
    group: '图色', name: 'cloneImage', signature: 'cloneImage(bmp[, x1,y1,x2,y2]) → bmp',
    label: '图像_克隆裁剪',
    desc: '克隆 Bitmap（可选区域裁剪）',
    template: 'local part = cloneImage(img, 0, 0, 100, 100)',
  },
  {
    group: '图色', name: 'getImageSize', signature: 'getImageSize(bmp) / bmp:getSize() → w, h',
    label: '图像_尺寸',
    desc: '获取 Bitmap 尺寸',
    template: 'local w, h = getImageSize(img)',
  },

  // ================= 图色-images 模块 =================
  {
    group: '图色', name: 'images.crop', signature: 'images.crop(img, x, y, w, h) → img',
    label: '图像_裁剪',
    desc: '区域裁剪，返回新图',
    template: 'local part = images.crop(img, 0, 0, 100, 100)',
  },
  {
    group: '图色', name: 'images.clone', signature: 'images.clone(img) → img',
    label: '图像_克隆',
    desc: '整图拷贝',
    template: 'local copy = images.clone(img)',
  },
  {
    group: '图色', name: 'images.resize', signature: 'images.resize(img, w, h) → img',
    label: '图像_缩放',
    desc: '最近邻缩放',
    template: 'local small = images.resize(img, 360, 640)',
  },
  {
    group: '图色', name: 'images.rotate90', signature: 'images.rotate90(img) → img',
    label: '图像_旋转90',
    desc: '顺时针旋转 90 度（另有 rotate180 / rotate270）',
    template: 'local r = images.rotate90(img)',
  },
  {
    group: '图色', name: 'images.flipHorizontal', signature: 'images.flipHorizontal(img) → img',
    label: '图像_翻转',
    desc: '左右镜像（另有 flipVertical 上下镜像）',
    template: 'local flipped = images.flipHorizontal(img)',
  },
  {
    group: '图色', name: 'images.getData', signature: 'images.getData(img) → dataStr',
    label: '图像_像素导出',
    desc: '取紧凑像素字节串（width×stride×height）',
    template: 'local data = images.getData(img)',
  },
  {
    group: '图色', name: 'images.fromData', signature: 'images.fromData(w, h, dataStr[, stride]) → img',
    label: '图像_像素构建',
    desc: '字节串构建图像（默认 RGBA 4 字节）',
    template: 'local img = images.fromData(100, 100, data)',
  },
  {
    group: '图色', name: 'images.toPng', signature: 'images.toPng(img) → pngStr',
    label: '图像_PNG编码',
    desc: '编码为 PNG 字节串',
    template: 'local png = images.toPng(img)',
  },
  {
    group: '图色', name: 'images.toJpg', signature: 'images.toJpg(img[, quality]) → jpgStr',
    label: '图像_JPG编码',
    desc: '编码为 JPEG 字节串（quality 1-100 默认 80，别名 images.compress；libjpeg-turbo）',
    template: 'local jpg = images.toJpg(img, 85)',
  },
  {
    group: '图色', name: 'images.fromJpg / saveJpg / readJpg', signature: 'images.fromJpg(jpgStr) → img / images.saveJpg(img, path[, quality]) → bool, err / images.readJpg(path) → img',
    label: '图像_JPG解码保存读取',
    desc: 'JPEG 解码 / JPEG 落盘 / 加载 JPEG 文件',
    template: 'images.saveJpg(img, ROOT_DIR .. "/out.jpg", 85)\nlocal img = images.readJpg(ROOT_DIR .. "/out.jpg")',
  },
  {
    group: '图色', name: 'images.save', signature: 'images.save(img, path) → bool, err',
    label: '图像_写文件',
    desc: 'PNG 落盘',
    template: 'images.save(img, ROOT_DIR .. "/out.png")',
  },
  {
    group: '图色', name: 'images.read', signature: 'images.read(path) → img',
    label: '图像_读文件',
    desc: '加载图片（=loadImage）',
    template: 'local img = images.read(ROOT_DIR .. "/btn.png")',
  },
  {
    group: '图色', name: 'images.toBase64', signature: 'images.toBase64(img) → str',
    label: '图像_Base64编码',
    desc: 'PNG → base64',
    template: 'local b64 = images.toBase64(img)',
  },
  {
    group: '图色', name: 'images.fromBase64', signature: 'images.fromBase64(str) → img',
    label: '图像_Base64解码',
    desc: 'base64 → image',
    template: 'local img = images.fromBase64(b64)',
  },
  {
    group: '图色', name: 'images.readImageToBase64', signature: 'images.readImageToBase64(path) → str',
    label: '图像_文件转Base64',
    desc: '图片文件 → base64(PNG)',
    template: 'local b64 = images.readImageToBase64(ROOT_DIR .. "/btn.png")',
  },
  {
    group: '图色', name: 'images.writeFileFromBase64', signature: 'images.writeFileFromBase64(str, path) → bool',
    label: '图像_Base64写文件',
    desc: 'base64 解码落盘',
    template: 'images.writeFileFromBase64(b64, ROOT_DIR .. "/out.png")',
  },
  {
    group: '图色', name: 'images.getSize', signature: 'images.getSize(img) → w, h / isNull(img) / getWidth(img) / getHeight(img)',
    label: '图像_尺寸与判空',
    desc: '图像状态与尺寸查询',
    template: 'local w, h = images.getSize(img)',
  },

  // ================= 图色-缩放与别名 =================
  {
    group: '设备', name: 'setScreenScale', signature: 'setScreenScale(scale)',
    label: '设备_坐标缩放基准设置',
    desc: '脚本按虚拟分辨率编写，运行设备自动换算（触控+图色坐标统一缩放）',
    template: 'setScreenScale(720 / 1080) -- 按 720p 基准编写',
  },
  {
    group: '设备', name: 'getScreenScale', signature: 'getScreenScale() → scale',
    label: '设备_坐标缩放基准读取',
    desc: '当前缩放系数（默认 1）',
    template: 'print(getScreenScale())',
  },
  {
    group: '设备', name: 'snapShot', signature: 'snapShot(path[, x, y, w, h])',
    label: '设备_区域截屏',
    desc: '区域截图保存（无区域参数时整屏）',
    template: 'snapShot(ROOT_DIR .. "/part.png", 0, 0, 300, 300)',
  },
  {
    group: '图色', name: 'cmpColor', signature: 'cmpColor(x, y, color, sim) → bool',
    label: '取色_指定点比对',
    desc: '比色（=isColor；cmpColorEx = isFeature）',
    template: 'if cmpColor(500, 300, 0xffff00, 0.9) then end',
  },
  {
    group: '图色', name: 'getPixelColor', signature: 'getPixelColor(x, y) → color',
    label: '取色_单点别名',
    desc: '取色（=getColor）',
    template: 'local c = getPixelColor(500, 300)',
  },
  {
    group: '图色', name: 'getColorNum', signature: 'getColorNum(x1, y1, x2, y2, color, sim) → n',
    label: '找色_区域数量',
    desc: '区域颜色计数（懒人参数序）',
    template: 'local n = getColorNum(0, 0, 720, 1280, 0xffff00, 0.9)',
  },
  {
    group: '设备', name: 'keepCapture', signature: 'keepCapture() / releaseCapture()',
    label: '设备_抓屏句柄保持释放',
    desc: '保持帧 / 刷新帧（=keepScreen / updateScreen）',
    template: 'keepCapture()\n-- 取色...\nreleaseCapture()',
  },
  {
    group: '图色', name: 'colorToRGB', signature: 'colorToRGB(color) → r, g, b',
    label: '取色_颜色转RGB',
    desc: '色值拆分为 RGB 分量（colorDiff(c1,c2) 取最大分量差）',
    template: 'local r, g, b = colorToRGB(0x3366cc)',
  },

  // ================= 触控 =================
  {
    group: '触控', name: 'tap', signature: 'tap(x, y)',
    label: '点击_屏幕坐标',
    desc: '单击（人化按下-抬起时序）',
    template: 'tap(500, 500)',
  },
  {
    group: '触控', name: 'swipe', signature: 'swipe(x1, y1, x2, y2[, dur])',
    label: '滑动_屏幕坐标',
    desc: '滑动，dur 毫秒（缺省用系统随机时长）',
    template: 'swipe(500, 800, 500, 200, 300)',
  },
  {
    group: '触控', name: 'keyDown', signature: 'keyDown(key)',
    label: '按键_按下',
    desc: '物理按键按下（配合 keyUp 使用）',
    template: 'keyDown(KeyCode.BACK)',
  },
  {
    group: '触控', name: 'keyUp', signature: 'keyUp(key)',
    label: '按键_抬起',
    desc: '物理按键抬起',
    template: 'keyUp(KeyCode.BACK)',
  },
  {
    group: '触控', name: 'keyPress', signature: 'keyPress(key[, ms])',
    label: '按键_点击',
    desc: '按键并保持 ms 毫秒（默认人化时长）',
    template: 'keyPress(KeyCode.BACK)',
  },
  {
    group: '触控', name: 'useHardwareInput', signature: 'useHardwareInput(flag)',
    label: '输入_硬件模式切换',
    desc: '切换硬件输入通道（root 下默认 true）',
    template: 'useHardwareInput(true)',
  },
  {
    group: '触控', name: 'releaseAllDown', signature: 'releaseAllDown()',
    label: '触控_全部抬起',
    desc: '释放所有按下状态（触点与按键）',
    template: 'releaseAllDown()',
  },
  {
    group: '触控', name: 'touchDown', signature: 'touchDown(id, x, y)',
    label: '触控_多点按下',
    desc: '指定手指 id 按下（多指触控便捷层）',
    template: 'touchDown(1, 300, 400)',
  },
  {
    group: '触控', name: 'touchMove', signature: 'touchMove(id, x, y[, steps])',
    label: '触控_多点移动',
    desc: '手指移动（steps 插值步数，自动分帧）',
    template: 'touchMove(1, 320, 420, 10)',
  },
  {
    group: '触控', name: 'touchUp', signature: 'touchUp(id)',
    label: '触控_多点抬起',
    desc: '手指抬起',
    template: 'touchUp(1)',
  },
  {
    group: '触控', name: 'longTap', signature: 'longTap(x, y[, dur])',
    label: '点击_长按',
    desc: '长按，默认 800ms',
    template: 'longTap(500, 500)',
  },
  {
    group: '触控', name: 'createPointer', signature: 'createPointer() → p',
    label: '触控_创建多点指针',
    desc: '创建多指手势 Pointer 对象；p.x/p.y 可写，p:sync() 提交帧，p:up() 抬起',
    template: 'local p = createPointer()\np.x = 500; p.y = 500\np:sync()\nsleep(50)\np.y = 400\np:sync()\np:up()',
  },
  {
    group: '触控', name: 'inputText', signature: 'inputText(str)',
    label: '输入_文本注入',
    desc: '输入文本：全 ASCII 走 root input text（无需焦点）；含非 ASCII 先写剪贴板再对焦点/可见可编辑节点粘贴（需无障碍在线）',
    template: 'inputText("hello 世界")',
  },
  {
    group: '触控', name: 'toast / hideToast', signature: 'toast(msg[, dur]) / hideToast()',
    label: '提示_Toast显示隐藏',
    desc: '弹 Toast（沙箱走 UI 服务；root 经 luajava 反射，失败 false）/ 取消上一个 toast',
    template: 'toast("开始执行", 0)',
  },

  // ================= 节点 =================
  {
    group: '节点', name: 'UiSelector', signature: 'UiSelector() → sel',
    label: '节点_选择器创建',
    desc: '创建选择器，链式调用追加条件（高频条件见全局工厂 id()/text() 等）',
    template: 'local node = getRootInActiveWindow():findOne(UiSelector():text("登录"))\nif node then node:click() end',
  },
  {
    group: '节点', name: 'getRootInActiveWindow', signature: 'getRootInActiveWindow() → node',
    label: '节点_根窗口获取',
    desc: '获取当前活动窗口根节点（UiObject）',
    template: 'local root = getRootInActiveWindow()\nprint(root:pkg())',
  },
  {
    group: '节点', name: 'setText', signature: 'setText(text)',
    label: '节点_全局输入文本',
    desc: '对焦点节点输入文本',
    template: 'setText("hello")',
  },
  {
    group: '节点', name: 'text / textContains / textStartsWith / textEndsWith / textMatches', signature: 'text(v) → sel',
    label: '节点_文本条件',
    desc: '文本条件族（五系：精确/包含/前缀/后缀/正则），返回可继续链式的 UiSelector',
    template: 'auto.findOne(text("登录"):clickable(true)):click()',
  },
  {
    group: '节点', name: 'desc / descContains / descStartsWith / descEndsWith / descMatches', signature: 'desc(v) → sel',
    label: '节点_描述条件',
    desc: '描述条件族（五系）',
    template: 'auto.findOne(desc("发送")):click()',
  },
  {
    group: '节点', name: 'id / idContains / idStartsWith / idEndsWith / idMatches', signature: 'id(v) → sel',
    label: '节点_ID条件',
    desc: '资源 id 条件族（五系）',
    template: 'auto.findOne(idContains("btn_send")):click()',
  },
  {
    group: '节点', name: 'clazz / clazzContains / clazzStartsWith / clazzEndsWith / clazzMatches', signature: 'clazz(v) → sel',
    label: '节点_类型条件',
    desc: '类名条件族（五系）',
    template: 'auto.findOne(clazz("android.widget.Button")):click()',
  },
  {
    group: '节点', name: 'pkg / pkgMatches', signature: 'pkg(v) → sel',
    label: '节点_包名条件',
    desc: '包名条件族',
    template: 'auto.findOne(pkg("com.tencent.mm"))',
  },
  {
    group: '节点', name: 'hint 系列', signature: 'hint(v) / hintContains(v) / hintStartsWith(v) / hintEndsWith(v) / hintMatches(v)',
    label: '节点_提示条件',
    desc: '提示文本条件族（五系）',
    template: 'auto.findOne(hint("请输入密码"))',
  },
  {
    group: '节点', name: '布尔条件', signature: 'clickable(v) checkable(v) checked(v) enabled(v) focusable(v) focused(v) longClickable(v) scrollable(v) selected(v) visibleToUser(v) editable(v)',
    label: '节点_状态条件',
    desc: '布尔状态条件（传 true/false）',
    template: 'auto.findOne(text("登录"):clickable(true):visibleToUser(true))',
  },
  {
    group: '节点', name: 'depthSel', signature: 'depthSel(min[, max]) / depth(min[,max]) / minDepth(v) / maxDepth(v)',
    label: '节点_深度条件',
    desc: '层级深度条件',
    template: 'auto.findOne(depthSel(2, 5))',
  },
  {
    group: '节点', name: 'bounds', signature: 'bounds(l, t, r, b) / boundsInside(l, t, r, b)',
    label: '节点_区域条件',
    desc: '位置边界条件（bounds 精确 / boundsInside 区域内）',
    template: 'auto.findOne(bounds(0, 1000, 720, 1200))',
  },
  {
    group: '节点', name: 'findOne / find', signature: 'node:findOne(sel) → node / node:find(sel) → {node,...}',
    label: '节点_查找单个全部',
    desc: '在子树中查找（findOne 单个 nil 表示无；find 全部返回数组）',
    template: 'local root = getRootInActiveWindow()\nlocal list = root:find(idContains("item"))\nprint(#list)',
  },
  {
    group: '节点', name: 'node 属性', signature: 'node:text() / desc() / clazz() / pkg() / res() / bounds() / childCount() / parent() ...',
    label: '节点_属性读取',
    desc: 'UiObject 属性读取（无参调用）：文本/描述/类名/包名/资源名/边界{L,T,R,B}/子节点数/父节点等',
    template: 'local node = auto.findOne(idContains("title"))\nprint(node:text())',
  },
  {
    group: '节点', name: 'node:child', signature: 'node:child(i) → node',
    label: '节点_子节点获取',
    desc: '第 i 个子节点（另有 findFocus(f) 焦点子节点、recycle() 回收）',
    template: 'local c = root:child(0)\nprint(c:text())',
  },
  {
    group: '节点', name: 'node:setText', signature: 'node:setText(t) → bool',
    label: '节点_设置文本',
    desc: '设置节点文本（ACTION_SET_TEXT）',
    template: 'auto.findOne(idContains("input")):setText("hello")',
  },
  {
    group: '节点', name: '节点动作', signature: 'node:click() / longClick() / scrollForward() / scrollBackward() / scrollUp/Down/Left/Right() / focus() / clearFocus() / select() / copy() / paste() / cut() / collapse() / expand() / contextClick() / clearSelection() → bool',
    label: '节点_动作执行',
    desc: '节点动作（performAction 封装，返回 bool）；setSelection(start, ends) 需两参',
    template: 'auto.findOne(text("登录")):click()',
  },
  {
    group: '节点', name: 'auto.findOne / auto.find', signature: 'auto.findOne(sel) → node / auto.find(sel) → {node,...}',
    label: '节点_集合查找单全部',
    desc: '派大星风格：在当前窗口查找',
    template: 'auto.findOne(text("登录")):click()',
  },
  {
    group: '节点', name: 'auto.waitFor', signature: 'auto.waitFor(sel, timeout) → node|nil',
    label: '节点_等待出现',
    desc: '轮询等待节点出现，超时返回 nil',
    template: 'local node = auto.waitFor(text("登录"), 5000)\nif node then node:click() end',
  },
  {
    group: '节点', name: 'auto.exists', signature: 'auto.exists(sel) → bool',
    label: '节点_存在判断',
    desc: '节点是否存在',
    template: 'if auto.exists(text("继续观看")) then auto.findOne(text("继续观看")):click() end',
  },

  // ================= 节点域引擎全局（2026-09-23 与引擎同步） =================
  {
    group: '节点', name: 'waitFor', signature: 'waitFor(sel[, timeout]) → node|nil',
    label: '节点_等待出现全局',
    desc: '等待选择器匹配节点出现（带超时轮询，默认 10s，超时 nil）',
    template: 'local n = waitFor(text("开始"), 5000)\nif n then n:click() end',
  },
  {
    group: '节点', name: 'keepNode / releaseNode / updateNode', signature: 'keepNode() / releaseNode() / updateNode()',
    label: '节点_缓存三件套',
    desc: '懒人节点缓存三件套：开启缓存 / 释放 / 手动刷新（减少重复抓树）',
    template: 'keepNode()\nlocal n = text("确定"):findOne()\nreleaseNode()',
  },
  {
    group: '节点', name: 'findByIndex', signature: 'findByIndex(str) → node|nil',
    label: '节点_索引查找',
    desc: '按节点索引查找（内部索引字符串）',
    template: 'local n = findByIndex("0.1.2")',
  },
  {
    group: '节点', name: 'setNodeMode', signature: 'setNodeMode([mode][, enableInvisible])',
    label: '节点_模式切换',
    desc: '节点获取模式：mode=0 全部 / 1 简单；enableInvisible 决定 getNodeXml 缺省是否含不可见节点',
    template: 'setNodeMode(0, true)',
  },
  {
    group: '节点', name: 'isAccServiceOk', signature: 'isAccServiceOk() → bool',
    label: '节点_服务就绪检测',
    desc: '无障碍服务是否就绪（Java 直读 settings，零 fork；root 下坐标触控/找色找图不依赖它）',
    template: 'if not isAccServiceOk() then print("节点功能需先手动开启无障碍") end',
  },
  {
    group: '节点', name: 'openAccessibility / closeAccessibility', signature: 'openAccessibility() → bool / closeAccessibility() → bool',
    label: '节点_无障碍开关',
    desc: 'root 开关本引擎无障碍服务（写 settings，不自动跳设置页；应由用户自主开启）',
    template: 'openAccessibility()',
  },
  {
    group: '节点', name: 'getNodeXml', signature: 'getNodeXml([includeInvisible]) → str',
    label: '节点_树XML导出',
    desc: '导出当前节点树 XML（调试利器；缺省跟随 setNodeMode）',
    template: 'print(getNodeXml())',
  },
  {
    group: '节点', name: 'saveNode', signature: 'saveNode([path][, includeInvisible]) → path',
    label: '节点_树XML落盘',
    desc: '节点树 XML 落盘，返回实际写入路径',
    template: 'print(saveNode())',
  },
  {
    group: '节点', name: 'screenshot', signature: 'screenshot([timeoutMs]) → bmp|nil',
    label: '节点_无障碍截图',
    desc: '无障碍截图（API 30+，服务连接时可用；失败/超时 nil；勿在主线程调用）',
    template: 'local img = screenshot(3000)\nif img then img:save(ROOT_DIR .. "/shot.png") end',
  },
  {
    group: '设备', name: 'auto.getScreenSize', signature: 'auto.getScreenSize() → w, h',
    label: '设备_窗口尺寸',
    desc: '当前窗口尺寸（=coordinate.getScreenSize）',
    template: 'local w, h = auto.getScreenSize()',
  },
  {
    group: '触控', name: 'auto.click / auto.longClick', signature: 'auto.click(x, y) / auto.longClick(x, y[, dur])',
    label: '触控_无障碍点击长按',
    desc: '坐标点击 / 长按（=tap/longTap，派大星别名；longClick 默认 800ms）',
    template: 'auto.click(500, 500)',
  },
  {
    group: '触控', name: 'auto.swipe', signature: 'auto.swipe(x1, y1, x2, y2[, dur])',
    label: '触控_无障碍滑动',
    desc: '坐标滑动（=swipe，默认 500ms）',
    template: 'auto.swipe(500, 800, 500, 200, 300)',
  },
  {
    group: '触控', name: 'auto.back / auto.home / auto.recents', signature: 'auto.back() / auto.home() / auto.recents()',
    label: '触控_无障碍系统键',
    desc: '系统键：返回 / HOME / 最近任务（=keyPress(KeyCode.xxx)）',
    template: 'auto.back()',
  },
  {
    group: '触控', name: 'auto.touchDown/Move/Up', signature: 'auto.touchDown(id, x, y) / auto.touchMove(id, x, y) / auto.touchUp(id)',
    label: '触控_无障碍手势三件套',
    desc: '多指触控（=touchDown/Move/Up）；auto.press(key, ms) = keyPress',
    template: 'auto.touchDown(1, 300, 400)\nauto.touchMove(1, 320, 420)\nauto.touchUp(1)',
  },

  // ================= 文件 =================
  {
    group: '文件', name: 'fileExist', signature: 'fileExist(path) → bool',
    label: '文件_存在判断',
    desc: '文件/目录是否存在',
    template: 'if fileExist(ROOT_DIR .. "/cfg.json") then end',
  },
  {
    group: '文件', name: 'mkdir', signature: 'mkdir(path)',
    label: '文件_建目录',
    desc: '递归创建目录',
    template: 'mkdir(ROOT_DIR .. "/data/log")',
  },
  {
    group: '文件', name: 'delfile', signature: 'delfile(path)',
    label: '文件_删除',
    desc: '递归删除文件/目录',
    template: 'delfile(ROOT_DIR .. "/tmp")',
  },
  {
    group: '文件', name: 'fileSize', signature: 'fileSize(path) → n',
    label: '文件_大小',
    desc: '文件字节数（目录 -1）',
    template: 'print(fileSize(ROOT_DIR .. "/cfg.json"))',
  },
  {
    group: '文件', name: 'readFile', signature: 'readFile(path) → str',
    label: '文件_读取',
    desc: '读文本（UTF-8）',
    template: 'local s = readFile(ROOT_DIR .. "/cfg.json")',
  },
  {
    group: '文件', name: 'writeFile', signature: 'writeFile(path, content)',
    label: '文件_写入',
    desc: '写文本（覆盖，自动建父目录）',
    template: 'writeFile(ROOT_DIR .. "/out.txt", "hello")',
  },
  {
    group: '文件', name: 'listDir', signature: 'listDir(path) → {name,...}',
    label: '文件_列目录',
    desc: '目录列举，返回名字数组',
    template: 'for _, name in ipairs(listDir(ROOT_DIR)) do print(name) end',
  },
  {
    group: '文件', name: 'file.exists', signature: 'file.exists(path) / file.isFile(path) / file.isDirectory(path) → bool',
    label: '文件_存在判断对象式',
    desc: '类型判断',
    template: 'print(file.exists("/sdcard/a.txt"), file.isFile("/sdcard/a.txt"))',
  },
  {
    group: '文件', name: 'file.size', signature: 'file.size(path) → n',
    label: '文件_大小对象式',
    desc: '文件字节数',
    template: 'print(file.size("/sdcard/a.txt"))',
  },
  {
    group: '文件', name: 'file.mkdir / file.mkdirs', signature: 'file.mkdir(path) / file.mkdirs(path)',
    label: '文件_建目录对象式',
    desc: '创建目录（单级 / 递归）',
    template: 'file.mkdirs("/sdcard/a/b/c")',
  },
  {
    group: '文件', name: 'file.remove / file.deleteDir', signature: 'file.remove(path) / file.deleteDir(path)',
    label: '文件_删除对象式',
    desc: '单层删除（空目录或文件）/ 递归删除',
    template: 'file.deleteDir("/sdcard/tmp")',
  },
  {
    group: '文件', name: 'file.rename / file.copy', signature: 'file.rename(from, to) / file.copy(from, to) → bool',
    label: '文件_重命名复制',
    desc: '重命名 / 复制（支持目录递归）',
    template: 'file.copy("/sdcard/a.txt", "/sdcard/b.txt")',
  },
  {
    group: '文件', name: 'file.mTime', signature: 'file.mTime(path) → ms',
    label: '文件_修改时间',
    desc: '最后修改时间（毫秒时间戳）',
    template: 'print(file.mTime("/sdcard/a.txt"))',
  },
  {
    group: '文件', name: 'file.read', signature: 'file.read(path[, binary]) → str|bytes',
    label: '文件_读取对象式',
    desc: '读文本；binary=true 返回字节数组',
    template: 'local s = file.read("/sdcard/a.txt")',
  },
  {
    group: '文件', name: 'file.write', signature: 'file.write(path, content[, append]) → bool',
    label: '文件_写入对象式',
    desc: '写文本；append=true 追加',
    template: 'file.write("/sdcard/log.txt", "line\\n", true)',
  },
  {
    group: '文件', name: 'file.append', signature: 'file.append(path, content)',
    label: '文件_追加',
    desc: '追加文本',
    template: 'file.append("/sdcard/log.txt", "new line\\n")',
  },

  // ================= 设备 =================
  {
    group: '设备', name: 'getBrand / getModel / getManufacturer', signature: 'getBrand() / getModel() / getManufacturer() → str',
    label: '设备_品牌型号厂商',
    desc: '品牌 / 机型 / 制造商',
    template: 'print(getBrand(), getModel())',
  },
  {
    group: '设备', name: 'getBoard / getHardware / getProduct / getDevice', signature: 'getBoard() → str',
    label: '设备_主板硬件',
    desc: '硬件标识（另有 getHardware/getProduct/getDevice）',
    template: 'print(getBoard())',
  },
  {
    group: '设备', name: 'getFingerprint / getId / getBootLoader', signature: 'getFingerprint() → str',
    label: '设备_指纹标识',
    desc: '固件信息（固件指纹 / 版本 id / bootloader）',
    template: 'print(getFingerprint())',
  },
  {
    group: '设备', name: 'getCpuAbi', signature: 'getCpuAbi() / getCpuAbi2() → str',
    label: '设备_CPU架构',
    desc: '主/次 ABI（如 arm64-v8a）',
    template: 'print(getCpuAbi())',
  },
  {
    group: '设备', name: 'getSdkVersion', signature: 'getSdkVersion() → n',
    label: '设备_SDK版本',
    desc: 'Android API Level',
    template: 'print(getSdkVersion())',
  },
  {
    group: '设备', name: 'getOsVersionName', signature: 'getOsVersionName() / getAndroidVersion() → str',
    label: '设备_系统版本',
    desc: '系统版本号（如 "13"）',
    template: 'print(getAndroidVersion())',
  },
  {
    group: '设备', name: 'getAndroidId', signature: 'getAndroidId() / getDeviceId() → str',
    label: '设备_AndroidID',
    desc: 'ANDROID_ID（Android 10+ 无 IMEI，两者同值；root 进程返回空）',
    template: 'print(getAndroidId())',
  },
  {
    group: '设备', name: 'getBatteryLevel', signature: 'getBatteryLevel() → n',
    label: '设备_电量',
    desc: '电量百分比（0-100，失败 -1）',
    template: 'print(getBatteryLevel() .. "%")',
  },
  {
    group: '设备', name: 'device.isCharging', signature: 'device.isCharging() → bool',
    label: '设备_充电状态',
    desc: '是否充电中（对象式扩展）',
    template: 'print(device.isCharging())',
  },
  {
    group: '设备', name: 'getDisplayDpi', signature: 'getDisplayDpi() → n',
    label: '设备_DPI',
    desc: '屏幕密度 DPI',
    template: 'print(getDisplayDpi())',
  },
  {
    group: '设备', name: 'getDisplaySize', signature: 'getDisplaySize() → w, h',
    label: '设备_屏幕尺寸',
    desc: '屏幕分辨率（返回两个值）',
    template: 'local w, h = getDisplaySize()\nprint(w, h)',
  },
  {
    group: '设备', name: 'getMacAddress', signature: 'getMacAddress() / getWifiMac() → str',
    label: '设备_MAC地址',
    desc: 'MAC 地址',
    template: 'print(getMacAddress())',
  },
  {
    group: '设备', name: 'getIpAddress', signature: 'getIpAddress() → str',
    label: '设备_IP地址',
    desc: 'IPv4 地址',
    template: 'print(getIpAddress())',
  },
  {
    group: '设备', name: 'device.isWifiEnabled', signature: 'device.isWifiEnabled() → bool',
    label: '设备_WiFi状态',
    desc: 'WiFi 是否开启（对象式扩展）',
    template: 'print(device.isWifiEnabled())',
  },

  // ================= 应用与 shell =================
  {
    group: '应用', name: 'runApp', signature: 'runApp(target)',
    label: '应用_启动',
    desc: '启动应用：包名或 http(s) 链接（浏览器打开）',
    template: 'runApp("com.tencent.mm")',
  },
  {
    group: '应用', name: 'stopApp', signature: 'stopApp(packageName)',
    label: '应用_停止',
    desc: '停止应用（am force-stop，需 root）',
    template: 'stopApp("com.tencent.mm")',
  },
  {
    group: '应用', name: 'appIsRunning', signature: 'appIsRunning(packageName) → bool',
    label: '应用_运行判断',
    desc: '应用是否在运行（pidof）',
    template: 'if appIsRunning("com.tencent.mm") then end',
  },
  {
    group: '应用', name: 'appIsFront', signature: 'appIsFront(packageName) → bool',
    label: '应用_前台判断',
    desc: '是否前台应用',
    template: 'if appIsFront("com.tencent.mm") then print("微信已到前台") end',
  },
  {
    group: '应用', name: 'frontAppName', signature: 'frontAppName() → str',
    label: '应用_前台包名',
    desc: '前台应用包名',
    template: 'print(frontAppName())',
  },
  {
    group: '应用', name: 'getCurrentActivity', signature: 'getCurrentActivity() → str',
    label: '应用_当前Activity',
    desc: '当前 resumed Activity 描述',
    template: 'print(getCurrentActivity())',
  },
  {
    group: '应用', name: 'getInstalledApps', signature: 'getInstalledApps() → {pkg,...}',
    label: '应用_已装清单',
    desc: '第三方应用包名数组',
    template: 'for _, p in ipairs(getInstalledApps()) do print(p) end',
  },
  {
    group: '应用', name: 'exec', signature: 'exec(cmd[, isRet]) → str | out, code',
    label: '应用_Shell执行',
    desc: '执行 shell 命令；isRet 非 false 返回输出字符串，否则返回 (output, exitCode) 两值',
    template: 'local out, code = exec("ls /sdcard", false)\nprint(out, code)',
  },
  {
    group: '应用', name: 'app.execOut', signature: 'app.execOut(cmd) → str',
    label: '应用_Shell取输出',
    desc: '执行命令仅返回输出',
    template: 'print(app.execOut("id"))',
  },
  {
    group: '应用', name: 'app.launchPackage / app.killApp', signature: 'app.launchPackage(pkg) / app.killApp(pkg)',
    label: '应用_启动停止对象式',
    desc: 'monkey 方式启动 / 停止应用',
    template: 'app.launchPackage("com.tencent.mm")',
  },
  {
    group: '应用', name: 'app.isAppInstalled', signature: 'app.isAppInstalled(pkg) → bool',
    label: '应用_安装判断',
    desc: '应用是否已安装（pm path 判定）',
    template: 'print(app.isAppInstalled("com.tencent.mm"))',
  },
  {
    group: '应用', name: 'app.getTopPackageName', signature: 'app.getTopPackageName() → pkg',
    label: '应用_顶层包名',
    desc: '前台包名（mCurrentFocus 解析）',
    template: 'print(app.getTopPackageName())',
  },
  {
    group: '应用', name: 'app.getAppVersionName', signature: 'app.getAppVersionName(pkg) → str',
    label: '应用_版本号',
    desc: '应用版本号',
    template: 'print(app.getAppVersionName("com.tencent.mm"))',
  },

  // ================= 网络 =================
  {
    group: '网络', name: 'http.request', signature: 'http.request{url, method, headers, body, timeout, binary} → body, code, headers',
    label: '网络_通用请求',
    desc: '完整请求（HttpURLConnection）。url 必填；method 默认 GET；binary=true 时 body 为字节数组',
    template: 'local body, code = http.request{\n    url = "http://api.example.com/login",\n    method = "POST",\n    headers = {["Content-Type"] = "application/json"},\n    body = \'{"user":"a"}\',\n    timeout = 10000,\n}\nprint(code, body)',
  },
  {
    group: '网络', name: 'http.get', signature: 'http.get(url) → body, code',
    label: '网络_GET',
    desc: '便捷 GET',
    template: 'local html, code = http.get("http://www.baidu.com")\nprint(code)',
  },
  {
    group: '网络', name: 'http.post', signature: 'http.post(url, body) → body, code',
    label: '网络_POST',
    desc: '便捷 POST（form-urlencoded）',
    template: 'local body, code = http.post("http://api.example.com", "a=1&b=2")',
  },
  {
    group: '网络', name: 'httpGet', signature: 'httpGet(url) → body, code',
    label: '网络_GET别名',
    desc: 'compat 别名 = http.get',
    template: 'local body, code = httpGet("http://www.baidu.com")',
  },
  {
    group: '网络', name: 'httpPost', signature: 'httpPost(url, bodyData) → body, code',
    label: '网络_POST别名',
    desc: 'compat 别名 = http.post',
    template: 'local body, code = httpPost("http://api.example.com", "a=1")',
  },
  {
    group: '网络', name: 'downloadFile', signature: 'downloadFile(url, path) → ok, code',
    label: '网络_文件下载',
    desc: 'GET 二进制下载并落盘',
    template: 'local ok, code = downloadFile("http://x/img.png", ROOT_DIR .. "/img.png")\nprint(ok, code)',
  },

  // ================= 加密 =================
  {
    group: '加密', name: 'crypt.md5', signature: 'crypt.md5(data) → hex',
    label: '加密_MD5',
    desc: 'MD5 hex 字符串（全局别名 MD5(data)；fileMD5(path) 文件 MD5）',
    template: 'print(crypt.md5("hello"))',
  },
  {
    group: '加密', name: 'crypt.sha1 / sha256 / sha512', signature: 'crypt.sha256(data) → hex',
    label: '加密_SHA族',
    desc: 'SHA 族 hex（另有全局别名 sha256）',
    template: 'print(crypt.sha256("hello"))',
  },
  {
    group: '加密', name: 'crypt.fileMD5', signature: 'crypt.fileMD5(path) → hex',
    label: '加密_文件MD5',
    desc: '文件 MD5（全局别名 fileMD5）',
    template: 'print(fileMD5(ROOT_DIR .. "/cfg.json"))',
  },
  {
    group: '加密', name: 'crypt.encodeBase64 / decodeBase64', signature: 'crypt.encodeBase64(data) → str',
    label: '加密_Base64编解码',
    desc: 'UTF-8 文本编解码（全局别名 encodeBase64/decodeBase64）',
    template: 'local enc = encodeBase64("hello")\nprint(decodeBase64(enc))',
  },
  {
    group: '加密', name: 'crypt.encodeBase64Bytes', signature: 'crypt.encodeBase64Bytes(bytes) → str',
    label: '加密_Base64字节编码',
    desc: '字节级编解码（decodeBase64Bytes(str) 反向）',
    template: 'local s = crypt.encodeBase64Bytes(bytes)',
  },
  {
    group: '加密', name: 'crypt.getFileBase64', signature: 'crypt.getFileBase64(path) → str',
    label: '加密_文件Base64',
    desc: '文件转 Base64（全局别名 getFileBase64）',
    template: 'local b64 = getFileBase64(ROOT_DIR .. "/img.png")',
  },
  {
    group: '加密', name: 'crypt.encodeUrl / decodeUrl', signature: 'crypt.encodeUrl(data) → str',
    label: '加密_URL编解码',
    desc: 'URL 编解码（全局别名 encodeUrl/decodeUrl）',
    template: 'print(encodeUrl("a b&c"))',
  },
  {
    group: '加密', name: 'crypt.aesCrypt', signature: 'crypt.aesCrypt(mode, key, data[, iv]) → str',
    label: '加密_AES加解密',
    desc: '懒人 aes_crypt 语义：mode "E"加密/"D"解密；key 16/24/32 字节；有 iv 用 CBC、无 iv 用 ECB；加密返回 Base64',
    template: 'local key = "0123456789abcdef"\nlocal enc = crypt.aesCrypt("E", key, "hello")\nlocal dec = crypt.aesCrypt("D", key, enc)\nprint(dec)',
  },
  {
    group: '加密', name: 'crypt.aesKeygen / aesIvgen', signature: 'crypt.aesKeygen(size) → str',
    label: '加密_AES密钥生成',
    desc: '随机 key / iv（原始字节字符串）',
    template: 'local key = crypt.aesKeygen(16)\nlocal iv = crypt.aesIvgen()',
  },
  {
    group: '加密', name: 'crypt.rsaGenerateKey', signature: 'crypt.rsaGenerateKey(keySize) → {publicKey, privateKey}',
    label: '加密_RSA密钥对',
    desc: '生成 RSA 密钥对（base64 编码）',
    template: 'local k = crypt.rsaGenerateKey(2048)\nprint(k.publicKey)',
  },
  {
    group: '加密', name: 'crypt.rsaEncrypt', signature: 'crypt.rsaEncrypt(data, keyBase64[, privateKey]) → str',
    label: '加密_RSA加密',
    desc: '公钥加密（第三参 true 时私钥，签名语义），返回 Base64',
    template: 'local enc = crypt.rsaEncrypt("hello", pubKey)',
  },
  {
    group: '加密', name: 'crypt.rsaDecrypt', signature: 'crypt.rsaDecrypt(dataBase64, keyBase64[, privateKey]) → str',
    label: '加密_RSA解密',
    desc: '私钥解密（默认 true），返回明文',
    template: 'local plain = crypt.rsaDecrypt(enc, privKey)',
  },

  // ================= JSON =================
  {
    group: 'JSON', name: 'json.encode', signature: 'json.encode(value) → str',
    label: 'JSON_编码',
    desc: 'Lua 表/值 → JSON 字符串（int 键表自动作数组）',
    template: 'local s = json.encode({name = "x", nums = {1, 2, 3}})\nprint(s)',
  },
  {
    group: 'JSON', name: 'json.decode', signature: 'json.decode(str) → table',
    label: 'JSON_解码',
    desc: 'JSON 字符串 → Lua 表（jsonLib 为 compat 别名 = json）',
    template: 'local t = json.decode(\'{"a":1,"list":[1,2,3]}\')\nprint(t.a, #t.list)',
  },

  // ================= 线程与定时 =================
  {
    group: '线程', name: 'thread.start', signature: 'thread.start(func, ...) → handle',
    label: '协程_启动',
    desc: '启动线程任务（同 state 协程，_G 与闭包天然共享），其余参数作为 func 入参',
    template: 'local t = thread.start(function(n)\n    print("worker", n)\nend, 42)',
  },
  {
    group: '线程', name: 'thread.sleep', signature: 'thread.sleep(ms)',
    label: '协程_延时',
    desc: '线程内延时（挂起让出执行权，不阻塞其他线程）',
    template: 'thread.sleep(100)',
  },
  {
    group: '线程', name: 'thread.lock', signature: 'thread.lock() → lk',
    label: '协程_锁',
    desc: '创建互斥锁对象；lk:acquire() 加锁 / lk:release() 解锁（等待期间让出调度）',
    template: 'local lk = thread.lock()\nlk:acquire()\n-- 临界区\nlk:release()',
  },
  {
    group: '线程', name: 'thread.stop', signature: 'thread.stop(handle)',
    label: '协程_停止',
    desc: '终止任务（在挂起点抛出）',
    template: 'thread.stop(t)',
  },
  {
    group: '线程', name: 'thread.join', signature: 'thread.join(handle) → ok, result',
    label: '协程_等待结束',
    desc: '阻塞等待结束，返回 ok, 结果',
    template: 'local ok, r = thread.join(t)',
  },
  {
    group: '线程', name: 'thread.currentId / isMain', signature: 'thread.currentId() → id / thread.isMain() → bool',
    label: '协程_标识查询',
    desc: '当前线程 id / 是否主 worker',
    template: 'print(thread.currentId(), thread.isMain())',
  },
  {
    group: '线程', name: 'thread.setShared', signature: 'thread.setShared(key, v) / getShared(key) / hasShared(key) / removeShared(key)',
    label: '协程_共享数据',
    desc: '跨线程共享表',
    template: 'thread.setShared("count", 0)\nprint(thread.getShared("count"))',
  },
  {
    group: '线程', name: 'thread.atomicInc', signature: 'thread.atomicInc(key[, delta]) → new',
    label: '协程_原子计数',
    desc: '原子自增（默认 +1），返回新值',
    template: 'thread.atomicInc("count")',
  },
  {
    group: '线程', name: 'setTimer', signature: 'setTimer(func, ms, ...) → handle',
    label: '定时器_单次延时',
    desc: '后台延时执行（thread 协程驱动，不阻塞主脚本）',
    template: 'setTimer(function() print("3 秒后执行") end, 3000)',
  },
  {
    group: '线程', name: 'beginThread / newThread / th.stopThread', signature: 'beginThread(func, ...) → handle',
    label: '协程_兼容启动组',
    desc: '懒人别名 = thread.start（th.stopThread = thread.stop）',
    template: 'local t = beginThread(function() print("bg") end)',
  },

  // ================= Java 桥 =================
  {
    group: 'Java桥', name: 'import', signature: 'import(name) → classObj / importClass(name)',
    label: 'Java桥_导入类',
    desc: '全局函数：加载 Java 类，返回类对象（支持后续链式静态调用）',
    template: 'local Integer = import("java.lang.Integer")\nprint(Integer:parseInt("42"))',
  },
  {
    group: 'Java桥', name: 'luajava.bindClass', signature: 'luajava.bindClass(name) → classObj',
    label: 'Java桥_绑定类',
    desc: '对象式：同 import',
    template: 'local String = luajava.bindClass("java.lang.String")',
  },
  {
    group: 'Java桥', name: 'luajava.new', signature: 'luajava.new(classObj, ...) → obj',
    label: 'Java桥_实例化',
    desc: '按参数匹配构造器实例化（cls:new(...) 为语法糖）',
    template: 'local sb = luajava.new(import("java.lang.StringBuilder"))\nsb:append("a")\nprint(sb:toString())',
  },
  {
    group: 'Java桥', name: 'luajava.newInstance', signature: 'luajava.newInstance(name, ...) → obj',
    label: 'Java桥_实例化名字串',
    desc: '类名 + 参数直接实例化',
    template: 'local sb = luajava.newInstance("java.lang.StringBuilder")',
  },
  {
    group: 'Java桥', name: 'LuaEngine.getContext', signature: 'LuaEngine.getContext() → context',
    label: 'Java桥_上下文',
    desc: 'Android Context（root 进程反射 ActivityThread 获取，可能 nil）',
    template: 'local ctx = LuaEngine.getContext()',
  },
  {
    group: 'Java桥', name: 'LuaEngine.loadApk', signature: 'LuaEngine.loadApk(path, className) → classObj',
    label: 'Java桥_加载Apk',
    desc: 'DexClassLoader 加载 apk/dex 中的类（配合 luajava:new 实例化）',
    template: 'local cls = LuaEngine.loadApk(ROOT_DIR .. "/plugin.apk", "com.example.Entry")',
  },
  {
    group: 'Java桥', name: 'LuaEngine.getVersion', signature: 'LuaEngine.getVersion() → str',
    label: 'Java桥_引擎版本',
    desc: '引擎版本（"2.0"）',
    template: 'print(LuaEngine.getVersion())',
  },

  // ================= 字符串扩展 =================
  {
    group: '字符串', name: 'splitStr', signature: 'splitStr(str[, sep]) → {part,...}',
    label: '字符串_分割',
    desc: '按分隔符分割（默认逗号），返回数组',
    template: 'local parts = splitStr("a,b,c")\nprint(#parts, parts[1])',
  },
  {
    group: '字符串', name: 'utf8.length', signature: 'utf8.length(s) → n',
    label: '字符串_UTF8长度',
    desc: '字符数（= 标准 utf8.len）',
    template: 'print(utf8.length("中文abc"))',
  },
  {
    group: '字符串', name: 'utf8.sub', signature: 'utf8.sub(s, i[, j]) → str',
    label: '字符串_UTF8截取',
    desc: '按字符取子串（1 起始，负数从尾计）',
    template: 'print(utf8.sub("中文abc", 1, 2))',
  },
  {
    group: '字符串', name: 'utf8.left / utf8.right', signature: 'utf8.left(s, n) → str',
    label: '字符串_UTF8左右取',
    desc: '左 / 右 n 字符',
    template: 'print(utf8.left("中文abc", 2), utf8.right("中文abc", 3))',
  },
  {
    group: '字符串', name: 'utf8.mid', signature: 'utf8.mid(s, i[, j]) → str',
    label: '字符串_UTF8中取',
    desc: '同 utf8.sub',
    template: 'print(utf8.mid("中文abc", 2))',
  },
  {
    group: '字符串', name: 'utf8.inStr / utf8.inStrRev', signature: 'utf8.inStr(s, sub[, from]) → n',
    label: '字符串_UTF8查找',
    desc: '子串首次出现字符位置（0 = 不存在）/ inStrRev 最后一次出现位置',
    template: 'print(utf8.inStr("中文abc", "b"))',
  },
  {
    group: '字符串', name: 'utf8.strReverse', signature: 'utf8.strReverse(s) → str',
    label: '字符串_UTF8反转',
    desc: '按字符反转',
    template: 'print(utf8.strReverse("中文abc"))',
  },
  {
    group: '字符串', name: 'utf8.strCut', signature: 'utf8.strCut(s, pos) → char',
    label: '字符串_UTF8单字',
    desc: '取第 pos 个字符',
    template: 'print(utf8.strCut("中文abc", 2))',
  },
  {
    group: '字符串', name: 'utf8.gsub', signature: 'utf8.gsub(s, replFunc) → str',
    label: '字符串_UTF8替换',
    desc: '逐字符替换（回调返回 nil 保留原字符）',
    template: 'local s = utf8.gsub("abc123", function(ch)\n    if ch:match("%d") then return "#" end\nend)\nprint(s)',
  },

  // ================= 数组/表扩展 =================
  {
    group: '表', name: 'table.contains', signature: 'table.contains(t, v) → bool',
    label: '表_包含判断',
    desc: '是否包含元素',
    template: 'print(table.contains({1, 2, 3}, 2))',
  },
  {
    group: '表', name: 'table.indexOf', signature: 'table.indexOf(t, v) → n',
    label: '表_索引查找',
    desc: '元素下标（1 起始，无则 -1）',
    template: 'print(table.indexOf({"a", "b"}, "b"))',
  },

  // ================= 数学与随机 =================
  {
    group: '数学', name: 'math.clamp', signature: 'math.clamp(v, lo, hi) → n',
    label: '数学_区间收敛',
    desc: '限幅',
    template: 'print(math.clamp(150, 0, 100))',
  },
  {
    group: '数学', name: 'math.round', signature: 'math.round(v) → n',
    label: '数学_四舍五入',
    desc: '四舍五入',
    template: 'print(math.round(3.6))',
  },
  {
    group: '数学', name: 'math.lerp', signature: 'math.lerp(a, b, t) → n',
    label: '数学_线性插值',
    desc: '线性插值',
    template: 'print(math.lerp(0, 100, 0.5))',
  },
  {
    group: '数学', name: 'math.sign', signature: 'math.sign(v) → -1|0|1',
    label: '数学_符号',
    desc: '符号',
    template: 'print(math.sign(-5))',
  },
  {
    group: '数学', name: 'math.isEven / math.isOdd', signature: 'math.isEven(v) → bool',
    label: '数学_奇偶判断',
    desc: '奇偶判定',
    template: 'print(math.isEven(4))',
  },
  {
    group: '数学', name: 'math.inRange', signature: 'math.inRange(v, lo, hi) → bool',
    label: '数学_区间判断',
    desc: '区间判定',
    template: 'print(math.inRange(5, 1, 10))',
  },
  {
    group: '数学', name: 'math.isInteger', signature: 'math.isInteger(v) → bool',
    label: '数学_整数判断',
    desc: '是否整数',
    template: 'print(math.isInteger(3.0))',
  },
  {
    group: '数学', name: 'frandom.seed', signature: 'frandom.seed([s])',
    label: '随机_播种',
    desc: '随机播种（默认 os.time）',
    template: 'frandom.seed()',
  },
  {
    group: '数学', name: 'frandom.int / frandom.float', signature: 'frandom.int(lo, hi) → n',
    label: '随机_整数浮点',
    desc: '整数 / 浮点随机',
    template: 'print(frandom.int(1, 100))',
  },
  {
    group: '数学', name: 'frandom.boolean / frandom.chance', signature: 'frandom.boolean([p]) → bool',
    label: '随机_概率布尔',
    desc: '概率布尔（默认 0.5）',
    template: 'if frandom.chance(0.3) then print("30% 命中") end',
  },
  {
    group: '数学', name: 'frandom.pick', signature: 'frandom.pick(list) → v',
    label: '随机_取元素',
    desc: '随机取元素',
    template: 'print(frandom.pick({"苹果", "香蕉", "橙子"}))',
  },
  {
    group: '数学', name: 'frandom.hex / frandom.string', signature: 'frandom.hex([n]) → str',
    label: '随机_HEX字符串',
    desc: 'n 位 hex 串（默认 8）/ frandom.string([n[, charset]]) 随机字符串',
    template: 'print(frandom.hex(16), frandom.string(8))',
  },
  {
    group: '数学', name: 'frandom.uuid', signature: 'frandom.uuid() → str',
    label: '随机_UUID',
    desc: 'UUID v4',
    template: 'print(frandom.uuid())',
  },
  {
    group: '数学', name: 'frandom.shuffle', signature: 'frandom.shuffle(list)',
    label: '随机_洗牌',
    desc: '原地洗牌',
    template: 'local l = {1, 2, 3, 4, 5}\nfrandom.shuffle(l)',
  },
  {
    group: '数学', name: 'frandom.weighted', signature: 'frandom.weighted(items) → v',
    label: '随机_权重抽取',
    desc: '权重抽取：{{value=..., weight=...}, ...}',
    template: 'local v = frandom.weighted({\n    {value = "稀有", weight = 1},\n    {value = "普通", weight = 9},\n})\nprint(v)',
  },
  {
    group: '数学', name: 'rnd', signature: 'rnd(lo, hi) → n',
    label: '随机_整数别名',
    desc: '懒人别名 = frandom.int',
    template: 'print(rnd(1, 10))',
  },

  // ================= 时间 =================
  {
    group: '时间', name: 'systemTime', signature: 'systemTime() → ms',
    label: '时间_墙钟毫秒',
    desc: '系统墙钟毫秒（System.currentTimeMillis）',
    template: 'print(systemTime())',
  },
  {
    group: '时间', name: 'tickCount', signature: 'tickCount() → ms',
    label: '时间_开机毫秒',
    desc: '开机计时毫秒（SystemClock.elapsedRealtime）',
    template: 'print(tickCount())',
  },
  {
    group: '时间', name: 'getNetWorkTime', signature: 'getNetWorkTime() → str',
    label: '时间_网络时间',
    desc: '网络时间 "YYYY-MM-DD HH:MM:SS"（HTTP Date 头解析，需网络）',
    template: 'print(getNetWorkTime())',
  },
  {
    group: '时间', name: 'time.unix / timestamp / millisecond', signature: 'time.unix() → s / time.timestamp() / time.millisecond()',
    label: '时间_秒毫秒',
    desc: '秒 / 毫秒 / 当前毫秒部分',
    template: 'print(time.unix(), time.millisecond())',
  },
  {
    group: '时间', name: 'time.now', signature: 'time.now() → table',
    label: '时间_当前时间表',
    desc: '当前时间表（os.date("*t")）',
    template: 'local t = time.now()\nprint(t.year, t.month, t.day)',
  },
  {
    group: '时间', name: 'time 分量', signature: 'time.year() / month() / day() / hour() / minute() / second() / weekday()',
    label: '时间_分量',
    desc: '时间分量',
    template: 'print(time.year(), time.month(), time.day())',
  },
  {
    group: '时间', name: 'time.utc', signature: 'time.utc() → table',
    label: '时间_UTC表',
    desc: 'UTC 时间表',
    template: 'local u = time.utc()\nprint(u.hour)',
  },
  {
    group: '时间', name: 'time.format', signature: 'time.format([t[, fmt]]) → str',
    label: '时间_格式化',
    desc: '格式化（表或时间戳，默认 "%Y-%m-%d %H:%M:%S"）',
    template: 'print(time.format())\nprint(time.format(os.time(), "%H:%M"))',
  },
  {
    group: '时间', name: 'time.parse', signature: 'time.parse(str) → ts',
    label: '时间_解析',
    desc: '解析 "YYYY-MM-DD [HH:MM:SS]" → 时间戳',
    template: 'print(time.parse("2026-09-12 08:00:00"))',
  },
  {
    group: '时间', name: 'time.diff / time.addDays', signature: 'time.diff(t1, t2) / time.addDays(t, n) / addHours / addMinutes / addSeconds',
    label: '时间_差值平移',
    desc: '时间差值 / 平移',
    template: 'local t = time.addDays(time.now(), 1)\nprint(time.format(t))',
  },
  {
    group: '时间', name: 'time.isLeapYear / time.daysInMonth', signature: 'time.isLeapYear(y) → bool / time.daysInMonth(y, m) → n',
    label: '时间_闰年月天数',
    desc: '闰年 / 月天数',
    template: 'print(time.isLeapYear(2024), time.daysInMonth(2026, 9))',
  },

  // ================= UI 与悬浮窗 =================
  {
    group: 'UI', name: 'UI.showToast', signature: 'UI.showToast(msg[, dur])',
    label: 'UI悬浮_MLNToast',
    desc: 'Toast 提示（dur 为时长档位）',
    template: 'UI.showToast("hello", 0)',
  },
  {
    group: 'UI', name: 'UI.createView', signature: 'UI.createView(uri, LayoutParams) → view',
    label: 'UI悬浮_MLN创建视图',
    desc: '创建悬浮视图',
    template: 'local v = UI.createView(ROOT_DIR .. "/float.lua", LayoutParamsFlag.NOT_FOCUSABLE)',
  },
  {
    group: 'UI', name: 'UI.putSignal / UI.takeSignal', signature: 'UI.putSignal(sig, data) / UI.takeSignal(sig[, timeout])',
    label: 'UI悬浮_信号收发',
    desc: '信号收发（UI 与脚本通信）',
    template: 'UI.putSignal("cmd", "start")\nlocal data = UI.takeSignal("result", 3000)',
  },
  {
    group: 'UI', name: 'FloatView 方法', signature: 'view:show() / hide() / setPosition(x,y) / setSize(w,h) / setContent(view) / isShowing() / setAlpha(a)',
    label: 'UI悬浮_窗体方法',
    desc: '悬浮窗对象操作',
    template: 'v:show()',
  },

  // ================= 动态 UI 全族（2026-09-23 与引擎 UiLib 同步） =================
  {
    group: 'UI', name: 'ui.createWindow', signature: 'ui.createWindow(cfg, rootTree) → winId',
    label: '动态UI_创建窗口',
    desc: '创建动态悬浮窗（JSON 控件树）；cfg:{win,x,y,w,h,drag,focus,alpha,notTouchable}；自动启动事件派发',
    template: 'local win = ui.createWindow({win="p", x=40, y=80, w="72%", h="50%", drag=true},\n  { type="linear", id="__root", orientation="v", children={\n    { type="text", id="t", text="hello" } } })',
  },
  {
    group: 'UI', name: 'ui.close / show / hide', signature: 'ui.close(win) / ui.show(win) / ui.hide(win) → bool',
    label: '动态UI_关闭显示隐藏',
    desc: '关闭 / 显示 / 隐藏窗口',
    template: 'ui.close(win)',
  },
  {
    group: 'UI', name: 'ui.move / setSize / setAlpha', signature: 'ui.move(win,x,y) / ui.setSize(win,w,h) / ui.setAlpha(win,a) → bool',
    label: '动态UI_移动尺寸透明度',
    desc: '窗口移动 / 改尺寸 / 改透明度',
    template: 'ui.move(win, 60, 80)',
  },
  {
    group: 'UI', name: 'ui.list', signature: 'ui.list() → {name→id}',
    label: '动态UI_窗口清单',
    desc: '当前已开窗口清单',
    template: 'for name, id in pairs(ui.list()) do print(name, id) end',
  },
  {
    group: 'UI', name: 'ui.add', signature: 'ui.add(win, parent, node[, index])',
    label: '动态UI_添加控件',
    desc: '动态添加控件节点（JSON spec）',
    template: 'ui.add(win, "__root", { type="button", id="b1", text="点我" })',
  },
  {
    group: 'UI', name: 'ui.set', signature: 'ui.set(win, id, attrs)',
    label: '动态UI_改控件属性',
    desc: '批量修改控件属性（text/checked/selectedIndex/progress/bg/visibility...）',
    template: 'ui.set(win, "t", { text = "新文案" })',
  },
  {
    group: 'UI', name: 'ui.get', signature: 'ui.get(win, id) → attrs',
    label: '动态UI_读控件属性',
    desc: '读取控件当前属性（text/checked/selectedIndex...）',
    template: 'local a = ui.get(win, "edit1")\nprint(a.text)',
  },
  {
    group: 'UI', name: 'ui.tree', signature: 'ui.tree(win) → nodes',
    label: '动态UI_整树导出',
    desc: '整树导出（含 pager 各页；类型/当前值，一次 op 拿全树）',
    template: 'for _, n in ipairs(ui.tree(win)) do print(n.id, n.type) end',
  },
  {
    group: 'UI', name: 'ui.remove', signature: 'ui.remove(win, id) → bool',
    label: '动态UI_删除控件',
    desc: '删除控件节点',
    template: 'ui.remove(win, "b1")',
  },
  {
    group: 'UI', name: 'ui.bind / ui.unbind', signature: 'ui.bind(win, id, event, fn) / ui.unbind(win, id, event)',
    label: '动态UI_事件绑定',
    desc: '绑定/解绑控件事件回调（click/touch/textChange/check/itemSelect/dialog；每事件独立协程派发）',
    template: 'ui.bind(win, "b1", "click", function() print("clicked") end)',
  },
  {
    group: 'UI', name: 'ui.alert / ui.confirm', signature: 'ui.alert(title, msg[, onOk]) / ui.confirm(title, msg[, cb]) → bool',
    label: '动态UI_提示确认弹窗',
    desc: '提示 / 确认弹窗（默认同步阻塞等待；传回调则异步）',
    template: 'if ui.confirm("退出", "确定退出？") then exitScript() end',
  },
  {
    group: 'UI', name: 'ui.input / ui.sheet', signature: 'ui.input(opts[, cb]) → values / ui.sheet(title, items[, cb]) → {action,index}',
    label: '动态UI_输入选项弹窗',
    desc: '输入弹窗（title/label/text/inputType/fields）/ 选项弹窗（取消 index=-1）',
    template: 'local r = ui.input{ title="输入", label="昵称" }\nprint(r.values)',
  },
  {
    group: 'UI', name: 'ui.dialog / ui.dialogClose', signature: 'ui.dialog(opts) / ui.dialogClose(win, action)',
    label: '动态UI_通用弹窗',
    desc: '通用弹窗（type/title/msg/buttons/fields/items/content）与编程关闭',
    template: 'local w = ui.dialog{ type="alert", title="hi", msg="内容" }',
  },
  {
    group: 'UI', name: 'ui.settings', signature: 'ui.settings(opts) → {action, values}',
    label: '动态UI_设置面板',
    desc: '阻塞式设置面板：确定=收集 fields 存 config JSON 并返回；退出=确认后 exitScript 终止脚本',
    template: 'local r = ui.settings{ title="设置", config=ROOT_DIR.."/ui_set.json",\n  fields={ {id="acc", label="账号"} } }\nprint(r.values.acc)',
  },
  {
    group: 'UI', name: 'ui.saveProfile / ui.loadProfile', signature: 'ui.saveProfile(path[, win]) / ui.loadProfile(path[, win])',
    label: '动态UI_配置存取',
    desc: '按窗口树枚举可保存控件（edit/checkbox/switch/spinner/seek/rating）存/取配置 JSON；__ 前缀内部控件跳过',
    template: 'ui.saveProfile(ROOT_DIR .. "/ui.json")\nui.loadProfile(ROOT_DIR .. "/ui.json")',
  },
  {
    group: 'UI', name: 'ui.toast', signature: 'ui.toast(msg[, dur])',
    label: '动态UI_Toast提示',
    desc: '引擎 Toast 提示',
    template: 'ui.toast("完成")',
  },
  {
    group: 'UI', name: 'console.show / println / clear / dismiss / isShowing', signature: 'console.show() / console.println(s) / console.clear() / console.dismiss() / console.isShowing() → bool',
    label: '动态UI_日志悬浮窗',
    desc: '日志悬浮窗：显示 / 打印（缓冲 200 行）/ 清空 / 关闭 / 是否显示',
    template: 'console.show()\nconsole.println("调试信息")',
  },

  // ================= 常量表 =================
  {
    group: '常量', name: 'KeyCode', signature: 'KeyCode.BACK / HOME / VOLUME_UP / ENTER / DPAD_* / RECENT / POWER / DEL / SEARCH ...',
    label: '常量_按键码表',
    desc: '按键码常量表：HOME BACK MENU VOLUME_UP/DOWN POWER RECENT VOLUME_MUTE DPAD_* ENTER TAB DEL SEARCH CAMERA CALL ENDCALL',
    template: 'keyPress(KeyCode.BACK)',
  },
  {
    group: '常量', name: 'LayoutParamsFlag', signature: 'LayoutParamsFlag.NOT_FOCUSABLE / NOT_TOUCHABLE / NOT_TOUCH_MODAL',
    label: '常量_悬浮窗标志',
    desc: '悬浮窗布局参数标志',
    template: 'local flag = LayoutParamsFlag.NOT_FOCUSABLE',
  },
  {
    group: '常量', name: 'FIND_ORDER', signature: 'FIND_ORDER.UP / DOWN / LEFT / RIGHT（及组合 8 向）',
    label: '常量_查找方向',
    desc: 'findColor/findImage 的 order 查找方向常量',
    template: 'local x, y = findColor(0, 0, 720, 1280, 0xffff00, 0.9, FIND_ORDER.DOWN)',
  },

  // ================= 图色-扩展（a9183ad） =================
  {
    group: '图色', name: 'findImageAll', signature: 'findImageAll(x1, y1, x2, y2, imgNames, sim[, order]) → {{x,y},...}',
    label: '找图_全部',
    desc: '模板全部匹配位置（imgNames 竖线分隔多模板，命中任一即收集；含重叠匹配）',
    template: 'local pts = findImageAll(0, 0, 719, 1279, ROOT_DIR .. "/res/btn.png", 0.9)\nfor _, p in ipairs(pts) do print(p[1], p[2]) end',
  },
  {
    group: '图色', name: 'findPicAllPoint / findPicAll', signature: 'findPicAllPoint(x1, y1, x2, y2, pngNames, sim[, order]) → {{x,y},...}',
    label: '找图_全部别名',
    desc: '懒人找图全匹配别名（=findImageAll）',
    template: 'local pts = findPicAllPoint(0, 0, 719, 1279, "btn.png", 0.9)',
  },
  {
    group: '图色', name: 'getScreenFrame', signature: 'getScreenFrame() → data, w, h, stride, "RGBA"',
    label: '取色_整帧像素导出',
    desc: '整帧像素一次导出（captureScreen.getFrame 同源；data 长度 stride*h）',
    template: 'local data, w, h, stride, fmt = getScreenFrame()\nprint(#data, w, h, stride, fmt)',
  },
  {
    group: '图色', name: 'images.binarize', signature: 'images.binarize(img, threshold[, x, y, w, h]) → img',
    label: '图像_二值化',
    desc: '阈值二值化（0~255，灰度≥阈值置白；可选区域，区域外像素透传）',
    template: 'local bin = images.binarize(loadImage(ROOT_DIR .. "/res/src.png"), 128)',
  },
  {
    group: '图色', name: 'images.rotate', signature: 'images.rotate(img, angle) → img',
    label: '图像_任意角度旋转',
    desc: '任意角度旋转（逆映射最近邻，中心旋转，出界填黑）',
    template: 'local r = images.rotate(img, 30)',
  },
  {
    group: '图色', name: 'binaryImage / binaryRect', signature: 'binaryImage(img, threshold) / binaryRect(img, threshold, x,y,w,h)',
    label: '图像_二值化别名',
    desc: '懒人别名（双参数序自适应：binaryImage(threshold, img) 亦可）',
    template: 'local bin = binaryImage(img, 128)',
  },
  {
    group: '图色', name: 'rotateImage / scaleImage / getImage', signature: 'rotateImage(img, angle) / scaleImage(img, w, h) / getImage(path)',
    label: '图像_处理别名',
    desc: '懒人图片处理别名（=images.rotate/resize/loadImage）',
    template: 'local r = rotateImage(img, 90)\nlocal s = scaleImage(img, 100, 50)\nlocal im = getImage(ROOT_DIR .. "/res/a.png")',
  },

  // ================= 路径（a9183ad） =================
  {
    group: '文件', name: 'path.join', signature: 'path.join(...) → str',
    label: '路径_拼接',
    desc: '路径拼接（自动处理斜杠去重）',
    template: 'local p = path.join(ROOT_DIR, "res", "btn.png")',
  },
  {
    group: '文件', name: 'path.dirname / basename / extname', signature: 'path.dirname(p) / path.basename(p) / path.extname(p) → str',
    label: '路径_部分提取',
    desc: '目录部分 / 文件名 / 扩展名（带点）',
    template: 'print(path.dirname("/a/b/c.txt"), path.basename("/a/b/c.txt"), path.extname("/a/b/c.txt"))',
  },
  {
    group: '文件', name: 'path.normalize / resolve / getAbsolutePath', signature: 'path.normalize(p) / path.resolve(base, rel) / path.getAbsolutePath(p)',
    label: '路径_规范化解析',
    desc: '规范化（去 . ..）/ 基于 base 解析（默认 ROOT_DIR）/ 绝对路径',
    template: 'local p = path.getAbsolutePath("res/btn.png")',
  },
  {
    group: '文件', name: 'path.exists / isReadable / isAbsolutePath / stripAssetPrefix', signature: '各判定函数 → bool / str',
    label: '路径_存在判定',
    desc: '存在/可读判定；assets 前缀判定与剥离',
    template: 'if path.exists(p) then print("ok") end',
  },
  {
    group: '文件', name: 'path.getProjectDir / getAppFilesDir / resolveCandidates', signature: 'path.getProjectDir() / path.getAppFilesDir() / path.resolveCandidates(...)',
    label: '路径_目录获取',
    desc: '项目根（=ROOT_DIR）/ app files 目录 / 首个存在的候选路径',
    template: 'local f = path.resolveCandidates(ROOT_DIR .. "/res/a.png", ROOT_DIR .. "/res/b.png")',
  },

  // ================= 坐标（a9183ad，派大星对齐） =================
  {
    group: '设备', name: 'coordinate.getScreenSize', signature: 'coordinate.getScreenSize() → w, h',
    label: '设备_实际分辨率',
    desc: '实际屏幕分辨率',
    template: 'local w, h = coordinate.getScreenSize()',
  },
  {
    group: '设备', name: 'coordinate.setScreenSize', signature: 'coordinate.setScreenSize(vw, vh) → bool',
    label: '设备_虚拟分辨率设置',
    desc: '设虚拟分辨率基准（联动 setScreenScale，脚本按 vw 编写）',
    template: 'coordinate.setScreenSize(720, 1280)',
  },
  {
    group: '图色', name: 'coordinate.p2r / r2p', signature: 'coordinate.p2r(px, py) → x, y / coordinate.r2p(x, y) → px, py',
    label: '坐标_百分比像素互转',
    desc: '百分比(0~100) ↔ 像素；另有 p2rX/p2rY/r2pX/r2pY 与 p2rRect/r2pRect',
    template: 'local x, y = coordinate.p2r(50, 50)',
  },
  {
    group: '图色', name: 'coordinate.getCenter', signature: 'coordinate.getCenter() → x, y',
    label: '坐标_屏幕中心',
    desc: '屏幕中心像素；getCenterPercent() 返回 (50, 50)',
    template: 'local cx, cy = coordinate.getCenter()',
  },

  // ================= 定时器（a9183ad，派大星对齐） =================
  {
    group: '线程', name: 'timer.set / timer.clear', signature: 'timer.set(fn, ms[, repeat]) → id / timer.clear(id)',
    label: '定时器_周期执行',
    desc: '延时/周期执行（thread 协程驱动，可 clear）；别名 setTimeout/setInterval/clearTimeout/clearInterval',
    template: 'local id = setInterval(function() print("tick") end, 1000)\nsleep(3500)\nclearInterval(id)',
  },

  // ================= 事件（a9183ad，懒人对齐） =================
  {
    group: '事件', name: 'setXxxEventCallBack / sendXxxEvent', signature: 'setPluginEventCallBack(cb) / sendPluginEvent(name, data) → bool',
    label: '事件_进程内总线',
    desc: '进程内事件总线：user/plugin/notify 三通道（cb(name, data)；无回调返回 false）',
    template: 'setPluginEventCallBack(function(name, data) print("evt:", name) end)\nsendPluginEvent("start", 1)',
  },

  // ================= 媒体（a9183ad，派大星对齐） =================
  {
    group: '设备', name: 'media.playAudio', signature: 'media.playAudio(path[, loop]) → bool',
    label: '媒体_播放音频',
    desc: '播放本地音频文件（单实例 MediaPlayer；新播放自动释放旧实例）',
    template: 'media.playAudio(ROOT_DIR .. "/res/tip.mp3")',
  },
  {
    group: '设备', name: 'media 控制', signature: 'media.stopAudio() / pauseAudio() / resumeAudio() / seekTo(ms) / isPlaying() / getDuration() / getCurrentPosition() / setVolume(0~1)',
    label: '媒体_播放控制',
    desc: '播放控制；全局 playAudio(path)/stopAudio() 为懒人别名',
    template: 'while media.isPlaying() do sleep(200) end\nmedia.stopAudio()',
  },

  // ================= fhttp（a9183ad，派大星对齐） =================
  {
    group: '网络', name: 'fhttp.get / post', signature: 'fhttp.get(url[, headers]) / fhttp.post(url, body[, headers]) → body, code, headers',
    label: '网络_派大星GETPOST',
    desc: '派大星风格 HTTP；底层 http 服务',
    template: 'local body, code = fhttp.get("http://www.baidu.com")',
  },
  {
    group: '网络', name: 'fhttp.postJson / postForm', signature: 'fhttp.postJson(url, jsonStr[, headers]) / fhttp.postForm(url, formTable[, headers]) → body, code, headers',
    label: '网络_派大星JSON表单',
    desc: 'JSON / 表单 POST（自动 Content-Type）',
    template: 'local body, code = fhttp.postJson("http://api.example.com", \'{"a":1}\')',
  },
  {
    group: '网络', name: 'fhttp.put / delete / patch / head / options', signature: 'fhttp.put(url, body[, headers]) 等 → body, code, headers',
    label: '网络_派大星REST方法族',
    desc: 'REST 方法族',
    template: 'local body, code = fhttp.delete("http://api.example.com/item/1")',
  },
  {
    group: '网络', name: 'fhttp.download / upload', signature: 'fhttp.download(url, path[, headers]) → ok, code / fhttp.upload(url, path, fieldName[, fileName, form]) → body, code',
    label: '网络_派大星下载上传',
    desc: '流式下载落盘 / multipart 文件上传',
    template: 'fhttp.download("http://example.com/a.png", ROOT_DIR .. "/a.png")',
  },
  {
    group: '网络', name: 'fhttp.setTimeout / setProxy', signature: 'fhttp.setTimeout(ms) / fhttp.setProxy(host, port)',
    label: '网络_派大星超时代理',
    desc: '全局超时 / HTTP 代理（host 传 "" 清除）',
    template: 'fhttp.setTimeout(8000)',
  },
  {
    group: '网络', name: 'fhttp 异步句柄族', signature: 'fhttp.requestAsync(options) / fhttp.downloadAsync(url, path) / fhttp.uploadAsync(url, path, fieldName) → handle',
    label: '网络_派大星异步句柄',
    desc: '网络 IO 在独立 Java 线程，返回句柄：handle:isDone() 轮询、handle:get() → body, code, headers、handle:cancel() 中断。协程/并发安全，推荐替代回调族',
    template: 'local h = fhttp.requestAsync({url="http://www.baidu.com"})\nwhile not h:isDone() do sleep(15) end\nlocal body, code = h:get()',
  },
  {
    group: '网络', name: 'fhttp 回调异步族', signature: 'fhttp.getAsync(url, cb) 等 11 个 → cb(body, code, headers)',
    label: '网络_派大星异步回调',
    desc: '回调族（getAsync/postAsync/.../downloadAsync/uploadAsync）：最后参数为回调。注意：thread 协程内回调受 Java 调用限制，长任务用句柄族',
    template: 'fhttp.getAsync("http://www.baidu.com", function(body, code) print(code) end)',
  },

  // ================= WebSocket（a9183ad，libhv） =================
  {
    group: '网络', name: 'ws.serverStart / serverPoll', signature: 'ws.serverStart(port) → bool / ws.serverPoll([timeoutMs]) → "open",conn | "message",conn,msg | "close",conn | nil',
    label: '网络_WS服务端启停',
    desc: '启动 WS 服务端（端口占用返回 nil, err）/ 轮询事件',
    template: 'ws.serverStart(9000)\nlocal ev, conn, msg = ws.serverPoll(50)',
  },
  {
    group: '网络', name: 'ws.serverSend / serverBroadcast', signature: 'ws.serverSend(conn, msg[, binary]) / ws.serverBroadcast(msg[, binary]) → n',
    label: '网络_WS服务端发送',
    desc: '定向发送 / 广播（返回送达连接数）；serverStop() 停止、serverIsRunning()/serverConnCount() 状态',
    template: 'ws.serverSend(conn, "echo")',
  },
  {
    group: '网络', name: 'ws.clientConnect / clientPoll', signature: 'ws.clientConnect(url[, timeoutMs]) → cid / ws.clientPoll(cid[, timeoutMs]) → "open" | "message",data | "close" | nil',
    label: '网络_WS客户端',
    desc: '连接 WS 服务 / 轮询事件；clientSend(cid, msg[, binary]) 发送、clientIsOpen(cid)/clientClose(cid) 状态',
    template: 'local cid = ws.clientConnect("ws://127.0.0.1:9000/test")\nlocal ev, data = ws.clientPoll(cid, 3000)',
  },

  // ================= 插件（a9183ad，派大星对齐） =================
  {
    group: '插件与OCR', name: 'plugin.list / getLoaded', signature: 'plugin.list() → {{id,name,version,provides,loaded},...} / plugin.getLoaded() → {[id]=true}',
    label: '插件_清单',
    desc: '已安装插件清单 / 已加载映射',
    template: 'for _, p in ipairs(plugin.list()) do print(p.id, p.version) end',
  },
  {
    group: '插件与OCR', name: 'plugin.load / unload / uninstall', signature: 'plugin.load(id) → bool / plugin.unload(id) → bool / plugin.uninstall(id) → bool',
    label: '插件_装卸卸载',
    desc: '加载（安装未加载）/ 卸出（保留目录）/ 卸载（删目录）',
    template: 'plugin.load("com.autolua.plugin.ocr.demo")',
  },
  {
    group: '插件与OCR', name: 'plugin.install / getPluginPath / getSoPath', signature: 'plugin.install(zipPath) → bool, err / plugin.getPluginPath(id) / plugin.getSoPath(id) → str',
    label: '插件_安装路径',
    desc: '本地 zip 直装（sha256 校验）/ dex 与 so 目录',
    template: 'local ok, err = plugin.install("/sdcard/myplugin.zip")',
  },

  // ================= OCR（a9183ad，插件运行时） =================
  {
    group: '插件与OCR', name: 'ocr.isSupported / ocr.init', signature: 'ocr.isSupported() → bool / ocr.init() → bool',
    label: 'OCR_就绪初始化',
    desc: '是否安装 provides=["ocr"] 且实现 IOcrPlugin 的插件 / 初始化插件运行时',
    template: 'if not ocr.isSupported() then print("请先安装 OCR 插件") end',
  },
  {
    group: '插件与OCR', name: 'ocr.ocrText / ocrEx', signature: 'ocr.ocrText(x1, y1, x2, y2) → str / ocr.ocrEx(...) → {{text,x,y,w,h,confidence},...}',
    label: 'OCR_区域识别',
    desc: '区域识别（取帧→插件识别）；ocrExNew 块坐标相对基点',
    template: 'local text = ocr.ocrText(0, 0, 719, 399)\nlocal blocks = ocr.ocrEx(0, 0, 719, 399)',
  },
  {
    group: '插件与OCR', name: 'ocr.recognizeRaw', signature: 'ocr.recognizeRaw(frame, fw, fh, stride, x, y, w, h) → blocks',
    label: 'OCR_帧识别',
    desc: '直接识别帧数据（getScreenFrame 提供）',
    template: 'local frame, fw, fh, stride = getScreenFrame()\nlocal blocks = ocr.recognizeRaw(frame, fw, fh, stride, 0, 0, fw, fh)',
  },
  {
    group: '插件与OCR', name: 'ocr.recognizeFile', signature: 'ocr.recognizeFile(pngPath) → blocks',
    label: 'OCR_文件识别',
    desc: '识别 PNG 文件（调试用）',
    template: 'local blocks = ocr.recognizeFile(ROOT_DIR .. "/shot.png")',
  },

  // ================= 输入法（ime，root shell + 宿主广播） =================
  {
    group: '触控', name: 'ime.setText / commitText / inputLText', signature: 'ime.setText(text) → bool',
    label: '输入法_提交文本',
    desc: '提交文本到焦点输入框（经本引擎 IME 通道；imeLib 为别名）',
    template: 'ime.setText("hello")',
  },
  {
    group: '触控', name: 'ime.clearField / deleteChar', signature: 'ime.clearField() / ime.deleteChar([n]) → bool',
    label: '输入法_清空退格',
    desc: '清空输入框 / 退格删除 n 个字符（默认 1）',
    template: 'ime.clearField()',
  },
  {
    group: '触控', name: 'ime.showKeyboard / hideKeyboard / finishInput / keyEvent', signature: 'ime.showKeyboard() → bool / ime.keyEvent(keyCode) → bool',
    label: '输入法_键盘与按键',
    desc: '键盘显示/隐藏；keyEvent 经 IME 发送按键（Keycode 表）',
    template: 'ime.showKeyboard()\nime.keyEvent(Keycode.ENTER)',
  },
  {
    group: '触控', name: 'ime.isActive / isOurIMEActive / setAsDefaultIME / setCurrentIME', signature: 'ime.isActive() → bool / ime.setAsDefaultIME() → bool',
    label: '输入法_默认切换',
    desc: '键盘是否显示 / 本 IME 是否默认输入法 / root 切换默认输入法（写 settings 并回读验证）/ setCurrentIME(imeId) 切指定输入法',
    template: 'if not ime.isOurIMEActive() then ime.setAsDefaultIME() end',
  },
  {
    group: '触控', name: 'ime.showInputMethodPicker / getCurrentIMEName / openIMESettings', signature: 'ime.showInputMethodPicker() → bool / ime.getCurrentIMEName() → str',
    label: '输入法_选择器与设置',
    desc: '弹出系统输入法选择器 / 当前默认输入法 id / 打开系统输入法设置',
    template: 'print(ime.getCurrentIMEName())',
  },

  // ================= autoCollection 节点集合批量操作（派大星对齐） =================
  {
    group: '节点', name: 'autoCollection.find / create', signature: 'autoCollection.find(selector) → col / autoCollection.create(nodeArray) → col',
    label: '节点集合_查找构建',
    desc: '全窗口查找返回节点集合（findAll 同名）；由节点数组构建用 create；autoCollection.findOne(selector) 返回单节点',
    template: 'local col = autoCollection.find(text("确定"))\nif col:nonEmpty() then col:click() end',
  },
  {
    group: '节点', name: 'col 容器方法', signature: 'col:isEmpty() / col:nonEmpty() / col:size() / col:get(i) / col:first() / col:last() / col:toList() / col:each(fn) / col:contains(node)',
    label: '节点集合_容器方法',
    desc: '容器与遍历；each 回调 fn(node, i)；contains 按 text/desc/res/bounds 指纹比对',
    template: 'col:each(function(node, i) print(i, node.text) end)',
  },
  {
    group: '节点', name: 'col 二次筛选与批量设置', signature: 'col:find(selector) → col2 / col:findOne(selector) → node / col:setText(t) → n',
    label: '节点集合_二次筛选',
    desc: '对集合内各节点子树二次查找；setText 批量设置返回成功数',
    template: 'local btns = col:find(clickable(true))\nprint(btns:size())',
  },
  {
    group: '节点', name: 'col 批量动作', signature: 'col:click() / col:longClick() / col:scrollForward() / col:scrollUp() / col:expand() / col:collapse() / col:dismiss() / col:show() / col:performAction(action) → n',
    label: '节点集合_批量动作',
    desc: '遍历执行返回成功数；含 select/copy/cut/paste/focus/clearFocus/scrollBackward/scrollDown/scrollLeft/scrollRight',
    template: 'local n = col:click()  -- 逐个点击，返回成功数',
  },
  {
    group: '节点', name: 'node ACTION 扩展', signature: 'node:dismiss() / node:show() / node:pageUp() / node:imeEnter() / node:scrollTo(row, col) / node:setProgress(v) / node:performAction(action) → bool',
    label: '节点_动作扩展',
    desc: 'UiObject 全量 ACTION（dismiss/show/pageUp-Down-Left-Right/imeEnter/showTooltip/hideTooltip/accessibilityFocus 等；API 依框架版本）',
    template: 'node:scrollForward()\nnode:performAction(16)  -- ACTION_CLICK',
  },
  {
    group: '节点', name: 'node 光标与 HTML 导航', signature: 'node:nextAtMovementGranularity(g) / previousAtMovementGranularity(g) / nextHtmlElement(elem) / previousHtmlElement(elem) → bool',
    label: '节点_光标HTML导航',
    desc: '按移动粒度移动光标（字符/词/行等）/ WebView HTML 元素前后导航',
    template: 'node:nextAtMovementGranularity(1)',
  },

  // ================= mysql 数据库（懒人 connectSQL 系 + 对象式） =================
  {
    group: '数据库', name: 'connectSQL / closeSQL', signature: 'connectSQL(host, port, user, pwd, db[, timeout]) → bool, err / closeSQL() → bool',
    label: '数据库_连接关闭懒人式',
    desc: '连接 MySQL（懒人全局，隐式当前连接）；closeSQL 关闭当前连接',
    template: 'local ok, err = connectSQL("192.168.1.10", 3306, "root", "pwd", "mydb")\nif not ok then print(err) end',
  },
  {
    group: '数据库', name: 'executeQuerySQL / executeSQL', signature: 'executeQuerySQL(sql) → rows / executeSQL(sql) → affected',
    label: '数据库_查询执行懒人式',
    desc: '查询返回行数组（每行 [1..n]+[列名] 双键，NULL 为 nil）；执行返回影响行数；失败 nil, err',
    template: 'local rows = executeQuerySQL("select id,name from users limit 10")\nfor _, r in ipairs(rows) do print(r.id, r.name) end',
  },
  {
    group: '数据库', name: 'mysql.connect / conn 对象', signature: 'mysql.connect(host, port, user, pwd, db[, timeout]) → conn / conn:query(sql) / conn:execute(sql) / conn:ping() / conn:close()',
    label: '数据库_对象式连接',
    desc: '对象式多连接；mysql.escape(str) 转义；MySQL 4.1+（native/sha2 快速认证，完整认证需服务器改 mysql_native_password）',
    template: 'local conn = mysql.connect("127.0.0.1", 3306, "root", "pwd", "mydb")\nlocal rows = conn:query("select 1 as v")\nconn:close()',
  },

  // ================= event 事件总线 + project Fire 热更（派大星对齐） =================
  {
    group: '事件', name: 'event.on / off / removeListeners', signature: 'event.on(name, cb) → id / event.off(id) / event.removeListener(id) / event.removeListeners([name])',
    label: '事件_多播总线',
    desc: '多播事件总线（任意事件名）；回调异常隔离；addListener=on、removeListener=off 别名；Fire 热更完成走 event.on("hotfix_finish", cb)',
    template: 'local id = event.on("my_event", function(data) print(data) end)\nevent._emit("my_event", "hi")',
  },
  {
    group: '事件', name: 'event.onStopEvent / onToast / onNotification', signature: 'event.onStopEvent(cb) / event.onToast(cb) / event.onNotification(cb) → id',
    label: '事件_停止Toast通知',
    desc: '脚本停止多播；toast/通知事件 cb(pkg, className, text)（无障碍事件镜像转发，自动开关）',
    template: 'event.onToast(function(pkg, cls, text) print("toast:", text) end)',
  },
  {
    group: '事件', name: 'event.onTouch / onClick / getPermissionStatus', signature: 'event.onTouch(cb) / event.onClick(cb) / event.getPermissionStatus() → {root,accessibility,overlay,touch} / event.hasTouchPermission()',
    label: '事件_触摸与权限',
    desc: '触摸/点击事件（60ms 轮询尽力而为）；权限状态表；onVoice/onGesture 不支持返回 false',
    template: 'if event.hasTouchPermission() then print("可注入") end',
  },
  {
    group: '热更', name: 'project.hotfixFire 系', signature: 'project.hotfixFire([dir]) / hotfixFireFromUrl(url) / hotfixFireFromDir(dir) / hasPendingHotfix() / rollback() / clearCustomFire() / setHotFixCallback(cb)',
    label: '热更_Fire热更',
    desc: 'Fire 热更：pending 覆盖到项目根（被覆盖文件入 backup），rollback 回滚，完成触发 hotfix_finish 事件',
    template: 'project.hotfixFireFromUrl("http://srv/fire.zip")\nproject.setHotFixCallback(function(ok) print("hotfix:", ok) end)',
  },
  {
    group: '热更', name: 'project.getProjectDir / getHotfixDir', signature: 'project.getProjectDir() → str / project.getHotfixDir() → str',
    label: '热更_目录获取',
    desc: '工程根目录 / 热更目录（项目根/hotfix/，含 pending 待应用与 backup 回滚快照）',
    template: 'print(project.getProjectDir(), project.getHotfixDir())',
  },

  // ================= cv.Mat 图像加工（OpenCV 子集，零依赖） =================
  {
    group: 'cv图像', name: 'cv.createMat / captureMat / matToImage', signature: 'cv.createMat(rows, cols[, ch]) / cv.createMatFromData(rows, cols, data[, ch]) / cv.captureMat(x, y, w, h) → mat / cv.matToData(m) / cv.matToImage(m)',
    label: 'CV_Mat创建互通',
    desc: 'Mat 创建与互通（images.getMat/getGrayMat/toJavaMat）；mat: rows/cols/channels/type 属性 + get/put/clone/submat/setTo/release',
    template: 'local m = cv.captureMat(0, 0, 100, 100)\nlocal gray = cv.cvtColor(m, cv.COLOR_RGBA2GRAY)',
  },
  {
    group: 'cv图像', name: 'cv 二值/滤波/形态学', signature: 'cv.threshold(m, th, maxval[, inv]) / cv.adaptiveThreshold(m[, blk[, c]]) / cv.blur(m, k) / cv.gaussianBlur(m[, k]) / cv.medianBlur(m, k) / cv.dilate / erode / morphologyOpen / Close',
    label: 'CV_二值滤波形态学',
    desc: '验证码预处理管线：灰度 → 二值/自适应 → 滤波降噪 → 膨胀腐蚀开闭',
    template: 'local g = cv.toGray(m)\nlocal b = cv.threshold(g, 128, 255)\nlocal den = cv.medianBlur(b, 3)',
  },
  {
    group: 'cv图像', name: 'cv 连通域/统计/几何', signature: 'cv.extractContours(m[, minArea]) → {{x,y,w,h,area}} / cv.despeckle(m, minArea) / cv.minMaxLoc(m) / cv.mean(m) / cv.resize(m, rows, cols) / cv.rotateArbitrary(m, deg) / cv.cropBlackBorder(m[, th])',
    label: 'CV_连通域统计几何',
    desc: '轮廓外接框提取、小连通域清除、极值统计、几何变换；split/merge/bitwise 系/inRange/colorBinary/drawRect/Line/Circle',
    template: 'for _, box in ipairs(cv.extractContours(bw, 20)) do print(box.x, box.y, box.w, box.h) end',
  },
  {
    group: 'cv图像', name: 'cv 算术/位运算/几何补充/资源管理', signature: 'cv.add(a, b[, wa]) / cv.subtract(a, b) / cv.addWeighted(a, wa, b, wb, gamma) / cv.alphaBlend(a, b, ratio) / cv.equalizeHist(m) / cv.convertScaleAbs(m, alpha[, beta]) / cv.flip(m, mode) / cv.rotate(m, angle) / cv.resizeRatio(m, ratio) / cv.isAvailable() / cv.getVersion()',
    label: 'CV_算术位运算资源',
    desc: '图像融合与直方图均衡；bitwiseNot/And/Or/Xor、inRange(m, loR,loG,loB, hiR,hiG,hiB)、colorBinary；cropImage/createGrayMatFromRgba/keepComponentsBySize/sum/countNonZero；资源管理 getMatStats/forceGc/encodePng(m)→PNG串',
    template: 'local m3 = cv.addWeighted(m1, 0.7, m2, 0.3, 0)\nlocal png = cv.encodePng(m3)\nprint(cv.getVersion())',
  },
];
