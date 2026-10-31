# Änderungen WS 2026/27 – „SysML AI Q&A“ (Weiterentwicklung von Schrödinger's Block)

Branch: `feature/free-prompt-full-model` (Basis: `main` der Vorgängergruppe, Commit 2606f7d)

## Was ist neu

| Vorher | Jetzt |
|---|---|
| Rechtsklick → „Schrödinger's Block“ → „Element View“ / „Model View“, danach RUN mit festem Prompt | Rechtsklick → **„Ask AI about the model…“** (direkt im Menü, auch unter *Tools*), Frage frei eintippen. Sichtbarer Name des Werkzeugs: **SysML AI Q&A** (interne Namen bleiben `schroedingersblock`, damit Updates und gespeicherte Einstellungen funktionieren) |
| 4 feste Prompt-Dateien (Basic/Expert × Element/Model View) | 1 editierbarer System-Prompt `prompts/System_Prompt.md` + eigene Frage |
| Auch „Model View“ exportierte nur einen Ausschnitt (Tiefe 5) | Standard: **ganzes Modell** (`sysml_flat_export.vm`); Ausschnitt optional wählbar |
| Einstellungen verteilt (Tools → Settings), Modell/Temperatur fest im Code | **Alles in einem Fenster**: Modell, Antwortstil, Kontext, Temperatur, Max Tokens, Grounding, neuer Chat pro Frage, Fallback |
| Grounding standardmäßig an, erzwingt `gemini-2.5-flash-lite` | Grounding standardmäßig **aus**, erzwingt kein Modell |
| Stiller Wechsel auf andere Modelle bei Überlastung | Nur wenn „Fallback model“ angehakt; antwortendes Modell wird immer angezeigt |
| – | **Schnellfragen** nach Kategorien A–D (Retrieval, Struktur, Semantik, Unsicherheit) |
| – | **Evaluations-Log** mit vollständigem Versuchsaufbau (Prüfsummen von Prompt, Export, Antwort; Archiv) in `Dokumente/SysML-AI-QA/` |
| – | **Testläufe aus CSV** („Run test suite…“), auch für mehrere Modelle nacheinander, mit Pause gegen Rate-Limits |
| – | **Wiederholungen** pro Frage (Konsistenz-Messung) |
| – | **ID-Check**: KI nennt Element-IDs, Plugin erkennt erfundene IDs |
| Modell wurde bei jeder Frage komplett mitgeschickt | **„Upload model once“**: Modell einmal als Gemini-Cache hochladen, danach nur Fragen; **„Resend model“** exportiert neu und lädt neu hoch |
| Feste Pause / Abbruch bei Rate-Limit | Automatisches Warten genau so lange wie Google verlangt; Tageslimit wird erkannt |
| Log zeigte nur die angefragte Modell-ID | Log zeigt auch die **tatsächliche Modellversion** (Google leitet z. B. `gemini-3.5-flash` auf `gemini-3.6-flash` um) |
| Standardmodell `gemini-3.5-flash` | Vorschlagsliste aktualisiert: `gemini-3.8-flash`, `gemini-3.6-flash`, `gemini-3.5-flash-lite`, `gemini-3.1-flash-lite` (Stand Okt. 2026; „Refresh“ zeigt die echte Liste) |
| – | „Refresh“ lädt alle für den API-Key verfügbaren Gemini-Modelle (für den Vergleich der Modellgrößen) |

## Geänderte Dateien
- `ui/ExplanationDialog.java` – neues Fenster (komplett neu)
- `ui/ShowExplanationAction.java`, `core/MenuConfigurator.java`, `core/SchroedingersBlockPlugin.java` – ein Menüeintrag, neue Einstellungs-Keys
- `ui/SettingsDialog.java` – nur noch API-Key
- `api/GeminiExplanationService.java`, `api/ExplanationService.java`, `api/PromptLoader.java` – überarbeitet
- `api/AskSettings.java`, `api/EvaluationLog.java` – neu
- `api/Evaluation.java`, `api/EvaluationRecord.java`, `api/ModelExport.java`, `api/IdChecker.java`, `api/TestSuite.java`, `api/Hashing.java` – neu (Evaluation)
- `evaluation/README.md`, `evaluation/testcases_template.csv` – Anleitung und Vorlage für Testfälle
- `data_extraction/DataExtractionService.java` – `exportModel(...)` mit Scope ganzes Modell / Umgebung
- `installer/ressources/` – `System_Prompt.md` und `sysml_flat_export.vm` neu, 4 alte Prompt-Dateien entfernt; Installer kopiert die neuen Dateien

## Evaluation
Siehe [`evaluation/README.md`](evaluation/README.md).

## Bauen und in CATIA installieren
Einmalig: Java JDK 17 oder neuer installieren (z. B. Eclipse Temurin 21 von https://adoptium.net, bei der Installation „Set JAVA_HOME“ anhaken).

1. Diesen Branch holen (`git checkout feature/free-prompt-full-model` + `git pull`, oder als ZIP herunterladen).
2. CATIA schließen.
3. **`build.bat` doppelklicken.** Das Skript findet CATIA, das JDK und den Plugin-Ordner, kompiliert, baut `schroedingers-block.jar` und kopiert alles in den Plugin-Ordner (alte JAR → `.bak`).
4. CATIA starten → Rechtsklick auf ein Element → **„Ask AI about the model…“**.

**Updates:** Nach jeder Code-Änderung auf GitHub: CATIA schließen, **`update.bat` doppelklicken** (lädt den neuesten Stand, baut und installiert; fragt selbst nach Administratorrechten). `build.bat` baut dagegen den Code aus dem eigenen Ordner (für lokale Änderungen).
Falls die automatische Suche nicht klappt: `build.bat -CatiaDir "<CATIA-Ordner>" -PluginDir "<Plugin-Ordner>"`. Nur bauen: `build.bat -NoInstall`.
Hinweis: Die JAR im Installer (`installer/ressources/`) ist noch die alte Version.

## Offene Punkte / Risiken
- **Nur gegen Platzhalter-Klassen kompiliert**, nicht in CATIA getestet. Das Build-Skript wurde mit PowerShell 7 gegen eine nachgebaute CATIA-Ordnerstruktur getestet, nicht auf Windows.
- Beim ersten Start nach dem Update wird Grounding einmalig ausgeschaltet (die alte Version hatte „an“ gespeichert).
- **Größe**: Das ganze Modell kann > 100 000 Tokens haben. Free-Tier-Limits (Tokens pro Minute) können zu HTTP 429 führen.
- **Cache**: Ob gecachte Tokens im Free Tier auf das Limit „Tokens pro Minute“ angerechnet werden, ist in Googles Doku nicht eindeutig – im Log (`cached_tokens`, `rate_limit_wait_s`) prüfen. Laut Preisliste ist Caching im Free Tier nicht für alle Modelle verfügbar (Flash-Lite nicht); dann wird automatisch inline gesendet.
- **System-Prompt ist eine Versuchsvariable** – Änderungen an `System_Prompt.md` dokumentieren.
- SysML v2 ist noch nicht unterstützt.
- Die README-Aussage „Model View = gesamtes Modell“ war schon vorher falsch (es war ein Ausschnitt).
