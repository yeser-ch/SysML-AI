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
„Ask AI…“ → Einstellungen wählen → **Export model** → **Run test suite…** → CSV auswählen.
Im Dialog: mehrere Modelle mit Komma trennen (z. B. `gemini-3.8-flash, gemini-3.5-flash-lite`).
- Jede Frage läuft in einem **neuen Chat**, ohne Fallback-Modell.
- **Repetitions** > 1: jede Frage wird mehrfach gestellt → Konsistenz messbar (Spalte `answer_sha`: gleiche Prüfsumme = wortgleiche Antwort).
- **Modell nur einmal hochladen** („Upload model once“, Standard): Das Modell geht pro KI-Modell einmal als Gemini-Cache hoch (60 min gültig), danach nur noch die Fragen. Nicht jedes Modell erlaubt das im Free Tier (laut Google z. B. die Flash-Lite-Modelle nicht) – dann wird automatisch inline gesendet; Spalte `model_transfer` zeigt, was passiert ist.
- **Pause:** Standard 0. Meldet Google ein Rate-Limit pro Minute (HTTP 429), wartet das Plugin genau die von Google genannte Zeit und versucht es erneut (Spalte `rate_limit_wait_s`). Bei einem **Tageslimit** werden die restlichen Fragen für dieses Modell übersprungen.
- „Stop test run“ bricht sofort ab, auch die gerade laufende Anfrage (steht mit `finish_reason = CANCELLED` im Log); bisherige Ergebnisse bleiben gespeichert.
- Der Testlauf nutzt Format (SysML v1/v2), Umfang und System-Prompt, wie sie gerade im Fenster eingestellt sind.

## 3. Ergebnisse
Ordner `Dokumente/SysML-AI-QA/` (Button „Open results“):

| Datei | Inhalt |
|---|---|
| `runs/run_<datum-zeit>.xlsx` | ein Testlauf, formatiert für Excel – **diese Datei bewerten** |
| `answers_log.xlsx` | alles, auch Chat-Fragen (`run_id = chat`), formatiert – wird nach jeder Antwort neu erzeugt |
| `runs/run_<datum-zeit>.csv`, `answers_log.csv` | dieselben Daten als Rohdaten (Quelle für die .xlsx, für Auswertungen mit Python/R) |
| `archive/prompts/<sha>.md` | System-Prompt im Wortlaut, Dateiname = `system_prompt_sha` |
| `archive/exports/<sha>.json` / `.sysml` | Modell-Export genau wie gesendet (SysML v1 JSON bzw. SysML v2 Text), Dateiname = `export_sha` |

Wichtige Spalten: `model_used`, `model_version` (**tatsächlich antwortendes Modell** – Google leitet Aliase um, z. B. `gemini-3.5-flash` → `gemini-3.6-flash`; für den Modellvergleich diese Spalte nehmen), `model_transfer` (`inline` / `cache` / `cache_new`), `cached_tokens`, `temperature`, `system_prompt_sha`, `export_sha`, `export_elements`, `export_relations`, `prompt_tokens`, `answer_tokens`, `finish_reason` (`MAX_TOKENS` = abgeschnitten), `duration_s`, `thinking_level` / `thinking_used` / `thoughts_tokens` (Denktiefe: eingestellt / tatsächlich / verbrauchte Denk-Tokens).

Neu ab 3.3 bzw. 3.3.1 (Lastenheft FR11):

| Spalte | Inhalt |
|---|---|
| `system_prompt_source` | `default` = Standardfassung, `custom` = im Fenster geändert, `installed` = Datei im Plugin-Ordner |
| `export_format` | `sysml_v1_json` (SysML v1), `sysml_v2_text` (v1-Modell als SysML-v2-Text) oder `sysml_v2_native` (SysML-v2-Modell aus CATIA) |
| `scope` | `FULL_MODEL`, `SELECTION` (gewählte Pakete/Elemente), `FOCUS_NEIGHBOURHOOD` oder `SYSML_V2_FILES` (SysML-v2-Dateien aus CATIA) |
| `export_scope_detail` | gewählte Pakete/Elemente mit ID bzw. Element und Tiefe |
| `export_tokens_est` / `export_tokens_exact` | Tokens der Modelldaten: geschätzt (Zeichen / 4) / exakt laut Google mit Modellname |
| `previous_turns` | Frage/Antwort-Paare davor im selben Chat (0 = neuer Chat) |
| `user_message` | exakte Frage-Nachricht an die KI, inkl. ausgewähltem Element |
| `focus_element` | ausgewähltes Element, wie es der KI genannt wurde; leer, wenn *Mention selected element* aus war |
| `allow_fallback`, `upload_once`, `mention_focus` | Schalter *Fallback model*, *Upload model once*, *Mention selected element* |
| `repetitions_set`, `pause_s` | eingestellte Wiederholungen, Extra-Pause im Testlauf |
| `relation_depth`, `neighbour_depth` | *Depth* bei *Selected element + surroundings* (sonst leer) |
| `tool_tokens` | Tokens der Google-Suche (nur bei Grounding) |
| `total_tokens` | insgesamt verbrauchte Tokens der Anfrage laut Google (Eingabe inkl. Cache + Antwort + Denken + Suche) |

