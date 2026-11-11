# Exportformate und Aufbau einer Anfrage

Dieses Dokument beschreibt, **was genau** das Plugin an die KI schickt (Lastenheft NFR6, FR3, NFR3): den Umfang des Exports, die drei Formate (SysML v1 JSON, SysML v2 umgewandelt aus v1, SysML-v2-Modell aus CATIA) und die Reihenfolge der Nachrichten. Die Struktur des Protokolls steht in [`evaluation/README.md`](../evaluation/README.md#3-ergebnisse).

Im Plugin zeigt **Show request…** genau diese Teile für die nächste Frage an und speichert sie auf Wunsch als Dateien.

---

## 1. Aufbau einer Anfrage

Jede Anfrage an Gemini besteht aus diesen Teilen, in dieser Reihenfolge:

| Nr. | Teil | Inhalt | Im Fenster sichtbar über |
| --- | --- | --- | --- |
| 1 | System-Prompt | Anweisung an die KI, je Format eine eigene Fassung. `{idInstruction}` wird bei *Cite element IDs* ersetzt | **System prompt…**, Show request (Tab 1) |
| 2 | Modelldaten | `# Model data (JSON export)`, `# Model data (SysML v2 textual notation)` bzw. `# Model data (SysML v2 textual notation, exported from CATIA)`, darunter der Export | Show request (Tab 2) |
| 3 | Bisheriger Chat | nur wenn *New chat per question* aus ist: frühere Fragen und Antworten | Show request (Tab 3) |
| 4 | Frage | `# Question` und die Frage. Bei *Mention selected element* steht bei der ersten Frage eines Chats davor: `# Currently selected element` mit Name, Typ, ID und Besitzer | Show request (Tab Question) |
| 5 | Parameter | Modell, Temperatur, maximale Antwortlänge, Denktiefe, Grounding | Show request (Tab Parameters) |

Mit *Upload model once* werden Teil 1 und 2 einmal als Gemini-Cache hochgeladen und danach nur noch referenziert. Der Inhalt ist identisch, nur der Transport ändert sich (Logspalte `model_transfer`).

Es wird nichts anderes gesendet. Der Antwortstil und die Antwortsprache werden nicht vorgegeben (NFR5): Die Sprache ergibt sich aus der Frage.

---

## 2. Umfang des Exports (FR1)

| Einstellung *Context* | Was exportiert wird | Logspalte `scope` | Wofür |
| --- | --- | --- | --- |
| **Whole model** | alle Elemente des Primärmodells außer SysML/UML-Bibliotheken und Profilen | `FULL_MODEL` | Standard für Messungen: alle Fragen haben dieselbe Grundlage |
| **Selected packages / elements** | die mit **Choose…** gewählten Pakete oder Elemente, jeweils mit allem, was sie enthalten | `SELECTION` | Einfluss der Modellgröße: dasselbe Modell in Teilen, große Modelle unter das Eingabelimit bringen |
| **Selected element + surroundings** | ein Element, seine Besitzer, Nachbarn und über Beziehungen erreichbare Elemente bis zur eingestellten Tiefe | `FOCUS_NEIGHBOURHOOD` | Fragen zu einem Element, Einfluss des Kontexts |

**Tiefe** (nur bei *surroundings*): *relations* = Anzahl Beziehungsschritte vom Element aus (0–10, Standard 5), *neighbours* = über wie viele Besitzer-Ebenen Nachbarelemente gesammelt werden (0–5, Standard 3).

Der genaue Umfang steht in der Logspalte `export_scope_detail` (gewählte Elemente mit ID bzw. Tiefe).

**Warum ein einstellbarer Umfang?** Das Lastenheft fragt, ob das nötig ist. Begründung:

- Das *ganze Modell* ist die Referenz und bleibt Standard.
- Die Forschungsfrage verlangt den **Einfluss der Modellgröße**. Mit *Selected packages / elements* lässt sich dasselbe Modell in definierten Teilmengen senden, ohne für jede Größe ein eigenes CATIA-Modell zu bauen.
- Große Modelle können das **Eingabelimit** eines kleinen KI-Modells überschreiten. Dann ist ein kleinerer Umfang die einzige Möglichkeit, das Modell überhaupt zu testen.
- Die Element-Umgebung beantwortet die Frage, ob mehr Kontext hilft oder ablenkt (z. B. Structural Reasoning bei Tiefe 1 gegenüber 5).

Beziehungen zu Elementen außerhalb des Umfangs bleiben als ID erhalten. Die KI sieht dann, dass es eine Beziehung gibt, aber nicht, wohin sie führt. Das ist bei der Bewertung (Kategorie D, fehlende Informationen) zu berücksichtigen.

---

## 3. Format SysML v1: JSON-Export

Erzeugt von den Velocity-Templates `sysml_flat_export.vm` (ganzes Modell, Auswahl) und `sysml_partial_export.vm` (Umgebung eines Elements) im Plugin-Ordner `data_extraction`.

### So läuft der Export ab

1. **Start:** Die Klasse `DataExtractionService` startet die in CATIA eingebaute Velocity-Template-Engine (dieselbe wie im Report Wizard) mit dem geöffneten Projekt und einer der beiden Vorlagen. Die Vorlage liest das Modell über die SysML-v1/UML-Schnittstelle von CATIA und schreibt Text.
2. **Ganzes Modell bzw. Auswahl** (`sysml_flat_export.vm`):
   - Startpunkt: alle obersten Elemente des Primärmodells (*Whole model*) bzw. die gewählten Pakete/Elemente (*Selected packages / elements*; verschachtelte Auswahl wird vorher bereinigt).
   - Rekursiver Durchlauf durch alles, was ein Element enthält (`ownedElement`).
   - Filter 1 – Bibliotheken: Elemente, deren qualifizierter Name mit `SysML`, `UML`, `StandardProfile`, `PrimitiveTypes`, `Cameo`, `MD Customization`, `Simulation Profile`, `Metric Suite` oder `Requirement Profile` beginnt, werden übersprungen.
   - Filter 2 – nur bekannte SysML-v1-Typen (Liste siehe unten); Diagramme, Tabellen und Kommentare fehlen absichtlich.
   - Pro Element ein JSON-Objekt mit den Feldern aus der Tabelle unten. Ausgehende Abhängigkeiten (`clientDependency`: Satisfy, Verify, Refine, DeriveReqt, Allocate, Trace …) stehen mit Typ, Stereotyp und Ziel-ID in `outgoing_relations`.
3. **Element mit Umgebung** (`sysml_partial_export.vm`), ausgehend vom gewählten Element:
   - nach oben: die Besitzer-Hierarchie,
   - nach unten: die eigenen Kind-Elemente,
   - über Beziehungen in beide Richtungen (aus- und eingehende Abhängigkeiten, Assoziationen, Generalisierungen/Spezialisierungen, Attribute, Ports) bis zur Tiefe *Depth: relations* (Standard 5),
   - zur Seite: Nachbarelemente über die Besitzer bis *Depth: neighbours* (Standard 3).
   - Jedes Element erhält zusätzlich `partial_source` und `partial_depth` (über welchen Weg, in welcher Tiefe gefunden).
4. **Ausgabe:** `Dokumente\SysML-AI-QA\exports\export_<full|selection|focus>_<Zeit>.json` (wird beim Schließen von CATIA gelöscht).
5. **Weiterverarbeitung im Plugin** (`ModelExport`): Elemente und Beziehungen zählen, alle IDs für den ID-Check sammeln, Prüfsumme bilden (`export_sha`), je nach Format in SysML-v2-Text umwandeln (Abschnitt 4), exakte Tokens bei Google zählen. Beim ersten Senden wird der übertragene Inhalt im Archiv gespeichert.

**Wann exportiert wird:** beim Öffnen des Fensters, bei Änderung von *Context* oder *Depth* und bei **Resend model**. Änderungen in CATIA erkennt das Plugin nicht von selbst.

```json
{
  "global_context": { "project_name": "Robot Demo", "export_info": "SysML Flat Graph Export for AI (v7.4 …)" },
  "elements": [
    { "id": "_19_0_…", "name": "Robot", "type": "Block", "stereotypes": ["Block"], "owner_id": "_19_0_…",
      "documentation": "…", "tagged_values": {}, "outgoing_relations": [
        { "relation_type": "Abstraction", "relation_stereotypes": ["Satisfy"], "target_id": "_19_0_…" } ] }
  ]
}
```

Jedes Element ist ein Objekt in einer flachen Liste. Die Hierarchie entsteht über `owner_id`, Beziehungen verweisen über IDs.

| Feld | Bei welchen Elementen | Inhalt |
| --- | --- | --- |
| `id`, `name`, `type`, `stereotypes`, `owner_id`, `documentation`, `tagged_values` | alle | Kennung, Name, CATIA-Typ, Stereotypen, Besitzer, Dokumentation, Tagged Values der Stereotypen |
| `requirement_text` | Requirement | Anforderungstext |
| `constraint_equation` | Constraint Block / Property | Gleichung |
| `default_value` | Properties | Standardwert |
| `property_type_id`, `property_type_name` | typisierte Elemente | Typ (z. B. Block oder Value Type) |
| `aggregation`, `multiplicity` (`lower`, `upper`) | Properties | Komposition/Referenz, Multiplizität |
| `direction` | Ports, Flow Properties | in, out, inout |
| `item_flow_details` (`conveyed_id`, `realizing_connector_id`) | Item Flow | transportiertes Element, realisierender Konnektor |
| `represents_block_id` | Activity Partition | dargestellter Block |
| `routing_details` (`source_id`, `target_id`) | Flüsse, Transitionen, Messages, Usage, Interface Realization | Quelle und Ziel |
| `association_ends` | Association | Enden mit Property und Typ |
| `connector_details` (`role_name`, `role_id`, `part_id`) | Connector, Binding Connector | verbundene Rollen, ggf. über welchen Part |
| `outgoing_relations` (`relation_type`, `relation_stereotypes`, `target_id`) | alle | Abhängigkeiten (satisfy, verify, refine, deriveReqt, trace, allocate …), Include, Extend, Generalization, Realization |
| `partial_source`, `partial_depth` | nur Element-Umgebung | über welchen Weg und in welcher Tiefe das Element gefunden wurde |

Exportiert werden nur SysML-v1-Elementtypen aus einer festen Liste (Blöcke, Ports, Properties, Anforderungen, Use Cases, Aktivitäten, Zustandsautomaten, Interaktionen, Pakete, Beziehungen). Diagramme, Tabellen und Bibliotheken fehlen absichtlich.

---

## 4. Format SysML v2, umgewandelt aus v1 (FR7)

Das Plugin übersetzt **denselben** v1-Export in die textuelle Notation von SysML v2 (Klasse `SysmlV2Writer`). Es ist also kein natives SysML-v2-Modell, sondern das v1-Modell in v2-Schreibweise. Für die Auswertung ist das gewollt: Inhalt gleich, nur die Darstellung ändert sich. So lässt sich der Einfluss der Notation isoliert messen (Vergleich SysML v1/v2 in der Aufgabenstellung).

Grenzen: Der Text wurde nicht gegen einen SysML-v2-Parser (z. B. die Pilot-Implementierung) validiert. Er folgt der Syntax, wo es eine direkte Entsprechung gibt. Wo es keine gibt, steht ein Kommentar mit dem v1-Typ. Ein natives v2-Modell (z. B. aus einem SysML-v2-Werkzeug) könnte anders aussehen.

Beispiel:

```sysml
// SysML v2 textual notation, generated from a SysML v1 model (CATIA Magic)
// by SysML AI Q&A 3.3-ws2627. Element ids are given as short names <...>.

package 'Robot Demo' {
    package <_pkg1> '01_Structure' {
        part def <_b1> Robot {
            doc /* The mobile robot. */
            part <_p1> battery : Battery;
            part <_p2> wheels : Wheel [4];
            attribute <_v1> 'total mass' : 'mass[kilogram]' = 12.5;
            connection <_c1> connect battery to wheels.pwr;
        }
        satisfy 'Operating Time' by Robot;
        #electrical part def <_b2> Battery :> 'Energy Source';
    }
    package <_pkg2> '02_Requirements' {
        requirement <_r1> 'Operating Time' {
            doc /* The robot shall operate for at least 4 h. */
            // tagged values: Id = "SYS-REQ_01"
        }
        dependency derives from 'Operating Time' to Mission;
    }
}
```

**Allgemeine Regeln**

- Jedes Element bekommt seine CATIA-ID als **Kurzname** in spitzen Klammern: `part def <_19_0_abc> Battery`. Dadurch funktioniert der ID-Check (*Cite element IDs*) auch für v2.
- Verweise auf andere Elemente stehen über deren **Namen** (lesbar); unbenannte Elemente über die ID.
- Namen mit Leerzeichen, Sonderzeichen oder SysML-v2-Schlüsselwörtern stehen in einfachen Anführungszeichen: `'total mass'`.
- Die Besitzstruktur folgt `owner_id`. Elemente, deren Besitzer nicht im Export ist, stehen direkt im Projektpaket.
- Beziehungen stehen direkt hinter dem Element, von dem sie ausgehen.
- Dokumentation → `doc /* … */`, Anforderungstext → `doc /* … */` (die Dokumentation dann als `comment`), Gleichungen → `comment /* Constraint expression (from SysML v1): … */`, Tagged Values → `// tagged values: …`.
- Stereotypen, die das Schlüsselwort nicht schon ausdrückt, stehen als Metadaten davor: `#electrical part def …`.

**Zuordnung der Elemente**

| SysML v1 (`type`) | SysML v2 |
| --- | --- |
| Package, Model | `package` |
| Block | `part def` |
| Value Type | `attribute def` |
| Constraint Block | `constraint def` |
| Requirement | `requirement` (mit `doc` = Anforderungstext) |
| Test Case | `verification def` |
| Use Case | `use case def` |
| Actor | `part def /* v1 Actor */` |
| Activity | `action def` |
| Action (alle Arten) | `action` |
| Decision / Merge / Fork / Join Node | `decide` / `merge` / `fork` / `join` |
| Initial, Final und andere Knoten | `action /* v1 … */` |
| State Machine | `state def` |
| State, Final State, Pseudostate | `state` (bei Pseudo- und Endzustand mit Kommentar) |
| Region | kein eigenes Element, Inhalt steht direkt im Zustandsautomaten |
| Interaction | `occurrence def` |
| Lifeline | `ref part /* v1 Lifeline */` |
| View / Viewpoint | `view def` / `viewpoint def` |
| Part Property | `part name : Typ [Multiplizität];` |
| Reference Property | `ref part` |
| Value Property | `attribute name : Typ = Standardwert;` |
| Constraint Property | `constraint` |
| Flow Property | `in item` / `out item` / `inout item` |
| Property (sonst) | `ref` |
| Port, Proxy Port, Full Port, Flow Port | `port` (Richtung als Kommentar) |
| Activity Partition | `part /* v1 Activity Partition */` mit `// represents …` |
| Connector | `connection connect a to b;` (Part.Port, wenn über einen Part verbunden) |
| Binding Connector | `binding bind a = b;` |
| Item Flow | `flow of Item from a to b;` |
| Control Flow | `succession first a then b;` |
| Object Flow | `flow from a to b;` |
| Transition | `transition first S1 accept Trigger then S2;` |
| Message | `message from a to b;` |
| Association | `connection def { end … : Typ; end … : Typ; }` |
| Usage, Interface Realization | `dependency from a to b; // v1 …` |
| Rationale, Problem | `comment /* Rationale: … */` |
| alles andere | Kommentar `// v1 <Typ> <Name> [id:…]` |

**Zuordnung der Beziehungen** (`outgoing_relations`)

| SysML v1 | SysML v2 |
| --- | --- |
| Satisfy | `satisfy Anforderung by Element;` |
| Allocate | `allocate Quelle to Ziel;` |
| Generalization | `:>` in der Deklaration (`part def B :> A`) |
| Verify | `dependency verifies from Testfall to Anforderung;` |
| Refine | `dependency refines from … to …;` |
| DeriveReqt | `dependency derives from abgeleitet to Quelle;` |
| Trace / Copy | `dependency traces …` / `dependency copies …` |
| Include / Extend | `dependency includes …` / `dependency extends …` |
| Realization | `dependency realizes …` |
| sonstige Dependency | `dependency <Stereotyp> from … to …;` (ohne Stereotyp: `depends`) |

SysML v2 hat für verify, refine, derive und trace keine eigenen Kurzschreibweisen wie `satisfy`. Die benannten `dependency`-Anweisungen halten die Bedeutung lesbar fest.

---

## 5. Format SysML v2: Modell aus CATIA

Für **SysML-v2-Projekte** in CATIA (*Format: SysML v2 model (CATIA export)*). Das Plugin schickt genau den Text, den CATIA selbst exportiert:

1. In CATIA: *File → Export To → SysML v2 Textual Notation* (im Plugin über **Export in CATIA…**), Namespaces und Ordner wählen, **Export**.
2. Im Plugin: **Files…** → Ordner oder `.sysml`-Dateien wählen. **Resend model** liest dieselben Dateien neu.

**Warum kann man ein SysML-v2-Projekt nicht direkt mit „Ask AI“ schicken?**

- Der Plugin-Export (Abschnitt 3) liest das Modell über die SysML-v1/UML-Schnittstelle von CATIA: Blöcke, Part Properties, Stereotypen wie «Block» oder «Requirement», Abhängigkeiten. SysML-v2-Modelle sind in CATIA anders aufgebaut (`part def`, Usages, Features, Spezialisierung statt Stereotypen). Diese Elemente erkennt die v1-Vorlage nicht; der Export bleibt leer, und das Plugin weist auf dieses Format hin.
- CATIA dokumentiert für SysML v2 keine Programmierschnittstelle, weder zum Auslesen des Modells noch zum Aufruf des Textexports (Stand 2026x). Dokumentiert ist nur der Export über das Menü.
- Der Textexport von CATIA ist die offizielle, vollständige SysML-v2-Notation und wird unverändert übernommen. Der Preis ist ein Schritt mehr (*Export in CATIA…*, *Files…*).
- Möglicher Ausbau: eine interne, undokumentierte Exportfunktion des SysML-v2-Plugins direkt aufrufen. Das könnte mit jedem CATIA-Update brechen und ist deshalb nicht umgesetzt.

**Aufbau des gesendeten Texts:** Die Dateien werden alphabetisch zusammengefügt. Bei mehreren Dateien beginnt jede mit `// ===== File: <Name> =====`. Zeilenenden werden vereinheitlicht, sonst bleibt der Text unverändert. Die Zusammenfassung liegt unter `exports\export_sysml2_<Zeit>.sysml` und im Archiv unter `archive\exports\<export_sha>.sysml`.

**Kennzahlen:** Das Plugin liest den Text mit einem einfachen Leser (`SysmlV2Reader`, kein vollständiger SysML-v2-Parser):

| Angabe | Bedeutung |
| --- | --- |
| *declarations* | benannte Definitionen und Usages (`part def`, `part`, `attribute`, `port`, `requirement`, `action`, `state`, …) |
| *relations* | satisfy, verify, allocate, connect, bind, flow, succession/then, transition, dependency, Spezialisierung (`:>`) |
| ID-Check | Die KI nennt qualifizierte Namen (`[id:RobotSystem::Battery]`); bekannt sind alle qualifizierten Namen und ihre Endstücke (`Battery`) |
| Schnellfragen | aus den gefundenen Elementen und Beziehungen |

*Context* und *Depth* gelten nur für SysML v1. Den Umfang eines SysML-v2-Modells legt man im Export-Dialog von CATIA über die Namespaces fest.

Logspalten: `export_format = sysml_v2_native`, `scope = SYSML_V2_FILES`, `export_scope_detail` = Dateinamen mit Änderungszeit.

---

## 6. Größe und Tokens (FR2)

Die Statuszeile zeigt nach jedem Export: Elemente, Beziehungen, Größe in KB, geschätzte Tokens (Zeichen / 4) und, sobald Google geantwortet hat, die **exakte** Tokenzahl der Modelldaten für das gewählte KI-Modell (Gemini `countTokens`, zählt nicht gegen das Anfrage-Kontingent). Ist das Eingabelimit des Modells kleiner, erscheint vor dem Senden eine rote Warnung (FR12).

Beide Werte stehen im Log: `export_tokens_est`, `export_tokens_exact`. Die tatsächlich verarbeiteten Tokens einer Anfrage (inkl. System-Prompt, Chat und Frage) stehen in `prompt_tokens`.

---

## 7. Wo die Dateien liegen

| Datei | Inhalt |
| --- | --- |
| `Dokumente\SysML-AI-QA\exports\export_*.json` / `.sysml` | aktuelle Exporte (werden beim Schließen von CATIA gelöscht) |
| `Dokumente\SysML-AI-QA\archive\exports\<export_sha>.json` / `.sysml` | jeder verwendete Export, genau der übertragene Inhalt; Dateiname = Logspalte `export_sha` |
| `Dokumente\SysML-AI-QA\archive\prompts\<system_prompt_sha>.md` | jeder verwendete System-Prompt im Wortlaut |
| `Dokumente\SysML-AI-QA\prompts\System_Prompt.md`, `System_Prompt_v2.md`, `System_Prompt_v2_native.md` | eigene, im Fenster bearbeitete Fassungen des System-Prompts |
| `<Plugin-Ordner>\prompts\System_Prompt.md`, `System_Prompt_v2.md` | installierte Standardfassungen |
