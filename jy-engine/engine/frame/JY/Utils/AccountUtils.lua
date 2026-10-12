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
local jy_task
local jy_acc = {}

local function getGameVer(i)
	local ver = jy_ui.getUISet("游戏版本" .. i , 1)
	if ver == 0 then
		ver = jy_ui.getUISet("游戏版本" , 1)
	end
	if ver == 0 then
		ver = 1
	end
	return ver
end

local function mainAccCheck(i)

	local mainAcc = {}
	-- 分割主号字符串为数组
	for num in string.gmatch(G.config(" 主号") , "([^-]+)") do
		table.insert( mainAcc , num)
	end
	local mainStatus = G.config("主号状态")
	local mainPriority = G.config("主号优先")
	if mainStatus == 0 then
		if mainPriority and not (JY.jy_tab.arraySearch(mainAcc , tostring(i)) ~= - 1) then
			print("主号优先 ***************** 跳过主号1 序号 = " , i)
			return true
		elseif not mainPriority and (JY.jy_tab.arraySearch(mainAcc , tostring(i)) ~= - 1) then
			print("主号优先 *****************跳过主号2 序号 = " , i) -- 修正：原逻辑应输出主号4
			return true
		end
	elseif mainStatus == 1 then
		if mainPriority and (JY.jy_tab.arraySearch(mainAcc , tostring(i)) ~= - 1) then
			print("主号优先 *****************跳过主号3 序号 = " , i)
			return true
		elseif not mainPriority and not (JY.jy_tab.arraySearch(mainAcc , tostring(i)) ~= - 1) then
			print("主号优先 *****************跳过主号4 序号 = " , i) -- 修正：原逻辑应输出主号2
			return true
		end
	elseif mainStatus == 2 then
		-- 结束脚本（根据实际需求实现）
		frameSdk.exitScript()
	end

	return false
end

local function getpathes(rootpath, pathes)
    local pathes = pathes or {}
	-- frameSdk 迁移修复（S3）：原代码为 fileExist("/mnt/sdcard/test.png")(rootpath)，
	-- 将 fileExist 返回值误当函数二次调用（布尔值不可调用，运行必报错）；
	-- 按本函数"枚举 rootpath 目录"的明显意图，修复为直接判断 rootpath 是否存在
	if frameSdk.fileExist(rootpath) then
		for entry in frameSdk.lfs.dir(rootpath) do
			if entry ~= '.' and entry ~= '..' and entry ~= "lib" and entry ~= "libs" then
				local path = rootpath .. '/' .. entry
				local attr = frameSdk.lfs.attributes(path)
				if attr ~= nil then
					if attr.mode == 'directory' then
						table.insert(pathes, path)
					end
				end
			end
		end
	end
    return pathes
end

local function setAccountFile(saveFilePath, gameFilePath, account, fileName, setName)
    -- 拼接账户根路径（VBS: accFilePath = saveFilePath & account）
    local accFilePath = saveFilePath .. account

    -- 拼接最终账户文件路径，优先使用setName，无则用fileName（VBS: setName or fileName）
    local targetSetName = setName or fileName
    local accFile = accFilePath .. "/" .. targetSetName

    -- 初始化创建状态（对应VBS的createStatus = 0）
    local createStatus = 0

    -- 检查路径是否存在（模拟VBS的Dir.Exist(accFile) <> 0）
    -- lfs.attributes返回nil表示路径不存在，否则存在
    local attr = frameSdk.lfs.attributes(accFile)
    if attr then
        -- 复制文件：这里实现跨平台的文件复制逻辑（替代VBS的Dir.Copy）
        local success, err = JY.jy_file.copyFile(accFile, gameFilePath)
        if not success then
            print("文件复制失败：", err)
        end
    end

    -- 可选：返回状态（原VBS未返回，如需可启用）
    -- return createStatus
