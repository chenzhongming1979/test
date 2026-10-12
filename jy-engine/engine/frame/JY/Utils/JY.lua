-- 导入框架全局配置 不使用框架可删除
local GlobalStateManager = require("JY.config.GlobalStateManager")
G = require("JY.config.G")
-- 初始化全局状态管理器
-- 导入Point对象池（性能优化：复用Point对象，减少GC压力）
local PointPool = require("JY.Core.PointPool")

-- 导入必要的Java类库
frameSdk.import('java.io.File')
frameSdk.import('java.lang.*')
frameSdk.import('java.util.*')
frameSdk.import('android.content.Context')
frameSdk.import('android.hardware.Sensor')
frameSdk.import('android.hardware.SensorEvent')
frameSdk.import('android.hardware.SensorEventListener')
frameSdk.import('android.hardware.SensorManager')
frameSdk.import('com.nx.assist.lua.LuaEngine')
frameSdk.import('com.nx.assist.lua.PaddleOcr')
-- 单次初始化随机种子
local seed = tostring(frameSdk.systemTime()) .. tostring(frameSdk.systemTime())  .. tostring(math.random(999999999))
math.randomseed(tonumber(seed:reverse():sub(1,  10)) or frameSdk.systemTime())


--[[
═══════════════════════════════════════════════════════════
                        JY 基础模块API文档
                       总计: 66个核心函数 + 2个全局函数
═══════════════════════════════════════════════════════════

📍 jy_ocr (文字识别模块) ──────────────────────────────
├── performOcrWithSnapshot      │ 区域文字识别(PaddleOCR引擎)
                                │ 支持RGB过滤、padding扩展

📍 jy_num (数字运算模块) ──────────────────────────────
├── qz                         │ 取绝对值
├── isNumber                   │ 判断是否为有效数字
├── toFixed                    │ 四舍五入保留小数位
├── toNumber                   │ 类型转换为数字(带回退默认值)
└── extractNumbers             │ 从字符串提取所有数字

📍 jy_tab (表操作模块) ───────────────────────────────c
├── arraySearch                │ 在数组中查找元素索引
└── arrayRand                  │ 随机抽取一个数组元素

📍 jy_file (文件操作模块) ─────────────────────────────
├── formatSize                 │ 字节数格式化为人类可读(B/KB/MB/GB/TB)
├── getTotalFileLines          │ 快速统计文件总行数
├── getLine                    │ 读取指定行内容(优化性能)
├── readFile                   │ 读取全部文件内容(可选加密删除)
├── readFileLines              │ 读取文件转为Lua数组(支持选项)
├── readFileLine               │ 读取单行(增强版getLine)
├── writeFileLines             │ 将数组批量写入文件
├── fileGet                    │ 读取JSON配置文件键值
├── filePut                    │ 写入JSON配置文件键值(自动保存)
├── fileGetUser                │ 读取用户专属配置(按userName隔离)
├── filePutUser                │ 写入用户专属配置
├── delFile                    │ 删除单个文件
├── deleteFile                 │ 批量删除文件(支持排除名单)
├── getFileName                │ 提取文件名(去除路径和扩展名)
├── createTempFile             │ 创建唯一临时文件
├── createRandFileName         │ 生成随机文件名(指定前缀/长度/后缀)
├── scanPathEx                 │ 递归扫描目录下所有文件
├── getDirectoryPaths          │ 获取所有子目录路径
├── getPathType                │ 判断路径类型(不存在/文件/目录)
├── mWriteFile                 │ 多安全级别写入(自动创建目录)
├── cleanGameFile              │ 清理游戏应用特定缓存目录
├── deleteDirectoryContents    │ 递归清空目录内容(保留目录本身)
└── fileLine2delete            │ 条件删除行(支持多种匹配规则)

📍 jy_str (字符串操作模块) ────────────────────────────
├── inStrEx                    │ 智能字符串包含判定(支持多关键词OR逻辑)
└── UUID                       │ 生成32位UUID(不含连字符)

📍 jy_sys (系统操作模块) ─────────────────────────────
├── havaApp                    │ 检测APP是否安装(通过appid)
├── copyFile                   │ Java IO复制文件(高效可靠)
├── execute                    │ 安全执行Shell命令(pcall保护)
├── mlog                       │ 结构化日志输出(标记来源函数和时间)
├── acLog                      │ 特征信息记录(用于调试追踪)
├── isScreenStable             │ 屏幕静止检测(像素变化敏感度控制)
├── sleep                      │ 智能延时(±20%随机波动防检测)
└── Shake                      │ 触发设备重力感应事件

📍 jy_touch (触控操作模块) ────────────────────────────
├── gestureMoveCrestPath       │ 波浪轨迹模拟(crestPath算法)
├── gestureMove                │ 自定义多点手势移动
├── longClick                  │ 长按操作(持续时间可控)
├── click                      │ 人性化点击(位置±5px随机浮动)
├── click2                     │ 精确点击(无偏差)
├── zOut                       │ 双指放大手势(中心扩散)
├── zIn                        │ 双指缩小/捏合手势
├── moveLine                   │ 直线滑动(细分点平滑过渡)
└── moveCtrl                   │ 贝塞尔曲线滑动(三次Bezier公式)

📍 jy_rand (随机生成模块) ────────────────────────────
├── myRand                     │ 万能随机生成器
│                              │ 支持: num/int/phone/email/name/uuid/ip/mac/hex/binary/base64
├── randAcc                    │ 生成随机账号密码组合
├── randomFloat                │ 浮点数值浮动(num ± offset范围)
└── randomRange                │ 区间随机整数[min, max]

📍 jy_time (时间操作模块) ────────────────────────────
├── countDown                  │ Toast倒计时显示
├── timeFormat                 │ 秒数人性化格式化(XX天XX时XX分XX秒)
├── getTimeInfo                │ 解析时间戳详细字段(table形式)
├── getWeek                    │ 星期几(返回1~7对应周一~周日)
├── getDay                     │ 月中日(1~31)
└── getMonth                   │ 年中月(1~12)

📍 jy_net (网络操作模块) ────────────────────────────
├── getNetIP                   │ 获取公网IP地址(API查询)
├── checkPing                  │ ping.baidu.com延迟测试(Root权限)
└── ipCheck                    │ IP变更历史记录(持久化存储比对)

🔄 全局工具函数 ───────────────────────────────────────
├── Iif                        │ 三元表达式实现(Iif(condition,trueVal,falseVal))
└── IsEmpty                    │ 空值综合判定(nil/" "/{}均为empty)

⚙️ 配置字典 jy_conf 说明 ──────────────────────────────
• charSet/LETTERS/HEX          : 随机字符集(大小写字母/数字/十六进制)
• PHONE_HEADERS                : 中国三大运营商常见号段前缀
• FIRST_NAME/LAST_NAME         : 中文百家姓+常用汉字库(用于myRand.name)
• ADJECT_LIST/NOUN_LIST        : 形容词/名词词库(用于智能命名)
• EMAIL_SUFFIX                 : 主流邮箱服务商域名(@qq/@hotmail/@sohu)
• NAME_SEPARATORS              : 用户名分隔符(- _ | 、 .)
• workPath/sdPath              : 工作空间/SDCard根目录路径
• rowNum/ui_idx                : 行计数/UI组件索引(运行时动态维护)
• 是否打印输出                  : 日志输出总开关(boolean)
• runEnvType                   : 运行环境标识(getRunEnvType())

💡 使用示例:
  local ocr_result = jy_ocr.performOcrWithSnapshot(100,100,300,200,{rgb={255,0,0}})
  local rnd_name = jy_rand.myRand("name", 6)
  jy_sys.sleep(2000)  -- 实际等待1600~2400ms随机

═══════════════════════════════════════════════════════════
版本: 1.0 | 更新: 自动同步代码实际函数
═══════════════════════════════════════════════════════════
]]

