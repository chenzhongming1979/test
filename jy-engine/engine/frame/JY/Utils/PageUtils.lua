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

local jy_frame = require("JY.Utils.FrameUtils")
local JY = require("JY.Utils.JY")

-- 预编译正则模式字符串，避免重复编译
local COLOR_SPLIT_PATTERN = "(.-)" .. ","      -- 颜色字符串分割
local POINT_SPLIT_PATTERN = "(.-)" .. "|"      -- 坐标点分割
local PATH_SPLIT_PATTERN = "[^%.]+"            -- 路径分割

local direction = 1
local game = nil
local Change={
	dev_dev = {1280/720},
	dev_vga = 1280/720,--开发比例
	game_dev = {},
	game_vga =  0,--游戏比例
	transition = 0,
	x=0,
	y=0,
	k = 0, --拉升系数
	m_fun = 0, --1同步拉升，2按宽拉升，3按高拉升
	m=0, --偏移值
}

local Base = {
	name="", --点位名称
	a =0,--锚点
	fun=0,--方法
}

Base.PointFun=1
Base.FindColorFun=2
Base.Zone=2

local myPoint={
	x=0;
	y=0;
}
local comm ={
	w = 0,
	h = 0,
}
function myPoint:new(name,a,myPointxy)
	local o=Base:new()
	setmetatable(o,self)
	if type(myPointxy) == "table" then
		o.x=myPointxy[1] or myPointxy.x or 0
		o.y=myPointxy[2] or myPointxy.y or 0
	else
		o.x=myPointxy.x or 0
		o.y=myPointxy.y or 0
	end
	o.a=a
	o.name=name
	o.fun=Base.PointFun
	return o
end


local FindColor ={
	r={0,0,0,0},
	colors="",
}

function FindColor:new(name,a,rect,colors)
	local o = Base:new()
	setmetatable(o,self)
	o.a=a
	o.name=name
	o.fun = Base.FindColorFun
	o.r=rect or {0,0,comm.w,comm.h}
	o.colors = colors or ""
	return o
end

function Base:new()
	local o ={}
	setmetatable(o,self)
	self.__index = self
	return o
end


function Change:new(o)
	local o = o or {}
	self = Change
	setmetatable(o,self)
	self.__index=self
	if direction==2 then --home在左边
		if self.dev_vga < self.game_vga then--按宽拉升
			self.k = self.game_dev[1] / self.dev_dev[1]
			self.m = self.game_dev[2] - self.dev_dev[2]*self.k
			self.m_fun=2
		elseif self.dev_vga > self.game_vga then  --按高拉升
			self.k = self.game_dev[2] / self.dev_dev[2]
			self.m = self.game_dev[1] - self.dev_dev[1]*self.k
			self.m_fun=3
		else--同比拉升
			self.k = self.game_dev[1] / self.dev_dev[1]
			self.m = self.game_dev[2] - self.dev_dev[2]*self.k
			self.m_fun=1
		end
		return o
	end
	if direction==1 then --home在右边
		if self.dev_vga < self.game_vga then--按宽拉升
			self.k = self.game_dev[1] / self.dev_dev[1]
			self.m = self.game_dev[2] - self.dev_dev[2]*self.k
			self.m_fun=2
		elseif self.dev_vga > self.game_vga then  --按高拉升
			self.k = self.game_dev[2] / self.dev_dev[2]
			self.m = self.game_dev[1] - self.dev_dev[1]*self.k
			self.m_fun=3
		else--同比拉升
			self.k = self.game_dev[1] / self.dev_dev[1]
			self.m = self.game_dev[2] - self.dev_dev[2]*self.k
			self.m_fun=1
		end
		return o
	end

	if direction==0 then --home在下边
		if self.dev_vga > self.game_vga then--按宽拉升
			self.k = self.game_dev[1]/self.dev_dev[1]
			self.m = self.game_dev[2] - self.dev_dev[2]*self.k
			self.m_fun=2
		elseif self.dev_vga < self.game_vga then  --按高拉升
			-- 修正：按高拉升时应使用高度的比值作为缩放系数，而不是宽度
			self.k = self.game_dev[2]/self.dev_dev[2]
			self.m = self.game_dev[1] - self.dev_dev[1]*self.k
			self.m_fun=3
		else--同比拉升
			self.k = self.game_dev[1]/self.dev_dev[1]
			self.m = self.game_dev[2] - self.dev_dev[2]*self.k
			self.m_fun=1
		end
		return o
	end