Die `.xlsx` hat zusätzlich das Blatt **Tokens** mit Summen je antwortendem Modell (`model_version`) und insgesamt. Unbekannte Tokenzahlen (z. B. bei Fehlern) sind leer statt `-1`, damit Summen stimmen.

Alle Spalten mit Erklärung stehen im Blatt **Columns** jeder `.xlsx`. Formate und Aufbau einer Anfrage: [`docs/EXPORTFORMATE.md`](../docs/EXPORTFORMATE.md).

**Bewertung:** Die Spalten `rating` und `rating_notes` sind leer – dort tragt ihr eure Bewertung ein. Das Schema legt ihr selbst fest (z. B. `correct / partial / wrong / hallucinated / correctly_refused`). Es sollte vor dem ersten Lauf feststehen und von zwei Personen unabhängig angewendet werden.

### Bearbeiten und Löschen
Alles sind normale Dateien – ihr dürft sie bearbeiten und löschen:
- **Testfälle** (eure CSV): beliebig ändern, Zeilen löschen/hinzufügen. Das Plugin liest sie bei jedem Start eines Testlaufs neu.
- **Ergebnisdateien**: Das Plugin hängt nur Zeilen an und liest sie nie zurück. Zeilen oder ganze Dateien löschen ist kein Problem.
- **Empfehlung zum Bewerten:** in `runs/run_….xlsx` bewerten (Spalte `rating` hat eine Auswahlliste). Diese Datei wird nach dem Testlauf nicht mehr verändert. Die CSV bleibt als unveränderte Rohdaten erhalten. `answers_log.xlsx` wird bei jeder Antwort neu erzeugt – dort nicht bewerten.
- Ist eine Ergebnisdatei gerade in Excel geöffnet, kann das Plugin nicht hineinschreiben (Windows sperrt sie). Dann landet die Zeile in `…_while-open.csv` und im Chat erscheint ein Hinweis.
- Die Kopfzeile nicht umbenennen – sonst legt das Plugin eine neue Datei an und benennt die alte in `…_old_….csv` um.
- `archive/` nur löschen, wenn ihr die Prüfsummen nicht mehr nachschlagen müsst.

## 4. ID-Check (Halluzinationen)
„Cite element IDs“ ergänzt den System-Prompt: Die KI soll zu jedem genannten Element `[id:...]` angeben. Das Plugin prüft, ob es diese IDs im Export gibt:
- `ids_cited`, `ids_found`, `ids_unknown`, `unknown_ids`
- Unbekannte IDs = **sicher erfundene** Elemente bzw. Referenzen.

Grenzen:
- Eine **echte** ID mit **falscher Aussage** dazu fällt nicht auf → weiterhin manuell bewerten.
- Nennt die KI ein Element ohne ID, wird es nicht geprüft (`ids_cited` vergleichen).
- Der Schalter **verändert den Prompt** (andere `system_prompt_sha`). Innerhalb eines Experiments konstant lassen. Ob die ID-Pflicht die Antwortqualität verändert, wäre eine eigene Versuchsfrage.

## 5. Reproduzierbarkeit
Zwei Antworten sind nur dann direkt vergleichbar, wenn `system_prompt_sha`, `export_sha` (enthält Format und Umfang), `model_version`, `thinking_used`, `temperature`, `max_tokens`, `grounding`, `require_ids`, `previous_turns` und `focus_element` gleich sind. `model_transfer` (Cache oder inline) ändert den Gesprächsaufbau nicht – sollte sich trotzdem ein Unterschied zeigen, ist das eine Beobachtung wert. Variiert pro Experiment genau **eine** Größe, z. B. nur `model_used` oder nur das Testmodell.
