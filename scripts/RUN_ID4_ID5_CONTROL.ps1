# X14U ID4/ID5 same-package admission comparison, Windows PowerShell 5.1.
# Two Camera2 sessions only: 0x900D, YUV 2560x1920 + RAW_SENSOR 8192x6144.
# No root, no system changes, no deletion of photos or working lenses.
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$pkg = 'com.rawr.camera.x14u.x5yuvprobe'
$component = "$pkg/com.rawr.x14u.probe.X5YuvRawProbeActivity"
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$apk = Join-Path $dir 'x5-probe-debug.apk'
$checksumFile = Join-Path $dir 'APK_SHA256.txt'
$desktop = [Environment]::GetFolderPath('Desktop')
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$outDir = Join-Path $desktop "X14U_ID4_ID5_CONTROL_$stamp"
$null = New-Item -ItemType Directory -Path $outDir -Force
$summary = Join-Path $outDir 'RUN_SUMMARY.txt'
$script:adb = ''
$script:device = ''
$script:continuousLog = $null
$script:stageCompleted = 0

function Record([string]$line) {
    $s = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff') $line"
    Add-Content -LiteralPath $summary -Value $s -Encoding UTF8
    Write-Host $line
}
function Snapshot([string]$label) {
    if (!$script:adb -or !$script:device) { return }
    try {
        $p = Join-Path $outDir ("cameraservice_" + $label + '.txt')
        & $script:adb -s $script:device shell dumpsys media.camera |
            Out-File -LiteralPath $p -Encoding UTF8 -Width 500
        Record "SNAPSHOT $label exit=$LASTEXITCODE size=$((Get-Item -LiteralPath $p).Length)"
    } catch { Record "SNAPSHOT_ERROR $label $($_.Exception.Message)" }
}
function Mark([string]$message) {
    try {
        & $script:adb -s $script:device shell log -p i -t X14U_ROUTE_CONTROL $message | Out-Null
    } catch { Record "MARKER_ERROR $($_.Exception.Message)" }
}
function CollectLogs([string]$label) {
    if (!$script:adb -or !$script:device) { return }
    try {
        $p = Join-Path $outDir ("logcat_" + $label + '.txt')
        & $script:adb -s $script:device logcat -d -b all -v threadtime |
            Out-File -LiteralPath $p -Encoding UTF8 -Width 500
        Record "LOGCAT_SNAPSHOT $label exit=$LASTEXITCODE size=$((Get-Item -LiteralPath $p).Length)"
    } catch { Record "LOGCAT_SNAPSHOT_ERROR $label $($_.Exception.Message)" }
}
function CaptureStage([string]$id) {
    $label = if ($id -eq '4') { 'ID4_3X_CONTROL' } else { 'ID5_5X_TARGET' }
    Record "STAGE_BEGIN $label"
    Mark "STAGE_BEGIN_$label"
    & $script:adb -s $script:device shell am force-stop $pkg | Out-Null
    $startText = (& $script:adb -s $script:device shell am start -n $component --es probe_camera_id $id | Out-String).Trim()
    Record "LAUNCH $label exit=$LASTEXITCODE $startText"
    if ($LASTEXITCODE -ne 0 -or $startText -match 'Error:|Exception') {
        throw "Cannot launch ${label}: $startText"
    }
    Start-Sleep -Seconds 2
    Snapshot "${label}_BEFORE"
    Write-Host ''
    Write-Host '==============================================================' -ForegroundColor Yellow
    Write-Host "STAGE: $label  |  camera ID=$id  |  opmode=0x900D" -ForegroundColor Yellow
    Write-Host 'On the phone: verify the header displays the correct ID and CONTROL V3.'
    Write-Host 'Tap 1. START ONCE exactly once.'
    Write-Host 'If PREVIEW_RUNNING: tap 2. CAPTURE ONE RAW, then wait for RAW_SAVE_OK or FAIL.'
    Write-Host 'If FAIL: DO NOT RETRY. Return to the laptop.'
    Write-Host '==============================================================' -ForegroundColor Yellow
    $answer = Read-Host "Press ENTER when $label finished, or type STOP to finish with logs"
    Snapshot "${label}_AFTER"
    CollectLogs "${label}_AFTER"
    Mark "STAGE_END_$label"
    Record "STAGE_END $label reply=$answer"
    $script:stageCompleted++
    if ($answer -eq 'STOP') { return $false }
    return $true
}

