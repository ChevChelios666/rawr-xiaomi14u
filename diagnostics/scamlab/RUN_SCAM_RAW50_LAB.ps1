# PowerShell 5.1+; ONE run only. Never changes stock camera/RAWR/SCAMERA or deletes phone data.
param(
  [ValidateSet('4','5')][string]$CameraId='4',
  [ValidateSet('A','B')][string]$Variant='B',
  [string]$Adb='C:\Android\platform-tools\adb.exe',
  [string]$Device=''
)
$ErrorActionPreference='Stop'
if (-not (Test-Path $Adb)) { throw "ADB not found: $Adb" }
if ($Device) { $adbArgs=@('-s',$Device) } else { $adbArgs=@() }
& $Adb @adbArgs get-state | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'ADB not connected' }
$pkg='com.x14u.scamlab'
$installed=(& $Adb @adbArgs shell pm path $pkg 2>&1 | Out-String)
if ($installed -notmatch 'package:') { throw "SCAM RAW50 Lab ($pkg) is not installed. Download signed APK from our GitHub Actions build first." }
$dir=Join-Path ([Environment]::GetFolderPath('Desktop')) ('SCAM_RAW50_ID'+$CameraId+'_'+$Variant+'_'+(Get-Date -Format yyyyMMdd_HHmmss))
New-Item -Path $dir -ItemType Directory -Force | Out-Null
$log=Join-Path $dir 'logcat.txt'
$dump=Join-Path $dir 'camera_active.txt'
$dumpAfter=Join-Path $dir 'camera_after.txt'
$summary=Join-Path $dir 'RUN_INFO.txt'
("CameraId=$CameraId`r`nVariant=$Variant`r`nPackage=$pkg`r`nTime=$(Get-Date -Format o)") | Set-Content $summary
& $Adb @adbArgs shell am force-stop $pkg | Out-Null # only our separate LAB app
# Start log capture on PC; no logcat -c (don't wipe system logs).
$proc=Start-Process -FilePath $Adb -ArgumentList (@($adbArgs)+@('logcat','-v','threadtime')) -NoNewWindow -PassThru -RedirectStandardOutput $log
try {
  & $Adb @adbArgs shell am start -n "$pkg/.MainActivity" --es cameraId $CameraId --es variant $Variant | Out-Host
  if ($LASTEXITCODE -ne 0) { throw 'Could not launch SCAM RAW50 Lab' }
  Start-Sleep -Seconds 4
  & $Adb @adbArgs shell dumpsys media.camera 2>&1 | Out-File -FilePath $dump -Encoding utf8
  Start-Sleep -Seconds 12
  & $Adb @adbArgs shell dumpsys media.camera 2>&1 | Out-File -FilePath $dumpAfter -Encoding utf8
  $remote="/sdcard/Android/data/$pkg/files/runs"
  & $Adb @adbArgs pull $remote (Join-Path $dir 'runs') 2>&1 | Out-Host
  ("AppStart=OK`r`nLogs=$dir") | Add-Content $summary
} finally {
  if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
}
$zip=$dir+'.zip'
Compress-Archive -Path (Join-Path $dir '*') -DestinationPath $zip -CompressionLevel Optimal
Write-Host "RESULT: $zip" -ForegroundColor Green
Write-Host 'Upload the ZIP here. Do not repeat failed modes.'
