@echo off
rem Holt den neuesten Stand von GitHub, baut ihn und installiert ihn in CATIA.
rem Fragt bei Bedarf selbst nach Administratorrechten (Plugin-Ordner liegt oft unter C:\Program Files).
net session >nul 2>&1
if %errorlevel% equ 0 goto run
if "%~1"=="" (
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
) else (
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -ArgumentList '%*' -Verb RunAs"
)
exit /b
:run
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0update.ps1" %*
echo.
pause
