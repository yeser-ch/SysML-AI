@echo off
rem Baut das Plugin aus diesem Ordner und installiert es in CATIA (Details: build.ps1).
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
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
echo.
pause