--公共配置
local jy_conf = {

	--随机字符串
	charSet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789",
	--工作目录
	workPath = frameSdk.getWorkPath(),
	--sd目录
	rowNum = 0,
	ui_idx = 0,
	sdPath = frameSdk.getSdPath(),
	["是否打印输出"] = G.config("是否打印输出") or false,
    --随机参数
	LETTERS = {"A","B","C","D","E","F","G","H","I","J","K","L","M","N","O","P","Q","R","S","T","U","V","W","X","Y","Z","a","b","c","d","e","f","g","h","i","j","k","l","m","n","o","p","q","r","s","t","u","v","w","x","y","z"},
    HEX = {"0","1","2","3","4","5","6","7","8","9","a","b","c","d","e","f","A","B","C","D","E","F"},
    PHONE_HEADERS = {133,149,153,173,177,180,181,189,199,130,131,132,145,155,156,166,171,175,176,185,186,166,134,135,136,137,138,139,147,150,151,152,157,158,159,172,178,182,183,184,187,188,198},
    EMAIL_SUFFIX = {"@qq.com","@hotmail.com","@sohu.com"},
    NAME_SEPARATORS = {"-", "_", "|", "、", "."},
	FIRST_NAME = {"赵","钱","孙","李","周","吴","郑","王","冯","陈","楮","卫","蒋","沈","韩","杨","朱","秦","尤","许","何","吕","施","张","孔","曹","严","华","金","魏","陶","姜","戚","谢","邹","喻","柏","水","窦","章","云","苏","潘","葛","奚","范","彭","郎","鲁","韦","昌","马","苗","凤","花","方","俞","任","袁","柳","酆","鲍","史","唐","费","廉","岑","薛","雷","贺","倪","汤","滕","殷","罗","毕","郝","邬","安","常","乐","于","时","傅","皮","卞","齐","康","伍","余","元","卜","顾","孟","平","黄","和","穆","萧","尹","姚","邵","湛","汪","祁","毛","禹","狄","米","贝","明","臧","计","伏","成","戴","谈","宋","茅","庞","熊","纪","舒","屈","项","祝","董","梁","杜","阮","蓝","闽","席","季","麻","强","贾","路","娄","危","江","童","颜","郭","梅","盛","林","刁","锺","徐","丘","骆","高","夏","蔡","田","樊","胡","凌","霍","虞","万","支","柯","昝","管","卢","莫","经","房","裘","缪","干","解","应","宗","丁","宣","贲","邓","郁","单","杭","洪","包","诸","左","石","崔","吉","钮","龚","程","嵇","邢","滑","裴","陆","荣","翁","荀","羊","於","惠","甄","麹","家","封","芮","羿","储","靳","汲","邴","糜","松","井","段","富","巫","乌","焦","巴","弓","牧","隗","山","谷","车","侯","宓","蓬","全","郗","班","仰","秋","仲","伊","宫","宁","仇","栾","暴","甘","斜","厉","戎","祖","武","符","刘","景","詹","束","龙","叶","幸","司","韶","郜","黎","蓟","薄","印","宿","白","怀","蒲","邰","从","鄂","索","咸","籍","赖","卓","蔺","屠","蒙","池","乔","阴","郁","胥","能","苍","双","闻","莘","党","翟","谭","贡","劳","逄","姬","申","扶","堵","冉","宰","郦","雍","郤","璩","桑","桂","濮","牛","寿","通","边","扈","燕","冀","郏","浦","尚","农","温","别","庄","晏","柴","瞿","阎","充","慕","连","茹","习","宦","艾","鱼","容","向","古","易","慎","戈","廖","庾","终","暨","居","衡","步","都","耿","满","弘","匡","国","文","寇","广","禄","阙","东","欧","殳","沃","利","蔚","越","夔","隆","师","巩","厍","聂","晁","勾","敖","融","冷","訾","辛","阚","那","简","饶","空","曾","毋","沙","乜","养","鞠","须","丰","巢","关","蒯","相","查","后","荆","红","游","竺","权","逑","盖","益","桓","公","晋","楚","阎","法","汝","鄢","涂","钦","岳","帅","缑","亢","况","后","有","琴","商","牟","佘","佴","伯","赏","墨","哈","谯","笪","年","爱","阳","佟","万俟","司马","上官","欧阳","夏侯","诸葛","闻人","东方","赫连","皇甫","尉迟","公羊","澹台","公冶","宗政","濮阳","淳于","单于","太叔","申屠","公孙","仲孙","轩辕","令狐","锺离","宇文","长孙","慕容","鲜于","闾丘","司徒","司空","丌官","司寇","仉","督","子车","颛孙","端木","巫马","公西","漆雕","乐正","壤驷","公良","拓拔","夹谷","宰父","谷梁","段干","百里","东郭","南门","呼延","归","海","羊舌","微生","梁丘","左丘","东门","西门","南宫"},
	LAST_NAME = {"一","乙","二","十","丁","厂","七","卜","人","入","八","九","几","儿","了","力","乃","刀","又","三","于","干","亏","士","工","土","才","寸","下","大","丈","与","万","上","小","口","巾","山","千","乞","川","亿","个","勺","久","凡","及","夕","丸","么","广","亡","门","义","之","尸","弓","己","已","子","卫","也","女","飞","刃","习","叉","马","乡","丰","王","井","开","夫","天","无","元","专","云","扎","艺","木","五","支","厅","不","太","犬","区","历","尤","友","匹","车","巨","牙","屯","比","互","切","瓦","止","少","日","中","冈","贝","内","水","见","午","牛","手","毛","气","升","长","仁","什","片","仆","化","仇","币","仍","仅","斤","爪","反","介","父","从","今","凶","分","乏","公","仓","月","氏","勿","欠","风","丹","匀","乌","凤","勾","文","六","方","火","为","斗","忆","订","计","户","认","心","尺","引","丑","巴","孔","队","办","以","允","予","劝","双","书","幻","玉","刊","示","末","未","击","打","巧","正","扑","扒","功","扔","去","甘","世","古","节","本","术","可","丙","左","厉","右","石","布","龙","平","灭","轧","东","卡","北","占","业","旧","帅","归","且","旦","目","叶","甲","申","叮","电","号","田","由","史","只","央","兄","叼","叫","另","叨","叹","四","生","失","禾","丘","付","仗","代","仙","们","仪","白","仔","他","斥","瓜","乎","丛","令","用","甩","印","乐","句","匆","册","犯","外","处","冬","鸟","务","包","饥","主","市","立","闪","兰","半","汁","汇","头","汉","宁","穴","它","讨","写","让","礼","训","必","议","讯","记","永","司","尼","民","出","辽","奶","奴","加","召","皮","边","发","孕","圣","对","台","矛","纠","母","幼","丝","式","刑","动","扛","寺","吉","扣","考","托","老","执","巩","圾","扩","扫","地","扬","场","耳","共","芒","亚","芝","朽","朴","机","权","过","臣","再","协","西","压","厌","在","有","百","存","而","页","匠","夸","夺","灰","达","列","死","成","夹","轨","邪","划","迈","毕","至","此","贞","师","尘","尖","劣","光","当","早","吐","吓","虫","曲","团","同","吊","吃","因","吸","吗","屿","帆","岁","回","岂","刚","则","肉","网","年","朱","先","丢","舌","竹","迁","乔","伟","传","乒","乓","休","伍","伏","优","伐","延","件","任","伤","价","份","华","仰","仿","伙","伪","自","血","向","似","后","行","舟","全","会","杀","合","兆","企","众","爷","伞","创","肌","朵","杂","危","旬","旨","负","各","名","多","争","色","壮","冲","冰","庄","庆","亦","刘","齐","交","次","衣","产","决","充","妄","闭","问","闯","羊","并","关","米","灯","州","汗","污","江","池","汤","忙","兴","宇","守","宅","字","安","讲","军","许","论","农","讽","设","访","寻","那","迅","尽","导","异","孙","阵","阳","收","阶","阴","防","奸","如","妇","好","她","妈","戏","羽","观","欢","买","红","纤","级","约","纪","驰","巡","寿","弄","麦","形","进","戒","吞","远","违","运","扶","抚","坛","技","坏","扰","拒","找","批","扯","址","走","抄","坝","贡","攻","赤","折","抓","扮","抢","孝","均","抛","投","坟","抗","坑","坊","抖","护","壳","志","扭","块","声","把","报","却","劫","芽","花","芹","芬","苍","芳","严","芦","劳","克","苏","杆","杠","杜","材","村","杏","极","李","杨","求","更","束","豆","两","丽","医","辰","励","否","还","歼","来","连","步","坚","旱","盯","呈","时","吴","助","县","里","呆","园","旷","围","呀","吨","足","邮","男","困","吵","串","员","听","吩","吹","呜","吧","吼","别","岗","帐","财","针","钉","告","我","乱","利","秃","秀","私","每","兵","估","体","何","但","伸","作","伯","伶","佣","低","你","住","位","伴","身","皂","佛","近","彻","役","返","余","希","坐","谷","妥","含","邻","岔","肝","肚","肠","龟","免","狂","犹","角","删","条","卵","岛","迎","饭","饮","系","言","冻","状","亩","况","床","库","疗","应","冷","这","序","辛","弃","冶","忘","闲","间","闷","判","灶","灿","弟","汪","沙","汽","沃","泛","沟","没","沈","沉","怀","忧","快","完","宋","宏","牢","究","穷","灾","良","证","启","评","补","初","社","识","诉","诊","词","译","君","灵","即","层","尿","尾","迟","局","改","张","忌","际","陆","阿","陈","阻","附","妙","妖","妨","努","忍","劲","鸡","驱","纯","纱","纳","纲","驳","纵","纷","纸","纹","纺","驴","纽","奉","玩","环","武","青","责","现","表","规","抹","拢","拔","拣","担","坦","押","抽","拐","拖","拍","者","顶","拆","拥","抵","拘","势","抱","垃","拉","拦","拌","幸","招","坡","披","拨","择","抬","其","取","苦","若","茂","苹","苗","英","范","直","茄","茎","茅","林","枝","杯","柜","析","板","松","枪","构","杰","述","枕","丧","或","画","卧","事","刺","枣","雨","卖","矿","码","厕","奔","奇","奋","态","欧","垄","妻","轰","顷","转","斩","轮","软","到","非","叔","肯","齿","些","虎","虏","肾","贤","尚","旺","具","果","味","昆","国","昌","畅","明","易","昂","典","固","忠","咐","呼","鸣","咏","呢","岸","岩","帖","罗","帜","岭","凯","败","贩","购","图","钓","制","知","垂","牧","物","乖","刮","秆","和","季","委","佳","侍","供","使","例","版","侄","侦","侧","凭","侨","佩","货","依","的","迫","质","欣","征","往","爬","彼","径","所","舍","金","命","斧","爸","采","受","乳","贪","念","贫","肤","肺","肢","肿","胀","朋","股","肥","服","胁","周","昏","鱼","兔","狐","忽","狗","备","饰","饱","饲","变","京","享","店","夜","庙","府","底","剂","郊","废","净","盲","放","刻","育","闸","闹","郑","券","卷","单","炒","炊","炕","炎","炉","沫","浅","法","泄","河","沾","泪","油","泊","沿","泡","注","泻","泳","泥","沸","波","泼","泽","治","怖","性","怕","怜","怪","学","宝","宗","定","宜","审","宙","官","空","帘","实","试","郎","诗","肩","房","诚","衬","衫","视","话","诞","询","该","详","建","肃","录","隶","居","届","刷","屈","弦","承","孟","孤","陕","降","限","妹","姑","姐","姓","始","驾","参","艰","线","练","组","细","驶","织","终","驻","驼","绍","经","贯","奏","春","帮","珍","玻","毒","型","挂","封","持","项","垮","挎","城","挠","政","赴","赵","挡","挺","括","拴","拾","挑","指","垫","挣","挤","拼","挖","按","挥","挪","某","甚","革","荐","巷","带","草","茧","茶","荒","茫","荡","荣","故","胡","南","药","标","枯","柄","栋","相","查","柏","柳","柱","柿","栏","树","要","咸","威","歪","研","砖","厘","厚","砌","砍","面","耐","耍","牵","残","殃","轻","鸦","皆","背","战","点","临","览","竖","省","削","尝","是","盼","眨","哄","显","哑","冒","映","星","昨","畏","趴","胃","贵","界","虹","虾","蚁","思","蚂","虽","品","咽","骂","哗","咱","响","哈","咬","咳","哪","炭","峡","罚","贱","贴","骨","钞","钟","钢","钥","钩","卸","缸","拜","看","矩","怎","牲","选","适","秒","香","种","秋","科","重","复","竿","段","便","俩","贷","顺","修","保","促","侮","俭","俗","俘","信","皇","泉","鬼","侵","追","俊","盾","待","律","很","须","叙","剑","逃","食","盆","胆","胜","胞","胖","脉","勉","狭","狮","独","狡","狱","狠","贸","怨","急","饶","蚀","饺","饼","弯","将","奖","哀","亭","亮","度","迹","庭","疮","疯","疫","疤","姿","亲","音","帝","施","闻","阀","阁","差","养","美","姜","叛","送","类","迷","前","首","逆","总","炼","炸","炮","烂","剃","洁","洪","洒","浇","浊","洞","测","洗","活","派","洽","染","济","洋","洲","浑","浓","津","恒","恢","恰","恼","恨","举","觉","宣","室","宫","宪","突","穿","窃","客","冠","语","扁","袄","祖","神","祝","误","诱","说","诵","垦","退","既","屋","昼","费","陡","眉","孩","除","险","院","娃","姥","姨","姻","娇","怒","架","贺","盈","勇","怠","柔","垒","绑","绒","结","绕","骄","绘","给","络","骆","绝","绞","统","耕","耗","艳","泰","珠","班","素","蚕","顽","盏","匪","捞","栽","捕","振","载","赶","起","盐","捎","捏","埋","捉","捆","捐","损","都","哲","逝","捡","换","挽","热","恐","壶","挨","耻","耽","恭","莲","莫","荷","获","晋","恶","真","框","桂","档","桐","株","桥","桃","格","校","核","样","根","索","哥","速","逗","栗","配","翅","辱","唇","夏","础","破","原","套","逐","烈","殊","顾","轿","较","顿","毙","致","柴","桌","虑","监","紧","党","晒","眠","晓","鸭","晃","晌","晕","蚊","哨","哭","恩","唤","啊","唉","罢","峰","圆","贼","贿","钱","钳","钻","铁","铃","铅","缺","氧","特","牺","造","乘","敌","秤","租","积","秧","秩","称","秘","透","笔","笑","笋","债","借","值","倚","倾","倒","倘","俱","倡","候","俯","倍","倦","健","臭","射","躬","息","徒","徐","舰","舱","般","航","途","拿","爹","爱","颂","翁","脆","脂","胸","胳","脏","胶","脑","狸","狼","逢","留","皱","饿","恋","桨","浆","衰","高","席","准","座","脊","症","病","疾","疼","疲","效","离","唐","资","凉","站","剖","竞","部","旁","旅","畜","阅","羞","瓶","拳","粉","料","益","兼","烤","烘","烦","烧","烛","烟","递","涛","浙","涝","酒","涉","消","浩","海","涂","浴","浮","流","润","浪","浸","涨","烫","涌","悟","悄","悔","悦","害","宽","家","宵","宴","宾","窄","容","宰","案","请","朗","诸","读","扇","袜","袖","袍","被","祥","课","谁","调","冤","谅","谈","谊","剥","恳","展","剧","屑","弱","陵","陶","陷","陪","娱","娘","通","能","难","预","桑","绢","绣","验","继","球","理","捧","堵","描","域","掩","捷","排","掉","堆","推","掀","授","教","掏","掠","培","接","控","探","据","掘","职","基","著","勒","黄","萌","萝","菌","菜","萄","菊","萍","菠","营","械","梦","梢","梅","检","梳","梯","桶","救","副","票","戚","爽","聋","袭","盛","雪","辅","辆","虚","雀","堂","常","匙","晨","睁","眯","眼","悬","野","啦","晚","啄","距","跃","略","蛇","累","唱","患","唯","崖","崭","崇","圈","铜","铲","银","甜","梨","犁","移","笨","笼","笛","符","第","敏","做","袋","悠","偿","偶","偷","您","售","停","偏","假","得","衔","盘","船","斜","盒","鸽","悉","欲","彩","领","脚","脖","脸","脱","象","够","猜","猪","猎","猫","猛","馅","馆","凑","减","毫","麻","痒","痕","廊","康","庸","鹿","盗","章","竟","商","族","旋","望","率","着","盖","粘","粗","粒","断","剪","兽","清","添","淋","淹","渠","渐","混","渔","淘","液","淡","深","婆","梁","渗","情","惜","惭","悼","惧","惕","惊","惨","惯","寇","寄","宿","窑","密","谋","谎","祸","谜","逮","敢","屠","弹","随","蛋","隆","隐","婚","婶","颈","绩","绪","续","骑","绳","维","绵","绸","绿","琴","斑","替","款","堪","搭","塔","越","趁","趋","超","提","堤","博","揭","喜","插","揪","搜","煮","援","裁","搁","搂","搅","握","揉","斯","期","欺","联","散","惹","葬","葛","董","葡","敬","葱","落","朝","辜","葵","棒","棋","植","森","椅","椒","棵","棍","棉","棚","棕","惠","惑","逼","厨","厦","硬","确","雁","殖","裂","雄","暂","雅","辈","悲","紫","辉","敞","赏","掌","晴","暑","最","量","喷","晶","喇","遇","喊","景","践","跌","跑","遗","蛙","蛛","蜓","喝","喂","喘","喉","幅","帽","赌","赔","黑","铸","铺","链","销","锁","锄","锅","锈","锋","锐","短","智","毯","鹅","剩","稍","程","稀","税","筐","等","筑","策","筛","筒","答","筋","筝","傲","傅","牌","堡","集","焦","傍","储","奥","街","惩","御","循","艇","舒","番","释","禽","腊","脾","腔","鲁","猾","猴","然","馋","装","蛮","就","痛","童","阔","善","羡","普","粪","尊","道","曾","焰","港","湖","渣","湿","温","渴","滑","湾","渡","游","滋","溉","愤","慌","惰","愧","愉","慨","割","寒","富","窜","窝","窗","遍","裕","裤","裙","谢","谣","谦","属","屡","强","粥","疏","隔","隙","絮","嫂","登","缎","缓","编","骗","缘","瑞","魂","肆","摄","摸","填","搏","塌","鼓","摆","携","搬","摇","搞","塘","摊","蒜","勤","鹊","蓝","墓","幕","蓬","蓄","蒙","蒸","献","禁","楚","想","槐","榆","楼","概","赖","酬","感","碍","碑","碎","碰","碗","碌","雷","零","雾","雹","输","督","龄","鉴","睛","睡","睬","鄙","愚","暖","盟","歇","暗","照","跨","跳","跪","路","跟","遣","蛾","蜂","嗓","置","罪","罩","错","锡","锣","锤","锦","键","锯","矮","辞","稠","愁","筹","签","简","毁","舅","鼠","催","傻","像","躲","微","愈","遥","腰","腥","腹","腾","腿","触","解","酱","痰","廉","新","韵","意","粮","数","煎","塑","慈","煤","煌","满","漠","源","滤","滥","滔","溪","溜","滚","滨","粱","滩","慎","誉","塞","谨","福","群","殿","辟","障","嫌","嫁","叠","缝","缠","静","碧","璃","墙","撇","嘉","摧","截","誓","境","摘","摔","聚","蔽","慕","暮","蔑","模","榴","榜","榨","歌","遭","酷","酿","酸","磁","愿","需","弊","裳","颗","嗽","蜻","蜡","蝇","蜘","赚","锹","锻","舞","稳","算","箩","管","僚","鼻","魄","貌","膜","膊","膀","鲜","疑","馒","裹","敲","豪","膏","遮","腐","瘦","辣","竭","端","旗","精","歉","熄","熔","漆","漂","漫","滴","演","漏","慢","寨","赛","察","蜜","谱","嫩","翠","熊","凳","骡","缩","慧","撕","撒","趣","趟","撑","播","撞","撤","增","聪","鞋","蕉","蔬","横","槽","樱","橡","飘","醋","醉","震","霉","瞒","题","暴","瞎","影","踢","踏","踩","踪","蝶","蝴","嘱","墨","镇","靠","稻","黎","稿","稼","箱","箭","篇","僵","躺","僻","德","艘","膝","膛","熟","摩","颜","毅","糊","遵","潜","潮","懂","额","慰","劈","操","燕","薯","薪","薄","颠","橘","整","融","醒","餐","嘴","蹄","器","赠","默","镜","赞","篮","邀","衡","膨","雕","磨","凝","辨","辩","糖","糕","燃","澡","激","懒","壁","避","缴","戴","擦","鞠","藏","霜","霞","瞧","蹈","螺","穗","繁","辫","赢","糟","糠","燥","臂","翼","骤","鞭","覆","蹦","镰","翻","鹰","警","攀","蹲","颤","瓣","爆","疆","壤","耀","躁","嚼","嚷","籍","魔","灌","蠢","霸","露","囊","罐"},
	ADJECT_LIST = "一样喜欢美丽定原来好开心可能爱明白所有后清新迷人光共同听话知名闷热众多重要经常自然真正害怕空中红色痛苦干净辛精彩乐拥挤天生你老实友始笑长远土黄和合法过时低下不快级用公办功负责幸运难受绝对及附近华焦急良相互惊奇被动坚决厚道年迈稀扎深富牢固情雄壮贫灰暗便利小号信客气沉着顽强愉晰诚出博大完善敏捷必充火孤单尊敬高贵悦饥饿艰异苍规矩渊勤劳细妙水平量安结许久普通厉陌短暂无聊模糊晶莹繁翠绿寂静欣慰悄激烈直接说雪飞观主鲜艳冰冷皮崭义务鼓舞庞恶均匀耳辜恼崇范奢侈浑浊讲究笨拙乏味锐润泽衷冒昧恳拘谨适当灵慎挚辟简朴巧疏朗烦琐贴切凶残柔韧古怪葱郁耻困忠胆劣倦碎洁讶惜慈祥炎凉爽顺著傲随粗糙意集雅致束闲威严极端仁荒苗条神刻薄促足显整坦龌龊靓谦卑儒契隔膜褴褛阴晦宽绰娇娆茫狂妄顿暴虐鄙努力纷偶尔迅速秘肃忧伤脆愤怒犹豫乌黑挺拔感途敦杰堂皇弱振奋枯竭机缓英武髦锃亮熠湍宗颀堪颓丧紊乱赫败恬密衰朽渺逸惋惘混沌狭隘懈怠溟濛浮躁懦婆娑隽永洗练诡忍芜杂备吻喁峻峭落悲恸伶俜络绎仓泠冥哀怨嫡亲板滞微幽谧臊费解怃昳得体谲诈荫破怯个托约性偏执笃馥匮镇险宝蓝流转零洒埋汰仿悻痒洲际效逼仄苟且应届啰唆猥亵谓团忙上装划算具象洋活期没劲非礼贞孪优越圆融隐蛮型聪慧敞乖戾寡卷曲憨详茁少芳菲凡压俏迂回茕辩证恐慌辣宏星滔免在什锦分歧牵格贤外向素线逊为猖獗鸷身亨较米殷刺伪悱恻煊冽硬汲警醒健奥魔怔方学浓青稳骄矜丰盈专制妖媚嘶哑侠片断资拍刚怡鹅废旧娴统历豪手透冬烘酸楚木月黧悬殊潜昂扬索导崛枣临界特惠浅怏恒诸惨旷达罗锅地立家面泫靡娟秀卖教悠眼岿续左肉食紧凑早民事毒溏俗熙攘现役畅橙泛浩荡茂额刁等裕蓊淫电绵油嘴舒涨目眩花差轻反抢淳海产猛服帖持茶褊危虚言慢属耐勃质曈悖谬宾岌偿似认述内行寿入张死略杲口治荦牙碜路缺德梼冶昭篇寒放诞婵媛起溜凄双边淡飘阻屹纤晚熟戆滑痴肥阔鲁或舛穷更阑刷夹碌澄碧养婉玄乎暖村野窄甚看骇把憎些絮视熬风概委愚太缠筋甜疾辽形管亏派瞪姣秽颖豁轰万尖矫馨徐调式洼到翻惶遽超卓龄文松衮彀勇品惭怍滂湃旺僵识停剧迫忽绀觉浇昏疑伟嫩符理萧晓走倒灶坏含岸挠银妥兼任命晃头率阒哲弛前虔涩易吉庆群响晴佼兴错独翛测廉跃迟纯造了赤蠢陋烜济病磊讷丑成守潸谐全盛棉纺钻狠悍昌见瓷散聩迥余彰市复本劬满齐杳陈腐软贱敌书发初步醇香私营吝喟位军裙带翕黢馋靛失背消凛拉耿介缕森愁赖巨莫逆俭确间巴绛紫未几烂涣点纭貌倨律至拳枝蔓温糟酥称变饶遂隆蒙眬喷哈喇怆麻态离环卫蔚仗拗克己露骨允横俊粹标朵颐胜猩连锁凝甲假笼周铁恍种喑狎昵座元寥廓苛瘦各漠支奸佞罕歉疚羞浪瘙别默智佳腻歪刑草鸡巩贪仃颇棘声控彪唐突囫囵嘹顶衡佻扭层写斯胖跋扈世故沱悚程货炙荣幻揪摩登数典基投职业矛盾尴尬则漫累邪十甘工纵四婷度拟围夸类澈盎血腥配摇茏果敢畏敛二橘庸霸盲节协准关疼嘈如燥折展滚匆望寞遗憾耀朦胧屈疯懂窭辅助噱瘠赘泥愕驯赧邃蓬懵黯僻冤枉啬绣脉莞熨缜讪潋滟呆悭袅娜削蕴藉恢弘诧泄狡炽怫游纳凋敝区恣肆剔扫潮惑凌戚粝穹珍茸渴靠湛潇氤氲斑驳妩慷慨广袤铿锵懊缥缈诙漆锋圣瑰虑窘沛码宜趣矮瘾胎杏勉门械震脱巍峨婀铺肯闹踊蔽洽袖恰闭塞沃毅抱郑喧哗嚣沮湿绚皎愧馁霉缤联夺幼乳误稠景烁毛灿冲饱泞滴日福讨厌积保桃吓笔两进影根金",
	NOUN_LIST = "疼痛猜测奔腾丑陋长久模仿固定寒冷恐惧奇怪整齐漂浮特殊虚假遥望微弱消灭珍贵祝愿缠绕颤抖抚摩拉扯柔软明亮宽敞黑暗破旧思玄思恋思凡思致思惟思潮思忆思元思绎思域思永思怀思越思纬思理思莼思服思咏思愆思议思秋思言馨香飘散舒展娇美神韵恬静醉人素雅娇嫩幽香淡雅朴素花瓣花蕊花粉鲜花怒放含苞孤单孤寂寂寞寥寂寂寥落寞孤独寂静僻静宁静寂然清静沉寂沉静安静孤立伶仃孤单零落落莫动静呼吸朝夕取舍善恶兴衰祸福甘苦彼此首尾褒贬吞吐黑白是非多少真假虚实反正生死悲欢前后始末早晚昼夜曲直东西善恶南北开关左右眷恋留恋依恋迷恋自恋爱恋失恋贪恋思恋热恋怀恋初恋顾恋流恋恋恋悲恋相恋婉恋忆恋积恋恳恋情恋凝恋耽恋欣恋绻恋感恋单恋遐恋嫪恋安恋慕恋兴奋快乐喜悦愉快畅快欢畅欢喜欢腾欢快欣喜积恋恳恋情恋凝恋耽恋欣恋绻恋感恋单恋遐恋嫪恋安恋慕恋兴奋快乐喜悦愉快畅快欢畅欢喜欢腾欢快欣喜激奋激昂激情敬佩佩服崇拜钦仰惊恐惊慌惊吓惧怕恐惧胆怯畏缩发慌心慌恐慌激怒恼火怒斥怒吼震怒气愤担忧发愁犯愁忧伤忧愁忧心愁闷悲伤悲痛悲惨悲凉哀伤哀怨忧伤伤感瘦削憔悴俊美恬静慈祥苍白白皙清秀光滑英俊红润挺直扁平小巧笔直端庄秀气秀美巧嘴朱唇苍白红润干裂娇嫩整齐整洁雪白焦黄洁净蓬松鲜嫩粉红通红红嫩滑润滋润干巴干裂干燥蜡黄洁白炭黑瘦弱矮小苗条丰满强壮清秀单薄文弱干瘦轩昂解释文辞短语单词词组词汇字眼范例语素丰富信息组成最小最大造句单位构成方式可以神奇单纯自由单音双音多音可以山高水深天高地厚拥有凑整仿佛苍茫参差上面前方内部中间方位名词:学生老师群众老头妇女同志叔叔婶婶爸爸妈妈哥哥弟弟姐姐妹妹事物蜗牛猎豹棒球足球蓝求战机飞机地球思想中学物理科穴碗盘竹筷上午下午过去将来早晨午夜三更甲戊世纪动词:行为跑步唱歌喝水敲门吆喝踢求生长枯萎发芽结果产卵喜欢气愤觉得思考厌恶存现消失显现丢失幻灭使令使人让座命令禁止勒令能愿如会愿意能够宁可表示状貌特征状态气急怒火高兴形容:高大高瘦矮胖粗细强壮性质香甜漂亮圆滑机智单调快速浓厚肥满许多迅速悄悄量词单位公尺分寸公里公斤一两一辆一角一元一次一趟一下回声一幢一座代词代替我们你们它们她们大家咱们疑问谁的什么怎么哪里何以这里那里那边拟声模拟声音轰隆淅沥噼里啪啦哗啦叽喳啪拉哄堂开怀笑颜笑容喜笑欢声笑语眉开生气火冒雷霆欣喜心情词汇忧愁丧气雾气满腹满腔愁肠百结欲断寸断九转百愁心事阴郁忧心如焚愁绪疼痛猜测奔腾丑陋长久模仿固定寒冷恐惧奇怪整齐漂浮特殊虚假遥望微弱消灭珍贵祝愿缠绕颤抖抚摩拉扯柔软明亮宽敞黑暗破旧思玄思恋思凡思致思惟思潮思忆思元思绎思域思永思怀思越思纬思理思莼思服思咏思愆思议思秋思言馨香飘散舒展娇美神韵恬静醉人素雅娇嫩幽香淡雅朴素花瓣花蕊花粉鲜花怒放含苞孤单孤寂寂寞寥寂寂寥落寞孤独寂静僻静宁静寂然清静沉寂沉静安静孤立伶仃孤单零落落莫动静呼吸朝夕取舍善恶兴衰祸福甘苦彼此首尾褒贬吞吐黑白是非多少真假虚实反正生死悲欢前后始末早晚昼夜曲直东西善恶南北开关左右眷恋留恋依恋迷恋自恋爱恋失恋贪恋思恋热恋怀恋初恋顾恋流恋恋恋悲恋相恋婉恋忆恋积恋恳恋情恋凝恋耽恋欣恋绻恋感恋单恋遐恋嫪恋安恋慕恋兴奋快乐喜悦愉快畅快欢畅欢喜欢腾欢快欣喜积恋恳恋情恋凝恋耽恋欣恋绻恋感恋单恋遐恋嫪恋安恋慕恋兴奋快乐喜悦愉快畅快欢畅欢喜欢腾欢快欣喜激奋激昂激情敬佩佩服崇拜钦仰惊恐悲咽鸣咽哽咽抽咽幽咽抽噎抽泣低首俯首颔首摇头点头伸颈缩颈歪颈歪脖。吃喝吮吸吞吐吞咽呕吐啃咬咀嚼咬嚼吞食吞噬品味品尝尝试品茗啜茗咕嘟张嘴闭嘴撅嘴嘟嘴抿嘴努嘴撇嘴捉住抓住擒获相拥拥抱搂抱搀扶推拉揍捶打撞击撕咬撕扯撕打捧起托住抽打抬起扛起挑起捡拣拈住提起拎起鼓捣掏出掘进开挖撬动撒开撤离支撑掉下投入掷出抛出扔下摔倒擦拭揩抹摊开搔痒哄抢烂漫复苏莺莺和煦艳阳春景春光怀春回春阑珊暖意盎然融融明媚宜人明媚和风骀荡料峭温煦韶光韶晖韶华韶景蛰雷残春余春早春仲春阳春酣春熙春烂漫复苏莺莺和煦艳阳春景春光怀春回春阑珊暖意盎然融融明媚宜人明媚和风骀荡料峭温煦韶光韶晖韶华韶景蛰雷残春余春早春仲春阳春酣春熙春春天春风春季春光春雷春联春节春耕春笋春汛春运春种春雨春雨春装春色春秋青春早春打春善良娇美美丽秀气淡雅邪魅秀气清秀内向高雅漂亮温柔可爱冷魅外向可人靓丽艳丽倾世文雅丑陋冰冷妖冶娇媚靓丽漂亮玲珑艳丽甜美倾世可爱淡雅丑陋秀气平凡文静绝美无邪害羞妖媚清纯倾城优雅内向素雅清纯文雅倾城冷漠娇艳苗条忧郁高贵冷漠娇艳娇美娇媚可人率性冷魅绝美倾国靓丽甜美美丽青春邪魅倾国苗条活泼早熟娇艳苗条甜美率性美女忧郁妩媚素净文静美丽漂亮活泼安静单纯素雅清秀萝莉美女外向九歌苏奠当归川穹苏梗七漓白薇漓云穹光青苍离殇雨辰云崖子归墨漓威风横行枭雄豪杰彪悍彪汉豪爽粗暴欺人炫耀放肆放纵自大独尊天下豪杰蛮横傲慢雷霆澎湃磅礴汹涌蛟龙雄浑荡涤仰天枭雄横刀砥柱流苏鎏金残雪阑珊传说飞仙染尘回想悠然轮回雪绒妖娆红尘寂寥蓦然清冷倾城落花花舞醉阳灏夜迷离流水落花琉璃汐水北葵如烟依恋拂柳尘缘萧桔木辰西垣华琴顾棋初翔玄素指尖锦华子衿听兰听蓉语芙夏彤凌瑶忆翠幻灵怜菡紫南依珊水云访琴谷波笑白妙海紫霜凌旋孤丝夕颜秋恋蝉羽浴兰沉鱼落雁闭月羞花幽然静微艺雅卉馨轩然子茹萦绕流萤静谧流凨羽翼蔓延浅唱轻盈清芳黯淡纯洁涤荡皓月思琪绚烂郁葱寂寞尘世杳鸢池畔慕灼眸序唇眷笺若安冉白瓷挽衾暮昼牵缕蚀妆灼凉音凉肆虐杳然蚀心衬眉妄空嫣然千紫羽衣忆昔亦柔墨画叙白柚念离鸢水蛊倾颜画凉词庸醉陌灵素欢颜靖柔瑾烟玲珑相思何欢落微伶歌伽蛮宁思齐嫣席甄忘怀羽缘童楠锡影凝烟墨歌君墨风畔昌胤闻柳泠崖戏时出野洛城狐若青裁墨池西岭隐水流觞玄裳夭梅半云溪叠元徽灼光奉之漠尘萧然衡虑翊歌崇凛步崖弄影浮生长恨池暝绯辞",
	--当前环境
    runEnvType = frameSdk.getRunEnvType()
}

