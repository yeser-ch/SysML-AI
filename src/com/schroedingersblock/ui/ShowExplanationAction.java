/**
 * Menüeinträge "Ask AI about the model..." und "Ask AI about this element...".
 * Prüft Datenschutz-Zustimmung und API-Key und öffnet dann das Frage-Fenster.
 */

package com.schroedingersblock.ui;

import com.nomagic.magicdraw.actions.MDAction;
import com.schroedingersblock.core.SchroedingersBlockPlugin;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import javax.swing.JOptionPane;
import java.awt.event.ActionEvent;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.prefs.Preferences;

import com.nomagic.magicdraw.core.Application;

public class ShowExplanationAction extends MDAction {

    public static final String ELEMENT_LABEL = "Ask AI about this element...";
    public static final String SELECTED_ELEMENT_LABEL = "Ask AI about the selected element...";

    private final Element focusElement;
    private final boolean elementMode;
    private final boolean resolveSelectionOnClick;

    /** Kontextmenü: Fragen zum ganzen Modell, angeklicktes Element als Fokus. */
    public ShowExplanationAction(Element focusElement) {
        this(focusElement, false, false);
    }

    /**
     * @param focusElement            angeklicktes Element (darf null sein)
     * @param elementMode             true = "Ask AI about this element" (nur Umgebung des Elements exportieren)
     * @param resolveSelectionOnClick true = Tools-Menü: Auswahl erst beim Klick ermitteln (Diagramm/Tabelle/Baum)
     */
    public ShowExplanationAction(Element focusElement, boolean elementMode, boolean resolveSelectionOnClick) {
        super(id(elementMode, resolveSelectionOnClick), label(elementMode, resolveSelectionOnClick), null, null);
        this.focusElement = focusElement;
        this.elementMode = elementMode;
        this.resolveSelectionOnClick = resolveSelectionOnClick;
        setDescription(elementMode
                ? SchroedingersBlockPlugin.DISPLAY_NAME + ": ask an AI about this element and its surroundings."
                : SchroedingersBlockPlugin.DISPLAY_NAME + ": ask an AI any question about this SysML model.");
    }

    private static String id(boolean elementMode, boolean tools) {
        return "SCHROEDINGER_ASK_" + (elementMode ? "ELEMENT" : "MODEL") + (tools ? "_TOOLS" : "");
    }

    private static String label(boolean elementMode, boolean tools) {
        if (!elementMode) return SchroedingersBlockPlugin.MENU_LABEL;
        return tools ? SELECTED_ELEMENT_LABEL : ELEMENT_LABEL;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (Application.getInstance().getProject() == null) {
            JOptionPane.showMessageDialog(null, "Open a project first.", SchroedingersBlockPlugin.DISPLAY_NAME,
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        Element focus = focusElement;
        if (resolveSelectionOnClick) focus = currentSelection();
        if (elementMode && focus == null) {
            JOptionPane.showMessageDialog(null,
                    "No element selected.\n\nSelect an element in the containment tree or in a diagram first.\n"
                            + "In a table: right-click the row -> 'Select in Containment Tree' (Alt+B), then try again.",
                    SchroedingersBlockPlugin.DISPLAY_NAME, JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        Preferences prefs = Preferences.userNodeForPackage(SchroedingersBlockPlugin.class);
        String consent = prefs.get(SchroedingersBlockPlugin.PRIVACY_KEY, "UNKNOWN");

        if ("ACCEPTED".equals(consent)) {
            checkApiKeyAndOpenDialog(prefs, focus);
        } else {
            int choice = JOptionPane.showConfirmDialog(null,
                    "Access Denied: You declined the Data Privacy Policy.\nReview and accept now?",
                    "Privacy Error",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.ERROR_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) {
                SchroedingersBlockPlugin.askPrivacyConsent(prefs);
                if ("ACCEPTED".equals(prefs.get(SchroedingersBlockPlugin.PRIVACY_KEY, "UNKNOWN"))) {
                    checkApiKeyAndOpenDialog(prefs, focus);
                }
            }
        }
    }

    private void checkApiKeyAndOpenDialog(Preferences prefs, Element focus) {
        String apiKey = prefs.get(SchroedingersBlockPlugin.API_KEY_PREF, "");
        if (apiKey.isEmpty()) {
            SchroedingersBlockPlugin.askApiKey(prefs);
            apiKey = prefs.get(SchroedingersBlockPlugin.API_KEY_PREF, "");
        }
        if (!apiKey.isEmpty()) {
            new ExplanationDialog(focus, elementMode).setVisible(true);
        } else {
            Application.getInstance().getGUILog().log(SchroedingersBlockPlugin.LOG_PREFIX + "Info: Abgebrochen, da kein API-Key hinterlegt wurde.");
        }
    }

    /**
     * Aktuell ausgewähltes Element: zuerst im aktiven Diagramm bzw. in der aktiven Tabelle, sonst im Containment Tree.
     * Per Reflection, damit unterschiedliche CATIA-Versionen nicht am Kompilieren scheitern.
     */
    static Element currentSelection() {
        Object project = Application.getInstance().getProject();
        // 1. Aktives Diagramm / aktive Tabelle
        try {
            Object diagram = call(project, "getActiveDiagram");
            Object selected = call(diagram, "getSelected");
            Element el = firstElement(selected);
            if (el != null) return el;
        } catch (Throwable ignored) { }
        // 2. Containment Tree
        try {
            Object browser = call(project, "getBrowser");
            Object tree = call(browser, "getContainmentTree");
            Object node = call(tree, "getSelectedNode");
            Object user = call(node, "getUserObject");
            if (user instanceof Element) return (Element) user;
        } catch (Throwable ignored) { }
        return null;
    }

    private static Element firstElement(Object selected) throws Exception {
        if (selected instanceof Collection) {
            for (Object pe : (Collection<?>) selected) {
                Object el = pe instanceof Element ? pe : call(pe, "getElement");
                if (el instanceof Element) return (Element) el;
            }
        } else if (selected instanceof Object[]) {
            for (Object pe : (Object[]) selected) {
                Object el = pe instanceof Element ? pe : call(pe, "getElement");
                if (el instanceof Element) return (Element) el;
            }
        }
        return null;
    }

    private static Object call(Object target, String method) throws Exception {
        if (target == null) return null;
        Method m = target.getClass().getMethod(method);
        return m.invoke(target);
    }
}
