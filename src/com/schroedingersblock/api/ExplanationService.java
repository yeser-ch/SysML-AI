package com.schroedingersblock.api;

import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;

public interface ExplanationService {
    void setDetailLevel(String level);
    String fetchExplanation(Element targetElement, String mode);
    String fetchFollowUp(String question);
}