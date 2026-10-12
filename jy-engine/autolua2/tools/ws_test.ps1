# WS protocol stack auto test (capabilities + stream binary frames)
param(
    [string]$Url = "ws://127.0.0.1:18080"
)
$ErrorActionPreference = "Stop"

$client = New-Object System.Net.WebSockets.ClientWebSocket
$client.Options.KeepAliveInterval = [TimeSpan]::Zero
$client.Options.Proxy = New-Object System.Net.WebProxy   # bypass system proxy
$ct = New-Object System.Threading.CancellationToken($false)
try {
    $client.ConnectAsync([Uri]$Url, $ct).Wait()
    Write-Host "[OK] WebSocket connected: $Url (state=$($client.State))"
} catch {
    Write-Host "[FAIL] connect error:"
    $ex = $_.Exception
    while ($ex -ne $null) {
        Write-Host "  - $($ex.GetType().Name): $($ex.Message)"
        $ex = $ex.InnerException
    }
    exit 1
}

function Send-Json($obj) {
    $json = $obj | ConvertTo-Json -Compress -Depth 5
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $seg = [ArraySegment[byte]]::new($bytes)
    $client.SendAsync($seg, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $ct).Wait()
}

function Recv-Message($timeoutSec = 8) {
    $buffer = New-Object byte[] (2 * 1024 * 1024)
    $sb = [System.Text.StringBuilder]::new()
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $timeoutSec) {
        $seg = [ArraySegment[byte]]::new($buffer)
        $task = $client.ReceiveAsync($seg, $ct)
        if (-not $task.Wait(2000)) { continue }
        $r = $task.Result
        if ($r.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
            return @{ type = "CLOSED" }
        }
        $chunk = $buffer[0..($r.Count - 1)]
        if ($r.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Binary) {
            return @{ type = "BINARY"; len = $r.Count; head = ($chunk[0..([Math]::Min(15, $r.Count-1))] | ForEach-Object { $_.ToString("X2") }) -join " " }
        }
        [void]$sb.Append([System.Text.Encoding]::UTF8.GetString($chunk, 0, $r.Count))
        if ($r.EndOfMessage) {
            return @{ type = "TEXT"; text = $sb.ToString() }
        }
    }
    return @{ type = "TIMEOUT" }
}

# 1. capabilities
Send-Json @{ type = "capabilities"; data = @{} }
$r = Recv-Message
Write-Host "[capabilities] $($r.type): $($r.text)"

# 2. ping
Send-Json @{ type = "ping"; data = @{} }
$r = Recv-Message
Write-Host "[ping] $($r.type): $($r.text)"

# 3. stream_start jpeg 5fps (binary frame test)
Send-Json @{ type = "stream_start"; data = @{ codec = "jpeg"; fps = 5; quality = 50; scale = 1.0 } }
$r = Recv-Message
Write-Host "[stream_start] $($r.type): $($r.text)"

# receive 3 binary frames
for ($i = 1; $i -le 3; $i++) {
    $r = Recv-Message 10
    if ($r.type -eq "BINARY") {
        Write-Host "[frame $i] BINARY $($r.len) bytes, head16: $($r.head)"
    } else {
        Write-Host "[frame $i] $($r.type): $($r.text)"
    }
}

# 4. stop stream
Send-Json @{ type = "stream_stop"; data = @{} }
$r = Recv-Message
Write-Host "[stream_stop] $($r.type): $($r.text)"

$client.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, "done", $ct).Wait()
Write-Host "[DONE] test finished"
