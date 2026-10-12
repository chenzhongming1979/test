# ncnn-ocr plugin one-shot build script (PowerShell 5.1+, ASCII only)
#
# Steps: javac -> d8 -> NDK cmake (libncnnocr.so) -> pack classes.jar (dex + models resources)
#        -> pack plugin zip -> compute content-manifest sha256 -> backfill manifest -> repack
#
# Usage:
#   .\build.ps1                                  # arm64-v8a only
#   .\build.ps1 -ArmV7                           # also build armeabi-v7a
#   .\build.ps1 -X86_64                          # also build x86_64 (dev emulator, see README)
#   .\build.ps1 -NdkDir D:\Android\SDK\ndk\25.2.9519653   # multi-NDK machines: pass explicitly
#   .\build.ps1 -NcnnDir D:\libs\ncnn-android    # custom ncnn prebuilt dir
#
# Full parameter table + x86_64 emulator notes: README.md "构建参数（build.ps1）"
#
# Prereqs:
#   1) engine built once: cd autolua2; gradlew.bat :engine:assembleDebug
#   2) ncnn android prebuilt zip (CPU) extracted to plugins\ncnn-ocr\third_party\ncnn-android
#      layout: include\ncnn\*.h + arm64-v8a\lib\libncnn.a
#   3) models ready in plugins\ncnn-ocr\models (see MODEL_CONVERT.md), optional but recommended
param(
    [string]$SdkDir = "",
    [string]$NdkDir = "",
    [string]$NcnnDir = (Join-Path $PSScriptRoot "third_party\ncnn-android"),
    [switch]$ArmV7,
    [switch]$X86_64,       # also build x86_64 (x86 emulator with arm-translation can't bind translated arm64 jni symbols)
    [switch]$SkipNative    # reuse existing build\jni\<abi>\libncnnocr.so (native step done elsewhere)
)

$ErrorActionPreference = "Continue"   # PS5: 'Stop' turns native stderr (cmake chatter) into bogus terminating errors;
                                      # every native step below checks $LASTEXITCODE and throws explicitly

function Read-Prop($file, $key) {
    if (-not (Test-Path $file)) { return "" }
    foreach ($line in Get-Content $file) {
        $t = $line.Trim()
        if ($t.StartsWith("$key=")) { return $t.Substring($key.Length + 1).Trim() }
    }
    return ""
}

$Root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent   # autolua2/ (two levels up from plugins/ncnn-ocr)
if (-not $SdkDir) {
    $SdkDir = (Read-Prop (Join-Path $Root "local.properties") "sdk.dir") -replace '\\:', ':'   # unescape java properties "G\:/..."
}
if (-not $SdkDir) { throw "sdk.dir not found, pass -SdkDir" }
if (-not $NdkDir) {
    $ndkRoot = Join-Path $SdkDir "ndk"
    if (Test-Path $ndkRoot) {
        $ndk = Get-ChildItem $ndkRoot -Directory | Sort-Object Name | Select-Object -Last 1
        if ($ndk) { $NdkDir = $ndk.FullName }
    }
}
if (-not $NdkDir -or -not (Test-Path $NdkDir)) { throw "NDK not found, pass -NdkDir" }
if (-not (Test-Path $NcnnDir)) { throw "ncnn prebuilt dir not found: $NcnnDir (see MODEL_CONVERT.md step 0)" }

# ---- locate build-tools / cmake / platform ----
$bt = Get-ChildItem (Join-Path $SdkDir "build-tools") -Directory | Sort-Object Name | Select-Object -Last 1
$d8 = Join-Path $bt.FullName "d8.bat"
$cmakeRoot = Get-ChildItem (Join-Path $SdkDir "cmake") -Directory |
    Where-Object { Test-Path (Join-Path $_.FullName "bin\ninja.exe") } |
    Sort-Object Name | Select-Object -Last 1
$cmakeExe = Join-Path $cmakeRoot.FullName "bin\cmake.exe"
$ninjaExe = Join-Path $cmakeRoot.FullName "bin\ninja.exe"
$aj = Join-Path $SdkDir "platforms\android-34\android.jar"
if (-not (Test-Path $aj)) {
    $plat = Get-ChildItem (Join-Path $SdkDir "platforms") -Directory |
        Where-Object { Test-Path (Join-Path $_.FullName "android.jar") } |
        Sort-Object Name | Select-Object -Last 1
    $aj = Join-Path $plat.FullName "android.jar"
}

