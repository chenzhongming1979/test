--[[ jyframe.lua —— jy-frame 框架装载引导（main.lua 唯一引导行）
职责：工程根探测 → require 劫持 → 品牌 → main.config 预写 → frameSdk/JY 装载。
云控通道在引擎内置全局 cloud（纯 WS 通道，无协议语义）；协议参考实现在 jy_protocol.lua；
本文件不做任何 WS/协议相关事情。返回 brand 表（app_name/secret 等，无 brand.json 时 {}）。
--]]

local function bootstrap()
    -- 1) 工程根：__SCRIPT_DIR__（引擎 run_script 注入）优先，debug.getinfo 兜底
    local root = rawget(_G, "__SCRIPT_DIR__")
    if root == nil or root == "" then
        root = "."
        local okS, info = pcall(debug.getinfo, 1, "S")
        if okS and type(info) == "table" and type(info.source) == "string" then
            local dir = info.source:match("^@(.*)[/\\][^/\\]+$")
            if dir ~= nil and dir ~= "" then root = dir end
        end
    end
    root = root:gsub("[/\\]$", "")
    print("[jyf] 工程根: " .. root)

    -- 2) require 劫持：引擎 require 走 CodeProvider（外部工程根），工程内包 require 不到；
    --    frameSdk-autolua2[.xxx] / JY[.xxx] / Funs 前缀 → dofile 本地文件（带缓存）
    local rawRequire = require
    local modCache = {}
    local function hookRequire(name)
        if type(name) ~= "string" then return rawRequire(name) end
        local norm = name:gsub("[/\\]", ".")
        local short = nil
        if norm:sub(1, #"frameSdk-autolua2") == "frameSdk-autolua2" then
            short = norm:sub(#"frameSdk-autolua2" + 1)
            if short:sub(1, 1) == "." then short = short:sub(2) end
            if short == "" then short = "init" end
            short = "frameSdk-autolua2/" .. short
        elseif norm:sub(1, 3) == "JY." or norm == "JY" then
            short = norm:gsub("%.", "/")
        elseif norm == "Funs" then
            short = "Funs"
        elseif norm == "JUI" then
            short = "JUI"
        end
        if short == nil then return rawRequire(name) end
        if modCache[short] == nil then
            print("[jyf] loading: " .. short)
            local ok, mod = pcall(dofile, root .. "/" .. short .. ".lua")
            if not ok then
                error("hookRequire 装载失败 [" .. name .. "]: " .. tostring(mod), 2)
            end
            modCache[short] = mod
            print("[jyf] loaded: " .. short)
        end
        return modCache[short]
    end
    _G.require = hookRequire
    pcall(function()
        local pathm = rawget(_G, "path")
        if pathm ~= nil and pathm.setProjectDir ~= nil then pathm.setProjectDir(root) end
    end)

    -- 3) 品牌（贴牌流水线：brand.json 随工程导入落工程根）；secret 供 cloud.open 加密
    local json = rawget(_G, "jsonLib") or rawget(_G, "json")
    local fe = rawget(_G, "fileExist")
    local brand = nil
    if fe ~= nil and json ~= nil then
        local okc, exists = pcall(fe, root .. "/brand.json")
        if okc and exists == true then
            local f = io.open(root .. "/brand.json", "r")
            if f ~= nil then
                local content = f:read("*a")
                f:close()
                local okJ, b = pcall(json.decode, content)
                if okJ and type(b) == "table" then brand = b end
            end
        end
    end
    if brand ~= nil and brand.app_name ~= nil and brand.app_name ~= "" then
        print("[jyf] 品牌: " .. tostring(brand.app_name))
    end

    -- 3) main.config 预写（getUISet 查询命中静态 UI 分支，无 UI 场景绕过 exitScript）；
    --    chmod 666：root 引擎进程写入的文件若不改权限，IDE 通道（app 进程）推送覆盖会 Permission denied
    if fe ~= nil and json ~= nil then
        local okc, exists = pcall(fe, root .. "/main.config")
        if okc and exists ~= true then
            local f = io.open(root .. "/main.config", "w")
            if f ~= nil then
                f:write(json.encode({
                    ["cloud"] = {
                        ["记录日志"] = false, ["轮号休息"] = 0, ["账号文件"] = "cloud",
                        ["改机"] = false, ["单号模式"] = false, ["游戏版本"] = 1,
                    },
                }))
                f:close()
                pcall(os.execute, "chmod 666 " .. root .. "/main.config")
                print("[jyf] main.config 预写完成")
            end
        end
    end

    -- 5) frameSdk init + 工作目录校正 + JY 核心装载
    local frameSdk = hookRequire("frameSdk-autolua2")
    local st0 = rawget(_G, "systemTime")
    local tt0 = st0 and st0() or 0
    frameSdk.init({ bootstrapPermissions = true })
    local tt1 = st0 and st0() or 0
    if tt0 > 0 and (tt1 - tt0) > 1000 then
        print(("[jyf] frameSdk.init 耗时 %dms（>1s 请查 init 内部分段日志）"):format(tt1 - tt0))
    end
    pcall(frameSdk.setWorkPath, root)
    print("[jyf] frameSdk v" .. tostring(frameSdk.VERSION) .. " 就绪，工作目录 " .. tostring(frameSdk.getWorkPath()))

    hookRequire("JY.FrameInit")
    if type(EnvInit) ~= "function" then
        error("EnvInit 未注册（JY 核心装载失败）", 0)
    end
    print("[jyf] JY 核心就绪（EnvInit/G 已注册）")

    return brand or {}
end

local ok, ret = pcall(bootstrap)
if not ok then
    print("[jyf] MAIN-TERMINATED: " .. tostring(ret))
    return nil
end
return ret
