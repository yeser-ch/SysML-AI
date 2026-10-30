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
    private static final String TEMPLATE_NAME_PARTIAL = "sysml_partial_export.vm";
    private static final String TARGET_SUBDIR = "raw_model_data";

    private static final String TEMPLATE_NAME_FULL = "sysml_flat_export.vm";

    /** Welcher Teil des Modells an die KI geht. */
    public enum Scope {
        /** Gesamtes Primärmodell (ohne SysML/UML-Bibliotheken) – Standard. */
        FULL_MODEL,
        /** Nur das ausgewählte Element plus Umgebung (alte "Model View"-Tiefen). */
        FOCUS_NEIGHBOURHOOD
    }

    /**
     * Exportiert Modelldaten als JSON-Datei.
     *
     * @param currentProject Das aktuell geöffnete Projekt.
     * @param focusElement   Ausgewähltes Element (nur für FOCUS_NEIGHBOURHOOD nötig, sonst ignoriert).
     * @param scope          FULL_MODEL oder FOCUS_NEIGHBOURHOOD.
     * @return die erzeugte Datei oder null bei einem Fehler.
     */
    public static File exportModel(Project currentProject, Element focusElement, Scope scope) {
        GUILog log = Application.getInstance().getGUILog();

        if (currentProject == null) {
            log.log("[DataExtractionService] Fehler: Kein aktives Projekt übergeben.");
            return null;
        }
        if (scope == Scope.FOCUS_NEIGHBOURHOOD && focusElement == null) {
            log.log("[DataExtractionService] Kein Element ausgewählt – exportiere stattdessen das ganze Modell.");
            scope = Scope.FULL_MODEL;
        }

        try {
            File baseDir = getBaseDirectory(currentProject);
            boolean full = scope == Scope.FULL_MODEL;
            String selectedTemplate = full ? TEMPLATE_NAME_FULL : TEMPLATE_NAME_PARTIAL;
            log.log("[DataExtractionService] Starte JSON-Export (" + scope + ", Template " + selectedTemplate + ")");

            File templateFile = new File(baseDir, DATA_EXTRACTION_DIR + File.separator + selectedTemplate);
            if (!templateFile.exists()) {
                log.log("[DataExtractionService] Fehler: Template nicht gefunden unter: " + templateFile.getAbsolutePath());
                return null;
            }

            File targetDir = new File(baseDir, DATA_EXTRACTION_DIR + File.separator + TARGET_SUBDIR);
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                log.log("[DataExtractionService] Fehler: Zielordner konnte nicht erstellt werden: " + targetDir.getAbsolutePath());
                return null;
            }

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            File outputFile = new File(targetDir, "export_" + (full ? "full_" : "focus_") + timestamp + ".json");

            ITemplateEngine engine = new TextEngine();
            try {
                engine.addContext("project", currentProject);
                engine.addContext("stringUtil", new StringCleaner());
                engine.addContext("debugUtil", new DebugLogger(log));

                if (full) {
                    // Kein "element" im Kontext -> das Flat-Template läuft über das gesamte Primärmodell.
                    log.log("[DataExtractionService] Scope: gesamtes Modell");
                } else {
                    engine.addContext("centerElement", focusElement);
                    engine.addContext("maxDependencyDepth", 5);
                    engine.addContext("maxSiblingDepth", 3);
                    engine.addContext("maxSiblingOwnedDepth", 0);
                    log.log("[DataExtractionService] Scope: Umgebung von " + focusElement.getHumanName());
                }

                Template template = engine.createTemplate(templateFile, outputFile);
                engine.setProperty("input.encoding", "UTF-8");
                engine.setProperty("output.encoding", "UTF-8");
                engine.evaluate(template);

                log.log("[DataExtractionService] Erfolgreich: " + outputFile.getAbsolutePath()
                        + " (" + String.format("%.2f", outputFile.length() / 1024.0) + " KB)");
            } finally {
                engine.destroy();
            }
            return outputFile;

        } catch (Exception e) {
            log.log("[DataExtractionService] Fehler beim Export: " + e.getMessage());
            e.printStackTrace();
            return null;
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
