/**
 * Darstellung und Integration der Plugin-Funktionen im Rechtsklick-Menü.
 * Ermittelt das vom Nutzer anvisierte Ziel-Element und stellt das Dropdown-Menü
 * mit den verfügbaren Modi bereit.
 */

package com.schroedingersblock.core;

import com.nomagic.actions.ActionsCategory;
import com.nomagic.actions.ActionsManager;
import com.nomagic.magicdraw.actions.BrowserContextAMConfigurator;
import com.nomagic.magicdraw.actions.DiagramContextAMConfigurator;
import com.nomagic.magicdraw.ui.browser.Tree;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;
import com.nomagic.magicdraw.uml.symbols.PresentationElement;
import com.schroedingersblock.ui.ShowExplanationAction;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;

public class MenuConfigurator implements BrowserContextAMConfigurator, DiagramContextAMConfigurator {
//wenn jemand links im Strukturbaum rechtsklickt und wenn jemand in der Mitte im Diagramm rechtsklickt

//im Strukturbaum (Containment Tree)
    @Override
    public void configure(ActionsManager mngr, Tree tree) {
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

    @Override
    public void configure(ActionsManager mngr, DiagramPresentationElement diagram, PresentationElement[] selected, PresentationElement requestor) {
        Element targetElement = null;

        // Bestimmung auf was der Nutzer im Diagramm geklickt hat (Block oder Hintergrund)
        if (requestor != null) {
            targetElement = requestor.getElement();
        } else if (diagram != null) {
            targetElement = diagram.getElement(); //Element des gesamten Diagramms
        }

        addMenu(mngr, targetElement);
    }

    // Hauptmenü-Eintrag "Schrödinger's Block" mit dropdownmenü
    private void addMenu(ActionsManager mngr, Element targetElement) {
        if (mngr.getActionFor("SCHROEDINGER_ROOT") != null) {
            return;
        }

        // Filter für unerwünschte System-Elemente
        if (targetElement != null) {
            String humanName = targetElement.getHumanName();
            if (humanName.contains("Auxiliary Resources") || humanName.contains("Code Engineering Sets")) {
                return; // Menü wird für dieses Element gar nicht erst generiert
            }
        }

        ActionsCategory category = new ActionsCategory("SCHROEDINGER_ROOT", "Schrödinger's Block");
        category.setNested(true);

        category.setDescription("AI-powered SysML analysis tool");

        // Ein einziger Einstieg: Fenster öffnen, Frage frei eingeben.
        // Das angeklickte Element wird als "Fokus" mitgegeben (z. B. für Schnellfragen),
        // standardmäßig wird aber das GANZE Modell an die KI geschickt.
        category.addAction(new ShowExplanationAction(targetElement));

        mngr.addCategory(category);
    }

    @Override
    public int getPriority() {
        return 0;
    } //Priorität/position des Menüs
}