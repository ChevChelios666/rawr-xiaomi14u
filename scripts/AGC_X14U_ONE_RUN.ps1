# Xiaomi 14 Ultra / AGC 2.0: one-run capture provenance diagnostics.
# Windows PowerShell 5.1, read-only ADB diagnostics, no root/ROM/key changes.
# Original photos, app profiles, APKs and phone files are not modified or deleted.
[CmdletBinding()]
param(
    [string]$AdbPath = 'C:\Android\platform-tools\adb.exe'
)

$ErrorActionPreference = 'Stop'
$desktop = [Environment]::GetFolderPath('Desktop')
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$work = Join-Path $desktop ('X14U_AGC50_ONE_RUN_' + $stamp)
$logDir = Join-Path $work 'LOGS'
$sampleDir = Join-Path $work 'SAMPLES'
$snapDir = Join-Path $logDir 'camera_active_snapshots'
New-Item -ItemType Directory -Path $logDir, $sampleDir, $snapDir -Force | Out-Null
$timeline = Join-Path $logDir 'timeline.tsv'
$logcatFile = Join-Path $logDir 'logcat_all.txt'
$logcatErr = Join-Path $logDir 'logcat_stderr.txt'
$stopFile = Join-Path $work 'STOP_SAMPLING.flag'
$adb = $null
$serial = $null
$logcatProcess = $null
$samplerJob = $null
$baselinePaths = @()
$testStarted = $false
$fatal = $null

function Note([string]$stage, [string]$message) {
    $now = (Get-Date).ToString('o')
    ($now + "`t" + $stage + "`t" + $message) |
        Add-Content -LiteralPath $script:timeline -Encoding UTF8
    Write-Host ('[' + $stage + '] ' + $message)
}

function Get-MediaPaths {
    $paths = @()
    if ([string]::IsNullOrWhiteSpace($script:adb) -or [string]::IsNullOrWhiteSpace($script:serial)) {
        return $paths
    }
    # Android shell interprets this quoted command. No files are touched.
    $cmd = 'find /sdcard/DCIM /sdcard/Pictures /sdcard/Download /sdcard/Android/media -type f 2>/dev/null'
    try {
        $lines = @(& $script:adb -s $script:serial shell $cmd)
        $rc = $LASTEXITCODE
        if ($rc -ne 0) {
            Note 'WARN' ('media find returned exit=' + $rc)
        }
        $paths = @($lines | ForEach-Object { ([string]$_).Trim() } |
            Where-Object { $_ -match '(?i)\.(dng|jpe?g|heic)$' } |
            Sort-Object -Unique)
    } catch {
        Note 'WARN' ('media enumeration: ' + $_.Exception.Message)
    }
    return $paths
}

function Get-CameraDump([string]$name) {
    if ([string]::IsNullOrWhiteSpace($script:adb) -or [string]::IsNullOrWhiteSpace($script:serial)) { return }
    $out = Join-Path $script:logDir ($name + '.txt')
    try {
        & $script:adb -s $script:serial shell dumpsys media.camera |
            Out-File -LiteralPath $out -Encoding UTF8 -Width 4096
        $rc = $LASTEXITCODE
        Note 'DUMPSYS' ($name + ' exit=' + $rc + ' bytes=' + (Get-Item -LiteralPath $out).Length)
    } catch {
        Note 'WARN' ('dumpsys ' + $name + ': ' + $_.Exception.Message)
    }
}

function Get-DeviceClock([string]$stage) {
    try {
        $clock = (& $script:adb -s $script:serial shell 'date +%Y-%m-%dT%H:%M:%S%z') -join ' '
        Note 'DEVICE_TIME' ($stage + ' ' + $clock.Trim())
    } catch { Note 'WARN' ('device clock ' + $stage + ': ' + $_.Exception.Message) }
}