local jy_ocr = {}
local jy_num = {}
local jy_tab = {}
local jy_file = {}
local jy_str = {}
local jy_sys = {}
local jy_touch = {}
local jy_rand = {}
local jy_time = {}
local jy_net = {}



--[[ ================== 文字识别操作 ==================]]


--[[封装坐标
参数 x : x坐标
参数 y : y坐标
参数 idx : 序号
参数 ap : 锚点 (可选，默认5-居中)
返回值：返回 {x = x, y = y, idx = idx, ap = ap}

性能优化：使用对象池复用Point对象，减少内存分配和GC压力
]]
function Point(x, y, f, idx, ap)
	return PointPool.acquire(x, y, f, idx, ap)
end


--[[懒人自带paddleOcr
说明：
	懒人自带paddleOcr
参数：
	left 截图区域左坐标
	top 截图区域上坐标
	right 截图区域右坐标
	bottom 截图区域下坐标
返回：
	table|nil 识别结果表，失败返回nil
]]
function jy_ocr.performOcrWithSnapshot(left, top, right, bottom,mrgb, mpadding ,ocrEx)
    -- 1. 加载OCR模型（失败处理由 frameSdk 实现）
    frameSdk.PaddleOcr.loadModel(true)
	local jsonResult
	if ocrEx ~= false then
		local padding = mpadding or 10
		local r,g,b

		if IsEmpty(mrgb) then
			error("文字识别失败，无 rgb 参数")
		else
			local color
			if type(mrgb) == "table" then
				color = frameSdk.getPixelColor(mrgb.x,mrgb.y)
			elseif type(mrgb) == "string" then
				color = string.gsub(mrgb,"#", "")
			end
			r,g,b = frameSdk.colorToRGB("0x" .. color)
		end

		-- 2. 获取屏幕截图（失败处理由 frameSdk 实现）
		local bitmap = frameSdk.LuaEngine.snapShot(left, top, right, bottom)
		-- 3. 执行OCR识别（带padding处理） 63 60 41
		jsonResult = frameSdk.PaddleOcr.detectWithPadding(bitmap,padding,r,g,b)

		-- 4. 释放bitmap资源
		frameSdk.LuaEngine.releaseBmp(bitmap)
	else
		--普通识别
		jsonResult = frameSdk.PaddleOcr.detect(left, top, right, bottom)
	end


    if not jsonResult then
        return nil
    end

    return jsonResult
end



function jy_ocr.NCNNOcr(left, top, right, bottom,mcolor,mwhite,mbox)
	local ocrServer = G.config("NccnOcr_ocrSever")
	local ret = nil
	local ocrParam = {left, top, right, bottom}
	local white =mwhite or "" -- 白名单
	local box = mbox or "" --手动识别框
	local color = mcolor or "" --偏色
	--[[
		--识别范围
		ocrParam[1] = x1
		ocrParam[2] = y1
		ocrParam[3] = x2
		ocrParam[4] = y2
		ocrParam[5] = 偏色
		ocrParam[6] = 白名单
		ocrParam[7] = 手动识别框

	]]
	local bitmap = frameSdk.LuaEngine.snapShot(ocrParam[1],ocrParam[2],ocrParam[3],ocrParam[4])

	ret = ocrServer.detectBitmap(bitmap,white,box,color) -- 迁移修正：ocrServer 为 G.config 传递的插件对象，不属于命令面

	--ret = decodeOcrJson(ret,"text")


	return ret
end

function jy_ocr.NJOcr(left, top, right, bottom,mtype)

	local type = mtype or 2
	local snapShotPath = frameSdk.getSdPath() .. "/" .. jy_file.createRandFileName("tmp",".png")
	frameSdk.snapShot(snapShotPath,left, top, right, bottom)
	local res = frameSdk.httpPost("http://127.0.0.1:1666", "path=" .. snapShotPath .. "&type=" .. type)
	frameSdk.delfile(snapShotPath)
	return res
end

function jy_ocr.LBTOcr(x1 , y1 , x2 , y2)
	local bmp = frameSdk.LuaEngine.snapShot(x1 , y1 , x2 , y2)
	local ocrResult = frameSdk.PPv5_OCR.detect(bmp)
	return ocrResult
end
--[[ ================== 数字操作 ==================]]

--[[将负数转为正数
说明：
	如果为正数直接返回
参数：
	num 需要转换的数字
返回：
	转换后的数字
]]
function jy_num.qz(num)
	assert(type(num) == "number", "num.qz(t) 参数必须为数字")
	if num <0 then num = 0 - num end
	return num
end

--[[判断是否为数字
说明：
	如果为正数直接返回
参数：
	num 需要转换的数字
返回：
	是数字返回true 反之返回false
]]
function jy_num.isNumber(str) return tonumber(str) ~= nil end

 --[[保留指定位数小数
说明：
	保留指定位数小数
参数：
	nNum[number]： 需要操作的数字
	n[number]： 需要保留多少位小数 默认0
	bRound[boolean]： 是否四舍五入 默认true
返回：
	处理后的数字
 ]]
function jy_num.toFixed(nNum, n, bRound)
    -- 参数校验增强
	assert(type(nNum) == "number", "num.toFixed(nNum, n, bRound) nNum参数必须为数字")
    bRound = bRound == nil and true or bRound  -- 默认开启四舍五入
    n = (type(n) == "number") and math.max(0,  math.floor(n))  or 0

    -- 零位特殊处理
    if n == 0 then
        return bRound and math.floor(nNum  + 0.5 * (nNum >= 0 and 1 or -1)) or math.floor(nNum)
    end

    -- 计算缩放因子和补偿值
    local factor = 10 ^ n
    local offset = bRound and 0.5 or 0  -- 根据模式决定补偿值
    offset = offset * (nNum >= 0 and 1 or -1)  -- 符号同步

    -- 浮点精度补偿（解决1.005等问题）
    local epsilon = 1e-10
    local scaledValue = nNum * factor + offset + epsilon

    -- 核心处理逻辑
    local processed = bRound and math.floor(scaledValue)  or math.floor(nNum  * factor)
    return processed / factor