end


local function transition(tape ,tapeType,tape2)
	local newTape = JY.jy_tab.deepCopy(tape)
	local var
	if tapeType == nil then
		if Change.transition == 1 then
			var = tape.x
			newTape.x = Change.dev_dev[2] - 1 - tape.y
			newTape.y = var
		elseif Change.transition == 2 then
			var = tape.y
			newTape.x = var
			newTape.y = Change.dev_dev[1] - 1 - tape.x
		end
	elseif  tapeType == 1 and Change.transition ~= 0 then
			var = tape.x
			newTape.x = tape.y
			newTape.y = var
	elseif  tapeType == 2 and Change.transition ~= 0 then
		if Change.transition == 1 then
			var = tape.x
			newTape.x = 0-tape.y
			newTape.y = var
		elseif Change.transition == 2 then
			var = tape.x
			newTape.x = tape.y
			if var > 0 then
				newTape.y = var - var * 2
			else
				newTape.y = 0 -var
			end
		end
	elseif  tapeType == 4 and Change.transition ~= 0 then	--ret起始坐标转换
		if Change.transition == 1 then
			-- x = screenH - (y + h)
			-- y = x
			var = tape.x
			newTape.x = Change.dev_dev[2]-(tape.y + tape2.y)
			newTape.y = var
		elseif Change.transition == 2 then
			var = tape.x
			newTape.x = tape.y
			newTape.y = var + tape2.y
		end
	end
	return newTape
end
local function colors_fmt(colors,a,name)
	local result = {}
	local return_colors=""
	--local count = 0
	local p = Point(0,0)
	for match in (colors..","):gmatch(COLOR_SPLIT_PATTERN) do
		for match2 in (match.."|"):gmatch(POINT_SPLIT_PATTERN) do
			table.insert(result, match2)
		end
		local x=tonumber(result[1])
		local y=tonumber(result[2])
		p = Point(x,y)
		if Change.transition ~= 0 then
			p = transition(p,2)
		end
		p=Change:changepoint(myPoint:new(name,a,p),3)
		return_colors=return_colors..","..p.x.."|"..p.y.."|"..result[3]

		for i=#result,1,-1 do
			table.remove(result,i)
		end
	end
	return string.sub(return_colors,2)
end
--	-1,{681, 394, 708, 426},"0|0|0x784d2f,-8|0|0x7f5537,10|0|0x875f42,0|16|0x946f53,-3|12|0xf2e4ce,3|12|0xf2e4ce"
function Change:changezone(value)
	local rect1 = Change:changepoint(myPoint:new(value.name,value.a,Point(value.r[1],value.r[2])))
	local rect2 = Change:changepoint(myPoint:new(value.name,value.a,Point(value.r[3],value.r[4])))
	local colors = colors_fmt(value.colors,value.a,value.name)

	return {rect1.x,rect1.y,rect2.x,rect2.y},colors
end

function Change:change(value,tapeType,tape2)
	if value.fun == Base.PointFun then
		return Change:changepoint(value,tapeType,tape2)
		--sysLogFmt("%s------原坐标:%s,%s,锚点：%s 转换为：%s,%s",value.name,value.x,value.y,value.a,x,y)
	elseif value.fun == Base.FindColorFun then
		local rect,colors = Change:changezone(value)
		--		if type(value.colors) =="table" then
		--			sysLogFmt("%s------原区域：{%s,%s,%s,%s},锚点：%s, 转换------区域:{%s,%s,%s,%s}",
		--				value.name,value.rect[1],value.rect[2],value.rect[3],value.rect[4],value.a,x,y,x1,y1)
		--		else
		--			sysLogFmt("%s------原区域：{%s,%s,%s,%s},锚点：%s,色块：%s 转换------区域:{%s,%s,%s,%s},色块:%s",
		--				value.name,value.rect[1],value.rect[2],value.rect[3],value.rect[4],value.a,value.colors,x,y,x1,y1,colors)
		--		end
		--sysLog("-------------------------")
		return rect,colors
	end
