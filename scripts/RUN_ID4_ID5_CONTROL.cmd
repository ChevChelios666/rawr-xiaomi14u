@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0RUN_ID4_ID5_CONTROL.ps1"
echo.
echo Test ended. Upload the ZIP from the Desktop.
pause
