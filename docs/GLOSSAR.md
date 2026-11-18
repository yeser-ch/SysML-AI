# Glossar

Begriffe des Projekts **„How well does AI understand the SysML model?“** (Systems Engineering Project, Hochschule München, WS 2026/27). Das Glossar legt fest, wie das Team die Begriffe verwendet – vor allem die, mit denen KI-Antworten bewertet werden. Stand: 18.11.2026, Plugin-Version 3.5.2.

**Herkunft** (letzte Spalte):

| Kürzel | Bedeutung |
| --- | --- |
| **P** | Vorgabe aus Aufgabenstellung bzw. Lastenheft (C. Zuccaro, S. Weeger) |
| **V** | Vorgängerprojekt „Schrödingers Block“ (SoSe 2026, [hirtleo/SchroedingersBlock](https://github.com/hirtleo/SchroedingersBlock)) |
| **Q** | unser Plugin „SysML AI Q&A“ (dieses Repository) |
| **S** | Standardbegriff aus SysML, Systems Engineering oder KI |
| **N** | neue Festlegung des Teams – **Vorschlag, im Team abzustimmen** |

**Inhalt**

1. [SysML-Grundbegriffe](#1-sysml-grundbegriffe)
2. [Die vier untersuchten Fähigkeiten](#2-die-vier-untersuchten-fähigkeiten)
3. [KI- und LLM-Begriffe](#3-ki--und-llm-begriffe)
4. [Begriffe des Plugins](#4-begriffe-des-plugins)
5. [Evaluation und Bewertung](#5-evaluation-und-bewertung)
6. [Systems Engineering und Projektmanagement](#6-systems-engineering-und-projektmanagement)
7. [Begriffe aus dem Vorgängerprojekt](#7-begriffe-aus-dem-vorgängerprojekt)
8. [Offene Punkte](#8-offene-punkte)

---

## 1. SysML-Grundbegriffe

Die Beziehungstypen (satisfy, verify, deriveReqt …) sind der Kern der Testfragen. Gerade bei ihnen muss klar sein, was sie aussagen und was nicht.

| Begriff | Bedeutung | Herkunft |
| --- | --- | --- |
| SysML | Systems Modeling Language: Sprache zur Modellierung technischer Systeme (Anforderungen, Struktur, Verhalten, Parameter). | S |
| SysML v1 | Version 1.x, als UML-Profil definiert. Im Plugin: Format *SysML v1 (JSON export)*. | S, Q |
| SysML v2 | Neue Generation, eigenständig auf KerML aufgebaut, mit offizieller Textnotation. Laut Aufgabe zu unterstützen und mit v1 zu vergleichen. Im Plugin: Format *SysML v2 model (CATIA export)*. | S, P, Q |
| SysML v2 Textual Notation | Offizielle Textsprache von SysML v2 (OMG), Dateien `.sysml`, z. B. `part def`, `requirement def`, `satisfy … by …`. | S |
| Block / `part def` | Grundbaustein der Struktur (v1: Block, v2: `part def`), z. B. *Roboter*, *Batterie*. | S |
| Part Property / `part` | Verwendung eines Blocks als Teil eines anderen (Komposition), z. B. Roboter hat eine Batterie. | S |
| Value Property / `attribute` | Größe mit Wert und Einheit, z. B. Masse = 12 kg. | S |
| Port, Interface | Schnittstelle eines Blocks, über die Energie, Daten oder Material fließen. | S |
| Connector | Verbindung zwischen Parts bzw. Ports innerhalb eines Blocks. | S |
| Item Flow | Was über eine Verbindung fließt (Daten, Energie, Material). Grundlage für Frage B-02. | S |
| Requirement | Modellelement mit Anforderungstext und ID, z. B. *Betriebsdauer ≥ 4 h*. | S |
| «satisfy» | „Element X soll Anforderung Y erfüllen.“ Eine **Behauptung des Modellierers, kein Nachweis** (Frage C-01). | S |
| «verify» | „Testfall bzw. Analyse prüft Anforderung Y.“ Sagt nichts über das Prüfergebnis aus. | S |
| «deriveReqt» | Anforderung B ist aus Anforderung A abgeleitet. | S |
| «refine» | Ein Element präzisiert eine Anforderung (z. B. Use Case verfeinert Requirement). | S |
| «trace» | Allgemeine Verknüpfung ohne genaue Semantik. | S |
| «allocate» | Zuordnung, z. B. Funktion wird einem Block zugewiesen. | S |
| Containment / Owner | Besitzbeziehung: welches Element ein anderes enthält (Paket → Block). Im Export als *parent_hierarchy* / *owned_element*. | S, Q |
| Constraint Block | Block mit einer Gleichung, z. B. Betriebsdauer = Kapazität / Leistung. | S |
| Parametric Diagram | Verbindet Constraints mit Value Properties. Damit wird z. B. der Zusammenhang Masse ↔ Betriebsdauer modellierbar (Vorgabe für das Testmodell). | S, P |
| BDD / IBD | Block Definition Diagram (Typen, Hierarchie) / Internal Block Diagram (innerer Aufbau, Verbindungen). | S |
| Namespace (v2) | Paket bzw. Namensraum in SysML v2. Bei v2 wählt man den Exportumfang über die Namespaces im CATIA-Export. | S, Q |
| Qualifizierter Name | Eindeutiger Pfadname eines Elements, z. B. `RobotSystem::Battery`. Bei SysML v2 die „ID“ im ID-Check. | S, Q |
| Direkte / indirekte Beziehung | Direkt: eine Beziehung zwischen zwei Elementen (Pfadlänge 1). Indirekt: Verbindung über Zwischenelemente (Pfadlänge ≥ 2). | N |

## 2. Die vier untersuchten Fähigkeiten

Die vier Kategorien sind vorgegeben (P). Das Abgrenzungskriterium ist ein Vorschlag (N), damit jede Testfrage genau einer Kategorie zugeordnet werden kann. Die Fragen A-01 … D-02 sind die Schnellfragen des Plugins (siehe [`HANDBUCH.md`](../HANDBUCH.md#9-schnellfragen)).

| Kategorie | Bedeutung | Fragen im Katalog | Abgrenzungskriterium (N) |
| --- | --- | --- | --- |
| **A – Information Retrieval** | Vorhandene Elemente und direkte Beziehungen finden und korrekt wiedergeben. | A-01, A-02 | Die Antwort steht explizit im Modell, Pfadlänge 1. |
| **B – Structural Reasoning** | Direkte und indirekte Verbindungen über mehrere Schritte verfolgen. | B-01, B-02 | Die Antwort erfordert Pfadlänge ≥ 2 oder die Kombination mehrerer Beziehungen. |
| **C – Semantic Reasoning** | Fachliche Bedeutung und Grenzen von SysML-Beziehungen verstehen. | C-01, C-02 | Die Frage zielt auf die Aussagekraft einer Beziehung, nicht auf ihr Vorhandensein. |
| **D – Uncertainty / Missing Information** | Erkennen, dass das Modell für eine Aussage nicht reicht, und das offen sagen. | D-01, D-02 | Die korrekte Antwort lautet „nicht entscheidbar“ bzw. nennt die fehlenden Daten. |

## 3. KI- und LLM-Begriffe

| Begriff | Bedeutung | Herkunft |
| --- | --- | --- |
| LLM (Large Language Model) | Sprachmodell, das Text erzeugt; bei uns Google Gemini. | S |
| KI-Modell | Konkretes LLM, z. B. `gemini-3.8-flash`. Nicht verwechseln mit dem **SysML-Modell**. | Q |
| KI-Modellgröße | Laut Aufgabe ein Einflussfaktor. Gemini nennt keine Parameterzahlen; wir vergleichen deshalb **Modellklassen**: Flash (groß) gegen Flash-Lite (klein). Pro-Modelle sind im Free Tier nicht per API nutzbar – Einschränkung für die Methodik. | P, Q |
| Modellversion (`model_version`) | Das **tatsächlich antwortende** Modell. Google leitet Aliase um (z. B. `gemini-3.5-flash` → `gemini-3.6-flash`); für Vergleiche immer diese Spalte nehmen. | Q |
| Prompt | Gesamte Eingabe an das LLM: System-Prompt, Modelldaten, ggf. früherer Chat und Frage. | S |
| System-Prompt | Feste Anweisung an die KI je Format (`resources/System_Prompt*.md`). Ist eine **Versuchsvariable**, jede Fassung wird mit Prüfsumme protokolliert. | Q |
| User Message | Die Frage-Nachricht, ggf. mit dem ausgewählten Element davor (*Mention selected element*). | Q |
| Token | Texteinheit, in der das LLM rechnet. Eingabelänge, Limits und Verbrauch werden in Tokens gemessen. | S |
| Kontextfenster / Eingabelimit | Maximale Tokenzahl pro Anfrage, bei den genutzten Gemini-Modellen etwa 1.048.576. Ist der Export größer, wird die Statuszeile rot. | S, Q |
| Temperatur | Zufälligkeit der Antwort (0–2). 0 = möglichst gleiche Antworten, empfohlen für Messungen. | S, Q |
| Thinking | Wie viel die KI vor der Antwort intern „nachdenkt“ (*Minimal* … *High*). Kann besonders Kategorie B beeinflussen. | S, Q |
| Halluzination | Aussage, die nicht durch die Modelldaten gedeckt ist: erfundene Elemente, Werte oder Beziehungen. | S, P |
| Grounding | Im Plugin: die KI darf **Google durchsuchen**. Für Messungen aus, weil die Antwort nur aus dem SysML-Modell kommen soll. | Q |
| Modellbasiertheit | Eine Antwort stützt sich nur auf die mitgesendeten Modelldaten. (Allgemein wird das in der Literatur oft ebenfalls „Grounding“ genannt – bei uns zur Unterscheidung so bezeichnet.) | N |
| Weltwissen | Allgemeines Wissen des LLMs aus dem Training (z. B. typische Akkukapazitäten). Wird getrennt von Aussagen aus dem Modell bewertet. | N |
| Free Tier | Kostenlose Nutzung der Gemini-API ohne Billing, mit Minuten- und Tageslimits (HTTP 429). Google darf die Daten dort zur Produktverbesserung nutzen. | Q |

## 4. Begriffe des Plugins

Ausführlich in [`HANDBUCH.md`](../HANDBUCH.md) und [`docs/EXPORTFORMATE.md`](EXPORTFORMATE.md).

| Begriff | Bedeutung | Herkunft |
| --- | --- | --- |
| SysML AI Q&A | Unser CATIA-Plugin: freie Fragen an Gemini zum SysML-Modell, mit vollständiger Protokollierung. Weiterentwicklung von „Schrödingers Block“. | Q |
| CATIA Magic | Modellierungswerkzeug von Dassault Systèmes (2026x), in das das Plugin eingebunden ist; früher „Cameo Systems Modeler“. | S |
| Context | Welcher Teil des SysML-v1-Modells gesendet wird: *Whole model*, *Selected packages / elements*, *Selected element + surroundings*. Log: `scope`. | Q |
| Format | Wie das Modell gesendet wird: *SysML v1 (JSON export)* oder *SysML v2 model (CATIA export)*. Log: `export_format`. | Q |
| Export | Auslesen der Modelldaten aus CATIA (Knopf *Export model* / *Resend model*). v1 über Velocity-Templates als JSON, v2 als Textdateien aus dem CATIA-eigenen Export. | Q |
| Umgebung (surroundings) | Das ausgewählte Element mit Besitzern, Nachbarn und über Beziehungen verbundenen Elementen. | Q |
| Relation depth | Wie viele Schritte nach unten, nach oben und entlang von Beziehungen exportiert werden (0–10, Standard 5). | Q |
| Neighbour depth | Auf wie vielen Ebenen nach oben die Nachbarelemente (gleiches Paket) mitkommen (0–5, Standard 3). | Q |
| Fokuselement | Das ausgewählte Element; wird mit *Mention selected element* in der ersten Frage genannt. Log: `focus_element`. | Q |
| Upload model once | System-Prompt und Modell gehen einmal als Gemini-Cache hoch (60 min gültig), danach nur die Fragen. Ändert den Inhalt nicht. Log: `model_transfer`. | Q |
| Fallback model | Bei Überlastung automatisch ein anderes KI-Modell nehmen. Für Messungen aus, im Testlauf immer aus. | Q |
| New chat per question | Jede Frage wird ohne vorherigen Chat beantwortet. Log: `previous_turns` (0 = neuer Chat). | Q |
| Show request | Zeigt genau, was gesendet wird (System-Prompt, Modelldaten, Frage, Parameter). | Q |
| Schnellfragen / Fragenkatalog | Die acht Fragevorlagen A-01 … D-02 mit Platzhaltern wie `<requirement ID>`. | Q |
| Testsuite / Testlauf | Automatisches Abarbeiten aller Testfälle einer CSV-Datei, optional für mehrere KI-Modelle nacheinander. | Q |
| ID-Check (Cite element IDs) | Die KI muss zu jedem genannten Element die ID angeben; das Plugin prüft, ob es sie im Export gibt. Unbekannte IDs = sicher erfundene Elemente. Eine echte ID mit falscher Aussage fällt **nicht** auf. Ändert den System-Prompt. | Q |
| Prüfsumme (SHA) | Fingerabdruck eines Inhalts. `system_prompt_sha` und `export_sha` zeigen, ob zwei Anfragen denselben Prompt bzw. Export hatten. | Q |
| Archiv | `archive/prompts/` und `archive/exports/`: jeder verwendete System-Prompt und Export im Wortlaut, Dateiname = Prüfsumme. | Q |
| Statuszeile | Zeigt nach dem Export Format, Anzahl Elemente und Beziehungen, Größe, Tokens und Prüfsumme. | Q |

## 5. Evaluation und Bewertung

Ein Großteil dieser Begriffe legt fest, wie bewertet wird. Sie müssen **vor dem ersten Experiment** feststehen.

| Begriff | Bedeutung | Herkunft |
| --- | --- | --- |
| Testmodell | Eigens erstelltes SysML-Modell mit bekannten Inhalten und fachlichen Zusammenhängen (z. B. Masse ↔ Betriebsdauer eines Roboters). | P |
| SysML-Modellgröße | Umfang des gesendeten Modells, gemessen als `export_elements`, `export_relations` und `export_tokens_exact`. Laut Aufgabe ein Einflussfaktor. | P, Q |
| Modellvariante | Verschiedene Größen bzw. Versionen desselben Testmodells (z. B. klein / mittel / groß; v1 / v2), um den Einfluss gezielt zu messen. | N |
| Testfall | Eine Zeile der Testfall-CSV: `test_id`, `category`, `question`, `expected_answer`, `notes`. | Q |
| Ground Truth / Referenzantwort | Vorab festgelegte korrekte Antwort (`expected_answer`) inkl. der Elemente, die genannt werden müssen. Wird vor dem Lauf erstellt und danach nicht mehr geändert. | P, N |
| Experiment | Mehrere Testläufe, bei denen **genau eine** Größe variiert wird (z. B. nur das KI-Modell oder nur die SysML-Version). | Q |
| Unabhängige Variable | Was gezielt verändert wird: SysML-Version, SysML-Modellgröße, KI-Modellgröße, Fragekategorie. | P, N |
| Kontrollvariable | Was konstant bleibt: System-Prompt, Temperatur, Thinking, Grounding, ID-Check, Context, Mention selected element. | Q |
| Rating | Bewertung jeder Antwort in der Spalte `rating`. Vorgeschlagene Werte im Plugin: `correct`, `partial`, `wrong`, `hallucinated`, `correctly_refused`. Bedeutung siehe nächste Zeilen. | Q |
| correct | Antwort stimmt mit der Referenzantwort überein, alle geforderten Elemente genannt, nichts Falsches. | N |
| partial | Teilweise richtig: nicht alle geforderten Elemente genannt oder ungenau, aber nichts Erfundenes. | N |
| wrong | Falsch, ohne erfundene Elemente (z. B. falsche Beziehung, falsche Schlussfolgerung). | N |
| hallucinated | Enthält mindestens ein erfundenes Element, einen erfundenen Wert oder eine erfundene Beziehung. | N |
| correctly_refused | Die KI sagt zu Recht, dass die Information im Modell fehlt (erwartetes Ergebnis in Kategorie D). | N |
| Unbegründete Schlussfolgerung | Aussage, die über das Modell hinausgeht, ohne das kenntlich zu machen (z. B. „satisfy beweist die Erfüllung“). | P, N |
| Vollständigkeit (Recall) | Anteil der geforderten Elemente, die die Antwort nennt. | S, N |
| Präzision | Anteil der genannten Elemente, die korrekt sind. | S, N |
| Halluzinationsrate | Anteil der Antworten mit Rating `hallucinated` bzw. mit `ids_unknown` > 0. | N |
| Konsistenz | Wie stabil die Antworten bei Wiederholungen sind. Gleiche `answer_sha` = wortgleiche Antwort. | Q |
| Repetitions | Wie oft jede Frage gestellt wird (jeweils neuer Chat), um Schwankungen trotz Temperatur 0 sichtbar zu machen. | Q |
| Bewerterübereinstimmung | Zwei Personen bewerten unabhängig; die Übereinstimmung (z. B. Cohen's Kappa) zeigt, ob das Schema eindeutig ist. | S, N |
| Reproduzierbarkeit | Zwei Antworten sind vergleichbar, wenn u. a. `system_prompt_sha`, `export_sha`, `model_version`, `temperature`, `thinking_used`, `grounding` und `require_ids` gleich sind (siehe [`evaluation/README.md`](../evaluation/README.md#5-reproduzierbarkeit)). | Q |

## 6. Systems Engineering und Projektmanagement

| Begriff | Bedeutung | Herkunft |
| --- | --- | --- |
| Lastenheft | Anforderungen des Auftraggebers an das Plugin: *Lastenheft für Plugin Schrödingers Block (SysML Verständnis)*, V0.1 vom 03.11.2026. Abdeckung: [`LASTENHEFT_ABDECKUNG.md`](LASTENHEFT_ABDECKUNG.md). | P |
| FR / NFR | Funktionale bzw. nichtfunktionale Anforderung (z. B. FR7 = Export der SysML-v2-Textnotation). | S, P |
| Anforderung | Prüfbare Aussage darüber, was System oder Projekt leisten muss. Nicht verwechseln mit dem SysML-Element *Requirement* im Testmodell. | S |
| Stakeholder | Personen oder Gruppen mit Interesse am Projekt (Betreuer, Team, künftige Nutzer). | S |
| User Story | Anforderung aus Nutzersicht: „Als … möchte ich …, damit …“. | S |
| Akzeptanzkriterium | Bedingung, unter der eine Anforderung als erfüllt gilt. | S |
| Systemkontext | Abgrenzung des Systems gegenüber der Umgebung (Nutzer, CATIA, Gemini-API). | S |
| Schnittstelle | Übergabestelle zwischen Systemteilen, z. B. Plugin ↔ Gemini-API. | S |
| Traceability | Nachverfolgbarkeit: Anforderung → Umsetzung → Testfall → Ergebnis. | S |
| Verifikation | „Bauen wir das System richtig?“ – Prüfung gegen die Anforderungen (z. B. Lastenheft). | S |
| Validierung | „Bauen wir das richtige System?“ – Prüfung gegen den eigentlichen Zweck bzw. die Forschungsfrage. | S |
| Vorgehensmodell | Gewählter Projektablauf, mit Begründung. Das Vorgängerprojekt nutzte Water–Scrum–Fall; für uns nicht verpflichtend. | S, V |
| Sprint, Product Backlog | Feste Iteration mit Planning, Review, Retrospektive / priorisierte Liste offener Arbeitspakete. | S |
| Meilenstein | Prüfbarer Zwischenstand zu einem Termin. Fest: Projektvernissage 21.01.2027, Abschlusspräsentation 08.02.2027. | P |
| Q/C/T/R | Quality, Cost, Time, Risk – die vier Steuergrößen im Projektcontrolling. | P |
| Risiko | Mögliches Ereignis mit negativer Wirkung, bewertet nach Wahrscheinlichkeit × Auswirkung. | S |
| Lessons Learned | Dokumentierte Erfahrungen für künftige Projekte. | S |

## 7. Begriffe aus dem Vorgängerprojekt

Diese Begriffe kommen in der Dokumentation von „Schrödingers Block“ vor, gibt es in unserem Plugin aber nicht mehr bzw. in anderer Form.

| Begriff (V) | Bedeutung damals | Bei uns |
| --- | --- | --- |
| Element View | Erklärung eines einzelnen Elements, Exporttiefe fest im Code (Abhängigkeiten 2, Nachbarn 1). | *Context: Selected element + surroundings* mit einstellbarer Depth |
| Model View | Erklärung des realen Systems hinter dem Element, größere Exporttiefe (5 / 3). | *Context: Whole model* bzw. höhere Depth |
| Basic / Expert | Zwei feste Detailstufen mit eigenen Prompts. | Freie Fragen, ein System-Prompt je Format |
| Grounding standardmäßig an | Google-Suche war per Standard aktiv. | Standard **aus** |
| Modellkaskade | Automatischer Wechsel zwischen Gemini-Modellen. | *Fallback model*, Standard aus |
| Velocity-Export | `sysml_partial_export.vm` | weiterverwendet und erweitert (`sysml_flat_export.vm` für das ganze Modell) |

## 8. Offene Punkte

- [ ] Bedeutung der Rating-Werte (Abschnitt 5) im Team abstimmen und vor dem ersten Lauf einfrieren.
- [ ] Größenklassen der Testmodelle festlegen (Grenzen für klein / mittel / groß nach `export_elements` bzw. Tokens).
- [ ] Festlegen, welche KI-Modelle als „klein“ und „groß“ gelten (Vorschlag Handbuch: `gemini-3.5-flash-lite` gegen `gemini-3.8-flash`).
- [ ] Abgrenzungskriterien der Kategorien A–D (Abschnitt 2) bestätigen.
- [ ] Verantwortliche Person für dieses Glossar benennen.

Neue Begriffe bitte mit Herkunft eintragen. Änderungen an Abschnitt 2 und 5 im Team-Meeting bestätigen, weil sie die Vergleichbarkeit der Ergebnisse betreffen.
