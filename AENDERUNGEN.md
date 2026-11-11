# Änderungen WS 2026/27 – SysML AI Q&A

Branch: `feature/free-prompt-full-model` (Basis: Stand SoSe 2026, Commit 2606f7d)

## v3.3 – Umsetzung des Lastenhefts V0.1

Zuordnung jeder Anforderung zur Umsetzung: [`docs/LASTENHEFT_ABDECKUNG.md`](docs/LASTENHEFT_ABDECKUNG.md).

- **Umfang (FR1):** neu *Selected packages / elements* mit **Choose…** (Modellbaum, Mehrfachauswahl). Bei *Selected element + surroundings* ist die Tiefe einstellbar (**Depth: relations / neighbours**).
- **SysML v2 (FR7):** **Format** *SysML v2 (textual notation)* schreibt dasselbe Modell in SysML-v2-Notation (eigener System-Prompt, IDs als Kurznamen). Beschreibung beider Formate: [`docs/EXPORTFORMATE.md`](docs/EXPORTFORMATE.md).
- **Größe (FR2):** exakte Tokenzahl der Modelldaten für das gewählte KI-Modell; Warnung, wenn das Eingabelimit überschritten wird.
- **Transparenz (FR3, NFR3):** **Show request…** zeigt und speichert genau, was gesendet wird. Das ausgewählte Element wird nur noch mit *Mention selected element* gesendet.
- **System-Prompt (FR9):** **System prompt…** zum Ansehen und Ändern je Format; eigene Fassungen liegen in `Dokumente\SysML-AI-QA\prompts`.
- **Abbrechen (NFR4):** *Send* wird während einer Anfrage zu **Stop**; *Stop test run* bricht auch die laufende Anfrage ab.
- **Fehlermeldungen (FR12):** konkrete Meldungen bei nicht erreichbarer API, zu großem Export und leerer Antwort (leere Antworten zählen nicht mehr als Erfolg).
- **Sprache (NFR5):** „Answer in the language of the question“ ist aus dem Standard-Prompt entfernt. Neue `system_prompt_sha` – Ergebnisse vor und nach 3.3 nicht direkt vergleichen.
- **Log (FR11):** neue Spalten `system_prompt_source`, `export_format`, `export_scope_detail`, `export_tokens_est`, `export_tokens_exact`, `previous_turns`, `user_message`. Ein altes `answers_log.csv` wird umbenannt (`_old_…`), es geht nichts verloren.
- Handbuch mit neuen Bildern auf Stand 3.3 gebracht (alle Bedienelemente nummeriert, neue Abschnitte „Umfang und Format wählen“ und „Sehen und ändern, was gesendet wird“).
- Eine unveränderte installierte Standardfassung des System-Prompts zählt als `default` (nicht als `installed`).
- Nicht in CATIA getestet, sondern gegen nachgebildete CATIA-Schnittstellen kompiliert und mit simuliertem Gemini-Server geprüft.

## v3.2.1 – Fenster nicht mehr immer im Vordergrund

- Chat-Fenster und API-Key-Fenster waren „always on top“ und lagen über allen Programmen, auch bei minimiertem CATIA. Jetzt gehören sie zum CATIA-Hauptfenster: Sie bleiben über CATIA (man kann weiter im Modell klicken), werden mit CATIA minimiert und liegen nicht mehr über anderen Programmen.
- Dafür haben die Fenster keinen eigenen Minimieren-Knopf und keinen eigenen Eintrag in der Taskleiste mehr.

## v3.2 – Antwortstil entfernt

- Die Auswahl **Answer style (Basic/Expert)** ist entfernt: Für die Evaluation gibt es nur noch einen System-Prompt. Die Einstellungen sind neu angeordnet (Thinking neben AI model, Repetitions in der Evaluation-Zeile).
- Eine ältere `prompts/System_Prompt.md` mit dem Platzhalter `{answerStyle}` funktioniert weiter, der Platzhalter wird entfernt.
- Log: Spalte `detail_level` entfällt. Ein vorhandenes `answers_log.csv` mit den alten Spalten wird beim ersten Eintrag zu `answers_log_old_<Datum>.csv` umbenannt (plus `.xlsx`); es geht nichts verloren.
- Der System-Prompt ist dadurch ein anderer (neue `system_prompt_sha`) – Ergebnisse vor und nach v3.2 nicht direkt vergleichen.
- Das Bild `docs/images/settings-numbered.png` im Handbuch zeigt noch den alten Stand.

## Was ist neu

