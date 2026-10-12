-- exitScript 独立验证：调用后脚本应立即终止（后续 print 不出现），引擎回 Idle
print("EX:0 start")
print("EX:1 before exitScript")
exitScript()
print("EX:2 SHOULD NOT APPEAR")