end

function Change:changepoint(value,tapeType,tape2)
	value = transition(value,tapeType,tape2)
	if direction==0 then --home在下边
		if self.m_fun==2 then--按宽拉升
			if value.a ==1 or value.a ==4 or value.a ==7 then
				self.x=value.x*self.k
				self.y=value.y*self.k
			elseif value.a == 2 or value.a ==5 or value.a ==8 then
				self.x=value.x*self.k+self.m/2
				self.y=value.y*self.k
			elseif value.a == 3 or value.a ==6 or value.a ==9 then
				self.x=value.x*self.k+self.m
				self.y=value.y*self.k
			elseif value.a ==-1 then
				self.x=value.x*self.k+self.m/2
				self.y=value.y*self.k
			end
		end

		if self.m_fun==3 then--按高拉升
			if value.a ==1 or value.a ==2 or value.a ==3 then--ok
				self.x=value.x*self.k
				self.y=value.y*self.k  -- 优化：移除历史遗留的-1偏移，使用四舍五入替代硬编码修正
			elseif value.a == 4 or value.a ==5 or value.a ==6 then
				self.x=value.x*self.k
				self.y=value.y*self.k+self.m/2
			elseif value.a == 7 or value.a ==8 or value.a ==9 then--ok
				self.x=value.x*self.k
				self.y=value.y*self.k+self.m
			elseif value.a ==-1 then
				self.x=value.x*self.k
				self.y=value.y*self.k+self.m/2  -- 优化：移除历史遗留的-1偏移，使用四舍五入替代硬编码修正
			end
		end
	end

	if direction==1 then --home在右边
		if self.m_fun==2 then
			if value.a ==1 or value.a ==4 or value.a ==7 then
				self.x=value.x*self.k
				self.y=value.y*self.k
			elseif value.a == 2 or value.a ==5 or value.a ==8 then
				self.x=value.x*self.k+self.m/2
				self.y=value.y*self.k
			elseif value.a == 3 or value.a ==6 or value.a ==9 then
				self.x=value.x*self.k+self.m
				self.y=value.y*self.k

			elseif value.a ==-1 then
				self.x=value.x*self.k+self.m/2
				self.y=value.y*self.k
			end
		end

		if self.m_fun==3 then
			if value.a ==1 or value.a ==2 or value.a ==3 then
				self.x=value.x*self.k
				self.y=value.y*self.k
			elseif value.a == 4 or value.a ==5 or value.a ==6 then
				self.x=value.x*self.k
				self.y=value.y*self.k+self.m/2
			elseif value.a == 7 or value.a ==8 or value.a ==9 then
				self.x=value.x*self.k
				self.y=value.y*self.k+self.m
			elseif value.a ==-1 then
				self.x=value.x*self.k
				self.y=value.y*self.k+self.m/2
			end
		end
	end

	if direction==2 then --home在左边
		if self.m_fun==2 then
			if value.a ==1 or value.a ==4 or value.a ==7 then
				self.x=value.x*self.k
				self.y=value.y*self.k
			elseif value.a == 2 or value.a ==5 or value.a ==8 then
				self.x=value.x*self.k-self.m/2
				self.y=value.y*self.k
			elseif value.a == 3 or value.a ==6 or value.a ==9 then
				self.x=value.x*self.k-self.m
				self.y=value.y*self.k
			elseif value.a ==-1 then
				self.x=value.x*self.k-self.m/2
				self.y=value.y*self.k
			end
		end

		if self.m_fun==3 then
			-- 补充：按高拉升时，水平方向有额外空间，应根据锚点的x位置进行分配
			-- 这样才能与其他方向的行为保持一致
			if value.a ==1 or value.a ==4 or value.a ==7 then
				self.x=value.x*self.k  -- 左对齐
				self.y=value.y*self.k
			elseif value.a == 2 or value.a ==5 or value.a ==8 then
				self.x=value.x*self.k+self.m/2  -- 水平居中
				self.y=value.y*self.k
			elseif value.a == 3 or value.a ==6 or value.a ==9 then
				self.x=value.x*self.k+self.m  -- 右对齐
				self.y=value.y*self.k
			elseif value.a ==-1 then
				self.x=value.x*self.k+self.m/2  -- 默认居中
				self.y=value.y*self.k
			end
		end
	end

	if self.m_fun==1 then
		self.x=value.x*self.k
		self.y=value.y*self.k
	end

	-- 优化：使用四舍五入替代向下取整，减少平均0.5px的偏差
	local retPoint = Point(math.floor(self.x + 0.5), math.floor(self.y + 0.5))

	return retPoint
