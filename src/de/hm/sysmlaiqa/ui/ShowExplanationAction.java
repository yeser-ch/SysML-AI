/**
 * Menüeintrag "Ask AI...". Prüft Datenschutz-Zustimmung und API-Key und öffnet dann das Frage-Fenster.
 * Ein angeklicktes bzw. ausgewähltes Element wird mitgegeben; im Fenster lässt es sich als Context wählen.
 */

package de.hm.sysmlaiqa.ui;

import com.nomagic.magicdraw.actions.MDAction;
import de.hm.sysmlaiqa.core.SysmlAiQaPlugin;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import javax.swing.JOptionPane;
import java.awt.event.ActionEvent;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.prefs.Preferences;

import com.nomagic.magicdraw.core.Application;

public class ShowExplanationAction extends MDAction {

    private final Element focusElement;
    private final boolean resolveSelectionOnClick;

    /** Kontextmenü: angeklicktes Element wird mitgegeben. */
    public ShowExplanationAction(Element focusElement) {
        this(focusElement, false);
    }

    /**
     * @param focusElement            angeklicktes Element (darf null sein)
     * @param resolveSelectionOnClick true = Menüleiste: Auswahl erst beim Klick ermitteln (Diagramm/Tabelle/Baum)
     */
    public ShowExplanationAction(Element focusElement, boolean resolveSelectionOnClick) {
        super("SYSMLAIQA_ASK" + (resolveSelectionOnClick ? "_TOOLS" : ""), SysmlAiQaPlugin.MENU_LABEL, null, null);
        this.focusElement = focusElement;
        this.resolveSelectionOnClick = resolveSelectionOnClick;
        setDescription(SysmlAiQaPlugin.DISPLAY_NAME + ": ask an AI questions about this SysML model or a selected element.");
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (Application.getInstance().getProject() == null) {
            JOptionPane.showMessageDialog(null, "Open a project first.", SysmlAiQaPlugin.DISPLAY_NAME,
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        if (resolveSelectionOnClick) {
            // Liegt der Fokus in einer Tabelle, CATIAs eigenes "Select in Containment Tree" (Alt+B) auslösen
            // und danach die Auswahl im Modellbaum lesen – zuverlässiger als die Tabelle selbst auszulesen.
            java.awt.Component focusOwner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
            if (isInTable(focusOwner)) {
                clearTreeSelection(); // damit eindeutig ist, ob Alt+B etwas ausgewählt hat
                pressAltB(focusOwner);
                // zweimal invokeLater: CATIA wählt im Baum ggf. selbst erst per invokeLater aus
                javax.swing.SwingUtilities.invokeLater(() -> javax.swing.SwingUtilities.invokeLater(() -> {
                    Object after = treeSelectionObject();
                    Element el = after instanceof Element ? (Element) after : null;
                    if (el != null) {
                        logInfo("Selected table row via 'Select in Containment Tree': " + el.getHumanName());
                        proceed(el);
                    } else {
                        logInfo("'Select in Containment Tree' did not change the tree selection - reading the table instead.");
                        proceed(currentSelection());
                    }
                }));
                return;
            }
            proceed(currentSelection());
        } else {
            proceed(focusElement);
        }
    }

    private void proceed(Element focus) {
        Preferences prefs = Preferences.userNodeForPackage(SysmlAiQaPlugin.class);
        String consent = prefs.get(SysmlAiQaPlugin.PRIVACY_KEY, "UNKNOWN");

        if ("ACCEPTED".equals(consent)) {
            checkApiKeyAndOpenDialog(prefs, focus);
        } else {
            int choice = JOptionPane.showConfirmDialog(null,
                    "Access Denied: You declined the Data Privacy Policy.\nReview and accept now?",
                    "Privacy Error",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.ERROR_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) {
                SysmlAiQaPlugin.askPrivacyConsent(prefs);
                if ("ACCEPTED".equals(prefs.get(SysmlAiQaPlugin.PRIVACY_KEY, "UNKNOWN"))) {
                    checkApiKeyAndOpenDialog(prefs, focus);
                }
            }
        }
    }

    private void checkApiKeyAndOpenDialog(Preferences prefs, Element focus) {
        String apiKey = prefs.get(SysmlAiQaPlugin.API_KEY_PREF, "");
        if (apiKey.isEmpty()) {
            SysmlAiQaPlugin.askApiKey(prefs);
            apiKey = prefs.get(SysmlAiQaPlugin.API_KEY_PREF, "");
        }
        if (!apiKey.isEmpty()) {
            new ExplanationDialog(focus).setVisible(true);
        } else {
            Application.getInstance().getGUILog().log(SysmlAiQaPlugin.LOG_PREFIX + "Cancelled: no API key configured.");
        }
    }

    private static boolean isInTable(java.awt.Component c) {
        for (; c != null; c = c.getParent()) {
            if (c instanceof javax.swing.JTree) return false; // Modellbaum: Auswahl direkt lesbar
            if (c instanceof javax.swing.JTable) return true;
            String n = c.getClass().getName().toLowerCase();
            if (n.contains("table") || n.contains("grid")) return true;
        }
        return false;
    }

    /** Simuliert Alt+B ("Select in Containment Tree") auf der fokussierten Komponente. */
    private static void pressAltB(java.awt.Component target) {
        try {
            long now = System.currentTimeMillis();
            int alt = java.awt.event.InputEvent.ALT_DOWN_MASK;
            target.dispatchEvent(new java.awt.event.KeyEvent(target, java.awt.event.KeyEvent.KEY_PRESSED, now, alt,
                    java.awt.event.KeyEvent.VK_B, java.awt.event.KeyEvent.CHAR_UNDEFINED));
            target.dispatchEvent(new java.awt.event.KeyEvent(target, java.awt.event.KeyEvent.KEY_RELEASED, now + 1, alt,
                    java.awt.event.KeyEvent.VK_B, java.awt.event.KeyEvent.CHAR_UNDEFINED));
        } catch (Throwable t) {
            logInfo("Could not trigger 'Select in Containment Tree': " + t);
        }
    }

    private static void clearTreeSelection() {
        try {
            Object project = Application.getInstance().getProject();
            Object tree = call(call(project, "getBrowser"), "getContainmentTree");
            Object jtree = call(tree, "getTree");
            if (jtree instanceof javax.swing.JTree) ((javax.swing.JTree) jtree).clearSelection();
        } catch (Throwable ignored) { }
    }

    /** Aktuell im Containment Tree ausgewähltes Objekt (oder null). */
    private static Object treeSelectionObject() {
        try {
            Object project = Application.getInstance().getProject();
            Object browser = call(project, "getBrowser");
            Object tree = call(browser, "getContainmentTree");
            Object node = call(tree, "getSelectedNode");
            return call(node, "getUserObject");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void logInfo(String msg) {
        try {
            Application.getInstance().getGUILog().log(SysmlAiQaPlugin.LOG_PREFIX + msg);
        } catch (Throwable ignored) { }
    }

    /**
     * Aktuell ausgewähltes Element: zuerst im aktiven Diagramm bzw. in der aktiven Tabelle, sonst im Containment Tree.
     * Per Reflection, damit unterschiedliche CATIA-Versionen nicht am Kompilieren scheitern.
     */
    static Element currentSelection() {
        Object project = Application.getInstance().getProject();
        // 0. Markierte Zeile in einer Tabelle (Requirement Table usw.): Tabelle mit dem Tastaturfokus auslesen
        try {
            Element el = selectedTableRowElement();
            if (el != null) return el;
        } catch (Throwable ignored) { }
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

    /**
     * Sucht eine Tabelle mit markierter Zeile und liefert deren Modellelement.
     * Reihenfolge: Tabelle mit Tastaturfokus, dann alle sichtbaren Tabellen in allen Fenstern.
     * Findet sie nichts, wird der Aufbau ins Notification Window geschrieben (Diagnose).
     */
    private static Element selectedTableRowElement() {
        StringBuilder diag = new StringBuilder();
        java.awt.Component focus = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
        diag.append("focus=").append(focus == null ? "null" : focus.getClass().getName());
        // 1. Tabelle im Fokus (oder deren Eltern)
        for (java.awt.Component c = focus; c != null; c = c.getParent()) {
            if (c instanceof javax.swing.JTable) {
                Element el = elementFromTable((javax.swing.JTable) c, diag);
                if (el != null) return el;
                break;
            }
        }
        // 2. Alle sichtbaren Tabellen in allen Fenstern
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (!w.isShowing()) continue;
            java.util.List<javax.swing.JTable> tables = new java.util.ArrayList<>();
            collectTables(w, tables);
            for (javax.swing.JTable t : tables) {
                if (!t.isShowing() || t.getSelectedRow() < 0) continue;
                Element el = elementFromTable(t, diag);
                if (el != null) return el;
            }
        }
        lastTableDiagnosis = diag.toString();
        return null;
    }

    static String lastTableDiagnosis = "";

    private static void collectTables(java.awt.Component c, java.util.List<javax.swing.JTable> out) {
        if (c instanceof javax.swing.JTable) out.add((javax.swing.JTable) c);
        if (c instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) c).getComponents()) collectTables(child, out);
        }
    }

    private static Element elementFromTable(javax.swing.JTable table, StringBuilder diag) {
        int row = table.getSelectedRow();
        diag.append(" | table=").append(table.getClass().getName()).append(" model=")
                .append(table.getModel().getClass().getName()).append(" row=").append(row);
        if (row < 0) return null;
        // a) Was in der markierten Zeile ANGEZEIGT wird (zuverlässig, gleiche Zeilenzählung wie auf dem Bildschirm).
        //    Zuerst die Spalte "Name", dann die übrigen Spalten.
        int nameCol = -1;
        for (int col = 0; col < table.getColumnCount(); col++) {
            try {
                if ("Name".equalsIgnoreCase(String.valueOf(table.getColumnName(col)).trim())) { nameCol = col; break; }
            } catch (Throwable ignored) { }
        }
        java.util.List<Integer> cols = new java.util.ArrayList<>();
        if (nameCol >= 0) cols.add(nameCol);
        for (int col = 0; col < table.getColumnCount(); col++) if (col != nameCol) cols.add(col);
        for (int col : cols) {
            Object v = null;
            try { v = table.getValueAt(row, col); } catch (Throwable ignored) { }
            if (diag.length() < 600) diag.append(" col").append(col).append("=").append(v == null ? "null" : v.getClass().getName());
            Element el = asElement(v, 0);
            if (el != null) return el;
        }
        // b) Fallback: Methoden des Tabellenmodells (Zeilenzählung kann hier abweichen)
        int modelRow = row;
        try { modelRow = table.convertRowIndexToModel(row); } catch (Throwable ignored) { }
        Object model = table.getModel();
        for (String m : new String[]{"getRowElement", "getElementAt", "getRowObject", "getObjectAt", "getRowValue", "getNodeForRow"}) {
            try {
                java.lang.reflect.Method meth = model.getClass().getMethod(m, int.class);
                Element el = asElement(meth.invoke(model, modelRow), 0);
                if (el != null) {
                    diag.append(" via model.").append(m);
                    return el;
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    /** Versucht, aus einem beliebigen Tabellen-/Baumobjekt das Modellelement zu holen (bis 2 Ebenen tief). */
    private static Element asElement(Object value, int depth) {
        if (value == null || depth > 2) return null;
        if (value instanceof Element) return (Element) value;
        if (value instanceof Object[]) {
            for (Object o : (Object[]) value) { Element el = asElement(o, depth + 1); if (el != null) return el; }
            return null;
        }
        if (value instanceof Collection) {
            for (Object o : (Collection<?>) value) { Element el = asElement(o, depth + 1); if (el != null) return el; }
            return null;
        }
        for (String m : new String[]{"getElement", "getUserObject", "getValue", "getRowElement", "getObject",
                "getModelElement", "getData", "getRowObject", "getTarget"}) {
            try {
                Object v = call(value, m);
                if (v != null && v != value) {
                    Element el = asElement(v, depth + 1);
                    if (el != null) return el;
                }
            } catch (Throwable ignored) { }
        }
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
