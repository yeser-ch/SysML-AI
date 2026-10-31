# SysML AI Q&A

**Fragen an eine KI zum eigenen SysML-Modell – direkt in CATIA Magic, mit vollständiger Protokollierung für die Auswertung.**

Entwickelt im Projekt **„How well does AI understand the SysML model?“** – Systems Engineering Project, Hochschule München, WS 2026/27.

| | |
| --- | --- |
| **Plattform** | CATIA Magic / Magic Systems of Systems Architect 2026x, SysML v1 |
| **KI** | Google Gemini API (eigener, kostenloser API-Key) |
| **Aktueller Stand** | Branch `feature/free-prompt-full-model` |

## Loslegen

- **Installieren:** [`INSTALL.md`](INSTALL.md) – fertiges Paket unter *Releases* herunterladen, entpacken, `install.bat` doppelklicken. Kein Java nötig.
- **Testläufe und Auswertung:** [`evaluation/README.md`](evaluation/README.md)
- **Änderungen im WS 2026/27:** [`AENDERUNGEN.md`](AENDERUNGEN.md)

## Was das Plugin kann

- Freie Fragen zum ganzen Modell oder zu einem ausgewählten Element stellen, mit Folgefragen im Chat
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
| `resources/System_Prompt.md` | System-Prompt (wird installiert und lässt sich in CATIA ohne neu zu bauen ändern) |
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
| `DataExtractionService` | Export des Modells als JSON |
| `GeminiExplanationService` | Anfragen an Gemini, Modell-Cache, Wiederholungen, Rate-Limits |
| `PromptLoader` | System-Prompt laden und Platzhalter füllen |
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

Nach jeder Änderung an `.java`-Dateien muss neu gebaut werden. Änderungen am System-Prompt kann man auch direkt im installierten Plugin-Ordner unter `prompts\System_Prompt.md` machen. Aber Achtung: Der System-Prompt ist eine Versuchsvariable, jede Änderung muss dokumentiert werden.

### Zusammenarbeit (Vorschlag)

- `feature/free-prompt-full-model` ist der aktuelle Arbeitsstand.
- Neue Arbeit in einem eigenen Branch davon beginnen (z. B. `feature/<thema>`) und per Pull Request zurückführen. So bleibt der gemeinsame Stand lauffähig.
- Vor dem Pull Request einmal bauen und in CATIA testen.
- Bei einer neuen Version `PLUGIN_VERSION` in `api/EvaluationRecord.java` erhöhen. Sie steht in jeder Log-Zeile und macht Messungen eindeutig zuordenbar.
- Neue Versionen für andere als Release mit dem Paket aus `dist\` veröffentlichen (siehe [`INSTALL.md`](INSTALL.md)).

---

## Herkunft

SysML AI Q&A baut auf einem Plugin auf, das eine Projektgruppe im Sommersemester 2026 an der Hochschule München entwickelt hat ([Original-Repository](https://github.com/hirtleo/SchroedingersBlock)). Übernommen wurden die Plugin-Grundstruktur, die Velocity-Exporte, Datenschutzabfrage und API-Key-Verwaltung. Was seitdem neu ist, steht in [`AENDERUNGEN.md`](AENDERUNGEN.md).
