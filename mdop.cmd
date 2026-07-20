@echo off
setlocal

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\mdop.ps1" %*

exit /b %ERRORLEVEL%
