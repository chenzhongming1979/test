-- 云控版最小 Funs：TaskRunner 装载依赖（require("Funs")）
-- FunRegistry 由 JY.Utils.TaskUtils 提供；云控模式下自定义函数（hfun/执行方法）
-- 经 cloud 扩展 op 动态注册，业务函数（loginBack/账号体系等）按需在此补注册
local jy_task = require("JY.Utils.TaskUtils")

-- 示例：注册一个云控自检用的执行方法
jy_task.FunRegistry.addFun("cloudPing", function()
    print("[cloud] FunRegistry.cloudPing 触发")
    return 1
end)

return true
