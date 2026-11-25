@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0X14U_PhotonVidCam_Exact_APK_Collector_20261125.ps1"
set "rc=%errorlevel%"
echo.
if not "%rc%"=="0" echo ERROR: Collector failed (code %rc%).
pause
exit /b %rc%
