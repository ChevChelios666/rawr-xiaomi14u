# X14U Camera Lab - read-only installed PhotonVidCam APK collector
# Tested by GitHub Actions Windows PowerShell 5.1 parser in the companion CI workflow.
# No root, no install/uninstall, no deletion. Preserve original APK and settings.
param(
    [string]$AdbPath = 'C:\Android\platform-tools\adb.exe',
    [string]$PackageName = 'com.xiaomi.module.evaluation'
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Fail([string]$message) { throw $message }
function Write-String([string]$path, [string]$value) {
    [System.IO.File]::WriteAllText($path, $value, (New-Object System.Text.UTF8Encoding($false)))
}

$desktop = [Environment]::GetFolderPath('Desktop')
if (-not (Test-Path -LiteralPath $desktop -PathType Container)) { Fail "Desktop missing: $desktop" }
if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    $cmd = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($null -eq $cmd) { Fail 'ADB missing. Expected C:\Android\platform-tools\adb.exe or adb.exe in PATH.' }
    $AdbPath = $cmd.Source
}
if ($PackageName -ne 'com.xiaomi.module.evaluation') { Fail 'Only the logged PhotonVidCam Xiaomi package is supported.' }

$devicesText = @(& $AdbPath devices)
if ($LASTEXITCODE -ne 0) { Fail 'adb devices failed' }
$serials = @(
    $devicesText | ForEach-Object {
        if ($_ -match '^([^\s]+)\s+device\s*$') { $Matches[1] }
    }
)
if ($serials.Count -ne 1) {
    $devicesText | ForEach-Object { Write-Host $_ }
    Fail "Expected exactly one authorized device; found $($serials.Count). Connect Wireless Debugging first."
}
$serial = [string]$serials[0]
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$work = Join-Path $desktop "X14U_PVC_EXACT_APK_$stamp"
$apkFolder = Join-Path $work 'apk'
New-Item -ItemType Directory -Path $apkFolder -Force | Out-Null

Write-Host "DEVICE: $serial" -ForegroundColor Cyan
Write-Host "PACKAGE: $PackageName" -ForegroundColor Cyan
Write-Host 'Reading package paths only. No camera shots or app changes.'

$pathText = @(& $AdbPath -s $serial shell pm path $PackageName)
if ($LASTEXITCODE -ne 0) { Fail 'adb shell pm path failed; check installed package and connection' }
$remoteApks = @(
    $pathText | ForEach-Object {
        if ($_.Trim() -match '^package:(/.+\.apk)$') { $Matches[1] }
    } | Select-Object -Unique
)
if ($remoteApks.Count -eq 0) {
    Write-Host ($pathText -join "`n")
    Fail 'No installed APK for com.xiaomi.module.evaluation. Source is not available from this phone.'
}
Write-String (Join-Path $work 'package_paths.txt') (($pathText -join "`n") + "`n")

$packageDump = @(& $AdbPath -s $serial shell dumpsys package $PackageName)
if ($LASTEXITCODE -ne 0) { Fail 'dumpsys package failed' }
Write-String (Join-Path $work 'package_dump.txt') (($packageDump -join "`n") + "`n")
$deviceInfo = @(
    "adb_serial=$serial"
    "device=$((& $AdbPath -s $serial shell getprop ro.product.device) -join ' ')"
    "firmware=$((& $AdbPath -s $serial shell getprop ro.build.version.incremental) -join ' ')"
)
Write-String (Join-Path $work 'device_info.txt') (($deviceInfo -join "`n") + "`n")

$records = @()
$i = 0
foreach ($remotePath in $remoteApks) {
    $i++
    $basename = [System.IO.Path]::GetFileName($remotePath)
    $safeName = '{0:D2}_{1}' -f $i, $basename
    $localPath = Join-Path $apkFolder $safeName
    Write-Host "Pulling $i/$($remoteApks.Count): $basename" -ForegroundColor Cyan
    & $AdbPath -s $serial pull $remotePath $localPath
    if ($LASTEXITCODE -ne 0) { Fail "ADB pull failed: $basename. Files already pulled remain in $work." }
    if (-not (Test-Path -LiteralPath $localPath -PathType Leaf)) { Fail "Missing pulled file: $localPath" }
    $info = Get-Item -LiteralPath $localPath
    if ($info.Length -lt 1024) { Fail "Pulled APK unexpectedly small: $safeName" }
    $hash = (Get-FileHash -LiteralPath $localPath -Algorithm SHA256).Hash
    $records += [pscustomobject]@{
        file = "apk/$safeName"
        remote = $remotePath
        bytes = $info.Length
        sha256 = $hash
    }
}

$manifest = [ordered]@{
    description = 'Installed PhotonVidCam Xiaomi APK snapshots; read-only collection'
    collectedAt = (Get-Date).ToString('yyyy-MM-ddTHH:mm:ssK')
    packageName = $PackageName
    device = $serial
    apkCount = $records.Count
    files = @($records)
}
Write-String (Join-Path $work 'manifest.json') ($manifest | ConvertTo-Json -Depth 8)
$shaLines = @($records | ForEach-Object { "$($_.sha256)  $($_.file)" })
Write-String (Join-Path $work 'SHA256SUMS.txt') (($shaLines -join "`n") + "`n")

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zipPath = Join-Path $desktop "X14U_PVC_EXACT_APK_$stamp.zip"
[System.IO.Compression.ZipFile]::CreateFromDirectory($work, $zipPath, [System.IO.Compression.CompressionLevel]::Optimal, $false)
if (-not (Test-Path -LiteralPath $zipPath -PathType Leaf)) { Fail 'ZIP creation failed' }
$zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    foreach ($record in $records) {
        if ($null -eq $zip.GetEntry([string]$record.file)) { Fail "Missing ZIP entry: $($record.file)" }
    }
    if ($null -eq $zip.GetEntry('manifest.json')) { Fail 'ZIP missing manifest.json' }
} finally {
    $zip.Dispose()
}
Write-Host "SUCCESS: $zipPath" -ForegroundColor Green
Write-Host "APKs: $($records.Count)" -ForegroundColor Green
Write-Host 'No installed apps or user data modified.' -ForegroundColor Green
