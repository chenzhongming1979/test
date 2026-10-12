-- ==================== 日志系统 ====================
-- 分级日志：debug / info / warn / error
-- 支持控制台输出 + 文件输出 + 日志轮转

local M = {}

local LEVELS = {
    debug = 1,
    info  = 2,
    warn  = 3,
    error = 4,
}

local currentLevel = LEVELS.info
local consoleOutput = true
local logDir = "/sdcard/game-bot/logs/"
local currentFile = nil
local currentFileSize = 0
local maxFileSize = 10 * 1024 * 1024  -- 10MB
local maxFiles = 5
local logBuffer = {}
local maxBufferSize = 1000

--- 初始化日志系统
-- @param config 可选, 覆盖默认配置 {dir, level, console, maxFileSize, maxFiles}
function M.init(config)
    config = config or {}
    logDir = config.dir or logDir
    currentLevel = LEVELS[config.level] or LEVELS.info
    consoleOutput = config.console ~= false
    maxFileSize = (config.maxFileSize or 10) * 1024 * 1024
    maxFiles = config.maxFiles or 5

    if not files.isDir(logDir) then
        files.mkdir(logDir)
    end

    currentFile = M.openLogFile()
    print("[Logger] 初始化完成: dir=" .. logDir .. " level=" .. config.level or "info")
end

--- 打开新的日志文件
function M.openLogFile()
    local timestamp = os.date("%Y%m%d_%H%M%S")
    local filename = logDir .. "game_" .. timestamp .. ".log"

    local f = files.open(filename, "w")
    if not f then
        print("[Logger] 无法创建日志文件: " .. filename)
        return nil
    end

    currentFile = f
    currentFileSize = 0

    -- 写入文件头
    f.write("[=== Game Bot Log Start ===]\n")
    f.write(string.format("[Date] %s\n", os.date("%Y-%m-%d %H:%M:%S")))
    f.write("[Level] " .. (config and config.level or "info") .. "\n")
    f.write("[Screen] " .. device.width .. "x" .. device.height .. "\n")
    f.write("[=== End Header ===]\n\n")

    return f
end

--- 检查日志文件是否需要轮转
function M.checkRotate()
    if not currentFile then return end

    local pos = currentFile.pos()
    if pos and pos >= maxFileSize then
        currentFile.close()

        -- 轮转旧文件
        local timestamp = os.date("%Y%m%d_%H%M%S")
        local oldName = logDir .. "game_" .. timestamp .. ".log"
        local backupName = logDir .. "game_" .. timestamp .. "_old.log"

        if files.exists(oldName) then
            files.copy(oldName, backupName)
        end

        -- 清理超期文件
        M.cleanupOldFiles()

        currentFile = M.openLogFile()
    end
end

--- 清理超期日志文件
function M.cleanupOldFiles()
    local files_list = files.listDir(logDir)
    if not files_list then return end

    local logFiles = {}
    for _, name in ipairs(files_list) do
        if name:find("game_") and name:find(".log$") then
            table.insert(logFiles, name)
        end
    end

    table.sort(logFiles)

    while #logFiles > maxFiles do
        local oldFile = table.remove(logFiles, 1)
        local path = logDir .. oldFile
        if files.exists(path) then
            files.delete(path)
        end
    end
end

--- 写入日志（内部函数）
function M.write(level, msg, ...)
    if LEVELS[level] < currentLevel then return end

    local timestamp = os.date("%H:%M:%S")
    local prefix = string.format("[%s][%s] ", timestamp, level:upper())
    local text = string.format(prefix .. msg, ...)

    -- 控制台输出
    if consoleOutput then
        print(text)
    end

    -- 文件输出
    if currentFile then
        currentFile.write(text .. "\n")
        currentFileSize = currentFileSize + #text + 1
        M.checkRotate()
    end

    -- 缓冲区（最近N条）
    table.insert(logBuffer, text)
    while #logBuffer > maxBufferSize do
        table.remove(logBuffer, 1)
    end
end

--- 调试日志
function M.debug(msg, ...)
    M.write("debug", msg, ...)
end

--- 信息日志
function M.info(msg, ...)
    M.write("info", msg, ...)
end

--- 警告日志
function M.warn(msg, ...)
    M.write("warn", msg, ...)
end

--- 错误日志
function M.error(msg, ...)
    M.write("error", msg, ...)
end

--- 获取最近日志
function M.getRecent(n)
    n = n or 50
    local start = math.max(1, #logBuffer - n + 1)
    local result = {}
    for i = start, #logBuffer do
        table.insert(result, logBuffer[i])
    end
    return result
end

--- 关闭日志系统
function M.close()
    if currentFile then
        currentFile.close()
        currentFile = nil
    end
end

return M
