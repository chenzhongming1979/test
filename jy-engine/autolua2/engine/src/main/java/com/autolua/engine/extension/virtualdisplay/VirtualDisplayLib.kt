package com.autolua.engine.extension.virtualdisplay

import com.autolua.engine.base.LuaContext
import com.autolua.engine.base.LuaEnvContributor
import com.autolua.engine.base.LuaObjectAdapter
import com.autolua.engine.input.inject.ShizukuInjectorChannel

/**
 * 虚拟屏 Lua 口（注入为 Lua 全局 `virtualDisplay` 表，虚拟屏任务书 §5）：
 *
 * - Java 原语层（本类 contribute 注册，仅脚本内部使用）：
 *   `virtualDisplay._createJava(w, h, dpi)` → VdHandle（Shizuku 建屏 → attachFrameSink → mmap）；
 *   `virtualDisplay._listJava()` → {VdHandle,...} 当前实例数组
 * - Lua 组合层（VD_LUA 内嵌串，contribute 时 loadBuffer + pcall 执行）：
 *   `virtualDisplay.create{width=,height=,dpi=}` / `virtualDisplay.list()` 与
 *   vd 对象方法全集——runApp/tap/longTap/swipe/destroy/getSize/getDisplayId 直达 Java；
 *   findColor/findColorAllPoint/findFeatureAll/findImage/findImageAll/getScreenPixel/
 *   getScreenFrame/saveScreen 由 `vd:__frame()` 原始帧 → `images.fromData` → Bitmap 同名
 *   方法拼装（Bitmap 方法集 = 3rd/autolua_vision 实际注册面：getColor/getColorCount/
 *   isColor/whichColor/findColor/isFeature/findFeature/isImage/whichImage/findImage/
 *   save/clone/getSize；findColorAllPoint/findFeatureAll/findImageAll 不在 Bitmap 上，
 *   组合层以窗口推进扫描实现，收集序固定行优先）。
 *
 * create 失败一律抛可读 Lua 错误（引导 Shizuku 授权；root 直建路径属任务书 §6.1 未落地，
 * 错误信息注明），不静默、不返回假对象。三环境注册：root 主引擎 Server.kt initEngine /
 * IDE 沙箱 MainApp ideExecutor.registerService / 本地降级引擎 buildLocalSandboxEngine。
 */
open class VirtualDisplayLib : LuaEnvContributor {

  override fun contribute(ctx: LuaContext) {
    ctx.createTable(0, 4)
    put(ctx, "_createJava") { L ->
      val w = optInt(L, 2)
      val h = optInt(L, 3)
      val dpi = optInt(L, 4)
      try {
        ctx.pushValue(create(w, h, dpi), Any::class.java)
        1
      } catch (t: Throwable) {
        ctx.pushNil()
        ctx.push(t.message ?: t.toString())
        2
      }
    }
    put(ctx, "_listJava") { L ->
      val list = listHandles()
      ctx.createTable(list.size, 0)
      for ((i, h) in list.withIndex()) {
        ctx.push((i + 1).toLong())
        ctx.pushValue(h, Any::class.java)
        ctx.setTable(-3)
      }
      1
    }
    ctx.setGlobal("virtualDisplay")
    try {
      ctx.loadBuffer(VD_LUA.toByteArray(Charsets.UTF_8), "=virtualdisplay", LuaContext.CodeMode.TEXT)
      val r = ctx.pcall(0, 0, 0)
      if (r != 0) {
        val msg = try { ctx.toString(-1) } catch (t: Throwable) { "pcall code=$r" }
        android.util.Log.w("VirtualDisplayLib", "virtualdisplay.lua exec failed: $msg")
        ctx.pop(1)
      }
    } catch (t: Throwable) {
      android.util.Log.w("VirtualDisplayLib", "virtualdisplay.lua load failed: $t")
    }
  }

  private fun optInt(ctx: LuaContext, index: Int, def: Int = -1): Int =
    if (ctx.type(index) == LuaContext.ValueType.NUMBER) ctx.toLong(index).toInt() else def

  private fun put(ctx: LuaContext, name: String, body: (LuaContext) -> Int) {
    ctx.push(name)
    ctx.push(adapter(body))
    ctx.setTable(-3)
  }

  private fun adapter(body: (LuaContext) -> Int): LuaObjectAdapter = object : LuaObjectAdapter {
    override fun invoke(L: LuaContext): Int = body(L)
    override fun index(L: LuaContext): Int = 0
    override fun newIndex(L: LuaContext): Int = 0
    override fun call(L: LuaContext, name: String): Int = 0
    override fun release(L: LuaContext) {}
  }

