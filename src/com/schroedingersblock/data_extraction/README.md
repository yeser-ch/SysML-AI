# Data Extraction Service

Dieser Ordner enthält die Logik für den Export von SysML-Modelldaten aus CATIA Magic / MagicDraw in ein KI-optimiertes JSON-Format.

## Komponenten

1.  **`DataExtractionService.java`**: Der Java-Service, der den Export steuert. Er nutzt die `ITemplateEngine` (konkret die `TextEngine`) der Report Wizard API, um das Velocity-Template programmatisch zu verarbeiten.
2.  **`sysml_flat_export.vm`**: Das Velocity-Template, das die Struktur der JSON-Ausgabe definiert. Es wurde speziell für die flache Graph-Darstellung von SysML-Modellen entwickelt.

## Funktionsweise des Ordners

Die Dateien in diesem Ordner ermöglichen einen automatisierten "One-Click-Export". Sobald ein Benutzer in der UI eine Analyse anfordert, triggert der `DataExtractionService` die Velocity-Engine. Das Template durchläuft das Modell (oder einen gewählten Teilbereich) und generiert eine JSON-Datei im Unterordner `raw_model_data`.

## Hintergrund der Template-Anpassungen

Beim Wechsel von der manuellen Ausleitung (über den Report Wizard in der MagicDraw GUI) zur automatisierten Ausleitung traten signifikante Unterschiede in der Dateigröße und Datenqualität auf.

### Das Problem: Raw Java Objects vs. GUI Proxies
In der MagicDraw GUI werden Model-Elemente automatisch in "Proxy-Wrapper" eingepackt. Diese Proxys vereinfachen komplexe Beziehungen für Velocity. Über die Java-API erhalten wir jedoch direkten Zugriff auf die rohen Java-Implementierungsklassen. 
Dies führte dazu, dass Listen-Beziehungen (wie `source` oder `target`) als komplette Java-Objekt-Referenzen (z.B. `[com.nomagic...impl.ClassImpl@12345]`) in die JSON geschrieben wurden. Dies blähte die Datei massiv auf (von ca. 400KB auf 1.4MB).

### Die Lösung im Template (`.vm`)
Um die Ausleitung wieder so sauber und kompakt wie im manuellen Export zu machen, wurden folgende Änderungen am Template vorgenommen:

*   **Einführung des `#getCleanId`-Makros**: Dieses Makro prüft nun bei jedem Zugriff auf eine ID, ob es sich um eine Liste handelt. Wenn ja, wird nur die ID des ersten Elements extrahiert, statt die Liste als Objekt-String auszugeben.
*   **Explizite Methodenaufrufe**: Anstatt sich auf die automatische Property-Auflösung zu verlassen, nutzt das Template nun stabilere Aufrufe, die auch mit den rohen Java-Klassen der 2026x-API funktionieren.
*   **Bereinigung technischer Overheads**: Durch das Abfangen der Java-Objektreferenzen wurde die Dateigröße wieder auf das Niveau des manuellen Exports optimiert.

## Analyse der Dateigrößen (Overhead-Info)

Die automatische Ausleitung (ca. 700 KB) ist etwas größer als die manuelle (ca. 440 KB), obwohl die Anzahl der Elemente (1096) identisch ist. 

**Grund:** Die automatische Ausleitung schreibt aktuell alle definierten JSON-Keys (z.B. `default_value`, `multiplicity`, `property_type_id`) für **jedes** Element, auch wenn diese leer sind. Der manuelle Export der GUI ist "smarter" und lässt leere Felder bei unpassenden Elementtypen (z.B. multiplicity bei einem Package) komplett weg. Dies wurde bewusst so gelassen, um der KI eine konsistente Datenstruktur zu bieten.

## Model View vs. Element View

Der Service unterstützt zwei verschiedene Export-Modi, die über die UI gesteuert werden. Der Hauptunterschied liegt im sogenannten **Startpunkt (Scope)**:

### 1. Model View (Gesamtmodell)
*   **Der Startpunkt**: Wenn kein spezifisches Element im Fokus steht, greift sich der Java-Code automatisch die **Wurzel des gesamten Projekts** (`currentProject.getPrimaryModel()`).
*   **Der Ablauf**: Das Template startet an der obersten Wurzel. Durch die rekursive Schleife (`#foreach($child in $elem.ownedElement)`) arbeitet sich die Engine durch den kompletten Baum des Projekts.
*   **Anwendung**: Ideal, wenn die KI einen Überblick über das gesamte Projekt benötigt (z.B. für globale Konsistenzprüfungen oder Architektur-Analysen).

### 2. Element View (Kontextbezogen)
*   **Der Startpunkt**: Hier wird das **exakte Element** übergeben, das der Benutzer gerade in der Cameo-Oberfläche ausgewählt hat (z. B. ein spezifisches Paket oder ein Block).
*   **Der Ablauf**: Das Template startet die Generierung direkt bei diesem Element. Alles, was "neben" oder "über" diesem Element im Modellbaum liegt, wird ignoriert. Die Engine taucht jedoch trotzdem in die Tiefe ab und exportiert alle untergeordneten Kinder dieses Elements.
*   **Anwendung**: Ideal für punktuelle Analysen. Es spart Rechenzeit und Token, da der KI nur der wirklich relevante Kontext geliefert wird.
