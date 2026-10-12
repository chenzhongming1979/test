--[[
G - 全局状态快捷访问模块
提供简洁的访问接口，减少代码量
]]

local GSM = require("JY.config.GlobalStateManager").getInstance()

local G = {}

-- ============================================================
-- 快捷读取方法
-- ============================================================

--- 读取运行时状态（原 JYConf）
--- @param path string 路径
--- @param default any|nil 默认值
--- @return any
function G.runtime(path, default)
    return GSM:get("runtime", path, default)
end

--- 读取网络配置（原 JY_NET_CONF）
--- @param path string 路径
--- @param default any|nil 默认值
--- @return any
function G.network(path, default)
    return GSM:get("network", path, default)
end

--- 读取框架配置（原 conf）
--- @param path string 路径
--- @param default any|nil 默认值
--- @return any
function G.config(path, default)
    return GSM:get("config", path, default)
end

--- 读取账号变量（原 vars）
--- @param path string 路径
--- @param default any|nil 默认值
--- @return any
function G.vars(path, default)
    return GSM:get("vars", path, default)
end

--- 读取特征数据（原 taps）
--- @param path string 路径
--- @param default any|nil 默认值
--- @return any
function G.taps(path, default)
    return GSM:get("taps", path, default)
end

--- 读取持久化存储（原 config）
--- @param path string 路径
--- @param default any|nil 默认值
--- @return any
function G.persist(path, default)
    return GSM:get("persist", path, default)
end

-- ============================================================
-- 快捷写入方法
-- ============================================================

--- 写入运行时状态
--- @param path string 路径
--- @param value any 值
function G.setRuntime(path, value)
    return GSM:set("runtime", path, value)
end

--- 写入网络配置
--- @param path string 路径
--- @param value any 值
function G.setNetwork(path, value)
    return GSM:set("network", path, value)
end

--- 写入框架配置
--- @param path string 路径
--- @param value any 值
function G.setConfig(path, value)
    return GSM:set("config", path, value)
end

--- 写入账号变量
--- @param path string 路径
--- @param value any 值
function G.setVars(path, value)
    return GSM:set("vars", path, value)
end

--- 写入特征数据
--- @param path string 路径
--- @param value any 值
function G.setTaps(path, value)
    return GSM:set("taps", path, value)
end

--- 写入持久化存储
--- @param path string 路径
--- @param value any 值
function G.setPersist(path, value)
    return GSM:set("persist", path, value)
end

-- ============================================================
-- 通用方法
-- ============================================================

--- 通用读取
--- @param fullPath string 完整路径（如 "runtime.gameCheck.gameState"）
--- @param default any|nil 默认值
--- @return any
function G.get(fullPath, default)
    local firstDot = string.find(fullPath, "%.")
    if not firstDot then
        error("G.get: 路径格式错误，应为 'type.path' 格式")
    end

    local varType = string.sub(fullPath, 1, firstDot - 1)
    local subPath = string.sub(fullPath, firstDot + 1)

    return GSM:get(varType, subPath, default)
end

--- 通用写入
--- @param fullPath string 完整路径
--- @param value any 值
--- @return boolean
function G.set(fullPath, value)
    local firstDot = string.find(fullPath, "%.")
    if not firstDot then
        error("G.set: 路径格式错误，应为 'type.path' 格式")
    end

    local varType = string.sub(fullPath, 1, firstDot - 1)
    local subPath = string.sub(fullPath, firstDot + 1)

    return GSM:set(varType, subPath, value)
end

-- ============================================================
-- 导出管理器实例
-- ============================================================
G.GSM = GSM

return G
