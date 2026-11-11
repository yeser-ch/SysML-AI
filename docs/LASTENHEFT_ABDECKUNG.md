# Abdeckung des Lastenhefts (V0.1 vom 03.11.2026)

Zuordnung jeder Anforderung aus dem *Lastenheft für Plugin Schrödingers Block (SysML Verständnis)* (C. Zuccaro, S. Weeger) zur Umsetzung im Plugin ab **Version 3.3-ws2627**, mit Hinweis zur Prüfung. Grundlage für Traceability und Verifikation.

Status: ✅ erfüllt · 🟡 teilweise / mit Einschränkung · ⬜ offen

## Funktionale Anforderungen

| ID | Anforderung (Kurzform) | Status | Umsetzung | Prüfung |
| --- | --- | --- | --- | --- |
| FR1 | Exportumfang einstellbar, mindestens ganzes Modell; Bedarf begründen | ✅ | Auswahl *Context*. Begründung in [`EXPORTFORMATE.md`](EXPORTFORMATE.md#2-umfang-des-exports-fr1) | Je Umfang exportieren, Elementanzahl in der Statuszeile vergleichen |
| FR1.1 | gesamtes Modell | ✅ | *Whole model* (Standard), `sysml_flat_export.vm` | Export enthält alle Elemente außer Bibliotheken |
| FR1.2 | ausgewählte Pakete oder Elemente | ✅ | *Selected packages / elements* + **Choose…** (Modellbaum, Mehrfachauswahl, Vorauswahl aus dem Containment Tree). Verschachtelte Auswahl wird nicht doppelt exportiert | Zwei Pakete wählen → nur deren Inhalt im Export (Show request, Tab 2); Logspalte `export_scope_detail` |
| FR1.3 | einzelnes Element mit einstellbarer Kontexttiefe (Soll) | ✅ | *Selected element + surroundings* mit **Depth: relations** (0–10) und **neighbours** (0–5) | Tiefe 0 und 5 vergleichen: Elementanzahl ändert sich; Spalte `export_scope_detail` |
| FR2 | Größe vor dem Senden als Elementanzahl und Tokenzahl | ✅ | Statuszeile: Elemente, Beziehungen, KB, geschätzte und exakte Tokens (Gemini `countTokens`) für das gewählte Modell | Nach Export und Modellwechsel Statuszeile prüfen; Log `export_tokens_est`, `export_tokens_exact` |
| FR3 | exakt übergebener Modellinhalt einsehbar und speicherbar | ✅ | **Show request…** (Tab *Model data*, **Save all…**); Archiv `archive/exports/<export_sha>` mit genau dem übertragenen Inhalt | Gespeicherte Datei mit Archivdatei vergleichen (gleiche Prüfsumme) |
| FR4 | Wahl zwischen mehreren LLMs, keine automatische Auswahl | 🟡 | Freie Wahl aller Gemini-Modelle des API-Keys (**Refresh**), Modell-ID frei eintippbar. *Fallback model* standardmäßig aus und im Testlauf immer aus; Abweichungen stehen im Log (`model_used`, `model_version`). **Nur Google Gemini**, andere Anbieter (z. B. lokale Modelle) sind nicht angebunden | Log: `model_requested` = `model_used` bei ausgeschaltetem Fallback |
| FR5 | Temperatur und Thinking einstellbar und pro Anfrage dokumentiert | ✅ | *Temperature*, *Thinking*, *Max tokens*; Log `temperature`, `thinking_level`, `thinking_used`, `thoughts_tokens` | Werte ändern, Logzeile prüfen |
| FR6 | Export SysML v1 wie bisher | ✅ | *Format: SysML v1 (JSON export)* | – |
| FR7 | Export der textuellen Notation von SysML v2 | 🟡 | *Format: SysML v2 (textual notation)*: dasselbe v1-Modell in v2-Schreibweise (`SysmlV2Writer`), eigener System-Prompt. Kein natives v2-Modell; nicht gegen einen v2-Parser validiert (siehe [`EXPORTFORMATE.md`](EXPORTFORMATE.md#4-format-sysml-v2-textuelle-notation-fr7)) | Show request, Tab 2; Log `export_format = sysml_v2_text` |
| FR8 | Prompt in der GUI, nicht im Code | ✅ | Eingabefeld *Your question* | – |
| FR8.1 | Prompt-Vorlagen (z. B. pro Fehlerkategorie) | ✅ | *Quick questions* nach Kategorie A Retrieval, B Structure, C Semantics, D Uncertainty, aus echten Modellelementen erzeugt; Testfälle aus CSV mit Kategorie | Schnellfrage anklicken → Vorlage im Eingabefeld |
| FR9 | System-Prompt in der GUI sichtbar und änderbar, Nutzung immer dokumentieren | ✅ | **System prompt…** (je Format, *Reset to default*); gespeichert unter `Dokumente\SysML-AI-QA\prompts`. Jede Fassung im Wortlaut im Archiv, Log `system_prompt_sha`, `system_prompt_source` | Prompt ändern, Frage stellen → neue `system_prompt_sha`, `system_prompt_source = custom`, Datei in `archive/prompts` |
| FR10 | Grounding ein/aus, Zustand pro Anfrage protokolliert | ✅ | *Grounding*; Log `grounding` | – |
| FR11 | Protokoll pro Anfrage: Zeitstempel, Modell, Parameter, Grounding, System-Prompt, Prompt, exakter Export inkl. Umfang und Tokens, vollständige Antwort | ✅ | `answers_log.csv/.xlsx` und `runs/run_*.csv/.xlsx`: `timestamp`, `model_*`, Parameter, `grounding`, `system_prompt_sha` (+ Archiv), `question`, `user_message` (exakte Nachricht), `previous_turns`, `export_format`, `scope`, `export_scope_detail`, `export_sha` (+ Archiv), `export_tokens_*`, `prompt_tokens`, `answer` | Spaltenliste in [`evaluation/README.md`](../evaluation/README.md) |
| FR12 | konkrete Fehlermeldung bei nicht erreichbarer API, zu großem Export, leerer Antwort | ✅ | Nicht erreichbar: Ursache (DNS, Verbindung, SSL) und Hinweis auf Netz/Proxy. Zu groß: Warnung schon vor dem Senden (Vergleich mit `inputTokenLimit`), beim Senden Meldung mit Limit und Abhilfe. Leer: Fehler mit `finishReason` und Grund (Denk-Tokens verbraucht, Inhaltsfilter) statt einer „erfolgreichen“ leeren Antwort | Ohne Internet senden; sehr großes Modell mit kleinem Modell; *Max tokens* sehr klein + *Thinking High* |

## Nichtfunktionale Anforderungen

| ID | Anforderung (Kurzform) | Status | Umsetzung | Prüfung |
| --- | --- | --- | --- | --- |
| NFR1 | läuft in CATIA Magic 2026, hält Bedienkonzepte ein | ✅ | Kontextmenüs in Baum, Diagrammen und Tabellen, Menü *Ask AI*, Fenster gehört zum CATIA-Hauptfenster, Meldungen im Notification Window | Installation nach `INSTALL.md` |
| NFR2 | Einstellungen bleiben zwischen Sitzungen erhalten | ✅ | Java Preferences: Modell, Parameter, Grounding, Format, Tiefe, Optionen, API-Key; System-Prompt als Datei | CATIA neu starten, Einstellungen prüfen |
| NFR3 | nur in der GUI sichtbare Inhalte gehen an das LLM | ✅ | **Show request…** zeigt alle Teile der Anfrage; der System-Prompt ist im Fenster sichtbar; das ausgewählte Element wird nur mit *Mention selected element* gesendet (Text im Tooltip und in Show request) | Show request mit gespeicherter Anfrage vergleichen |
| NFR4 | Abbruch einer laufenden Anfrage | ✅ | *Send* wird während der Anfrage zu **Stop**; bricht die HTTP-Anfrage und Wartezeiten sofort ab. *Stop test run* bricht auch die laufende Anfrage ab. Abgebrochene Anfragen stehen mit `finish_reason = CANCELLED` im Log | Lange Anfrage starten, Stop → sofort „Cancelled by user.“ |
| NFR5 | GUI englisch, Antwortsprache nicht vorgeschrieben | ✅ | Oberfläche englisch. Die Zeile „Answer in the language of the question“ ist aus dem Standard-Prompt entfernt; unveränderte ältere Prompts werden als Standard erkannt | System prompt… ansehen |
| NFR6 | Dokumentation: Installation, Exportformate, Protokollstruktur, Quellcode | ✅ | [`INSTALL.md`](../INSTALL.md), [`EXPORTFORMATE.md`](EXPORTFORMATE.md), [`evaluation/README.md`](../evaluation/README.md), [`HANDBUCH.md`](../HANDBUCH.md), Quellcode kommentiert, Klassenübersicht in [`README.md`](../README.md) | – |

## Offene Punkte

- **FR4:** Andere Anbieter als Gemini (z. B. lokale Modelle über eine OpenAI-kompatible Schnittstelle wie Ollama) würden echte Modellgrößen-Vergleiche erleichtern. Nicht umgesetzt; als Erweiterung möglich (`ExplanationService` ist dafür als Schnittstelle angelegt).
- **FR7:** v2-Text mit einem SysML-v2-Parser prüfen und ggf. ein natives v2-Modell als Gegenprobe nutzen.
- **Test in CATIA:** Die Änderungen wurden außerhalb von CATIA gegen nachgebildete CATIA-Schnittstellen kompiliert und mit einem simulierten Gemini-Server getestet. Ein Abnahmetest in CATIA (Prüfspalte oben) steht aus.
