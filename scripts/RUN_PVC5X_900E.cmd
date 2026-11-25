@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0PVC5X_900E_ONE_RUN.ps1"
set "result=%errorlevel%"
echo.
echo Exit code: %result%. Diagnostic archives may exist even after an error.
pause
exit /b %result%