# ---- locate engine classes.jar (host contract classes) ----
$eng = Join-Path $Root "engine\build\intermediates\compile_library_classes_jar\debug\bundleLibCompileToJarDebug\classes.jar"
if (-not (Test-Path $eng)) {
    $aar = Join-Path $Root "engine\build\outputs\aar\engine-debug.aar"
    if (-not (Test-Path $aar)) { throw "engine classes.jar not found; build :engine:assembleDebug first" }
    $eng = Join-Path $PSScriptRoot "build\engine-classes.jar"
    Copy-Item $aar "$aar.zip" -Force
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $z = [System.IO.Compression.ZipFile]::OpenRead("$aar.zip")
    try {
        $e = $z.Entries | Where-Object { $_.FullName -eq "classes.jar" } | Select-Object -First 1
        $s = $e.Open(); $fs = [System.IO.File]::Create($eng); $s.CopyTo($fs); $fs.Dispose(); $s.Dispose()
    } finally { $z.Dispose() }
    Remove-Item "$aar.zip" -Force
}
Write-Host "[1/7] engine classes.jar: $eng"

# ---- javac ----
$src = Join-Path $PSScriptRoot "src\com\autolua\plugin\ocr\ncnn\NcnnOcrPlugin.java"
$clsDir = Join-Path $PSScriptRoot "build\classes"
if (Test-Path $clsDir) { Remove-Item $clsDir -Recurse -Force }
New-Item -ItemType Directory -Path $clsDir -Force | Out-Null
Write-Host "[2/7] javac..."
javac -source 8 -target 8 -encoding UTF-8 -cp "$aj;$eng" -d $clsDir $src
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# ---- d8 -> classes.dex ----
Write-Host "[3/7] d8..."
$dexDir = Join-Path $PSScriptRoot "build\dex"
if (Test-Path $dexDir) { Remove-Item $dexDir -Recurse -Force }
New-Item -ItemType Directory -Path $dexDir -Force | Out-Null
$clsFiles = (Get-ChildItem $clsDir -Recurse -Filter *.class).FullName
& $d8 --release --min-api 22 --lib $aj --classpath $eng --output $dexDir $clsFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }
$dexFile = Join-Path $dexDir "classes.dex"

# ---- NDK cmake per ABI ----
$abis = @("arm64-v8a")
if ($ArmV7) { $abis += "armeabi-v7a" }
if ($X86_64) { $abis += "x86_64" }
$soMap = @{}
if ($SkipNative) {
    Write-Host "[4/7] NDK build SKIPPED (-SkipNative), reusing existing so..."
    foreach ($abi in $abis) {
        $so = Join-Path $PSScriptRoot "build\jni\$abi\libncnnocr.so"
        if (-not (Test-Path $so)) { throw "libncnnocr.so missing for $abi; run native build first" }
        $soMap[$abi] = $so
        Write-Host "      $abi -> $so"
    }
} else {
    Write-Host "[4/7] NDK build libncnnocr.so..."
    foreach ($abi in $abis) {
        $bdir = Join-Path $PSScriptRoot "build\jni\$abi"
        # PS5: $ErrorActionPreference=Stop turns cmake's stderr chatter into terminating errors;
        # merge 2>&1 and stringify to neutralize, exit code still checked via $LASTEXITCODE
        # 注意：-D 参数一律用括号表达式拼接传值，不用裸 token（-DX=$var）。
        # 实测部分 PS5 环境下裸 token 的变量不展开（cache 里出现字面 "$var"，
        # ANDROID_ABI 变成 "$abi" → toolchain 编译器路径无效 → CMAKE_CXX_COMPILER not set）；
        # 括号表达式在求值阶段拼接，不受参数模式解析影响。
        & $cmakeExe -G Ninja -B $bdir -S (Join-Path $PSScriptRoot "cpp") `
            ("-DCMAKE_MAKE_PROGRAM=" + $ninjaExe) `
            ("-DCMAKE_TOOLCHAIN_FILE=" + $NdkDir + "\build\cmake\android.toolchain.cmake") `
            ("-DANDROID_ABI=" + $abi) -DANDROID_PLATFORM=android-22 -DANDROID_STL=c++_static `
            ("-DNCNN_DIR=" + $NcnnDir) 2>&1 | ForEach-Object { "$_" } | Select-Object -Last 3
        if ($LASTEXITCODE -ne 0) { throw "cmake configure failed: $abi" }
        & $cmakeExe --build $bdir 2>&1 | ForEach-Object { "$_" } | Select-Object -Last 3
        if ($LASTEXITCODE -ne 0) { throw "cmake build failed: $abi" }
        $soMap[$abi] = Join-Path $bdir "libncnnocr.so"
        Write-Host "      $abi -> $($soMap[$abi])"
    }
}

