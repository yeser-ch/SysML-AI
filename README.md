# SchroedingersBlock
📘 AI4MBSE - Entwickler Guide & Workflow



---
Willkommen im Projekt! Diese Anleitung definiert unseren gemeinsamen Workflow und erklärt die technische Architektur unseres Plugins, damit wir effizient zusammenarbeiten können.
---

---

### 🚀 Aktueller Status (main)
Der main-Branch enthält die stabilste Version des Plugins.
Das Grundgerüst des Plugins enthält:

    UI-Integration: Rechtsklick-Menü in allen relevanten SysML-Diagrammen und im Strukturbaum.

    Datenfluss: Erfolgreiche Erfassung des selektierten Elements (targetElement).

    User Interface: Asynchrones Chat-Fenster (Swing) für KI-Interaktionen.

    Compliance: Integrierte Datenschutz-Abfrage (Privacy Consent).

    API-Brücke: Bereitgestelltes Interface für die Anbindung von LLMs.


### 🏗️ Projekt-Architektur (Die Kern-Skripte)

Damit du im Code sofort weißt, wo du Änderungen vornehmen musst, sind hier unsere zentralen Java-Klassen und ihre jeweiligen Zuständigkeiten (Rollen) erklärt:

* `SchroedingersBlockPlugin.java`
    * **Funktion:** Verwaltet den Start- und den Stopp-Zyklus des Plugins und registriert unsere Menüs im CATIA-System.

* `MenuConfigurator.java`
    * **Funktion:** Findet exakt heraus, auf welches SysML-Element (z.b.:Block, Requirement, Diagramm, etc.) der Nutzer geklickt hat und fängt es ab.

* `ShowExplanationAction.java`
    * **Funktion:** Reagiert auf den Klick im Menü, überprüft die Datenschutz-Zustimmung und öffnet bei Erfolg das Chat-Fenster.

* `ExplanationDialog.java`
    * **Funktion:** Die grafische Benutzeroberfläche (UI). Nutzt Multithreading (`SwingWorker`), damit CATIA während der KI-Ladezeiten nicht einfriert.

*  `ExplanationService.java`
    * **Funktion:** Das Interface (für die LLM-API),definiert wie unsere UI Daten an die Künstliche Intelligenz sendet und empfängt.

* `DummyExplanationService.java`
    * **Funktion:** Ein Platzhalter für das LLM-API-Team. Liefert aktuell noch statische Test-Antworten, um das UI zu testen, ohne echtes Internet zu benötigen.
----

### Speziell für LLM-API & Daten-Export

Eure Hauptbaustelle ist der `DummyExplanationService.java`. Dieser muss durch echte Logik ersetzt werden.

**1. Eure Eingangsdaten (Was ihr von der UI bekommt):**
Die UI übergibt euch über das Interface `ExplanationService` zwei entscheidende Parameter:
*  **`targetElement` (Typ: `Element`):** Das rohe CATIA-Element, auf das der Nutzer geklickt hat.
* **`mode` (Typ: `String`):** Der Kontext der Anfrage (z. B. `"Element View"` oder `"Model View"`).

--------

----




### 📚 Glossar: Local vs. Origin

💻 Local (Lokal)
Das ist dein Laptop. Änderungen, die du hier machst (speichern, committen), sieht erst mal nur DU. Wenn dein Laptop kaputt geht, sind "lokale" Änderungen weg.

Befehle: git commit, git checkout, .\build.ps1

☁️ Origin (Remote/Server)
Das ist GitHub (die Cloud). Erst wenn du etwas hierhin "pushst", können es die anderen (Simon, Moritz, etc.) sehen.

git push: Schiebt deine lokalen Änderungen auf den Server (Origin).
git pull: Holt Änderungen vom Server auf deinen Laptop (Local).
git fetch: Schaut auf dem Server nach Neuerungen, lädt sie aber noch nicht in deine Dateien.

### 🌳 Unsere Branching-Strategie (Der 3-Ebenen-Workflow)

Wir arbeiten nach einem strengen hierarchischen Prinzip. Bitte haltet euch daran, um Merge-Konflikte zu vermeiden.

1. Ebene: main (Heilig!)
Hier liegt der funktionierende Code.
Niemals direkt hierhin pushen.
Niemals direkt von features/ hierhin mergen.

