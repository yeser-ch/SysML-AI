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
- Internetverbindung und ein Google-Konto (für den API-Key, siehe [Erster Start](#erster-start))

Dauer: etwa 5 Minuten.

### Schritt 1 – Paket herunterladen

Im GitHub-Repository rechts auf **Releases** klicken. Bei der neuesten Version unter *Assets* die Datei **`SysML-AI-QA_v….zip`** anklicken. Sie landet im Ordner *Downloads*.

![GitHub Releases mit der ZIP-Datei](docs/images/install-01-github-release.png)

> Nicht die Dateien *Source code (zip)* nehmen. Darin ist nur der Quellcode, keine fertige JAR.

### Schritt 2 – ZIP entpacken

Im Explorer **Rechtsklick auf die ZIP-Datei → Alle extrahieren… → Extrahieren**.

![Rechtsklick auf die ZIP und „Alle extrahieren“](docs/images/install-02-extract.png)

Es öffnet sich ein Ordner `SysML-AI-QA_v…` mit diesem Inhalt:

![Entpackter Ordner mit install.bat](docs/images/install-03-folder.png)

| Datei / Ordner | Inhalt |
| --- | --- |
| `install.bat` | startet die Installation |
| `install.ps1` | das eigentliche Installationsskript (wird von `install.bat` aufgerufen) |
| `plugin\` | das Plugin selbst: JAR, `plugin.xml`, Export-Vorlagen, System-Prompt |
| `INSTALL.md`, `HANDBUCH.md`, `docs\` | diese Anleitung und das Benutzerhandbuch mit Bildern |

> `install.bat` **nicht** direkt in der ZIP doppelklicken (Windows zeigt ZIP-Dateien wie Ordner an). Dann fehlen die anderen Dateien und das Skript meldet *„install.ps1 wurde nicht gefunden“*.

### Schritt 3 – CATIA schließen

CATIA komplett beenden, auch weitere geöffnete CATIA-Fenster. Solange CATIA läuft, ist die Plugin-Datei gesperrt.

### Schritt 4 – `install.bat` starten

Im entpackten Ordner **`install.bat` doppelklicken**.

**4a – Windows-Warnung (nur manchmal).** Weil die Datei aus dem Internet kommt, kann Windows warnen. Dann **Weitere Informationen** → **Trotzdem ausführen** klicken (bzw. **Ausführen**).

![Windows-Warnung beim ersten Start](docs/images/install-04-windows-warning.png)

**4b – Administratorrechte.** Die Frage *„Möchten Sie zulassen, dass durch diese App Änderungen an Ihrem Gerät vorgenommen werden?“* mit **Ja** beantworten. Ohne Adminrechte kann das Plugin nicht in den CATIA-Ordner kopiert werden.

![Abfrage der Administratorrechte](docs/images/install-05-uac.png)

### Schritt 5 – Installation abwarten

Ein schwarzes Fenster öffnet sich und arbeitet vier Schritte ab:

| Schritt im Fenster | Was passiert |
| --- | --- |
| `1/4 CATIA-Installation suchen` | findet CATIA automatisch (meist `C:\Program Files\Magic Systems of Systems Architect`) |
| `2/4 Plugin-Ordner bestimmen` | *Neuinstallation* oder *Vorhandene Installation gefunden – sie wird aktualisiert* |
| `3/4 Ältere Version … entfernen` | nur bei einer sehr alten Version mit anderer Plugin-ID: Sie wird nach `Dokumente\SysML-AI-QA\backup\` verschoben. Sonst steht dort *Keine gefunden* |
| `4/4 Dateien kopieren` | kopiert das Plugin; eine vorhandene JAR wird als `.bak` gesichert |

Am Ende steht in Grün **„Fertig! SysML AI Q&A … ist installiert.“** Eine beliebige Taste drücken, das Fenster schließt sich.

![Installation abgeschlossen](docs/images/install-06-done.png)

Steht dort stattdessen `FEHLER: …` in Rot, hilft die Tabelle [Wenn etwas nicht klappt](#wenn-etwas-nicht-klappt).

---

## Erster Start

### Schritt 6 – API-Key besorgen (einmalig)

Das Plugin braucht einen persönlichen Schlüssel für die Gemini-API von Google. Er ist im Free Tier kostenlos.

1. <https://aistudio.google.com/apikey> öffnen und mit dem Google-Konto anmelden.
2. **Create API key** klicken.
3. Den angezeigten Key **kopieren** (Strg+C). Der Key ist wie ein Passwort: nicht weitergeben und nicht auf Screenshots zeigen.

![API-Key in Google AI Studio erstellen](docs/images/start-01-aistudio-key.png)

### Schritt 7 – CATIA starten und zwei Fragen beantworten

Beim **ersten Start von CATIA nach der Installation** erscheinen nacheinander zwei Fenster:

**Datenschutzhinweis:** Mit **Yes** zustimmen. Das Plugin schickt das Modell an Google. Deshalb nur Modelle ohne vertrauliche Inhalte verwenden.

![Datenschutzhinweis](docs/images/first-start-privacy.png)

**API-Key:** Den kopierten Key einfügen (Strg+V) → **OK**. Er wird nur lokal auf diesem PC gespeichert.

![Abfrage des API-Keys](docs/images/first-start-api-key.png)

Wer hier *No* oder *Abbrechen* klickt, wird beim ersten Öffnen des Plugins noch einmal gefragt. Den Key kann man später jederzeit über **Ask AI → SysML AI Q&A – API key…** oder den Button **API key…** im Plugin-Fenster ändern.

> Wurde vorher schon eine ältere Version benutzt, entfallen beide Fragen: Zustimmung und Key werden übernommen.

### Schritt 8 – Prüfen, ob das Plugin geladen ist

In CATIA **Window → Notification Window** öffnen. Dort muss eine Zeile stehen wie:

```
[SysML AI Q&A] Plugin loaded (version 3.1-ws2627, 17/17 diagram/table types)
```

![Notification Window mit der Kontrollzeile](docs/images/start-02-notification-window.png)

Fehlt die Zeile, ist das Plugin nicht geladen (siehe [Wenn etwas nicht klappt](#wenn-etwas-nicht-klappt)).

### Schritt 9 – Plugin öffnen

Ein Projekt öffnen. Dann gibt es zwei Wege:

**Rechtsklick** auf ein Element im Containment Tree oder in einem Diagramm → **Ask AI (SysML AI Q&A)** →
- **Ask AI about the model…**: Fragen zum ganzen Modell
- **Ask AI about this element…**: Fragen zu genau diesem Element und seiner Umgebung

![Rechtsklick-Menü im Containment Tree](docs/images/start-03-context-menu.png)

**Menüleiste → Ask AI** → *Ask AI about the model…*, *Ask AI about the selected element…* oder *SysML AI Q&A – API key…*. Das funktioniert auch, wenn ein Element in einer Tabelle markiert ist.

![Menüleiste „Ask AI“](docs/images/start-04-menu-bar.png)

Es öffnet sich das Fenster **SysML AI Q&A**. Wie es funktioniert und was jede Einstellung bedeutet, steht im **[Benutzerhandbuch](HANDBUCH.md)**.

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
build.bat -PluginDir "C:\Program Files\Magic Systems of Systems Architect\plugins\de.hm.sysmlaiqa.plugin"
build.bat -JdkDir "C:\Program Files\Eclipse Adoptium\jdk-21…"
build.bat -NoInstall        (nur bauen und Paket erstellen)
```

Dieselben Optionen funktionieren auch mit `update.bat`. Mit `install.bat` gehen `-CatiaDir` und `-PluginDir`.

---

## Neue Version an andere verteilen (Team)

1. Falls sich etwas Wesentliches geändert hat: die Versionsnummer `PLUGIN_VERSION` in `src/de/hm/sysmlaiqa/api/EvaluationRecord.java` erhöhen. Sie steht in jeder Log-Zeile und im Dateinamen des Pakets.
2. `update.bat` (oder `build.bat`) ausführen. Danach liegt das Paket unter `dist\SysML-AI-QA_v<Version>.zip`.
3. Kurz in CATIA testen.
4. Auf GitHub: **Releases → Draft a new release**.
   - **Tag** `v<Version>` anlegen, **Target** = `feature/free-prompt-full-model`.
   - Die ZIP per Drag & Drop anhängen, kurz notieren, was neu ist → **Publish release**.

Die ZIP gehört **nicht** in den Code (sie steht in `.gitignore`). Sie wird nur als Release-Datei hochgeladen.

---

## Update, zurück zur alten Version, Deinstallation

- **Update:** neue ZIP herunterladen, entpacken, `install.bat` starten. API-Key, Einstellungen und ein selbst geänderter `System_Prompt.md` bleiben erhalten. Die neue Fassung des Prompts liegt dann daneben als `System_Prompt.md.new`.
- **Zurück zur vorherigen Version:** im Plugin-Ordner `sysml-ai-qa.jar` löschen und `sysml-ai-qa.jar.bak` in `sysml-ai-qa.jar` umbenennen.
- **Deinstallation:** CATIA schließen und den Ordner `<CATIA-Ordner>\plugins\de.hm.sysmlaiqa.plugin` löschen (braucht Adminrechte). Die Ergebnisse unter `Dokumente\SysML-AI-QA\` bleiben erhalten und können von Hand gelöscht werden.

Der Plugin-Ordner liegt normalerweise unter `C:\Program Files\Magic Systems of Systems Architect\plugins\de.hm.sysmlaiqa.plugin`. Das Installationsskript zeigt den genauen Pfad an.

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
