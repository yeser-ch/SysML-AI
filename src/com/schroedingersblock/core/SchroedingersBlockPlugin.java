/**
 * Hauptklasse des Plugins "Schrödinger's Block".
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

import javax.swing.JOptionPane;
import java.util.prefs.Preferences;

public class SchroedingersBlockPlugin extends Plugin {
    public static final String PRIVACY_KEY = "data_privacy_accepted";
    public static final String API_KEY_PREF = "gemini_api_key";
    public static final String DEFAULT_MODE_PREF = "schroedingersblock_default_mode";

    @Override
    public void init() {
        Preferences prefs = Preferences.userNodeForPackage(SchroedingersBlockPlugin.class); //zum speichern des Datenschutz

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
                "SysML Use Case Diagram"
        };

        for (String type : diagramTypes) {
            manager.addDiagramContextConfigurator(type, configurator); // Das Menü wird für jeden Typ in der obrigen Liste angemeldet
        }

        ActionsConfiguratorsManager.getInstance().addMainMenuConfigurator(new AMConfigurator() {
            @Override
            public void configure(ActionsManager manager) {
                // Suche nach dem Tools-Menü
                ActionsCategory toolsMenu = (ActionsCategory) manager.getActionFor("TOOLS");
                if (toolsMenu != null) {
                    // Füge die Einstellungs-Action hinzu
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
                "Data Privacy",
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
                "API Key Configuration",
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
            File pluginDir = new File(System.getProperty("user.dir"), "plugins" + File.separator + "com.schroedingersblock.plugin");
            File exportDir = new File(pluginDir, "data_extraction" + File.separator + "raw_model_data");

            if (exportDir.exists() && exportDir.isDirectory()) {
                File[] files = exportDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.getName().endsWith(".json")) {
                            file.delete(); // Löscht alle alten Exporte beim Schließen
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