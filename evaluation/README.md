# Evaluation mit dem Plugin

## 1. Testfälle anlegen
Vorlage: `testcases_template.csv` (in Excel öffnen, Trennzeichen `;`). Die Beispiel-Fragen beziehen sich auf ein ausgedachtes Roboter-Modell – bitte durch Fragen zu **eurem** Testmodell ersetzen.

| Spalte | Pflicht | Inhalt |
|---|---|---|
| `test_id` | nein | z. B. `A-01` (fehlt sie, wird `T01`, `T02` … vergeben) |
| `category` | nein | `A` Retrieval, `B` Structure, `C` Semantics, `D` Uncertainty |
| `question` | **ja** | die Frage, genau so wie sie an die KI geht |
| `expected_answer` | empfohlen | Referenzantwort (Ground Truth) |
| `notes` | nein | z. B. warum die Frage gestellt wird |

Deutsche Spaltennamen (`id`, `kategorie`, `frage`, `erwartete_antwort`) gehen auch. Zeilen mit `#` am Anfang der ID werden übersprungen.

## 2. Testlauf starten
„Ask the model…“ → Einstellungen wählen → **Run test suite…** → CSV auswählen.
Im Dialog: mehrere Modelle mit Komma trennen (z. B. `gemini-3.5-flash, gemini-3.1-flash-lite`) und eine Pause zwischen den Anfragen setzen.
- Jede Frage läuft in einem **neuen Chat**, ohne Fallback-Modell.
- **Repetitions** > 1: jede Frage wird mehrfach gestellt → Konsistenz messbar (Spalte `answer_sha`: gleiche Prüfsumme = wortgleiche Antwort).
- Free Tier: Bei jeder Anfrage geht das ganze Modell mit (siehe Token-Schätzung in der Statuszeile). Bei ~100 000 Tokens pro Anfrage eine Pause von 30–60 s einstellen, sonst kommt HTTP 429.
- „Stop test run“ bricht nach der laufenden Anfrage ab; bisherige Ergebnisse bleiben gespeichert.

## 3. Ergebnisse
Ordner `Dokumente/SchroedingersBlock/` (Button „Open results“):

| Datei | Inhalt |
|---|---|
| `runs/run_<datum-zeit>.csv` | ein Testlauf – **diese Datei bewerten** |
| `answers_log.csv` | alles, auch Chat-Fragen (`run_id = chat`) |
| `archive/prompts/<sha>.md` | System-Prompt im Wortlaut, Dateiname = `system_prompt_sha` |
| `archive/exports/<sha>.json` | Modell-Export, Dateiname = `export_sha` |

Wichtige Spalten: `model_used`, `temperature`, `system_prompt_sha`, `export_sha`, `export_elements`, `export_relations`, `prompt_tokens`, `answer_tokens`, `finish_reason` (`MAX_TOKENS` = abgeschnitten), `duration_s`.

**Bewertung:** Die Spalten `rating` und `rating_notes` sind leer – dort tragt ihr eure Bewertung ein. Das Schema legt ihr selbst fest (z. B. `correct / partial / wrong / hallucinated / correctly_refused`). Es sollte vor dem ersten Lauf feststehen und von zwei Personen unabhängig angewendet werden.

## 4. ID-Check (Halluzinationen)
„Cite element IDs“ ergänzt den System-Prompt: Die KI soll zu jedem genannten Element `[id:...]` angeben. Das Plugin prüft, ob es diese IDs im Export gibt:
- `ids_cited`, `ids_found`, `ids_unknown`, `unknown_ids`
- Unbekannte IDs = **sicher erfundene** Elemente bzw. Referenzen.

Grenzen:
- Eine **echte** ID mit **falscher Aussage** dazu fällt nicht auf → weiterhin manuell bewerten.
- Nennt die KI ein Element ohne ID, wird es nicht geprüft (`ids_cited` vergleichen).
- Der Schalter **verändert den Prompt** (andere `system_prompt_sha`). Innerhalb eines Experiments konstant lassen. Ob die ID-Pflicht die Antwortqualität verändert, wäre eine eigene Versuchsfrage.

## 5. Reproduzierbarkeit
Zwei Antworten sind nur dann direkt vergleichbar, wenn `system_prompt_sha`, `export_sha`, `temperature`, `max_tokens`, `grounding` und `require_ids` gleich sind. Variiert pro Experiment genau **eine** Größe, z. B. nur `model_used` oder nur das Testmodell.
