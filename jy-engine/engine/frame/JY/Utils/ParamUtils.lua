--[[
    模块：参数工具 (ParamUtils)
    版本：2.0.0
    功能：动态参数获取与设置、任务状态管理

    Copyright (c) 2024 成都林深见鹿网络科技有限公司
    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

    本软件仅供个人学习研究使用。如需商业授权，请联系版权方.
--]]

-- ==================== 模块依赖 ====================
local JY = require("JY.Utils.JY")
local jy_ui = require("JY.Utils.UIUtils")
local jy_frame = require("JY.Utils.FrameUtils")

-- ==================== 常量定义 ====================
local CONST = {
    -- 参数类型标识
    TYPE_VARS = "vars",
    TYPE_CONF = "conf",
    TYPE_CONFIG = "config",
    TYPE_THREAD = "Thread",
    TYPE_TASK_STATE = "任务状态",
    TYPE_THREAD_VAR = "线程内存变量",
    TYPE_READ_UI = "readUI",
    TYPE_CSV = "CSV",

    -- 数字类型索引映射
    TYPE_INDEX = {
        [1] = "vars", [2] = "conf", [3] = "config",
        [4] = "Thread", [5] = "任务状态", [6] = "线程内存变量",
        [7] = "readUI", [8] = "CSV"
    },

    -- 时间单位转换倍率（秒）
    TIME_UNITS = {
        ["S"] = 1, ["秒"] = 1,
        ["M"] = 60, ["分"] = 60,
        ["H"] = 3600, ["时"] = 3600,
        ["d"] = 86400, ["天"] = 86400
    },

    -- 动态值生成器
    DYNAMIC_VALUES = {
        ["day"] = function() return os.date("%d") end,
        ["ms"] = function() return frameSdk.systemTime() end,
        ["time"] = function() return os.time() end
    }
}

-- ==================== 模块初始化 ====================
local jy_param = {}

-- ==================== 类型处理器（模块级缓存，避免重复创建） ====================

--- 获取参数类型处理器
local TYPE_HANDLERS_GET = {
    ["vars"] = function(key1, key2, varl, defVal)
        if IsEmpty(key1) then
            return defVal
        end
        -- 使用路径方式访问，支持嵌套键
        local path = IsEmpty(key2) and key1 or (key1 .. "." .. key2)
        local ret = G.vars(path)
        return Iif(IsEmpty(ret) and not IsEmpty(defVal), defVal, ret)
    end,

    ["conf"] = function(key1, key2, varl, defVal)
        if IsEmpty(key1) then
            return defVal
        end
        -- 使用路径方式访问，支持嵌套键
        local path = IsEmpty(key2) and key1 or (key1 .. "." .. key2)
        local ret = G.config(path)
        return Iif(IsEmpty(ret) and not IsEmpty(defVal), defVal, ret)
    end,

    ["config"] = function(key1, key2, varl, defVal)
        return JY.jy_file.fileGetUser(key1, key2 or defVal, G.config("当前账号") or "")
    end,

    ["Thread"] = function(key1, key2, varl, defVal)
        -- 获取线程运行时状态
        if IsEmpty(key1) then
            return G.runtime("Thread")
        end
        local path = IsEmpty(key2) and ("Thread." .. key1) or ("Thread." .. key1 .. "." .. key2)
        return G.runtime(path) or defVal
    end,

    ["任务状态"] = function(key1, key2, varl, defVal)
        -- 前向声明，函数定义在后面
        return jy_param.getTastState(key1)
    end,

    ["线程内存变量"] = function(key1, key2, varl, defVal)
        if IsEmpty(key1) then
            return G.runtime("线程内存变量") or defVal
        end
        local path = IsEmpty(key2) and ("线程内存变量." .. key1) or ("线程内存变量." .. key1 .. "." .. key2)
        local value = G.runtime(path)
        -- 保留VB版本的特殊逻辑
        if varl and varl ~= "" then
            defVal = key2
        end
        return value or (defVal ~= -1 and defVal or nil)
    end,

    ["readUI"] = function(key1, key2, varl, defVal)
        return jy_ui.getUISet(key1, key2)
    end,

    ["CSV"] = function(key1, key2, varl, defVal)
        -- 预留接口，可扩展为从CSV文件读取
        return nil
    end
}

