-- =====================================================================
-- ljzckcb（贪玩·传奇 com.tanwan.ljzckcb）JUI 设置面板定义
-- 来源：按键精灵《龙迹之城》.uip 迁移（多选框→addCheckBox，输入框→addEditText，
--       下拉框→addSpinner，文字框→addTextView，水平布局→addRow）
-- 实现方式参照 engine\JUI.lua：jy_ui 描述器构建控件 → tab 数组（pager 多标签）
-- 消费方：JUI.ShowJUI(uiConf)——阻塞至【确定】(保存+继续) /【退出】(exitScript)
-- 控件名 = 读取键（getUISet/ReadUIConfig 同名读取），与 conf.lua readUI 联动
-- =====================================================================

local jy_ui = require("JY.Utils.UIUtils")

-- UI 基础配置（参数对齐 engine\JUI.lua 的 UIConf；高度/名称取按键 UIConf 值）
local UIConf = {
        UIJson            = {},                           -- UI界面数据
        script_name       = "龙迹之城",                   -- 脚本名称（按键值）
        config_list       = {                             -- 配置列表（多配置档案）
                "配置1",
                "配置2",
                "配置3",
                "配置4"
        },
        config_path       = rawget(_G, "__SCRIPT_DIR__") or ".",  -- 配置文件路径（脚本目录）
        ui_height   = 2900,       -- 界面高度（按键值；引擎 >1200 自动降级 72%）
        ui_width    = 700,        -- 界面宽度
        interface_name = "龙迹之城助手",
}

-- ============ 页签 1：功能 ============
local function createUITabFunc()
        local result = {}
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("新手任务", "新手", false))
        table.insert(result, jy_ui.addCheckBox("领取红包", "领取红包", true))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("领取奖励", "奖励", true))
        table.insert(result, jy_ui.addTextView("领取奖励提示1", "自动领取活动、打宝金库等奖励"))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addTextView("提示1", "↓↓↓↓↓面板任务↓↓↓↓↓"))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("英雄", "英雄殿", true))
        table.insert(result, jy_ui.addEditText("英雄间隔", "50", "", 80))
        table.insert(result, jy_ui.addTextView("英雄间隔提示1", "分钟执行一次"))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("合成", "合成", true))
        table.insert(result, jy_ui.addTextView("合成方案提示1", "方案"))
        table.insert(result, jy_ui.addSpinner("合成方案", {"全部", "终极"}, 0))
        table.insert(result, jy_ui.addTextView("合成间隔提示2", "间隔"))
        table.insert(result, jy_ui.addEditText("合成间隔", "50", "", 80))
        table.insert(result, jy_ui.addTextView("合成间隔提示3", "分钟"))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addTextView("合成提示1", "合成：会自动锻造和合成其他"))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("背包", "背包", true))
        table.insert(result, jy_ui.addCheckBox("天赋", "天赋", true))
        table.insert(result, jy_ui.addCheckBox("符文", "符文", true))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("商城", "商城", true))
        table.insert(result, jy_ui.addCheckBox("军衔", "军衔", true))
        table.insert(result, jy_ui.addCheckBox("转生", "转生", false))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("魔器", "魔器", true))
        table.insert(result, jy_ui.addCheckBox("神坛", "神坛", true))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("历练", "历练", true))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("历练购买", "历练购买道具", true))
        table.insert(result, jy_ui.addCheckBox("历练传奇", "传奇币过任务", false))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addTextView("说明1", "背包：自动使用元宝，自动回收"))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addTextView("说明2", "商城：自动购买行会两个道具"))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addTextView("说明3", "合成会自动合成和锻造，看个人需求开启"))
        return result
end

