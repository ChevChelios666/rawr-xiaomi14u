# Xiaomi 14 Ultra Camera Lab: PhotonVidCam 5x provenance / failure collector.
# READ-ONLY ADB diagnostics: no install, uninstall, root, app data changes or logcat clear.
# Windows PowerShell 5.1; designed for exactly one 5x FullRes attempt.
[CmdletBinding()]
param([string]$AdbPath = 'C:\Android\platform-tools\adb.exe')
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$package = 'com.xiaomi.module.evaluation'
$desktop = [Environment]::GetFolderPath('Desktop')
$timestamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$root = Join-Path $desktop ("X14U_PVC_5X_ONERUN_" + $timestamp)
$logdir = Join-Path $root 'LOGS'
$sampledir = Join-Path $root 'SAMPLES'
$snapdir = Join-Path $logdir 'CAMERA_SNAPSHOTS'
New-Item -ItemType Directory -Path $logdir,$sampledir,$snapdir -Force | Out-Null
$timeline = Join-Path $logdir 'timeline.tsv'
$logcatOut = Join-Path $logdir 'logcat_full.txt'
$logcatErr = Join-Path $logdir 'logcat_stderr.txt'
$adb = $null
$serial = $null
$logProcess = $null
$job = $null
$stopFlag = Join-Path $root 'STOP.txt'
$baselineMedia = @()
$started = $false
$fail = ''

