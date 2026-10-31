@echo off
rem Holt den neuesten Stand von GitHub, baut ihn und installiert ihn in CATIA.
rem Fragt bei Bedarf selbst nach Administratorrechten (Plugin-Ordner liegt oft unter C:\Program Files).
net session >nul 2>&1
if %errorlevel% neq 0 (
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -ArgumentList '%*' -Verb RunAs"
    exit /b
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0update.ps1" %*
echo.
pause
