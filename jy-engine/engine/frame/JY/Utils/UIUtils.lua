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

--[[ ================== 动态UI函数 ==================]]
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
--]]

local JY = require("JY.Utils.JY")
local jy_ui = {}
local rowNum = 0
local idx = 0

--判断UI控件是否可保存设置
local function uiIsSave(view)
	if string.find(view["reqName"], "多选框", 1) ~= nil then
		return true
	end
	if string.find(view["reqName"], "下拉框", 1) ~= nil then
		return true
	end
	if string.find(view["reqName"], "输入框", 1) ~= nil then
		return true
	end
	if string.find(view["reqName"], "单选框", 1) ~= nil then
		return true
	end
	return false
end
local function addFunctions(UI)
	for key, value in pairs(UI) do
		if type(key) == "number" then
			for tap_k, view in pairs(value["controls"]) do
				if jy_ui.uiIsFunction(view) then
					if view["properties"].onChange ~= nil then
						frameSdk.ui.setOnChange(view["properties"]["name"], view["properties"].onChange)
					end

					if view["properties"]["onClick"] ~= nil then
						frameSdk.ui.setOnClick(view["properties"]["name"], view["properties"]["onClick"])
					end
				end
			end
		end
	end
end
local function checkChange(UI, uiSavePath)
	local uiSetStr = JY.jy_file.readFile(uiSavePath)
	local uiSetJson = frameSdk.jsonLib.decode(uiSetStr)
	if uiSetStr ~= nil then
		for key, value in pairs(UI) do
			if type(key) == "number" then
				for tap_k, view in pairs(value["controls"]) do
					if (view.reqName == "按钮" or view.reqName == "文字框") and uiSetJson[view["properties"]["name"]] ~= view["properties"]["text"] then
						uiSetJson[view["properties"]["name"]]=view["properties"]["text"]
					end
				end
			end
		end
		JY.jy_file.mWriteFile(uiSavePath, frameSdk.jsonLib.encode(uiSetJson))
	end
end
--判断UI控件是否可添加方法
local function uiIsFunction(view)
	if string.find(view["reqName"], "多选框", 1) ~= nil then
		return true
	end
	if string.find(view["reqName"], "下拉框", 1) ~= nil then
		return true
	end
	if string.find(view["reqName"], "输入框", 1) ~= nil then
		return true
	end
	if string.find(view["reqName"], "单选框", 1) ~= nil then
		return true
	end
	if string.find(view["reqName"], "按钮", 1) ~= nil then
		return true
	end
	return false
end

--[[添加多选框
参数：
	name : 多选框的name
	tooltip : 多选框提示内容
	sel : 是否默认选中
	w： 宽
	h： 高
返回值：
	返回多选框table
]]
local function addCheckBox(name,tooltip,sel,w,h)
	if name == nil or type(name) ~= "string" then
		error("name 参数错误")
	end
	if tooltip == nil or type(tooltip) ~= "string" then
		error("tooltip 参数错误")
	end
	idx = idx + 1
	return {
		reqName = "多选框",
		id = idx,
		properties = {
			name = name,
			tip = tooltip,
			checked = Iif(sel == nil, true, sel),
			w = w or -2,
			h = h or -2,
		}
	}
end

--[[添加输入框
参数：
	name : name
	display_text : 提示内容
	deful_text : 初始文本
	isNum: 仅输入数字
	w： 宽
	h： 高
返回值：
	返回输入框table
]]
local function addEditText(name,display_text,deful_text,isNum,w,h)
	if name == nil or type(name) ~= "string" then
		error("name 参数错误")
	end
	if display_text ~= nil and type(display_text) ~= "string" then
		error("display_text 参数错误")
	end

	if deful_text ~= nil and type(deful_text) ~= "string" then
		error("deful_text 参数错误")
	end

	idx = idx + 1
	return {
		reqName = "输入框",
		id = idx,
		properties = {
            numOnly = isNum or false,
			name = name,
			defText = deful_text or '',
			tip = display_text or '',
			w = w or -2,
			h = h or -2,
		}
	}
end

--[[添加文字框
参数：
	name : name
	display_text : 显示内容
	w： 宽
	h： 高
返回值：
	返回文字框table
]]
local function addTextView(name,display_text,w,h)
	if error == nil or type(name) ~= "string" then
		error("addTextView 【name】 参数错误")
	end
	if display_text ~= nil and type(display_text) ~= "string" then
		error("display_text 参数错误")
	end
	idx = idx + 1
	return {
		reqName = "文字框",
		id = idx,
		properties = {
			name = name,
			text = display_text,
			w = w or -2,
			h = h or -2,
		}
	}
end

