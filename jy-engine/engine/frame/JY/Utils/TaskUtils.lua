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

local jy_frame = require("JY.Utils.FrameUtils")

local jy_task = {}

-- 函数注册表模块：实现函数的动态注册、注销和安全调用
local FunRegistry = {
    -- 存储注册的函数表
    funs = {}
}


-- pageWaitOut 函数
local function pageWaitOut(pages , wo)
	local code = false
	local t = frameSdk.systemTime()
	local waitOut = wo or G.config("timeout") or 2000

	while true do
		for k , v in pairs(pages) do
			v = jy_frame.tyReload(v)
			if frameSdk.systemTime() - t > waitOut then
				return code
			end
			local ActionFramework = require("JY.Actions.ActionFramework")
			if ActionFramework.actions(v) then
				code = true
				return code
			end
		end
		JY.jy_sys.sleep(300)
	end

	return code
end
jy_task.pageWaitOut = pageWaitOut

-- 注册函数
-- @param funName string 函数名（唯一标识）
-- @param func function 要注册的函数
-- @return boolean 注册是否成功
function FunRegistry.addFun(funName, func)
    -- 严格的参数类型校验
    if type(funName) ~= "string" or funName == "" then
        error("函数名必须是非空字符串")
    end

    if type(func) ~= "function" then
        error("注册的必须是函数类型")
    end

    -- 注册函数并返回成功状态
    FunRegistry.funs[funName] = func
    return true
end

-- 注销函数
-- @param funName string 要注销的函数名
-- @return boolean 注销是否成功
function FunRegistry.removeFun(funName)
    if FunRegistry.funs[funName] then
        FunRegistry.funs[funName] = nil
        return true
    end
    return false
end

-- 安全调用已注册的函数
-- @param funName string 要调用的函数名
-- @param v any 传递给函数的参数
-- @return number/boolean 执行结果（成功返回函数执行结果，失败返回false）
-- @return string 错误信息（仅在失败时返回）
function FunRegistry.runSetFun(funName, v)
    -- 参数校验
    if type(funName) ~= "string" then
        return false, "函数名必须是字符串类型"
    end

    -- 检查函数是否存在
    local func = FunRegistry.funs[funName]
    if not func then
        return false, "函数[" .. funName .. "]未注册"
    end

    -- 使用pcall安全调用函数，防止执行异常导致程序崩溃
    local success, ret = pcall(func, v)
    if not success then
        return false, "函数[" .. funName .. "]执行出错: " .. ret
    end

    -- 返回函数执行结果
    return ret
end

-- 可选：清空所有注册的函数
function FunRegistry.clearAll()
    FunRegistry.funs = {}
end
jy_task.FunRegistry = FunRegistry

--强制退出任务链判断
local function exitRun(code , outTaskSure)
	if outTaskSure ~= false and ( code == 12 or code == 13 or code == 14 ) then
		return code
	end
	return false
end
jy_task.exitRun = exitRun
--特征内调用其他方法通用
local function oFun(runLast , v2)
	local lastCode
	local code = true
	-- 处理表类型方法
	if type(runLast) == "table" then
		lastCode = jy_task.otherRun(runLast , v2)

		-- 特殊跳转条件
		if lastCode ~= runLast[2] then
			code = lastCode
		end
		-- 处理非表类型方法
	else
		lastCode = jy_task.otherRun(runLast , v2)
	end
	-- 主条件判断逻辑
	if exitRun(lastCode , v2["outTaskSure"]) ~= false then
		code = lastCode
	end
	return code
end
jy_task.oFun = oFun

local function otherRun(task , v2)
	local returnCode = - 1
	local runTast = nil
	local view = Iif(v2 ~= nil , v2[1] , nil)
	local taps = G.taps()
	if taps[task] ~= nil then
        local TaskScheduler = require("JY.Task.TaskScheduler")
		jy_frame.setHUDTextWrapper(G.runtime("HUDID") , task)
        returnCode = TaskScheduler.new():runWait(taps[task], nil)
	elseif jy_task.FunRegistry.funs[task] ~= nil then
		returnCode = jy_task.FunRegistry.runSetFun(task , v2)
	end
	if returnCode == - 1 then
		runTast = task or v2[1] or v2["name"] or G.config("当前任务") or "未知页面"
		JY.jy_sys.mlog("未找到<" .. runTast .. ">页面，请检查对应页面name是否匹配" , task , view)
	end
	return returnCode
end
jy_task.otherRun = otherRun


return jy_task