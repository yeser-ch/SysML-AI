/**
 * Benutzerinteraktion beim Klick auf einen Plugin-Menüeintrag.
 * Überprüft vor dem Start der Systemanalyse die vorliegende Datenschutz-Zustimmung und öffnet das Chat-Interface.
 */

package com.schroedingersblock.ui;

import com.nomagic.magicdraw.actions.MDAction;
import com.schroedingersblock.core.SchroedingersBlockPlugin;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import javax.swing.JOptionPane;
import java.awt.event.ActionEvent;
import java.util.prefs.Preferences;

import com.nomagic.magicdraw.core.Application;

public class ShowExplanationAction extends MDAction { //MagicDraw-Button
    private final String mode;
    private final Element targetElement;

    public ShowExplanationAction(String mode, Element targetElement) {
        //Dynamische Tooltips je nach Modus generieren ---
        super("EXPL_" + mode.toUpperCase().replace(" ", "_"), mode, null, null);
        this.mode = mode;
        this.targetElement = targetElement;

        if ("Element View".equals(mode)) {
            setDescription("Short, concise explanations.");
        } else if ("Model View".equals(mode)) {
            setDescription("Long, detailed explanations.");
        }
    }//Tastenkürzel und Icon wären noch möglich

    @Override
    public void actionPerformed(ActionEvent e) { //Bei Klick auf das Menü
        Preferences prefs = Preferences.userNodeForPackage(SchroedingersBlockPlugin.class);
        String consent = prefs.get(SchroedingersBlockPlugin.PRIVACY_KEY, "UNKNOWN"); //aktuelle Datenschutzpräferenz

        if ("ACCEPTED".equals(consent)) { //wenn zugestimmt
            checkApiKeyAndOpenDialog(prefs); // NEU: Ausgelagerte Logik
        } else { // Wenn der Nutzer abgelehnt hat --> erneute Prüfung
            int choice = JOptionPane.showConfirmDialog(null,
                    "Access Denied: You declined the Data Privacy Policy.\nReview and accept now?",
                    "Privacy Error",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.ERROR_MESSAGE);

            if (choice == JOptionPane.YES_OPTION) {
                SchroedingersBlockPlugin.askPrivacyConsent(prefs);

                // Wenn jetzt zugestimmt, API Key prüfen und Dialog öffnen
                if ("ACCEPTED".equals(prefs.get(SchroedingersBlockPlugin.PRIVACY_KEY, "UNKNOWN"))) {
                    checkApiKeyAndOpenDialog(prefs); // NEU
                }
            }
        }
    }

    private void checkApiKeyAndOpenDialog(Preferences prefs) {
        String apiKey = prefs.get(SchroedingersBlockPlugin.API_KEY_PREF, "");

        // Wenn kein Key vorhanden ist, Nutzer fragen
        if (apiKey.isEmpty()) {
            SchroedingersBlockPlugin.askApiKey(prefs);
            apiKey = prefs.get(SchroedingersBlockPlugin.API_KEY_PREF, ""); // Key nach Eingabe neu laden
        }

        // Nur wenn wir jetzt einen Key haben, öffne Dialog
        if (!apiKey.isEmpty()) {
            openDialog();
        } else {
            Application.getInstance().getGUILog().log("[Schrödinger's Block] Info: Analyse abgebrochen, da kein API-Key hinterlegt wurde.");
        }
    }

    private void openDialog() {
        // --- NEU: JSON-Export triggern, bevor das Dialog-Fenster öffnet ---
        //Project project = Application.getInstance().getProject();
        //if (project != null) {
        //    // Wenn "Model View" gewählt wurde, Scope null lassen (-> exportiert gesamtes Primärmodell)
        //    // Wenn "Element View" gewählt wurde, nutzen wir das targetElement als Scope
        //    Element scope = "Model View".equals(mode) ? null : targetElement;
        //
        //    boolean success = DataExtractionService.executeJsonExport(project, scope);
        //    if (!success) {
        //        Application.getInstance().getGUILog().log("[Schrödinger's Block] Warnung: JSON-Export fehlgeschlagen.");
        //}
    //    }
        // ------------------------------------------------------------------

        new ExplanationDialog(mode, targetElement).setVisible(true); //modus und aktuelles Element weitergeben und chatfenster aktivieren
    }
}