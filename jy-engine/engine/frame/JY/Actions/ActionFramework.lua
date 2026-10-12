--[[
Copyright (c) 2026 成都林深见鹿网络科技有限公司 All Rights Reserved.

本代码基于 Apache License 2.0 协议开源：
1. 个人学习、非商业用途：可免费使用、修改、分发本代码；
2. 商业用途、企业使用：必须联系作者获取书面授权后方可使用；
3. 任何使用场景下，禁止删除本版权声明及相关协议说明；
4. 若进行二次开发/衍生，需在衍生作品的显著位置保留本版权声明，并标注衍生说明。

你可以获取 Apache License 2.0 完整协议文本：
http://www.apache.org/licenses/LICENSE-2.0
--]]

local JY = require("JY.Utils.JY")
local acUtils = require("JY.Utils.ActionsUtils")
local jy_task = require("JY.Utils.TaskUtils")
local jy_frame = require("JY.Utils.FrameUtils")
local jy_param = require("JY.Utils.ParamUtils")
local jy_ui = require("JY.Utils.UIUtils")
local condUtil = require("JY.Utils.ConditionUtils")
local evaluateCondition = condUtil.evaluateCondition
local ACTIONS = {
    ["ps_w"] = function(v2) return acUtils.find.findClick_w(v2) end,
    ["ps_b"] = function(v2) return acUtils.find.findClick(v2) end,
    ["ps_bw"] = function(v2) return acUtils.find.findClick_w(v2) end,
    ["ps_wv"] = function(v2) return acUtils.find.find_wv(v2) end,

    ["ft_c"] = function(v2) return acUtils.find.findClick(v2) end,
    ["ft_s"] = function(v2, wait) return acUtils.find.findClick_s(v2, wait) end,
    ["ft_w"] = function(v2) return acUtils.find.findClick_w(v2) end,
    ["ft_a"] = function(v2, wait) return acUtils.find.findClick_a(v2, wait) end,
    ["ft_r"] = function(v2, wait) return acUtils.find.findClick_r(v2, wait) end,
    ["ft_cl"] = function(v2, wait) return acUtils.find.findClick_l(v2, wait) end,
    ["ft_res"] = function(v2, wait) return acUtils.find.find_res(v2) end,
    ["ft_v"] = function(v2, wait) return acUtils.find.find_v(v2) end,
    ["ft_wv"] = function(v2, wait) return acUtils.find.find_wv(v2) end,
    ["ft_num"] = function(v2, wait) return acUtils.find.find_num(v2) end,
    ["ft_ex_f"] = function(v2, wait) return acUtils.find.find_ex_m(v2, wait) end,

    ["pic_c"] = function(v2) return acUtils.find.findClick(v2) end,
    ["pic_s"] = function(v2, wait) return acUtils.find.findClick_s(v2, wait) end,
    ["pic_w"] = function(v2, wait) return acUtils.find.findClick_w(v2) end,
    ["pic_a"] = function(v2, wait) return acUtils.find.findClick_a(v2, wait) end,
    ["pic_r"] = function(v2, wait) return acUtils.find.findClick_r(v2, wait) end,
    ["pic_cl"] = function(v2, wait) return acUtils.find.findClick_l(v2, wait) end,
    ["pic_res"] = function(v2, wait) return acUtils.find.find_res(v2) end,
    ["pic_v"] = function(v2, wait) return acUtils.find.find_v(v2) end,
    ["pic_num"] = function(v2, wait) return acUtils.find.find_num(v2) end,
    ["pic_wv"] = function(v2, wait) return acUtils.find.find_wv(v2) end,
    ["pic_p"] = function(v2)
        acUtils.pic.ac_pic_p(v2)
        return true
    end,
    ["fc_c"] = function(v2) return acUtils.find.findClick(v2) end,
    ["fc_s"] = function(v2, wait) return acUtils.find.findClick_s(v2, wait) end,
    ["fc_w"] = function(v2, wait) return acUtils.find.findClick_w(v2) end,
    ["fc_a"] = function(v2, wait) return acUtils.find.findClick_a(v2, wait) end,
    ["fc_cl"] = function(v2, wait) return acUtils.find.findClick_l(v2, wait) end,
    ["fc_res"] = function(v2, wait) return acUtils.find.find_res(v2) end,
    ["fc_r"] = function(v2, wait) return acUtils.find.findClick_r(v2, wait) end,
    ["fc_v"] = function(v2, wait) return acUtils.find.find_v(v2) end,
    ["fc_wv"] = function(v2, wait) return acUtils.find.find_wv(v2) end,

    ["fs_c"] = function(v2) return acUtils.find.findClick(v2) end,
    ["fs_s"] = function(v2, wait) return acUtils.find.findClick_s(v2, wait) end,
    ["fs_w"] = function(v2, wait) return acUtils.find.findClick_w(v2) end,
    ["fs_a"] = function(v2, wait) return acUtils.find.findClick_a(v2, wait) end,
    ["fs_r"] = function(v2, wait) return acUtils.find.findClick_r(v2, wait) end,
    ["fs_cl"] = function(v2, wait) return acUtils.find.findClick_l(v2, wait) end,
    ["fs_res"] = function(v2, wait) return acUtils.find.find_res(v2) end,
    ["fs_v"] = function(v2, wait) return acUtils.find.find_v(v2) end,
    ["fs_num"] = function(v2, wait) return acUtils.find.find_num(v2) end,
    ["fs_wv"] = function(v2, wait) return acUtils.find.find_wv(v2) end,

    -- 节点类指令（无障碍节点查找点击，实现在 acUtils.nd）
    ["nd_c"] = function(v2) return acUtils.nd.findClick(v2) end,
    ["nd_w"] = function(v2, wait) return acUtils.nd.findClick_w(v2, wait) end,
    ["nd_s"] = function(v2, wait) return acUtils.nd.findClick_s(v2, wait) end,
    ["nd_a"] = function(v2, wait) return acUtils.nd.findClick_a(v2, wait) end,
    ["nd_t"] = function(v2, wait) return acUtils.nd.getText(v2, wait) end,
    ["nd_in"] = function(v2, wait) return acUtils.nd.setText(v2, wait) end,
    ["nd_scroll"] = function(v2, wait) return acUtils.nd.scroll(v2, wait) end,

    -- AI目标检测指令（YoloV5目标检测，实现在 acUtils.yolo）
    ["yolo"] = function(v2) return acUtils.find.findClick(v2) end,
    ["yolo_c"] = function(v2) return acUtils.find.findClick(v2) end,
    ["yolo_s"] = function(v2, wait) return acUtils.find.findClick_s(v2, wait) end,
    ["yolo_w"] = function(v2) return acUtils.find.findClick_w(v2) end,
    ["yolo_a"] = function(v2, wait) return acUtils.find.findClick_a(v2, wait) end,

    ["gcn"] = function(v2) return acUtils.find.findClick(v2) end,
    ["gcn_s"] = function(v2, wait) return acUtils.find.findClick_s(v2, wait) end,
    ["moves"] = function(v2) return acUtils.move.moves(v2) end,
    ["tMove"] = function(v2) return acUtils.move.tMove(v2) end,
    ["compute"] = function(v2) return acUtils.ac_comput(v2) end,
    ["dc"] = function(v2) return acUtils.find.moreFind_dc(v2) end,
    ["zIn"] = function(v2, wait)
        JY.jy_touch.zIn(v2.xy1[1], v2.xy1[2], v2.xy2[1], v2.xy2[2], v2.moveTime, wait)
        return true
    end,
    ["zOut"] = function(v2, wait)
        JY.jy_touch.zOut(v2.xy1[1], v2.xy1[2], v2.xy2[1], v2.xy2[2], v2.moveTime, wait)
        return true
    end,
    ["rand"] = function(v2) return acUtils.rand.ac_rand(v2) end,
    ["cjRand"] = function(v2) return acUtils.rand.cjRand(v2) end,
    ["nil"] = function(v2, wait)
        JY.jy_sys.acLog("直接运行", v2)
        JY.jy_sys.sleep(wait)
        return true
    end,
    ["rApp"] = function(v2, wait)
        frameSdk.runApp(v2["appid"])
        JY.jy_sys.acLog("启动 " .. v2["appid"], v2)
        JY.jy_sys.sleep(wait)
        return true
    end,
    ["idd"] = function(v2, wait)
        local code = JY.jy_sys.isScreenStable(v2["r"], v2["time"], v2["degree"], v2["pl"], v2["maxP"])
        if not code then
            if (v2["tape"] ~= nil or v2["clickRange"] ~= nil) then
                jy_frame.comClick(v2["tape"], v2)
            end
            JY.jy_sys.acLog("界面无变化 返回：false", v2)
            return false
        else
            JY.jy_sys.acLog("界面有变化 返回：true", v2)
            return true
        end
    end,
    ["pi"] = function(v2, wait)
        local index1 = jy_param.getParamVar(v2["i"])
        JY.jy_touch.click(v2["pi"][index1])
        JY.jy_sys.acLog("点击第" .. index1 .. "个坐标", v2)
        JY.jy_sys.sleep(wait)
        return true
    end,
    ["rc"] = function(v2, wait)
        local alltap = v2["rc"]
        local idx = acUtils.rand.randomRange(1, #alltap)
        JY.jy_touch.click(alltap[idx])
        JY.jy_sys.acLog(" 随机点击第" .. idx .. "个坐标", v2)
        JY.jy_sys.sleep(wait)
        return true
    end,
    ["p"] = function(v2, wait)
        if v2["clickRange"] then
            v2["tape"] = jy_frame.clickRange(v2["clickRange"])
        end
        JY.jy_sys.acLog("点击：" .. v2["tape"]["x"] .. " - " .. v2["tape"]["y"], v2)
        JY.jy_touch.click2(v2["tape"])
        JY.jy_sys.sleep(wait)
        return true
    end,
    ["2p"] = function(v2, wait)
        local count = v2["count"] or 1
        local pwait = v2["pwait"] or 100

        for i = 1, count do
            JY.jy_touch.click(v2["tape"])
            JY.jy_sys.sleep(pwait)
        end
        JY.jy_sys.acLog("点击：" .. v2["tape"]["x"] .. " - " .. v2["tape"]["y"], v2)
        JY.jy_sys.sleep(wait)
        return true
    end,
}


--[[
    ActionFramework - 基于元表优化的actions函数封装
    功能
    1. 策略延迟加载和缓存
    2. 参数自动验证和转换
    3. 函数结果缓存
    4. Action对象封装
    5. 兼容原API
]]


local ActionFramework = {}

-- ==================== 1. 策略管理类 ====================

local StrategyManager = {}
StrategyManager.__index = StrategyManager

function StrategyManager.new()
    local self = {
        strategies = {},
        lazy_strategies = {},
        cache = {},
        stats = {
            hit = 0,
            miss = 0,
            lazy_load = 0
        }
    }
    return setmetatable(self, StrategyManager)
end

function StrategyManager:register(name, handler, is_lazy)
    if is_lazy then
        self.lazy_strategies[name] = handler
    else
        self.strategies[name] = handler
    end
end

function StrategyManager:registerBatch(strategies, is_lazy)
    for name, handler in pairs(strategies) do
        self:register(name, handler, is_lazy)
    end
end

function StrategyManager:get(name)
    if self.cache[name] then
        self.stats.hit = self.stats.hit + 1
        return self.cache[name]
    end

    if self.strategies[name] then
        self.cache[name] = self.strategies[name]
        self.stats.miss = self.stats.miss + 1
        return self.strategies[name]
    end

    if self.lazy_strategies[name] then
        self.cache[name] = self.lazy_strategies[name]
        self.stats.lazy_load = self.stats.lazy_load + 1
        return self.lazy_strategies[name]
    end

    return nil
end

function StrategyManager:getStats()
    local total = self.stats.hit + self.stats.miss + self.stats.lazy_load
    return {
        hit = self.stats.hit,
        miss = self.stats.miss,
        lazy_load = self.stats.lazy_load,
        total = total,
        hit_rate = total > 0 and (self.stats.hit / total) or 0
    }
end

function StrategyManager:clearCache()
    self.cache = {}
    self.stats.hit = 0
    self.stats.miss = 0
    self.stats.lazy_load = 0
end

-- ==================== 2. 参数验证类 ====================

local ParamValidator = {}
ParamValidator.__index = ParamValidator

function ParamValidator.new()
    local self = {
        validators = {},
        transformers = {},
        defaults = {}
    }
    return setmetatable(self, ParamValidator)
end

function ParamValidator:addValidator(key, validator)
    self.validators[key] = validator
end

function ParamValidator:addTransformer(key, transformer)
    self.transformers[key] = transformer
end

function ParamValidator:addDefault(key, default_value)
    self.defaults[key] = default_value
end

function ParamValidator:validate(key, value)
    if self.validators[key] then
        local success, result = pcall(self.validators[key], value)
        if not success then
            error("参数验证失败 [" .. key .. "]: " .. result)
        end
        return result
    end
    return value
end

function ParamValidator:transform(key, value, context)
    if self.transformers[key] then
        local success, result = pcall(self.transformers[key], value, context)
        if not success then
            error("参数转换失败 [" .. key .. "]: " .. result)
        end
        return result
    end
    return value
end

function ParamValidator:getDefault(key)
    return self.defaults[key]
end

function ParamValidator:createTable(raw_params)
    local mt = {
        __newindex = function(t, key, value)
            value = self:validate(key, value)
            value = self:transform(key, value, t)
            rawset(t, key, value)
        end,

        __index = function(t, key)
            local value = rawget(t, key)
            if value ~= nil then
                return value
            end
            return self:getDefault(key)
        end
    }

    local params = {}
    for key, value in pairs(raw_params or {}) do
        params[key] = value
    end

    return setmetatable(params, mt)
end

-- ==================== 3. 函数缓存装饰类 ====================

local FunctionCache = {}
FunctionCache.__index = FunctionCache

function FunctionCache.new(func, options)
    options = options or {}
    local self = {
        func = func,
        cache = {},
        max_size = options.max_size or 100,
        ttl = options.ttl or nil,
        key_func = options.key_func or function(...)
            local args = {...}
            local keys = {}
            for i, v in ipairs(args) do
                if type(v) == "table" then
                    keys[i] = table.concat(v, "|")
                else
                    keys[i] = tostring(v)
                end
            end
            return table.concat(keys, "|")
        end,
        stats = {
            hit = 0,
            miss = 0,
            evict = 0
        }
    }
    return setmetatable(self, FunctionCache)
end

function FunctionCache:__call(...)
    local key = self.key_func(...)
    local cached = self.cache[key]

    if cached then
        if self.ttl and (os.time() - cached.time > self.ttl) then
            self.cache[key] = nil
            self.stats.evict = self.stats.evict + 1
        else
            self.stats.hit = self.stats.hit + 1
            return cached.value
        end
    end

    local value = self.func(...)

    if #self.cache >= self.max_size then
        local oldest_key = next(self.cache)
        self.cache[oldest_key] = nil
        self.stats.evict = self.stats.evict + 1
    end

    self.cache[key] = {
        value = value,
        time = os.time()
    }

    self.stats.miss = self.stats.miss + 1
    return value
end

function FunctionCache:getStats()
    local total = self.stats.hit + self.stats.miss
    return {
        hit = self.stats.hit,
        miss = self.stats.miss,
        evict = self.stats.evict,
        total = total,
        hit_rate = total > 0 and (self.stats.hit / total) or 0
    }
end

function FunctionCache:clear()
    self.cache = {}
    self.stats.hit = 0
    self.stats.miss = 0
    self.stats.evict = 0
end

-- ==================== 4. 输入处理类 ====================

local InputProcessor = {}
InputProcessor.__index = InputProcessor

function InputProcessor.new()
    local self = {
        handlers = {}
    }
    return setmetatable(self, InputProcessor)
end

function InputProcessor:register(input_type, handler)
    self.handlers[input_type] = handler
end

function InputProcessor:process(v2)
    local inputList = v2.input
    if not inputList then
        return nil, nil
    end

    local logText, input_text

    if type(inputList) == "table" then
        local handler = self.handlers[inputList[1]]
        if handler then
            logText, input_text = handler(v2, inputList)
        else
            logText = "输入变量"
            input_text = jy_param.getParamVar(inputList)
            if type(input_text) == "table" and input_text[1] == "readUI" then
                input_text = jy_ui.getUISet(input_text, input_text[3])
            end
        end
    else
        local handler = self.handlers[inputList]
        if handler then
            logText = "预定义输入"
            return handler(v2, inputList)
        end
        local success, ret = jy_task.FunRegistry.runSetFun(inputList,v2)
        if success then
            logText = "自定义函数输入"
            input_text = ret
             return logText, input_text
        end
        logText = "指定输入"
        input_text = inputList
    end

    return logText, input_text
end

-- ==================== 5. Action对象 ====================

local Action = {}
Action.__index = Action

function Action.new(v2)
    local self = {
        v2 = v2,
        config = {
            pageName = v2[1],
            wait = v2["wait"] or G.config("comSleep") or 500,
        },
        code = false,
        state = "initialized",
        history = {}
    }
    return setmetatable(self, Action)
end

function Action:__call(...)
    local param = ...
    return self:execute(param)
end

function Action:execute(param)
    self.state = "running"
    self:addHistory("start")

    local code = self:executePreRun()
    if code ~= nil then
        self.code = code
        self.state = "completed"
        self:addHistory("pre_run_exit", code)
        return code
    end

    -- code = self:checkCondition()
    -- if code ~= nil then
    --     self.code = code
    --     self.state = "completed"
    --     self:addHistory("condition_exit", code)
    --     return code
    -- end

    self:initializeCount()
    self:executeBefore()
    self:replaceParams()

    local handler = ActionFramework.strategy_manager:get(self.v2["ff"])
    if not handler then
        JY.jy_sys.acLog("未找到操作类: " .. tostring(self.v2["ff"]), self.v2)
        self.code = -1
    else
        self.code = handler(self.v2, self.config.wait)
    end

    self:executePost()

    self.state = "completed"
    self:addHistory("complete", self.code)
    return self.code
end

function Action:executePreRun()
    if self.v2["运行前方法"] == nil then return nil end

    JY.jy_sys.acLog("运行前执行：<" .. self.v2["运行前方法"] .. ">", self.v2)
    local code = jy_task.oFun(self.v2["运行前方法"], self.v2)

    if jy_task.exitRun(code, self.v2["outTaskSure"]) ~= false then
        return code
    end
    return nil
end

function Action:checkCondition()
    if self.v2["执行条件"] == nil then return nil end

    if not evaluateCondition(self.v2) then
        if not IsEmpty(self.v2["条件不满足方法"]) then
            local code = jy_task.oFun(self.v2["条件不满足方法"], self.v2)
            if jy_task.exitRun(code, self.v2["outTaskSure"]) ~= false then
                return code
            end
        end
        return 2
    end
    return nil
end

function Action:initializeCount()
    if self.v2["count"] == nil then return end

    self.v2["count"] = Iif(type(self.v2["count"]) == "table",
        jy_param.getParamVar(self.v2["count"]), self.v2["count"])
    if type(self.v2["count"]) == "string" then
        self.v2["count"] = tonumber(self.v2["count"])
    end
    if self.v2.count < 0 then
        self.v2.count = -self.v2.count
    end
end

function Action:executeBefore()
    if self.v2["fun"] ~= nil then
        jy_task.otherRun(self.v2["fun"], self.v2)
    end

    if self.v2["beforeTape"] ~= nil then
        JY.jy_sys.acLog("执行前点击: " .. self.v2["beforeTape"]["x"] .. "," .. self.v2["beforeTape"]["y"], self.v2)
        JY.jy_touch.click(self.v2["beforeTape"])
        JY.jy_sys.sleep(self.config["wait"])
    end
end

function Action:replaceParams()
    self.v2 = jy_frame.tyReload(self.v2)
    self.v2 = jy_frame.reSetR(self.v2)
end

function Action:executePost()
    if self.v2["变量赋值"] then
        jy_param.paramSet(self.v2, self.code)
    end

    if self.v2["compute"] ~= nil and self.v2["compute"] == self.code then
        acUtils.ac_comput(self.v2)
    end

    if self.code == true then
        self:acSuccess()
    else
        self:acFail()
    end

    if self.v2["success"] ~= nil and self.code == self.v2["success"] then
        jy_param.taskRecord(self.v2)
    end
end

function Action:acSuccess()
    if self.v2["input"] ~= nil then
        local logText, input_text = ActionFramework.input_processor:process(self.v2)
        if input_text == nil then
            JY.jy_sys.acLog("输入处理失败，未获取到输入内容", self.v2)
        else
            acUtils.mInput(input_text, self.v2.clean, self.v2.inputType, self.v2.model)
            JY.jy_sys.acLog("[" .. logText .. "]输入：" .. input_text, self.v2)
            JY.jy_sys.sleep(self.config["wait"])
        end
    end

    if self.v2["afterTape"] ~= nil then
        JY.jy_sys.acLog("执行后点击： " .. self.v2["afterTape"]["x"] .. " , " .. self.v2["afterTape"]["y"], self.v2)
        JY.jy_touch.click(self.v2["afterTape"])
        JY.jy_sys.sleep(self.config["wait"])
    end

    if self.v2["shake"] == true then
        JY.jy_sys.Shake()
        JY.jy_sys.sleep(self.config["wait"])
    end

    if self.v2["keyp"] ~= nil or self.v2["KeyPress"] ~= nil then
        frameSdk.keyPress(self.v2["keyp"] or self.v2["KeyPress"])
        JY.jy_sys.acLog("执行后执行KeyPress(" .. (self.v2["keyp"] or self.v2["KeyPress"]) .. ")", self.v2)
    end
    if self.v2["运行后方法"] ~= nil then
        local runLast = self.v2["运行后方法"]
        JY.jy_sys.acLog("运行后执行<" .. Iif(type(runLast) == "table", runLast[1], runLast) .. ">", self.v2)
        local code = jy_task.oFun(self.v2["运行后方法"], self.v2)

        if jy_task.exitRun(code, self.v2["outTaskSure"]) == false then
            self.code = true
        end
    end

    if self.v2["outTask"] == true then
        G.setConfig("outTask", true)
    end

    if self.v2["exitScript"] == true then
        JY.jy_sys.acLog("执行后 停止脚本", self.v2)
        frameSdk.exitScript()
    end
end

function Action:acFail()
    if self.v2["notTape"] ~= nil then
        JY.jy_sys.acLog("未找到特征点击： " .. self.v2["notTape"]["x"] .. " , " .. self.v2["notTape"]["y"], self.v2)
        JY.jy_touch.click(self.v2["notTape"])
        JY.jy_sys.sleep(self.config["wait"])
    end

    if self.v2["notFun"] ~= nil then
        local runLast = self.v2["notFun"]
        JY.jy_sys.acLog("无特征执行<" .. Iif(type(runLast) == "table", runLast[1], runLast) .. ">", self.v2)
        local code = jy_task.oFun(self.v2["notFun"], self.v2)

        if jy_task.exitRun(code, self.v2["outTaskSure"]) == false then
            self.code = false
        end
    end
end

function Action:addHistory(event, data)
    table.insert(self.history, {
        event = event,
        data = data,
        time = os.time()
    })
end

function Action:getHistory()
    return self.history
end

function Action:getState()
    return self.state
end

-- ==================== 6. 框架初始化 ====================

ActionFramework.strategy_manager = StrategyManager.new()
ActionFramework.param_validator = ParamValidator.new()
ActionFramework.input_processor = InputProcessor.new()

ActionFramework.strategy_manager:registerBatch({
    ["ps"] = function(v2) return acUtils.find.findClick(v2) end,
    ["ps_s"] = function(v2, wait) return acUtils.find.findClick_s(v2, wait) end,
    ["ft"] = function(v2) return acUtils.find.findClick(v2) end,
    ["pic"] = function(v2) return acUtils.find.findClick(v2) end,
    ["ocr"] = function(v2) return acUtils.ocr_handler(v2) end,
    ["move"] = function(v2) return acUtils.move.myMove(v2) end,
})

ActionFramework.strategy_manager:registerBatch(ACTIONS, true)

ActionFramework.param_validator:addValidator("count", function(value)
    local num = tonumber(value)
    if not num then
        error("count必须是数字")
    end
    return math.abs(num)
end)

-- 自动从 strategyMap 注册所有动作类型到验证器
ActionFramework.param_validator:addValidator("actionType", function(value)
    local valid_actions = {}
    for action_name, _ in pairs(ActionFramework.strategy_manager.strategies) do
        valid_actions[action_name] = true
    end
    if not valid_actions[value] then
        JY.jy_sys.acLog("警告: 未知的操作类型: " .. tostring(value))
    end
    return value
end)

ActionFramework.param_validator:addDefault("wait", G.config("comSleep") or 500)
ActionFramework.param_validator:addDefault("bigCount", 50)
ActionFramework.param_validator:addDefault("isClick", true)
ActionFramework.param_validator:addDefault("actionType", nil)

ActionFramework.input_processor:register(4, function(v2, inputList)
    return "自定义函数", jy_task.otherRun(inputList[2], v2)
end)

ActionFramework.input_processor:register("礼包激活码", function(v2, inputList)
    local texts = G.config("礼包激活码")
    local num = (G.vars("任务变量.礼包激活码序号") or 1) + 1

    if num < inputList[2] then
        G.setVars("任务变量.礼包激活码序号", num + 1)
        return "礼包激活码", texts[num]
    end
    return "礼包激活码", nil
end)

ActionFramework.input_processor:register("randName", function(v2)
    return "随机姓名", acUtils.rand.myRand(10, nil, nil)
end)

ActionFramework.input_processor:register("randFile", function(v2)
    local fileLines = JY.jy_file.readFileLines(v2.inputFile)
    return "文件内容", fileLines[math.random(#fileLines)]
end)

ActionFramework.cached_jy_param = {}
ActionFramework.cached_jy_param.getParamVar = FunctionCache.new(jy_param.getParamVar, {
    max_size = 200,
    ttl = 300
})

-- ==================== 7. 公共API ====================

function ActionFramework.create(v2_raw)
    local validated_v2 = ActionFramework.param_validator:createTable(v2_raw)
    return Action.new(validated_v2)
end

function ActionFramework.actions(v2)
    local action = ActionFramework.create(v2)
    return action()
end

function ActionFramework.getStats()
    return {
        strategy = ActionFramework.strategy_manager:getStats(),
        cache = ActionFramework.cached_jy_param.getParamVar:getStats()
    }
end

function ActionFramework.clearCache()
    ActionFramework.strategy_manager:clearCache()
    ActionFramework.cached_jy_param.getParamVar:clear()
end

function ActionFramework.registerStrategy(name, handler, is_lazy)
    ActionFramework.strategy_manager:register(name, handler, is_lazy)
end

return ActionFramework
