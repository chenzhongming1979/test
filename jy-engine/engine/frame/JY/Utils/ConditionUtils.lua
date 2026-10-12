--[[
    模块：条件构造与判断 (ConditionUtils)
    版本：2.0.0
    功能：条件表达式解析与评估

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
local jy_frame = require("JY.Utils.FrameUtils")
local jy_task = require("JY.Utils.TaskUtils")
local jy_param = require("JY.Utils.ParamUtils")
local JY = require("JY.Utils.JY")

-- ==================== 常量定义 ====================
local CONST = {
    -- 逻辑模式
    MODE_AND = 1,
    MODE_OR = 2,

    -- 条件类型标识
    TYPE_BOOLEAN = "boolean",
    TYPE_FUNCTION = "function",
    TYPE_TABLE = "t",
    TYPE_UNKNOWN = "unknown",

    -- 比较方法
    COMPARE_IN_STR = "inStr",

    -- 修饰符
    MODIFIER_NOT = "not",
    MODIFIER_MS = "ms",

    -- 特殊值映射
    NIL_VALUES = {["nil"] = true, ["null"] = true},
    EMPTY_TABLE_VALUES = {["nilTable"] = true, ["nullTable"] = true}
}

-- ==================== 辅助函数 ====================


-- 合法运算符表
local VALID_COMPARATORS = {
    ["=="] = true,
    [">"] = true,
    ["<"] = true,
    ["~="] = true,
    [">="] = true,
    ["<="] = true,
    ["inStr"] = true,
    ["ms"] = true
}

-- 核心处理函数
local function processString(str)
    if not str or str == "" then return {} end

    -- 统一去除首尾空格
    str = JY.jy_str.trim(str)

    -- 判定逻辑符：优先识别 and / or（只能存在一种）
    local logicType = nil
    local splitParts = nil

    if string.find(str, "or") then
        logicType = "or"
        splitParts = frameSdk.splitStr(str, "or")
    elseif string.find(str, "and") then
        logicType = "and"
        splitParts = frameSdk.splitStr(str, "and")
    else
        logicType = nil
        splitParts = {str}
    end

    -- 最终结果：第一位放逻辑符（如果有）
    local finalResult = {}
    if logicType then
        table.insert(finalResult, logicType)
    end

    -- 遍历处理每一个条件
    for _, s in ipairs(splitParts) do
        s = JY.jy_str.trim(s)
        if s == "" then goto continue end

        -- 匹配运算符
        local matchOp, matchPos, matchOpLen = nil, nil, 0
        for op, _ in pairs(VALID_COMPARATORS) do
            local pos = string.find(s, op, 1, true)
            if pos then
                matchPos = pos
                matchOp = op
                matchOpLen = #op
                break
            end
        end

        if not matchOp then
            table.insert(finalResult, s)
            goto continue
        end

        -- 分割左右
        local left = JY.jy_str.trim(string.sub(s, 1, matchPos - 1))
        local right = JY.jy_str.trim(string.sub(s, matchPos + matchOpLen))

        -- 左边按 . 分割
        local leftArr = frameSdk.splitStr(left, ".")
        local rightRes = string.find(right, "%.") and frameSdk.splitStr(right, ".") or right
        local item

        -- 左边规则：1段 → vars/任务变量；≥2段正常分割
        if #leftArr == 1 then
            item = {"vars", "任务变量", leftArr[1], matchOp, rightRes}
        else
            local l1 = leftArr[1] or ""
            local l2 = leftArr[2] or ""
            local l3 = leftArr[3] or ""
            item = {l1, l2, l3, matchOp, rightRes}
        end

        table.insert(finalResult, item)
        ::continue::
    end
    if type(finalResult[1]) == "string" and #finalResult == 1 then
        return finalResult[1]
    end
    -- 固定返回
    return finalResult
end
--- 计算当前天数（依据奖励重置时间）
--- @return number 当前天数
local function computeDay()
    return os.date("%d", os.time() - (G.config("奖励重置时间") or 0))
end

--- 将任意参数格式化为字符串（用于日志输出）
--- @param p any 参数值
--- @return string 格式化后的字符串
local function formatParam(p)
    if p == nil then
        return "nil"
    end
    if type(p) == "table" then
        return frameSdk.jsonLib.encode(p)
    end
    if type(p) == "boolean" then
        return p and "true" or "false"
    end
    return tostring(p)
end

--- 规范化参数值
--- @param param any 参数值
--- @return any 规范化后的值
local function normalizeParam(param)
    -- 参数可能已是数字/字符串/表
    if type(param) ~= "table" then
        if param == "day" then
            return computeDay()
        elseif CONST.EMPTY_TABLE_VALUES[param] then
            return {}
        elseif CONST.NIL_VALUES[param] or param == nil then
            return nil
        else
            return param
        end
    end
    -- 表表示参数变量，需要通过工具解析
    return jy_param.getParamVar(param)
end

--- 应用修饰符
--- @param param1 any 参数值
--- @param modifier string|nil 修饰符
--- @return any 处理后的值
local function applyModifier(param1, modifier)
    if modifier == CONST.MODIFIER_MS then
        return frameSdk.systemTime() - JY.jy_num.toNumber(param1)
    end
    return param1
end

--- 规范化比较值（统一类型转换）
--- @param param1 any 第一个参数
--- @param param2 any 第二个参数
--- @return number|string, number|string 规范化后的两个值
local function normalizeValues(param1, param2)
    local isNumeric = JY.jy_num.isNumber(param1) and JY.jy_num.isNumber(param2)

    if isNumeric then
        return tonumber(param1), tonumber(param2)
    end

    if type(param1) == "table" or type(param2) == "table" then
        return frameSdk.jsonLib.encode(param1), frameSdk.jsonLib.encode(param2)
    end

    return param1, param2
end

--- 执行比较操作
--- @param param1 any 第一个参数
--- @param compareType string 比较类型
--- @param param2 any 第二个参数
--- @param modifier string|nil 修饰符
--- @return boolean 比较结果
local function executeComparison(param1, compareType, param2, modifier)
    local status

    if compareType == CONST.COMPARE_IN_STR then
        status = param1 ~= nil and JY.jy_str.inStrEx(param1, param2)
    else
        status = jy_frame.compar(param1, compareType, param2)
    end

    -- 应用取反修饰符
    if modifier == CONST.MODIFIER_NOT then
        status = not status
    end

    return status
end

--- 短路判断
--- @param status boolean 当前状态
--- @param contrastType number 逻辑模式
--- @return boolean 是否应该短路退出
local function shouldShortCircuit(status, contrastType)
    return (status and contrastType == CONST.MODE_OR) or
           (not status and contrastType == CONST.MODE_AND)
end

--- 格式化输出参数
--- @param param1 any 第一个参数
--- @param param2 any 第二个参数
--- @return string, string 格式化后的参数
local function formatOutputParams(param1, param2)
    if type(param1) == "boolean" then
        param1 = param1 and "true" or "false"
    end
    if type(param2) == "boolean" then
        param2 = param2 and "true" or "false"
    end
    return param1, param2
end

--- 输出条件失败信息至系统日志
--- @param t table 任务配置
--- @param cmpType string cmpType标识
--- @param num number 条件序号
--- @param param1 any 参数1
--- @param param2 any 参数2
--- @param tType string 比较类型
--- @param strName string|nil 函数名称
local function logConditionInfo(t, cmpType, num, param1, param2, tType, strName)
    local name = t.name or t[1] or t["1"]
    if type(name) == "table" then
        name = name.name or "未知"
    end

    param1, param2 = formatParam(param1), formatParam(param2)

    local msg
    if cmpType == CONST.TYPE_TABLE then
        msg = string.format("%s %s %s", param1, tType or "", param2)
    elseif cmpType == CONST.TYPE_BOOLEAN or cmpType == "b" then
        msg = "为 false"
    else
        msg = string.format("%s = false", strName or "")
    end

    JY.jy_sys.acLog(string.format("[ %s ] 不满足执行条件 -- #%d: %s", name, num, msg), t)
end

-- ==================== 核心函数 ====================

--- 处理布尔类型条件
--- @param vv boolean 布尔值
--- @return boolean, string, nil, nil, nil 结果
local function processBooleanCondition(vv)
    return vv, CONST.TYPE_BOOLEAN, nil, nil, nil
end

--- 处理函数类型条件
--- @param vv string 函数名称
--- @param t table 任务配置
--- @return boolean, string, nil, nil, nil 结果
local function processFunctionCondition(vv, t)
    local result = jy_task.otherRun(vv, t)
    return not not result, CONST.TYPE_FUNCTION, nil, nil, nil
end

--- 处理表类型条件
--- @param vv table 条件表
--- @param t table 任务配置
--- @return boolean, string, any, any, string 结果
local function processTableCondition(vv, t)
    local param1 = normalizeParam({vv[1], vv[2], vv[3]})
    local param2 = normalizeParam(vv[5])
    local compareType = vv[4]
    local modifier = vv[6]

    -- 应用修饰符
    param1 = applyModifier(param1, modifier)

    -- 类型转换
    param1, param2 = normalizeValues(param1, param2)

    -- 执行比较
    local status = executeComparison(param1, compareType, param2, modifier)

    -- 格式化输出参数
    param1, param2 = formatOutputParams(param1, param2)

    return status, CONST.TYPE_TABLE, param1, param2, compareType
end

--- 处理单条条件表达式
--- @param vv any 条件值（布尔、字符串或表）
--- @param t table 任务配置
--- @return boolean, string, any, any, string|nil 结果
local function processConditionItem(vv, t)
    -- 类型分发
    if type(vv) == "boolean" then
        return processBooleanCondition(vv)
    end

    if type(vv) == "string" then
        return processFunctionCondition(vv, t)
    end

    if type(vv) == "table" then
        return processTableCondition(vv, t)
    end

    return false, CONST.TYPE_UNKNOWN, nil, nil, nil
end

--- 递归构建执行条件
--- @param vv any 原始条件值
--- @return table|any 规范化后的条件结构
local function buildCondition(vv)
    if type(vv) ~= "table" then
        return vv
    end

    local ret = {}
    local first = vv[1]

    -- 复杂逻辑或嵌套
    if type(first) == "table" or first == "or" or first == "and" then
        for key, value in pairs(vv) do
            if value == "or" or value == "and" then
                ret[key] = value
            else
                local sub = buildCondition(value)
                ret[key] = sub[1]
            end
        end
    elseif type(first) == "string" or type(first) == "number" then
        -- 简单比较表达式
        if jy_frame.isCompar(vv, 4) then
            ret[1] = vv
        elseif jy_frame.isCompar(vv, 2) then
            -- 兼容旧格式：{变量名, 比较符, 值}
            ret[1] = {1, "任务变量", vv[1], vv[2], vv[3]}
        end
    else
        -- 无法识别时原样返回
        return vv
    end

    return ret
end

--- 评估简单类型条件
--- @param condition any 条件值
--- @param t table 任务配置
--- @return boolean 条件是否满足
local function evaluateSimpleCondition(condition, t)
    if type(condition) == "boolean" then
        if not condition then
            logConditionInfo(t, CONST.TYPE_BOOLEAN, 1)
        end
        return condition
    end

    if type(condition) == "string" then
        local result = not not jy_task.otherRun(condition, t)
        if not result then
            logConditionInfo(t, CONST.TYPE_FUNCTION, 1, nil, nil, nil, condition)
        end
        return result
    end

    -- 非预期类型视为条件通过
    return true
end

--- 评估表类型条件（支持and/or逻辑）
--- @param condition table 条件表
--- @param t table 任务配置
--- @return boolean 条件是否满足
local function evaluateTableCondition(condition, t)
    local contrastType = CONST.MODE_AND
    local status = true
    local conditionIndex = 0
    local cmpType, param1, param2, tType, strName

    for _, vv in ipairs(condition) do
        if vv == "or" then
            contrastType = CONST.MODE_OR
        elseif vv == "and" then
            contrastType = CONST.MODE_AND
        else
            conditionIndex = conditionIndex + 1
            status, cmpType, param1, param2, tType = processConditionItem(vv, t)

            -- 短路优化
            if shouldShortCircuit(status, contrastType) then
                break
            end
        end
    end

    if not status then
        logConditionInfo(t, cmpType, conditionIndex, param1, param2, tType, strName)
    end

    return status
end

--- 入口函数：判断条件集合是否满足
--- @param t table 任务配置，包含执行条件
--- @return boolean 条件是否满足
local function evaluateCondition(t)
	if type(t["执行条件"]) == "string" then
		local ret = processString(t["执行条件"])
		if type(ret) == "table" then
			t["执行条件"] = ret
		end
	end
	local condition = buildCondition(t["执行条件"])
	local conditionType = type(condition)
	-- 简单类型直接判断
	if conditionType ~= "table" then
		local ret = evaluateSimpleCondition(condition, t)
		if ret then
			JY.jy_sys.acLog("满足执行条件", t)
		end
		return ret
	end

	-- 表类型条件评估
	local ret = evaluateTableCondition(condition, t)
	if ret then
		JY.jy_sys.acLog("满足执行条件", t)
	end
	return ret
end

-- ==================== 模块导出 ====================
return {
    evaluateCondition = evaluateCondition,
    processConditionItem = processConditionItem,
    buildCondition = buildCondition
}