  companion object {
    private const val CREATE_GUIDE =
      "no Shizuku service (please start Shizuku and authorize this app, " +
        "or run engine as root; root direct-create path is not implemented yet - see plan §6.1)"

    private val handles = LinkedHashMap<Int, VdHandle>()
    private var nameSeq = 0
    private val channel: ShizukuInjectorChannel by lazy { ShizukuInjectorChannel() }

    /**
     * 建屏 + attach 帧泵（Java 侧原语）。任一步失败抛 IllegalStateException（可读信息）。
     */
    @JvmStatic
    fun create(width: Int, height: Int, dpi: Int): VdHandle {
      if (width <= 0 || height <= 0) {
        throw IllegalArgumentException("width/height must be positive (got ${width}x${height})")
      }
      val ch = channel
      val seq = synchronized(handles) { ++nameSeq }
      val displayId = ch.createVirtualDisplay("autolua-vd-$seq", width, height, dpi)
      if (displayId < 0) {
        throw IllegalStateException(CREATE_GUIDE)
      }
      val dims = IntArray(2)
      val fds = ch.attachFrameSink(displayId, dims)
      if (fds == null) {
        ch.destroyVirtualDisplay(displayId)
        throw IllegalStateException("attachFrameSink failed (displayId=$displayId); " +
          "UserService version mismatch? restart Shizuku server and retry")
      }
      val client = VdFrameClient.attach(ch, displayId, width, height)
      if (client == null) {
        ch.destroyVirtualDisplay(displayId)
        throw IllegalStateException("frame sink mmap failed (displayId=$displayId)")
      }
      val handle = VdHandle(ch, client, displayId, width, height)
      synchronized(handles) { handles[displayId] = handle }
      return handle
    }

    @JvmStatic
    fun listHandles(): List<VdHandle> =
      synchronized(handles) { handles.values.filter { !it.isDestroyed } }

    @JvmStatic
    fun remove(handle: VdHandle) {
      synchronized(handles) { handles.remove(handle.displayId) }
    }

    // ------------------------------------------------------------------
    // Lua 组合层（vd 对象方法全集；依赖全局 images 与 virtualDisplay._createJava/_listJava）
    // ------------------------------------------------------------------
    private val VD_LUA = """
-- ============ virtualDisplay Lua 组合层（VirtualDisplayLib 注入） ============
-- 依赖：virtualDisplay._createJava/_listJava（Java 原语）+ images（C++ image_lib）
-- Bitmap 方法集 = 3rd/autolua_vision 实际注册面（lua_vision.cc COMMON_BITMAP_METHODS）：
--   getColor/getColorCount/isColor/whichColor/findColor/isFeature/findFeature/
--   isImage/whichImage/findImage/save/clone/getSize
-- findColorAllPoint/findFeatureAll/findImageAll 不在 Bitmap 上（仅主屏全局注册），
-- 组合层以窗口推进扫描实现，收集序固定行优先（02 §20.2 已标注）。

local function _vd_frame(h, who)
    local data, w, hh = h:__frame()
    if data == nil then
        error(who .. ": frame unavailable (no frame rendered yet?)", 3)
    end
    return data, w, hh
end

local function _vd_bitmap(h, who)
    local data, w, hh = _vd_frame(h, who)
    return images.fromData(w, hh, data), w, hh
end

-- 全部匹配收集：行优先游标推进。内部窗口查找强制 order=0（READ_ORDER.UP_DOWN_LEFT_RIGHT
-- = 枚举首成员 0，见 3rd/autolua_vision/src/vision_util.h:40；注意 order=1 是
-- UP_DOWN_RIGHT_LEFT 行内从右往左，不满足"窗内首点=游标后第一个行优先命中"），
-- 保证不漏采不重采；用户 order 不透传内部窗口（收集序固定行优先，02 §20.2 已声明）。
local function _vd_scanAll(x1, y1, x2, y2, findFn)
    local pts = {}
    local ry, rx = y1, x1
    while ry <= y2 do
        local fx, fy = findFn(rx, ry, x2, y2)
        if not fx or fx < 0 then break end
        pts[#pts + 1] = { fx, fy }
        -- 行优先游标推进：命中点在下方行则直接跳到命中行；游标越出行尾归一化到下一行首
        if fy > ry then
            ry = fy
        end
        rx = fx + 1
        if rx > x2 then
            ry = ry + 1
            rx = x1
        end
    end
    return pts
end

local function _vd_wrap(h)
    if not h then return nil end
    local vd = {}
    vd.getDisplayId = function(self) return h:getDisplayId() end
    vd.getSize = function(self) return h:getSize() end

    vd.runApp = function(self, pkg, activity)
        local code = h:runApp(pkg or "", activity or "")
        if code ~= 0 then
            error("vd:runApp failed (code=" .. code .. ", pkg=" .. tostring(pkg) .. ")", 2)
        end
        return true
    end

    vd.tap = function(self, x, y)
        local code = h:tap(x, y)
        if code ~= 0 then
            error("vd:tap failed (code=" .. code .. ", displayId=" ..
                h:getDisplayId() .. ")", 2)
        end
        return true
    end

    vd.longTap = function(self, x, y, ms)
        local code = h:longTap(x, y, ms or 0)
        if code ~= 0 then
            error("vd:longTap failed (code=" .. code .. ", displayId=" ..
                h:getDisplayId() .. ")", 2)
        end
        return true
    end

    vd.swipe = function(self, x1, y1, x2, y2, durationMs)
        local code = h:swipe(x1, y1, x2, y2, durationMs or 0)
        if code ~= 0 then
            error("vd:swipe failed (code=" .. code .. ", displayId=" ..
                h:getDisplayId() .. ")", 2)
        end
        return true
    end

    vd.destroy = function(self)
        if not h:destroy() then
            error("vd:destroy failed (displayId=" .. h:getDisplayId() .. ")", 2)
        end
        return true
    end

    -- 帧原始字节（组合层内部原语暴露，返回 data, w, h）
    vd.__frame = function(self)
        return _vd_frame(h, "vd:__frame")
    end

    -- 原始帧 → data, w, h, stride, "RGBA"（stride = w*4，同全局 getScreenFrame 签名）
    vd.getScreenFrame = function(self)
        local data, w, hh = _vd_frame(h, "vd:getScreenFrame")
        return data, w, hh, w * 4, "RGBA"
    end

    -- 区域像素二维数组（每行一表，元素 0xRRGGBB；签名同全局 getScreenPixel）
    vd.getScreenPixel = function(self, x1, y1, x2, y2)
        local bmp = _vd_bitmap(h, "vd:getScreenPixel")
        local rows = {}
        for y = y1, y2 do
            local row = {}
            for x = x1, x2 do
                row[#row + 1] = bmp:getColor(x, y)
            end
            rows[#rows + 1] = row
        end
        return rows
    end

    vd.findColor = function(self, x1, y1, x2, y2, color, sim, order)
        local bmp = _vd_bitmap(h, "vd:findColor")
        return bmp:findColor(x1, y1, x2, y2, color, sim, order)
    end

    vd.findColorAllPoint = function(self, x1, y1, x2, y2, color, sim, order)
        local bmp = _vd_bitmap(h, "vd:findColorAllPoint")
        return _vd_scanAll(x1, y1, x2, y2, function(ax, ay, bx, by)
            -- order 写死 0（UP_DOWN_LEFT_RIGHT）：游标推进依赖行优先语义，用户 order 忽略
            return bmp:findColor(ax, ay, bx, by, color, sim, 0)
        end)
    end

    vd.findFeatureAll = function(self, x1, y1, x2, y2, featureStr, sim, order)
        local bmp = _vd_bitmap(h, "vd:findFeatureAll")
        return _vd_scanAll(x1, y1, x2, y2, function(ax, ay, bx, by)
            return bmp:findFeature(ax, ay, bx, by, featureStr, sim, 0)
        end)
    end

    -- 全屏模板找图（img 同全局：路径串，竖线分隔多路径轮询；返回 x, y, 序号）
    vd.findImage = function(self, img, sim, order)
        local bmp, w, hh = _vd_bitmap(h, "vd:findImage")
        local x, y, idx = bmp:findImage(0, 0, w - 1, hh - 1, img, sim, order or 1)
        if x and x >= 0 then
            return x, y, idx
        end
        return -1, -1, -1
    end

    vd.findImageAll = function(self, x1, y1, x2, y2, imgNames, sim, order)
        local bmp = _vd_bitmap(h, "vd:findImageAll")
        return _vd_scanAll(x1, y1, x2, y2, function(ax, ay, bx, by)
            -- order 写死 0（UP_DOWN_LEFT_RIGHT），理由同 findColorAllPoint
            local fx, fy = bmp:findImage(ax, ay, bx, by, imgNames, sim, 0)
            if fx and fx >= 0 then return fx, fy end
            return -1, -1
        end)
    end

    -- 整屏保存（仅 png，同全局 saveScreen 约束）
    vd.saveScreen = function(self, path, fmt)
        if fmt and fmt ~= "png" then
            return false, "vd:saveScreen: jpeg not supported (png only)"
        end
        local bmp = _vd_bitmap(h, "vd:saveScreen")
        return images.save(bmp, path)
    end

    return vd
end

if not images then
    error("virtualdisplay.lua: global images unavailable (inject order broken)", 0)
end

-- virtualDisplay.create{width=, height=, dpi=} → vd 对象（多实例并存）
virtualDisplay.create = function(cfg)
    if type(cfg) ~= "table" then
        error("virtualDisplay.create: table {width=,height=,dpi=} expected", 2)
    end
    local w = tonumber(cfg.width)
    local hh = tonumber(cfg.height)
    if not w or not hh or w <= 0 or hh <= 0 then
        error("virtualDisplay.create: width/height required (positive integers)", 2)
    end
    local dpi = tonumber(cfg.dpi) or 320
    local ok, hOrErr, err2 = pcall(function()
        return virtualDisplay._createJava(math.floor(w), math.floor(hh), math.floor(dpi))
    end)
    if not ok then
        error("virtualDisplay.create: " .. tostring(hOrErr), 2)
    end
    if not hOrErr then
        error("virtualDisplay.create: " .. tostring(err2 or "unknown failure"), 2)
    end
    return _vd_wrap(hOrErr)
end

-- 当前全部实例数组 {vd,...}
virtualDisplay.list = function()
    local arr = virtualDisplay._listJava()
    local out = {}
    for _, h in ipairs(arr or {}) do
        out[#out + 1] = _vd_wrap(h)
    end
    return out
end
""".trimIndent()
  }
}