| Vorher | Jetzt |
|---|---|
| Rechtsklick → Untermenü → „Element View“ / „Model View“, danach RUN mit festem Prompt | Rechtsklick → **„Ask AI (SysML AI Q&A)“ → „Ask AI about the model…“**, oder Menüleiste **„Ask AI“**; Frage frei eintippen |
| Alter Projektname, Paket und Plugin-ID | Name **SysML AI Q&A**, Paket `de.hm.sysmlaiqa`, Plugin-ID `de.hm.sysmlaiqa.plugin`, JAR `sysml-ai-qa.jar`. API-Key und Einstellungen werden beim ersten Start übernommen, die alte Installation verschiebt `install.bat` ins Backup |
| 4 feste Prompt-Dateien (Basic/Expert × Element/Model View) | 1 editierbarer System-Prompt `prompts/System_Prompt.md` + eigene Frage (ab v3.2 ohne Antwortstil Basic/Expert) |
| Auch „Model View“ exportierte nur einen Ausschnitt (Tiefe 5) | Standard: **ganzes Modell** (`sysml_flat_export.vm`); Ausschnitt optional wählbar |
| Einstellungen verteilt (Tools → Settings), Modell/Temperatur fest im Code | **Alles in einem Fenster**: Modell, Kontext, Temperatur, Max Tokens, Grounding, neuer Chat pro Frage, Fallback |
| Grounding standardmäßig an, erzwingt `gemini-2.5-flash-lite` | Grounding standardmäßig **aus**, erzwingt kein Modell |
| Stiller Wechsel auf andere Modelle bei Überlastung | Nur wenn „Fallback model“ angehakt; antwortendes Modell wird immer angezeigt |
| – | **Schnellfragen** nach den Kategorien A–D der Aufgabenstellung, als Vorlagen mit Platzhaltern (`<add requirement>`, `<add element>`); bei „Ask AI about this element…“ ist das ausgewählte Element bzw. Requirement schon eingesetzt |
| – | **„Ask AI about this element…“**: Fragen zu einem Element, nur Element + Umgebung wird exportiert (kleiner, schneller); auch im Tools-Menü („…selected element…“, nimmt die aktuelle Auswahl) |
| – | **Thinking**: Denktiefe des Modells einstellbar (Model default / Minimal / Low / Medium / High) – weniger = schneller; wird geloggt |
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
- `ui/ShowExplanationAction.java`, `core/MenuConfigurator.java`, `core/SysmlAiQaPlugin.java` – ein Menüeintrag, neue Einstellungs-Keys
- `ui/SettingsDialog.java` – nur noch API-Key
- `api/GeminiExplanationService.java`, `api/ExplanationService.java`, `api/PromptLoader.java` – überarbeitet
- `api/AskSettings.java`, `api/EvaluationLog.java` – neu
- `api/Evaluation.java`, `api/EvaluationRecord.java`, `api/ModelExport.java`, `api/IdChecker.java`, `api/TestSuite.java`, `api/Hashing.java` – neu (Evaluation)
- `evaluation/README.md`, `evaluation/testcases_template.csv` – Anleitung und Vorlage für Testfälle
- `data_extraction/DataExtractionService.java` – `exportModel(...)` mit Scope ganzes Modell / Umgebung
- `resources/System_Prompt.md` – neuer System-Prompt (ersetzt die 4 festen Prompt-Dateien); der alte Installer-Ordner `installer/` wurde entfernt, installiert wird mit `install.bat`

## Evaluation
Siehe [`evaluation/README.md`](evaluation/README.md).

## Installieren
Ausführliche Anleitung: [`INSTALL.md`](INSTALL.md).

- **Nutzer:** fertiges Paket `SysML-AI-QA_v….zip` (GitHub → Releases) entpacken → CATIA schließen → `install.bat` doppelklicken. Kein Java nötig.
- **Entwickler:** `update.bat` (neuester Stand von GitHub) oder `build.bat` (eigener Ordner). Beide brauchen ein JDK 17+, bauen die JAR, legen das Paket für Nutzer unter `dist\` ab und installieren es mit `install.ps1`.

## Offene Punkte / Risiken
- **Nur gegen Platzhalter-Klassen kompiliert**, nicht in CATIA getestet. Das Build-Skript wurde mit PowerShell 7 gegen eine nachgebaute CATIA-Ordnerstruktur getestet, nicht auf Windows.
- Beim ersten Start nach dem Update wird Grounding einmalig ausgeschaltet (die alte Version hatte „an“ gespeichert).
- **Größe**: Das ganze Modell kann > 100 000 Tokens haben. Free-Tier-Limits (Tokens pro Minute) können zu HTTP 429 führen.
- **Cache**: Ob gecachte Tokens im Free Tier auf das Limit „Tokens pro Minute“ angerechnet werden, ist in Googles Doku nicht eindeutig – im Log (`cached_tokens`, `rate_limit_wait_s`) prüfen. Laut Preisliste ist Caching im Free Tier nicht für alle Modelle verfügbar (Flash-Lite nicht); dann wird automatisch inline gesendet.
- **System-Prompt ist eine Versuchsvariable** – Änderungen an `System_Prompt.md` dokumentieren.
- SysML v2 ist noch nicht unterstützt.
- Die README-Aussage „Model View = gesamtes Modell“ war schon vorher falsch (es war ein Ausschnitt).
