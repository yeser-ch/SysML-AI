package com.schroedingersblock.api;

public class DummyExplanationService implements ExplanationService {

    @Override
    public String fetchExplanation(String mode) {
        return "Initial Analysis (" + mode + "):\n" +
                "XXXXXXXXXXXXXXXXXXX ExampleResponse XXXXXXXXXXXXXXXXXXX\n" +
                "This SysML v1.x element is interpreted as a 'Block'. In the context of " +
                "the system architecture, it represents a structural unit that encapsulates " +
                "both behavior and properties." ;
    }

    /**
     *  follow-up questions in the same session.
     */
    public String fetchFollowUp(String question) {
        return "XXXXXXXXXXXXXXXXXXX ExampleResponse XXXXXXXXXXXXXXXXXXX\n" +
                "That is a deep dive. In this specific model context, the dependencies " +
                "suggest a high coupling with the 'Control Unit' Block.";
    }
}