function Copy-New-Samples {
    if (@($script:baselinePaths).Count -eq 0) {
        Note 'SAMPLES' 'Baseline media list was empty: no automatic pull (prevents copying old gallery).'
        return
    }
    $after = @(Get-MediaPaths)
    $after | Set-Content -LiteralPath (Join-Path $script:logDir 'media_after.txt') -Encoding UTF8
    $seen = @{}
    foreach ($p in $script:baselinePaths) { $seen[[string]$p] = $true }
    $fresh = @($after | Where-Object { -not $seen.ContainsKey([string]$_) })
    $fresh | Set-Content -LiteralPath (Join-Path $script:logDir 'media_new.txt') -Encoding UTF8
    Note 'SAMPLES' ('new_media_paths=' + $fresh.Count)
    $count = 0
    foreach ($remote in $fresh) {
        if ($count -ge 6) {
            Note 'WARN' 'More than six new media files; remaining paths preserved in media_new.txt.'
            break
        }
        $count++
        $base = [IO.Path]::GetFileName([string]$remote)
        $dest = Join-Path $script:sampleDir ('{0:D2}_{1}' -f $count, $base)
        $pullLog = Join-Path $script:logDir 'adb_pull.txt'
        try {
            & $script:adb -s $script:serial pull ([string]$remote) $dest |
                Out-File -LiteralPath $pullLog -Append -Encoding UTF8
            $rc = $LASTEXITCODE
            if ($rc -eq 0 -and (Test-Path -LiteralPath $dest) -and (Get-Item -LiteralPath $dest).Length -gt 0) {
                $item = Get-Item -LiteralPath $dest
                $sha = (Get-FileHash -LiteralPath $dest -Algorithm SHA256).Hash
                Note 'PULL_OK' ($remote + ' bytes=' + $item.Length + ' sha256=' + $sha)
            } else {
                Note 'PULL_FAIL' ($remote + ' exit=' + $rc)
            }
        } catch {
            Note 'PULL_FAIL' ($remote + ' error=' + $_.Exception.Message)
        }
    }
}

function Create-Archive([string]$folder, [string]$zip) {
    if (!(Test-Path -LiteralPath $folder)) { return }
    try {
        Compress-Archive -LiteralPath $folder -DestinationPath $zip -CompressionLevel Optimal -Force
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive = [System.IO.Compression.ZipFile]::OpenRead($zip)
        try {
            if ($archive.Entries.Count -lt 1) { throw 'ZIP contains no files' }
            foreach ($entry in $archive.Entries) {
                if ($entry.Length -gt 0) {
                    $stream = $entry.Open()
                    try {
                        $buf = New-Object byte[] 131072
                        while ($stream.Read($buf, 0, $buf.Length) -gt 0) { }
                    } finally { $stream.Dispose() }
                }
            }
        } finally { $archive.Dispose() }
        Write-Host ('ZIP_OK ' + $zip + ' bytes=' + (Get-Item -LiteralPath $zip).Length) -ForegroundColor Green
    } catch {
        Write-Host ('ZIP_FAIL ' + $zip + ': ' + $_.Exception.Message) -ForegroundColor Red
    }
}

