@echo off
setlocal DisableDelayedExpansion
"%~dp0runtime\bin\java.exe" --enable-native-access=ALL-UNNAMED -jar "%~dp0knitty.jar" %*
exit /b %errorlevel%
