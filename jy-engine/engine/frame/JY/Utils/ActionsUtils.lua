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
local jy_ui = require("JY.Utils.UIUtils")
local jy_frame = require("JY.Utils.FrameUtils")
local jy_param = require("JY.Utils.ParamUtils")

local jy_task = require("JY.Utils.TaskUtils")
local condUtil = require("JY.Utils.ConditionUtils")
local evaluateCondition = condUtil.evaluateCondition

--找色策略配置
local FIND_POINT_KEYS = {
	["ft"] = function(v2) return jy_frame.myFind(v2) end ,
	["fc"] = function(v2) return jy_frame.MFindColor(v2) end ,
	["pic"] = function(v2) return jy_frame.mFindPic(v2) end ,
	["fs"] = function(v2) return jy_frame.findStr(v2) end ,
	["ps"] = function(v2) return jy_frame.mCmpColorEx(v2) end,
	-- D1 修复（FireFly 移植版）：登记 yolo 单点查找（原版缺失，yolo/yolo_c 会走 error 分支）
	["yolo"] = function(v2) return yolo.find(v2) end ,
	["yolo_c"] = function(v2) return yolo.find(v2) end ,
	["gcn"] = function(v2)
		local colorNum = jy_frame.findColorNum(v2)
		if v2["colorNum"] + 0 < colorNum + 0 then
			JY.jy_sys.acLog("颜色数量 = " .. colorNum .." > " .. v2.colorNum,v2)
			if v2["clickRange"] ~= nil then
				v2["tape"] = jy_frame.clickRange(v2["clickRange"])
			end

			if v2["tape"] ~= nil then
				return v2["tape"]
			else
				return Point(-2,-2)
			end
		end
		return Point(-1,-1)
	end ,
}

--找全部坐标策略配置
local FIND_ALL_KEYS = {
	["pic_res"] = function(v2) return jy_frame.mFindPic_all(v2) end ,
	["pic_cl"] = function(v2) return jy_frame.mFindPic_all(v2) end ,
	["pic_r"] = function(v2) return jy_frame.mFindPic_all(v2) end ,
	["pic_a"] = function(v2) return jy_frame.mFindPic_all(v2) end ,
	["pic_num"] = function(v2) return jy_frame.mFindPic_all(v2) end ,
	["pic_ex_f"] = function(v2) return jy_frame.mFindPic_all(v2) end ,

	["fs_r"] = function(v2) return jy_frame.findStr_all(v2) end ,
	["fs_cl"] = function(v2) return jy_frame.findStr_all(v2) end ,
	["fs_res"] = function(v2) return jy_frame.findStr_all(v2) end ,
	["fs_a"] = function(v2) return jy_frame.findStr_all(v2) end ,
	["fs_ex_f"] = function(v2) return jy_frame.findStr_all(v2) end ,

	["ft_a"] = function(v2) return jy_frame.myFind_all(v2) end ,
	["ft_r"] = function(v2) return jy_frame.myFind_all(v2) end ,
	["ft_cl"] = function(v2) return jy_frame.myFind_all(v2) end ,
	["ft_res"] = function(v2) return jy_frame.myFind_all(v2) end ,
	["ft_num"] = function(v2) return jy_frame.myFind_all(v2) end ,
	["ft_ex_f"] = function(v2) return jy_frame.myFind_all(v2) end ,

	["pic_re"] = function(v2) return { jy_frame.mFindPic(v2) } end
}




local find = {}

local function find_p(v2,isDc)
	local ff = frameSdk.splitStr(v2["ff"] , "_")
	local findType = Iif(isDc == true, ff[2], ff[1])
	-- 执行对应的查找策略
	if findType and FIND_POINT_KEYS[findType] then
		return FIND_POINT_KEYS[findType](v2)
	else
		error("未找到指定图色命令，检查【ff】 对应的命令是否正确 ")
	end

	return Point{-1,-1}
end
find.find_p = find_p

local function find_a(v2)
	-- 执行查找并返回结果
	local handler = FIND_ALL_KEYS[v2["ff"]]
	local ret = handler(v2)
	ret = JY.jy_tab.tableSizer(ret , v2["diff"] or 20)
	return ret
end
find.find_a = find_a

-- 排除点检查
local function ex_p(v2)
	local p = find_p(v2)
	if p["x"] > 0 then
		local ex = v2["ex"]
		local x_b = (p["x"] > ex[1] and p["x"] < ex[3]) or (p["x"] < ex[1] and p["x"] > ex[3])
		local y_b = (p["y"] > ex[2] and p["y"] < ex[4]) or (p["y"] < ex[2] and p["y"] > ex[4])
		if x_b and y_b then
			p["x"] = - 2
			p["y"] = - 2
		else
			return Point(p.x, p.y)
		end
	end


	return p
end
local function findClick(v2)
	-- 获取查找类型
	local point = find_p(v2)

	if v2["执行前点击"] ~=nil then
		jy_frame.comClick(v2["执行前点击"],v2)
	end

	if point["x"] > 0 then
		jy_frame.comClick(point,v2)
		return true
	elseif point["x"] == -2 then
		return true
	end
	return false -- 默认返回false
end
find.findClick = findClick

--[[
函数名: findClick_w
功能描述: 等待找色点击 - 在指定超时时间内循环查找特征并点击，找到后立即返回

参数说明:
    v (table): 特征对象，包含以下可选字段
        - waitSleep (number): 每次查找间隔时间(毫秒)，默认500ms
        - waitOut (number): 超时时间(毫秒)，默认使用conf["timeout"]或2000ms
        - 其他findClick所需参数（如ff、p、degree等图色参数）

返回值:
    boolean: true表示找到特征并点击成功，false表示超时未找到

使用示例:
    -- 等待最多3秒查找并点击特征
    local result = findClick_w({
        "特征名称", 1,
        ff = "ps",
        p = "100|200|0xffffff",
        degree = 0.9,
        waitOut = 3000,
        waitSleep = 200
    })
]]
local function findClick_w(v)
	local code = false
	local sleepTime = v["waitSleep"] or 500
	local t = frameSdk.systemTime()
	local waitOut1 = v["waitOut"] or G.config("timeout") or 2000
	local c = false

	while true do
		if frameSdk.systemTime() - t > waitOut1 then
			break
		end
		c = findClick(v)
		if c then
			code = true
			break
		end
		JY.jy_sys.sleep(sleepTime)
	end

	return code
end
find.findClick_w = findClick_w

