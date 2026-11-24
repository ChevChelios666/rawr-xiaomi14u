# X14U Camera Lab: one-shot build/install/capture for isolated ID5 YUV+RAW probe.
# Windows PowerShell 5.1. No root. Does not modify working RAWR or existing images.
# IMPORTANT: Do not execute again if CameraService rejects the session unchanged.
param(
    [string]$AdbPath = 'C:\Android\platform-tools\adb.exe'
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$repo = 'ChevChelios666/rawr-xiaomi14u'
$branch = 'lab/5x-yuv-raw-still-probe-20261124'
$expectedSha = ''
$package = 'com.rawr.camera.x14u.x5yuvprobe'
$activity = 'com.rawr.x14u.probe.X5YuvRawProbeActivity'
$desktop = [Environment]::GetFolderPath('Desktop')
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$work = Join-Path $desktop ('X14U_ID5_YUVRAW_' + $stamp)
$zip = $work + '_ALL.zip'

if (!(Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    $found = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($null -eq $found) { throw 'adb.exe not found. Check C:\Android\platform-tools\adb.exe' }
    $AdbPath = $found.Source
}
$ghCmd = Get-Command gh.exe -ErrorAction SilentlyContinue
if ($null -eq $ghCmd) { throw 'GitHub CLI (gh.exe) is missing; existing login is required.' }
$gh = $ghCmd.Source
$apiRef = 'repos/' + $repo + '/git/ref/heads/' + $branch
$shaOutput = & $gh api $apiRef --jq '.object.sha'
if ($LASTEXITCODE -ne 0) { throw 'Cannot fetch branch HEAD from GitHub' }
$expectedSha = ($shaOutput | Out-String).Trim()
if ($expectedSha -notmatch '^[0-9a-f]{40}$') { throw 'Invalid GitHub HEAD SHA' }
New-Item -ItemType Directory -Path $work -ErrorAction Stop | Out-Null

Write-Host 'STEP 1/5: EXACT COMMIT BUILD CHECK' -ForegroundColor Cyan
$runText = & $gh run list --repo $repo --branch $branch --limit 30 --json 'databaseId,headSha,status,conclusion,name'
if ($LASTEXITCODE -ne 0) { throw 'gh run list failed; run: gh auth status' }
$runs = @($runText | ConvertFrom-Json)
$runMatches = @($runs | Where-Object { $_.headSha -eq $expectedSha -and $_.name -eq 'X14U ID5 Isolated YUV RAW Probe' })
if ($runMatches.Count -lt 1) { throw 'No workflow run for expected commit. Review GitHub Actions.' }
$run = $runMatches[0]
if ($run.status -ne 'completed') {
    & $gh run watch $run.databaseId --repo $repo --exit-status
    if ($LASTEXITCODE -ne 0) { throw 'GitHub build failed. Do not install an APK.' }
} elseif ($run.conclusion -ne 'success') {
    throw ('GitHub build has status: ' + $run.conclusion)
}
$artifact = 'x14u-x5-yuvraw-probe-' + $expectedSha
& $gh run download $run.databaseId --repo $repo --name $artifact --dir $work
if ($LASTEXITCODE -ne 0) { throw 'Artifact download failed' }
$apks = @(Get-ChildItem -LiteralPath $work -Recurse -File -Filter '*.apk')
if ($apks.Count -ne 1 -or $apks[0].Length -lt 10000) { throw 'APK count/size check failed' }
$apk = $apks[0].FullName

Write-Host 'STEP 2/5: ADB DEVICE AND APK IDENTITY' -ForegroundColor Cyan
$deviceLines = @(& $AdbPath devices)
if ($LASTEXITCODE -ne 0) { throw 'adb devices failed' }
$online = @($deviceLines | ForEach-Object {
    $m = [regex]::Match($_, '^([^\s]+)\s+device\s*$')
    if ($m.Success) { $m.Groups[1].Value }
})
if ($online.Count -ne 1) { throw ('Need exactly one ONLINE ADB device. Found ' + $online.Count + '. Run adb devices.') }
$serial = $online[0]
$buildToolsDir = 'C:\Android\build-tools'
$aapt = @(Get-ChildItem -LiteralPath $buildToolsDir -Recurse -File -Filter 'aapt.exe' -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1)
if ($aapt.Count -gt 0) {
    $aaptExe = $aapt[0].FullName
    $badge = @(& $aaptExe dump badging $apk)
    if ($LASTEXITCODE -ne 0 -or !($badge -match [regex]::Escape("name='$package'"))) {
        throw 'APK package identity does not match the isolated probe. Refusing install.'
    }
} else {
    Write-Host 'Local aapt missing. CI already verified APK identity.' -ForegroundColor Yellow
}
& $AdbPath -s $serial shell getprop ro.product.device | Out-File -LiteralPath (Join-Path $work 'device.txt') -Encoding ASCII
& $AdbPath -s $serial shell getprop ro.build.fingerprint | Out-File -LiteralPath (Join-Path $work 'firmware.txt') -Encoding ASCII
$deviceId = (Get-Content -LiteralPath (Join-Path $work 'device.txt') -Raw).Trim()
if ($deviceId -ne 'aurora') { throw ('Unexpected device: ' + $deviceId) }
Get-FileHash -LiteralPath $apk -Algorithm SHA256 | Format-List | Out-File (Join-Path $work 'apk_sha256.txt') -Encoding ASCII

Write-Host 'STEP 3/5: INSTALL AND START LOGGING' -ForegroundColor Cyan
& $AdbPath -s $serial install -r -t $apk
if ($LASTEXITCODE -ne 0) { throw 'ADB install failed. On the phone, enable install via USB/wireless debugging if prompted.' }
& $AdbPath -s $serial shell pm path $package | Out-File -LiteralPath (Join-Path $work 'installed_package.txt') -Encoding ASCII
if (!(Select-String -Path (Join-Path $work 'installed_package.txt') -Pattern '^package:' -Quiet)) { throw 'Probe package not installed' }
& $AdbPath -s $serial shell dumpsys media.camera | Out-File -LiteralPath (Join-Path $work 'camera_before.txt') -Encoding UTF8
& $AdbPath -s $serial shell am start -n ($package + '/' + $activity)
if ($LASTEXITCODE -ne 0) { throw 'Failed to open probe activity' }

Write-Host ''
Write-Host '=================== PHONE TEST ===================' -ForegroundColor Yellow
Write-Host '1. In X14U 5x YUV+RAW Probe press: 1. START ONCE.' -ForegroundColor Yellow
Write-Host '2. Allow CAMERA access. If FAIL appears, DO NOT RETRY.' -ForegroundColor Yellow
Write-Host '3. If PREVIEW_RUNNING appears, press 2. CAPTURE ONE RAW.' -ForegroundColor Yellow
Write-Host '4. Wait for RAW_SAVE_OK (may write ~100 MB) OR FAIL.' -ForegroundColor Yellow
Write-Host '5. Return to this PowerShell window and press ENTER.' -ForegroundColor Yellow
Write-Host '==================================================' -ForegroundColor Yellow
Read-Host 'Press ENTER when test finished (do not close phone app while saving)' | Out-Null

Write-Host 'STEP 4/5: FETCH LOGS AND ANY RAW BUFFER' -ForegroundColor Cyan
& $AdbPath -s $serial shell dumpsys media.camera | Out-File -LiteralPath (Join-Path $work 'camera_after.txt') -Encoding UTF8
& $AdbPath -s $serial logcat -d -v threadtime | Out-File -LiteralPath (Join-Path $work 'logcat_full.txt') -Encoding UTF8
if ($LASTEXITCODE -ne 0) { throw 'adb logcat read failed' }
$filtered = @(Get-Content -LiteralPath (Join-Path $work 'logcat_full.txt') | Where-Object {
    $_ -match 'X14U5XProbe|RawrJavaCamera|CameraService|CameraCaptureSession|Camera3-Device|QCamera|CHI|chicdk|configureStreams'
})
$filtered | Set-Content -LiteralPath (Join-Path $work 'logcat_filtered.txt') -Encoding UTF8
$remote = '/sdcard/Download/X14U_X5_YUV_PROBE'
$remoteList = @(& $AdbPath -s $serial shell ls -la $remote 2>&1)
$remoteList | Out-File -LiteralPath (Join-Path $work 'device_samples_listing.txt') -Encoding UTF8
if ($remoteList -match '\.raw16le|\.json|_FAIL_') {
    & $AdbPath -s $serial pull $remote (Join-Path $work 'samples')
    if ($LASTEXITCODE -ne 0) { throw 'ADB pull failed; logs are still in work directory' }
} else {
    Write-Host 'No RAW/JSON visible under Downloads; logs remain available.' -ForegroundColor Yellow
}

Write-Host 'STEP 5/5: PACKAGE AND VERIFY' -ForegroundColor Cyan
$readme = @(
    'X14U Camera Lab - ID5 YUV + RAW50 probe',
    ('GitHub SHA: ' + $expectedSha),
    ('Android serial: ' + $serial),
    'Streams: YUV_420_888 2560x1920 (repeating), RAW_SENSOR 8192x6144 (one-shot)',
    'Operation mode: 36877 (0x900D)',
    'IMPORTANT: A reader or configured session is NOT proven valid 50MP Bayer.'
)
$readme | Set-Content -LiteralPath (Join-Path $work 'README.txt') -Encoding ASCII
if (!(Test-Path -LiteralPath (Join-Path $work 'logcat_full.txt'))) { throw 'Missing logcat' }
Compress-Archive -Path (Join-Path $work '*') -DestinationPath $zip -CompressionLevel Optimal -ErrorAction Stop
Add-Type -AssemblyName System.IO.Compression.FileSystem
$checkZip = [System.IO.Compression.ZipFile]::OpenRead($zip)
try {
    if ($checkZip.Entries.Count -lt 5) { throw 'ZIP validation failed (too few entries)' }
} finally { $checkZip.Dispose() }
Write-Host ('READY: ' + $zip) -ForegroundColor Green
Write-Host 'Send the ALL.zip file to the ChatGPT Camera Lab.' -ForegroundColor Green
