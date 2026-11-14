# SysML AI Q&A

**Fragen an eine KI zum eigenen SysML-Modell – direkt in CATIA Magic, mit vollständiger Protokollierung für die Auswertung.**

Entwickelt im Projekt **„How well does AI understand the SysML model?“** – Systems Engineering Project, Hochschule München, WS 2026/27.

| | |
| --- | --- |
| **Plattform** | CATIA Magic / Magic Systems of Systems Architect 2026x, SysML-v1- und SysML-v2-Projekte |
| **KI** | Google Gemini API (eigener, kostenloser API-Key) |
| **Aktueller Stand** | Branch `feature/free-prompt-full-model` |

## Loslegen

- **Installieren:** [`INSTALL.md`](INSTALL.md) – Schritt für Schritt mit Bildern: fertiges Paket unter *Releases* herunterladen, entpacken, `install.bat` doppelklicken. Kein Java nötig.
- **Bedienen:** [`HANDBUCH.md`](HANDBUCH.md) – das Fenster, alle Einstellungen, Schnellfragen, Testläufe und Ergebnisse erklärt
- **Testläufe und Auswertung:** [`evaluation/README.md`](evaluation/README.md)
- **Änderungen im WS 2026/27:** [`AENDERUNGEN.md`](AENDERUNGEN.md)
- **Exportformate und Aufbau einer Anfrage:** [`docs/EXPORTFORMATE.md`](docs/EXPORTFORMATE.md)
- **Abdeckung des Lastenhefts:** [`docs/LASTENHEFT_ABDECKUNG.md`](docs/LASTENHEFT_ABDECKUNG.md)

## Was das Plugin kann

- Freie Fragen zum ganzen Modell, zu ausgewählten Paketen/Elementen oder zu einem Element mit einstellbarer Umgebung stellen, mit Folgefragen im Chat
- SysML-v1- und SysML-v2-Modelle senden (v1 als JSON oder in SysML-v2-Notation, v2-Projekte als Textexport von CATIA), Größe und exakte Tokens vorab sehen
- Genau ansehen und speichern, was gesendet wird (*Show request…*), System-Prompt im Fenster ändern, laufende Anfragen abbrechen
- Schnellfragen als Vorlagen nach den vier Kategorien A Retrieval, B Structure, C Semantics, D Uncertainty
- Alle Versuchsparameter in einem Fenster: KI-Modell, Temperatur, Thinking, Kontext, Grounding, Fallback, Wiederholungen
- Eine Testsuite aus einer CSV-Datei automatisch abarbeiten und jede Antwort mit Versuchsaufbau protokollieren (CSV für Excel)
- Zitierte Element-IDs gegen das Modell prüfen

---

## Für Entwickler

### Aufbau des Repositorys

| Ordner / Datei | Inhalt |
| --- | --- |
| `src/de/hm/sysmlaiqa/core/` | Start des Plugins, Menüs in Tree, Diagrammen, Tabellen und Menüleiste |
| `src/de/hm/sysmlaiqa/ui/` | Fenster: Fragen, Einstellungen, Schnellfragen, Chat, API-Key |
| `src/de/hm/sysmlaiqa/data_extraction/` | Modellexport als JSON über Velocity-Templates (ganzes Modell oder Umgebung eines Elements) |
| `src/de/hm/sysmlaiqa/api/` | Gemini-Anbindung (Cache, Retry, Rate-Limits), System-Prompt, Testsuite, Protokoll, ID-Check |
| `resources/System_Prompt*.md` | Standard-System-Prompts für SysML v1, v2 (umgewandelt) und v2 aus CATIA (im Plugin unter *System prompt…* änderbar) |
| `docs/` | Exportformate, Lastenheft-Abdeckung, Bilder der Anleitungen |
| `evaluation/` | Vorlage für Testfälle und Erklärung der Log-Spalten |
| `Catia_Model/` | CATIA-Modelle (Anforderungen und Systemmodell des Plugins) |
| `build.*`, `update.*`, `install.*` | Bauen, Aktualisieren, Installieren (siehe [`INSTALL.md`](INSTALL.md)) |

Paket: `de.hm.sysmlaiqa`, Plugin-ID: `de.hm.sysmlaiqa.plugin`, JAR: `sysml-ai-qa.jar`.