2. Ebene: Persönliche Branches (Julius, Nikolaj, Florian, Max, Leo)
Jedes Teammitglied hat seinen eigenen Branch (z.B. Leo).
Dies ist dein persönlicher "Sammelplatz" (Staging Area).
Verantwortung: Du bist dafür zuständig, dass dein Branch regelmäßig in main gemerged wird. Es dürfen sich keine riesigen Änderungen anstauen!

3. Ebene: features/ (Die Werkstatt)
In diesem Unterordner befinden sich die Branches, in denen die eigentliche Entwicklung stattfindet.
Wenn du etwas Neues entwickeln oder ausprobieren willst, erstelle hier einen Branch (z.B. features/neue-buttons).
Regel: Diese Features werden immer von deinem persönlichen Branch abgeleitet und auch nur dorthin zurück gepusht.
🛠️ Workflow: Wie entwickle ich ein neues Feature?

Schritt 1: Feature-Branch erstellen
Du leitest deinen Feature-Branch immer von deinem persönlichen Branch ab (nie vom main!).

Stelle sicher, dass dein persönlicher Branch aktuell ist und erstelle dann den Feature-Branch. Beispiel für Leo:

## 1. Auf deinen persönlichen Branch wechseln
git checkout Leo

## 2. Den neuesten Stand holen (falls du woanders gearbeitet hast)
git pull origin Leo

## 3. Neuen Feature Branch davon ableiten (WICHTIG!)
git checkout -b features/neue-buttons Leo
Schritt 2: Entwickeln & Builden (Wichtig!)

Bevor du deine Änderungen in CATIA Magic testen kannst, muss der Java-Code kompiliert werden. Das Plugin funktioniert sonst nicht mit dem neuen Code.

Befehl: Führe im Terminal im Projektordner folgendes aus:

.\build.ps1  *** - Den Teil müssen wir für unser Projekt wahrscheinlich noch etwas anpassen. - ***

Wann muss ich das tun?

Einmalig vor der allerersten Nutzung.
Jedes Mal, wenn du .java Code geändert hast und die Änderung in CATIA sehen willst.
Schritt 3: Arbeit sichern (Commit & Push)

Halte deine Features klein! Konzentriere dich auf eine Aufgabe. Wenn fertig:

git add .
git commit -m "Feat: Beschreibung was ich gemacht habe"

## Pushen in den feature branch auf dem Server
git push origin features/mein-neues-feature
Schritt 4: In den persönlichen Branch bringen

Wenn das Feature fertig ist, bringst du es in deinen Namens-Branch zurück.

## Zurück zu deinem Branch wechseln
git checkout <DeinName>

## Feature reinholen (mergen)
git merge features/mein-neues-feature

## Auf den Server laden
git push origin <DeinName>
Schritt 5: In den Main bringen (Der Abschluss)

Regelmäßig (z.B. wenn das Feature stabil läuft) muss dein Namens-Branch in den main gemerged werden. Dies machen wir idealerweise über einen Pull Request in GitHub oder (nach Absprache im Team) lokal.

🔄 Tägliche Routine: Update vom Main (Drift verhindern!)

Da wir zu fünft arbeiten, ändert sich der main Branch ständig. Damit dein persönlicher Branch nicht veraltet ("abweicht") und du später keine riesigen Merge-Konflikte lösen musst, musst du die Änderungen der anderen regelmäßig in deinen Branch holen.

## 1. Sicherstellen, dass du auf deinem persönlichen Branch bist
git checkout <DeinName>

## 2. Den neuesten Stand vom Server (GitHub) herunterladen (noch nicht mergen)
git fetch origin

## 3. Den main vom Server in deinen lokalen Branch mergen
git merge origin/main
# ⚠️ Goldene Regeln

Klein anfangen: Entwickle kleine Bausteine und merge sie oft in deinen persönlichen Branch.
Verantwortung: Du bist für deinen Namens-Branch verantwortlich. Halte ihn sauber und synchronisiere ihn oft mit main.
Build Skript: Vergiss nicht .\build.ps1 auszuführen, sonst wunderst du dich, warum CATIA deine Änderungen nicht anzeigt.
Hierarchie:
main <--- DeinName <--- features/...




