@echo off
setlocal
set "PS=powershell.exe"
where pwsh >nul 2>nul
if not errorlevel 1 set "PS=pwsh"
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-release.ps1" %*
exit /b %ERRORLEVEL%