end


--可替换所有键值
local cmdMap = {
    ["blfz"] = "变量赋值",
    ["fz"] = "变量赋值",
    ["tj"] = "执行条件",
    ["zxtj"] = "执行条件",
    ["hfun"] = "运行后方法",
    ["qfun"] = "运行前方法",
    ["nfun"] = "notFun",
    ["zxyc"] = "执行一次",
    ["ntjfun"] = "条件不满足方法",
    ["range"] = "clickRange",  	--点击范围
    ["zx间隔"] = "timeLag", 	--执行间隔
    ["aTape"] = "afterTape", 	--执行后点击指定坐标
    ["nTape"] = "notTape", 		--未找到特征点击指定坐标
    ["bTape"] = "beforTape", 	--执行特征之后点击
    ["未找到执行"] = "notFun",  --未找到特征执行指定方法
    ["点击"] = "isClick",		--是否点击
    ["tap"] = "isClick",

    ["bCount"] = "bigCount",		--最大点击次数 用于 _s 命令
    ["wOut"] = "waitOut",			--延时等待时间

    ["mClick"] = "每次点击后方法",			--延时等待时间


    ["p1"] = "param1",				--参数1 用于 compute 命令
    ["p2"] = "param2",				--参数2 用于 compute 命令
    ["ctype"] = "computeType",

    ["退出"] = "out", 					--找到特征退出
    ["未找到退出"] = "notOut", 			--未找到特征退出
    ["outTask"] = "outTask", 			--退出整个任务链
    ["阻止退出流程"] = "outTaskSure",
    ["执行特此次数"] = "outCount",
    ["等待时间"] = "waitOut",
    ["退出码"] = "outCode",
    ["未找到退出码"] = "notOutCode",
    ["jy_param.taskRecord"] = "success",
}
local jy_page = {}


local function toPoint(page)
	return Point(page[1], page[2], page[3], page["idx"])
end

local function colorChange(color,pageAnchor)
	if not string.find(color,"|") then
		return color
	end
	local allps = frameSdk.splitStr(color,",")
	local newP = ""
	local endStr = ","
	for k,v in pairs(allps) do
		if k == #allps then
			endStr = ""
		end
		local allp = frameSdk.splitStr(v,"|")
		allp["a"] = pageAnchor
		local ps = toPoint(allp)
		newP = newP .. ps.x .. "|" ..ps.y .. "|" ..allp[3]..endStr
	end
	return newP

end

