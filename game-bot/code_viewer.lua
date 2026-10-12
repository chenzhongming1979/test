-- ==================== 手机端源码查看器（精简版）====================
-- 在懒人精灵手机 App 中运行，浏览项目 Lua 源码

local CONFIG = {
    scriptDir = "/sdcard/lazygenius/scripts/"
}

-- ===== 文件列表 =====
local FILES = {
    { title = "找色模块",          path = CONFIG.scriptDir .. "color_finder.lua" },
    { title = "使用示例",           path = CONFIG.scriptDir .. "color_finder_example.lua" },
    { title = "主程序 (main.js)",    path = CONFIG.scriptDir .. "main.js" },
}

-- ===== 工具函数 =====

--- 读取整个文件内容
function readFile(path)
    local f = io.open(path, "r")
    if not f then return nil end
    local content = f:read("*a")
    f:close()
    return content
end

--- 统计行数
function countLines(str)
    local n = 0
    for _ in str:gmatch("[^\n]+") do
        n = n + 1
    end
    return n
end

--- HTML 安全转义
function htmlEscape(s)
    s = s:gsub("&", "&amp;"):gsub("<", "&lt;"):gsub(">", "&gt;")
    return s
end

-- ===== 生成代码浏览页 HTML =====
function buildViewerHtml(title, code)
    -- CSS
    local css = [[*{margin:0;padding:0;box-sizing:border-box}]]
    css = css .. [[html,body{height:100%;background:#1e1e1e;color:#d4d4d4;font-family:monospace;overflow:hidden}]]
    css = css .. [[.header{display:flex;align-items:center;padding:10px;background:#2d2d2d;border-bottom:1px solid #3c3c3c;min-height:44px}]]
    css = css .. [[.title-text{font-size:15px;color:#569cd6;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}]]
    css = css .. [[.code-area{overflow-y:auto;height:calc(100vh - 44px);-webkit-overflow-scrolling:touch}]]
    css = css .. [[.line{display:flex;padding:0 8px;border-bottom:1px solid #262626}]]
    css = css .. [[.num{color:#858585;width:40px;text-align:right;padding-right:12px;font-size:13px}]]
    css = css .. [[.code{flex:1;font-size:13px;line-height:18px;white-space:pre;margin:0;display:block}]]
    css = css .. [[.footer{position:fixed;bottom:0;left:0;right:0;background:#2d2d2d;padding:6px 12px;font-size:12px;color:#888;text-align:center;border-top:1px solid #3c3c3c}]]
    
    -- 格式化代码行（带行号 + 语法高亮）
    local lines_html = ""
    local lineno = 0
    
    for line in code:gmatch("[^\n]+") do
        lineno = lineno + 1
        
        local e = htmlEscape(line)
        
        -- 简单语法高亮
        if string.find(e, "^%s*--") then
            e = '<span style="color:#6a9955;font-style:italic">' .. e .. '</span>'
        elseif string.find(e, "^%s*(local|function|return|if|then|else|end|for|while|do|repeat|until|in|and|or|not) ") then
            e = '<span style="color:#569cd6;font-weight:bold">' .. e .. '</span>'
        elseif string.find(e, '^%s*"') or string.find(e, "^%s*'") then
            e = '<span style="color:#ce9178">' .. e .. '</span>'
        end
        
        lines_html = lines_html .. '<div class="line"><span class="num">' .. lineno .. '</span><pre class="code">' .. e .. '</pre></div>\n'
    end
    
    -- 组装完整 HTML
    return [[<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>]] .. css .. [[</style>
</head><body>
<div class="header"><span class="title-text">]] .. htmlEscape(title) .. [[</span></div>
<div class="code-area">]] .. lines_html .. [[</div>
<div class="footer">共 ]]</ .. tostring(countLines(code)) .. [[ 行 | 点击顶部标题栏收起</div>
<script>
var totalLines = document.querySelectorAll('.line').length;
document.querySelector('.footer').textContent = '共 ' + totalLines + ' 行';

// 点击空白处收起滚动条/搜索提示
document.addEventListener('click', function(e) {
    if (e.target.tagName === 'PRE' || e.target.className === 'code' || e.target.parentElement && e.target.parentElement.className === 'code') {
        var area = document.getElementById('codeArea');
        var rect = area.getBoundingClientRect();
        if (e.clientY > rect.bottom - 20) {
            // 点击底部时不滚动
        }
    }
});
</script>
</body></html>]]
end

-- ===== 全局状态 =====
local viewTitle = ""
local viewCode = ""

-- ===== 主页：文件列表 =====
function showHome()
    ui.newLayout("home", -1, -1)
    ui.setTitleText("home", "源浏览器")
    
    ui.addTextView("home", "tips", "点击按钮查看对应文件的源代码", -1, 40)
    ui.setTextColor("tips", "#888")
    ui.setTextSize("tips", 12)
    ui.newRow("home", "row_tips", -1, -2)
    ui.addLine("home", "line_top", -1, 2)
    
    for i, file in ipairs(FILES) do
        ui.addButton("home", "btn_" .. i, file.title .. "\n" .. file.path, -1, 60)
        ui.setTextColor("btn_" .. i, "#ddd")
        ui.setTextSize("btn_" .. i, 12)
        
        -- 用闭包绑定每个按钮的数据，避免路径转义问题
        loadstring('local fname = "' .. file.title .. '"\nlocal fpath = "' .. file.path .. '"\nfunction onView_' .. i .. '()'
                .. '  viewTitle = fname\n  viewCode = readFile(fpath)\n  if not viewCode then toast("文件不存在",0,0,30) return end\n  ui.dismiss("home")\n  showViewer()\nend')()
        
        ui.setOnClick("btn_" .. i, "onView_" .. i)
        
        if i < #FILES then
            ui.addLine("home", "div_" .. i, -1, 1)
            ui.newRow("home", "row_div_" .. i, -1, -2)
        end
    end
    
    -- 底部刷新按钮
    ui.addLine("home", "line_bottom", -1, 2)
    ui.newRow("home", "row_refresh", -1, -2)
    ui.addButton("home", "btn_refresh", "刷新文件列表", -1, 45)
    ui.setText("btn_refresh", "刷新文件列表")
    ui.setTextColor("btn_refresh", "#66bbdd")
    
    ui.show("home")
end

-- ===== 源码查看器 =====
function showViewer()
    local tmpPath = getSdPath() .. "/.src_viewer.html"
    local html = buildViewerHtml(viewTitle, viewCode)
    writeFile(tmpPath, html)
    
    ui.newLayout("viewer", -1, -1)
    ui.setWebView("viewer", "webView", "file://" .. tmpPath, -1, -1)
    
    ui.setOnBackEvent("viewer", 'function onBackFromViewer()'
            .. '  os.remove("' .. tmpPath .. '")'
            .. '  ui.dismiss("viewer")'
            .. '  showHome()'
            .. 'end')
    
    ui.show("viewer")
end

-- ===== 启动 =====
showHome()
