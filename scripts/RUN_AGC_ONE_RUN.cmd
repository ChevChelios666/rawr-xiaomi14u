@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0AGC_X14U_ONE_RUN.ps1"
set "STATUS=%ERRORLEVEL%"
echo.
echo Exit code: %STATUS%. The LOGS ZIP may exist even on partial failure.
pause
exit /b %STATUS%
