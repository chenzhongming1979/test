--[[
Copyright (c) 2026 成都林深见鹿网络科技有限公司 All Rights Reserved.

GlobalStateManager - 全局状态管理单例
功能：统一管理所有全局变量的初始化、读取、写入和重置
设计模式：单例模式 + 观察者模式
]]

local GlobalStateManager = {}
GlobalStateManager.__index = GlobalStateManager

-- ============================================================
-- 单例实例
-- ============================================================
local instance = nil

-- ============================================================
-- 私有存储（不再暴露全局变量）
-- ============================================================
local storage = {
    runtime = {},    -- 原 JYConf
    network = {},    -- 原 JY_NET_CONF
    config = {},     -- 原 conf
    vars = {},       -- 原 vars
    taps = {},       -- 原 taps
    persist = {}     -- 原 config（持久化）
}

-- ============================================================
-- 默认值定义
-- ============================================================
local defaults = {
    runtime = {
        netParam = {
            scriptNo = nil,
            scriptId = nil,
            cdkey = nil,
            errorCount = 5,
            keyPortDelay = 30000
        },
        memoryParam = {},
        gameCheck = {},
        Thread = {},
        copyVars = {},
        appid = nil,
        accountFilename = "",
        ipStatus = true,
        ["卡屏次数"] = 0,
        timingTask = { startCrontab = 0, Crontab = 0 }
    },
    network = {
        scriptNo = "",
        cdkey = "",
        secretKey = "",
        authSecretKey = "",
        dev = "",
        version_params = {
            script_version = 1000,
            frame_version = 1000,
            version_remark = "版本说明"
        },
        token_params = { tm = 0, nm = 0, st = 0 }
    },
    config = {},
    vars = {},
    taps = {},
    persist = {}
}

-- ============================================================
-- 变更监听器
-- ============================================================
local listeners = {}

-- ============================================================
-- 私有工具函数
-- ============================================================

--- 深拷贝
local function deepCopy(src)
    if type(src) ~= "table" then return src end
    local copy = {}
    for k, v in pairs(src) do
        copy[deepCopy(k)] = deepCopy(v)
    end
    return copy
end

--- 深合并
local function deepMerge(target, source)
    for k, v in pairs(source) do
        if type(v) == "table" and type(target[k]) == "table" then
            deepMerge(target[k], v)
        else
            target[k] = deepCopy(v)
        end
    end
    return target
end

--- 清空表
local function clearTable(t)
    for k in pairs(t) do
        t[k] = nil
    end
end

--- 解析路径
local function parsePath(path)
    local keys = {}
    for key in string.gmatch(path, "[^%.]+") do
        table.insert(keys, key)
    end
    return keys
end

--- 触发变更监听
local function triggerChange(varType, path, oldValue, newValue)
    for _, listener in ipairs(listeners) do
        if listener.varType == varType or listener.varType == "*" then
            listener.callback(varType, path, oldValue, newValue)
        end
    end
end

-- ============================================================
-- 构造函数（私有）
-- ============================================================
function GlobalStateManager._new()
    local self = setmetatable({}, GlobalStateManager)
    return self
end

-- ============================================================
-- 单例获取
-- ============================================================
function GlobalStateManager.getInstance()
    if not instance then
        instance = GlobalStateManager._new()
    end
    return instance
end

-- ============================================================
-- 初始化方法
-- ============================================================

--- 初始化所有存储
function GlobalStateManager:initAll(userConfig)
    self:initRuntime()
    self:initNetwork()
    self:initConfig(userConfig.config or {})
    self:initVars(userConfig.vars or {})
    self:initTaps(userConfig.taps or {})
end

--- 初始化运行时状态
function GlobalStateManager:initRuntime()
    clearTable(storage.runtime)
    deepMerge(storage.runtime, defaults.runtime)
end

--- 初始化网络配置
function GlobalStateManager:initNetwork()
    clearTable(storage.network)
    deepMerge(storage.network, defaults.network)
end

