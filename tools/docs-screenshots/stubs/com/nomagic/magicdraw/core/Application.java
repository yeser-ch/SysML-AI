package com.nomagic.magicdraw.core;
public class Application {
  static Application I = new Application(); public static Application getInstance(){return I;}
  public GUILog getGUILog(){return new GUILog();} public javax.swing.JFrame getMainFrame(){return null;} public Project getProject(){return null;}
}
