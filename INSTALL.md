# SysML AI Q&A – Installation

SysML AI Q&A ist ein Plugin für **CATIA Magic / Magic Systems of Systems Architect 2026x**. Es stellt einer KI (Google Gemini) Fragen zum geöffneten SysML-Modell und protokolliert jede Antwort für die Auswertung. Entwickelt im Projekt „How well does AI understand the SysML model?“ (Systems Engineering Project, HM München, WS 2026/27).

Es gibt zwei Wege:

| Weg | Für wen | Braucht |
| --- | --- | --- |
| **A – Fertiges Paket** | Nutzer, Betreuer, neue Teammitglieder | nur CATIA und Adminrechte |
| **B – Selbst bauen** | Entwickler, die den Code ändern | zusätzlich ein Java JDK 17+ |

---

## Weg A – Fertiges Paket installieren

**Voraussetzungen**

- Windows-PC mit installiertem CATIA Magic / Magic Systems of Systems Architect 2026x
- Administratorrechte (das Plugin wird in den Programmordner von CATIA kopiert)
- Internetverbindung und ein Google-Konto (für den API-Key, siehe unten)

**Schritte**

1. **Paket holen.** Auf GitHub im Repository rechts auf **Releases** klicken und die neueste Datei `SysML-AI-QA_v….zip` herunterladen. Alternativ bekommst du die ZIP vom Team.
2. **Entpacken.** Rechtsklick auf die ZIP → **Alle extrahieren…** → **Extrahieren**. `install.bat` nicht direkt aus der ZIP heraus starten, das funktioniert nicht.
3. **CATIA komplett schließen.**
4. **`install.bat` doppelklicken** (im entpackten Ordner).
   - Falls Windows warnt („Der Computer wurde durch Windows geschützt“ oder „Herausgeber konnte nicht verifiziert werden“): **Weitere Informationen** → **Trotzdem ausführen** bzw. **Ausführen**.
   - Die Frage nach Administratorrechten mit **Ja** bestätigen.
5. Im schwarzen Fenster läuft die Installation in drei Schritten durch. Am Ende steht **„Fertig! SysML AI Q&A … ist installiert.“** → beliebige Taste drücken.
6. **CATIA starten.** Kontrolle: Menü **Window → Notification Window**. Dort steht die Zeile `Plugin loaded (version …)`.