function Note([string]$stage,[string]$detail) {
    $msg = (Get-Date).ToString('o') + "`t" + $stage + "`t" + $detail
    Add-Content -LiteralPath $script:timeline -Value $msg -Encoding UTF8
    Write-Host ('['+$stage+'] '+$detail)
}
function Mark([string]$stage) {
    try {
        & $script:adb -s $script:serial shell log -p i -t X14U_PVC5X $stage | Out-Null
    } catch { Note 'MARK_WARN' $_.Exception.Message }
}
function DumpCamera([string]$label) {
    if (-not $script:adb -or -not $script:serial) { return }
    $filename = Join-Path $script:logdir ('camera_' + $label + '.txt')
    try {
        & $script:adb -s $script:serial shell dumpsys media.camera | Out-File -LiteralPath $filename -Encoding UTF8 -Width 4096
        $code = $LASTEXITCODE
        Note 'DUMP' ($label + ' exit=' + $code + ' bytes=' + (Get-Item -LiteralPath $filename).Length)
    } catch { Note 'DUMP_WARN' ($label + ' ' + $_.Exception.Message) }
}
function MediaList {
    if (-not $script:adb -or -not $script:serial) { return @() }
    # No delete or access to private app storage. Phone might omit some directories.
    $findCommand = 'find /sdcard/DCIM /sdcard/Pictures /sdcard/Download -type f 2>/dev/null'
    try {
        $paths = @(& $script:adb -s $script:serial shell $findCommand)
        return @($paths | ForEach-Object { ([string]$_).Trim() } |
            Where-Object { $_ -match '(?i)\.(dng|raw|raw16|tif|tiff)$' } |
            Sort-Object -Unique)
    } catch {
        Note 'MEDIA_WARN' $_.Exception.Message
        return @()
    }
}
function PullNewMedia {
    if ($script:baselineMedia.Count -eq 0) {
        Note 'MEDIA' 'Baseline empty; not pulling gallery files automatically.'
        return
    }
    $after = @(MediaList)
    $after | Set-Content -LiteralPath (Join-Path $script:logdir 'media_after.txt') -Encoding UTF8
    $old = @{}
    foreach ($f in $script:baselineMedia) { $old[[string]$f] = $true }
    $new = @($after | Where-Object { -not $old.ContainsKey([string]$_) })
    $new | Set-Content -LiteralPath (Join-Path $script:logdir 'media_new.txt') -Encoding UTF8
    Note 'MEDIA' ('newRawLikeFiles=' + $new.Count)
    $counter = 0
    foreach ($remote in $new) {
        if ($counter -ge 3) { Note 'MEDIA' 'Max three new samples; remaining paths listed in media_new.txt'; break }
        $counter++
        $filename = [IO.Path]::GetFileName([string]$remote)
        $local = Join-Path $script:sampledir ('{0:D2}_{1}' -f $counter, $filename)
        try {
            $result = @(& $script:adb -s $script:serial pull ([string]$remote) $local 2>&1)
            $code = $LASTEXITCODE
            if ($code -ne 0 -or -not (Test-Path -LiteralPath $local -PathType Leaf)) {
                Note 'PULL_FAIL' ($remote + ' exit=' + $code + ' ' + ($result -join ' '))
            } else {
                $bytes = (Get-Item -LiteralPath $local).Length
                $hash = (Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash
                Note 'PULL_OK' ($remote + ' bytes=' + $bytes + ' sha256=' + $hash)
            }
        } catch { Note 'PULL_WARN' ($remote + ' ' + $_.Exception.Message) }
    }
}
function MakeZip([string]$source,[string]$name) {
    $target = Join-Path $script:desktop $name
    try {
        Compress-Archive -LiteralPath $source -DestinationPath $target -CompressionLevel Optimal -Force
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [System.IO.Compression.ZipFile]::OpenRead($target)
        try {
            if ($zip.Entries.Count -eq 0) { throw 'No ZIP entries' }
        } finally { $zip.Dispose() }
        Note 'ZIP_OK' ($target + ' bytes=' + (Get-Item -LiteralPath $target).Length)
    } catch { Note 'ZIP_FAIL' ($target + ' ' + $_.Exception.Message) }
}

try {
    if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
        $command = Get-Command adb.exe -ErrorAction SilentlyContinue
        if ($null -eq $command) { throw 'ADB not found; expected C:\Android\platform-tools\adb.exe or adb.exe on PATH.' }
        $AdbPath = $command.Source
    }
    $adb = (Resolve-Path -LiteralPath $AdbPath).ProviderPath
    $devices = @(& $adb devices)
    if ($LASTEXITCODE -ne 0) { throw 'adb devices failed' }
    $ready = @($devices | ForEach-Object { if ($_ -match '^([^\s]+)\s+device\s*$') { $Matches[1] } })
    if ($ready.Count -ne 1) { throw ('Expected one authorized ADB phone. Found '+$ready.Count+'. '+($devices -join ' / ')) }
    $serial = [string]$ready[0]
    $model = ((& $adb -s $serial shell getprop ro.product.device) -join '').Trim()
    $fw = ((& $adb -s $serial shell getprop ro.build.version.incremental) -join '').Trim()
    Note 'DEVICE' ('model='+$model+' firmware='+$fw)
    if ($model -ne 'aurora') { throw ('Wrong device: '+$model) }
    $pm = @(& $adb -s $serial shell pm path $package)
    if ($LASTEXITCODE -ne 0 -or -not ($pm -match '^package:')) { throw ('Installed PhotonVidCam Xiaomi package not found: '+$package) }
    Note 'PACKAGE' ($package + ' pathCount=' + @($pm).Count)
    $packageInfo = @(& $adb -s $serial shell dumpsys package $package)
    $versionLine = @($packageInfo | Where-Object { $_ -match '^\s*versionCode=' } | Select-Object -First 1)
    if ($versionLine.Count -gt 0) { Note 'PACKAGE_VERSION' ([string]$versionLine[0]).Trim() }
    $pm | Set-Content -LiteralPath (Join-Path $logdir 'installed_package_paths.txt') -Encoding UTF8
    $script:baselineMedia = @(MediaList)
    $script:baselineMedia | Set-Content -LiteralPath (Join-Path $logdir 'media_before.txt') -Encoding UTF8
    Note 'BASELINE' ('existingRawLikeFiles='+$script:baselineMedia.Count)
    $logProcess = Start-Process -FilePath $adb -ArgumentList @('-s', $serial, 'logcat', '-b', 'all', '-v', 'threadtime') `
        -PassThru -NoNewWindow -RedirectStandardOutput $logcatOut -RedirectStandardError $logcatErr
    Start-Sleep -Seconds 2
    $logProcess.Refresh()
    if ($logProcess.HasExited) { throw 'Continuous logcat exited before the test.' }
    Note 'LOGCAT' ('started pid=' + $logProcess.Id)
    DumpCamera '00_baseline'
    Write-Host ''
    Write-Host 'Open ALREADY INSTALLED PhotonVidCam Xiaomi 0.98 (4389) on the phone.' -ForegroundColor Cyan
    Write-Host 'Select physical 5x lens, FULLRES RAW 8192x6144, not AGC/stock.' -ForegroundColor Cyan
    Write-Host 'Prepare a STATIC well-lit scene. Do not take a photo yet.'
    [void](Read-Host 'When 5x FullRes RAW viewfinder is ready, press ENTER on the laptop')
    Mark 'PVC5X_READY'
    DumpCamera '01_ready_before_shot'
    # Start a background camera state sampler just before the one-shot capture.
    $job = Start-Job -ArgumentList @($adb,$serial,$snapdir,$stopFlag) -ScriptBlock {
        param($exe,$id,$folder,$stop)
        for ($i=1; $i -le 20; $i++) {
            if (Test-Path -LiteralPath $stop) { break }
            $filename = Join-Path $folder ('active_{0:D2}_{1}.txt' -f $i,(Get-Date -Format 'HHmmss_fff'))
            try {
                $all = @(& $exe -s $id shell dumpsys media.camera)
                $code = $LASTEXITCODE
                # Keep stream/operation details + active client; separate full dumps are collected outside.
                $filtered = $all | Select-String -Pattern 'Camera ID:|Client Package Name:|Operation mode:|Stream\[|Dims:|Consumer name:|Combined usage:|Stream use case:|Frames produced:|No input stream|Session parameters' -Context 0,2
                @('timestamp='+(Get-Date).ToString('o'),'exit='+$code,'=== STREAMS ===',($filtered | Out-String -Width 320)) |
                    Set-Content -LiteralPath $filename -Encoding UTF8
            } catch {
                ('snapshot error: '+$_.Exception.Message) | Set-Content -LiteralPath $filename -Encoding UTF8
            }
            Start-Sleep -Milliseconds 700
        }
    }
    Note 'TEST' 'Collector running; one 5x FullRes capture only.'
    Write-Host 'SHOOT NOW: one 5x FullRes RAW in PhotonVidCam. If it fails/freeze, do not retry.' -ForegroundColor Yellow
    [void](Read-Host 'After DNG saved OR error appeared, press ENTER on the laptop')
    Mark 'PVC5X_DONE_OR_ERROR'
    DumpCamera '02_after_attempt'
    $started = $true
} catch {
    $fail = $_.Exception.Message
    Note 'ERROR' $fail
} finally {
    if ($job -ne $null) {
        New-Item -ItemType File -Path $stopFlag -Force | Out-Null
        [void](Wait-Job -Job $job -Timeout 12)
        if ($job.State -eq 'Running') { Stop-Job -Job $job -ErrorAction SilentlyContinue }
        try { Receive-Job -Job $job -ErrorAction SilentlyContinue | Out-Null } catch { }
        Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
    }
    if ($adb -and $serial) { DumpCamera '99_final' }
    if ($logProcess -ne $null) {
        try {
            $logProcess.Refresh()
            if (-not $logProcess.HasExited) { Stop-Process -Id $logProcess.Id -Force -ErrorAction SilentlyContinue }
            Start-Sleep -Seconds 2
        } catch { Note 'LOGCAT_WARN' $_.Exception.Message }
    }
    if (Test-Path -LiteralPath $logcatOut -PathType Leaf) {
        try {
            Select-String -LiteralPath $logcatOut -Pattern 'X14U_PVC5X|CameraService|Camera3-Device|CamX|CHIUSECASE|MiCamHAL|CaptureController|VendorTagUtils|ImageReader|RAW_SENSOR|8192|4096|Error|Exception' |
                ForEach-Object { $_.Line } | Out-File -LiteralPath (Join-Path $logdir 'logcat_camera_filtered.txt') -Width 4096 -Encoding UTF8
        } catch { Note 'FILTER_WARN' $_.Exception.Message }
    }
    if ($started) { PullNewMedia }
    @('Read-only collection. No app files or photos deleted.',
      'The full logcat can contain unrelated app data; review before publicly sharing.',
      'A 50MP DNG is not proof of native Bayer. Need CameraService source buffer + metadata.') |
       Set-Content -LiteralPath (Join-Path $logdir 'README_PRIVACY.txt') -Encoding UTF8
    if ($fail) { Note 'RESULT' ('PARTIAL '+$fail) } else { Note 'RESULT' 'COLLECTED; provenance not yet classified' }
    MakeZip $logdir ('X14U_PVC5X_'+$timestamp+'_LOGS.zip')
    $samples = @(Get-ChildItem -LiteralPath $sampledir -File -ErrorAction SilentlyContinue)
    if ($samples.Count -gt 0) { MakeZip $sampledir ('X14U_PVC5X_'+$timestamp+'_SAMPLES.zip') }
    Write-Host ('RESULTS: '+$root) -ForegroundColor Green
    Write-Host 'SEND *_LOGS.zip FIRST. *_SAMPLES.zip if created.' -ForegroundColor Green
}
if ($fail) { exit 1 }
