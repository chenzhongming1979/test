--[[

	简游全模块框架

]]


--[[
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

作者：老污龟
版本：1.0.0
QQ群：855026423
微信：jianxin_data
开源地址：https://gitee.com/lwg_6/JYScript
--]]

local JY = require("JY.Utils.JY")
local script_task = require("JY.Process.TaskProcessControl")
local TaskScheduler = require("JY.Task.TaskScheduler")
local jy_frame = require("JY.Utils.FrameUtils")

local run = {}

--模块测试
function run.pageTest(pageName)
	local taps = G.taps()
	if taps[pageName] ~= nil then
        local lotStr =  "进入任务 ***************** 进入任务 【 " ..pageName .. " 】"
		JY.jy_sys.mlog(lotStr, "pageTest", nil)
		TaskScheduler.new():runWait(taps[pageName])
    else
        local lotStr = "进入任务 ***************** 进入任务 【 " .. pageName .. " 】失败,无此任务,核对任务名"
		JY.jy_sys.mlog(lotStr, "pageTest", nil)
    end
end

--任务测试
function run.taskTest(taskName)
	local task = nil
	local taps = G.taps()
	for key, value in pairs(taps["任务列表"]) do
		if type(value) == "table" and value[1] == taskName then
			task = value
		end
	end
	if task == nil then
		JY.jy_sys.mlog("任务名错误，请检查任务列表和任务名是否匹配：" .. taskName, "taskTest", nil)
	end

	local lodingCode
    if task ~= nil then
        -- 处理任务参数
        if task["任务参数"]["执行模式"] == nil and type(task["任务参数"]) == "table" then
            task["任务参数"] = jy_frame.taskParamSet(
                task["任务参数"][1], task["任务参数"][2], task["任务参数"][3],
                task["任务参数"][4], task["任务参数"][5], task["任务参数"][6],
                task["任务参数"][7], task["任务参数"][8], task["任务参数"][9]
            )
        end

        -- 执行前置操作
        if task["任务参数"]["任务执行前操作"] ~= nil then
			TaskScheduler.new():runWait(task["任务参数"]["任务执行前操作"])
        end

		local ActionFramework = require("JY.Actions.ActionFramework")
        -- 执行主任务逻辑
        lodingCode = ActionFramework.actions(task)
        if lodingCode and lodingCode ~= 2 then
            script_task.taskRun(task)

        end
		TaskScheduler.new():runWait("通用任务退出")
    else
        JY.jy_sys.mlog("任务调试***************** 无此任务： 【 " .. task .. " 】", "taskTest", nil)
    end
end

--启动脚本
function run.scriptRun(mTaps , mConf , mVars)
	--框架环境初始化
	EnvInit(mTaps , mConf , mVars)
    if mConf["游戏模式"] == 5 then
        local model = require("JY/Process/Model_5")
        model.run()
    else
        local model = require("JY/Process/Model")
        model.run()
    end
end

return run