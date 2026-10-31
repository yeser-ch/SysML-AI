/**
 * Benutzerinteraktion beim Klick auf den Menüeintrag "Ask AI about the model...".
 * Überprüft Datenschutz-Zustimmung und API-Key und öffnet dann das Frage-Fenster.
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
    private final Element focusElement;

    /**
     * @param focusElement Element, auf das der Nutzer geklickt hat (darf null sein, z. B. aus dem Tools-Menü).
     *                     Es dient nur als Fokus/Hinweis – exportiert wird standardmäßig das ganze Modell.
     */
    public ShowExplanationAction(Element focusElement) {
        super(focusElement == null ? "SCHROEDINGER_ASK_MODEL_TOOLS" : "SCHROEDINGER_ASK_MODEL",
                SchroedingersBlockPlugin.MENU_LABEL,
                null, null);
        this.focusElement = focusElement;
        setDescription(SchroedingersBlockPlugin.DISPLAY_NAME + ": ask an AI any question about this SysML model.");
    }

    @Override
    public void actionPerformed(ActionEvent e) { //Bei Klick auf das Menü
        if (Application.getInstance().getProject() == null) {
            JOptionPane.showMessageDialog(null, "Open a project first.", SchroedingersBlockPlugin.DISPLAY_NAME,
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        Preferences prefs = Preferences.userNodeForPackage(SchroedingersBlockPlugin.class);
        String consent = prefs.get(SchroedingersBlockPlugin.PRIVACY_KEY, "UNKNOWN"); //aktuelle Datenschutzpräferenz

        if ("ACCEPTED".equals(consent)) { //wenn zugestimmt
            checkApiKeyAndOpenDialog(prefs);
        } else { // Wenn der Nutzer abgelehnt hat --> erneute Prüfung
            int choice = JOptionPane.showConfirmDialog(null,
                    "Access Denied: You declined the Data Privacy Policy.\nReview and accept now?",
                    "Privacy Error",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.ERROR_MESSAGE);

            if (choice == JOptionPane.YES_OPTION) {
                SchroedingersBlockPlugin.askPrivacyConsent(prefs);

                if ("ACCEPTED".equals(prefs.get(SchroedingersBlockPlugin.PRIVACY_KEY, "UNKNOWN"))) {
                    checkApiKeyAndOpenDialog(prefs);
                }
            }
        }
    }

    private void checkApiKeyAndOpenDialog(Preferences prefs) {
        String apiKey = prefs.get(SchroedingersBlockPlugin.API_KEY_PREF, "");

        if (apiKey.isEmpty()) {
            SchroedingersBlockPlugin.askApiKey(prefs);
            apiKey = prefs.get(SchroedingersBlockPlugin.API_KEY_PREF, "");
        }

        if (!apiKey.isEmpty()) {
            new ExplanationDialog(focusElement).setVisible(true);
        } else {
            Application.getInstance().getGUILog().log(SchroedingersBlockPlugin.LOG_PREFIX + "Info: Abgebrochen, da kein API-Key hinterlegt wurde.");
        }
    }
}
