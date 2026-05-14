package com.nomagic.magicdraw.actions;

import com.nomagic.actions.ActionsManager;
import com.nomagic.magicdraw.ui.browser.Tree;

public interface BrowserContextAMConfigurator {
    void configure(ActionsManager manager, Tree tree);
    int getPriority();
}
