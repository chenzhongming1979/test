# sign-plugin.ps1 —— 插件包 ECDSA P-256 签名工具（宿主 PluginManager 验签配套）
#
# 用法：
#   生成密钥对（一次性，私钥妥善保管、勿入库）：
#     .\sign-plugin.ps1 -GenerateKey -OutKey ..\signing\dev-key.json
#   给插件 zip 签名（读 zip -> 内容清单 sha256 -> ECDSA 签名 -> manifest.signature -> 重打包）：
#     .\sign-plugin.ps1 -Zip <plugin.zip> -Key <key.json>
#
# 签名对象：内容清单 sha256 的 hex 小写字符串（UTF-8 bytes），与宿主
# PluginManager.verifyAndReadMeta 的 computed 完全一致；manifest.signature 存
# base64(DER ECDSA-Sig-Value)，Java 侧 SHA256withECDSA 直接验。
#
# 内容清单算法（与宿主/ncnn-ocr build.ps1 一致）：除 manifest.json 外全部 file entry
# 按名排序，逐条 "name:size:sha256hex\n" 拼接后整体 sha256。
param(
    [switch]$GenerateKey,
    [string]$OutKey,
    [string]$Zip,
    [string]$Key
)
$ErrorActionPreference = "Stop"

function New-KeyJson {
    # PS5/.NET Framework 无 ECDsa.Create(int)，用 ECCurve 指定 P-256
    $curve = [System.Security.Cryptography.ECCurve]::CreateFromFriendlyName("secp256r1")
    $ec = [System.Security.Cryptography.ECDsa]::Create($curve)
    $p = $ec.ExportParameters($true)   # 含私钥 D（ECParameters.Q.X/Y/D 为定长字节）
    $qx = [BitConverter]::ToString($p.Q.X).Replace('-', '').ToLower()
    $qy = [BitConverter]::ToString($p.Q.Y).Replace('-', '').ToLower()
    $d  = [BitConverter]::ToString($p.D).Replace('-', '').ToLower()
    $ec.Dispose()
    return @{ curve = "secp256r1"; qx = $qx; qy = $qy; d = $d }
}

function Get-KeyJson($path) {
    $j = Get-Content $path -Raw | ConvertFrom-Json
    if ($j.curve -ne "secp256r1") { throw "unsupported curve: $($j.curve)" }
    return $j
}

function ConvertFrom-Hex([string]$hex) {
    $b = New-Object byte[] ($hex.Length / 2)
    for ($i = 0; $i -lt $b.Length; $i++) {
        $b[$i] = [Convert]::ToByte($hex.Substring($i * 2, 2), 16)
    }
    return $b
}

# 内容清单 sha256（hex 小写）：与宿主 verifyAndReadMeta / build.ps1 同算法
function Get-ContentManifestHash($zipPath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $lines = @()
        foreach ($e in ($zip.Entries | Where-Object { $_.Name -ne '' -and $_.FullName -ne 'manifest.json' } | Sort-Object FullName)) {
            $s = $e.Open(); $ms = New-Object System.IO.MemoryStream; $s.CopyTo($ms); $s.Dispose()
            $hex = ([BitConverter]::ToString($sha.ComputeHash($ms.ToArray()))).Replace('-', '').ToLower()
            $ms.Dispose()
            $lines += "$($e.FullName):$($e.Length):$hex"
        }
        $bytes = [Text.Encoding]::UTF8.GetBytes(($lines -join "`n") + "`n")
        return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLower()
    } finally { $zip.Dispose(); $sha.Dispose() }
}

