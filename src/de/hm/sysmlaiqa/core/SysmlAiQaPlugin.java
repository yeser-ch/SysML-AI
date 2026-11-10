/**
 * Hauptklasse des Plugins "SysML AI Q&A".
 * Übernimmt die Registrierung der Menüs in den entsprechenden Diagramm-
 * und Strukturbaum-Kontexten sowie die initiale Abfrage der Datenschutz-Zustimmung.
 */

package de.hm.sysmlaiqa.core;

import com.nomagic.actions.AMConfigurator;
import com.nomagic.actions.ActionsCategory;
import com.nomagic.actions.ActionsManager;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import com.nomagic.magicdraw.plugins.Plugin;
import com.nomagic.magicdraw.actions.ActionsConfiguratorsManager;
import de.hm.sysmlaiqa.ui.OpenSettingsAction;
import de.hm.sysmlaiqa.ui.ShowExplanationAction;

import javax.swing.JOptionPane;
import java.util.prefs.Preferences;

public class SysmlAiQaPlugin extends Plugin {
    /** Sichtbarer Name des Werkzeugs (Fenstertitel, Menü, Meldungen). Hier ändern, um es umzubenennen. */
    public static final String DISPLAY_NAME = "SysML AI Q&A";
    /** Menüpunkt im Rechtsklick- und Tools-Menü. */
    public static final String MENU_LABEL = "Ask AI about the model...";
    /** Präfix für Einträge im CATIA-Meldungsfenster. */
    public static final String LOG_PREFIX = "[" + DISPLAY_NAME + "] ";

    public static final String PRIVACY_KEY = "data_privacy_accepted";
    public static final String API_KEY_PREF = "gemini_api_key";
    // Schalter für Google-Search-Grounding (Standard: aus, siehe init())
    public static final String GROUNDING_PREF = "sysmlaiqa_grounding_enabled";

    // Einstellungen des neuen "Ask AI about the model"-Fensters (alle auf einer Oberfläche, werden hier persistiert)
    public static final String MODEL_PREF = "sysmlaiqa_model";
    public static final String TEMPERATURE_PREF = "sysmlaiqa_temperature";
    public static final String MAX_TOKENS_PREF = "sysmlaiqa_max_tokens";
    public static final String SCOPE_PREF = "sysmlaiqa_context_scope";
    public static final String FRESH_CHAT_PREF = "sysmlaiqa_fresh_chat";
    public static final String FALLBACK_PREF = "sysmlaiqa_model_fallback";
    public static final String REQUIRE_IDS_PREF = "sysmlaiqa_require_ids";
    public static final String REPETITIONS_PREF = "sysmlaiqa_repetitions";
    public static final String PAUSE_PREF = "sysmlaiqa_batch_pause_s";
    public static final String CACHE_MODEL_PREF = "sysmlaiqa_cache_model";
    public static final String THINKING_PREF = "sysmlaiqa_thinking";
    public static final String SETTINGS_VERSION_PREF = "sysmlaiqa_settings_version";
    public static final String LEGACY_IMPORTED_PREF = "sysmlaiqa_legacy_settings_imported";

    /** Einstellungsknoten und Schlüsselpräfix der Ausgangsversion (nur für die einmalige Übernahme). */
    private static final String LEGACY_NODE = "com/schroedingersblock/core";
    private static final String LEGACY_KEY_PREFIX = "schroedingersblock_";

