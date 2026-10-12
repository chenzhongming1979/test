# raw TCP websocket handshake probe - print exact response bytes
param([string]$Host_ = "127.0.0.1", [int]$Port = 18080)
$ErrorActionPreference = "Stop"

$tcp = New-Object System.Net.Sockets.TcpClient
$tcp.Connect($Host_, $Port)
$stream = $tcp.GetStream()
$stream.ReadTimeout = 5000

$req = "GET / HTTP/1.1`r`n" +
       "Host: $Host_`:$Port`r`n" +
       "Upgrade: websocket`r`n" +
       "Connection: Upgrade`r`n" +
       "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==`r`n" +
       "Sec-WebSocket-Version: 13`r`n" +
       "`r`n"
$bytes = [System.Text.Encoding]::ASCII.GetBytes($req)
$stream.Write($bytes, 0, $bytes.Length)
$stream.Flush()

Start-Sleep -Milliseconds 800
$buf = New-Object byte[] 8192
$n = $stream.Read($buf, 0, $buf.Length)
Write-Host "--- raw response ($n bytes) ---"
$text = [System.Text.Encoding]::ASCII.GetString($buf, 0, [Math]::Min($n, 600))
Write-Host $text
Write-Host "--- end ---"
$tcp.Close()