# r||s 裸签名 -> DER ECDSA-Sig-Value（Java SHA256withECDSA 期望格式）
function ConvertTo-DerSig([byte[]]$rs) {
    if ($rs.Length -ne 64) { throw "expect raw r||s 64 bytes, got $($rs.Length)" }
    $r = $rs[0..31]; $s = $rs[32..63]
    function TrimInt([byte[]]$v) {
        $first = 0
        while ($first -lt ($v.Length - 1) -and $v[$first] -eq 0) { $first++ }
        if ($v[$first] -ge 0x80) {
            # 首字节高位为 1，补 0x00 前缀保持正数
            $out = New-Object byte[] ($v.Length - $first + 1)
            $out[0] = 0x00
            [Array]::Copy($v, $first, $out, 1, $v.Length - $first)
            return $out
        }
        $out = New-Object byte[] ($v.Length - $first)
        [Array]::Copy($v, $first, $out, 0, $out.Length)
        return $out
    }
    $rb = TrimInt $r; $sb = TrimInt $s
    $body = New-Object byte[] (2 + $rb.Length + 2 + $sb.Length)
    $body[0] = 0x02; $body[1] = $rb.Length
    [Array]::Copy($rb, 0, $body, 2, $rb.Length)
    $body[2 + $rb.Length] = 0x02; $body[3 + $rb.Length] = $sb.Length
    [Array]::Copy($sb, 0, $body, 4 + $rb.Length, $sb.Length)
    $der = New-Object byte[] (2 + $body.Length)
    $der[0] = 0x30; $der[1] = $body.Length
    [Array]::Copy($body, 0, $der, 2, $body.Length)
    return $der
}

if ($GenerateKey) {
    if (-not $OutKey) { throw "-OutKey required" }
    $k = New-KeyJson
    $dir = Split-Path $OutKey -Parent
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $k | ConvertTo-Json | Set-Content $OutKey -Encoding UTF8
    Write-Host "key generated: $OutKey"
    Write-Host "public (qx,qy) for host injection:"
    Write-Host "  qx=$($k.qx)"
    Write-Host "  qy=$($k.qy)"
    exit 0
}

if ($Zip -and $Key) {
    $k = Get-KeyJson $Key
    $hash = Get-ContentManifestHash $Zip
    $msg = [Text.Encoding]::UTF8.GetBytes($hash)
    $ec = [System.Security.Cryptography.ECDsa]::Create([System.Security.Cryptography.ECCurve]::CreateFromFriendlyName("secp256r1"))
    # ECPoint/ECParameters 是值类型：必须整体赋值，$p.Q.X = ... 的嵌套赋值改的是副本（PS 值类型语义）
    $q = New-Object System.Security.Cryptography.ECPoint
    $q.X = ConvertFrom-Hex $k.qx
    $q.Y = ConvertFrom-Hex $k.qy
    $p = New-Object System.Security.Cryptography.ECParameters
    $p.Curve = [System.Security.Cryptography.ECCurve]::CreateFromFriendlyName("secp256r1")
    $p.Q = $q
    $p.D = ConvertFrom-Hex $k.d
    $ec.ImportParameters($p)
    $rs = $ec.SignData($msg, [System.Security.Cryptography.HashAlgorithmName]::SHA256)
    $der = ConvertTo-DerSig $rs
    $sigB64 = [Convert]::ToBase64String($der)
    $ec.Dispose()

    # 读 manifest -> 注入 signature -> 重打包（其余 entry 原样搬运）
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $src = [System.IO.Compression.ZipFile]::OpenRead($Zip)
    $tmp = "$Zip.signing"
    $out = [System.IO.Compression.ZipFile]::Open($tmp, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $mf = $null
        foreach ($e in $src.Entries) {
            if ($e.FullName -eq 'manifest.json') {
                $sr = New-Object IO.StreamReader($e.Open(), [Text.Encoding]::UTF8)
                $mf = $sr.ReadToEnd(); $sr.Dispose()
                continue
            }
            $ne = $out.CreateEntry($e.FullName)
            $s = $e.Open(); $d = $ne.Open(); $s.CopyTo($d); $s.Dispose(); $d.Dispose()
        }
        if (-not $mf) { throw "manifest.json not found in $Zip" }
        # JSON 注入 signature 字段（幂等：替换已有或插入首个 { 后）
        $mf = $mf -replace '"signature"\s*:\s*"[^"]*"\s*,?', ''
        $mf = $mf -replace '^\s*\{', "{`n  `"signature`": `"$sigB64`","
        $ne = $out.CreateEntry('manifest.json')
        $w = New-Object IO.StreamWriter($ne.Open(), (New-Object Text.UTF8Encoding($false)))
        $w.Write($mf); $w.Dispose()
    } finally { $out.Dispose(); $src.Dispose() }
    Move-Item $tmp $Zip -Force
    Write-Host "signed: $Zip"
    Write-Host "  content hash: $hash"
    Write-Host "  signature   : $sigB64"
    exit 0
}

throw "usage: -GenerateKey -OutKey <json> | -Zip <zip> -Key <json>"
