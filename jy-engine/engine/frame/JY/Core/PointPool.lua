--[[
Copyright (c) 2026 成都林深见鹿网络科技有限公司 All Rights Reserved.

本代码基于 Apache License 2.0 协议开源：
1. 个人学习、非商业用途：可免费使用、修改、分发本代码；
2. 商业用途、企业使用：必须联系作者获取书面授权后方可使用；
3. 任何使用场景下，禁止删除本版权声明及相关协议说明；
4. 若进行二次开发/衍生，需在衍生作品的显著位置保留本版权声明，并标注衍生说明。

你可以获取 Apache License 2.0 完整协议文本：
http://www.apache.org/licenses/LICENSE-2.0
]]

--- PointPool - Point对象池
--- 功能：复用Point对象，减少内存分配和GC压力
--- 使用：PointPool.acquire() 获取对象，PointPool.release() 归还对象

local PointPool = {}
PointPool.__index = PointPool

-- 池配置
local MAX_POOL_SIZE = 500  -- 最大池容量
local pool = {}            -- 池存储
local poolSize = 0         -- 当前池大小

-- 统计信息（用于调试）
local stats = {
    acquired = 0,   -- 获取次数
    released = 0,   -- 归还次数
    created = 0     -- 创建次数
}

--- 从池中获取Point对象
--- @param x number|nil x坐标，默认-1
--- @param y number|nil y坐标，默认-1
--- @param f boolean|nil 是否浮动，默认true
--- @param idx number|nil 索引，默认1
--- @param ap number|nil 锚点，默认5
--- @return table Point对象 {x, y, idx, ap}
function PointPool.acquire(x, y,f, idx, ap)
    stats.acquired = stats.acquired + 1

    local point
    if poolSize > 0 then
        -- 从池中取出
        point = pool[poolSize]
        pool[poolSize] = nil
        poolSize = poolSize - 1
    else
        -- 池为空，创建新对象
        point = {}
        stats.created = stats.created + 1
    end

    -- 设置属性
    point.x = x or -1
    point.y = y or -1
    point.idx = idx or 1
    point.ap = ap or 5
    if f == nil then
        point.f = true
    else
        point.f = f
    end
    return point
end

--- 将Point对象归还到池中
--- @param point table Point对象
function PointPool.release(point)
    if not point then return end

    stats.released = stats.released + 1

    if poolSize < MAX_POOL_SIZE then
        -- 清空属性，防止内存泄漏
        point.x = nil
        point.y = nil
        point.idx = nil
        point.ap = nil
        point.f = nil
        poolSize = poolSize + 1
        pool[poolSize] = point
    end
    -- 超过容量则丢弃，让GC回收
end

--- 批量释放Point对象
--- @param points table Point对象数组
function PointPool.releaseAll(points)
    if type(points) ~= "table" then return end

    for i = #points, 1, -1 do
        PointPool.release(points[i])
        points[i] = nil
    end
end

--- 清空对象池
function PointPool.clear()
    for i = 1, poolSize do
        pool[i] = nil
    end
    poolSize = 0
end

--- 获取统计信息
--- @return table 统计数据
function PointPool.getStats()
    return {
        acquired = stats.acquired,
        released = stats.released,
        created = stats.created,
        poolSize = poolSize,
        reuseRate = stats.acquired > 0 and
            string.format("%.2f%%", (stats.acquired - stats.created) / stats.acquired * 100) or "0%"
    }
end

--- 重置统计信息
function PointPool.resetStats()
    stats.acquired = 0
    stats.released = 0
    stats.created = 0
end

return PointPool