--[[
函数名: findClick_s
功能描述: 循环找色点击 - 在找到特征后持续点击，直到特征消失或达到最大点击次数

参数说明:
    v2 (table): 特征对象，包含以下可选字段
        - bigCount (number): 最大点击次数，默认50次
        - 执行条件 (table): 循环执行条件，不满足时退出循环
        - 每次点击后方法 (string): 每次点击后执行的方法名
        - outTaskSure (boolean): 是否阻止退出流程
        - eWait (number): 最终延迟时间(毫秒)
        - 其他findClick所需参数（如ff、p、degree等图色参数）
    wait (number): 可选，最终延迟时间(毫秒)

返回值:
    boolean: true表示至少成功点击一次，false表示未找到特征

使用示例:
    -- 循环点击最多20次，每次点击后执行"检查完成"方法
    local result = findClick_s({
        "特征名称", 1,
        ff = "ps",
        p = "100|200|0xffffff",
        bigCount = 20,
        每次点击后方法 = "检查完成"
    }, 500)
]]
local function findClick_s(v2, wait)
	-- 初始化变量
	local ftsCount = v2["bigCount"] or 50
	local ftsnum = 1
	local code = false
	local cvars

	-- 释放捕获（假设为外部函数）
	frameSdk.releaseCapture()

	-- 主循环
	while true do
		-- 检查循环次数限制
		if ftsnum > ftsCount then
			break
		end

		-- 检查执行条件
		if v2["执行条件"] ~= nil then
			if not evaluateCondition(v2) then -- 假设为外部条件检查函数
				JY.jy_sys.acLog("循环中不满足执行条件，退出循环" , v2)
				break
			end
		end

		-- 执行点击操作
		if not findClick(v2) then -- 假设为外部点击函数
			break
		else
			code = true
			if v2["每次点击后方法"] ~= nil then
				code = jy_task.oFun(v2["每次点击后方法"] , v2)
				if jy_task.exitRun(code , v2["outTaskSure"]) then
					JY.jy_sys.acLog("循环中不满足执行条件，退出循环" , v2)
					break
				end
			end
		end
		ftsnum = ftsnum + 1
	end

	-- 最终延迟处理
	if code == true then
		JY.jy_sys.sleep(v2["eWait"] or wait or 0) -- 假设为休眠函数
	end
	return code
end
find.findClick_s = findClick_s

--[[
函数名: findClick_a
功能描述: 全找点击 - 查找所有匹配的特征坐标并依次点击

参数说明:
    v2 (table): 特征对象，包含以下可选字段
        - bigCount (number): 最大点击次数，默认20次
        - count (number): 每个坐标点击次数，默认1次
        - countSleep (number): 多次点击间隔时间(毫秒)
        - offset (table): 坐标偏移 {x, y} 或 {x=, y=}
        - 执行条件 (table): 循环执行条件，不满足时退出循环
        - 每次点击后方法 (string): 每次点击后执行的方法名
        - outTaskSure (boolean): 是否阻止退出流程
        - 其他find_a所需参数（如ff、picName等图色参数）
    wait (number): 可选，点击间隔时间(毫秒)

返回值:
    boolean: true表示至少成功点击一次，false表示未找到任何特征

使用示例:
    -- 查找所有匹配图片并依次点击，每个坐标点击2次
    local result = findClick_a({
        "特征名称", 1,
        ff = "pic_a",
        picName = "button.png",
        bigCount = 10,
        count = 2,
        countSleep = 200,
        offset = {x = 5, y = 5}
    })
]]
local function findClick_a(v2, wait)
	local points = find_a(v2)
	local bigCount = v2["bigCount"] or 20
	local clickOutCode = true
	local wait = v2.countSleep or G.config("comWait") or wait
	local checkp
	local clickCount = 1
	if #points < 1 then
		clickOutCode = false
	end
	for k , v in pairs(points) do
		checkp = find_a(v2)
		if v2["执行条件"] ~= nil then
			if not evaluateCondition(v2) then
				JY.jy_sys.acLog("循环点击, 不满足执行条件退出点击" , v2)
				break
			end
		end
		if #checkp < 1 then
			JY.jy_sys.acLog("循环点击 检查后退出循环" , v2)
			break
		end
		if k > bigCount then
			JY.jy_sys.acLog("循环点击 已超过最大点击次数" , v2)
			break
		end

		if v2["offset"] ~= nil then
			local offset = v2["offset"]
			v["x"] = v["x"] + offset["x"] or offset[1]
			v["y"] = v["y"] + offset["y"] or offset[2]
		end
		if v2.count ~= nil and type(v2.count) == "number" then
			clickCount = v2.count
		end
		for i = 1, clickCount, 1 do
			JY.jy_touch.click(v)
			JY.jy_sys.sleep(wait)
		end
		JY.jy_sys.acLog("循环点击 第" .. k .. " 次 ,坐标" .. v["x"] .. "，" .. v["y"] .. "点击" .. clickCount .. "次" , v2)


		if v2["每次点击后方法"] ~= nil then
			JY.jy_sys.acLog("循环点击 执行 每次点击后方法【" .. v2["每次点击后方法"] .. "】" , v2)
			clickOutCode = jy_task.oFun(v2["每次点击后方法"] , v2)
			if jy_task.exitRun(clickOutCode , v2["outTaskSure"]) then
				JY.jy_sys.acLog("循环点击中检测到强制退出，退出任务" , v2)
				return clickOutCode
			end
		end
	end
	return clickOutCode
end
find.findClick_a = findClick_a

local function findClick_r(v2, wait)
	local code = false
	local psss = find_a(v2)
	if #psss > 0 then
		local n = JY.jy_tab.arrayRand(psss)

		JY.jy_sys.acLog("随机点击坐标：" ..  n.x ..",".. n.y, v2)
		JY.click(n)
		JY.jy_sys.sleep(wait)
		code = true
	end
	return code
end
find.findClick_r = findClick_r

local function findClick_l(v2, wait)
	local code = false
	local psss = find_a(v2)
	local idx = v2["index"]

	if type(idx) == "table" then
		idx = jy_param.getParamVar(v2["index"])
	end

	if psss[idx] ~= nil then
		JY.jy_sys.acLog("随机点击第" .. idx.."个坐标：" ..  psss[idx].x ..",".. psss[idx].y, v2)
		JY.click(psss[idx])
		JY.jy_sys.sleep(wait)
		code = true
	end

	return code
end
find.findClick_l = findClick_l

local function find_write_vars(key , value , defult)
	if key == nil then
		G.setVars("任务变量." .. defult, value)
	elseif type(key) == "string" then
		G.setVars("任务变量." .. key, value)
	else
		G.setVars(key[1] .. "." .. key[2], value)
	end
end
find.find_write_vars = find_write_vars