--- 设置参数类型处理器
local TYPE_HANDLERS_SET = {
    ["vars"] = function(key1, key2, varl, v2)
        if IsEmpty(key1) then
            JY.jy_sys.acLog("vars 赋值失败: key1 为空", v2)
            return false
        end
        -- 使用路径方式设置，支持嵌套键
        local path = IsEmpty(key2) and key1 or (key1 .. "." .. key2)
        G.setVars(path, varl)
        return true
    end,

    ["conf"] = function(key1, key2, varl, v2)
        if IsEmpty(key1) then
            JY.jy_sys.acLog("conf 赋值失败: key1 为空", v2)
            return false
        end
        -- 使用路径方式设置，支持嵌套键
        local path = IsEmpty(key2) and key1 or (key1 .. "." .. key2)
        G.setConfig(path, varl)
        return true
    end,

    ["config"] = function(key1, key2, varl, v2)
        local actualValue = key2 and key2 ~= "" and key2 or varl
        return JY.jy_file.filePutUser(key1, actualValue, G.config("当前账号"))
    end,

    ["Thread"] = function(key1, key2, varl, v2)
        if IsEmpty(key1) then
            JY.jy_sys.acLog("Thread 赋值失败: key1 为空", v2)
            return false
        end
        -- 使用路径方式设置，支持嵌套键
        local path = IsEmpty(key2) and ("Thread." .. key1) or ("Thread." .. key1 .. "." .. key2)
        G.setRuntime(path, varl)
        return true
    end,

    ["线程内存变量"] = function(key1, key2, varl, v2)
        if IsEmpty(key1) then
            JY.jy_sys.acLog("线程内存变量 赋值失败: key1 为空", v2)
            return false
        end
        -- 使用路径方式设置，支持嵌套键
        local path = IsEmpty(key2) and ("线程内存变量." .. key1) or ("线程内存变量." .. key1 .. "." .. key2)
        G.setRuntime(path, varl)
        return true
    end,

    ["CSV"] = function(key1, key2, varl, v2)
        -- 预留接口，可扩展为写入CSV文件
        return true
    end
}

-- ==================== 辅助函数 ====================


local function processString(str)
    if not str or str == "" then return {} end

    local strList = frameSdk.splitStr(str, ",")
    local finalResult = {}

    for _, s in ipairs(strList) do
        s = JY.jy_str.trim(s)  -- 替换完成
        local equalPos = string.find(s, "=")

        if not equalPos then
            table.insert(finalResult, {"vars", "任务变量", "", "=", ""})
            goto continue
        end

        local left = string.sub(s, 1, equalPos - 1)
        left = JY.jy_str.trim(left)  -- 替换完成

        local right = string.sub(s, equalPos + 1)
        right = JY.jy_str.trim(right)  -- 替换完成

        local leftArr = frameSdk.splitStr(left, ".")
        local item

        -- 右边处理：先 | 分割，再 . 分割
        local rightParts = frameSdk.splitStr(right, "|")
        local processedRight = {}
        for _, r in ipairs(rightParts) do
            local rTrim = JY.jy_str.trim(r)  -- 替换完成
            local part = string.find(rTrim, "%.") and frameSdk.splitStr(rTrim, ".") or rTrim
            table.insert(processedRight, part)
        end

        -- 左边逻辑
        if #leftArr == 1 then
            item = {"vars", "任务变量", leftArr[1]}
        else
            local l1 = leftArr[1] or ""
            local l2 = leftArr[2] or ""
            local l3 = leftArr[3] or ""
            item = {l1, l2, l3}
        end

        -- 追加 | 分割后的所有结果到 item
        for _, val in ipairs(processedRight) do
            table.insert(item, val)
        end

        table.insert(finalResult, item)
        ::continue::
    end

    return finalResult
end
--- 递归解析嵌套键值
--- @param key any 键值（可能是表类型）
--- @return any 解析后的键值
local function resolveKey(key)
    return type(key) == "table" and jy_param.getParamVar(key) or key
end

