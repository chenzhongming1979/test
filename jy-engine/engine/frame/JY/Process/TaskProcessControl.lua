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
local TaskScheduler = require("JY.Task.TaskScheduler")
local jy_frame = require("JY.Utils.FrameUtils")
local jy_task = require("JY.Utils.TaskUtils")
local condUtil = require("JY.Utils.ConditionUtils")
local evaluateCondition = condUtil.evaluateCondition
local ActionFramework = require("JY.Actions.ActionFramework")
local script_task = {}
local jy_param = require("JY.Utils.ParamUtils")
-- =======================
-- TaskScheduler 实例（模块加载时统一初始化）
-- =======================
local scheduler = nil

--- 初始化调度器（内部方法，模块加载时调用）
local function initScheduler()
    if scheduler then return end
    scheduler = TaskScheduler.new({
        defaultTimeout = 2000,
        onRunList = function(pageObject)
            return script_task.runList(pageObject)
        end
    })
end

--- 获取 TaskScheduler 实例
--- @return TaskScheduler
local function getScheduler()
    if not scheduler then
        initScheduler()
    end
    return scheduler
end

-- =======================
-- 业务逻辑方法
-- =======================

-- 任务执行前检查
local function taskRunCheck(v)
	local executeCondition = true
	-- 执行条件判断
	if v["执行条件"] ~= nil and not evaluateCondition(v) then
		JY.jy_sys.mlog("█【进入任务】===================== 【 "..v[1].." 】失败，不满足执行条件")
		executeCondition = false
	end

	-- 任务是否执行判断
	executeCondition = executeCondition and jy_param.getTastState(v)

	local exitCode = true
	if executeCondition then
		exitCode = jy_task.exitRun(v, G.config())
		return not exitCode
	end
	return executeCondition
end

-- =======================
-- runWait 方法（委托给 TaskScheduler）
-- =======================

--- 任务入口判断和分类处理
--- @param page string|table 页面名称或页面对象
--- @return number|boolean 执行结果
local function runWait(page)
	local TaskScheduler = require("JY.Task.TaskScheduler")
	return TaskScheduler.new():runWait(page)
end

-- 任务执行函数
local function taskRun(task)
	local ret = false
	local tastName
	if type(task) == "table" then
		tastName = task["任务参数"]["执行方法"] or task[1]
	elseif type(task) == "string" then
		tastName = task
	end


	if G.taps()[tastName] == nil then
		JY.jy_sys.mlog("执行任务***************** 【" .. tastName .. " 】 未匹配到任务，检查对应任务name是否匹配1")
		return false
	end
	JY.jy_sys.mlog("执行任务***************** 【" .. tastName .. " 】")
	return runWait(G.taps()[tastName])

end
script_task.taskRun = taskRun

--执行任务列表
local function runList(taskList)
	local code = 0
    local loadingCode
	local errCount = 0
	local quitTimeoutCount = G.config("通用任务退出超时次数") or 3
	local quitTimeoutFun = G.config("通用任务退出超时方法") or "restart"
	for i , v in pairs(taskList) do
		G.setRuntime("gameCheck.gameState", 1)
		if type(v) ~= "table" then
			goto continue
		end

		if taskRunCheck(v) then
			G.setConfig("当前任务", v[1])
			if G.config("当前任务链") ~= nil then
				G.setConfig("当前任务链", v[1] .. "-->")
			end

			if v["任务参数"]["任务执行前操作"] ~= nil then
				local TaskScheduler = require("JY.Task.TaskScheduler")
				code = TaskScheduler.new():runWait(v["任务参数"]["任务执行前操作"], nil)
				if code == 14 or code == 13 then
					return code
				end
			end
			loadingCode = ActionFramework.actions(v)
			if loadingCode == true then  -- 注意：Lua 中 true 是独立类型

				if G.config("显示浮窗") then
					jy_frame.setHUDText(G.runtime("HUDID") , v[1])
				end

				local code = script_task.taskRun(v)
				if code == 14 then
					return code
				end
				G.setConfig("当前任务", "通用任务退出")
				local TaskScheduler = require("JY.Task.TaskScheduler")
				code = TaskScheduler.new():runWait("通用任务退出", nil)
				if code == 6 then
					errCount = errCount + 1
				else
					errCount = 0
				end

				if errCount > quitTimeoutCount then
					JY.jy_sys.mlog("█【任务异常】===================== 通用任务退出连续超时，执行通用任务退出超时方法")
					local errCode = jy_task.otherRun(quitTimeoutFun, v)
					if errCode == 6 then
						return
					end
				end
			else
				JY.jy_sys.mlog("█【进入任务】【" .. v[1] .. "】===================== 失败，未找到任务特征。")
				local TaskScheduler = require("JY.Task.TaskScheduler")
				TaskScheduler.new():runWait("通用任务退出", nil)
			end
		end

		-- 任务间隔
		if G.config("任务间隔") then
			frameSdk.sleep(G.config("任务间隔"))
		end
		::continue::
	end
	return code
end
script_task.runList = runList

-- =======================
-- 任务加载入口
-- =======================

local function taskLoading()
	local taskList
	-- 根据定时任务状态选择任务列表
	if G.config("定时任务状态") == 0 then
		taskList = G.taps()["任务列表"]
	else
		taskList = G.taps()["定时任务"]
	end

	-- 处理任务屏幕并获取所有操作点
	-- taps = taskScreen()

	-- 创建任务执行列表的深拷贝
	local execList = JY.jy_tab.deepCopy( taskList)
	local wait = 500

	-- 注释掉的条件过滤逻辑（保留原注释）
	--[[
	if conf["任务检查"] then
	execList = 任务执行条件过滤(execList)
	end
	--]]

	-- 随机化任务顺序
	if G.config("随机任务") then
		execList = JY.jy_tab.shuffle( execList)
	end

	-- 初始化变量
	local code = 0
	frameSdk.sleep(2000) -- 等待延迟

	-- 设置共享变量和执行任务
	code = script_task.runList(execList)
	-- 游戏退出逻辑
	if G.config("游戏模式") ~= 5 and code ~= 13 and code ~= 14 and code ~= 6 then
		local TaskScheduler = require("JY.Task.TaskScheduler")
		TaskScheduler.new():runWait("退出游戏")
	end

	-- 更新游戏状态并返回结果
	return code
end

script_task.taskLoading = taskLoading

-- =======================
-- 模块导出
-- =======================

--- 快速执行任务（便捷方法，简洁调用入口）
--- @param page string|table 页面名称或页面对象
--- @return number|boolean 执行结果
function script_task.runWait(page)
    return runWait(page)
end

return script_task