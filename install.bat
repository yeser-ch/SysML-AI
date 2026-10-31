@echo off
rem Installiert das fertige Plugin "SysML AI Q&A" in CATIA Magic (Details: install.ps1).
rem Braucht kein Java und kein Git. Fragt selbst nach Administratorrechten.
if not exist "%~dp0install.ps1" (
    echo.
    echo FEHLER: install.ps1 wurde nicht gefunden.
    echo Bitte die ZIP-Datei zuerst entpacken: Rechtsklick auf die ZIP -^> "Alle extrahieren",
    echo danach install.bat im entpackten Ordner starten.
    echo.
    pause
    exit /b 1
)
net session >nul 2>&1
if %errorlevel% equ 0 goto run
if "%~1"=="" (
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
) else (
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -ArgumentList '%*' -Verb RunAs"
)
exit /b
:run
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
echo.
pause
