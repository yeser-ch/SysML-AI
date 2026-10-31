@echo off
rem Baut das Plugin aus diesem Ordner und installiert es in CATIA (Details: build.ps1).
rem Fragt bei Bedarf selbst nach Administratorrechten (Plugin-Ordner liegt oft unter C:\Program Files).
net session >nul 2>&1
if %errorlevel% neq 0 (
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -ArgumentList '%*' -Verb RunAs"
    exit /b
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
echo.
pause