end
-- 转换后的 saveGameFile 函数
local function saveGameFile(appid, files, setFilePath, account)
    local path, fileName, setName

    for _, v in ipairs(files) do
        path = v[1]
        fileName = v[2]
        setName = v[3]

        -- 拼接路径并调用setAccountFile
        local fullPath = path .. "/" .. fileName
        setAccountFile(setFilePath, fullPath, account, fileName, setName)
    end
end
local function saveAccountFile(saveFilePath, gameFilePath, account, fileName, setName)
    -- 1. 拼接路径（与原逻辑一致）
    local accFilePath = saveFilePath .. account
    local targetSetName = setName or fileName
    local accFile = accFilePath .. "/" .. targetSetName

    -- 2. 初始化创建状态（保留原接口）
    local createStatus = 0

    -- 3. 检查 saveFilePath 是否存在且是目录（不存在则直接报错）
    local savePathAttr = frameSdk.lfs.attributes(saveFilePath)
    if not savePathAttr then
        error("目录不存在：" .. saveFilePath)
    elseif savePathAttr.mode ~= "directory" then
        error("不是有效目录：" .. saveFilePath)
    end

    -- 4. 检查 accFilePath 是否存在且是目录（不存在则直接报错）
    local accPathAttr = frameSdk.lfs.attributes(accFilePath)
    if not accPathAttr then
        error("目录不存在：" .. accFilePath)
    elseif accPathAttr.mode ~= "directory" then
        error("不是有效目录：" .. accFilePath)
    end

    -- 5. 直接执行文件复制（复制时若accFile父目录不存在，copyFile内会报错）
    local success, err = pcall(JY.jy_file.copyFile, gameFilePath, accFile)
    if not success then
        error("文件复制失败：" .. err)
    end

    -- 返回状态（保留原接口）
    return createStatus
end
local function copyGameFile(appid, Files, setFilePath, account)
    local path, fileName, setName
    for _, v in ipairs(Files) do
        path = v[1]
        fileName = v[2]
        setName = v[3]
        saveAccountFile(setFilePath, path .. "/" .. fileName, account, fileName, setName)
    end
end

local function accSet(i , user)
	--[[
		if conf["主号模式"] then
			mainAccStatus = mainAccCheck(i)
		end
		user = frameSdk.splitStr(user,"--")
		--自动建号自动生成账号密码
		if conf["自动建号"] == true and conf["当前账号"] == "11" then

			if conf["建号类型"] ~= nil then
				user = createAccounts(conf["建号类型"]["rty"])
			else
				user = createAccounts()
			end
			if vars["任务变量"]["当前区"] == nil then
				vars["任务变量"]["当前区"] = 1
			end
			conf["是否建号"] = 1
		else
			conf["是否建号"] = 0
		end
	]]

	user = frameSdk.splitStr(user,"--")
	--设置账号到全局conf
	local currentAccount = JY.jy_str.trim(user[1])
	local currentPassword = JY.jy_str.trim(user[2])
	G.setConfig("当前账号", currentAccount)
	G.setConfig("当前密码", currentPassword)

	if G.config("游戏模式") == 7 then
		local roleNum = JY.jy_num.toNumber(JY.jy_str.trim(user[3]),1)
		G.setConfig("角色数量", roleNum)
		JY.jy_file.filePutUser("角色数量",roleNum, currentAccount)
		G.setConfig("当前区", JY.jy_str.trim(user[4]))
	else
		G.setConfig("当前区", JY.jy_str.trim(user[3]))
	end
	G.setConfig("当前序号", i)
	JY.jy_file.filePut("当前序号", i)
	local appid = G.config("appid")
	G.setRuntime("appid", appid[getGameVer(i)])
	local errAcc = JY.jy_file.fileGetUser("封号状态" , "1" , currentAccount)
	if errAcc ~= "1" then
		JY.jy_sys.mlog("游戏启动 ***************** 检测到账号：" .. currentAccount .. " 已被封，跳过账号")
		return nil;
	end
	-- IP切换流程
	local taps = G.taps()
	if G.config("切换IP") == true and taps["切换IP"] ~= nil then
		G.setVars("任务变量.ip状态", 0)
		JY.jy_sys.mlog("游戏启动 ***************** 切换IP")
		jy_task.otherRun("切换IP" , nil)

		if G.vars("任务变量.ip状态") ~= 1 then
			for outi = 1 , 5 do
				-- 计算倒计时显示（5-outi 显示4~0秒）
				frameSdk.toast("游戏启动 ***************** IP切换失败，切换账号倒计时:" .. (5 - outi) .. "秒")
				JY.jy_sys.mlog("游戏启动 ***************** IP切换失败，切换账号倒计时:" .. (5 - outi) .. "秒")
				frameSdk.sleep(1000) -- 使用统一命名规范的延时函数
			end
			return nil
		end
	end
	return user
