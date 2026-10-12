-- ==================== 全局配置 ====================
-- 手机游戏脚本辅助 - 配置文件

local CONFIG = {}

-- ==================== 基础设置 ====================

CONFIG.app = {
    name = "目标游戏",           -- 应用名称
    package = "com.example.game", -- 包名（用于定位应用）
    version = "1.0.0",          -- 脚本版本
}

-- ==================== 屏幕设置 ====================

CONFIG.screen = {
    -- 开发时使用的分辨率（实际设备可能不同，会自动缩放）
    devWidth = 720,
    devHeight = 1280,
    
    -- 是否启用屏幕缩放（不同分辨率设备适配）
    useScale = true,
}

-- ==================== 找色配置 ====================

CONFIG.color = {
    -- 默认相似度 (0~1)
    defaultSim = 0.9,
    
    -- 默认查找方向 (0:左上→右下 | 1:中心→四周 | 2:右下→左上 | 3:左下→右上 | 4:右上→左下)
    defaultDir = 0,
    
    -- 全屏查找时的缩放区域 (避免全屏扫描过慢)
    scanRegionScale = 0.85,   -- 屏幕中间 85% 区域
    
    -- 找色超时时间 (毫秒)
    findTimeout = 5000,
}

-- ==================== 操作配置 ====================

CONFIG.action = {
    -- 点击随机偏移范围 (像素)
    clickJitter = {2, 5},
    
    -- 操作延迟范围 (毫秒)
    minDelay = 800,
    maxDelay = 2000,
    
    -- 连续操作最大间隔 (毫秒)，超过则视为卡死
    maxIdleTime = 30000,
    
    -- 点击后最小延迟 (毫秒)
    tapDelay = 150,
    
    -- 滑动操作配置
    swipe = {
        minDistance = 200,
        maxDistance = 400,
        duration = 300,  -- 滑动时长(毫秒)
    },
}

-- ==================== 战斗配置 ====================

CONFIG.battle = {
    -- 战斗超时时间 (毫秒)
    timeout = 60000,
    
    -- 自动用药阈值 (HP < 30% 时用药)
    autoPotionThreshold = 0.3,
    
    -- 攻击间隔范围 (毫秒)
    attackInterval = {200, 500},
    
    -- 技能冷却检测 (毫秒)
    skillCooldownCheck = 1000,
    
    -- 战斗结束检测时间 (毫秒)
    idleTimeout = 5000,
}

-- ==================== 循环配置 ====================

CONFIG.loop = {
    -- 循环次数 (0=无限循环)
    maxCount = 0,
    
    -- 每轮循环最大耗时 (毫秒)，超过则退出
    maxRoundTime = 3600000,  -- 1小时
    
    -- 每轮循环后的休息间隔 (毫秒)
    restInterval = 5000,
}

-- ==================== 异常处理配置 ====================

CONFIG.exception = {
    -- 广告弹窗检测颜色
    adColor = "FFFFFF",
    
    -- 广告区域坐标 (可选，不填则全屏检测)
    adRegion = {left = 0, top = 0, right = 720, bottom = 1280},
    
    -- 网络断开检测颜色 (可选)
    disconnectColor = nil,
    
    -- 最大重试次数
    maxRetry = 3,
    
    -- 重试间隔 (毫秒)
    retryDelay = 2000,
    
    -- 卡死检测阈值 (毫秒)
    deadlockThreshold = 30000,
}

-- ==================== 每日副本配置 ====================

CONFIG.dungeon = {
    -- 是否启用每日副本自动执行
    enabled = true,
    
    -- 默认层数
    defaultFloor = 1,
    
    -- 副本时长 (毫秒)，默认1小时 = 3600000
    dungeonDuration = 3600000,
    
    -- 自动挂机血量阈值 (0~1)，低于此值开始挂机
    autoHangThreshold = 0.3,
    
    -- 退出副本后延迟 (毫秒)
    exitDelay = 2000,
    
    -- 可选：每日副本层数列表（按优先级）
    floorPriority = {1, 2, 3, 4, 5},
}

-- ==================== 日志配置 ====================

CONFIG.log = {
    -- 日志目录
    dir = "/sdcard/game-bot/logs/",
    
    -- 日志级别 (debug, info, warn, error)
    level = "info",
    
    -- 是否输出到控制台
    console = true,
    
    -- 日志文件最大大小 (MB)
    maxFileSize = 10,
    
    -- 保留日志文件数量
    maxFiles = 5,
}

-- ==================== 游戏模板配置 ====================

CONFIG.templates = {
    -- 游戏界面模板路径
    path = "/sdcard/game-bot/templates/",
    
    -- 自动加载模板
    autoLoad = true,
}

-- ==================== 辅助函数 ====================

--- 获取随机延迟
function CONFIG.randomDelay()
    local delay = math.random(CONFIG.action.minDelay, CONFIG.action.maxDelay)
    sleep(delay)
    return delay
end

--- 随机偏移后点击
function CONFIG.randomClick(x, y)
    local dx = math.random(-CONFIG.action.clickJitter[1], CONFIG.action.clickJitter[0])
    local dy = math.random(-CONFIG.action.clickJitter[1], CONFIG.action.clickJitter[0])
    tap(x + dx, y + dy)
    CONFIG.randomDelay()
end

--- 获取当前时间戳
function CONFIG.timestamp()
    return os.date("%Y%m%d_%H%M%S")
end

--- 获取日志文件路径
function CONFIG.logPath(filename)
    local dir = CONFIG.log.dir
    if not files.isDir(dir) then
        files.mkdir(dir)
    end
    return dir .. (filename or CONFIG.timestamp()) .. ".log"
end

return CONFIG