end

 --[[带默认值的字符串转数字
说明：
	讲字符串转换为数字，如果转换失败则返回默认值
参数：
	nNum[number/string]： 需要操作的字符串
	fallback[number]： 无法转数字的默认值
返回：
	转换后的数字
 ]]
function jy_num.toNumber(value, fallback)
    -- 布尔值处理：true→1, false→0
    if type(value) == "boolean" then
        return value and 1 or 0
    end

    -- 字符串处理逻辑
    if type(value) == "string" then
        -- 移除前后空格
        local trimmed = value:match("^%s*(.-)%s*$")

        -- 尝试直接转换
        -- 空字符串处理
        if trimmed == "" then
            return fallback or nil
        end
        local num = tonumber(trimmed)
        if num then
            return num
        end

        -- 处理布尔字符串
        local lower_val = trimmed:lower()
        if lower_val == "true" then
            return 1
        elseif lower_val == "false" then
            return 0
        end
    end

	if type(value) == "number" then
		return value
	end
    -- 其他所有类型返回默认值或 0
    return fallback or 0
end

 --[[提取字符串中的所有数字
说明：
	提取字符串中的所有数字，如： "sdf21ga4" 返回==> 214
参数：
	nNum[number/string]： 需要操作的字符串
	fallback[number]： 无法转数字的默认值
返回：
	提取的数字
 ]]
function jy_num.extractNumbers(str)
	local nums = ""
	for num in string.gmatch(str, '[+-]?%d+%.?%d*') do
		nums = nums ..num
	end
	return tonumber(nums)
end



--[[ ================== 表操作 ==================]]


--[[深度复制
说明：
	常规赋值会将两个变量指向同一个内存地址，改变一个表的内容会导致两个表的内容同时变化
	深度复制后则不会改变
参数：
	arr : 要复制的表
返回：
	返回复制的表
]]
function jy_tab.deepCopy(orig)
	assert(type(orig) == "table", "tab.deepCopy(orig) orig参数必须为数组")
    local visited = {} -- 用于跟踪已复制的表，解决循环引用问题

    local function _copy(obj)
        -- 基础类型直接返回
        if type(obj) ~= "table" then
            return obj
        end

        -- 检查是否已处理过该表（解决循环引用）
        if visited[obj] then
            return visited[obj]
        end

        local new_tbl = {}
        visited[obj] = new_tbl  -- 提前存入记录表

        -- 复制元表（递归处理）
        local mt = getmetatable(obj)
        if mt then
            setmetatable(new_tbl, _copy(mt))
        end

        -- 迭代复制内容（处理嵌套表）
        for k, v in pairs(obj) do
            new_tbl[_copy(k)] = _copy(v)  -- 递归复制键和值
        end

        return new_tbl
    end

    return _copy(orig)
end

 --[[打乱table顺序
说明：
	讲table中元素顺序，随机打乱后返回
参数：
	t : 需要打乱顺序的数组
返回：
	打乱顺序后的table
 ]]
function jy_tab.shuffle(t)
	assert(type(t) == "table", "tab.shuffle(t) t参数必须为数组")
    local n = #t
    -- 处理空表或单元素表的特殊情况
    if n <= 1 then
        return {table.unpack(t)}
    end

    -- 深拷贝原表避免修改原始数据
    local shuffled = jy_tab.deepCopy(t)

    -- Fisher-Yates 洗牌算法（优化时间复杂度）
    for i = n, 2, -1 do
        -- 生成更均匀的随机索引
        local j = math.random(1,  i)

        -- 高效元素交换
        shuffled[i], shuffled[j] = shuffled[j], shuffled[i]
    end

    return shuffled
end


--[[表格点集去重
说明：
	去除相近的点（基于欧氏距离），保留特征点
参数：
	pointSet : 点集表格
	threshold : 距离阈值（默认50），单位同坐标单位
                当点间距离≤此值时视为相近点
                支持动态阈值函数：thresholdFunc(point1, point2)
	featureKey : 特征保留键名（默认"name"）
                当点具有此特征时优先保留
返回：
	去重后的点集表格
]]
function jy_tab.tableSizer(pointSet, threshold)
	assert(type(pointSet) == "table", "tab.tableSizer(pointSet, threshold) pointSet参数必须为数组")
	assert(type(threshold) == "number", "tab.tableSizer(pointSet, threshold) threshold参数必须为数字")
    -- 参数验证与默认值处理
    if type(pointSet) ~= "table" then
        return {}
    end
    -- 设置默认阈值
    threshold = tonumber(threshold) or 50
    local thresholdSquared = threshold * threshold  -- 使用平方距离避免sqrt计算

    -- 结果集
    local result = {}

    -- 遍历所有点（使用pairs支持非连续键）
    for _, currentPoint in pairs(pointSet) do
        local isUnique = true

        -- 获取当前点坐标
        local cx = tonumber(currentPoint.x) or tonumber(currentPoint[1]) or 0
        local cy = tonumber(currentPoint.y) or tonumber(currentPoint[2]) or 0

        -- 与结果集中的每个点比较
        for _, existPoint in ipairs(result) do
            local ex = tonumber(existPoint.x) or tonumber(existPoint[1]) or 0
            local ey = tonumber(existPoint.y) or tonumber(existPoint[2]) or 0

            -- 计算平方距离
            local dx = cx - ex
            local dy = cy - ey
            local distanceSquared = dx * dx + dy * dy

            -- 如果距离小于等于阈值，则不是唯一点
            if distanceSquared <= thresholdSquared then
                isUnique = false
                break
            end
        end

        -- 如果是唯一点，添加到结果集
        if isUnique then
            -- 创建新点，保留所有原始属性
            local newPoint = {}
            for k, v in pairs(currentPoint) do
                newPoint[k] = v
            end
            table.insert(result, newPoint)
        end
    end
    return result
end

--[[查询表格内是否存在指定元素
说明：
	在table中查找指定的值或者元素，查找到后返回索引或者key，未找到返回-1
参数：
	arr : 要查找的表
	element : 指定的元素
返回：
	元素的索引或key
]]
function jy_tab.arraySearch(arr, element)
	assert(type(arr) == "table", "tab.arraySearch(arr, element) arr参数必须为数组")
    -- 遍历数组（处理连续数字索引）
    for i = 1, #arr do
        if arr[i] == element then
            return i  -- 找到匹配项，返回下标
        end
    end

    -- 处理非连续索引或特殊值
    for key, value in pairs(arr) do
        -- 只处理整数索引且未被#遍历到的部分
        if type(key) == "number" and math.floor(key)  == key and key > #arr then
            if value == element then
                return key
            end
        end
    end

    return -1  -- 未找到元素
end

