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
import com.nomagic.magicdraw.core.Project;
import com.schroedingersblock.data_extraction.DataExtractionService;

public class ShowExplanationAction extends MDAction { //MagicDraw-Button
    private String mode;
    private Element targetElement;

    public ShowExplanationAction(String mode, Element targetElement) {
        super("EXPL_" + mode.toUpperCase().replace(" ", "_"), mode, null, null); //ID vergeben.. z.b: EXPL_ELEMENT_VIEW
        this.mode = mode;
        this.targetElement = targetElement;
    } //Tastenkürzel und Icon wären noch möglich

    @Override
    public void actionPerformed(ActionEvent e) { //Bei Klick auf das Menü
        Preferences prefs = Preferences.userNodeForPackage(SchroedingersBlockPlugin.class);
        String consent = prefs.get(SchroedingersBlockPlugin.PRIVACY_KEY, "UNKNOWN"); //aktuelle Datenschutzpräferenz

        if ("ACCEPTED".equals(consent)) { //wenn zugestimmt
            openDialog();
        } else { // Wenn der Nutzer abgelehnt hat --> erneute Prüfung
            int choice = JOptionPane.showConfirmDialog(null, //errorfenster mit frage ob nochmal das Datenschutzfenster angezeigt werden soll
                    "Access Denied: You declined the Data Privacy Policy.\nReview and accept now?",
                    "Privacy Error",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.ERROR_MESSAGE);

            if (choice == JOptionPane.YES_OPTION) {
                SchroedingersBlockPlugin.askPrivacyConsent(prefs);

                // Wenn jetzt zugestimmt, direkt Chat-Fenster öffnen
                if ("ACCEPTED".equals(prefs.get(SchroedingersBlockPlugin.PRIVACY_KEY, "UNKNOWN"))) {
                    openDialog();
                }
            }
        }
    }

    private void openDialog() {
        // --- NEU: JSON-Export triggern, bevor das Dialog-Fenster öffnet ---
        Project project = Application.getInstance().getProject();
        if (project != null) {
            // Wenn "Model View" gewählt wurde, Scope null lassen (-> exportiert gesamtes Primärmodell)
            // Wenn "Element View" gewählt wurde, nutzen wir das targetElement als Scope
            Element scope = "Model View".equals(mode) ? null : targetElement;
            
            boolean success = DataExtractionService.executeJsonExport(project, scope);
            if (!success) {
                Application.getInstance().getGUILog().log("[Schrödinger's Block] Warnung: JSON-Export fehlgeschlagen.");
            }
        }
        // ------------------------------------------------------------------

        new ExplanationDialog(mode, targetElement).setVisible(true); //modus und aktuelles Element weitergeben und chatfenster aktivieren
    }
}