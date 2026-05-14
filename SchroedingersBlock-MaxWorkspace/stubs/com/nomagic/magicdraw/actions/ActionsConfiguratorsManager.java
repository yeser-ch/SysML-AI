package com.nomagic.magicdraw.actions;

public class ActionsConfiguratorsManager {
    private static final ActionsConfiguratorsManager INSTANCE = new ActionsConfiguratorsManager();
    public static ActionsConfiguratorsManager getInstance() { return INSTANCE; }
    public void addContainmentBrowserContextConfigurator(BrowserContextAMConfigurator c) {}
    public void addDiagramContextConfigurator(String type, DiagramContextAMConfigurator c) {}
}
