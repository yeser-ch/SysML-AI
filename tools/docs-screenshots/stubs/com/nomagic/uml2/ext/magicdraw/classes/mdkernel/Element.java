package com.nomagic.uml2.ext.magicdraw.classes.mdkernel;
public interface Element {
  String getID(); String getHumanType(); String getHumanName(); Element getOwner();
  java.util.Collection<Element> getOwnedElement();
}