--- 初始化框架配置
function GlobalStateManager:initConfig(data)
    clearTable(storage.config)
    deepMerge(storage.config, defaults.config)
    if type(data) == "table" then
        deepMerge(storage.config, data)
    end
end

--- 初始化账号变量
function GlobalStateManager:initVars(data)
    clearTable(storage.vars)
    deepMerge(storage.vars, defaults.vars)
    if type(data) == "table" then
        deepMerge(storage.vars, data)
    end
end

--- 初始化特征数据
function GlobalStateManager:initTaps(data)
    clearTable(storage.taps)
    if type(data) == "table" then
        deepMerge(storage.taps, data)
    end
end

-- ============================================================
-- 读取方法
-- ============================================================

--- 获取值（核心方法）
--- @param varType string 变量类型：runtime/network/config/vars/taps/persist
--- @param path string 键名或路径（如 "gameCheck.gameState"）
--- @param default any|nil 默认值
--- @return any
function GlobalStateManager:get(varType, path, default)
    local target = storage[varType]
    if not target then
        error(string.format("GlobalStateManager: 无效的变量类型 [%s]", varType))
    end

    -- 无路径时返回整个存储的副本
    if not path then
        return deepCopy(target)
    end

    local keys = parsePath(path)
    local current = target

    for _, key in ipairs(keys) do
        if type(current) ~= "table" or current[key] == nil then
            return default
        end
        current = current[key]
    end

    return current
end

--- 获取值（带类型断言）
--- @param varType string 变量类型
--- @param path string 路径
--- @param expectedType string 期望类型
--- @param default any 默认值
--- @return any
function GlobalStateManager:getTyped(varType, path, expectedType, default)
    local value = self:get(varType, path, default)
    if value ~= nil and type(value) ~= expectedType then
        return default
    end
    return value
end

--- 获取字符串
function GlobalStateManager:getString(varType, path, default)
    return self:getTyped(varType, path, "string", default or "")
end

--- 获取数字
function GlobalStateManager:getNumber(varType, path, default)
    return self:getTyped(varType, path, "number", default or 0)
end

--- 获取布尔值
function GlobalStateManager:getBoolean(varType, path, default)
    return self:getTyped(varType, path, "boolean", default or false)
end

--- 获取表
function GlobalStateManager:getTable(varType, path, default)
    return self:getTyped(varType, path, "table", default or {})
end

--- 检查键是否存在
--- @param varType string 变量类型
--- @param path string 路径
--- @return boolean
function GlobalStateManager:has(varType, path)
    return self:get(varType, path) ~= nil
end

-- ============================================================
-- 写入方法
-- ============================================================

