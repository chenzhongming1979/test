# build app and report real exit code + apk timestamp
$ErrorActionPreference = "Continue"
Set-Location "D:\Project\script\lrjl\jy-frame-lrjl\jy-frame-engine\autolua2"

$p = Start-Process -FilePath ".\gradlew.bat" `
    -ArgumentList ":app:assembleDebug", "-x", "downloadMLN", "--console=plain" `
    -RedirectStandardOutput "build\assemble_out.txt" `
    -RedirectStandardError "build\assemble_err.txt" `
    -Wait -PassThru -NoNewWindow
Write-Host "gradle exit code: $($p.ExitCode)"

$tail = Get-Content "build\assemble_out.txt" -Tail 8 -ErrorAction SilentlyContinue
Write-Host "--- last 8 lines of build output ---"
$tail | ForEach-Object { Write-Host $_ }

$apk = "app\build\outputs\apk\debug\autolua2_0.0.1.apk"
if (Test-Path $apk) {
    $t = (Get-Item $apk).LastWriteTime
    Write-Host "APK: $apk  time=$($t.ToString('HH:mm:ss'))  size=$((Get-Item $apk).Length)"
} else {
    Write-Host "APK NOT FOUND"
}