--[[取表格内的随机内容
说明：
	取表格内的随机内容
参数：
	arr : 要查找的表
返回：
	返回随机内容
]]
function jy_tab.arrayRand(arr)
	assert(type(arr) == "table", "tab.arrayRand(arr) arr参数必须为数组")
    return arr[jy_rand.randomRange(1,#arr) ]  -- 未找到元素
end



--[[ ================== 文件操作 ==================]]

function jy_sys.havaApp(appid)
    local apps = frameSdk.getInstalledApk()
    for _, app in ipairs(apps) do
        if app == appid then
            return true
        end
    end
    return false

end

-- 文件复制
function jy_sys.copyFile(sourcePath, destPath)
    -- 打开源文件（只读模式）
    local srcFile, err = io.open(sourcePath, "rb")
    if not srcFile then
        return false, "无法打开源文件：" .. err
    end

    -- 打开目标文件（写入模式，不存在则创建，存在则覆盖）
    local destFile, err2 = io.open(destPath, "wb")
    if not destFile then
        srcFile:close()
        return false, "无法打开目标文件：" .. err2
    end

    -- 逐块复制文件内容（避免大文件内存溢出）
    local blockSize = 4096  -- 4KB块大小
    while true do
        local block = srcFile:read(blockSize)
        if not block then break end
        destFile:write(block)
    end

    -- 关闭文件句柄
    srcFile:close()
    destFile:close()

    return true
end

--[[格式化内容大小
说明：
	将文件大小格式化为 xxGB \ xxMB \ xxKB
参数：
	bytes : 字节数
返回：
	返回格式化后的值
]]
function jy_file.formatSize(bytes)
	assert(type(bytes) == "number", "file.formatSize(bytes) bytes参数必须为数字")
    local units = {"B", "KB", "MB", "GB"}
    for i = #units, 1, -1 do
        local unit = units[i]
        local size = bytes / (1024 ^ (i-1))
        if size >= 1 then
            return string.format("%.2f  %s", size, unit)
        end
    end
    return bytes.." B"
end

--[[判断路径是否是文件夹
说明：
	判断路径是否是文件夹
参数：
	path : 文件路径
返回：
	是文件夹返回 true, 路径无效和不是文件夹 返回false
]]
function jy_file.fileIsDir(path)
    local ret = frameSdk.lfs.attributes(path)
    return ret and ret.mode == "directory"
end
--[[判断路径是否是文件
说明：
	判断路径是否是文件
参数：
	path : 文件路径
返回：
	是文件返回 true, 路径无效和不是文件 返回false
]]
function jy_file.fileIsFile(path)
    local ret = frameSdk.lfs.attributes(path)
    return ret and ret.mode == "file"
end

--[[获取文件更新时间
说明：
	获取文件更新时间
参数：
	path : 文件路径
返回：
	返回更新时间戳，文件不存在返回null
]]
function jy_file.getFileUpdateTime(path)
    local ret = frameSdk.lfs.attributes(path)
    return ret and ret.change
end


--[[删除目录中最后修改时间超过指定小时的文件
说明：
	用于删除超过指定小时数的文件，删除设定过期文件
参数：
	dir: 目录路径, expireHours：间超过指定小时
]]
function jy_file.deleteFilesByModifyTime(dir, expireHours)
    expireHours = expireHours or 12
    local expireSec = expireHours * 3600
    local now = os.time()
    local ok, iter, state = pcall(frameSdk.lfs.dir, dir)
    if not ok then
        return
    end
    for file in iter, state do
        local filePaht = dir .."/" ..file
        local ret = frameSdk.lfs.attributes(filePaht)
        if ret and ret.mode == "file" then
            local mtime = ret.change
            if now - mtime > expireSec then
                frameSdk.delfile(filePaht)
            end
        end
    end
end
--[[获取文件总行数
说明：
	获取文件总行数
参数：
	path : 文件路径
返回：
	行数
]]
function jy_file.getTotalFileLines(path)
    local totalLines = 0
    local file = io.open(path, "rb")
    if not file then error("getTotalFileLines(path) 无法打开文件: " .. path) end

    local bufferSize = 8192  -- 8KB分块读取，平衡性能与内存
    local chunk = file:read(bufferSize)
    while chunk do
        local _, count = chunk:gsub("\n", "\n")
        totalLines = totalLines + count
        chunk = file:read(bufferSize)
    end
    -- 处理最后一行无换行符的情况
    file:seek("end", -1)
    local lastChar = file:read(1)
    if lastChar and lastChar ~= "\n" then
        totalLines = totalLines + 1
    end
    file:close()
    return totalLines
end

--[[读取文件指定行内容
说明：
	读取文件指定行内容
参数：
	path : 文件路径
	lineIndex : 要读取的行数
返回：
	指定行内容
]]
function jy_file.getLine(path, lineIndex)
    -- 参数缺失直接抛出错误
    if not path or not lineIndex then
        error("getLine(path, lineIndex, options)  缺少必需参数: path 或 lineIndex")
    end

    -- 文件不存在直接抛出错误
    if not frameSdk.fileExist(path) then
        error("文件不存在: " .. path)
    end

    -- 精准统计总行数
    local totalLines = jy_file.getTotalFileLines(path)
    local targetIndex = lineIndex
    local fromEnd = false

    -- 处理负索引并校验越界
    if lineIndex < 0 then
        fromEnd = true
        targetIndex = totalLines + lineIndex + 1  -- 转换为从1开始的正索引
        if targetIndex < 1 then
            error("负索引超出文件范围，总行数: " .. totalLines)
        end
    else
        -- 正索引直接校验越界
        if targetIndex > totalLines or targetIndex < 1 then
            error("正索引超出文件范围，总行数: " .. totalLines)
        end
    end

    -- 打开文件
    local file = io.open(path, "r")
    if not file then error("无法打开文件: " .. path) end

    -- 读取目标行（兼容原函数的过滤逻辑）
    local currentLine = 1
    local lineContent = ""

    while true do
        local line = file:read("*l")
        if not line then break end  -- 理论上不会执行到这里，因为已通过前置校验

       line = line:match("^%s*(.-)%s*$") or line
        -- 跳过空行
        if line:gsub("%s", "") == "" then
            goto continue
        end

        -- 匹配目标行
        if currentLine == targetIndex then
            lineContent = line
            break
        end
        currentLine = currentLine + 1
        ::continue::
    end

    file:close()
    return lineContent
end


--[[带删除的读取文件
说明：
	扩展的读取文件方法，可在读取文件后删除文件
参数：
	path : 文件路径
	isdel : 是否删除文件 默认不删除
返回：
	文件内容
]]
function jy_file.readFile(path, isdel)
    -- 参数验证与默认值处理
    if type(path) ~= "string" or #path == 0 then
        error("ReadFile: 无效文件路径 -> "..tostring(path))
        return ""
    end

    local content = ""
    local success, result = pcall(function()
		if not frameSdk.fileExist(path) then
			local handle = io.output(path)
			io.write("{}")
			io.close(handle)
		end
        -- 安全打开文件
        local file, err = io.open(path,  "rb")  -- 使用二进制模式避免换行符问题
        if not file then
            error("ReadFile: 文件打开失败 -> "..tostring(err))
        end

        -- 读取内容并确保文件关闭
        content = file:read("*a")
        file:close()

        -- 文件删除处理（如果需要）
        if isdel then
            local tmp_path = os.tmpname()   -- 生成唯一临时文件名
            if os.rename(path,  tmp_path) then
                os.remove(tmp_path)
            else
                jy_sys.mlog("ReadFile: 文件重命名失败: "..path.." -> "..tmp_path)
            end
        end
        return content
    end)

    -- 错误处理与结果返回
    if success then
        return result
    else
        error("ReadFile: "..tostring(result).." | 路径: "..path)
    end
end

--[[读取配置文件
说明：
	配置文件是以json数据格式存的，根据key读取保存的内容
参数：
	key : 键
	default : 未获取到值，返回的默认值
	fileName : 文件全路径 [可不填] 默认 getWorkPath() .. "/JYConf.txt"
返回：
	指定键的内容
]]
function jy_file.fileGet(key , default , fileName)
	local defPath = frameSdk.getWorkPath() .. "/JYConf.txt"
	-- 参数验证与默认值处理
	if type(key) ~= "string" or key == "" then
        error(" fileGet(key , default , fileName)  key参数无效")
	end
	fileName = fileName or defPath

	-- 安全文件读取
	local readStr = jy_file.readFile(fileName , false)

	if not readStr or readStr == "" then
        error(" fileGet(key , default , fileName)  文件为空或读取失败：fileName = " .. fileName)
	end

	-- 带错误处理的JSON解析
	local t , decodeErr = frameSdk.jsonLib.decode(readStr)
	if not t then
        error(" fileGet(key , default , fileName)  JSON解析失败： "  .. (decodeErr or "未知错误"))
	end

	-- 键值检索逻辑
	if t[key] ~= nil then
		return t[key] -- 成功找到键值
	end

	return default or nil -- 返回默认值
end

--[[写入配置文件
说明：
	配置文件是以json数据格式存的，根据key保存内容
参数：
	key : 键
	default : 未获取到值，返回的默认值
	fileName : 文件全路径 [可不填] 默认 getWorkPath() .. "/JYConf.txt"
返回：
	读取成功返回true，失败这返回false和失败原因
]]
function jy_file.filePut(key , value , fileName)
	local defPath = frameSdk.getWorkPath() .. "/JYConf.txt"

	-- 参数校验和默认值处理
	if type(key) ~= "string" or #key == 0 then
		error("jy_file.filePut(key , value , fileName) 参数【key】错误")
	end

	fileName = fileName or jy_conf.workPath .. "/JYConf.txt"

	-- 使用安全模式解析JSON
	local function safeJsonDecode(str)
		if not str or str == "" then return {} end
		local ok , data = pcall(frameSdk.jsonLib.decode , str)
		return ok and data or {}
	end

	-- 原子写入操作
	local function atomicWrite(data)
		-- 生成临时文件名（避免写入中断导致数据损坏）
		local tempFile = fileName .. ".tmp_" .. tostring(math.random(10000 , 99999))

		-- 尝试写入临时文件
		local writeOk , writeErr = jy_file.mWriteFile(tempFile , data , false , true)
		if not writeOk then
			return false , "Write failed: " .. writeErr
		end

		-- 原子替换原文件
		local renameOk , renameErr = os.rename(tempFile , fileName)
		if not renameOk then
			os.remove(tempFile) -- 清理临时文件
			return false , "Replace failed: " .. renameErr
		end

		return true
	end

	-- 主处理逻辑
	local content = ""
	local fileData = {}

	-- 读取现有数据
	local readOk , readErr = pcall(function()
		content = jy_file.readFile(fileName , false) or ""
	end)

	if not readOk then
		print("Read warning: " .. readErr)
	end

	-- 解析JSON数据
	fileData = safeJsonDecode(content)

	-- 更新键值
	fileData[key] = value
	-- 序列化并写入
	local jsonData , encodeErr = frameSdk.jsonLib.encode(fileData)
	if not jsonData then
		return false , "JSON encode failed: " .. (encodeErr or "unknown error")
	end

	local writeOk , writeErr = atomicWrite(jsonData)
	if not writeOk then
		return false , writeErr
	end

	return true
end

--[[遍历目录下所有文件
说明：
	排除lib 和 libs文件夹，如果传入pathTable，返回结果则会添加这个table中
参数：
	rootPath : 目录路径
	pathTable[table] : 添加到结果集【可不填】
返回：
	文件路径的table
]]
function jy_file.getDirectoryPaths(rootPath, pathTable)
    local pathTable = pathTable or {}

    -- 验证目录存在性
    if not frameSdk.lfs.attributes(rootPath,  "mode") then
        return pathTable  -- 目录不存在时直接返回
    end

    -- 预定义忽略项（使用Set提升查询效率）
    local IGNORE_SET = {
        ['.'] = true,
        ['..'] = true,
        ['lib'] = true,
        ['libs'] = true
    }

    for entry in frameSdk.lfs.dir(rootPath)  do
        if not IGNORE_SET[entry] then
            local fullPath = rootPath .. '/' .. entry

            -- 直接使用lfs.attributes 避免二次函数调用
            local attributes = frameSdk.lfs.attributes(fullPath)
            if attributes and attributes.mode  == "directory" then
                table.insert(pathTable,  fullPath)
            end
        end
    end

    return pathTable
end

--[[递归遍历目录
说明：
	查找出这个路径下所有的文件和子文件夹中的文件
参数：
	path : 遍历路径
返回：
	返回包含完整文件路径的数组
]]
function jy_file.scanPathEx(path)
    -- 确保路径以斜杠结尾
    if string.sub(path,  -1) ~= "/" then
        path = path .. "/"
    end

    local fileList = {}

    -- 使用io.popen 直接读取命令输出，避免临时文件
    local cmd = "find '" .. path .. "' -type f 2>/dev/null"  -- 只获取文件（排除目录）
    local pipe = io.popen(cmd)
    if pipe then
        for filePath in pipe:lines() do
            -- 直接获取完整路径，无需额外拼接
            table.insert(fileList,  filePath)
        end
        pipe:close()
    else
        error("无法查找到文件")
    end

    return fileList
end

--[[写入用户配置文件
说明：
	将键值对写入用户配置文件
参数：
	key      : 配置项键名（字符串类型）
	value    : 配置项值（任意可序列化类型）
	userName : 用户名标识（字符串类型），用于区分不同用户的配置
	filePath : [可选]配置文件路径（字符串类型）， 默认 getWorkPath() .. "/JYConf.txt"
返回：
	操作是否成功（布尔值）
]]
function jy_file.filePutUser(key , value , userName , filePath)
	--读取用户配置
	local userConfJson = jy_file.fileGet(userName,{}, filePath)
	--赋值
	userConfJson[key] = value

	--保存
	jy_file.filePut(userName, userConfJson, filePath)

	return true
end

--[[读取用户配置文件
说明：
	将键值对写入用户配置文件
参数：
	key      : 配置项键名（字符串类型）
	value    : 配置项值（任意可序列化类型）
	userName : 用户名标识（字符串类型），用于区分不同用户的配置
	filePath : [可选]配置文件路径（字符串类型）， 默认 getWorkPath() .. "/JYConf.txt"
返回：
	操作是否成功（布尔值）
]]
function jy_file.fileGetUser(key , default , userName , filePath)
	--读取用户配置
	local userConfJson = jy_file.fileGet(userName,"{}", filePath)
	local ret = userConfJson[key]
	if IsEmpty(ret) then
		return default
	end

	return ret
end

--[[安全删除文件
说明：
	将键值对写入用户配置文件
参数：
	filePath : 文件路径
返回：
	操作是否成功（布尔值）
]]
function jy_file.delFile(filePath)
    local tmpPath = filePath..".tmpdelete."..os.time()
    local renameOk, renameErr = os.rename(filePath,  tmpPath)
    if not renameOk then
        return false, "删除文件重命名失败: "..tostring(renameErr)
    end

    local removeOk, removeErr = os.remove(tmpPath)
    if not removeOk then
        return false, "删除失败: "..tostring(removeErr)
    end

    return true
end

--[[安全删除文件
说明：
	将键值对写入用户配置文件
参数：
	filePath : 文件路径
返回：
	操作是否成功（布尔值）
]]
function jy_file.deleteFile(fileName, excludeList)
    -- 参数合法性校验（增强稳定性）
    if type(fileName) ~= "string" or fileName:len() == 0 then
        error("deleteFile: 无效文件名：" .. fileName)
    end

    -- 文件存在性检查（使用局部变量加速访问）
    if not frameSdk.fileExist(fileName) then
        return false
    end

    -- 排除列表智能处理（修复逻辑缺陷）
    local shouldSkipDeletion = false
    if excludeList and type(excludeList) == "table" then
        for _, excludedFile in ipairs(excludeList) do
            if excludedFile == fileName then
                shouldSkipDeletion = true
				print("deleteFile: " .. string.format(" 文件%s在保护列表中，跳过删除", fileName))
                break
            end
        end
    end

    -- 直接跳过删除操作
    if shouldSkipDeletion then
        return true  -- 非错误状态，表示符合预期行为
    end
    jy_file.delFile(fileName)
    return true
end

--[[获取文件名
参数1： filePath 文件路径
返回值：文件名
]]
function jy_file.getFileName(filename)

    local fn_flag = string.find(filename, "\\")
    local dest_filename = ""
    if fn_flag then

    	dest_filename = string.match(filename, ".+\\([^\\]*%.%w+)$")
    end



    fn_flag = string.find(filename, "/")

    if fn_flag then

    	dest_filename = string.match(filename, ".+/([^/]*%.%w+)$")
    end
    return dest_filename
end

--[[生成随机临时文件
返回值：临时文件全路径
]]
function jy_file.createTempFile()
    local tmpDir = frameSdk.getWorkPath().."/temp/"  -- 应用私有目录

    -- 自动创建temp目录
    if not frameSdk.fileExist(tmpDir) then
        frameSdk.lfs.mkdir(tmpDir)
    end

    -- 生成唯一文件名
    local uuid = string.gsub(jy_str.UUID(),  "-", "")
    local filename = "tmp_"..uuid..(".tmp")
    local fullPath = tmpDir..filename

    -- 写入文件
    jy_file.writeFile(fullPath,  "")

    return fullPath
end

 --[[生成随机名称文件
	prefix: 文件名前缀
	suffix: 文件名后缀
	length: 文件名长度

	返回文件全路径
 ]]
function jy_file.createRandFileName(prefix, suffix, length)
	prefix = prefix  or "tmp_"
	suffix = suffix  or ".txt"
	length = length  or 1
	local nameParts = {prefix}

	-- 生成随机部分
	local randPart = {}
	for i = 1, length  do
		local idx = math.random(1,  #jy_conf.charSet)
		table.insert(randPart,  jy_conf.charSet:sub( idx))
	end

	table.insert(nameParts,  table.concat(randPart))
	table.insert(nameParts,  suffix)
	return table.concat(nameParts)
end

--[[写入文件指定行
参数:
  path: 文件路径
  var:  要写入的内容
  line: 目标行号 (从1开始计数，-1表示追加到末尾)
功能:
  在指定行写入内容，不足行用"-"填充，保留其他行原有内容
]]
local function writeFileLine(path, var, line)

    -- 参数校验和标准化
    local targetLine = math.max(1,  math.floor(tonumber(line)  or 1))
    if line == -1 then  -- 特殊值处理：追加到末尾
        targetLine = -1
    end
    local success, result = pcall(function()
        -- 读取文件内容
        local lines = jy_file.ReadFileLines(path) or {}
        local totalLines = #lines

        -- 处理追加到末尾的情况
        if targetLine == -1 then
            targetLine = totalLines + 1
        end

        -- 计算目标行范围
        local maxLine = math.max(totalLines,  targetLine)
        local newContent = {}

        -- 构建新内容表
        for i = 1, maxLine do
            if i == targetLine then
                newContent[i] = var  -- 目标行写入新内容
            elseif i <= totalLines then
                newContent[i] = lines[i]  -- 保留现有行内容
            else
                newContent[i] = "-"  -- 填充不足行
            end
        end
        -- 写入文件
        jy_file.writeFileLines(path, newContent)
        return true
    end)

    -- 错误处理
    if not success then
        error("writeFileLine: 文件操作失败 -> " .. tostring(result))
    end
    return true
end

 --[[读取文件内容 根据行转为table
	trim: 去除空白 默认：true
	skipEmpty: 是否跳过空行 默认：false
	encoding: 文件编码  默认：utf8
	maxLines: 最大读取行数  默认：-1
	commentPrefix: 注释前缀

	-- 读取整个文件
	local lines = ReadFileLines("/sdcard/data.txt")
	if lines then
		print("总行数:", #lines)
	end

	-- 读取配置文件（跳过空行和注释）
	local config = ReadFileLines("config.cfg",  {
		skipEmpty = true,
		commentPrefix = "#"  -- 忽略以#开头的行
	})

	-- 读取日志文件（只取前100行）
	local logs = ReadFileLines("system.log",  {
		maxLines = 100
	})

 ]]
function jy_file.readFileLines(path, options)
    -- 默认选项配置
    local opts = {
        trim = options and options.trim  or true,   -- 默认自动去除空白
        skipEmpty = options and options.skipEmpty  or false,  -- 是否跳过空行
        encoding = options and options.encoding  or "utf8",   -- 文件编码
        maxLines = options and options.maxLines  or -1       -- 最大读取行数
    }

    -- 错误处理包装
    local function safeRead()
        -- 检查文件是否存在
        if not frameSdk.fileExist(path) then
			jy_sys.mlog("文件不存在: " .. path, "读取文件", "读取文件")
            return nil
        end

        -- 获取文件大小（大文件警告）
        local size = frameSdk.fileSize(path)
        if size > 10 * 1024 * 1024 then  -- 超过10MB
            jy_sys.mlog("警告: 读取大文件 ("..jy_file.formatSize(size)..")，可能需要较长时间", "读取文件", "读取文件")
        end

        local lines = {}
        local count = 0

        -- 使用二进制模式读取避免编码问题
        local file, err = io.open(path,  "rb")
        if not file then
			jy_sys.mlog("文件不存在: " .. err, "读取文件", "读取文件")
			return nil
		end

        -- 设置编码（仅Lua 5.3+）
        if _VERSION >= "Lua 5.3" then
            file:setvbuf("line", 4096)  -- 缓冲区优化
        end

        -- 逐行读取
        for line in file:lines() do
            local ln = line
            -- 编码转换
            if opts.encoding  == "gbk" then
                ln = iconv.decode(ln,  "gbk", "utf8")  -- frameSdk-firefly 提供 iconv 兼容
            end

            -- 行处理
            if opts.trim  then
                ln = ln:match("^%s*(.-)%s*$")  -- 高效trim
            end

            -- 处理空行
            if ln == "" and opts.skipEmpty  then
                goto continue
            end

            -- 添加到结果集
            table.insert(lines,  ln)
            count = count + 1

            -- 行数限制
            if opts.maxLines  > 0 and count >= opts.maxLines  then
                break
            end

            ::continue::
        end

        file:close()
        return lines
    end

    -- 执行并处理结果
    local success, result = xpcall(safeRead, debug.traceback)

    if success then
        return result  -- 正常返回内容
    else
        -- 详细错误日志
        jy_sys.mlog("文件读取失败: "..path.."\n错误信息: "..tostring(result), "读取文件", "读取文件")
        return nil
    end
end

 --[[读取指定行的内容
	trim: 去除空白 默认：true
	skipEmpty: 是否跳过空行 默认：false
	encoding: 文件编码  默认：utf8
	maxLines: 最大读取行数  默认：-1

-- 读取文件第5行
local line5 = ReadFileLine("data.txt",  5)

-- 读取文件倒数第3行
local lineFromEnd = ReadFileLine("log.txt",  -3)

-- 跳过空行和注释读取
local configLine = ReadFileLine("settings.cfg",  10, {
    skipEmpty = true,
    commentPrefix = "//",
    trim = true
})

-- 处理GBK编码文件
local gbkLine = ReadFileLine("gbk_file.txt",  5, {
    encoding = "gbk"
})

 ]]
function jy_file.readFileLine(path, lineIndex, options)
    -- 参数验证
    if not path or not lineIndex then
        error("缺少必需参数: path 或 lineIndex")
    end

    -- 处理负数索引（从文件末尾开始）
    local fromEnd = false
    if lineIndex < 0 then
        fromEnd = true
        lineIndex = math.abs(lineIndex)
    end

    -- 错误处理封装
    local success, result = pcall(function()
        -- 高效的单行读取实现
        if not frameSdk.fileExist(path) then
            return nil, "文件不存在: " .. path
        end

        -- 优化小文件读取（使用之前的ReadFileLines函数）
        if frameSdk.fileSize(path) < 102400 then  -- 文件小于100KB
            local lines = jy_file.readFileLines(path, options)
            if fromEnd and not IsEmpty(lines) then
                return lines[#lines - lineIndex + 1] or ""
            end
            if IsEmpty(lines) then
                return ""
            end
            return lines[lineIndex] or ""
        end

        -- 大文件处理（流式读取）
        local currentLine = 1
        local targetFound = false
        local lineContent = ""

        local file = io.open(path,  "r")
        if not file then error("无法打开文件:" .. path) end

        -- 设置编码（如果提供选项）
        if options and options.encoding  == "gbk" then
            -- 使用懒人精灵的编码转换库
            local iconv = require("iconv")
            local decoder = iconv.new("utf8",  "gbk")
            file = decoder:wrap(file)
        end

        -- 逐行读取直到目标行
        while true do
            lineContent = file:read("*l")  -- 读取一行
            if not lineContent then break end  -- 文件结束

            -- 行处理选项
            if options then
                -- 跳过空行
                if options.skipEmpty  and lineContent:gsub("%s", "") == "" then
                    goto continue
                end

                -- 跳过注释
                if options.commentPrefix  and
                   lineContent:find("^%s*" .. options.commentPrefix)  then
                    goto continue
                end

                -- 行修剪
                if options.trim  ~= false then  -- 默认启用
                    lineContent = lineContent:match("^%s*(.-)%s*$")
                end
            end

            -- 检查是否为目标行
            if (not fromEnd and currentLine == lineIndex) or
               (fromEnd and jy_file.fileRemainingLines(file) == lineIndex) then
                targetFound = true
                break
            end

            currentLine = currentLine + 1
            ::continue::
        end

        file:close()
        return targetFound and lineContent or nil
    end)

    if success then
        return result or ""  -- 返回找到的内容或空字符串
    else
        error("ReadFileLine失败: " .. tostring(result))
        return ""
    end
end

 --[[数组写入文件
	path: 路径
	tbl: 数组
]]
function jy_file.writeFileLines(path, tbl)
    local success, err = pcall(function()
        -- 检查参数合法性
        if type(tbl) ~= "table" then
            error("错误：传入的第二个参数必须是数组（table）")
            return false
        end

        -- 打开文件
        local f = io.open(path,  "w")
        if not f then
            error("错误：无法打开文件路径：" .. path)
            return false
        end

        -- 逐行写入
        for i = 1, #tbl do
            local line = tostring(jy_str.trim(tbl[i])) -- 确保写入字符串
            f:write(line .. "\r\n") -- 统一换行，避免判断
        end

        io.close(f)
        return true
    end)

    if not success then
        error("写入文件过程中出现异常：" .. tostring(err))
        return false
    end

    return true
end

 --[[根据条件删除文件的指定行
url：文件目录
parma1：保留条件
parma2：表达式 ： == , > , < , ~=
text2：行分割符（可选）
tNum : 分割后table 下标（可选）
]]
function jy_file.fileLine2delete(url,  parma1, parma2, text2, tNum)
--[[判断路径类型 (文件/文件夹)
 返回值: 0 = 文件, 1 = 文件夹, nil = 路径无效
]]
    -- 参数验证与初始化
    if type(url) ~= "string" then return false, "无效文件路径" end
    text2 = text2 or false
    tNum = tNum or 1

    -- 文件读取与错误处理
    local file, err = jy_file.ReadFileLines(url)
    if not file or err then return false, "文件读取失败: " .. (err or "未知错误") end

    -- 创建比较函数映射表（避免重复条件判断）
    local comparators = {
        ["=="] = function(val) return val == parma1 end,
        [">"] = function(val)
            local num = tonumber(val)
            return num and num > tonumber(parma1)
        end,
        ["<"] = function(val)
            local num = tonumber(val)
            return num and num < tonumber(parma1)
        end,
        ["~="] = function(val) return val ~= parma1 end
    }

    -- 获取比较函数（支持默认操作符）
    local compare = comparators[parma2] or function() return false end

    -- 构建保留内容缓冲区
    local buffer = {}
    for _, line in ipairs(file) do
        local match = false
        local targetValue = line

        -- 分割模式处理
        if text2 then
            local parts = {line:split(text2)}
            targetValue = parts[tNum] or ""
        end

        -- 执行比较判断
        if compare(targetValue) then
            table.insert(buffer,  line)
        end
    end

    -- 写入处理结果
    local success, writeErr = jy_file.mWriteFile(url, table.concat(buffer,  "\r\n"), false, true)
    return success or false, writeErr
end

 --[[判断文件是文件夹还是文件
path：文件路径
返回值： 0、文件；1、文件夹
]]
function jy_file.getPathType(path)
    local attrs = frameSdk.lfs.attributes(path)
    if not attrs then return nil end  -- 路径不存在

    if attrs.mode  == "file" then
        return 0    -- 普通文件
    elseif attrs.mode  == "directory" then
        return 1  -- 文件夹
    end

    return nil  -- 其他类型（符号链接/设备文件等）
end

--[[ 安全写入文件（自动创建目录和文件）
-- @param fileName  string  目标文件名（可包含相对路径）
-- @param content   string|table 写入内容（字符串或表）
-- @param basePath  string  可选基础路径（默认为当前目录）
-- @param writeMode boolean  可选写入模式（true：清空覆盖内容，false：追加内容）
-- @return boolean  操作是否成功
-- @return string|nil 成功返回完整路径，失败返回nil
]]
function jy_file.mWriteFile(fileName, content, basePath, writeMode)
    -- ===== 1. 参数校验 =====
    if type(fileName) ~= "string" or fileName == "" then
	    error("mWriteFile：发现无效文件名")
        return false
    end

    if content == nil then
	    error("mWriteFile：内容不能为空，文件名：" ..fileName)
        return false
    end

    -- ===== 2. 路径规范化 =====
    local sep = "/"  -- Android环境固定使用正斜杠
    writeMode = Iif(writeMode,"w+","r+") or "a"  -- 默认追加模式

    -- 构建完整路径（兼容basePath结尾分隔符）
    local fullPath = fileName
    if basePath and type(basePath) == "string" then
        fullPath = basePath .. (basePath:sub(-1) == sep and "" or sep) .. fileName
    end

    -- ===== 3. 自动创建目录 =====
    local dirPath = fullPath:match("(.*"..sep..")")
    if dirPath then
        -- Android环境使用懒人精灵目录创建接口
        local pathParts = {}
        for part in dirPath:gmatch("[^"..sep.."]+") do
            table.insert(pathParts,  part)
            local currentPath = table.concat(pathParts,  sep) .. sep

            if not frameSdk.fileExist(currentPath)  then
                local ok = frameSdk.mkdir(currentPath)
                    if not ok then
                    error("mWriteFile：路径创建失败: "..currentPath)
                    return false
                end
            end
        end
    end
	if not frameSdk.fileExist(fullPath) then
		frameSdk.writeFile(fullPath,"",true)
	end
    -- ===== 4. 安全写入文件 =====
    local fileHandle, openErr = io.open(fullPath,  writeMode)
    if not fileHandle then
        error("mWriteFile：文件打开失败: "..openErr)
        return false
    end

    -- 内容处理（支持table转字符串）
    local writeContent = type(content) == "table" and table.concat(content,  "\n") or content
    local writeSuccess = fileHandle:write(writeContent)
    if not writeSuccess then
        fileHandle:close()
        error("mWriteFile：写入失败: "..fullPath)
        return false
    end

    -- ===== 5. 资源清理 =====
    local closeSuccess = fileHandle:close()
    if not closeSuccess then
        error("mWriteFile：关闭文件失败: "..fullPath)
        return false
    end

    return true, fullPath  -- 成功返回完整路径
end

function jy_file.cleanGameFile(appid , cleanConf)
	-- 检查是否在白名单中
	if cleanConf.notDelAppid and type(cleanConf.notDelAppid) == "table" then
		for _ , v in ipairs(cleanConf.notDelAppid) do
			if appid == v then
				return false -- 在白名单中，跳过清理
			end
		end
	end

	local function safeDelete(path)
		if frameSdk.fileExist(path) then
			if jy_file.getPathType(path) == 1 then
				jy_file.deleteDirectoryContents(path) -- 递归删除目录
			else
				os.remove(path) -- 删除文件
			end
			return true
		end
		return false
	end

	-- 清理SD卡文件
	if cleanConf.sdFiles and type(cleanConf.sdFiles) == "table" and #cleanConf.sdFiles > 0 then
		for _ , sdFile in ipairs(cleanConf.sdFiles) do
			local fullPath = frameSdk.getSdPath() .. "/" .. sdFile
			safeDelete(fullPath)
		end
	end

	-- 清理Android数据文件
	if cleanConf.AndroidFiles and type(cleanConf.AndroidFiles) == "table" and #cleanConf.AndroidFiles > 0 then
		for _ , androidFile in ipairs(cleanConf.AndroidFiles) do
			local fullPath = frameSdk.getSdPath() .. "/Android/data/" .. appid .. androidFile
			safeDelete(fullPath)
		end
	end

	-- 清理应用数据目录
	if cleanConf.directorys and type(cleanConf.directorys) == "table" and #cleanConf.directorys > 0 then
		for _ , directory in ipairs(cleanConf.directorys) do
			local basePath = "/data/data/" .. appid
			local paths = jy_file.getDirectoryPaths(basePath , directory) or {}

			if type(paths) == "table" then
				for _ , path in ipairs(paths) do
					safeDelete(path)
				end
			end
		end
	end

	return true -- 清理成功
end

--递归删除目录中的文件，不删除文件夹
function jy_file.deleteDirectoryContents(dir_path)
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


--[[ ================== 字符串操作 ==================]]


--[[去除字符串中的空格、换行、缩进
参数 str : 需要去除的字符串
返回值：去除后的字符串
]]
local function trim(str)
    -- 参数校验：确保输入是有效字符串
    if type(str) ~= "string" then
        return str
    end

    -- 一步式空白字符移除：使用模式匹配替代多次gsub
    local cleaned = str:gsub("[\r\n\t ]+", "")

    -- 精确去除首尾空白（使用模式锚点）
    cleaned = cleaned:match("^%s*(.-)%s*$") or cleaned

    return cleaned
end
jy_str.trim = trim

--[[判断字符串中是否存在指定字符串
特别说明，可判断是否存在多个字符串
参数 param1 : 要搜索的字符串
参数 param2 : 被搜索的字符串
返回值：true 存在，false 不存在
]]
function jy_str.inStrEx(param1, param2)
    local code = 0
    if string.find(param2, ",") then
        local tList = {}
        for v in string.gmatch(param2, "[^,]+") do
            table.insert(tList, v)
        end
        for _, v in ipairs(tList) do
            code = string.find(param1, v)
            if code then
                break
            end
        end
    else
        code = string.find(param1, param2)
        if not code then
            code = string.find(param2, param1)
        end
    end

    return code ~= nil
end

--[[生成随机32位字符串]]
function jy_str.UUID()
    local random_bytes = {}
    for i = 1, 16 do
        table.insert(random_bytes,  math.random(0,  255))
    end

    -- 设置版本位 (0100) 和变体位 (10xx)
    random_bytes[7] = (random_bytes[7] & 0x0F) | 0x40
    random_bytes[9] = (random_bytes[9] & 0x3F) | 0x80

    -- 格式化为标准 UUID
    return string.format(
        "%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x",
        random_bytes[1], random_bytes[2], random_bytes[3], random_bytes[4],
        random_bytes[5], random_bytes[6], random_bytes[7], random_bytes[8],
        random_bytes[9], random_bytes[10], random_bytes[11], random_bytes[12],
        random_bytes[13], random_bytes[14], random_bytes[15], random_bytes[16]
    )
end

--[[字符串分割（D7 修复：ipCheck 引用 jy_str.split 但原版未定义；分隔符按纯文本处理）]]
function jy_str.split(s, sep)
    return frameSdk.splitStr(s, sep)
end


--[[ ================== 系统方法 ==================]]


--[[安全的执行 execute命令
参数 cmd : 命令字符串
返回值：执行结果
]]
function jy_sys.execute(cmd)
    -- 生成唯一临时文件名（避免并发冲突）
    local timestamp = os.time()
    local rnd = math.random(10000,  99999)
    local temp_path = ("/sdcard/ps_temp_%d_%d.tmp"):format(timestamp,  rnd)

    -- 安全执行命令（错误捕获）
    local exec_ok, exec_err = pcall(function()
        os.execute(cmd  .. " > " .. temp_path .. " 2>&1")
    end)

    -- 命令执行异常处理
    if not exec_ok then
        pcall(os.remove,  temp_path)  -- 尝试清理残留文件
        return "【命令执行失败】" .. (exec_err or "未知错误")
    end

    -- 安全读取文件（三重防护机制）
    local result = ""
    local file, open_err = io.open(temp_path,  "r")
    if file then
        result = file:read("*a") or ""  -- 防止返回nil
        file:close()
    else
        result = "【文件读取错误】" .. (open_err or "无法打开临时文件")
    end

    -- 强制清理临时文件（即使读取失败）
    pcall(os.remove,  temp_path)
    return result
end

local function log_clean_message(full_log)
    -- 使用正则表达式匹配并移除前缀部分
    -- 正则解释：匹配以 [时间] [文件路径] 开头的部分，并替换为空
    local clean_log = string.gsub(full_log, "^%[.-%] %[.-%] ", "")

    -- 输出清理后的日志
    print(clean_log)
end

--[[格式化打印输出
参数 str : 需要打印的消息字符串
参数 fun : 消息所属模块/任务
参数 view : 消息所属特征
]]
function jy_sys.mlog(str, fun, view)
    local printOutput = G.config("是否打印输出")
    if printOutput then
		local mfun = fun and "【" .. fun .. "】 > " or ""
		local mview = view and "【" .. view .. "】" or  ""
		if mfun == nil and mview == nil then
			mfun = "【简游调试】 > "
		end
        log_clean_message (mfun .. mview .. str)
    end
end

--[[格式化特征信息输出
参数 str : 需要打印的消息字符串
参数 v2 : 特征
]]
function jy_sys.acLog(str , v2)
	local ff = ""
	if not IsEmpty(v2.ff) and type(v2.ff) == "string" then
		ff =  "【" .. v2.ff  .. "】"
	end
	local view1 = ""
	if not IsEmpty(v2[1]) and type(v2[1]) == "string" then
		view1 = v2[1]
	end

    local taskName = Iif(IsEmpty(v2["taskName"]) , v2["name"] ,v2["taskName"])
    local taskChain = G.config("当前任务链")
    if taskChain ~= nil then
        taskName = taskChain
    end
	jy_sys.mlog( ff .. str , taskName , view1)
end
--[[屏幕静止检测方法
 通过分析屏幕上多个采样点的颜色变化判断屏幕是否处于静止状态
 @param sensitivity number 检测灵敏度参数（0.01~1.0）
     值越小检测越宽松（适合动态场景），值越大检测越严格（适合静态界面）
     推荐值范围：
       0.1-0.3：特效较多的动态场景（如游戏战斗）
       0.4-0.6：通用场景（默认推荐值）
       0.7-1.0：静态界面/文字识别场景
     内部自动映射：
       精度 = 0.05 + (sensitivity * 0.95)  [0.05~1.0]
       阈值 = 0.5 + (sensitivity * 0.45)   [0.5~0.95]
 @param region table|nil 检测区域坐标（可选）
     格式：{x1, y1, x2, y2}
     默认值：nil（全屏检测）
     示例：{100, 200, 500, 800} 检测左上角(100,200)到右下角(500,800)区域
 @param duration number 最大检测时长（毫秒）
     默认值：1000ms（1秒）
     推荐范围：500~10000ms（0.5~10秒）
     达到此时间后自动结束检测
 @param interval number 检测间隔时间（毫秒）
     默认值：200ms
     推荐范围：50~1000ms
     值越小检测越频繁，CPU占用越高
 @param maxPoints number 最大采样点数
     默认值：100点
     实际采样数 = max(10, floor(maxPoints * precision))
     性能参考（1080P屏幕）：
        100点 ≈ 15ms/次
        500点 ≈ 60ms/次
        1000点 ≈ 120ms/次
 @return boolean isStopped true：有变化，false：无变化
 @return number similarity 实际相似度值（0.0~1.0）
 ]]
function jy_sys.isScreenStable(region, duration, sensitivity, interval, maxPoints)
    -- 参数验证与默认值
    sensitivity = tonumber(sensitivity) or 0.9
    sensitivity = math.max(0.01,  math.min(1.0,  sensitivity))

    region = region or {0, 0, frameSdk.getDisplaySize()}
    duration = duration or 1000
    interval = interval or 200
    maxPoints = maxPoints or 100

    -- 确保最小检测时间为2个间隔
    duration = math.max(duration,  interval * 2)

    -- 计算衍生参数
    local precision = 0.05 + (sensitivity * 0.95)
    local stabilityThreshold = 0.5 + (sensitivity * 0.45)
    local changeThreshold = stabilityThreshold * 0.8  -- 变化检测阈值

    -- 准备检测环境
    local w, h = region[3] - region[1], region[4] - region[2]
    local sampleCount = math.max(10,  math.floor(maxPoints  * precision))

    -- 生成采样点
    local points = {}
    for i = 1, sampleCount do
        points[i] = {
            x = region[1] + math.random(0,  w - 1),
            y = region[2] + math.random(0,  h - 1)
        }
    end

    -- 获取基准帧
    local baseFrame = {}
    frameSdk.keepCapture()
    for i, point in ipairs(points) do
        baseFrame[i] = frameSdk.getPixelColor(point.x, point.y)
    end
    frameSdk.releaseCapture()

    -- 初始化检测状态
    local lastColors = baseFrame
    local startTime = frameSdk.systemTime()
    local lastSimilarity = 1.0  -- 初始相似度设为100%

    -- 主检测循环
    while frameSdk.systemTime()  - startTime < duration do

        local currentColors = {}
        local sameCount = 0

        -- 获取当前帧颜色
        frameSdk.keepCapture()
        for i, point in ipairs(points) do
            currentColors[i] = frameSdk.getPixelColor(point.x, point.y)
        end
        frameSdk.releaseCapture()

        -- 与基准帧比较
        for i = 1, #points do
            local r1, g1, b1 = frameSdk.colorToRGB(baseFrame[i])
            local r2, g2, b2 = frameSdk.colorToRGB(currentColors[i])
            local diff = math.abs(r1  - r2) + math.abs(g1  - g2) + math.abs(b1  - b2)

            if diff < 30 then
                sameCount = sameCount + 1
            end
        end

        -- 计算相似度
        local currentSimilarity = sameCount / sampleCount
        lastSimilarity = currentSimilarity  -- 记录当前相似度

        -- 检测到变化立即返回
        if currentSimilarity < changeThreshold then
            return true, currentSimilarity
        end

        -- 更新历史帧
        lastColors = currentColors

        jy_sys.sleep(interval)
    end

    -- 检测周期内未发现变化
    return false, lastSimilarity
end
--[[休息时间优化（自带浮动20%休息时间）
参数 t : 休息时间(毫秒)
]]
function jy_sys.sleep(t)
	if t == nil then t = 200 end
	local st
	if type(t) == "number" then
		st = jy_rand.randomFloat(t,t/8)
	elseif type(t) == "table" then
		st = jy_rand.randomRange(t[1],t[2])
	end
	local start = frameSdk.systemTime()
	if st > 1000 then
		while true do
			if frameSdk.systemTime() - start > st then
				break
			end
			frameSdk.sleep(300)
		end
	else
		frameSdk.sleep(st)
	end
end

--[[摇一摇]]
function jy_sys.Shake()
	os.execute("setprop call.shake null")
end


--[[ ================== 触控方法 ==================]]


--[[无障碍滑动路径生成
	paths： 路径数组
	sTime：	按下时间
	moveTime： 滑动时间
返回：
	路径对象
]]

function jy_touch.gestureMoveCrestPath(paths,sTime,moveTime)
	local path = frameSdk.Path:new() --创建一个轨迹路径
	path:setStartTime(sTime) --从触发事件的第100毫秒后开始滑动
	path:setDurTime(moveTime) --滑动的时间
	for k, v in pairs(paths) do
		path:addPoint(v.x,v.y) --轨迹上的第一个点
	end
	return path
end
--[[无障碍滑动路径生成
参数：
	paths： 路径数组
	sTime：	按下时间
	moveTime： 滑动时间
返回：
	路径对象
]]
--无障碍滑动创建手势操作
function jy_touch.gestureMove(paths,sTime,moveTime)
	local gesture = frameSdk.Gesture:new() --创建一个手势滑动对象
	local path = jy_touch.gestureMoveCrestPath(paths,sTime,moveTime)
	gesture:addPath(path) --添加一个滑动路径
	return gesture
end

--[[无障碍滑动路径生成
参数：
	point： 坐标 {x = [坐标x], y=[坐标y]}}
	longWait：	长按时间
返回：
	无
]]
function jy_touch.longClick(point,longWait)
	local x,y
	local p = point
	if jy_conf.runEnvType == 1 then
		local gesture = jy_touch.gestureMove({Point(x,y),Point(x+1,y)},100,longWait)
		gesture:dispatch()
	else
		frameSdk.touchDown(2, x, y)
		jy_sys.sleep(longWait)
		frameSdk.touchUp(2)
	end
	jy_sys.sleep(math.random(50,100))
end

--[[浮动点击(点击坐标随机浮动5个像素)
参数：
	point： 坐标 {x = [坐标x], y=[坐标y]} 或 {坐标x, 坐标y}}
	longWait：	长按时间（不填直接点击）
返回：
	无
]]
function jy_touch.click(tape,wait)
	local x,y=tape[1] or tape.x,tape[2] or tape.y
    if tape.f ~= false then
        x = x + math.random(-5,5)
        y = y + math.random(-5,5)
    end
	if wait then
		jy_touch.longClick(Point(x,y),wait)
	else
		frameSdk.tap(x,y)
	end
end

--[[精确点击
参数：
	point： 坐标 {x = [坐标x], y=[坐标y]} 或 {坐标x, 坐标y}}
	longWait：	长按时间（不填直接点击）
返回：
	无
]]
function jy_touch.click2(tape,wait)
	local x,y=tape[1] or tape.x,tape[2] or tape.y

	local lwait = wait or  jy_rand.randomRange(20,50)
	if wait then
		jy_touch.longClick(Point(x,y),wait)
	else
		frameSdk.tap(x,y)
	end
end

--[[放大，模拟2指从屏幕中间向外滑动
参数：
	x1, y1： 结束坐标1
	x2, y2： 结束坐标2
	t：	放大时间
	wait：	放大后延时
返回：
	无
]]
function jy_touch.zOut(targetX1,targetX2, targetY1, targetY2,t,wait)
    local zTime = t or 500
    local zWait = wait or 500
    local steps = 20
    local stepDelay = zTime / steps
    local d_x,d_y = frameSdk.getDisplaySize()
    local centerX = d_x / 2
    local centerY = d_y / 2
    -- 初始化双指初始位置（中心点附近）
    local startX1 = centerX - 20
    local startY1 = centerY
    local startX2 = centerX + 20
    local startY2 = centerY

	if jy_conf.runEnvType == 1 then
		local path1 = {
			Point(centerX,centerY),
			Point(targetX1,targetY1),
		}
		local path2 = {
			Point(centerX,centerY),
			Point(targetX2,targetY2),
		}
		local gesture1 = jy_touch.gestureMove(path1,100,zTime)
		local addPath = jy_touch.gestureMoveCrestPath(path2,100,zTime)

		gesture1:addPath(addPath)
		gesture1:dispatch()
	else
		-- 同步按下双指
		frameSdk.touchDown(1,startX1,startY1)
		frameSdk.touchDown(2,startX2,startY2)
		jy_sys.sleep(50)

		-- 平滑同步外移
		for i =1,steps do
			local moveX1 = startX1 + (targetX1 - startX1)/steps * i
			local moveX2 = startX2 + (targetX2 - startX2)/steps * i
			frameSdk.touchMove(1,moveX1,startY1)
			frameSdk.touchMove(2,moveX2,startY2)
			jy_sys.sleep(stepDelay)
		end

		-- 稳定位置防止偏移
		jy_sys.sleep(100)

		-- 同步抬起双指
		frameSdk.touchUp(1)
		frameSdk.touchUp(2)

	end
    jy_sys.sleep(zWait)
end
--[[捏合，模拟2指向屏幕中间滑动
参数：
	x1, y1： 起始坐标1
	x2, y2： 起始坐标2
	t：	捏合时间
	wait：	捏合后延时
返回：
	无
]]
function jy_touch.zIn(x1, y1, x2, y2, t, wait)
    local zTime = t or 500
    local zWait = wait or 500
    local steps = 20  -- 增加移动步数提高流畅度
    local stepDelay = zTime / steps

    -- 计算中心点坐标
    local centerX = (x1 + x2) / 2
    local centerY = (y1 + y2) / 2

    -- 计算每个步骤的移动增量
    local dx1 = (centerX - x1) / steps
    local dy1 = (centerY - y1) / steps
    local dx2 = (centerX - x2) / steps
    local dy2 = (centerY - y2) / steps

	if jy_conf.runEnvType == 1 then
		local path1 = {
			Point(dx1,dy1),
			Point(centerX,centerY),
		}
		local path2 = {
			Point(dx2,dy2),
			Point(centerX,centerY),
		}
		local gesture1 = jy_touch.gestureMove(path1,100,zTime)
		local addPath = jy_touch.gestureMoveCrestPath(path2,100,zTime)

		gesture1:addPath(addPath)
		gesture1:dispatch()
	else
		-- 同时按下两个触点
		frameSdk.touchDown(1, x1, y1)
		frameSdk.touchDown(2, x2, y2)
		jy_sys.sleep(50)

		-- 平滑同步移动
		for i = 1, steps do
			local newX1 = x1 + dx1 * i
			local newY1 = y1 + dy1 * i
			local newX2 = x2 + dx2 * i
			local newY2 = y2 + dy2 * i

			frameSdk.touchMove(1, newX1, newY1)
			frameSdk.touchMove(2, newX2, newY2)
			jy_sys.sleep(stepDelay)
		end

		-- 添加稳定点防止滑动
		frameSdk.touchMove(1, centerX, centerY)
		frameSdk.touchMove(2, centerX, centerY)
		jy_sys.sleep(100)

		-- 同时释放触点
		frameSdk.touchUp(1)
		frameSdk.touchUp(2)
	end
    jy_sys.sleep(zWait)
end
--[[高精度滑动
参数：
	xy： 坐标 {起始坐标x, 起始坐标y, 结束坐标x, 结束坐标y}}
	moveTime：	滑动时间
	wait：	滑动后等待时间
	stdelay：	按下后延时
	dendelay：	抬起前延时
	moveId：模拟手指的索引号0-4之间 默认 1
返回：
	无
]]
function jy_touch.moveLine(xy,movedelay,wait,stdelay,dendelay,moveId)
	local stx, sty = xy[1], xy[2]
	local endx, endy = xy[3], xy[4]
    local mwait = wait  or 500
    local mmovedelay = movedelay  or 500
    local mtdelay = stdelay  or 50
    local mdendelay = dendelay  or 200
	local moveId = moveId or 1
    -- 分段移动路径计算（5点插值）
    local points = {
        {stx, sty},
        {stx + (endx - stx)*0.25, sty + (endy - sty)*0.25},
        {stx + (endx - stx)*0.5, sty + (endy - sty)*0.5},
        {stx + (endx - stx)*0.75, sty + (endy - sty)*0.75},
        {endx, endy}  -- 终点
    }

    -- 非线性速度曲线（缓入缓出）
    local segmentTimes = {
        mmovedelay  * 0.25,  -- 起始段加速
        mmovedelay  * 0.20,
        mmovedelay  * 0.20,
        mmovedelay  * 0.35   -- 结束段减速
    }

	if jy_conf.runEnvType == 1 then
		local gesture = jy_touch.gestureMove(points,mtdelay,movedelay)
		gesture:dispatch()

	else
	    frameSdk.touchDown(moveId,stx, sty )
		jy_sys.sleep(mtdelay)
		-- 执行分段滑动
		for i = 2, 5 do
			local prev = points[i-1]
			local curr = points[i]
			local dist = math.sqrt((curr[1]-prev[1])^2  + (curr[2]-prev[2])^2)

			-- 距离自适应延时（防止短距离移动过快）
			local adaptiveDelay = segmentTimes[i-1] * math.min(1.0,  dist/50)
			print()
			frameSdk.touchMoveEx(moveId, curr[1], curr[2],  adaptiveDelay)
			-- 动态等待（最后一段增加校准时间）
			local waitFactor = (i == 5) and 1.5 or 1.0
			jy_sys.sleep(adaptiveDelay  * 0.3 * waitFactor)
		end

		local endmx = jy_num.qz(endx - stx)  > 50
		local endmy = jy_num.qz(endy - sty)  > 50

		local endMovex = Iif(endmx, Iif(endx > stx, 10 , -10), 1)
		local endMovey = Iif(endmy, Iif(endy > sty, 10 , -10), 1)
		-- 终点校准（防止偏移）
		frameSdk.touchMoveEx(moveId,endx + endMovex, endy + endMovey,  50)  -- 微调
		jy_sys.sleep(200)
		frameSdk.touchUp(moveId)
	end
    jy_sys.sleep(mwait)
end
--[[曲线滑动
参数：
	xy： 坐标 {起始坐标x, 起始坐标y, 结束坐标x, 结束坐标y}}
	moveTime：	滑动时间
	wait：	滑动后等待时间
	stdelay：	按下后延时
	dendelay：	抬起前延时
返回：
	无
]]
function jy_touch.moveCtrl(xy,moveTime,wait,stdelay,dendelay)
    -- 合并参数处理
    local config = {
        wait = wait  or 200,
        down_delay = stdelay  or 50,
        duration = moveTime  or 800,
        start = {x = xy[1],  y = xy[2]},
        target = {x = xy[3],  y = xy[4]}
    }

    -- 滑动路径生成器
    local function generatePath()
        local path = {}
        local dx, dy = config.target.x  - config.start.x,  config.target.y  - config.start.y

        -- 贝塞尔曲线控制点（模拟自然滑动）
        local ctrl_x = config.start.x  + dx * 0.3
        local ctrl_y = config.start.y  + dy * 0.7

        for i = 0, 1, 0.05 do  -- 20个路径点
            local t = i
            local x = (1-t)^2 * config.start.x  + 2*(1-t)*t*ctrl_x + t^2 * config.target.x
            local y = (1-t)^2 * config.start.y  + 2*(1-t)*t*ctrl_y + t^2 * config.target.y
            table.insert(path,  {x = math.floor(x),  y = math.floor(y)})
        end
        return path
    end

	if jy_conf.runEnvType == 1 then
		local path_points = generatePath()
		local gesture = jy_touch.gestureMove(path_points,stdelay,moveTime)
		gesture:dispatch()
	else
	    -- 执行触摸操作
		frameSdk.touchDown(1, config.start.x,  config.start.y)
		jy_sys.sleep(config.down_delay)

		local path_points = generatePath()
		local segment_time = config.duration  / #path_points

		for _, point in ipairs(path_points) do
			frameSdk.touchMoveEx(1, point.x, point.y, segment_time)
		end

		frameSdk.touchUp(1)
	end

    jy_sys.sleep(config.wait)

