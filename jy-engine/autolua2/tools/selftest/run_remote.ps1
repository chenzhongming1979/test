# JY-Frame selftest remote runner (ASCII only for PS5 compatibility)
# Usage: powershell -File run_remote.ps1 [-DeviceSerial emulator-5558] [-TimeoutSec 180]
param(
    [string]$DeviceSerial = "emulator-5558",
    [int]$LocalPort = 18080,
    [int]$RemotePort = 8080,
    [string]$ScriptPath = "$PSScriptRoot\main.lua",
    [string]$PushRelPath = "selftest/main.lua",
    [int]$TimeoutSec = 180
)

$ErrorActionPreference = "Stop"
$logFile = "$PSScriptRoot\selftest_run.log"

function Write-Log([string]$msg) {
    $line = "[{0:HH:mm:ss}] {1}" -f (Get-Date), $msg
    Write-Host $line
    Add-Content -Path $logFile -Value $line -Encoding UTF8
}

Set-Content -Path $logFile -Value "=== selftest run $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ===" -Encoding UTF8

# 1) adb forward
adb -s $DeviceSerial forward "tcp:$LocalPort" "tcp:$RemotePort"
Write-Log "adb forward tcp:$LocalPort -> tcp:$RemotePort ($DeviceSerial)"

# 2) websocket connect (bypass system proxy: local socks5 proxy may intercept handshake)
[System.Net.WebRequest]::DefaultWebProxy = $null
$ws = New-Object System.Net.WebSockets.ClientWebSocket
# ClientWebSocket does not honour DefaultWebProxy: force no proxy (local socks5 may be up)
$ws.Options.Proxy = $null
$ct = [System.Threading.CancellationToken]::None
$ws.ConnectAsync([Uri]"ws://127.0.0.1:$LocalPort", $ct).Wait(8000) | Out-Null
if ($ws.State -ne [System.Net.WebSockets.WebSocketState]::Open) {
    throw "WS connect failed, state=$($ws.State)"
}
Write-Log "WS connected: ws://127.0.0.1:$LocalPort"

function Recv-Message([int]$waitMs) {
    $buf = New-Object byte[] 65536
    $ms = New-Object System.IO.MemoryStream
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.ElapsedMilliseconds -lt $waitMs) {
        $seg = New-Object System.ArraySegment[byte] -ArgumentList @(,$buf)
        $task = $ws.ReceiveAsync($seg, $ct)
        if (-not $task.Wait(200)) { continue }
        if ($task.Result.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
            return "__CLOSED__"
        }
        $ms.Write($buf, 0, $task.Result.Count)
        if ($task.Result.EndOfMessage) {
            return [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
        }
    }
    return $null
}

function Send-Message([string]$json) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $seg = New-Object System.ArraySegment[byte] -ArgumentList @(,$bytes)
    $ws.SendAsync($seg, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $ct).Wait(5000) | Out-Null
}

# 3) hello
$hello = Recv-Message 8000
Write-Log "hello: $hello"

# 4) push script
$lua = [System.IO.File]::ReadAllText($ScriptPath, [System.Text.Encoding]::UTF8)
$pushStart = '{"type":"push_files_start","data":{}}'
$payloadObj = @{ path = $PushRelPath; content = $lua; encoding = "utf8" }
$pushFileJson = @{ type = "push_file"; data = $payloadObj } | ConvertTo-Json -Compress -Depth 4
$pushEnd = '{"type":"push_files_end","data":{}}'

Send-Message $pushStart
Send-Message $pushFileJson
Send-Message $pushEnd
Write-Log "pushed: $PushRelPath"

$pr = Recv-Message 10000
Write-Log "push_result: $pr"
if ($pr -eq "__CLOSED__") { throw "connection closed during push" }

# 5) run_script
$relFixed = $PushRelPath.Replace('\', '/')
$run = '{"type":"run_script","data":{"path":"/' + $relFixed + '"}}'
Send-Message $run
Write-Log "run_script sent: $run"

$started = $false
$idle = $false
$sw = [System.Diagnostics.Stopwatch]::StartNew()
while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
    $msg = Recv-Message 2000
    if ($msg -eq "__CLOSED__") { Write-Log "connection closed by device"; break }
    if ($null -eq $msg) { continue }
    if ($msg.IndexOf('"type":"script_started"') -ge 0) {
        $started = $true
        Write-Log "script_started: $msg"
        continue
    }
    if ($msg.IndexOf('"type":"script_state"') -ge 0 -and $msg.IndexOf('"idle"') -ge 0) {
        if ($started) { $idle = $true; Write-Log "script_state: idle (script finished)"; break }
    }
    if ($msg.IndexOf('"type":"log"') -ge 0) {
        if ($msg -match '"line":"((?:[^"\\]|\\.)*)"') {
            $line = $Matches[1] -replace '\\n', " " -replace '\\"', '"' -replace '\\\\', '\'
            Write-Log "L: $line"
        } else {
            Write-Log "log-raw: $msg"
        }
        continue
    }
    if ($msg.IndexOf('"type":"error"') -ge 0) {
        Write-Log "error: $msg"
        continue
    }
}

if (-not $started) { Write-Log "WARN: script_started not received" }
elseif (-not $idle) { Write-Log "WARN: script still running at timeout ($TimeoutSec s)" }

try { $ws.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, "done", $ct).Wait(2000) | Out-Null } catch {}
Write-Log "=== done, log saved to $logFile ==="
