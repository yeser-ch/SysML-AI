package com.schroedingersblock.api;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;

import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;

public interface ExplanationService {
<<<<<<< HEAD
    void setDetailLevel(String level);
    String fetchExplanation(Element targetElement, String mode);
    String fetchFollowUp(String question);
=======
    String fetchExplanation(Element targetElement, String mode);
    String fetchFollowUp(String question);
    void setDetailLevel(String level);
>>>>>>> origin/main
}