local function pChange(page)
	local pageName = page[1]
	local pageAnchor = page[2]


	--命令转换
	for key, value in pairs(cmdMap) do
		if page[key] ~= nil then
			page[value] = page[key]
			page[key] = nil
		end
	end



	if page["tape"] ~= nil and page["change"]~=1 then
		page["tape"] = toPoint(page["tape"])
	end


	if page["rgb"] ~= nil and page["change"]~=1 and type(page["rgb"]) == "table" then
		page["rgb"] = toPoint(page["rgb"])
	end
	if page["afterTape"] ~= nil and page["change"]~=1 then
		page["afterTape"] = toPoint(page["afterTape"])
	end

	if page["beforeClick"] ~= nil and page["change"]~=1 then
		page["beforeClick"] = game:change(myPoint:new(pageName,pageAnchor,page["beforeClick"]))
	end

	if page["beforeTape"] ~= nil and page["change"]~=1 then
		page["beforeTape"] = toPoint(page["beforeTape"])
	end
	if page["offset"] ~= nil and page["change"]~=1 then
		page["offset"] = toPoint(page["offset"])
	end
	if page["tapes"] ~= nil then
		for k,v in pairs(page["tapes"]) do
			page["tapes"][k] = toPoint(page["tapes"][k])
		end
	end
	if page["ocrRect"] ~= nil then
		local p = {page["ocrRect"][1],page["ocrRect"][2]}
		p =  toPoint(p)
		page["ocrRect"][1] = p.x
		page["ocrRect"][2] = p.y

		p = {page["ocrRect"][3],page["ocrRect"][4]}
		p =  toPoint(p)
		page["ocrRect"][3] = p.x
		page["ocrRect"][4] = p.y
	end
	if page["notTape"] ~= nil and page["change"]~=1 then
		page["notTape"] = toPoint(page["notTape"])
	end
	--单点、多点找色
	if page["p"] ~=nil and page["change"]~=1 then
		if type(page["p"]) == "string" then
			page["p"] =colorChange(page["p"],pageAnchor)
		end
	end
	if page["ps"] ~=nil and page["change"]~=1 then
		for k1,v1 in pairs(page["ps"]) do
			page["ps"][k1] =colorChange(page["ps"][k1],pageAnchor)
		end
	end

	--FindColor
	if page["ct"] ~=nil and page["change"]~=1 then
		--page["ct"] =colorChange(page["ct"],pageAnchor)
	end
	--{"向上移动列表",7,ff="move",xy1={391,1019},xy2={391,311},step=150,wait=1500},
	if page["xy1"] ~=nil and page["change"]~=1 then
		page["xy1"]["a"] = pageAnchor
		local p = toPoint(page["xy1"])
		page["xy1"][1],page["xy1"][2] = p.x,p.y
		page["xy2"]["a"] = pageAnchor
		p = toPoint(page["xy2"])
		page["xy2"][1],page["xy2"][2] = p.x,p.y
	end

	if page["r"] ~=nil and page["change"]~=1 and type(page["r"]) == "table" then
		local p = toPoint({page["r"][1],page["r"][2],a = pageAnchor})
		page["r"][1],page["r"][2] = p.x ,p.y
		p =  toPoint({page["r"][3],page["r"][4],a = pageAnchor})
		page["r"][3],page["r"][4] = p.x,p.y
	end
	if page["clickRange"] ~=nil then
		local p = toPoint({page["clickRange"][1],page["clickRange"][2],a = pageAnchor})
		page["clickRange"][1],page["clickRange"][2] = p.x ,p.y
		p =  toPoint({page["clickRange"][3],page["clickRange"][4],a = pageAnchor})
		page["clickRange"][3],page["clickRange"][4] = p.x,p.y
	end
	return page
end

--[[
函数名: paChange
功能描述: 页面特征批量转换 - 递归遍历所有页面特征，对每个特征进行坐标转换和参数规范化

参数说明:
    pages (table): 页面特征表，包含多个特征定义
        - 支持数字索引和字符串索引
        - 支持嵌套的"任务界面"子表（递归处理）
        - 每个特征应包含名称(第一个元素)和锚点(第二个元素)

返回值:
    table: 转换后的页面特征表，所有坐标已根据当前分辨率适配

内部处理:
    1. 递归处理"任务界面"子表
    2. 调用pChange对每个特征进行坐标转换
    3. 处理数字字符串键("1" -> 1)的兼容转换
    4. 保留非特征类型的原始数据

使用示例:
    -- 在框架初始化时自动调用
    local convertedTaps = paChange(taps)

    -- 输入特征示例
    local taps = {
        ["登录按钮"] = {"登录", 1, ff = "ps", p = "100|200|0xffffff"},
        [1] = {"关闭", 5, ff = "ps", p = "300|400|0x000000"},
        ["任务界面"] = {
            ["任务1"] = {"任务", 1, ff = "ps", p = "50|100|0xffffff"}
        }
    }
]]
local function paChange(pages)
	local newPage = {}
	if type(pages) ~= "table" then
		return pages
	end
	for j,page in pairs(pages) do
		if j == "任务界面" then
			newPage["任务界面"] = paChange(pages["任务界面"])
		elseif type(page) == "table"  and  (type(page[1]) == "string" or type(page["1"]) == "string" ) then

			if page[1] == nil then
				page[1] = page["1"]
				page[2] = page["2"]
				page["1"] = nil
				page["2"] = nil
			end
			local num_j = tonumber(j)
			if num_j ~= nil then
				newPage[num_j] = pChange(page)
			else
				newPage[j] = pChange(page)
			end
		else
			newPage[j] = pages[j]
		end
	end

	return newPage