end
jy_acc.accSet = accSet

--递归删除目录中的文件，不删除文件夹
local function deleteDirectoryContents(dir_path)
	-- 验证目录存在性
	local attr = frameSdk.lfs.attributes(dir_path)
	if not attr or attr.mode ~= "directory" then
		return false , "无效目录: " .. tostring(dir_path)
	end

	-- 递归删除函数
	local function _delete(path)
		for entry in frameSdk.lfs.dir(path) do
			if entry ~= "." and entry ~= ".." then -- 跳过特殊目录
				local full_path = path .. "/" .. entry
				local attr = frameSdk.lfs.attributes(full_path)

				if attr then
					if attr.mode ~= "directory" then
						os.remove(full_path) -- 删除文件
						print("🗑️ 删除文件: " .. full_path)
					end
				end
			end
		end
	end

	-- 执行删除操作
	_delete(dir_path)
	return true
end
--[[游戏文件清理
-- appid: 应用程序ID
-- conf: 清理配置表
-- 返回值: 清理状态 (true=成功, false=跳过/失败)
]]
local function cleanGameFile(appid)
	-- 检查是否在白名单中
	local notDelAppid = G.config("notDelAppid")
	if notDelAppid and type(notDelAppid) == "table" then
		for _ , v in ipairs(notDelAppid) do
			if appid == v then
				return false -- 在白名单中，跳过清理
			end
		end
	end

	local function safeDelete(path)
		if frameSdk.fileExist(path) then
			if (frameSdk.lfs.attributes(path) or {}).mode == 'directory' then
				deleteDirectoryContents(path) -- 递归删除目录
			else
				os.remove(path) -- 删除文件
			end
			return true
		end
		return false
	end

	-- 清理SD卡文件
	local sdFiles = G.config("sdFiles")
	if sdFiles and type(sdFiles) == "table" and #sdFiles > 0 then
		for _ , sdFile in ipairs(sdFiles) do
			local fullPath = frameSdk.getSdPath() .. "/" .. sdFile
			safeDelete(fullPath)
		end
	end

	-- 清理Android数据文件
	local androidFiles = G.config("AndroidFiles")
	if androidFiles and type(androidFiles) == "table" and #androidFiles > 0 then
		for _ , androidFile in ipairs(androidFiles) do
			local fullPath = frameSdk.getSdPath() .. "/Android/data/" .. appid .. androidFile
			safeDelete(fullPath)
		end
	end

	-- 清理应用数据目录
	local directorys = G.config("directorys")
	if directorys and type(directorys) == "table" and #directorys > 0 then
		for _ , directory in ipairs(directorys) do
			local basePath = "/data/data/" .. appid
			local paths = getpathes(basePath , directory) or {}

			if type(paths) == "table" then
				for _ , path in ipairs(paths) do
					safeDelete(path)
				end
			end
		end
	end

	return true -- 清理成功
end
local function getDefulUserFile(defulFile)
    local defaultPath = G.config("默认账号路径")
    local picturesPath = defaultPath .. defulFile .. ".txt"
	if frameSdk.fileExist(picturesPath) then
		for i = 1, 500, 1 do
			picturesPath = defaultPath .. defulFile .. "_" .. i .. ".txt"
			if not frameSdk.fileExist(picturesPath) then
				break
			end
		end
	end

	JY.jy_file.filePut("账号文件", picturesPath)
    return picturesPath