local function find_wv(v2)
	local code = false
	local resPoints

	local t = frameSdk.systemTime()
	local waitOut1 = v2["waitOut"] or G.config("timeout") or 2000
	local sleepTime = v2["waitSleep"] or 100

	while true do
		if frameSdk.systemTime() - t > waitOut1 then
			break
		end
		resPoints = find_a(v2)
		if #resPoints > 0 then
			code = true
			break
		end
		JY.jy_sys.sleep(sleepTime)
	end
	if v2["log"] == true then
		JY.jy_sys.acLog("wv 方法查找坐标个数：" .. #resPoints , v2)
	end
	JY.jy_sys.acLog("【" .. v2["ff"] .. "】 获取到的个数：" .. #resPoints , v2)
	return code
end
find.find_wv = find_wv

local function find_res(v2)
	local code = false
	local points = {}
	local writePoint = v2.writePoint
	if v2["add"] == true or writePoint[3] == 1 then
		points = G.vars(writePoint[1] .. "." .. writePoint[2]) or {}
	end

	local resPoints = find_a(v2)

	if #resPoints > 0 then
		for k , v in pairs(resPoints) do
			if v2["offset"] ~= nil then
				v["x"] = v["x"] + v2["offset"]["x"]
				v["y"] = v["y"] + v2["offset"]["y"]
			end

			v["name"] = v2[1]

			if v2["log"] == true then
				JY.jy_sys.acLog("[" .. k .. "] = ".. v["x"].."," .. v["y"] , v2)
			end

			table.insert(points , v)
		end
		code = true
	end

	JY.jy_sys.acLog("【" .. v2["ff"] .. "】 获取到的个数：" .. #points , v2)
	find_write_vars(v2["writePoint"] , points , v2[1])

	return code
end
find.find_res = find_res

local function find_v(v2)
	local resPoints = find_p(v2)
	if resPoints == nil or resPoints["x"] == - 1 then
		return false
	end

	JY.jy_sys.acLog("【" .. v2["ff"] .. "】 获取到的个数：" .. #resPoints , v2)
	find_write_vars(v2["writePoint"] or v2["wp"] , resPoints , v2[1])

	return true
end
find.find_v = find_v

local function find_num(v2)
	local code = false
	local numPoints = find_a(v2)

	find_write_vars(v2["writeNum"] , #numPoints , v2[1])

	JY.jy_sys.acLog("【" .. v2["ff"] .. "】 获取到的个数：" .. #numPoints , v2)

	local setNum = v2["sNum"]
	if #numPoints > 0 and ((setNum ~= nil and setNum > #numPoints) or (setNum == nil)) then
		local swait = v2["wait"] or G.config("comSleep")
		local tape = nil
		if v2["tape"] ~= nil then
			tape = v2["tape"]
		end
		if v2["clickRange"] then
			tape = jy_frame.clickRange(v2["clickRange"])
		end

		if tape ~= nil then
			JY.click(tape)
			JY.jy_sys.sleep(swait)
		end
		code = true
	end
	return code
end
find.find_num = find_num

local function find_ex_m(v2, wait)
	local code = false
	local clickOutCode
	local p = ex_p(v2)

	if p["x"] > - 1 then
		jy_frame.comClick(p , v2)
		code = true
	elseif p["x"] == - 1 then
		code = false
	elseif p["x"] == - 2 then
		if not IsEmpty(v2["exFun"] ) then
			clickOutCode = jy_task.oFun(v2["exFun"] , v2)
			if jy_task.exitRun(clickOutCode , v2["outTaskSure"]) then
				JY.jy_sys.acLog("exFun 执行中检测到强制退出，退出任务" , v2)
				return clickOutCode
			end
		end
		p = ex_p(v2)
		if p["x"] > - 1 then
			jy_frame.comClick(p , v2)
			code = true
		end
	end

	return code
end
find.find_ex_m = find_ex_m

local function moreFind_dc(v2)
    local code = false
    local otherRunCode

    -- 调用图像查找函数

    -- 提取配置参数
    local offset1 = v2.offset1 or {0, 0}
    local offset2 = v2.offset2 or {0, 0}
    local dcNotFun = v2.dcNotFun
    local dcFun = v2.dcFun
    local features
    local dcNextFun = frameSdk.splitStr(dcFun, "-")

    local p = find.dc_find(v2,dcNextFun[1])

    dcNextFun = dcNextFun[#dcNextFun] or ""
    local ocrWrite, ocrSetParam

    -- 如果找到了图像特征
    if p.x and p.x > 0 then
        -- 计算坐标
        local x1 = p.x + (offset1[1] or 0)
        local y1 = p.y + (offset1[2] or 0)
        local x2 = p.x + (offset2[1] or 0)
        local y2 = p.y + (offset2[2] or 0)

        local color, picName, dcOcrParams, dcOne
        local ocrParams = {}

        -- 根据功能类型执行不同的处理
        if string.find(dcNextFun, "ft") then
            color = v2.dcColor
            dcOne = v2.dcOne
            features = {
                v2[1] or 0, v2[2] or 0,
                ff = dcNextFun,
                degree = v2.degree or 0,
                one = dcOne,
                ct = color,
                r = {x1, y1, x2, y2}
            }

            -- 合并额外参数
            for k, v in pairs(v2) do
                local t = type(k)
                if t ~= "number" and k ~= "ff" and k ~= "one" and k ~= "ct" then
                    features[k] = v
                end
            end

        elseif string.find(dcNextFun, "pic") then
            color = v2.deColor
            picName = v2.dcPicName
            features = {
                v2[1] or 0, v2[2] or 0,
                ff = dcNextFun,
                isClick = v2.isClick or false,
                clickRange = v2.clickRange or {},
                tape = v2.tape or 0,
                wait = v2.wait or 0,
                offset = v2.dcOffset or {0, 0},
                r = {x1, y1, x2, y2},
                delta_color = color,
                degree = v2.degree or 0,
                picName = picName
            }

        elseif string.find(dcNextFun, "fc") then
            color = v2.dcColor
            features = {
                v2[1] or 0, v2[2] or 0,
                ff = dcNextFun,
                r = {x1, y1, x2, y2},
                offset = v2.dcOffset or {0, 0},
                wait = v2.wait or 0,
                color = color,
                orient = v2.orient or 0,
                degree = v2.degree or 0
            }

        elseif string.find(dcNextFun, "ocr") then
            dcOcrParams = v2.ocrParams or {}
            ocrParams = {
                x1, y1, x2, y2,
                dcOcrParams[5] or 0,
                dcOcrParams[6] or 0
            }

            ocrWrite = v2.ocrWrite
            if not ocrWrite then
                ocrWrite = {1, "任务变量", v2[1] or 0, -1}
            end

            features = {
                v2[1] or 0, v2[2] or 0,
                ff = "ocr",
				ocrEx= v2.ocrEx,
				padding= v2.padding,
				rgb= v2.rgb,
                ocrType = v2.ocrType or 0,
                ocrParams = ocrParams,
                ocrWrite = v2.ocrWrite
            }

        elseif string.find(dcNextFun, "fs") then
            dcOcrParams = v2.ocrParams or {}
            ocrParams = {
                x1, y1, x2, y2,
                dcOcrParams[5] or 0,
                dcOcrParams[6] or 0
            }

            features = {
                v2[1] or 0, v2[2] or 0,
                ff = dcNextFun,
                isClick = v2.isClick or false,
                clickRange = v2.clickRange or {},
                tape = v2.tape or 0,
                offset = v2.dcOffset or {0, 0},
                wait = v2.wait or 0,
                degree = v2.degree or 0,
                ocrParams = ocrParams
            }
        end

        -- 执行图像处理操作
        if features then
			local ActionFramework = require("JY.Actions.ActionFramework")
            code = ActionFramework.actions(features)
        end

    elseif dcNotFun then
        -- 如果未找到图像特征且有dcNotFun配置
        if string.find(dcNextFun, "ocr") then
            ocrWrite = v2.ocrWrite
            if not ocrWrite then
                ocrWrite = {1, "任务变量", v2[1] or 0, -1}
            end
            ocrSetParam = {ocrWrite[1], ocrWrite[2], ocrWrite[3], ocrWrite[5]}
            jy_param.setParamVar(ocrSetParam)
        end

        otherRunCode = jy_task.otherRun(dcNotFun, v2)
		-- 主条件判断逻辑
		if jy_task.exitRun(otherRunCode , v2["outTaskSure"]) ~= false then
			code = otherRunCode
		end

    else
        -- 如果未找到图像特征且无dcNotFun配置
        if string.find(dcNextFun, "ocr") then
            ocrWrite = v2.ocrWrite
            if not ocrWrite then
                ocrWrite = {1, "任务变量", v2[1] or 0, -1}
            end
            ocrSetParam = {ocrWrite[1], ocrWrite[2], ocrWrite[3], ocrWrite[4]}
            jy_param.setParamVar(ocrSetParam)
        end
    end

    return code
end
find.moreFind_dc = moreFind_dc

local rand = {}
local function ac_rand(v2)
    local rand = v2.rand
    local code
    local randType = rand[1]  -- 获取随机类型配置
	local setParam = rand[2]
    local randRet

    -- 随机数生成逻辑
    if randType[1] == "num" then
        randRet = math.random(randType[2], randType[3])  -- 数值范围随机
    elseif randType[1] == "boolean" then
        local t = {}
        for i = 2, #randType do  -- 从第二个元素开始遍历
            local readV
            if type(randType[i]) == "table" then
                readV = jy_param.getParamVar(randType[i])  -- 处理嵌套表
            else
                readV = randType[i]
            end
            if readV == true then
                table.insert(t, i - 1)  -- 收集有效索引
            end
        end
        if #t == 0 then
            randRet = -1
        else
            randRet = t[math.random(1, #t)]  -- 随机选择有效索引
        end
    end

    -- 类型转换处理
    if randType[4] == "num" then
        randRet = randRet + 0  -- 转为数值类型
    elseif randType[4] == "str" then
        randRet = tostring(randRet)  -- 转为字符串类型
    end

    -- 日志记录
    JY.jy_sys.acLog("特征 ***************** [" .. v2[1] .. "] 随机生成：" .. tostring(randRet),v2)

    -- 结果存储逻辑
    if type(setParam) == "table" then
		setParam[4] = randRet
		jy_param.setParamVar(setParam, v2)
        code = true
    else
		v2.randRet = randRet
        code = jy_task.otherRun(v2.randFun, v2)  -- 执行外部函数
    end

    return code
end
rand.ac_rand = ac_rand

local function cjRand(v2)
    local rand = v2.rand		-- 随机参数
    local opts = rand[1]		-- 资源随机列表
	local isRand = v2.random	-- 随机判断 true：随机，false：顺序（可使用table获取动态变量如： {7,"随机采集","","==",true}）
	local check = v2.check		-- 动态检测稀缺资源开关 true：开，false：关（可使用表格获取动态变量如： {7,"缺啥采啥",false}）
	if type(check) == "table" then
		check = jy_param.getParamVar(check)
	end
	local noResSuffix = v2.noResSuffix or "不足" -- 稀缺资源后缀，如有资源 钻石 为稀缺资源，就将该资源存入文件为 "钻石不足" = 1,填入后缀为 "不足"
    local optTab = {}
    local randRet = -1
	local function getResType()
		local setType = jy_ui.getUISet("指定采集" .. jy_ui.getUISet("当前序号" , 1), nil)
		if not IsEmpty(setType) then
			return setType
		end
		return nil
	end
    -- 添加设置资源或缺资源检查
    if v2.checkNot == nil or v2.check ~= true then  --无稀缺资源检测
        for i = 1, #opts do
            if jy_ui.getUISet(opts[i], false) == true then
                table.insert(optTab, opts[i])
            end
        end
		else 	-- 检测是否存在资源不足
		      local checkNot = v2.checkNot
		      for i = 1, #opts - 1 do
		          local check = JY.jy_file.fileGetUser(opts[i] .. noResSuffix, "0", G.config("当前账号"))
		          if check == "1" then
		              table.insert(optTab, opts[i])
		          end
		      end
		  end

		  -- 后备处理：无缺资源检查时添加设置资源
		  if #optTab == 0 and v2.check == true then
		      for i = 1, #opts do
		          if jy_ui.getUISet(opts[i], false) == true then
		              table.insert(optTab, opts[i])
		          end
		      end
		  elseif #optTab == 0 and v2.check ~= true then
		      randRet = -1
		  end

		  local saveNum = 0
		  if #optTab ~= 0 then
		      -- 处理随机条件
		      if type(isRand) == "table" then
		          local v3 = v2
		          v3["执行条件"] = isRand
		          isRand= evaluateCondition(v3)
		      end
		      -- 选择随机结果
		      if not isRand then
		          saveNum = tonumber(JY.jy_file.fileGetUser("cjRand", "1", G.config("当前账号"))) or 0
		          randRet = optTab[(saveNum % #optTab) + 1]  -- Lua索引从1开始
		      else

		          randRet = JY.jy_tab.arrayRand(optTab)
		      end
		  end

		  -- 指定采集覆盖
		  local zdCj = getResType()
		  if zdCj ~= nil then
		      randRet = opts[zdCj]  -- 直接使用Lua的1-based索引
		      JY.jy_sys.acLog("特征 ***************** [" .. v2[1] .. "] 指定采集 ： " .. randRet, v2)
		  end

		  -- 结果存储
		  G.setVars("任务变量." .. rand[2], randRet)

		  JY.jy_sys.acLog("特征 ***************** [" .. v2[1] .. "] 随机生成 ： " .. randRet,v2)

		  -- 更新计数器
		  JY.jy_file.filePutUser("cjRand", tostring(saveNum + 1), G.config("当前账号"))
	return true
end
rand.cjRand = cjRand

local move = {}
local function myMove(v)
	local xy1, xy2, type
	if v["move"] ~= nil then
		local move_p = v["move"]
		xy1 = {move_p[1] , move_p[2]}
		xy2 = {move_p[3] , move_p[4]}
	else
		xy1 = v["xy1"]
		xy2 = v["xy2"]
	end
	type = ""
	if v.moveType == 1 then
		type = "曲线滑动"
		JY.jy_touch.moveCtrl({xy1[1] , xy1[2] , xy2[1] , xy2[2]} , v.moveTime , v.wait , v.stdelay,v.enddelay)
	elseif v.moveType == 2 then
		type = "原版滑动"
		frameSdk.swipe(xy1[1] , xy1[2] , xy2[1] , xy2[2] , v.moveTime or 800)
		JY.jy_sys.sleep(v.wait or 500)
	else
		type = "直线滑动"
		JY.jy_touch.moveLine({xy1[1] , xy1[2] , xy2[1] , xy2[2]} , v.moveTime , v.wait , v.stdelay,v.enddelay)
	end

	JY.jy_sys.acLog("滑动操作：" .. type .. "【" .. v[1] .. "】 " .. xy1[1] .. "," .. xy1[2] .. ">>>" .. xy2[1] .. "," .. xy2[2] , v)
	return true
end
move.myMove = myMove

local function moves(v2)
	local code
	local moveCount, moveCode
	if type(v2["moveCount"]) == "table" then
		moveCount = jy_param.getParamVar(v2["moveCount"])
	else
		moveCount = v2["moveCount"]
	end

	for i = 1, moveCount, 1 do
		if not IsEmpty(v2["执行条件"]) then
			if not evaluateCondition(v2) then
				JY.jy_sys.acLog("循环滑动中不满足执行条件，退出循环", v2)
				break
			end
		end
		myMove(v2)
		if not IsEmpty(v2["滑动后方法"]) then
			local runLast = v2["滑动后方法"]
			JY.jy_sys.acLog("滑动后方法<" .. Iif(type(runLast) == "table" , runLast[1] , runLast) .. ">", v2)
			moveCode = jy_task.oFun(v2["滑动后方法"] , v2)

			if jy_task.exitRun(moveCode , v2["outTaskSure"]) ~= false then
				code = moveCode
				break
			elseif v2["moveNotOutCode"] ~= nil and v2["moveNotOutCode"] == moveCode then
				code = 8
				break
			elseif v2["moveOut"] == moveCode then
				code = true
				break
			end
		end
	end

	return code
end
move.moves = moves

local function tMove(v2)
	local v = JY.jy_tab.deepCopy(v2)
	local taps = G.taps()
	v["xy1"] = taps["特殊特征"][v2["t1"]]["tape"]
	v["xy2"] = taps["特殊特征"][v2["t2"]]["tape"]
	myMove(v)
	return true
end
move.tMove = tMove

local acUtil = {}


local function ocr_handler(v2)
	local ocrRet
	local code =false
	local ocrType = v2.ocrType or "str"
    -- OCR 类型分派处理
    if ocrType == "num" then -- 识别数字
        ocrRet = jy_frame.ocr_num(v2)
    elseif ocrType == "str" then -- 识别文字
        ocrRet = jy_frame.ocr_str(v2)
	else
		JY.jy_sys.acLog("ocrType 错误， 无可以识别类型", v2)
    end

    -- OCR 结果回写处理
    if IsEmpty(ocrRet) and  not IsEmpty(v2.defult) then
        ocrRet = v2.defult
    end


    local ocrWrite = v2.ocrWrite or {1, "任务变量", v2[1], ocrRet}
    local ocrStatus = true
    local setP = true
    -- OCR 结果比较逻辑
    if v2.ocrCompare ~= nil and type(ocrWrite) == "table" then
        local ocrCompare = v2.ocrCompare
        local yxtj = {
            [1] = v2[1],
            ["执行条件"] = {}
        }

        if type(ocrCompare[2]) == "table" then
            local ocrCompare_1 = ocrCompare[2]
            yxtj["执行条件"] = {ocrWrite[1], ocrWrite[2], ocrWrite[3], ocrCompare[1], ocrRet}
        else
            jy_param.setParamVar({ocrWrite[1], ocrWrite[2], ocrWrite[3], ocrRet}, v2)
            yxtj["执行条件"] = {
                {ocrWrite[1], ocrWrite[2], ocrWrite[3], ocrCompare[1], ocrCompare[2]}
            }
            setP = false
        end

        if not evaluateCondition(yxtj) then
            code = false
            ocrStatus = false
        end
    end
	if ocrStatus and not IsEmpty(ocrRet)  then
		-- 最终结果处理
		if type(ocrWrite) == "table" then
			if setP then
				jy_param.setParamVar({ocrWrite[1], ocrWrite[2], ocrWrite[3], ocrRet},v2)
			end
			if ocrRet ~= -1 and ocrStatus then
				code = true
			end
		else
			code = jy_task.otherRun(v2.confWrite, v2)
		end
	else
		JY.jy_sys.acLog("文字识别不满足执行条件，取消保存识别结果",v2)
	end

	return code
end
local function dc_find(v2, ff)
	local ret
	local v2_copy = JY.jy_tab.deepCopy(v2)
	if ff == "ft" then
		v2_copy.ff = "ft"
        ret = jy_frame.myFind(v2_copy)
    elseif ff == "fc" then
		v2_copy.ff = "fc"
        ret = jy_frame.MFindColor (v2_copy)
    elseif ff == "pic" then
		v2_copy.ff = "pic"
        ret = jy_frame.mFindPic(v2_copy)
    elseif ff == "fs" then
		v2_copy.ff = "fs"
        ret = jy_frame.MFindStr (v2_copy)
    end
	JY.jy_sys.acLog("dc 第一个特征找到坐标：" .. ret.x .. "," .. ret.y, v2)
	return ret
end

local function compute(param1, computeType, param2)
    local result = 0

    if computeType == "-" then
        result = param1 - param2
    elseif computeType == "+" then
        result = param1 + param2
    elseif computeType == "*" then
        result = param1 * param2
    elseif computeType == "/" then
        result = param1 / param2
    elseif computeType == "|" then
        -- 整数除法 (对应 VBScript 的 \ 运算符)
        result = math.floor(param1 / param2)
    elseif computeType == "mod" then
        result = param1 % param2  -- 取模运算
    elseif computeType == "&" then
        -- 字符串连接 (需显式转换为字符串)
        result = tostring(param1) .. tostring(param2)
    end
    return result
end

local function ac_comput(v2)
	-- 声明局部变量（Lua无需显式声明类型）
	local param1, param2

	-- 根据computeType决定参数处理方式
	if v2.computeType == "&" then
		param1 = jy_param.getParamVar(v2.param1)     -- 字符串模式直接获取参数
		param2 = jy_param.getParamVar(v2.param2)
	else
		param1 = tonumber(jy_param.getParamVar(v2.param1))  -- 数值模式转换参数
		param2 = tonumber(jy_param.getParamVar(v2.param2))
	end

	-- 获取结果集并执行计算
	local result = v2.resultSet
	if IsEmpty(param1) then
		JY.jy_sys.acLog("计算参数1为空，取消计算", v2)
		return true
	end
	if IsEmpty(param2) then
		JY.jy_sys.acLog("计算参数2为空，取消计算", v2)
		return true
	end
	result[4] = compute(param1, v2.computeType, param2)  -- 注意：Lua数组索引从1开始
	-- 调试信息输出（适配Lua字符串连接语法）
	JY.jy_sys.acLog("计算结果：" .. param1 .." " .. v2.computeType .." " .. param2 .. " = " .. result[4], v2)

	-- 更新参数存储并返回成功标识
	jy_param.setParamVar(result, v2)
	return true  -- Lua中无需声明直接赋值

end

local pic = {}
local function ac_pic_p(v)
    local filePath = v["filePath"]
    local fileName = v["fileName"]
    if not IsEmpty(filePath) and not frameSdk.fileExist(filePath) then
		frameSdk.lfs.mkdir(filePath)
	end
	local x1, y1, x2, y2
	if not IsEmpty( v["r"]) then
		x1, y1, x2, y2 = v["r"][1], v["r"][2], v["r"][3], v["r"][4]
	elseif not IsEmpty(v["xy1"]) and not IsEmpty(v["xy2"]) then
		x1, y1 = v["xy1"][1], v["xy1"][2]
		x2, y2 = v["xy2"][1], v["xy2"][2]
	else
		JY.jy_sys.acLog("错误：未找到截图坐标配置", v)
		return false
	end
    frameSdk.snapShot(fileName,x1, y1, x2, y2)
end


local function mInput(t, clean, type,model)

    if clean == true then
        for i = 1, 20, 1 do
        	frameSdk.imeLib.deleteChar()
			JY.sleep(2)
        end
    end
    if  not IsEmpty(type) and type ~= 1 then
        	frameSdk.imeLib.setText(t)
			frameSdk.imeLib.finishInput()
    else
		frameSdk.inputText(t)
    end
end
pic.ac_pic_p = ac_pic_p

--[[
================================================================================
节点指令模块（nd_*）—— 框架基础能力，与 ps/ft/pic/fs 同构
    基于懒人精灵无障碍节点 API：
    平级查询：链式 selector API（id()/text()... 全局函数 + sel:findOnce(0)）
    层级查询：表式 API（nodeLib.findOne/findInNode/findByIndex/findParentNode/keepNode）
    文档依据：doc-lrword/api/android/node.md、doc-lrword/api/android/accessibility.md
    指令注册：ActionFramework.lua ACTIONS 表（nd_c/nd_w/nd_s/nd_a/nd_t/nd_in/nd_scroll）
    设计与验证清单见：《节点指令集成开发计划.md》（项目根目录）
================================================================================
]]
local nd = {}

-- 链式匹配函数白名单（node.md 第1~41节）键名 → 值类型（s=字符串 n=数字 b=布尔 t=表）
local ND_KEYS = {
    id = "s", idContains = "s", idStartsWith = "s", idEndsWith = "s", idMatches = "s",
    text = "s", textContains = "s", textStartsWith = "s", textEndsWith = "s", textMatches = "s",
    desc = "s", descContains = "s", descStartsWith = "s", descEndsWith = "s", descMatches = "s",
    className = "s", classNameContains = "s", classNameStartsWith = "s", classNameEndsWith = "s", classNameMatches = "s",
    packageName = "s", packageNameContains = "s", packageNameStartsWith = "s", packageNameEndsWith = "s", packageNameMatches = "s",
    drawingOrder = "n", depth = "n", index = "n",
    visibleToUser = "b", selected = "b", clickable = "b", longClickable = "b", enabled = "b",
    password = "b", scrollable = "b", checked = "b", checkable = "b", focusable = "b", focused = "b",
    bounds = "t", boundsInside = "t",
}

-- 表式键名白名单（节点工具勾选生成格式，层级路线用，模糊匹配传正则）
local ND_TAB_KEYS = {
    class = true, id = true, package = true, text = true, desc = true,
    checkable = true, checked = true, clickable = true, scrollable = true,
    longClickable = true, enabled = true, selected = true, focusable = true,
}

nd.ND_KEYS = ND_KEYS
nd.ND_TAB_KEYS = ND_TAB_KEYS

-- 值解析：数字/布尔/字符串直传；表走框架变量解析（如 {1,"任务变量","xx"}、{2,"当前账号"}）
function nd.resolve(val)
    if val == nil then return nil end
    if type(val) == "table" then
        local ok, r = pcall(jy_param.getParamVar, val)
        if ok then return r end
        return nil
    end
    return val
end

-- 无障碍服务检测（失败时按 conf["自动开启无障碍"] 尝试拉起）
function nd.accOk(v2)
    local ok, r = pcall(function() return frameSdk.nodeLib.isAccServiceOk() end)
    if ok and r == true then return true end
    if G.config("自动开启无障碍") ~= false then
        pcall(frameSdk.nodeLib.openAccessibility)
        JY.jy_sys.sleep(1500)
        local ok2, r2 = pcall(function() return frameSdk.nodeLib.isAccServiceOk() end)
        if ok2 and r2 == true then
            JY.jy_sys.acLog("无障碍服务已自动开启", v2)
            return true
        end
    end
    pcall(G.setVars, "状态变量.无障碍状态", 0)
    JY.jy_sys.acLog("无障碍服务未开启，nd_* 节点指令不可用", v2)
    return false
end

-- 构建链式 selector（平级查询）
function nd.buildSel(ndTab, v2)
    if type(ndTab) ~= "table" then
        JY.jy_sys.acLog("缺少 nd 条件表", v2)
        return nil
    end
    local sel
    local ok, err = pcall(function()
        for k, val in pairs(ndTab) do
            local vt = ND_KEYS[k]
            if vt == nil then
                error("nd不支持的条件键[" .. tostring(k) .. "]，层级查询请改用 ndRoot/ndRootUp（表式键名 class/text...）", 0)
            end
            local rv
            if vt == "t" then
                rv = val                  -- bounds/boundsInside 等表型值不做变量解析
            else
                rv = nd.resolve(val)
            end
            if vt == "s" then
                rv = tostring(rv)
            elseif vt == "n" then
                rv = tonumber(rv) or 0
            elseif vt == "b" then
                rv = (rv == true)
            end
            if sel == nil then
                sel = frameSdk.sel[k](rv)          -- 首个条件：经 frameSdk.sel 白名单键工厂创建 selector（V1 验证，原 _G[k] 全局工厂）
            else
                sel = sel[k](sel, rv)    -- 后续条件：selector 链式追加
            end
        end
    end)
    if not ok or sel == nil then
        JY.jy_sys.acLog("构建selector失败: " .. tostring(err), v2)
        return nil
    end
    return sel
end

-- 构建表式条件表（层级查询）
function nd.tabSel(ndTab, v2)
    if type(ndTab) ~= "table" then
        JY.jy_sys.acLog("缺少层级条件表", v2)
        return nil
    end
    local out = {}
    for k, val in pairs(ndTab) do
        if not ND_TAB_KEYS[k] then
            JY.jy_sys.acLog("层级nd不支持的条件键[" .. tostring(k) .. "]，请用表式键名 class/id/text/desc...", v2)
            return nil
        end
        out[k] = nd.resolve(val)
    end
    return out
end

function nd.fuzzy(v2)
    return v2["fuzzy"] ~= false   -- 默认模糊匹配
end

-- 兼容读取节点 bounds（V3：方法返回 table / 方法返回4个多返回值 / table 字段 三种形式）
function nd.boundsOf(node)
    if node == nil then return nil end
    local ok, r1, r2, r3, r4 = pcall(node.bounds, node)
    if ok then
        if type(r1) == "table" and #r1 >= 4 then return r1 end
        if type(r1) == "number" and type(r4) == "number" then return { r1, r2, r3, r4 } end
    end
    if type(node) == "table" and type(node["bounds"]) == "table" and #node.bounds >= 4 then
        return node.bounds
    end
    return nil
end

-- bounds → 中心点坐标（真实屏幕坐标，无需锚点换算）
function nd.center(node)
    local b = nd.boundsOf(node)
    if b == nil then return nil end
    return { x = math.floor((b[1] + b[3]) / 2), y = math.floor((b[2] + b[4]) / 2) }
end

-- 兼容读取节点文本
function nd.textOf(node)
    if node == nil then return nil end
    local ok, r = pcall(node.text, node)
    if ok and r ~= nil and type(r) ~= "table" then return tostring(r) end
    if type(node) == "table" then
        local tv = node["text"]
        if type(tv) == "function" then
            local ok2, r2 = pcall(tv, node)
            if ok2 and r2 ~= nil then return tostring(r2) end
        elseif tv ~= nil then
            return tostring(tv)
        end
    end
    return nil
end

function nd.describe(node)
    local t = nd.textOf(node)
    if t ~= nil and t ~= "" then return t end
    return "?"
end

-- 原生点击（先链式 node:click()，失败再表式 nodeLib.click(node)）
function nd.nativeClick(node, v2)
    local ok, r = pcall(node.click, node)
    if ok and r ~= false and r ~= nil then return true end
    local ok2, r2 = pcall(frameSdk.nodeLib.click, node)
    if ok2 and r2 == true then return true end
    JY.jy_sys.acLog("节点原生点击失败", v2)
    return false
end

-- 点击节点：默认坐标模式走框架 comClick 管线；ndClick="native" 走无障碍原生点击
function nd.clickNode(v2, node)
    local count = tonumber(v2["count"]) or 1
    if v2["ndClick"] == "native" then
        for i = 1, count do
            if not nd.nativeClick(node, v2) then return false end
            if i < count then JY.jy_sys.sleep(v2["countSleep"] or 100) end
        end
        JY.jy_sys.acLog("节点原生点击完成 x" .. count, v2)
        return true
    end
    local center = nd.center(node)
    if center == nil then
        JY.jy_sys.acLog("节点无可点击坐标，降级原生点击", v2)
        return nd.nativeClick(node, v2)
    end
    jy_frame.comClick(center, v2)   -- count/countSleep/lwait/clickRange/tape 全继承
    return true
end

-- 是否层级查询
function nd.isHier(v2)
    return not IsEmpty(v2["ndPath"]) or not IsEmpty(v2["ndRoot"]) or not IsEmpty(v2["ndRootUp"])
end

-- 层级查询（表式 API 路线），wantAll=true 返回节点数组
function nd.findHier(v2, wantAll)
    local fuzzy = nd.fuzzy(v2)
    -- ① 位置路径直达
    if not IsEmpty(v2["ndPath"]) then
        local ok, r = pcall(frameSdk.nodeLib.findByIndex, tostring(v2["ndPath"]))
        if not ok then
            JY.jy_sys.acLog("findByIndex失败: " .. tostring(r), v2)
            return nil
        end
        if r == nil then JY.jy_sys.acLog("节点未命中(ndPath=" .. tostring(v2["ndPath"]) .. ")", v2) end
        return r
    end
    -- ② 锚点模式：nd 匹配锚点 → 向上 ndRootUp 层父级 → 容器内查 ndSub
    if not IsEmpty(v2["ndRootUp"]) then
        local anchorSel = nd.tabSel(v2["nd"], v2)
        if anchorSel == nil then return nil end
        local okA, anchor = pcall(frameSdk.nodeLib.findOne, anchorSel, fuzzy)
        if not okA or anchor == nil then
            JY.jy_sys.acLog("节点未命中(锚点)", v2)
            return nil
        end
        local up = tonumber(nd.resolve(v2["ndRootUp"])) or 1
        local container = anchor
        for _ = 1, up do
            local okP, parent = pcall(function() return container:parent() end)
            if (not okP or parent == nil) and type(container) == "table" then
                local pf = container["parent"] or container["parentNode"]
                if type(pf) == "function" then
                    local ok2, p2 = pcall(pf, container)
                    parent = (ok2 and p2) or nil
                elseif pf ~= nil then
                    parent = pf
                end
            end
            if parent == nil then
                JY.jy_sys.acLog("节点未命中(锚点父级层数不足)", v2)
                return nil
            end
            container = parent
        end
        local subSel = nd.tabSel(v2["ndSub"], v2)
        if subSel == nil then
            JY.jy_sys.acLog("锚点模式缺少 ndSub 行内目标条件", v2)
            return nil
        end
        local okF, r = pcall(frameSdk.nodeLib.findInNode, container, subSel, wantAll == true, fuzzy)
        if not okF then
            JY.jy_sys.acLog("findInNode失败: " .. tostring(r), v2)
            return nil
        end
        if r == nil then JY.jy_sys.acLog("节点未命中(行内目标)", v2) end
        return r
    end
    -- ③ 容器模式：ndRoot 定位容器（ndRootIndex 取第N个）→ 容器内查 nd
    if not IsEmpty(v2["ndRoot"]) then
        local rootSel = nd.tabSel(v2["ndRoot"], v2)
        if rootSel == nil then return nil end
        local container
        if not IsEmpty(v2["ndRootIndex"]) then
            local okR, roots = pcall(frameSdk.nodeLib.findAll, rootSel, fuzzy)
            if not okR or type(roots) ~= "table" then
                JY.jy_sys.acLog("节点未命中(容器 findAll)", v2)
                return nil
            end
            container = roots[tonumber(v2["ndRootIndex"]) or 1]
        else
            local okR, r = pcall(frameSdk.nodeLib.findOne, rootSel, fuzzy)
            container = (okR and r) or nil
        end
        if container == nil then
            JY.jy_sys.acLog("节点未命中(容器 ndRoot)", v2)
            return nil
        end
        local subSel = nd.tabSel(v2["nd"], v2)
        if subSel == nil then return nil end
        local okF, r = pcall(frameSdk.nodeLib.findInNode, container, subSel, wantAll == true, fuzzy)
        if not okF then
            JY.jy_sys.acLog("findInNode失败: " .. tostring(r), v2)
            return nil
        end
        if r == nil then JY.jy_sys.acLog("节点未命中(容器内目标)", v2) end
        return r
    end
    return nil
end

-- 统一查询入口（nd_c/nd_w/nd_s/nd_t/nd_in/nd_scroll 共用）
-- wantAll=true 返回节点数组（供 nd_a 全部点击模式）
function nd.findNode(v2, wantAll)
    if not nd.accOk(v2) then return nil end
    local result
    if nd.isHier(v2) then
        if v2["ndKeep"] == true then pcall(frameSdk.nodeLib.keepNode) end
        local ok, r = pcall(nd.findHier, v2, wantAll)
        if v2["ndKeep"] == true then pcall(frameSdk.nodeLib.releaseNode) end
        if not ok then
            JY.jy_sys.acLog("层级节点查询异常: " .. tostring(r), v2)
            return nil
        end
        result = r
    else
        local sel = nd.buildSel(v2["nd"], v2)
        if sel == nil then return nil end
        if wantAll == true then
            local okA, all = pcall(function() return sel:findAll(0) end)
            if not okA or type(all) ~= "table" then return nil end
            result = all
        else
            local okF, node = pcall(function() return sel:findOnce(0) end)
            if not okF or node == nil then
                -- V2 回退：findAll 取第一个
                local okA, all = pcall(function() return sel:findAll(0) end)
                result = (okA and type(all) == "table" and #all > 0) and all[1] or nil
            else
                result = node
            end
        end
    end
    if result == nil then
        JY.jy_sys.acLog("节点未命中", v2)
    end
    return result
end

-- ==================== nd_* 指令策略 ====================

-- nd_c：查找节点→点击（isClick=false 纯检测）
function nd.findClick(v2, wait)
    local node = nd.findNode(v2)
    if node == nil then
        return false
    end
    JY.jy_sys.acLog("节点命中: " .. nd.describe(node), v2)
    if v2["isClick"] == false then
        return true
    end
    return nd.clickNode(v2, node)
end

-- nd_w：超时循环查找点击（waitSleep 间隔 / waitOut 总限，默认 conf.timeout）
function nd.findClick_w(v2, wait)
    local sleepTime = tonumber(v2["waitSleep"]) or 500
    local waitOut = tonumber(v2["waitOut"]) or G.config("timeout") or 2000
    local t = frameSdk.systemTime()
    while true do
        if frameSdk.systemTime() - t > waitOut then break end
        if nd.findClick(v2, wait) then return true end
        JY.jy_sys.sleep(sleepTime)
    end
    JY.jy_sys.acLog("等待节点超时(" .. waitOut .. "ms)", v2)
    return false
end

-- nd_s：点击直到节点消失（bigCount 上限，对齐图色 _s 语义）
function nd.findClick_s(v2, wait)
    local bigCount = tonumber(v2["bigCount"]) or 50
    local hits = 0
    for _ = 1, bigCount do
        local node = nd.findNode(v2)
        if node == nil then break end
        if v2["isClick"] ~= false then
            if not nd.clickNode(v2, node) then break end
        end
        hits = hits + 1
        JY.jy_sys.sleep(tonumber(v2["waitSleep"]) or G.config("comSleep") or 500)
    end
    if hits > 0 then
        JY.jy_sys.sleep(wait or 0)
        return true
    end
    return false
end

-- nd_a：多节点（ndAll=true 全部点击；ndIndex 第N个，1-based）
function nd.findClick_a(v2, wait)
    if v2["ndAll"] == true then
        local nodes = nd.findNode(v2, true)
        if type(nodes) ~= "table" or #nodes == 0 then
            JY.jy_sys.acLog("节点未命中(全部模式)", v2)
            return false
        end
        local n = 0
        for _, node in ipairs(nodes) do
            if v2["isClick"] == false then
                n = n + 1
            elseif nd.clickNode(v2, node) then
                n = n + 1
            end
            JY.jy_sys.sleep(tonumber(v2["countSleep"]) or 100)
        end
        return n > 0
    end
    local idx = tonumber(v2["ndIndex"]) or 1
    if idx < 1 then idx = 1 end
    local node
    if nd.isHier(v2) then
        local list = nd.findNode(v2, true)
        node = (type(list) == "table") and list[idx] or nil
    else
        local sel = nd.buildSel(v2["nd"], v2)
        if sel == nil then return false end
        local ok, r = pcall(function() return sel:findOnce(idx - 1) end)
        if ok then node = r end
    end
    if node == nil then
        JY.jy_sys.acLog("节点未命中(第" .. idx .. "个)", v2)
        return false
    end
    if v2["isClick"] == false then return true end
    return nd.clickNode(v2, node)
end

-- nd_t：读取节点文本 → G.setVars(ndVar, 文本)
function nd.getText(v2, wait)
    if IsEmpty(v2["ndVar"]) then
        JY.jy_sys.acLog("nd_t 缺少 ndVar 参数(变量路径，如 任务变量.当前等级)", v2)
        return false
    end
    local node = nd.findNode(v2)
    if node == nil then return false end
    local txt = nd.textOf(node)
    if txt == nil then
        JY.jy_sys.acLog("节点文本读取失败", v2)
        return false
    end
    G.setVars(tostring(v2["ndVar"]), txt)
    JY.jy_sys.acLog("节点文本[" .. txt .. "] → " .. tostring(v2["ndVar"]), v2)
    return true
end

-- nd_in：节点输入框写入文本（node:setText）
function nd.setText(v2, wait)
    local node = nd.findNode(v2)
    if node == nil then return false end
    local val = nd.resolve(v2["ndText"])
    if val == nil then
        JY.jy_sys.acLog("nd_in 缺少 ndText 参数", v2)
        return false
    end
    local text = tostring(val)
    local ok, r = pcall(node.setText, node, text)
    if not ok or r == false then
        local ok2, r2 = pcall(frameSdk.nodeLib.setText, node, text)
        if not ok2 or r2 == false then
            JY.jy_sys.acLog("节点输入失败", v2)
            return false
        end
    end
    JY.jy_sys.acLog("节点输入[" .. text .. "]", v2)
    return true
end

-- nd_scroll：节点滚动（ndScroll="up/down/left/right"）
function nd.scroll(v2, wait)
    local dirMap = { up = "scrollUp", down = "scrollDown", left = "scrollLeft", right = "scrollRight" }
    local m = dirMap[tostring(v2["ndScroll"] or "down")]
    if m == nil then
        JY.jy_sys.acLog("ndScroll 参数无效: " .. tostring(v2["ndScroll"]), v2)
        return false
    end
    local node = nd.findNode(v2)
    if node == nil then return false end
    local ok, r = pcall(node[m], node)
    if not ok or r == false then
        JY.jy_sys.acLog("节点滚动失败(" .. m .. ")", v2)
        return false
    end
    JY.jy_sys.acLog("节点滚动: " .. m, v2)
    return true
end

local acUtils = {
	move = move,
	nd = nd,
	yolo = yolo,
	find = find,
	rand = rand,
	acUtil = acUtil,
	pic = pic,
	ac_comput = ac_comput,
	mInput = mInput,
	ocr_handler = ocr_handler
}
return acUtils