-- ============ 页签 2：打怪 ============
local function createUITabFight()
        local result = {}
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addTextView("世界BOOS提示2", "↓—————世界BOOS—————↓"))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("比武", "比武", true))
        table.insert(result, jy_ui.addCheckBox("天空", "天空", true))
        table.insert(result, jy_ui.addCheckBox("荒原", "荒原", true))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("神域战灵", "神域战灵", true))
        table.insert(result, jy_ui.addCheckBox("万妖", "万妖", true))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("战灵BS", "战灵BS", false))
        table.insert(result, jy_ui.addCheckBox("无尽深渊", "无尽深渊", false))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("狂暴战场", "狂暴战场", false))
        table.insert(result, jy_ui.addCheckBox("至尊战灵", "至尊战灵(正中战灵)", false))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addTextView("整点boos顺序1", "狂暴顺序 "))
        table.insert(result, jy_ui.addSpinner("整点boos顺序", {"顺时针", "逆时针"}, 0))
        table.insert(result, jy_ui.addTextView("狂暴抢归属1", "  抢归属 "))
        table.insert(result, jy_ui.addSpinner("狂暴抢归属", {"不抢", "1%", "2%", "3%"}, 0))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("狂暴遗迹", "狂暴遗迹", false))
        table.insert(result, jy_ui.addSpinner("遗迹等级", {"最高", "低1级", "低2级"}, 0))
        table.insert(result, jy_ui.addTextView("遗迹间隔1", "间隔"))
        table.insert(result, jy_ui.addEditText("狂暴遗迹间隔", "120", "", 80))
        table.insert(result, jy_ui.addTextView("遗迹间隔2", "分钟"))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("反攻魔界", "反攻魔界", false))
        table.insert(result, jy_ui.addTextView("魔界等级111", "  魔界等级 "))
        table.insert(result, jy_ui.addSpinner("魔界等级", {"全打", "35", "45", "55"}, 0))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("无疆", "无疆", true))
        table.insert(result, jy_ui.addSpinner("无疆等级", {"最高", "低1级", "低2级", "低3级", "低4级"}, 0))
        table.insert(result, jy_ui.addTextView("无疆间隔1", "间隔"))
        table.insert(result, jy_ui.addEditText("无疆间隔", "120", "", 80))
        table.insert(result, jy_ui.addTextView("无疆间隔2", "分钟"))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("修罗扫荡", "修罗扫荡", true))
        table.insert(result, jy_ui.addTextView("修罗扫荡间隔1", "  修罗扫荡间隔"))
        table.insert(result, jy_ui.addEditText("修罗扫荡间隔", "50", "", 80))
        table.insert(result, jy_ui.addTextView("修罗扫荡间隔2", "分钟"))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("打修罗", "磨修罗    ", true))
        table.insert(result, jy_ui.addTextView("修罗限制个1", "从"))
        table.insert(result, jy_ui.addSpinner("修罗序号", {"第1个", "第2个", "第3个", "第4个", "第5个"}, 1))
        table.insert(result, jy_ui.addTextView("修罗限制个2", "开始"))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addTextView("修罗限制1", "只需要设置一次，脚本会记录"))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addTextView("挂机设置提示2", "↓—————挂机设置—————↓"))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addTextView("挂机地图文本", "挂机地图"))
        table.insert(result, jy_ui.addSpinner("挂机地图", {"普通刷怪", "神龙大陆"}, 0))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addTextView("挂机模式文本", "挂机模式"))
        table.insert(result, jy_ui.addSpinner("挂机模式", {"最高等级", "BOOS全刷"}, 0))
        table.insert(result, jy_ui.addCheckBox("快速刷图", "快速刷图", false))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addTextView("快速刷图1", "能快速打死boos勾选快速刷图"))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("治疗减冷却", "治疗减冷却", false))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addTextView("提示2", "↓—————地图选择—————↓"))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("等级BS", "等级", true))
        table.insert(result, jy_ui.addCheckBox("英魂BS", "英魂", true))
        table.insert(result, jy_ui.addCheckBox("符文BS", "符文", true))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("龙裔BS", "龙裔", true))
        table.insert(result, jy_ui.addCheckBox("异兽BS", "异兽", true))
        table.insert(result, jy_ui.addCheckBox("玛雅神殿", "神殿", true))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("神龙大BS", "神龙大BS", false))
        table.insert(result, jy_ui.addCheckBox("远古荒原", "远古荒原", true))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addTextView("神龙大提示2", "↓—————神龙大陆挂机—————↓"))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("只刷小BS", "只刷小BS", true))
        table.insert(result, jy_ui.addTextView("挂机本", "      神龙地图"))
        table.insert(result, jy_ui.addSpinner("神龙地图", {"龙兴原", "龙眠旷野", "永夜林地"}, 0))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addTextView("说明4", "1、所有世界boos会自动检测，自动打"))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addTextView("说明5", "2、比武、无疆、荒原有几个小时的间隔"))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addTextView("说明6", "3、狂暴战场自动进入，且会完成战场任务"))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addTextView("说明7", "4、阵营战、攻沙等整点活动会自动进入"))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addTextView("说明8", "5、挂机只会刷每个地图的BOOS"))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addTextView("说明9", "5、挂机地图选择神龙大陆，就只会在神龙大陆挂机"))
        return result
end

-- ============ 页签 3：发广告 ============
local function createUITabAd()
        local result = {}
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("发消息", "发广告", false))
        table.insert(result, jy_ui.addCheckBox("辅助功能", "辅助功能", false))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addTextView("游s111", "发言区域："))
        table.insert(result, jy_ui.addSpinner("发言区域", {"跨服", "世界"}, 0))
        for i = 1, 5 do
                table.insert(result, jy_ui.addRow())
                table.insert(result, jy_ui.addEditText("喊话" .. i, "", "", 600))
        end
        return result
end

-- ============ 页签 4：账号 ============
local function createUITabAccount()
        local result = {}
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addTextView("游戏版本111", "版本："))
        table.insert(result, jy_ui.addSpinner("游戏版本", {"龙迹之城"}, 0))
        table.insert(result, jy_ui.addTextView("区号1", "区号"))
        table.insert(result, jy_ui.addEditText("区号", "", "", 100))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addTextView("轮休息时间", "每轮休息时间："))
        table.insert(result, jy_ui.addEditText("轮号休息", "", "", 100))
        table.insert(result, jy_ui.addTextView("文字框1111", "分钟"))
        table.insert(result, jy_ui.addCheckBox("选区", "重新选区", false))
        table.insert(result, jy_ui.addRow())
        table.insert(result, jy_ui.addCheckBox("记录日志", "记录日志", false))
        table.insert(result, jy_ui.addCheckBox("任务提示", "任务提示", false))
        table.insert(result, jy_ui.addCheckBox("单号模式", "单号模式", true))
        return result
end

-- ============ 组装（tab 数组：ShowJUI 按 tab_name 分页渲染） ============
local uiConf = {
        UIConf = UIConf,
        interface_name = UIConf.interface_name,
        tabs = {
                { tab_name = "功能", controls = createUITabFunc() },
                { tab_name = "打怪", controls = createUITabFight() },
                { tab_name = "发广告", controls = createUITabAd() },
                { tab_name = "账号", controls = createUITabAccount() },
        },
}

return uiConf
