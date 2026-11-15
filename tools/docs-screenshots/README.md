# Fensterbilder fürs Handbuch

`make-screenshots.sh` baut das Plugin gegen einfache Platzhalter der CATIA-Klassen (`stubs/`, nur die vom Plugin benutzten Namen, kein Code von CATIA) und öffnet das Fenster unter Linux mit Xvfb. Es zeigt ein kleines Beispielmodell (`data/sample.json`, `data/DroneSystem.sysml`) und schreibt die Bilder nach `docs/images`.

- Voraussetzungen: JDK 17+, `xvfb-run`
- Alle Bilder: `tools/docs-screenshots/make-screenshots.sh`
- Nur einzelne Szenen: `tools/docs-screenshots/make-screenshots.sh numbered,stop` (Namen siehe `Docs.java`, Methode `main`)

Die Titelleiste (Titel, Minimieren, Maximieren, Schließen) zeichnet das Skript im Stil von Windows 11 nach, weil Xvfb keine Fensterrahmen hat. Menüs von CATIA, Excel, Explorer und die Installation lassen sich so nicht erzeugen; diese Bilder sind echte Bildschirmfotos.

Erzeugt werden: `start-06-plugin-window-model.png`, `start-05-plugin-window.png`, `window-numbered.png`, `settings-numbered.png`, `chat-answer.png`, `stop-button.png`, `test-suite-running.png`, `test-suite-finished.png`, `test-suite-export-confirm.png`, `window-element.png`, `quick-element.png`, `window-sysml2.png`.
