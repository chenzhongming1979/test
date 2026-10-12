# 一键重启 autolua2 app（调试循环用：VSCode 停止后再运行 main 之前先跑这个）
# 原因：引擎 stop_script 后再 run_script 会被忙态拒绝（LuaContext 不复位），
#       且旧脚本实例的 cloud WS server（9300）残留——重启 app 是唯一可靠清场方式
# 用法：.\restart_app.ps1                      # 默认 emulator-5558
#       .\restart_app.ps1 -Device 192.168.1.99:5555
param($Device = "emulator-5558")

Write-Host "[restart] force-stop + pkill ($Device) ..."
adb -s $Device shell am force-stop com.autolua.autolua2
# root 引擎进程独立于 app 进程，force-stop 杀不掉，必须一并 pkill
adb -s $Device shell "su 0 pkill -f com.autolua.autolua2"
Start-Sleep -Seconds 2

Write-Host "[restart] launching app ..."
adb -s $Device shell monkey -p com.autolua.autolua2 -c android.intent.category.LAUNCHER 1

# 轮询 8080 就绪（引擎构建在 onCreate 尾部，冷启动 >15s）
$deadline = (Get-Date).AddSeconds(30)
while ((Get-Date) -lt $deadline) {
    $netstat = adb -s $Device shell netstat -tln 2>$null
    if ("$netstat" -match "8080") {
        Write-Host "[restart] app ready (8080 listening)，可以运行 main 了"
        # 引擎就绪比 8080 晚几秒，缓冲一下，避免 run_script 被未就绪 worker 静默吞掉
        Start-Sleep -Seconds 8
        exit 0
    }
    Start-Sleep -Seconds 1
}
Write-Host "[restart] WARN: 30s 内 8080 未就绪，检查设备状态"
exit 1
