package com.schroedingersblock.data_extraction;

import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.core.GUILog;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.magicdraw.plugins.Plugin;
import com.nomagic.magicdraw.plugins.PluginDescriptor;
import com.nomagic.magicdraw.plugins.PluginUtils;

import com.nomagic.magicreport.engine.ITemplateEngine;
import com.nomagic.magicreport.engine.velocity.TextEngine;
import com.nomagic.magicreport.Template;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DataExtractionService {

    private static final String PLUGIN_ID = "com.schroedingersblock.plugin";
    private static final String DATA_EXTRACTION_DIR = "data_extraction";
    private static final String TEMPLATE_NAME_FLAT = "sysml_flat_export.vm";
    private static final String TEMPLATE_NAME_PARTIAL = "sysml_partial_export.vm";
    private static final String TARGET_SUBDIR = "raw_model_data";

    /**
     * Führt den JSON-Export über die Report Wizard API (ITemplateEngine) aus.
     *
     * @param currentProject Das aktuell geöffnete MagicDraw Projekt.
     * @param exportScope    Das Element, das als Scope dient. Wenn null, wird das Primary Model verwendet.
     * @return true wenn erfolgreich, false bei einem Fehler.
     */
    public static boolean executeJsonExport(Project currentProject, Element exportScope) {
        GUILog log = Application.getInstance().getGUILog();

        if (currentProject == null) {
            log.log("[DataExtractionService] Fehler: Kein aktives Projekt übergeben.");
            return false;
        }

        try {
            log.log("[DataExtractionService] Starte JSON-Export über ITemplateEngine...");

            // 1. Basis-Verzeichnis ermitteln (Plugin-Verzeichnis)
            File baseDir = getBaseDirectory(currentProject);

            // 2. Template-Auswahl basierend auf exportScope
            // - exportScope == null: Model View → sysml_flat_export.vm
            // - exportScope != null: Element View → sysml_partial_export.vm
            boolean isElementView = (exportScope != null);
            String selectedTemplate = isElementView ? TEMPLATE_NAME_PARTIAL : TEMPLATE_NAME_FLAT;
            log.log("[DataExtractionService] Modus: " + (isElementView ? "Element View (partial)" : "Model View (flat)"));

            // 3. Template-Pfad prüfen
            File templateFile = new File(baseDir, DATA_EXTRACTION_DIR + File.separator + selectedTemplate);
            if (!templateFile.exists()) {
                log.log("[DataExtractionService] Fehler: Template nicht gefunden unter: " + templateFile.getAbsolutePath());
                return false;
            }

            // 4. Ziel-Ordner erstellen, falls nicht vorhanden
            File targetDir = new File(baseDir, DATA_EXTRACTION_DIR + File.separator + TARGET_SUBDIR);
            if (!targetDir.exists()) {
                boolean created = targetDir.mkdirs();
                if (!created) {
                    log.log("[DataExtractionService] Fehler: Zielordner konnte nicht erstellt werden: " + targetDir.getAbsolutePath());
                    return false;
                }
            }

            // 5. Dynamischen Dateinamen mit Zeitstempel generieren
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String outputFileName = "export_" + timestamp + ".json";
            File outputFile = new File(targetDir, outputFileName);

            // 6. Scope festlegen
            Element scope = (exportScope != null) ? exportScope : currentProject.getPrimaryModel();
            if (scope == null) {
                log.log("[DataExtractionService] Fehler: Kein gültiger Scope für den Export gefunden.");
                return false;
            }

            // 7. ITemplateEngine Workflow
            ITemplateEngine engine = new TextEngine();
            try {
                // WICHTIG: Context ZUERST setzen (vor createTemplate)
                // Manche Template-Engines benötigen den Context beim Parsen
                engine.addContext("project", currentProject);
                engine.addContext("element", scope);
                
                // Für partielle Exports: centerElement übergeben (wird von sysml_partial_export.vm genutzt)
                if (isElementView && exportScope != null) {
                    engine.addContext("centerElement", exportScope);
                    log.log("[DataExtractionService] Center Element für Partial Export gesetzt: " + exportScope.getHumanName());
                }
                
                // String-Utility für Velocity verfügbar machen (für Whitespace-Cleaning)
                engine.addContext("stringUtil", new StringCleaner());
                
                // Debug-Logger für Velocity verfügbar machen (für Template-Debugging)
                engine.addContext("debugUtil", new DebugLogger(log));
                
                // Template erstellen (nach Context-Setup)
                Template template = engine.createTemplate(templateFile, outputFile);
                
                // Encodings setzen
                engine.setProperty("input.encoding", "UTF-8");
                engine.setProperty("output.encoding", "UTF-8");
                
                // Export synchron ausführen
                engine.evaluate(template);
            } finally {
                // Ressourcen nach dem Export freigeben
                engine.destroy();
            }

            log.log("[DataExtractionService] Erfolgreich! JSON gespeichert unter: " + outputFile.getAbsolutePath());
            return true;

        } catch (Exception e) {
            log.log("[DataExtractionService] Fehler beim Export: " + e.getMessage());
            e.printStackTrace();
            for (StackTraceElement ste : e.getStackTrace()) {
                log.log("\t" + ste.toString());
            }
            return false;
        }
    }

    /**
     * Ermittelt das Verzeichnis, in dem der Ordner 'data_extraction' liegt.
     * Priorität: 1. Plugin-Verzeichnis, 2. Projektverzeichnis, 3. Arbeitsverzeichnis.
     */
    private static File getBaseDirectory(Project project) {
        // 1. Versuche das Plugin-Verzeichnis zu ermitteln
        // Der einzig offizielle Weg ist ueber PluginUtils.getPlugins() zu iterieren.
        try {
            for (Plugin plugin : PluginUtils.getPlugins()) {
                PluginDescriptor descriptor = plugin.getDescriptor();
                if (descriptor != null && PLUGIN_ID.equals(descriptor.getID())) {
                    return descriptor.getPluginDirectory();
                }
            }
        } catch (Throwable t) {
            // Ignoriere Fehler
        }

        // 2. Fallback: Projektverzeichnis des aktuell geöffneten Projekts
        if (project != null) {
            try {
                String fileName = project.getFileName();
                if (fileName != null && !fileName.isEmpty()) {
                    File projectFile = new File(fileName);
                    if (projectFile.exists()) {
                        return projectFile.getParentFile();
                    }
                }
            } catch (Exception e) {
                // Ignoriere Fehler
            }
        }

        // 3. Letzter Fallback: MagicDraw Arbeitsverzeichnis
        return new File(System.getProperty("user.dir"), "plugins" + File.separator + PLUGIN_ID);
    }

    /**
     * Hilfsmethode für Velocity: Bereinigt String von Zeilenumbrüchen und Tabs
     * Ersetzt diese durch einzelne Leerzeichen für saubere JSON-Ausgabe
     */
    public static class StringCleaner {
        /**
         * Entfernt Zeilenumbrüche (\n, \r) und Tabs durch Leerzeichen
         * Reduziert mehrfache Leerzeichen auf eines
         * Filtert auch Java-Objektreferenzen (@...)
         */
        public String cleanWhitespace(Object input) {
            if (input == null) {
                return "";
            }
            String text = input.toString();
            
            // Java-Objektreferenzen filtern
            if (text.contains("@") || text.contains("impl.")) {
                return "";
            }
            
            // Zeilenumbrüche und Tabs durch Leerzeichen ersetzen
            text = text.replace("\n", " ")   // Newline
                       .replace("\r", " ")   // Carriage Return
                       .replace("\t", " ");  // Tab
            
            // Mehrfache Leerzeichen auf eines reduzieren
            text = text.replaceAll(" +", " ");
            
            return text.trim();
        }
    }

    /**
     * Debug-Logger für Velocity-Templates: Gibt Meldungen in MagicDraw GUILog aus
     * Verwendung im Template: $debugUtil.log("Nachricht: " + $variable)
     */
    public static class DebugLogger {
        private final GUILog log;

        public DebugLogger(GUILog log) {
            this.log = log;
        }

        /**
         * Gibt eine Debug-Nachricht im GUILog aus
         * Prefix: [VELOCITY_DEBUG]
         */
        public String log(Object message) {
            if (message != null) {
                String msg = "[VELOCITY_DEBUG] " + message.toString();
                log.log(msg);
                return "";  // Velocity erwartet Rückgabewert, gibt aber nichts aus
            }
            return "";
        }

        /**
         * Gibt einen Wert aus und gibt ihn weiter (für Inline-Nutzung)
         * Verwendung: $debugUtil.logAndReturn("label", $value)
         */
        public Object logAndReturn(String label, Object value) {
            if (value != null) {
                String msg = "[VELOCITY_DEBUG] " + label + ": " + value.toString();
                log.log(msg);
            }
            return value;
        }

        /**
         * Gibt die Größe einer Collection aus
         */
        public int logSize(String label, java.util.Collection<?> collection) {
            int size = collection != null ? collection.size() : 0;
            log.log("[VELOCITY_DEBUG] " + label + " size: " + size);
            return size;
        }
    }
}
