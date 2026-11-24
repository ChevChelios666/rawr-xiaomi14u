@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Run_ID5_Probe.ps1"
set "RESULT=%ERRORLEVEL%"
echo.
if not "%RESULT%"=="0" echo SCRIPT STOPPED WITH ERROR CODE %RESULT%
echo Press any key to close this window.
pause >nul
exit /b %RESULT%
