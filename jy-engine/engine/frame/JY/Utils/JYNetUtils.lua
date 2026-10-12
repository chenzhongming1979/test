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

local jy_sys = require("JY.Utils.JY").jy_sys
--心跳
local function keyProt()
	local scriptNo = G.runtime("netParam.scriptNo")
	local key = G.runtime("netParam.cdkey")
	local err = 0

	while true do
		jy_sys.sleep(G.runtime("netParam.keyPortDelay") or 30000) -- 延迟30秒

		local ret = 0 -- JY_SFUN.JY_KeyProt(key , scriptNo)

		if ret == 2 then
			err = err + 1
			if err > G.runtime("netParam.errorCount") or 5 then
				break -- 退出循环
			end
		elseif ret == 1 then
			break -- 退出循环
		else
			err = 0 -- 重置错误计数
		end
	end
end