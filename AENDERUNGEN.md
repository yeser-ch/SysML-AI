# Änderungen WS 2026/27 – „Ask the model“

Branch: `feature/free-prompt-full-model` (Basis: `main` der Vorgängergruppe, Commit 2606f7d)

## Was ist neu

| Vorher | Jetzt |
|---|---|
| Rechtsklick → „Element View“ / „Model View“, danach RUN mit festem Prompt | Rechtsklick → **„Ask the model…“** (auch unter *Tools*), Frage frei eintippen |
| 4 feste Prompt-Dateien (Basic/Expert × Element/Model View) | 1 editierbarer System-Prompt `prompts/System_Prompt.md` + eigene Frage |
| Auch „Model View“ exportierte nur einen Ausschnitt (Tiefe 5) | Standard: **ganzes Modell** (`sysml_flat_export.vm`); Ausschnitt optional wählbar |
| Einstellungen verteilt (Tools → Settings), Modell/Temperatur fest im Code | **Alles in einem Fenster**: Modell, Antwortstil, Kontext, Temperatur, Max Tokens, Grounding, neuer Chat pro Frage, Fallback |
| Grounding standardmäßig an, erzwingt `gemini-2.5-flash-lite` | Grounding standardmäßig **aus**, erzwingt kein Modell |
| Stiller Wechsel auf andere Modelle bei Überlastung | Nur wenn „Fallback model“ angehakt; antwortendes Modell wird immer angezeigt |
| – | **Schnellfragen** nach Kategorien A–D (Retrieval, Struktur, Semantik, Unsicherheit) |
| – | **Evaluations-Log**: jede Frage/Antwort mit Einstellungen, Tokens, Dauer in `Dokumente/SchroedingersBlock/answers_log.csv` |
| – | „Refresh“ lädt alle für den API-Key verfügbaren Gemini-Modelle (für den Vergleich der Modellgrößen) |

## Geänderte Dateien
- `ui/ExplanationDialog.java` – neues Fenster (komplett neu)
- `ui/ShowExplanationAction.java`, `core/MenuConfigurator.java`, `core/SchroedingersBlockPlugin.java` – ein Menüeintrag, neue Einstellungs-Keys
- `ui/SettingsDialog.java` – nur noch API-Key
- `api/GeminiExplanationService.java`, `api/ExplanationService.java`, `api/PromptLoader.java` – überarbeitet
- `api/AskSettings.java`, `api/EvaluationLog.java` – neu
- `data_extraction/DataExtractionService.java` – `exportModel(...)` mit Scope ganzes Modell / Umgebung
- `installer/ressources/` – `System_Prompt.md` und `sysml_flat_export.vm` neu, 4 alte Prompt-Dateien entfernt; Installer kopiert die neuen Dateien

## Zum Bauen/Testen (in CATIA nötig)
1. Plugin-JAR neu bauen und nach `installer/ressources/schroedingers-block.jar` kopieren (die mitgelieferte JAR ist noch die alte!).
2. Installer neu bauen und ausführen – oder `sysml_flat_export.vm` nach `<plugin>/data_extraction/` und `System_Prompt.md` nach `<plugin>/prompts/` kopieren.
3. Test: Rechtsklick auf ein Element → „Ask the model…“ → Statuszeile zeigt Elementanzahl und geschätzte Tokens.

## Offene Punkte / Risiken
- **Nur gegen Platzhalter-Klassen kompiliert**, nicht in CATIA getestet.
- **Größe**: Das ganze Modell kann > 100 000 Tokens haben. Free-Tier-Limits (Tokens pro Minute) können zu HTTP 429 führen.
- **System-Prompt ist eine Versuchsvariable** – Änderungen an `System_Prompt.md` dokumentieren.
- SysML v2 ist noch nicht unterstützt.
- Die README-Aussage „Model View = gesamtes Modell“ war schon vorher falsch (es war ein Ausschnitt).
