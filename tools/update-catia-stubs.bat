@echo off
rem Aktualisiert die CATIA-Signaturen (ci\catia-stubs), mit denen GitHub das Plugin ohne CATIA baut.
rem Nur noetig, wenn der Build auf GitHub meldet, dass CATIA-Klassen fehlen. Braucht keine Adminrechte.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0update-catia-stubs.ps1" %*
echo.
pause