Eine ältere Version von SysML AI Q&A (auch die Vorgängerversion *Schrödinger's Block*) wird dabei ersetzt. API-Key und Einstellungen bleiben erhalten.

---

## Erster Start

1. **API-Key besorgen** (einmalig, kostenlos im Free Tier): <https://aistudio.google.com/apikey> öffnen, mit dem Google-Konto anmelden, **Create API key** klicken und den Key kopieren.
2. In CATIA ein Projekt öffnen und eine der beiden Möglichkeiten nutzen:
   - Rechtsklick auf ein Element im Containment Tree oder in einem Diagramm → **Ask AI (SysML AI Q&A)** → **Ask AI about the model…** oder **Ask AI about this element…**
   - oben in der Menüleiste **Ask AI**
3. Den **Datenschutzhinweis** lesen und bestätigen. Das Modell wird an Google übertragen, deshalb nur Modelle ohne vertrauliche Inhalte verwenden.
4. Den **API-Key einfügen**. Er wird nur lokal auf diesem PC gespeichert und lässt sich später über den Button **API key…** ändern.
5. Im Fenster eine Frage eintippen oder eine Schnellfrage anklicken und **Send** drücken. Beim ersten Mal wird das Modell exportiert, das dauert etwas länger.

**Wo landen die Daten?** Exporte, Antworten und Testläufe liegen unter `Dokumente\SysML-AI-QA\` (Button **Open results**). Details zur Auswertung: [`evaluation/README.md`](evaluation/README.md).

---

## Weg B – Selbst bauen (Entwickler)

**Einmalig: Java JDK installieren**

1. *Eclipse Temurin JDK 21* (Windows, `.msi`) von <https://adoptium.net> herunterladen.
2. Bei der Installation den Punkt **Set JAVA_HOME variable** auf „Will be installed“ stellen.
3. Prüfen: Eingabeaufforderung öffnen und `javac -version` eingeben. Es muss **17 oder höher** erscheinen. Steht dort `1.8`, ist noch ein altes Java aktiv. Das Build-Skript sucht zwar selbst nach einem passenden JDK, mit JAVA_HOME klappt es aber sicherer.

**Bauen und installieren**

| Skript | Was es tut |
| --- | --- |
| `update.bat` | lädt den neuesten Stand des Branches `feature/free-prompt-full-model` von GitHub, baut ihn und installiert ihn. Legt außerdem das Paket für Weg A unter `dist\` ab. Ein Git-Clone ist dafür nicht nötig. |
| `build.bat` | baut den Code aus dem eigenen Ordner (für lokale Änderungen), legt das Paket unter `dist\` ab und installiert es |
| `install.bat` | installiert nur, ohne zu bauen (das ist der Weg A) |

Alle drei fragen selbst nach Administratorrechten. CATIA vorher schließen.

**Optionen**, falls die automatische Suche nicht klappt:

```
build.bat -CatiaDir "C:\Program Files\Magic Systems of Systems Architect"
build.bat -PluginDir "C:\Program Files\Magic Systems of Systems Architect\plugins\com.schroedingersblock.plugin"
build.bat -JdkDir "C:\Program Files\Eclipse Adoptium\jdk-21…"
build.bat -NoInstall        (nur bauen und Paket erstellen)
```

Dieselben Optionen funktionieren auch mit `update.bat`. Mit `install.bat` gehen `-CatiaDir` und `-PluginDir`.

---

## Neue Version an andere verteilen (Team)

1. Falls sich etwas Wesentliches geändert hat: die Versionsnummer `PLUGIN_VERSION` in `src/com/schroedingersblock/api/EvaluationRecord.java` erhöhen. Sie steht in jeder Log-Zeile und im Dateinamen des Pakets.
2. `update.bat` (oder `build.bat`) ausführen. Danach liegt das Paket unter `dist\SysML-AI-QA_v<Version>.zip`.
3. Kurz in CATIA testen.
4. Auf GitHub: **Releases → Draft a new release**.
   - **Tag** `v<Version>` anlegen, **Target** = `feature/free-prompt-full-model`.
   - Die ZIP per Drag & Drop anhängen, kurz notieren, was neu ist → **Publish release**.

Die ZIP gehört **nicht** in den Code (sie steht in `.gitignore`). Sie wird nur als Release-Datei hochgeladen.

---

## Update, zurück zur alten Version, Deinstallation

- **Update:** neue ZIP herunterladen, entpacken, `install.bat` starten. API-Key, Einstellungen und ein selbst geänderter `System_Prompt.md` bleiben erhalten. Die neue Fassung des Prompts liegt dann daneben als `System_Prompt.md.new`.
- **Zurück zur vorherigen Version:** im Plugin-Ordner `schroedingers-block.jar` löschen und `schroedingers-block.jar.bak` in `schroedingers-block.jar` umbenennen.
- **Deinstallation:** CATIA schließen und den Ordner `<CATIA-Ordner>\plugins\com.schroedingersblock.plugin` löschen (braucht Adminrechte). Die Ergebnisse unter `Dokumente\SysML-AI-QA\` bleiben erhalten und können von Hand gelöscht werden.

Der Plugin-Ordner liegt normalerweise unter `C:\Program Files\Magic Systems of Systems Architect\plugins\com.schroedingersblock.plugin`. Das Installationsskript zeigt den genauen Pfad an.

---

## Wenn etwas nicht klappt

| Meldung / Beobachtung | Ursache | Lösung |
| --- | --- | --- |
| `install.ps1 wurde nicht gefunden` oder `fehlen Dateien` | ZIP nicht (vollständig) entpackt | Rechtsklick auf die ZIP → **Alle extrahieren**, dann im entpackten Ordner starten |
| `CATIA wurde nicht automatisch gefunden` | CATIA liegt an einem ungewöhnlichen Ort | Startmenü → Rechtsklick auf CATIA → **Dateispeicherort öffnen** → nochmal Rechtsklick auf die Verknüpfung → **Dateispeicherort öffnen** → eine Ebene hoch. Diesen Ordner (mit `bin`, `lib`, `plugins`) ins Fenster einfügen |
| `Keine Schreibrechte` / `Zugriff verweigert` | ohne Adminrechte gestartet oder CATIA läuft noch | CATIA schließen; Rechtsklick auf die `.bat` → **Als Administrator ausführen** |
| `CATIA läuft noch` | ein CATIA-Fenster ist noch offen | alle CATIA-Fenster schließen, ggf. im Task-Manager beenden, dann Enter |
| Nach dem Start kein Menüeintrag „Ask AI“ | Plugin in eine andere CATIA-Installation kopiert oder Ladefehler | Pfad aus dem Installationsfenster mit dem CATIA-Startpfad vergleichen; im Notification Window nach `SysML AI Q&A` suchen und die Meldung ans Team schicken |
| Rechtsklick in einer **Requirement-Tabelle** zeigt keinen Eintrag | bekannte Einschränkung | Zeile markieren und oben in der Menüleiste **Ask AI → Ask AI about the selected element…** wählen, oder **Alt+B** (Select in Containment Tree) und dort Rechtsklick |
| `HTTP 429` | Free-Tier-Limit erreicht | Minutenlimit: das Plugin wartet selbst. Tageslimit: bis morgen warten oder ein anderes KI-Modell wählen |
| `HTTP 503` / `HTTP 500` | Google-Server überlastet | wird automatisch bis zu dreimal wiederholt; sonst später erneut versuchen oder ein anderes Modell wählen |
| `HTTP 400` / `HTTP 403` mit „API key“ | API-Key falsch oder gelöscht | über **API key…** einen neuen Key eintragen |
| (Weg B) `Kein Java JDK` oder `javac 1.8` | kein oder zu altes JDK | Temurin JDK 21 installieren (siehe oben) |

Hilft das nicht: den Text aus dem schwarzen Fenster bzw. aus **Window → Notification Window** kopieren und an das Team schicken.