try {
    Record 'X14U ID4/ID5 admission control V3. No AGC; two unchanged stream definitions.'
    if (!(Test-Path -LiteralPath $apk -PathType Leaf)) { throw "APK missing: $apk" }
    if (!(Test-Path -LiteralPath $checksumFile -PathType Leaf)) { throw "SHA file missing: $checksumFile" }
    $expected = ((Get-Content -LiteralPath $checksumFile -TotalCount 1).Trim() -split '\s+')[0].ToUpperInvariant()
    $actual = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($expected -notmatch '^[A-F0-9]{64}$' -or $actual -ne $expected) {
        throw "APK checksum mismatch: expected=$expected actual=$actual"
    }
    Record "APK_SHA256_CONFIRMED $actual"
    $fixed = 'C:\Android\platform-tools\adb.exe'
    if (Test-Path -LiteralPath $fixed -PathType Leaf) { $script:adb = $fixed }
    else {
        $p = Get-Command 'adb.exe' -ErrorAction SilentlyContinue
        if ($null -ne $p) { $script:adb = $p.Source }
    }
    if (!$script:adb) { throw 'ADB not found. Install Android platform-tools or add adb.exe to PATH.' }
    Record "ADB_PATH $script:adb"
    $allDevices = @(& $script:adb devices)
    $deviceIds = @($allDevices | ForEach-Object {
        if ($_ -match '^([^\s]+)\s+device\s*$') { $Matches[1] }
    })
    if ($deviceIds.Count -ne 1) {
        Record "ADB_DEVICES $($allDevices -join ' / ')"
        throw "Expected exactly one authorized ADB device; found $($deviceIds.Count)"
    }
    $script:device = $deviceIds[0]
    $model = (& $script:adb -s $script:device shell getprop ro.product.device | Out-String).Trim()
    Record "DEVICE serial=$($script:device) product_device=$model"
    if ($model -ne 'aurora') { throw "Unexpected product.device=$model. Refusing to run on non-X14U." }

    # Installation is scoped to the isolated diagnostic package, not stock / RAWR.
    $installOut = Join-Path $outDir 'apk_install_stdout.txt'
    $installErr = Join-Path $outDir 'apk_install_stderr.txt'
    $arguments = "-s $($script:device) install -r -t `"$apk`""
    $process = Start-Process -FilePath $script:adb -ArgumentList $arguments -NoNewWindow -PassThru -Wait `
        -RedirectStandardOutput $installOut -RedirectStandardError $installErr
    Record "INSTALL exit=$($process.ExitCode) stdout=$((Get-Content $installOut | Out-String).Trim()) stderr=$((Get-Content $installErr | Out-String).Trim())"
    if ($process.ExitCode -ne 0) {
        throw "Test app install failed. If INSTALL_FAILED_UPDATE_INCOMPATIBLE, remove ONLY $pkg manually; the script will not uninstall anything."
    }

    # Constant-coverage logcat capture; do not clear or change system log settings.
    $live = Join-Path $outDir 'logcat_continuous.txt'
    $liveErr = Join-Path $outDir 'logcat_continuous_stderr.txt'
    $script:continuousLog = Start-Process -FilePath $script:adb -ArgumentList "-s $($script:device) logcat -b all -v threadtime" `
        -NoNewWindow -PassThru -RedirectStandardOutput $live -RedirectStandardError $liveErr
    Record "LOGCAT_STREAM_STARTED pid=$($script:continuousLog.Id)"
    Start-Sleep -Seconds 2
    Snapshot 'BASELINE'
    if (CaptureStage '4') { $null = CaptureStage '5' }
    Mark 'CONTROL_END'
    Record "TEST_END stages=$($script:stageCompleted)"
} catch {
    Record "ERROR $($_.Exception.Message)"
} finally {
    if ($script:adb -and $script:device) {
        CollectLogs 'FINAL'
        Snapshot 'FINAL'
        try {
            & $script:adb -s $script:device shell ls -la /sdcard/Download/X14U_X5_YUV_PROBE/ |
                Out-File -LiteralPath (Join-Path $outDir 'saved_raw_files_listing.txt') -Encoding UTF8
        } catch { Record "FILE_LIST_ERROR $($_.Exception.Message)" }
    }
    if ($script:continuousLog) {
        try {
            if (!$script:continuousLog.HasExited) { $script:continuousLog.Kill() }
            $script:continuousLog.WaitForExit(3000) | Out-Null
            Record 'LOGCAT_STREAM_STOPPED'
        } catch { Record "LOGCAT_STOP_ERROR $($_.Exception.Message)" }
    }
    try {
        $target = "$outDir.zip"
        Compress-Archive -LiteralPath $outDir -DestinationPath $target -CompressionLevel Optimal
        if (!(Test-Path -LiteralPath $target -PathType Leaf) -or (Get-Item $target).Length -lt 300) {
            throw 'ZIP verification failed'
        }
        Write-Host "`nUPLOAD THIS FILE: $target" -ForegroundColor Green
        Write-Host 'If RAW_SAVE_OK appeared, also share the new RAW16LE + metadata JSON from Download/X14U_X5_YUV_PROBE.' -ForegroundColor Cyan
    } catch {
        Write-Host "ZIP FAILED; original logs remain at: $outDir" -ForegroundColor Red
        Write-Host $_.Exception.Message -ForegroundColor Red
        exit 1
    }
}