# ---- pack classes.jar (classes.dex + models/* resources) ----
Write-Host "[5/7] pack classes.jar..."
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$modelsDir = Join-Path $PSScriptRoot "models"
$expectedModels = @("det.ncnn.param", "det.ncnn.bin", "rec.ncnn.param", "rec.ncnn.bin", "ppocr_keys_v1.txt")
$modelsOk = $true
foreach ($m in $expectedModels) {
    if (-not (Test-Path (Join-Path $modelsDir $m))) { $modelsOk = $false }
}
if (-not $modelsOk) {
    Write-Warning "models/ incomplete -> classes.jar WITHOUT models (runtime falls back to /data/local/tmp/autolua_ocr_models)"
}

function New-Zip($zipPath, [scriptblock]$fill) {
    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    $zip = [System.IO.Compression.ZipFile]::Open($zipPath, [System.IO.Compression.ZipArchiveMode]::Create)
    try { & $fill $zip } finally { $zip.Dispose() }
}
function Add-FileEntry($zip, $file, $entryName) {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file, $entryName,
        [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
}

$jarPath = Join-Path $PSScriptRoot "build\classes.jar"
New-Zip $jarPath {
    param($zip)
    Add-FileEntry $zip $dexFile "classes.dex"
    if ($modelsOk) {
        foreach ($m in $expectedModels) {
            Add-FileEntry $zip (Join-Path $modelsDir $m) "models/$m"
        }
    }
}

# ---- pack plugin zip + sha256 backfill (two-pass) ----
$manifestPath = Join-Path $PSScriptRoot "manifest.json"
$outZip = Join-Path $PSScriptRoot "build\ncnn-ocr-1.0.0.zip"

function Build-PluginZip {
    New-Zip $outZip {
        param($zip)
        Add-FileEntry $zip $manifestPath "manifest.json"
        Add-FileEntry $zip $jarPath "classes.jar"
        foreach ($abi in $soMap.Keys) {
            Add-FileEntry $zip $soMap[$abi] "lib/$abi/libncnnocr.so"
        }
    }
}

function Get-ContentManifestHash($zipPath) {
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

Write-Host "[6/7] zip + sha256 backfill..."
Build-PluginZip
$hash = Get-ContentManifestHash $outZip
$mf = Get-Content $manifestPath -Raw -Encoding UTF8
$mf = $mf -replace '"sha256"\s*:\s*"[0-9a-f]*"', "`"sha256`": `"$hash`""
# Write with UTF8 no BOM to keep manifest byte-identical style
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($manifestPath, $mf, $utf8NoBom)
Build-PluginZip
$hash2 = Get-ContentManifestHash $outZip
if ($hash2 -ne $hash) { throw "sha256 unstable after repack" }

# ---- 签名（ECDSA P-256，宿主 PluginManager 注入公钥后强制验签）----
Write-Host "[7/7] sign + done"
$keyJson = Join-Path $PSScriptRoot "..\signing\dev-key.json"
if (-not (Test-Path $keyJson)) { throw "signing key not found: $keyJson (generate via plugins\sign-plugin.ps1 -GenerateKey)" }
& (Join-Path $PSScriptRoot "..\sign-plugin.ps1") -Zip $outZip -Key $keyJson
if ($LASTEXITCODE -ne 0) { throw "sign failed" }

Write-Host "done: $outZip"
Write-Host ""
Write-Host "Install:"
Write-Host "  adb push $outZip /data/local/tmp/ncnn-ocr.zip"
Write-Host "  then Lua: plugin.install('/data/local/tmp/ncnn-ocr.zip')"
Write-Host "  or WS:    plugin_push_start / chunk / complete"
Write-Host "Verify:    logcat -s NcnnOcr AutoLuaPlugin; WS ocr command"
Write-Host "NOTE: remove demo plugin first (getOcrPlugin returns the first 'ocr' provider):"
Write-Host "  WS {`"type`":`"plugin_remove`",`"data`":{`"id`":`"com.autolua.plugin.ocr.demo`"}}"
