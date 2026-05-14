/**
 * Hauptklasse des Plugins "Schrödinger's Block".
 * Übernimmt die Registrierung der Menüs in den entsprechenden Diagramm-
 * und Strukturbaum-Kontexten sowie die initiale Abfrage der Datenschutz-Zustimmung.
 */

package com.schroedingersblock.core;

import com.nomagic.magicdraw.plugins.Plugin;
import com.nomagic.magicdraw.actions.ActionsConfiguratorsManager;
import javax.swing.JOptionPane;
import java.util.prefs.Preferences;

public class SchroedingersBlockPlugin extends Plugin {
    public static final String PRIVACY_KEY = "data_privacy_accepted";

    @Override
    public void init() {
        Preferences prefs = Preferences.userNodeForPackage(SchroedingersBlockPlugin.class); //zum speichern des Datenschutz

        // Beim ersten Start nach Datenschutz fragen
        if ("UNKNOWN".equals(prefs.get(PRIVACY_KEY, "UNKNOWN"))) {
            askPrivacyConsent(prefs);
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

    @Override
    public boolean close() { return true; }
    @Override
    public boolean isSupported() { return true; }
}