end
local function accountReord()
	local defaultFileName = G.config("默认账号文件名")
	local sdCardPath = frameSdk.getWorkPath() .. defaultFileName .. ".txt"
	local picturesPath = frameSdk.getSdPath() .. "/Pictures/" .. defaultFileName .. ".txt"

	-- 处理当前区默认值
	if G.vars("任务变量.当前区") == nil then
		G.setVars("任务变量.当前区", 1)
	end

	-- 仅当账号有效时执行写入
	local currentAccount = G.config("当前账号")
	if currentAccount ~= "-1" then
		local currentPassword = G.config("当前密码")
		local accountOther = G.config("accountOther")
		local currentArea = G.vars("任务变量.当前区")
		local writeText
		if accountOther == nil then
			writeText = currentAccount .. "--" .. currentPassword .. "--" .. currentArea .. "\r\n"
		else
			writeText = currentAccount .. "--" .. currentPassword .. "--" .. currentArea .. "--" .. accountOther .. "\r\n"
		end

		-- 更新共享变量
		G.setVars("任务变量.账号已写入", "1")

		-- 写入 SD 卡
		JY.jy_file.mWriteFile(sdCardPath , writeText)
		JY.jy_sys.mlog("创建账号 ***************** 账号写入内部存储 : " , writeText)

		-- 写入 Pictures 目录
		local picturesPath = getDefulUserFile(defaultFileName)
		JY.jy_file.mWriteFile(picturesPath , writeText)
		JY.jy_sys.mlog("创建账号 ***************** 账号写入Pictures : " , writeText)
	end
end


-- loginErr 函数
local function loginErr()
	if G.config("账号异常登录") == true then
		local accFileName = jy_ui.getUISet("账号文件" , nil)
		if accFileName == nil or accFileName == "" then
			accFileName = G.config("默认账号文件名")
		end
		local accountFile = "/sdcard/Pictures/" .. accFileName .. ".txt"
		local users = JY.jy_file.readFileLines(accountFile)
		local user = {}
		local userStr
		local upState = false
		local currentAccount = G.config("当前账号")
		for i = 1 , #users do
			-- D3/D6 修复（FireFly 移植版）：JY.trim → JY.jy_str.trim；splitStr 返回表被当函数调用
			users[i] = JY.jy_str.trim(users[i])
			userStr = frameSdk.splitStr(users[i] , "--")
			if userStr ~= "" then
				if userStr[1] == currentAccount then
					local e1 = frameSdk.utf8.inStr(1 , users[i] , "--error")
					local e2 = frameSdk.utf8.inStr(1 , users[i] , "--账号异常")
					if e1 == 0 and e2 == 0 then
						users[i] = users[i] .. "--error"
						upState = true
					elseif e2 ~= 0 then
						users[i] = string.gsub(users[i] , "--账号异常" , "")
						users[i] = users[i] .. "--error"
						upState = true
					end
					break
				end
			end
		end
		if upState == true then
			JY.jy_file.writeFileLines(accountFile , table.concat(users, "\n"))
		end
	end
end
-- 轮号休息函数
local function lhSleep(t)
	local old_t = frameSdk.systemTime()
	local new_t , st
	if t ~= 0 then
		while true do
			new_t = frameSdk.systemTime()
			if new_t - old_t > t then
				break
			end
			frameSdk.sleep(1000)
			st = math.floor((t - (new_t - old_t)) / 1000)
			frameSdk.toast("轮号休息剩余时间: " .. frameSdk.DateTime.Format("%M 分 %S 秒" , st))
		end
	end
end

jy_acc.accountReord = accountReord
jy_acc.cleanGameFile = cleanGameFile
jy_acc.saveGameFile = saveGameFile
jy_acc.loginErr = loginErr
jy_acc.lhSleep = lhSleep

jy_acc.copyGameFile = copyGameFile
return jy_acc