--- 递归解析值（支持动态值）
--- @param value any 值（可能是表类型或动态值标识）
--- @return any 解析后的值
local function resolveValue(value)
    if type(value) == "table" then
        return (#value > 0 and jy_param.getParamVar(value)) or {}
    end

    local generator = CONST.DYNAMIC_VALUES[value]
    if generator then
        return generator()
    end

    return value
end

--- 规范化setParam参数
--- @param param table|nil 参数表
--- @return table 规范化后的参数表
local function normalizeSetParam(param)
    local defaultParams = {nil, "", "", nil, nil}

    if type(param) ~= "table" then
        return defaultParams
    end

    -- 自动补全缺失参数
    for i = 1, 5 do
        param[i] = param[i] or defaultParams[i]
    end

    return param
end

--- 规范化表类型参数配置
--- @param params table 原始参数表
--- @return table 规范化后的参数表
local function normalizeTableParams(params)
    local len = #params

    -- 长度为2：{变量名, 值}
    if len == 2 then
        return {1, "任务变量", params[1], params[2], "s", true}
    end

    -- 长度为3且第三个是布尔值：{变量名, 值, 是否启用}
    if len == 3 and type(params[3]) == "boolean" then
        return {1, "任务变量", params[1], params[2], "s", params[3]}
    end

    -- 长度为4：{类型, 变量名, 键, 值}
    if len == 4 then
        return {params[1], params[2], params[3], params[4], "s", true}
    end

    -- 长度为5：{类型, 变量名, 键, 值, 设置类型}
    if len == 5 then
        return {params[1], params[2], params[3], params[4], params[5], true}
    end

    -- 长度为6：标准格式直接使用
    if len == 6 then
        return params
    end

    -- 非标准格式报错
    JY.jy_sys.acLog("参数配置格式错误，长度: " .. len)
    frameSdk.exitScript()
end

--- 加载参数配置
--- @param params any 参数配置（可能是表、字符串或数字）
--- @param pageName string 页面名称
--- @return table 规范化后的参数配置
local function loadParams(params, pageName)
    -- 多赋值直接返回
    if type(params) == "table" and #params > 0 and type(params[1]) == "table" then
        return params
    end

    -- 字符串或数字类型直接赋值
    if type(params) == "string" or type(params) == "number" then
        return {1, "任务变量", pageName, params, "s", true}
    end

    -- 表类型处理
    if type(params) == "table" then
        return normalizeTableParams(params)
    end

    -- 类型错误
    JY.jy_sys.acLog("变量赋值配置类型错误: " .. type(params))
    frameSdk.exitScript()
end

--- 规范化参数列表
--- @param fz any 变量赋值配置
--- @param pageName string 页面名称
--- @return table 参数列表
local function normalizeParamsList(fz, pageName)
    -- 数组简写方案：{变量名, 值} 或 {"任务变量", 变量名, 值}
    if type(fz) == "table" and (type(fz[2]) == "string" or type(fz[1]) == "string") then
        return {fz}
    end

    -- 数值简写方案：直接是字符串或数字
    if type(fz) == "string" then
        local ret = processString(fz)
        if type(ret) == "string" then
            return {pageName, fz}
        else
            return ret
        end
    end
    if type(fz) == "number" then
        return {{pageName, fz}}
    end

    -- 多赋值方案：{{...}, {...}, ...}
    if type(fz) == "table" and #fz > 0 and type(fz[1]) == "table" then
        return fz
    end

    return {}
end

--- 记录设置操作日志
--- @param handlerKey string 处理器键名
--- @param key1 any 主键
--- @param key2 any 次键
--- @param varl any 值
--- @param v2 table 上下文信息
local function logSetOperation(handlerKey, key1, key2, varl, v2)
    local keyPath = "[ " .. tostring(key1) .. " ]"
    if key2 and key2 ~= "" then
        keyPath = keyPath .. "[ " .. tostring(key2) .. " ]"
    end

    local displayValue = type(varl) == "table" and "array()" or tostring(varl)
    JY.jy_sys.acLog("写入变量 " .. handlerKey .. keyPath .. " = " .. displayValue, v2)
end

-- ==================== 核心函数 ====================

--- 任务参数变更处理
--- @param v table 任务配置
--- @return table 处理后的任务配置
local function taskParamChange(v)
    -- 获取任务参数数据
    if v["任务参数"][1] ~= nil and type(v["任务参数"]) == "table" then
        v["任务参数"] = jy_frame.taskParamSet(
            v["任务参数"][1], v["任务参数"][2], v["任务参数"][3],
            v["任务参数"][4], v["任务参数"][5], v["任务参数"][6],
            v["任务参数"][7], v["任务参数"][8], v["任务参数"][9]
        )
    end

    -- 用户自定义时间间隔
    local setJG = jy_ui.getUISet(v[1] .. "间隔", nil)
    if type(v["任务参数"]["执行间隔时间"]) == "string" and setJG ~= nil then
        local unit = v["任务参数"]["执行间隔时间"]
        local multiplier = CONST.TIME_UNITS[unit]
        if multiplier then
            v["任务参数"]["执行间隔时间"] = setJG * multiplier
        else
            v["任务参数"]["执行间隔时间"] = nil
        end
    elseif type(v["任务参数"]["执行间隔时间"]) == "string" and setJG == nil then
        v["任务参数"]["执行间隔时间"] = nil
    end

    return v
end

--- 获取任务状态
--- @param task string 任务名称
--- @return boolean 任务是否可执行
local function getTastState(task)
    local v = jy_frame.getTask(task)
    if IsEmpty(v) then
        JY.jy_sys.mlog("█【任务状态】===================== 无法获取到【" .. tostring(task) .. "】任务状态：")
        return false
    end

    local settingID = v["任务参数"]["UIID"] or v[1]
    if v["任务参数"]["是否有对应设置"] ~= false and not jy_ui.getUISet(settingID, false) then
        JY.jy_sys.mlog("█【" .. v[1] .. "】===================== 【 " .. v[1] .. " 】取消，用户未启用该任务")
        return false
    end

    local taskParam = JY.jy_file.fileGetUser(v[1], {["执行次数"] = 0, ["执行时间"] = 0}, G.config("当前账号"))

    -- 任务参数格式转换和用户自定义间隔赋值
    v = taskParamChange(v)

    -- 当前时间戳
    local nowTime = os.time() - (tonumber(G.config("奖励重置时间") or 0))

    -- 上次执行时间戳
    local lastExecTime = tonumber(taskParam["执行时间"]) or 0

    -- 当前日期信息
    local nowTimeInfo = JY.jy_time.getTimeInfo(nowTime)

    -- 上次执行日期信息
    local oldTimeInfo = JY.jy_time.getTimeInfo(lastExecTime)

    -- 间隔时间检查
    local interval = tonumber(v["任务参数"]["执行间隔时间"] or 0)
    local execMode = tonumber(v["任务参数"]["执行模式"]) or -1
    local code = false

    -- 优先判断间隔时间
    if interval > 0 then
        if (nowTime - lastExecTime) < interval then
            JY.jy_sys.mlog("█【" .. v[1] .. "】===================== 【 " .. v[1] .. " 】取消，下次执行时间还剩：" .. JY.jy_time.timeFormat(interval -(nowTime - lastExecTime), false))
        else
            code = true
        end
    else
        if execMode == 1 and nowTimeInfo.day == oldTimeInfo.day then
            code = true
        elseif execMode == 2 and nowTimeInfo.weekDayCN == oldTimeInfo.weekDayCN then
            code = true
        elseif execMode == 3 and nowTimeInfo.month == oldTimeInfo.month then
            code = true
        elseif execMode == 0 then
            code = true
        end
    end

    -- 执行次数检查
    local maxExec = tonumber(v["任务参数"]["执行次数"] or 0)
    local currentCount = tonumber(taskParam["执行次数"] or 0)
    if maxExec > 0 then
        if nowTimeInfo.day ~= oldTimeInfo.day or (maxExec > currentCount) then
            code = true
        else
            JY.jy_sys.mlog("█【" .. v[1] .. "】===================== 【 " .. v[1] .. " 】取消，已执行指定次数：" .. currentCount)
            code = false
        end
    end

    return code
end

--- 任务记录
--- @param v2 table 上下文信息
local function taskRecord(v2)
    JY.jy_sys.acLog("**************** 任务记录 【" .. G.config("当前任务") .. "】****************", v2)

    local accConf = JY.jy_file.fileGetUser(G.config("当前任务"), -1, G.config("当前账号"))
    local v = jy_frame.getTask(G.config("当前任务"))

    if IsEmpty(v) then
        return
    end
    -- 计算奖励重置后的时间
    local nowTime = os.time() - (tonumber(G.config("奖励重置时间")) or 0)

    if v ~= -1 then
        -- 初始化accConf结构
        if type(accConf) ~= "table" then
            accConf = {["执行时间"] = 0, ["执行次数"] = 0}
        end

        -- 根据执行模式设置时间基准
        local execModeR = v["任务参数"]["执行模式"] or 0
        if execModeR == 1 or execModeR == 4 or execModeR == 0 then
            accConf["执行时间"] = os.date("%d", nowTime)
        elseif execModeR == 2 then
            accConf["执行时间"] = nowTime
        elseif execModeR == 3 then
            accConf["执行时间"] = os.date("%m", nowTime)
        end

        -- 更新执行次数逻辑
        if execModeR ~= -1 and execModeR ~= nil then
            local currentDay = os.date("%d", nowTime)
            if tonumber(accConf["执行时间"]) == tonumber(currentDay) then
                accConf["执行次数"] = (accConf["执行次数"] or 0) + 1
            else
                accConf["执行次数"] = 1
                accConf["执行时间"] = currentDay
            end
        else
            accConf["执行次数"] = (accConf["执行次数"] or 0) + 1
        end

        -- 处理间隔时间特殊逻辑
        if v["任务参数"]["执行间隔时间"] ~= -1 and v["任务参数"]["执行间隔时间"] ~= nil then
            accConf["执行时间"] = nowTime
        end

        -- 保存更新后的配置
        JY.jy_file.filePutUser(G.config("当前任务"), accConf, G.config("当前账号"))
    else
        JY.jy_sys.acLog("任务未找到，测试单功能可忽略此异常", v2)
    end
end

--- 动态获取参数
--- @param param table|any 参数配置表或基础类型值
--- @return any 解析后的参数值
local function getParamVar(param)
    -- 基础类型直接返回
    if type(param) ~= "table" then
        return param
    end

    -- 参数解构
    local vvType, key1, key2, varl, defVal = param[1], param[2], param[3], param[4], param[5]

    -- 递归解析嵌套键值
    key1 = resolveKey(key1)
    key2 = resolveKey(key2)
    -- 获取处理器
    local handlerKey = CONST.TYPE_INDEX[vvType] or vvType

    local handler = TYPE_HANDLERS_GET[handlerKey]

    if not handler then
        JY.jy_sys.acLog("getParamVar: 未支持的类型 - " .. tostring(handlerKey))
        return nil
    end

    return handler(key1, key2, varl, defVal)
end

--- 动态保存参数
--- @param param table 参数配置表
--- @param v2 table 上下文信息
--- @return boolean 是否成功
local function setParamVar(param, v2)
    -- 参数规范化
    param = normalizeSetParam(param)

    -- 参数解构
    local vvType, key1, key2, varl, setType = param[1], param[2], param[3], param[4], param[5]

    -- 数值累加模式处理
    if setType == "num" then
        local oldValue = getParamVar({vvType, key1, key2})
        varl = (tonumber(oldValue) or 0) + (tonumber(varl) or 0)
    end

    -- 递归解析键值
    key1 = resolveValue(key1)
    key2 = resolveValue(key2)
    varl = resolveValue(varl)
    -- 获取处理器
    local handlerKey = CONST.TYPE_INDEX[vvType] or vvType
    local handler = TYPE_HANDLERS_SET[handlerKey]

    if not handler then
        JY.jy_sys.acLog("setParamVar: 未支持的类型 - " .. tostring(handlerKey), v2)
        return false
    end

    local result = handler(key1, key2, varl, v2)

    -- 日志输出
    logSetOperation(handlerKey, key1, key2, varl, v2)

    return result or true
end

--- 参数设置
--- @param v2 table 上下文信息
--- @param code any 条件码
local function paramSet(v2, code)
    local pageName = v2[1]
    local fz = v2["变量赋值"]

    -- 参数校验
    if not fz then
        return
    end

    -- 统一转换为数组格式
    local paramsList = normalizeParamsList(fz, pageName)

    -- 执行赋值
    for _, params in ipairs(paramsList) do
        params = loadParams(params, pageName)
        if code == params[6] then
            setParamVar(params, v2)
        end
    end
end

-- ==================== 模块导出 ====================
jy_param.getParamVar = getParamVar
jy_param.setParamVar = setParamVar
jy_param.getTastState = getTastState
jy_param.taskRecord = taskRecord
jy_param.paramSet = paramSet



return jy_param
