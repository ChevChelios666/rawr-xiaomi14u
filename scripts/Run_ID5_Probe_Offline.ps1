# X14U Camera Lab: isolated Camera ID5 YUV+RAW probe.
# Windows PowerShell 5.1, no root, no working-lens modifications.
# Run via RUN.cmd from the same extracted directory as the APK.
param([string]$AdbPath = 'C:\Android\platform-tools\adb.exe')
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$expectedApkHash = '3ff22cb98c35ae8ac5b355eebe65976a1804e7596a7b3af63f60792936c66b04'
$apk = Join-Path $PSScriptRoot 'x5-probe-debug.apk'
$package = 'com.rawr.camera.x14u.x5yuvprobe'
$activity = 'com.rawr.x14u.probe.X5YuvRawProbeActivity'
$desktop = [Environment]::GetFolderPath('Desktop')
$work = Join-Path $desktop ('X14U_ID5_YUVRAW_' + (Get-Date -Format 'yyyyMMdd_HHmmss'))
$zip = $work + '_ALL.zip'

if (!(Test-Path -LiteralPath $apk -PathType Leaf)) { throw 'APK is missing. Extract the ENTIRE ZIP first.' }
$actualHash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualHash -ne $expectedApkHash) { throw 'APK SHA256 mismatch. Refusing installation.' }
if (!(Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    $adbCommand = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($null -eq $adbCommand) { throw 'ADB not found: C:\Android\platform-tools\adb.exe' }
    $AdbPath = $adbCommand.Source
}

$deviceLines = @(& $AdbPath devices)
if ($LASTEXITCODE -ne 0) { throw 'adb devices failed' }
$online = @($deviceLines | ForEach-Object {
    $m = [regex]::Match($_, '^([^\s]+)\s+device\s*$')
    if ($m.Success) { $m.Groups[1].Value }
})
if ($online.Count -ne 1) {
    throw ('Expected one authorized ADB device, found ' + $online.Count + '. Connect Wireless debugging first.')
}
$serial = $online[0]
$deviceName = ((& $AdbPath -s $serial shell getprop ro.product.device) | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $deviceName -ne 'aurora') { throw ('Wrong device or ADB error: ' + $deviceName) }

New-Item -ItemType Directory -Path $work -ErrorAction Stop | Out-Null
Write-Host ('Device: ' + $serial + ' (' + $deviceName + ')') -ForegroundColor Cyan
Write-Host ('APK hash verified: ' + $expectedApkHash.Substring(0,12)) -ForegroundColor Green
& $AdbPath -s $serial shell getprop ro.build.fingerprint |
    Out-File -LiteralPath (Join-Path $work 'firmware.txt') -Encoding ASCII

Write-Host 'INSTALLING ISOLATED PROBE APK' -ForegroundColor Cyan
& $AdbPath -s $serial install -r -t $apk
if ($LASTEXITCODE -ne 0) {
    throw 'Install failed. Approve USB/Wireless install prompt on phone if shown; then rerun RUN.cmd.'
}
$installed = @(& $AdbPath -s $serial shell pm path $package)
if ($LASTEXITCODE -ne 0 -or !($installed -match '^package:')) {
    throw 'Android package install verification failed'
}
$installed | Out-File -LiteralPath (Join-Path $work 'installed_package.txt') -Encoding ASCII

Write-Host 'CAPTURING CAMERA BASELINE' -ForegroundColor Cyan
& $AdbPath -s $serial shell dumpsys media.camera |
    Out-File -LiteralPath (Join-Path $work 'camera_before.txt') -Encoding UTF8
Write-Host 'STARTING PROBE ON PHONE' -ForegroundColor Cyan
$component = $package + '/' + $activity
& $AdbPath -s $serial shell am start -n $component
if ($LASTEXITCODE -ne 0) { throw 'Unable to open probe activity' }

Write-Host ''
Write-Host '========== CAMERA ID 5 : ONE CONTROLLED TEST ==========' -ForegroundColor Yellow
Write-Host 'PHONE: Tap 1. START ONCE, then ALLOW CAMERA permission.' -ForegroundColor Yellow
Write-Host 'IF FAIL: DO NOT RETRY. Return here and press ENTER.' -ForegroundColor Yellow
Write-Host 'IF PREVIEW_RUNNING: Tap 2. CAPTURE ONE RAW.' -ForegroundColor Yellow
Write-Host 'Wait for RAW_SAVE_OK (100 MB) or FAIL; then press ENTER here.' -ForegroundColor Yellow
Write-Host '=======================================================' -ForegroundColor Yellow
Read-Host 'Press ENTER only after the phone displays RAW_SAVE_OK or FAIL' | Out-Null

Write-Host 'SAVING LOGS AND CAMERA STATE' -ForegroundColor Cyan
& $AdbPath -s $serial shell dumpsys media.camera |
    Out-File -LiteralPath (Join-Path $work 'camera_after.txt') -Encoding UTF8
& $AdbPath -s $serial logcat -d -v threadtime |
    Out-File -LiteralPath (Join-Path $work 'logcat_full.txt') -Encoding UTF8
if ($LASTEXITCODE -ne 0) { throw 'ADB logcat collection failed' }
Get-Content -LiteralPath (Join-Path $work 'logcat_full.txt') |
    Where-Object { $_ -match 'X14U5XProbe|CameraService|Camera3-Device|CameraCaptureSession|CHI|configureStreams|ImageReader|QCamera' } |
    Set-Content -LiteralPath (Join-Path $work 'logcat_filtered.txt') -Encoding UTF8

$remote = '/sdcard/Download/X14U_X5_YUV_PROBE'
try {
    $list = @(& $AdbPath -s $serial shell ls -la $remote 2>&1)
} catch {
    $list = @('Device sample directory inaccessible: ' + $_.Exception.Message)
}
$list | Out-File -LiteralPath (Join-Path $work 'samples_on_device.txt') -Encoding UTF8
if ($list -match '\.raw16le|\.json|_FAIL_') {
    try {
        & $AdbPath -s $serial pull $remote (Join-Path $work 'samples')
        if ($LASTEXITCODE -ne 0) { Write-Host 'RAW pull failed. Sending logs anyway.' -ForegroundColor Yellow }
    } catch {
        Write-Host ('RAW pull error; logs preserved: ' + $_.Exception.Message) -ForegroundColor Yellow
    }
} else {
    Write-Host 'No sample files found; sending logs and session status.' -ForegroundColor Yellow
}
$captures = @(Get-ChildItem -LiteralPath $work -Recurse -File -Filter '*.raw16le')
$expectedRawBytes = 100663296L
foreach ($capture in $captures) {
    if ($capture.Length -eq $expectedRawBytes) {
        Write-Host ('RAW LENGTH OK: ' + $capture.Name) -ForegroundColor Green
    } else {
        Write-Host ('RAW LENGTH WARNING: ' + $capture.Name + ' has ' + $capture.Length + ' bytes') -ForegroundColor Yellow
    }
}
$readme = @(
    'X14U Camera Lab ID5 YUV+RAW controlled probe',
    'Android app: com.rawr.camera.x14u.x5yuvprobe',
    'GitHub source commit: ab7982c67ed193d44a725ea59fdbb7b2cd5e6fdd',
    ('ADB device: ' + $serial),
    'Camera ID 5, opmode 0x900D, YUV 2560x1920 repeating, RAW_SENSOR 8192x6144 still',
    'RESULT: compare logs + actual buffer; reader and request alone do not prove full Bayer.'
)
$readme | Set-Content -LiteralPath (Join-Path $work 'README.txt') -Encoding ASCII
Compress-Archive -Path (Join-Path $work '*') -DestinationPath $zip -CompressionLevel Optimal -ErrorAction Stop
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($zip)
try { if ($archive.Entries.Count -lt 5) { throw 'Final ZIP validation failed' } }
finally { $archive.Dispose() }
Write-Host ('ALL DONE: ' + $zip) -ForegroundColor Green
Write-Host 'Send exactly this ALL.zip to ChatGPT Camera Lab.' -ForegroundColor Green
