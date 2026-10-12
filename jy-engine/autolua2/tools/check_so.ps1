# extract libengine.so from apk and check JNI symbol
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apk = "D:\Project\script\lrjl\jy-frame-lrjl\jy-frame-engine\autolua2\app\build\outputs\apk\debug\autolua2_0.0.1.apk"
$zip = [System.IO.Compression.ZipFile]::OpenRead($apk)

# list native libs
$libs = $zip.Entries | Where-Object { $_.FullName -like "lib/*.so" -and $_.Name -like "*engine*" }
Write-Host "engine libs in apk:"
$libs | ForEach-Object { Write-Host ("  {0}  {1} bytes" -f $_.FullName, $_.Length) }

foreach ($e in $libs) {
    $s = $e.Open()
    $ms = New-Object System.IO.MemoryStream
    $s.CopyTo($ms)
    $bytes = $ms.ToArray()
    $ms.Close(); $s.Close()
    $str = [System.Text.Encoding]::ASCII.GetString($bytes)
    $hasMap = $str.Contains("mapFdToByteBuffer")
    $hasSetInfo = $str.Contains("nativeSetSharedMemoryInfo")
    Write-Host ("  {0}: mapFdToByteBuffer={1} nativeSetSharedMemoryInfo={2}" -f $e.FullName, $hasMap, $hasSetInfo)
}
$zip.Dispose()
