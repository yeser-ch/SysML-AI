package com.nomagic.magicdraw.actions;
public class ActionsConfiguratorsManager { static ActionsConfiguratorsManager I=new ActionsConfiguratorsManager(); public static ActionsConfiguratorsManager getInstance(){return I;}
 public void addMainMenuConfigurator(com.nomagic.actions.AMConfigurator c){} public void addContainmentBrowserContextConfigurator(BrowserContextAMConfigurator c){} public void addBaseDiagramContextConfigurator(String t, DiagramContextAMConfigurator c){} public void addDiagramContextConfigurator(String t, DiagramContextAMConfigurator c){} }
