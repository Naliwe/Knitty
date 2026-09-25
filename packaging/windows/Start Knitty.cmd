@echo off
setlocal DisableDelayedExpansion
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -STA -ExecutionPolicy Bypass -File "%~dp0launch.ps1"
set "knitty_exit=%errorlevel%"
echo.
pause
exit /b %knitty_exit%
