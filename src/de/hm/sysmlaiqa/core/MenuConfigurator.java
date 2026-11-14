/**
 * Darstellung und Integration der Plugin-Funktionen im Rechtsklick-Menü.
 * Ermittelt das vom Nutzer anvisierte Ziel-Element und stellt das Dropdown-Menü
 * mit den verfügbaren Modi bereit.
 */

package de.hm.sysmlaiqa.core;

import com.nomagic.actions.ActionsCategory;
import com.nomagic.actions.ActionsManager;
import com.nomagic.magicdraw.actions.BrowserContextAMConfigurator;
import com.nomagic.magicdraw.actions.DiagramContextAMConfigurator;
import com.nomagic.magicdraw.ui.browser.Tree;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;
import com.nomagic.magicdraw.uml.symbols.PresentationElement;
import de.hm.sysmlaiqa.ui.ShowExplanationAction;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;

public class MenuConfigurator implements BrowserContextAMConfigurator, DiagramContextAMConfigurator {
//wenn jemand links im Strukturbaum rechtsklickt und wenn jemand in der Mitte im Diagramm rechtsklickt

//im Strukturbaum (Containment Tree)
    @Override
    public void configure(ActionsManager mngr, Tree tree) {
        if (LOGGED_TYPES.add("Containment tree")) log("Context menu requested in 'Containment tree'");
        Element targetElement = null;

        // BEstimmung auf was der Nutzer im Strukturbaum geklickt hat
        if (tree != null && tree.getSelectedNode() != null) {
            Object obj = tree.getSelectedNode().getUserObject();
            if (obj instanceof Element) { //UML-Element?
                targetElement = (Element) obj; //als targetElement speichern
            }
        }

        addMenu(mngr, targetElement);
    }

    // Diagnose: jeden Diagramm-/Tabellentyp einmal ins Meldungsfenster schreiben, wenn CATIA unser Menü dafür anfragt
    private static final java.util.Set<String> LOGGED_TYPES = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private static void logMenuRequest(DiagramPresentationElement diagram, PresentationElement[] selected, PresentationElement requestor) {
        String type = "unknown";
        if (diagram != null) {
            // Typname per Reflection (damit es mit jeder CATIA-Version kompiliert); Fallbacks, falls das scheitert
            try {
                Object dt = diagram.getClass().getMethod("getDiagramType").invoke(diagram);
                type = String.valueOf(dt.getClass().getMethod("getType").invoke(dt));
            } catch (Throwable t1) {
                try {
                    type = String.valueOf(diagram.getClass().getMethod("getHumanType").invoke(diagram));
                } catch (Throwable t2) {
                    type = diagram.getClass().getSimpleName();
                }
            }
        }
        if (!LOGGED_TYPES.add(type)) return;
        String target = "-";
        try {
            if (requestor != null && requestor.getElement() != null) target = requestor.getElement().getHumanName();
            else if (selected != null && selected.length > 0 && selected[0] != null && selected[0].getElement() != null)
                target = selected[0].getElement().getHumanName();
        } catch (Throwable ignored) { }
        log("Context menu requested in '" + type + "' (selected: " + (selected == null ? 0 : selected.length)
                + ", clicked: " + target + ")");
    }

    private static void log(String text) {
        String msg = SysmlAiQaPlugin.LOG_PREFIX + text;
        System.out.println(msg);
        try {
            com.nomagic.magicdraw.core.Application.getInstance().getGUILog().log(msg);
        } catch (Throwable ignored) { }
    }

    @Override
    public void configure(ActionsManager mngr, DiagramPresentationElement diagram, PresentationElement[] selected, PresentationElement requestor) {
        logMenuRequest(diagram, selected, requestor);
        Element targetElement = null;

        // Bestimmung auf was der Nutzer im Diagramm geklickt hat (Block oder Hintergrund)
        if (requestor != null) {
            targetElement = requestor.getElement();
        } else if (selected != null && selected.length > 0 && selected[0] != null && selected[0].getElement() != null) {
            targetElement = selected[0].getElement(); // z. B. markierte Zeile in einer Tabelle
        } else if (diagram != null) {
            targetElement = diagram.getElement(); //Element des gesamten Diagramms
        }

        addMenu(mngr, targetElement);
    }

    // Untermenü "Ask AI" mit dem Eintrag "Ask AI..."
    private void addMenu(ActionsManager mngr, Element targetElement) {
        if (mngr.getActionFor("SYSMLAIQA_ROOT") != null) {
            return;
        }

        // Filter für unerwünschte System-Elemente
        if (targetElement != null) {
            String humanName = targetElement.getHumanName();
            if (humanName.contains("Auxiliary Resources") || humanName.contains("Code Engineering Sets")) {
                return; // Menü wird für dieses Element gar nicht erst generiert
            }
        }

        // Untermenü "Ask AI" (wie in der Vorgängerversion verschachtelt – nur so erscheint es auch in Requirement-Tabellen)
        ActionsCategory category = new ActionsCategory("SYSMLAIQA_ROOT", "Ask AI (" + SysmlAiQaPlugin.DISPLAY_NAME + ")");
        category.setNested(true);

        category.setDescription("Ask an AI questions about this SysML model");

        // Ein Eintrag "Ask AI...": das angeklickte Element ist im Fenster als Context wählbar
        category.addAction(new ShowExplanationAction(targetElement));

        mngr.addCategory(category);
    }

    @Override
    public int getPriority() {
        return 0;
    } //Priorität/position des Menüs
}