--- 设置值（核心方法）
--- @param varType string 变量类型
--- @param path string 键名或路径
--- @param value any 值
--- @return boolean
function GlobalStateManager:set(varType, path, value)
    local target = storage[varType]
    if not target then
        error(string.format("GlobalStateManager: 无效的变量类型 [%s]", varType))
    end

    if not path then
        error("GlobalStateManager: 路径不能为空")
    end

    local keys = parsePath(path)
    local current = target

    -- 记录旧值（用于变更监听）
    local oldValue = self:get(varType, path)

    -- 遍历到倒数第二个键
    for i = 1, #keys - 1 do
        local key = keys[i]
        if type(current[key]) ~= "table" then
            current[key] = {}
        end
        current = current[key]
    end

    -- 设置最终值
    local finalKey = keys[#keys]
    current[finalKey] = value

    -- 触发变更监听
    triggerChange(varType, path, oldValue, value)

    return true
end

--- 批量设置
--- @param varType string 变量类型
--- @param data table 数据表
--- @return boolean
function GlobalStateManager:setBatch(varType, data)
    if type(data) ~= "table" then return false end
    local target = storage[varType]
    if not target then return false end

    for k, v in pairs(data) do
        target[k] = v
        triggerChange(varType, k, nil, v)
    end
    return true
end

--- 删除键
--- @param varType string 变量类型
--- @param path string 路径
--- @return boolean
function GlobalStateManager:remove(varType, path)
    local target = storage[varType]
    if not target then return false end

    local keys = parsePath(path)
    local current = target

    for i = 1, #keys - 1 do
        local key = keys[i]
        if type(current[key]) ~= "table" then
            return false
        end
        current = current[key]
    end

    local oldValue = current[keys[#keys]]
    current[keys[#keys]] = nil
    triggerChange(varType, path, oldValue, nil)

    return true
end

-- ============================================================
-- 重置方法
-- ============================================================

--- 重置指定类型到默认值
--- @param varType string 变量类型
function GlobalStateManager:reset(varType)
    if varType == "runtime" then
        self:initRuntime()
    elseif varType == "network" then
        self:initNetwork()
    elseif varType == "config" then
        self:initConfig()
    elseif varType == "vars" then
        self:initVars()
    elseif varType == "taps" then
        self:initTaps()
    elseif varType == "persist" then
        clearTable(storage.persist)
    end
end

--- 重置账号变量（账号切换时调用）
function GlobalStateManager:resetVars()

    local copyVars = self:get("runtime", "copyVars")

    if copyVars and next(copyVars) then
        self:initVars(copyVars)
    else
        self:initVars()
    end
end

-- ============================================================
-- 持久化方法
-- ============================================================

--- 加载持久化配置
--- @param filePath string|nil 配置文件路径
--- @return boolean
function GlobalStateManager:loadPersist(filePath)
    filePath = filePath or "/sdcard/Pictures/jy_config.json"

    if not frameSdk.fileExist(filePath) then
        return false
    end

    local file = io.open(filePath, "r")
    if not file then return false end

    local content = file:read("*a")
    file:close()

    if not content or content == "" then
        return false
    end

    local success, data = pcall(frameSdk.jsonLib.decode, content)
    if not success or type(data) ~= "table" then
        return false
    end

    clearTable(storage.persist)
    deepMerge(storage.persist, data)
    return true
end

--- 保存持久化配置
--- @param filePath string|nil 配置文件路径
--- @return boolean
function GlobalStateManager:savePersist(filePath)
    filePath = filePath or "/sdcard/Pictures/jy_config.json"

    local success, content = pcall(frameSdk.jsonLib.encode, storage.persist)
    if not success then return false end

    local file = io.open(filePath, "w")
    if not file then return false end

    file:write(content)
    file:close()
    return true
end

-- ============================================================
-- 变更监听
-- ============================================================

--- 添加变更监听器
--- @param varType string 变量类型（"*" 表示所有类型）
--- @param callback function 回调函数 function(varType, path, oldValue, newValue)
--- @return number 监听器ID
function GlobalStateManager:addListener(varType, callback)
    local id = #listeners + 1
    table.insert(listeners, {
        id = id,
        varType = varType,
        callback = callback
    })
    return id
end

--- 移除变更监听器
--- @param id number 监听器ID
function GlobalStateManager:removeListener(id)
    for i, listener in ipairs(listeners) do
        if listener.id == id then
            table.remove(listeners, i)
            return true
        end
    end
    return false
end

-- ============================================================
-- 调试方法
-- ============================================================

--- 导出所有存储（用于调试）
--- @return table
function GlobalStateManager:exportAll()
    return {
        runtime = deepCopy(storage.runtime),
        network = deepCopy(storage.network),
        config = deepCopy(storage.config),
        vars = deepCopy(storage.vars),
        taps = deepCopy(storage.taps),
        persist = deepCopy(storage.persist)
    }
end

--- 打印存储状态
--- @param varType string|nil 变量类型（nil 表示全部）
function GlobalStateManager:debug(varType)
    if varType then
        print(string.format("=== %s ===", varType))
        print(table.concat({tostring(storage[varType])}, "\n"))
    else
        for t, _ in pairs(storage) do
            self:debug(t)
        end
    end
end

return GlobalStateManager
