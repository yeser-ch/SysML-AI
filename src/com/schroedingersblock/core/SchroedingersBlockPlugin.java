/**
 * Hauptklasse des Plugins "SysML AI Q&A" (Weiterentwicklung von "Schrödinger's Block").
 * Interne Namen (Paket, Plugin-ID, Einstellungen) bleiben "schroedingersblock", damit Updates kompatibel sind.
 * Übernimmt die Registrierung der Menüs in den entsprechenden Diagramm-
 * und Strukturbaum-Kontexten sowie die initiale Abfrage der Datenschutz-Zustimmung.
 */

package com.schroedingersblock.core;

import com.nomagic.actions.AMConfigurator;
import com.nomagic.actions.ActionsCategory;
import com.nomagic.actions.ActionsManager;
import java.io.File;
import com.nomagic.magicdraw.plugins.Plugin;
import com.nomagic.magicdraw.actions.ActionsConfiguratorsManager;
import com.schroedingersblock.ui.OpenSettingsAction;
import com.schroedingersblock.ui.ShowExplanationAction;

import javax.swing.JOptionPane;
import java.util.prefs.Preferences;

public class SchroedingersBlockPlugin extends Plugin {
    /** Sichtbarer Name des Werkzeugs (Fenstertitel, Menü, Meldungen). Hier ändern, um es umzubenennen. */
    public static final String DISPLAY_NAME = "SysML AI Q&A";
    /** Menüpunkt im Rechtsklick- und Tools-Menü. */
    public static final String MENU_LABEL = "Ask AI about the model...";
    /** Präfix für Einträge im CATIA-Meldungsfenster. */
    public static final String LOG_PREFIX = "[" + DISPLAY_NAME + "] ";

    public static final String PRIVACY_KEY = "data_privacy_accepted";
    public static final String API_KEY_PREF = "gemini_api_key";
    public static final String DEFAULT_MODE_PREF = "schroedingersblock_default_mode";
    // (Niko) Schalter für Google-Search-Grounding (Default an beim ersten Start; auf Free Tier nur mit gemini-2.5 nutzbar)
    public static final String GROUNDING_PREF = "schroedingersblock_grounding_enabled";

    // Einstellungen des neuen "Ask AI about the model"-Fensters (alle auf einer Oberfläche, werden hier persistiert)
    public static final String MODEL_PREF = "schroedingersblock_model";
    public static final String TEMPERATURE_PREF = "schroedingersblock_temperature";
    public static final String MAX_TOKENS_PREF = "schroedingersblock_max_tokens";
    public static final String SCOPE_PREF = "schroedingersblock_context_scope";
    public static final String FRESH_CHAT_PREF = "schroedingersblock_fresh_chat";
    public static final String FALLBACK_PREF = "schroedingersblock_model_fallback";
    public static final String REQUIRE_IDS_PREF = "schroedingersblock_require_ids";
    public static final String REPETITIONS_PREF = "schroedingersblock_repetitions";
    public static final String PAUSE_PREF = "schroedingersblock_batch_pause_s";
    public static final String CACHE_MODEL_PREF = "schroedingersblock_cache_model";
    public static final String THINKING_PREF = "schroedingersblock_thinking";
    public static final String SETTINGS_VERSION_PREF = "schroedingersblock_settings_version";

    @Override
    public void init() {
        Preferences prefs = Preferences.userNodeForPackage(SchroedingersBlockPlugin.class); //zum speichern des Datenschutz

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

        // Menü links im Strukturbaum anzeigen
        manager.addContainmentBrowserContextConfigurator(configurator);

        // Menü in den folgenden Diagrammen anzeigen:
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
                "SysML Requirement Diagram",
                "SysML Activity Diagram",
                "SysML Parametric Diagram",
                // Tabellen und Matrizen (Typnamen je nach Version unterschiedlich – unbekannte Namen schaden nicht)
                "Requirement Table",
                "Generic Table",
                "Instance Table",
                "Glossary Table",
                "Metric Table",
                "Dependency Matrix",
                "Requirement Matrix",
                "Satisfy Requirement Matrix",
                "Verify Requirement Matrix",
                "Refine Requirement Matrix",
                "Derive Requirement Matrix",
                "Allocation Matrix"
        };

        // Zusätzlich für ALLE Diagrammtypen anmelden (deckt z. B. Requirement-Diagramme ab, deren Typname je nach
        // Version anders heißt). Doppelte Einträge verhindert MenuConfigurator über die Action-ID.
        manager.addDiagramContextConfigurator("Any Diagram", configurator);

        for (String type : diagramTypes) {
            manager.addDiagramContextConfigurator(type, configurator); // Das Menü wird für jeden Typ in der obrigen Liste angemeldet
        }

        ActionsConfiguratorsManager.getInstance().addMainMenuConfigurator(new AMConfigurator() {
            @Override
            public void configure(ActionsManager manager) {
                // Suche nach dem Tools-Menü
                ActionsCategory toolsMenu = (ActionsCategory) manager.getActionFor("TOOLS");
                if (toolsMenu != null) {
                    // Auch ohne Rechtsklick erreichbar (z. B. aus Tabellen): Auswahl wird beim Klick ermittelt
                    toolsMenu.addAction(new ShowExplanationAction(null, false, true));
                    toolsMenu.addAction(new ShowExplanationAction(null, true, true));
                    // Einstellungs-Action (API-Key)
                    toolsMenu.addAction(new OpenSettingsAction());
                }
            }

            @Override
            public int getPriority() {
                return AMConfigurator.MEDIUM_PRIORITY;
            }
        });
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
            File exportDir = com.schroedingersblock.data_extraction.DataExtractionService.getExportDirectory();

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