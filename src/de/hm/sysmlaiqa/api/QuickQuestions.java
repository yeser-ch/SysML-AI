package de.hm.sysmlaiqa.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Schnellfragen: die acht Testfragen des Fragenkatalogs (A-01 … D-02) als Vorlagen mit Platzhaltern in spitzen
 * Klammern, die der Nutzer ausfüllt. Kategorien:
 *   A Information Retrieval, B Structural Reasoning, C Semantic Reasoning, D Uncertainty.
 * Ist ein Element ausgewählt, setzt das Plugin seinen Namen in die passenden Platzhalter ein:
 * bei einer Anforderung in "<requirement ID…>", sonst in "<component>" bzw. "<component 1>".
 */
public final class QuickQuestions {

    public static final String REQ = "<requirement ID>";
    public static final String COMP = "<component>";
    public static final String COMP1 = "<component 1>";

    private QuickQuestions() { }

    public static class Question {
        public final String category;  // z. B. "A-01"
        public final String label;     // kurzer Button-Text
        public final String text;      // vollständige Frage

        Question(String category, String label, String text) {
            this.category = category;
            this.label = label;
            this.text = text;
        }
    }

    /**
     * @param idx         Modell-Index (nur um zu erkennen, ob das ausgewählte Element eine Anforderung ist)
     * @param focusId     ID des ausgewählten Elements oder null
     * @param focusName   Name des ausgewählten Elements oder null
     * @param focusType   Typ des ausgewählten Elements (z. B. "Requirement", "Block") oder null
     * @param seed        nicht mehr verwendet (Vorlagen sind fest)
     * @param elementMode true = ausgewähltes Element einsetzen, false = alle Platzhalter frei
     */
    public static List<Question> generate(ModelIndex idx, String focusId, String focusName, String focusType,
                                          long seed, boolean elementMode) {
        List<Question> all = catalogue();
        if (!elementMode || focusName == null || focusName.isBlank()) return all;
        ModelIndex.Elem e = idx == null ? null : findFocus(idx, focusId, focusName);
        boolean isRequirement = (e != null && e.isRequirement())
                || (focusType != null && focusType.contains("Requirement"));
        String x = "\"" + focusName.trim() + "\"";
        List<Question> out = new ArrayList<>();
        for (Question q : all) {
            String text = isRequirement
                    ? q.text.replaceFirst("<requirement ID[^>]*>", java.util.regex.Matcher.quoteReplacement(x))
                    : q.text.replace(COMP, x).replace(COMP1, x);
            String label = isRequirement
                    ? q.label.replaceFirst("<requirement ID[^>]*>", java.util.regex.Matcher.quoteReplacement(x))
                    : q.label.replace(COMP, x).replace(COMP1, x);
            out.add(new Question(q.category, label, text));
        }
        return out;
    }

    public static List<Question> generate(ModelIndex idx, String focusId, long seed, boolean elementMode) {
        return generate(idx, focusId, null, null, seed, elementMode);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Fragenkatalog A-01 … D-02
    // ═══════════════════════════════════════════════════════════════════════

    private static List<Question> catalogue() {
        List<Question> q = new ArrayList<>();
        // A: Information Retrieval
        q.add(new Question("A-01", "Component satisfying " + REQ,
                "Which component satisfies the requirement " + REQ + "?"));
        q.add(new Question("A-02", "Parts of " + COMP,
                "Which subcomponents (parts) does " + COMP + " consist of?"));
        // B: Structural Reasoning
        q.add(new Question("B-01", "Connections of " + COMP,
                "Which components is " + COMP + " directly connected to, and which only indirectly via other components?"));
        q.add(new Question("B-02", "Path of a data flow / item",
                "How does <data flow / item> get from " + COMP1 + " to <component 2, with at least one component in between>? "
                        + "Name all components, ports and interfaces along the way."));
        // C: Semantic Reasoning
        q.add(new Question("C-01", "Does satisfy prove fulfilment?",
                "Does the satisfy relationship between " + COMP + " and " + REQ + " prove that the requirement is fulfilled? "
                        + "Explain your answer."));
        q.add(new Question("C-02", "Direct data transfer possible?",
                "Can " + COMP1 + " send data directly to <component 2, not directly connected to component 1>? "
                        + "Justify your answer using the model."));
        // D: Uncertainty
        q.add(new Question("D-01", "Is the requirement fulfilled?",
                "Does the system actually fulfil the requirement <requirement ID without test results in the model> "
                        + "in its current state?"));
        q.add(new Question("D-02", "Element not in the model",
                "What type and which properties does <element that is not in the model, e.g. a name only mentioned in a text> have?"));
        return q;
    }

    /** Fokus-Element im Export: zuerst über die ID, sonst über den (eindeutigen) Namen. */
    public static ModelIndex.Elem findFocus(ModelIndex idx, String focusId, String focusName) {
        ModelIndex.Elem e = idx.get(focusId);
        if (e != null || focusName == null || focusName.isBlank()) return e;
        List<ModelIndex.Elem> byName = idx.elements.values().stream()
                .filter(x -> focusName.trim().equals(x.name.trim())).collect(Collectors.toList());
        return byName.isEmpty() ? null : byName.stream().max(Comparator.comparingInt(ModelIndex.Elem::degree)).get();
    }
}