end
local function w(taps)
	local p
	local ps
	local mtaps = taps
	local tastName
	for i,pages in pairs(mtaps) do
		pages = paChange(pages)
		tastName = pages.name
		if tastName==nil then
			tastName = i
			pages.name = tastName
		end

		mtaps[i] = pages
	end
	return mtaps
end
local function freemInit(taps)
	local dev_dev = G.get("config.开发比例", nil)
	if dev_dev == nil then
		print("请设置 conf.开发比例 后启动")
		frameSdk.exitScript()
	end
	local game_dev ={ frameSdk.getDisplaySize() }

	direction = frameSdk.getDisplayRotate()
	comm.w = dev_dev[1]
	comm.h = dev_dev[2]
	Change.dev_dev = dev_dev
	Change.dev_vga = dev_dev[1]/dev_dev[2]
	Change.game_dev = game_dev
	Change.game_vga = game_dev[1]/game_dev[2]
	local changh,changw
	if Change.dev_vga > 1 and Change.game_vga < 1 then		--横屏转竖屏
		Change.transition =1
		changh = game_dev[1]
		changw = game_dev[2]
		Change.game_dev = {changw,changh}
		Change.game_vga = game_dev[2]/game_dev[1]
	elseif Change.dev_vga < 1 and Change.game_vga > 1 then 	--竖屏转横屏
		changh = game_dev[1]
		changw = game_dev[2]
		Change.game_dev = {changw,changh}
		Change.transition =2
		Change.game_vga = game_dev[2]/game_dev[1]
	end
	game = Change:new()
	local pages
	if taps ~= nil then
		pages =w(taps)
	end
	return pages
end

local function taskParamChange(allTaps)
    -- 遍历外层表（key: 任务类别, tast: 任务数据）
    for key, tast in pairs(allTaps) do
        -- 检查是否需要处理任务列表
        if tast["任务列表"] == true or key == "任务列表" then
            -- 遍历当前任务类别下的所有任务
            for k, v in pairs(tast) do
                -- 检测任务ID是否为数字（支持数值或数字字符串）
                if type(k) == "number" or (type(k) == "string" and tonumber(k)) then
                    -- 确保任务参数存在且是表结构
                    if type(v["任务参数"]) == "table" then
                        -- 调用参数设置函数并重组任务参数
                        v["任务参数"] = jy_frame.taskParamSet(
                                v["任务参数"][1],  -- 参数1
                                v["任务参数"][2],  -- 参数1
                                v["任务参数"][3],  -- 参数1
                                v["任务参数"][4],  -- 参数1
                                v["任务参数"][5],  -- 参数1
                                v["任务参数"][6],  -- 参数1
                                v["任务参数"][7],  -- 参数1
                                v["任务参数"][8],  -- 参数1
                                v["任务参数"][9]
                            )
                    end
                    tast[k] = v  -- 更新任务数据
                end
            end
            allTaps[key] = tast  -- 更新任务类别
        end
    end
    return allTaps  -- 返回处理后的数据集
end

jy_page.taskParamChange = taskParamChange
jy_page.freemInit = freemInit


return jy_page