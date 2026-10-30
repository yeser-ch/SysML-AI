@echo off
rem Baut das Plugin und installiert es in CATIA Magic (Details: build.ps1)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
echo.
pause