end


--[[ ================== 随机方法 ==================]]


--[[多功能随机内容生成器
函数名: myRand
功能描述: 多功能随机内容生成器，支持多种类型的随机内容生成

参数说明:
  rnType : integer - 生成内容的类型标识符 (必填)
    1 = 纯数字
    2 = 手机号
    3 = 纯字母
    4 = 数字字母组合
    5 = 电子邮箱
	6 = 十六进制字符串
	9 = 生成随机网民
	10 = 生成随机姓名
  rnLen  : integer - 生成内容的长度 (可选，默认值: 1)
    * 注意: 类型2(手机号)忽略此参数，固定生成11位
    * 类型5(邮箱)实际长度 = rnLen + 后缀长度(7-12位)
  rnUL   : integer - 大小写控制 (可选，默认值: 3)
    1 = 全大写
    2 = 全小写
    3 = 保持原始大小写

返回值:
  string - 生成的随机内容字符串
  特殊返回值:
    "无效类型"   - 当rnType不在1-9范围内
    "无字库"     - 类型7需要但未找到中文字库文件
    "词库未加载" - 类型8需要但未加载外部词库
	]]
function jy_rand.myRand(rnType, rnLen, rnUL)
    local result = ""
    rnLen = rnLen or 1
    rnUL = rnUL or 3

	local function getWordTable(words,lenth)
		local chars = {}
		local index = 1
		for i = 0 ,#words,3*lenth do
			chars[index] = string.sub(words,i+1,i+3*lenth)
			index = index + 1
		end
		return chars
	end
    -- 类型分发处理器
    local handlers = {
        [1] = function() -- 数字
            result = tostring(math.random(1,9))
            for i = 1, rnLen-1 do
                result = result .. math.random(0,9)
            end
			return result
        end,

        [2] = function() -- 手机号
            local header = jy_conf.PHONE_HEADERS[math.random(#jy_conf.PHONE_HEADERS)]
            result = tostring(header)
            for i = 1, 8 do  -- 修复: 手机号应为11位
                result = result .. math.random(0,9)
            end
			return result
        end,

        [3] = function() -- 字母
            for i = 1, rnLen do
                result = result .. jy_conf.LETTERS[math.random(52)]
            end
			return result
        end,

        [4] = function() -- 数字字母组合
            local letterCount = jy_rand.randomRange(2, rnLen)
            for i = 1, letterCount do
                result = result .. jy_conf.LETTERS[math.random(52)]
            end
            for i = 1, rnLen - letterCount do
                result = result .. math.random(0,9)
            end
			return result
        end,

        [5] = function() -- 邮箱
            result = jy_rand.myRand(4, rnLen, false) .. jy_conf.EMAIL_SUFFIX[math.random(#jy_conf.EMAIL_SUFFIX)]
			return result
        end,

        [6] = function() -- 16进制
            result = jy_conf.HEX[math.random(2,22)] -- 首位避开0
            for i = 1, rnLen-1 do
                result = result .. jy_conf.HEX[math.random(1,22)]
            end
			return result
        end,
		[9] = function()
			result = ""
			local charslist = jy_str.splitStr("- _ | 、 ."," ")
			result = result..jy_conf.ADJECT_LIST[math.random(1,#jy_conf.ADJECT_LIST)]
			result = result..charslist[math.random(1,#charslist)]
			result = result..jy_conf.NOUN_LIST[math.random(1,#jy_conf.NOUN_LIST)]
			return result
		end,
		[10] = function()
			local result = jy_conf.FIRST_NAME[math.random(1,#jy_conf.FIRST_NAME)]
			local namelength = math.random(1,2)
			for i = 1,namelength do
				result = result..jy_conf.LAST_NAME[math.random(1,#jy_conf.LAST_NAME)]
			end
			return result
		end,
    }

    -- 执行类型处理
    local handler = handlers[rnType]
    if handler then
        result = handler()
    else
        return "无效类型"
    end
    -- 大小写处理
    if rnUL == 1 then
        return string.upper(result)
    elseif rnUL == 2 then
        return string.lower(result)
    end
    return result
end
--[[生成随机账号密码
参数1： rty （生成策略 1 \ 2 ,两种模式，自行查看）

返回值:
	{
		1 ==> "pcbw4998",
		2 ==> "tz337tyx",
	}
]]
function jy_rand.randAcc(rty)

    -- 封装随机部分生成器
    local function genPart()
        return jy_rand.myRand(3, math.random(2,  4), 2) .. jy_rand.myRand(1, math.random(3,  4))
    end

    local account = {}
    -- 账户生成策略
    if not rty or rty == 1 then
        account[1] = genPart() .. jy_rand.myRand(3, math.random(2,  4), 2)
        account[2] = genPart() .. jy_rand.myRand(3, math.random(2,  4), 2)
    elseif rty == 2 then
        account[1] = genPart()  -- 仅基础部分
        account[2] = genPart() .. jy_rand.myRand(3, math.random(2,  4), 2)
    end

    return account
end

 --[[浮动随机
说明：
	传入一个基础数字，根据指定范围生成随机数
参数：
	num: 基础值
	float: 浮动范围
返回：
	随机的数字
 ]]
function jy_rand.randomFloat(num, float)
	local minNum = math.floor(num - float)
	local maxNum = math.floor(num + float)
	return math.random(minNum ,maxNum);
end

 --[[范围随机
说明：
	根据指定范围生成随机数
参数：
	min: 最小数
	max: 最大数
返回：
	随机的数字
 ]]
function jy_rand.randomRange(min, max)
    if min > max then min, max = max, min end  -- 自动校正范围
	return math.random(min,max);
end



--[[ ================== 日期方法 ==================]]


--[[倒计时
方法说明：
	一个倒计时方法，会弹出提示框
参数：
	t： 倒计时时间 [毫秒]
	mtext： 自定义提示前缀 默认"轮号休息剩余时间: "

返回值:
	无
]]
function jy_time.countDown(t,mtext)
    if t == 0 then return end  -- 零休眠时间直接返回

    local old_t = frameSdk.systemTime()   -- 记录起始时间戳
    local remaining = t        -- 初始化剩余时间

    while remaining > 0 do
        jy_sys.sleep(1000)  -- 每秒检查一次

        -- 计算剩余时间
        local current = frameSdk.systemTime()
        remaining = t - (current - old_t)

        if remaining > 0 then
            -- 毫秒转分钟/秒
            local total_seconds = math.floor(remaining  / 1000)
            local minutes = math.floor(total_seconds  / 60)
            local seconds = total_seconds % 60

            -- 格式化显示
            local time_str = string.format("%d  分 %02d 秒", minutes, seconds)
			if mtext == nil then
				frameSdk.toast("轮号休息剩余时间: " .. time_str)
			else
				frameSdk.toast(mtext .. time_str)
			end

        end
    end
end
--[[时间戳转动态格式函数，支持秒/毫秒时间戳
方法说明：
	讲时间戳转换为 [xx天xx小时xx分钟xx秒] 格式
参数：
	timestamp： 时间戳 [秒] 默认为当前时间戳
	isMillisecond： 是否是毫秒
返回：
	xx天xx小时xx分钟xx秒
]]
function jy_time.timeFormat(timestamp, isMillisecond)
    -- 处理空值默认返回0秒
    if not timestamp then return "0秒" end
    -- 转换为总秒数，兼容毫秒时间戳
    local totalSeconds = tonumber(timestamp) or 0
    if isMillisecond then
        totalSeconds = math.floor(totalSeconds / 1000)
    else
        totalSeconds = math.floor(totalSeconds)
    end
    -- 拆分各时间单位
    local days = math.floor(totalSeconds / 86400)
    local remaining = totalSeconds % 86400
    local hours = math.floor(remaining / 3600)
    remaining = remaining % 3600
    local mins = math.floor(remaining / 60)
    local secs = remaining % 60
    -- 动态拼接结果字符串
    local resultSegments = {}
    if days > 0 then
        table.insert(resultSegments, days .. "天")
    end
    if hours > 0 then
        table.insert(resultSegments, hours .. "小时")
    end
    if mins > 0 then
        table.insert(resultSegments, mins .. "分钟")
    end
    -- 始终添加秒数
    table.insert(resultSegments, secs .. "秒")
    -- 拼接所有非空段，返回最终结果
    return table.concat(resultSegments, " ")
end
--[[时间戳属性
参数：
	timestamp： 时间戳 [秒] 默认为当前时间戳
返回：
	{
		day:几号
		weekDayCN:星期几
		month: 月份
	}
]]
function jy_time.getTimeInfo(timestamp)
    local target = timestamp or os.time()
    -- 获取基础格式化内容
    local day = os.date("%d", target)
    local month = os.date("%m", target)
    local weekNum = tonumber(os.date("%w", target))
    -- 中文星期映射表
    local weekMap = {[0]=7,[1]=1,[2]=2,[3]=3,[4]=4,[5]=5,[6]=6}

    return {
        day = day,
        weekDayCN = weekMap[weekNum],
        month = month
    }
end
--[[获取时间是星期几
参数：
	t： 时间戳 [秒] 默认为当前时间戳
返回：
	星期几
]]
function jy_time.getWeek(t)
	local nowDate = getTimeInfo(t)
	return nowDate.weekDayCN
end
--[[获取时间是几号
参数：
	t： 时间戳 [秒] 默认为当前时间戳
返回：
	号数
]]
function jy_time.getDay(t)
	local nowDate = getTimeInfo(t)
	return nowDate.day
end
--[[获取时间的月份
参数：
	t： 时间戳 [秒] 默认为当前时间戳
返回：
	月份
]]
function jy_time.getMonth(t)
	local nowDate = getTimeInfo(t)
	return nowDate.month
end


--[[ ================== 网络方法 ==================]]


--[[获取当前外网IP
参数：
	无
返回：
	IP地址
]]
function jy_net.getNetIP(timeout)
    local timeout = timeout or 5000  -- 默认5秒超时
    local start_time = frameSdk.systemTime()

	local function fetchIP(url)
		local headers = {}
		headers["User-Agent"]
		= "Mozilla/5.0 (Windows; U; Windows NT 5.1; zh-CN; rv:1.9.0.3) Gecko/2008092417 Firefox/3.0.3"

		local ip = frameSdk.LuaEngine.httpGet(url,headers,3)
		if not ip:match("%d+%.%d+%.%d+%.%d+") then
			return nil, "响应非IP格式："..ip:sub(1, 20).."..."
		end

		return jy_str.trim(ip)
	end
    -- 优化服务列表（新增3个高可用服务）
    local ip_services = {
        "http://members.3322.org/dyndns/getip",   -- 原始服务
        "https://ipinfo.io/ip" ,              -- 备用服务1
        "https://4.ident.me/",                  -- 备用服务2
        "https://checkip.amazonaws.com"      -- AWS官方服务
    }

    for _, service in ipairs(ip_services) do

        -- 调用补全的IP获取方法
        local ip, err = fetchIP(service)
        if ip then
            -- 增强数据清洗：移除非IP字符
            return ip:gsub("[^%d%.]", ""):match("^%d+%.%d+%.%d+%.%d+$")
        end
    end

    return nil
end

--[[检查当前网络延迟
说明：
	通过ping 百度3次取平均值获取网络延时，需root环境
参数：
	无
返回：
	ping114.114.114.114 延迟
]]
function jy_net.checkPing()
	local ping = -1

	--解析ping延时
	local function readPing(tt)
		local rPing
		local time_i, time_j, substr = string.find(tt,"time=")
		local ms_i, ms_j, substr = string.find(tt,"ms")
		if time_i and ms_i then
			rPing = tonumber(string.sub(tt,time_j + 1 ,ms_i-1))
		else
			rPing = 1000
		end
		return rPing
	end

	local iRet,sRet =pcall(function()
		os.execute("ping -c 3 www.baidu.com > /sdcard/ping.txt")
		local pingCount = 4
		local pingSum = 0
		for i = 2, 4, 1 do
			local tt = jy_file.readFileLine("/sdcard/ping.txt",i)
			if tt and tt~="" then
				pingSum = pingSum + readPing(tt)
			end
		end

		ping = jy_num.toFixed(pingSum / 3,2,true)
		jy_file.delFile("/sdcard/ping.txt")
	end)
	if iRet ~= true then
		print("checkPingError:"..sRet)
		return 1000
	end
	if not ping or ping == "" then
		ping = 1000
	end
	return ping
end
--[[检查当前网络是否可用
说明：
	检查当前网络是否可用，且当前IP是否属于指定账号
参数：
	ipaddr_new：当前IP
	CKTime： IP有效时间 这个IP超过多长时间没有使用可以给其他账号使用
	acc：账号
返回：
	0、当前IP不可用；1、当前IP可用；2、当前IP已被其他账号使用
]]
function jy_net.ipCheck(ipaddr_new, CKTime,acc)
    -- 参数验证与初始化
    CKTime = CKTime or 172800  -- 默认48小时有效期
    local nowUser = acc

    -- IP有效性检查
    local invalid_ips = {"61.130.146.123", "没有获得ip地址", nil}
    if not ipaddr_new or frameSdk.table.contains(invalid_ips,  ipaddr_new) then
        jy_sys.mlog("无效IP: " .. (ipaddr_new or "nil"))
        return -1
    end
    -- 文件路径常量
    local IP_RECORD_PATH = "/sdcard/ipRecord.txt"
    local records = {}

    -- 读取并解析IP记录
    local function load_records()
        records = {}
        local lines = jy_file.ReadFileLines(IP_RECORD_PATH, "r") or {}
        for _, line in ipairs(lines) do
            local record = {}
            local parts = jy_str.split(jy_str.trim(line), ":")
            record.user  = tonumber(parts[1]) or 0
            record.ip  = parts[2] or ""
            record.first_time  = tonumber(parts[3]) or os.time()
            record.count  = tonumber(parts[4]) or 1
            record.last_time  = tonumber(parts[5]) or os.time()
            table.insert(records,  record)
        end
    end

    -- 保存IP记录
    local function save_records()
        local lines = {}
        local current_time = os.time()

        for _, rec in ipairs(records) do
            -- 删除过期记录 (超过CKTime未使用)
            if (current_time - rec.last_time)  <= CKTime then
                table.insert(lines,  string.format("%d:%s:%d:%d:%d",
                    rec.user,  rec.ip,  rec.first_time,  rec.count,  rec.last_time))
            end
        end

        jy_file.writeFileLines(IP_RECORD_PATH, lines)
        return #lines
    end

    -- 主处理逻辑
    local function process_ip()
        load_records()
        local code = 0
        local found = false
        local current_time = os.time()

        -- 搜索匹配记录
        for _, rec in ipairs(records) do
            -- IP已被其他账号使用
            if rec.ip  == ipaddr_new and rec.user  ~= nowUser then
                jy_sys.mlog(string.format("IP[%s] 已被账号%d使用", ipaddr_new, rec.user))
                return -1
            end

            -- 当前账号已使用过此IP
            if rec.ip  == ipaddr_new and rec.user  == nowUser then
                rec.count  = rec.count  + 1
                rec.last_time  = current_time
                found = true
                code = 1
            end
        end

        -- 添加新记录
        if not found then
            table.insert(records,  {
                user = nowUser,
                ip = ipaddr_new,
                first_time = current_time,
                count = 1,
                last_time = current_time
            })
            code = 1
        end

        -- 保存并返回
        save_records()
        return code
    end

    -- 执行并处理错误
    local ok, result = pcall(process_ip)
    if not ok then
        print("ipCheckError:"..tostring(result))
        return -1
    end
    return result
end




--[[ ================== 弹窗 ==================]]
--[[
弹窗工具类 MsgAlert
功能：创建标准化的消息提示弹窗，支持不同按钮组合
参数说明：
    text : string - 弹窗提示信息（必填）
    type : number - 弹窗按钮类型（可选，默认2）
        1 - 确定 + 取消按钮
        2 - 仅确定按钮
        0 - 仅关闭按钮
    wait : number - 等待超时时间（毫秒，可选，默认10000）
返回值：
    boolean - 用户操作结果
        true: 确认 / 关闭
        false: 取消
]]
local MsgAlert = {
    -- 私有属性
    _ret = false,          -- 返回结果
    _waiting = true,       -- 等待状态标识
    _layoutName = "消息提示" -- 弹窗布局名称
}

--[[
私有方法：重置弹窗状态
]]
function _resetState()
    MsgAlert._ret = false
    MsgAlert._waiting = true
end

--[[
私有方法：取消按钮点击事件处理
]]
function _onCancelClick()
    frameSdk.ui.dismiss(MsgAlert._layoutName)
    MsgAlert._ret = false
    MsgAlert._waiting = false
end

--[[
私有方法：确认/关闭按钮点击事件处理
]]
function _onConfirmClick()
    frameSdk.ui.dismiss(MsgAlert._layoutName)
    MsgAlert._ret = true
    MsgAlert._waiting = false
end

--[[
公有方法：显示消息弹窗
@param text string 提示文本
@param type number 按钮类型（0/1/2）
@param wait number 超时时间（毫秒）
@return boolean 用户选择结果
]]
function MsgAlert.showMsg(text, buttonType, wait)
    -- 参数校验
    if not text or type(text) ~= "string" then
        error("弹窗提示信息(text)必须为非空字符串")
    end

    -- 重置状态
    _resetState()

    -- 设置默认值
    local btnType = buttonType or 2  -- 默认仅确定按钮
    local waitTime = wait or 10000  -- 默认超时10秒

    -- 创建弹窗布局
    frameSdk.ui.newLayout(MsgAlert._layoutName, 300, 400)
    frameSdk.ui.newRow(MsgAlert._layoutName, "rou1")
    frameSdk.ui.newRow(MsgAlert._layoutName, "rou2")

    -- 添加提示文本
    frameSdk.ui.addTextView(MsgAlert._layoutName, "tvId", text)
    frameSdk.ui.newRow(MsgAlert._layoutName, "rou4")

    -- 添加分割线
    frameSdk.ui.addLine(MsgAlert._layoutName, "rou3", -1, 5)
    frameSdk.ui.newRow(MsgAlert._layoutName, "rou5")

    -- 根据按钮类型创建对应按钮
    if btnType == 1 then
        -- 确定 + 取消按钮
        frameSdk.ui.addButton(MsgAlert._layoutName, "取消", "取消")
        frameSdk.ui.addButton(MsgAlert._layoutName, "确定", "确定")
        frameSdk.ui.setOnClick("确定", "_onConfirmClick()")
        frameSdk.ui.setOnClick("取消", "_onCancelClick()")
    elseif btnType == 2 then
        -- 仅确定按钮
        frameSdk.ui.addButton(MsgAlert._layoutName, "确定", "确定")
        frameSdk.ui.setOnClick("确定", "_onConfirmClick()")
    elseif btnType == 0 then
        -- 仅关闭按钮
        frameSdk.ui.addButton(MsgAlert._layoutName, "关闭", "关闭")
        frameSdk.ui.setOnClick("关闭", "_onConfirmClick()")
    else
        -- 非法类型，使用默认值
        print(string.format("无效的按钮类型：%s，使用默认类型（仅确定按钮）", tostring(btnType)))
        frameSdk.ui.addButton(MsgAlert._layoutName, "确定", "确定")
        frameSdk.ui.setOnClick("确定", "_onConfirmClick()")
    end

    -- 显示弹窗
    frameSdk.ui.show(MsgAlert._layoutName, false)

    -- 等待用户操作或超时
    local elapsed = 0
    while MsgAlert._waiting and elapsed < waitTime do
        jy_sys.sleep(500)
        elapsed = elapsed + 500
    end

    -- 超时处理
    if MsgAlert._waiting then
        frameSdk.ui.dismiss(MsgAlert._layoutName)
        MsgAlert._waiting = false
        MsgAlert._ret = false  -- 超时默认返回取消
        print("弹窗操作超时，默认返回取消")
    end

    return MsgAlert._ret
end


--[[ ================== 其他公共函数 ==================]]

function Iif(exp,rtrue,rfalse)
	if exp == false or exp == nil then
		return rfalse
	else
		return rtrue
	end
end

--[[ 非空判断 ]]

function IsEmpty(val)
	return val == nil or val == ""
end

return {
	jy_ocr = jy_ocr,
	jy_num = jy_num,
	jy_tab = jy_tab,
	jy_file = jy_file,
	jy_str = jy_str,
	jy_sys = jy_sys,
	jy_touch = jy_touch,
	jy_rand = jy_rand,
	jy_time = jy_time,
	jy_net = jy_net,
	jy_msg = MsgAlert,
}
