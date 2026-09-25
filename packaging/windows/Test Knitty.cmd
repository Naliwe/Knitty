@echo off
setlocal DisableDelayedExpansion
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0test_windows_bundle.ps1" -Bundle "%~dp0."
set "knitty_exit=%errorlevel%"
echo.
pause
exit /b %knitty_exit%