try {
    if (!(Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
        $found = Get-Command adb -ErrorAction SilentlyContinue
        if ($null -eq $found) { throw 'ADB not found at C:\Android\platform-tools\adb.exe or on PATH.' }
        $AdbPath = $found.Source
    }
    $adb = (Resolve-Path -LiteralPath $AdbPath).ProviderPath
    $devicesOut = @(& $adb devices)
    if ($LASTEXITCODE -ne 0) { throw 'adb devices failed.' }
    $ready = @($devicesOut | Where-Object { $_ -match '^\S+\s+device\s*$' })
    if ($ready.Count -ne 1) {
        $devicesOut | Set-Content -LiteralPath (Join-Path $logDir 'adb_devices.txt') -Encoding UTF8
        throw ('Expected ONE authorized ADB phone, found ' + $ready.Count + '. Connect Wireless Debugging.')
    }
    $serial = ($ready[0] -split '\s+')[0]
    Note 'ADB_OK' ('serial=' + $serial)

    $deviceName = ((& $adb -s $serial shell getprop ro.product.device) -join '').Trim()
    $build = ((& $adb -s $serial shell getprop ro.build.version.incremental) -join '').Trim()
    Note 'DEVICE' ('device=' + $deviceName + ' build=' + $build)
    if ($deviceName -ne 'aurora') { throw ('Unexpected device: ' + $deviceName + ' (expected aurora)') }
    Get-DeviceClock 'BEGIN'

    # Capture before-shot filenames; copied images are ONLY those created afterward.
    $baselinePaths = @(Get-MediaPaths)
    $baselinePaths | Set-Content -LiteralPath (Join-Path $logDir 'media_before.txt') -Encoding UTF8
    Note 'BASELINE' ('media_file_count=' + $baselinePaths.Count)

    # A single continuous logcat; -d alone would risk rolling-buffer loss.
    $logcatProcess = Start-Process -FilePath $adb -ArgumentList @('-s', $serial, 'logcat', '-b', 'all', '-v', 'threadtime') `
        -PassThru -NoNewWindow -RedirectStandardOutput $logcatFile -RedirectStandardError $logcatErr
    Start-Sleep -Seconds 2
    $logcatProcess.Refresh()
    if ($logcatProcess.HasExited) { throw 'Background logcat exited before the test.' }
    Note 'LOGCAT' ('continuous pid=' + $logcatProcess.Id)
    Get-CameraDump '01_before_agc'

    Write-Host ''
    Write-Host 'STAGE A (one 50MP AGC 5x DNG)' -ForegroundColor Cyan
    Write-Host 'On phone: open AGC 2.0; select PHYSICAL 5x; enable 50MP DNG.'
    Write-Host 'Do not press shutter yet. Use a static well-lit detailed scene.'
    [void](Read-Host 'Once AGC is READY TO SHOOT, press Enter here')
    Get-DeviceClock 'AGC_READY'
    Get-CameraDump '02_agc_ready'

    # Poll while the phone shoots, not only after the camera session has closed.
    $samplerJob = Start-Job -ArgumentList @($adb, $serial, $snapDir, $stopFile) -ScriptBlock {
        param($adbExe, $deviceSerial, $snapshotFolder, $stopPath)
        $ErrorActionPreference = 'Continue'
        for ($i = 1; $i -le 45; $i++) {
            if (Test-Path -LiteralPath $stopPath) { break }
            $localStamp = Get-Date -Format 'HHmmss_fff'
            $file = Join-Path $snapshotFolder ('active_{0:D3}_{1}.txt' -f $i, $localStamp)
            try {
                $all = @(& $adbExe -s $deviceSerial shell dumpsys media.camera)
                $rc = $LASTEXITCODE
                $head = @($all | Select-Object -First 24)
                $matches = @($all | Select-String -Pattern 'Camera ID:|Client Package Name:|Operation mode:|Stream\[[0-9]+\]|Consumer name:|Dims:|Combined usage:|Stream use case:|Frames produced:|No input stream' -Context 0,2)
                $txt = 'Device snapshot ' + $i + ' ' + (Get-Date).ToString('o') + ' exit=' + $rc + "`n" +
                    ($head -join "`n") + "`n== STREAM DETAILS ==`n" + ($matches | Out-String -Width 300)
                $txt | Set-Content -LiteralPath $file -Encoding UTF8
            } catch {
                ('snapshot error: ' + $_.Exception.Message) | Set-Content -LiteralPath $file -Encoding UTF8
            }
            Start-Sleep -Milliseconds 1500
        }
    }
    Note 'SAMPLER' 'Started; captures active camera state during shooting.'
    Write-Host 'SHOOT NOW: take ONE 5x AGC 50MP DNG. Do NOT switch to stock camera.' -ForegroundColor Yellow
    [void](Read-Host 'Press Enter ONLY after the DNG has finished saving')
    Note 'CAPTURE' 'User reports AGC 5x 50MP DNG saved; not yet independently verified.'
    Get-DeviceClock 'AGC_SAVED'
    Get-CameraDump '03_agc_after_50mp'

    Write-Host ''
    $ctrl = Read-Host 'OPTIONAL: type YES for one 5x ordinary 12MP DNG in AGC; Enter to SKIP'
    if ($ctrl -eq 'YES') {
        Write-Host 'Set AGC 5x to ordinary 12MP DNG, SAME static scene.' -ForegroundColor Cyan
        [void](Read-Host 'Press Enter when ready, BEFORE capturing')
        Note 'CONTROL' 'AGC 5x ordinary DNG ready; user-selected mode.'
        Get-CameraDump '04_control_ready'
        Write-Host 'Take ONE AGC 5x ordinary DNG now.' -ForegroundColor Yellow
        [void](Read-Host 'Press Enter after the control DNG has saved')
        Note 'CONTROL' 'User reports AGC ordinary 5x DNG saved.'
        Get-CameraDump '05_control_after'
    } else {
        Note 'CONTROL' 'SKIPPED: no extra control capture.'
    }
    $testStarted = $true
} catch {
    $fatal = $_.Exception.Message
    Note 'FATAL' $fatal
} finally {
    # Stop the sampler and logcat even if capture, ADB or camera failed.
    if ($null -ne $samplerJob) {
        New-Item -ItemType File -Path $stopFile -Force | Out-Null
        [void](Wait-Job -Job $samplerJob -Timeout 15)
        if ($samplerJob.State -eq 'Running') { Stop-Job -Job $samplerJob -ErrorAction SilentlyContinue }
        try { Receive-Job -Job $samplerJob -ErrorAction SilentlyContinue |
            Out-File -LiteralPath (Join-Path $logDir 'sampler_job.txt') -Encoding UTF8 } catch { }
        Remove-Job -Job $samplerJob -Force -ErrorAction SilentlyContinue
    }
    Get-CameraDump '99_final'
    if ($null -ne $logcatProcess) {
        try { $logcatProcess.Refresh() } catch { }
        if (!$logcatProcess.HasExited) {
            try { Stop-Process -Id $logcatProcess.Id -Force -ErrorAction SilentlyContinue } catch { }
        }
        Start-Sleep -Seconds 2
    }
    if (Test-Path -LiteralPath $logcatFile) {
        try {
            Select-String -LiteralPath $logcatFile -Pattern 'DngTagPatcher|SABRE|MAXRES|MergeShot|RAW_SENSOR|ImageReader|CameraService|Camera3|CamX|MiCam|CHI|4096x3072|8192x6144|AGC' |
                ForEach-Object { $_.Line } |
                Out-File -LiteralPath (Join-Path $logDir 'logcat_camera_filtered.txt') -Encoding UTF8 -Width 4096
        } catch { Note 'WARN' ('logcat filtering: ' + $_.Exception.Message) }
    }
    if ($testStarted) { Copy-New-Samples }
    Get-DeviceClock 'END'
    $privacy = @'
Diagnostics may contain other app package names or unrelated logcat lines.
Review LOGCAT_ALL before forwarding outside this chat.
Only new photo filenames are pulled, never deleted or edited on the phone.
CameraService stream dimensions and DNG output dimensions must not be confused.
'@
    $privacy | Set-Content -LiteralPath (Join-Path $logDir 'PRIVACY_AND_INTERPRETATION.txt') -Encoding UTF8
    if ($fatal) { Note 'RESULT' ('COLLECTION_PARTIAL ' + $fatal) }
    elseif ($testStarted) { Note 'RESULT' 'COLLECTION_COMPLETE; capture path NOT yet classified.' }
    else { Note 'RESULT' 'COLLECTION_PARTIAL; no finished camera-stage confirmation.' }

    $logsZip = Join-Path $desktop ('X14U_AGC50_ONE_RUN_' + $stamp + '_LOGS.zip')
    Create-Archive $logDir $logsZip
    $samples = @(Get-ChildItem -LiteralPath $sampleDir -File -ErrorAction SilentlyContinue)
    if ($samples.Count -gt 0) {
        $samplesZip = Join-Path $desktop ('X14U_AGC50_ONE_RUN_' + $stamp + '_SAMPLES.zip')
        Create-Archive $sampleDir $samplesZip
    } else {
        Write-Host 'No original DNG auto-copied. Share the AGC DNG manually if needed.' -ForegroundColor Yellow
    }
    Write-Host ('RESULT DIRECTORY: ' + $work) -ForegroundColor Green
    Write-Host ('SEND FIRST: ' + $logsZip) -ForegroundColor Green
}
if ($fatal) { exit 1 }