### Wichtigste Klassen

| Klasse | Aufgabe |
| --- | --- |
| `SysmlAiQaPlugin` | Start und Ende des Plugins, Registrierung der Menüs, Einstellungs-Keys |
| `MenuConfigurator` | Menüeintrag „Ask AI (SysML AI Q&A)“ im Kontextmenü |
| `ShowExplanationAction` | Klick auf den Menüeintrag: Fokuselement ermitteln (auch aus Tabellen), Datenschutz und API-Key prüfen |
| `ExplanationDialog` | Hauptfenster: Einstellungen, Schnellfragen, Chat, Testsuite |
| `DataExtractionService` | Export des Modells als JSON (ganzes Modell, Auswahl, Umgebung mit Tiefe) |
| `GeminiExplanationService` | Anfragen an Gemini, Modell-Cache, Wiederholungen, Rate-Limits |
| `PromptLoader` | System-Prompt je Format laden (eigene Fassung, installiert, Standard) und Platzhalter füllen |
| `SysmlV2Writer`, `Json` | SysML-v1-Export (JSON) in SysML-v2-Textnotation übersetzen |
| `SysmlV2Reader` | SysML-v2-Text aus CATIA lesen: Kennzahlen, qualifizierte Namen, Index für Schnellfragen |
| `ElementPickerDialog`, `RequestDialog`, `SystemPromptDialog` | Auswahl von Paketen/Elementen, Anzeige der Anfrage, System-Prompt bearbeiten |
| `TestSuite`, `Evaluation` | Testfälle aus CSV abarbeiten, Wiederholungen |
| `EvaluationLog`, `EvaluationRecord` | Protokoll jeder Antwort (CSV) und Archiv von Prompt und Export |
| `IdChecker`, `ModelIndex` | Zitierte Element-IDs prüfen, Modellstruktur für Schnellfragen |
| `QuickQuestions` | Fragevorlagen nach Kategorie A–D |

### Bauen und testen

1. Einmalig ein **JDK 17 oder neuer** installieren (z. B. Eclipse Temurin 21, siehe [`INSTALL.md`](INSTALL.md), Weg B).
2. CATIA schließen.
3. **`build.bat`** im Repository-Ordner doppelklicken. Das baut den lokalen Code, legt das Installationspaket unter `dist\` ab und installiert es.
   Ohne lokalen Clone geht es auch mit **`update.bat`**: Es lädt den neuesten Stand des Branches von GitHub.
4. CATIA starten und unter **Window → Notification Window** prüfen, ob `Plugin loaded (version …)` mit der erwarteten Version erscheint.

Nach jeder Änderung an `.java`-Dateien muss neu gebaut werden. Den System-Prompt ändert man ohne neu zu bauen im Plugin unter **System prompt…**. Aber Achtung: Der System-Prompt ist eine Versuchsvariable. Jede verwendete Fassung wird archiviert und steht mit Prüfsumme im Log.

### Zusammenarbeit (Vorschlag)

- `feature/free-prompt-full-model` ist der aktuelle Arbeitsstand.
- Neue Arbeit in einem eigenen Branch davon beginnen (z. B. `feature/<thema>`) und per Pull Request zurückführen. So bleibt der gemeinsame Stand lauffähig.
- Vor dem Pull Request einmal bauen und in CATIA testen.
- Bei einer neuen Version `PLUGIN_VERSION` in `api/EvaluationRecord.java` erhöhen. Sie steht in jeder Log-Zeile und macht Messungen eindeutig zuordenbar.
- Das Release für andere baut GitHub nach jedem Push automatisch (siehe [`INSTALL.md`](INSTALL.md), Abschnitt *Neue Version an andere verteilen*).

---

## Herkunft

SysML AI Q&A baut auf einem Plugin auf, das eine Projektgruppe im Sommersemester 2026 an der Hochschule München entwickelt hat ([Original-Repository](https://github.com/hirtleo/SchroedingersBlock)). Übernommen wurden die Plugin-Grundstruktur, die Velocity-Exporte, Datenschutzabfrage und API-Key-Verwaltung. Was seitdem neu ist, steht in [`AENDERUNGEN.md`](AENDERUNGEN.md).
