package com.nomagic.magicdraw.actions;

import com.nomagic.actions.ActionsManager;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;
import com.nomagic.magicdraw.uml.symbols.PresentationElement;

public interface DiagramContextAMConfigurator {
    void configure(ActionsManager manager, DiagramPresentationElement diagram,
                   PresentationElement[] selected, PresentationElement requestor);
    int getPriority();
}