    @Override
    public void init() {
        Preferences prefs = Preferences.userNodeForPackage(SysmlAiQaPlugin.class); //zum speichern des Datenschutz
        String imported = importLegacySettings(prefs);

        // Beim allerersten Start noch kein gespeicherter Wert vorhanden -> Default an setzen.
        // Danach bleibt der zuletzt vom Nutzer gewählte Wert über Neustarts hinweg erhalten.
        // Einmalige Umstellung beim Update von der Vorgängerversion:
        // Grounding ist ab jetzt standardmäßig AUS (Google-Suchergebnisse würden die Evaluation verfälschen,
        // und neue Modelle unterstützen es im Free Tier nicht). Die alte Version hatte "an" gespeichert.
        if (prefs.getInt(SETTINGS_VERSION_PREF, 1) < 2) {
            prefs.putBoolean(GROUNDING_PREF, false);
            prefs.putInt(SETTINGS_VERSION_PREF, 2);
        }

        // Beim ersten Start nach Datenschutz fragen
        if ("UNKNOWN".equals(prefs.get(PRIVACY_KEY, "UNKNOWN"))) {
            askPrivacyConsent(prefs);
        }

        // Direkt im Anschluss den API-Key abfragen (nur, wenn der Datenschutz akzeptiert wurde und noch kein Key existiert)
        if ("ACCEPTED".equals(prefs.get(PRIVACY_KEY, "UNKNOWN")) && prefs.get(API_KEY_PREF, "").isEmpty()) {
            askApiKey(prefs);
        }

        MenuConfigurator configurator = new MenuConfigurator(); //Pluginmenu instanzeiren mit dem namen configurator
        ActionsConfiguratorsManager manager = ActionsConfiguratorsManager.getInstance();
        List<String> problems = new ArrayList<>();

        // 1. Tools-Menü ZUERST anmelden – darf nicht an einem Fehler bei den Diagrammtypen scheitern
        try {
            manager.addMainMenuConfigurator(new AMConfigurator() {
                @Override
                public void configure(ActionsManager manager) {
                    // Eigenes Menü "Ask AI" in der Menüleiste (gut auffindbar, unabhängig von der internen ID des Tools-Menüs).
                    // Die Auswahl (Baum, Diagramm, Tabellenzeile) wird erst beim Klick ermittelt.
                    if (manager.getActionFor("SYSMLAIQA_MAIN_MENU") != null) return;
                    ActionsCategory menu = new ActionsCategory("SYSMLAIQA_MAIN_MENU", "Ask AI");
                    menu.setNested(true);
                    menu.addAction(new ShowExplanationAction(null, false, true));
                    menu.addAction(new ShowExplanationAction(null, true, true));
                    menu.addAction(new OpenSettingsAction());
                    manager.addCategory(menu);
                }

                @Override
                public int getPriority() {
                    return AMConfigurator.MEDIUM_PRIORITY;
                }
            });
        } catch (Throwable t) {
            problems.add("Tools menu: " + t);
        }

        // 2. Rechtsklick im Modellbaum
        try {
            manager.addContainmentBrowserContextConfigurator(configurator);
        } catch (Throwable t) {
            problems.add("Containment tree: " + t);
        }

        // 3. Rechtsklick in Diagrammen und Tabellen (wie in der Vorgängerversion, die in Requirement-Tabellen funktionierte)
        String[] diagramTypes = {
                "SysML Block Definition Diagram",
                "SysML Internal Block Diagram",
                "Class Diagram",
                "Activity Diagram",
                "Requirement Diagram",
                "SysML Requirement Matrix",
                "SysML Requirement Table",
                "SysML State Machine Diagram",
                "SysML Sequence Diagram",
                "SysML Package Diagram",
                "SysML Use Case Diagram",
                // zusätzlich (je nach Version andere Typnamen)
                "SysML Requirement Diagram",
                "SysML Activity Diagram",
                "SysML Parametric Diagram",
                "Requirement Table",
                "Generic Table",
                "Any Diagram"
        };
        int registered = 0;
        for (String type : diagramTypes) {
            try {
                manager.addDiagramContextConfigurator(type, configurator);
                registered++;
            } catch (Throwable t) {
                problems.add(type + ": " + t);
            }
        }

        // Kontrollmeldung im CATIA-Meldungsfenster (zeigt, welche Version geladen ist)
        String msg = LOG_PREFIX + "Plugin loaded (version " + de.hm.sysmlaiqa.api.EvaluationRecord.PLUGIN_VERSION
                + ", " + registered + "/" + diagramTypes.length + " diagram/table types)"
                + (imported == null ? "" : " – " + imported)
                + (problems.isEmpty() ? "" : " – problems: " + String.join("; ", problems));
        System.out.println(msg);
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                com.nomagic.magicdraw.core.Application.getInstance().getGUILog().log(msg);
            } catch (Throwable ignored) { }
        });
    }

    /**
     * Übernimmt einmalig API-Key, Datenschutz-Zustimmung und Einstellungen aus der Ausgangsversion
     * (anderer Paketname = anderer Einstellungsknoten). Vorhandene neue Werte werden nicht überschrieben.
     *
     * @return Hinweis für die Logzeile oder null, wenn nichts übernommen wurde
     */
    static String importLegacySettings(Preferences now) {
        try {
            if (now.getBoolean(LEGACY_IMPORTED_PREF, false)) return null;
            int count = 0;
            Preferences root = Preferences.userRoot();
            if (root.nodeExists(LEGACY_NODE)) {
                Preferences old = root.node(LEGACY_NODE);
                for (String key : old.keys()) {
                    String newKey = key.startsWith(LEGACY_KEY_PREFIX)
                            ? "sysmlaiqa_" + key.substring(LEGACY_KEY_PREFIX.length()) : key;
                    String value = old.get(key, null);
                    if (value != null && now.get(newKey, null) == null) {
                        now.put(newKey, value);
                        count++;
                    }
                }
            }
            now.putBoolean(LEGACY_IMPORTED_PREF, true);
            now.flush();
            return count == 0 ? null : "imported " + count + " settings (incl. API key) from the previous version";
        } catch (Exception e) {
            System.err.println(LOG_PREFIX + "Could not import settings of the previous version: " + e.getMessage());
            return null;
        }
    }

    // Zeigt das DatenschutzustimmungsFenster an und speichert die Antwort
    public static void askPrivacyConsent(Preferences prefs) {
        int choice = JOptionPane.showConfirmDialog(null,
                "Privacy Notice: \nBy using this plugin, model data will be sent to an external AI provider.\nDo you agree?",
                DISPLAY_NAME + " – Data privacy",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (choice == JOptionPane.YES_OPTION) {
            prefs.put(PRIVACY_KEY, "ACCEPTED");
        } else {
            prefs.put(PRIVACY_KEY, "DECLINED");
        }
    }

    // Eingabefenster für den API-Key
    public static void askApiKey(Preferences prefs) {
        String newKey = JOptionPane.showInputDialog(null,
                "Please enter your Gemini API Key:\n(This will be saved locally on your device)",
                DISPLAY_NAME + " – API key",
                JOptionPane.QUESTION_MESSAGE);

        // Nur speichern, wenn der Nutzer nicht "Abbrechen" gedrückt hat und das Feld nicht leer ist
        if (newKey != null && !newKey.trim().isEmpty()) {
            prefs.put(API_KEY_PREF, newKey.trim());
        }
    }

    @Override
    public boolean close() {
        try {
            // Pfad zum raw_model_data Ordner ermitteln
            // Temporäre Exporte löschen (Kopien für die Auswertung liegen in archive/exports)
            File exportDir = de.hm.sysmlaiqa.data_extraction.DataExtractionService.getExportDirectory();

            if (exportDir.exists() && exportDir.isDirectory()) {
                File[] files = exportDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.getName().endsWith(".json")) {
                            // KORREKTUR: Rückgabewert auswerten, um die IDE-Warnung zu beheben
                            boolean deleted = file.delete();
                            if (!deleted) {
                                System.err.println(LOG_PREFIX + "Warning: File could not be deleted: " + file.getName());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Loggen, aber Schließen des Plugins nicht blockieren
            System.err.println("Cleanup failed: " + e.getMessage());
        }
        return true;
    }
    @Override
    public boolean isSupported() { return true; }
}