--[[添加按钮
参数：
	name : name
	display_text : 显示内容
	click_handler: click_handler
	w： 宽
	h： 高
返回值：
	返回按钮table
]]
local function addButton(name,display_text,click_handler,w,h)
	if name == nil or type(name) ~= "string" then
		error("name 参数错误")
	end
	if display_text == nil or type(display_text) ~= "string" then
		error("display_text 参数错误")
	end
	if click_handler ~= nil and type(click_handler) ~= "string" then
		error("click_handler 参数错误")
	end
	idx = idx + 1
	return {
		reqName = "按钮",
		id = idx,
		properties = {
			name = name,
			text = display_text,
			onClick = click_handler,
			w = w or -2,
			h = h or -2,
		}
	}
end

--[[换行
参数：
	w： 宽
	h： 高
返回值：
	返回下拉框table
]]
local function addRow(w,h)
	rowNum = rowNum + 1
	idx = idx + 1
	return {
		reqName = "换行",
		id = idx,
		properties = {
			name = "row" .. rowNum,
			w = w or -2,
			h = h or -2,
		}
	}
end

--[[添加下拉框
参数：
	name : name
	list : 选项
	w： 宽
	h： 高
返回值：
	返回下拉框table
]]
local function addSpinner(name,list,sel,w,h)
	if name == nil or type(name) ~= "string" then
		error("naddSpinner ame 参数错误")
	end
	if list == nil or type(list) ~= "table" then
		error("addSpinner list 参数错误")
	end
	idx = idx + 1
	return {
		reqName = "下拉框",
		id = idx,
		properties = {
			selIdx = sel or 0,
			name = name,
			list = list,
			w = w or -2,
			h = h or -2,
		}
	}
end

local strategy = {
	["按钮"] = function (v,layout) frameSdk.ui.addButton(layout, v.name, v.text,v.w, v.h) end,
	["多选框"] = function(v,layout) frameSdk.ui.addCheckBox(layout, v.name, v.tip, v.checked, v.w, v.h) end,
	["下拉框"] = function(v,layout) frameSdk.ui.addSpinner(layout, v.name, v.list, v.selIdx, v.w, v.h) end,
	["输入框"] = function(v,layout) frameSdk.ui.addEditText(layout, v.name, v.tip,  v.w, v.h) end,
	["换行"] = function(v,layout) frameSdk.ui.newRow(layout,v.name, v.w, v.h) end,
	["文字框"] = function(v,layout) frameSdk.ui.addTextView(layout, v.name, v.text, v.w, v.h) end,
}

--UI生成器
local function createUIView(view,layout)
	if string.find(view["reqName"], "多选框", 1) ~= nil then
		view["reqName"] = "多选框"
	end
	if string.find(view["reqName"], "下拉框", 1) ~= nil then
		view["reqName"] = "下拉框"
	end
	if string.find(view["reqName"], "输入框", 1) ~= nil then
		view["reqName"] = "输入框"
	end
	if string.find(view["reqName"], "换行", 1) ~= nil then
		view["reqName"] = "换行"
	end
	if string.find(view["reqName"], "文字框", 1) ~= nil then
		view["reqName"] = "文字框"
	end

	if string.find(view["reqName"], "按钮", 1) ~= nil then
		view["reqName"] = "按钮"
	end
	-- 执行策略并返回结果
	local handler = strategy[view["reqName"]]
	if handler ~= nil then
		handler(view["properties"], layout)
	end

end

--[[读取用户UI设置
参数：
	name : UI的name
	defVar : 默认值
返回值：
	返回用户UI设置值
]]
local function getUISet(name,defVar)
	local ret = nil
	-- 现在静态UI保存的值中查找name
	local arr = frameSdk.getUIConfig("main.config")
	if not IsEmpty(arr) then
		arr = frameSdk.jsonLib.decode(arr)
		for key, value in pairs(arr) do
			if not IsEmpty(value[name]) then
				return value[name]
			end
		end
	end


	--静态UI中没有查找对应值，再到动态UI中查找
	local UISavaPath = JY.jy_file.fileGet("UISavaPath",nil)
	if IsEmpty(UISavaPath) or not frameSdk.fileExist(UISavaPath) then
		print("无法获取UI设置，请先运行一次UI，再启动脚本, 设置名:" .. name)
		frameSdk.exitScript()
	end
	arr = UISavaPath and JY.jy_file.readFile(UISavaPath)
	if arr then
		arr = frameSdk.jsonLib.decode(arr)
		if not IsEmpty(arr[name]) then
			ret = arr[name]
		end
	end

	-- 如果都没查找到，返回默认值
	if IsEmpty(ret) then
		ret = defVar
	end
	return ret

end
jy_ui.getUISet = getUISet
jy_ui.uiIsSave = uiIsSave
jy_ui.uiIsFunction = uiIsFunction
jy_ui.addCheckBox = addCheckBox
jy_ui.addEditText = addEditText
jy_ui.addTextView = addTextView
jy_ui.addButton = addButton
jy_ui.addRow = addRow
jy_ui.addSpinner = addSpinner
jy_ui.createUIView = createUIView
jy_ui.addFunctions = addFunctions
jy_ui.checkChange = checkChange

return jy_ui
