package de.hm.sysmlaiqa.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Schnellfragen als allgemeine Vorlagen mit Platzhaltern (z. B. "<add requirement>"), die der Nutzer ausfüllt.
 * Aufbau nach den vier Kategorien der Aufgabenstellung (Beispiele aus der Projektbeschreibung):
 *   A Information Retrieval  – "Welche Komponente erfüllt die Anforderung XYZ?"
 *   B Structural Reasoning   – "Welche Komponenten stehen indirekt mit Bauteil XYZ in Verbindung?"
 *   C Semantic Reasoning     – "Beweist eine satisfy-Beziehung, dass eine Anforderung tatsächlich erfüllt ist?"
 *   D Uncertainty            – "Reicht die Batterie für vier Stunden Betriebszeit?"
 * Zwei Sätze: Fragen zum ganzen Modell (nur Platzhalter) und Fragen zu einem Element (ausgewähltes Element eingesetzt).
 */
public final class QuickQuestions {

    public static final String REQ = "<add requirement>";
    public static final String ELEM = "<add element>";
    public static final String ELEM2 = "<add second element>";

    private QuickQuestions() { }

    public static class Question {
        public final String category;  // z. B. "A · Retrieval"
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
     * @param elementMode true = Fragen zum Element, false = Fragen zum ganzen Modell
     */
    public static List<Question> generate(ModelIndex idx, String focusId, String focusName, String focusType,
                                          long seed, boolean elementMode) {
        if (!elementMode || focusName == null || focusName.isBlank()) return modelQuestions();
        ModelIndex.Elem e = idx == null ? null : findFocus(idx, focusId, focusName);
        boolean isRequirement = (e != null && e.isRequirement())
                || (focusType != null && focusType.contains("Requirement"));
        String x = "\"" + focusName.trim() + "\"";
        return isRequirement ? requirementQuestions(x) : elementQuestions(x);
    }

    public static List<Question> generate(ModelIndex idx, String focusId, long seed, boolean elementMode) {
        return generate(idx, focusId, null, null, seed, elementMode);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Ganzes Modell – alle Platzhalter frei
    // ═══════════════════════════════════════════════════════════════════════

    private static List<Question> modelQuestions() {
        List<Question> q = new ArrayList<>();
        q.add(new Question("A · Retrieval", "Who satisfies a requirement?",
                "Which component satisfies the requirement " + REQ + "? Name each element and the relationship type."));
        q.add(new Question("A · Retrieval", "Unsatisfied requirements",
                "Which requirements in the model are not satisfied by any element? List them by name."));
        q.add(new Question("B · Structure", "Direct and indirect connections",
                "Which components are directly or indirectly connected to " + ELEM + "? Show the path for each."));
        q.add(new Question("B · Structure", "Path between two elements",
                "How is " + ELEM + " connected to " + ELEM2 + "? Show the complete chain of relationships, element by element, "
                        + "with the relationship type at each step."));
        q.add(new Question("C · Semantics", "Does satisfy prove fulfilment?",
                "Does the satisfy relationship of " + REQ + " prove that the requirement is actually fulfilled? "
                        + "Explain using the model (verify relationships, test cases, constraints, values)."));
        q.add(new Question("C · Semantics", "Meaning of a relationship",
                "What does the relationship between " + ELEM + " and " + ELEM2 + " mean technically for the system, "
                        + "and what does it NOT guarantee?"));
        q.add(new Question("D · Uncertainty", "Can an element fulfil a requirement?",
                "Can " + ELEM + " fulfil the requirement " + REQ + "? If the model does not contain enough information "
                        + "to decide this, say so and name exactly what is missing."));
        q.add(new Question("D · Uncertainty", "Is a requirement decidable?",
                "Is there enough information in the model to decide whether " + REQ + " is fulfilled? "
                        + "If not, name exactly which values or relationships are missing."));
        return q;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Ein Element (kein Requirement) – Element eingesetzt
    // ═══════════════════════════════════════════════════════════════════════

    private static List<Question> elementQuestions(String x) {
        List<Question> q = new ArrayList<>();
        q.add(new Question("A · Retrieval", "Requirements of " + x,
                "Which requirements does " + x + " satisfy, and through which relationships?"));
        q.add(new Question("A · Retrieval", "Parts, ports, values of " + x,
                "Which parts, ports and value properties does " + x + " have? List each with its type and, if given, its value."));
        q.add(new Question("B · Structure", "Connections of " + x,
                "Which components are directly or indirectly connected to " + x + "? Show the path for each."));
        q.add(new Question("B · Structure", "Path from " + x + " to another element",
                "How is " + x + " connected to " + ELEM + "? Show the complete chain of relationships, element by element, "
                        + "with the relationship type at each step."));
        q.add(new Question("C · Semantics", "Does satisfy prove fulfilment?",
                "Do the satisfy relationships of " + x + " prove that the requirements it satisfies are actually fulfilled? "
                        + "Explain using the model (verify relationships, test cases, constraints, values)."));
        q.add(new Question("C · Semantics", "Meaning of a relationship of " + x,
                "What does the relationship between " + x + " and " + ELEM + " mean technically for the system, "
                        + "and what does it NOT guarantee?"));
        q.add(new Question("D · Uncertainty", "Can " + x + " fulfil a requirement?",
                "Can " + x + " fulfil the requirement " + REQ + "? If the model does not contain enough information "
                        + "to decide this, say so and name exactly what is missing."));
        q.add(new Question("D · Uncertainty", "What is missing about " + x + "?",
                "Which information about " + x + " is missing in the model to judge whether it works correctly in the system "
                        + "(e.g. values, interfaces, behaviour, verification)?"));
        return q;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Ein Requirement – Requirement eingesetzt
    // ═══════════════════════════════════════════════════════════════════════

    private static List<Question> requirementQuestions(String r) {
        List<Question> q = new ArrayList<>();
        q.add(new Question("A · Retrieval", "Who satisfies " + r + "?",
                "Which component satisfies the requirement " + r + "? Name each element and the relationship type."));
        q.add(new Question("A · Retrieval", "Verify, refine, derive of " + r,
                "Which elements verify or refine " + r + ", and which requirements are derived from it or contain it?"));
        q.add(new Question("B · Structure", "Connections of " + r,
                "Which components are directly or indirectly connected to " + r + "? Show the path for each."));
        q.add(new Question("B · Structure", "Path from " + r + " to an element",
                "How is " + r + " connected to " + ELEM + "? Show the complete chain of relationships, element by element, "
                        + "with the relationship type at each step."));
        q.add(new Question("C · Semantics", "Does satisfy prove " + r + "?",
                "Does the satisfy relationship of " + r + " prove that the requirement is actually fulfilled? "
                        + "Explain using the model (verify relationships, test cases, constraints, values)."));
        q.add(new Question("C · Semantics", "Meaning of the relationships of " + r,
                "What do the relationships of " + r + " (satisfy, verify, refine, derive) mean technically, "
                        + "and what do they NOT guarantee?"));
        q.add(new Question("D · Uncertainty", "Is " + r + " decidable?",
                "Is there enough information in the model to decide whether " + r + " is fulfilled? "
                        + "If not, name exactly which values or relationships are missing."));
        q.add(new Question("D · Uncertainty", "Can an element fulfil " + r + "?",
                "Can " + ELEM + " fulfil the requirement " + r + "? If the model does not contain enough information "
                        + "to decide this, say so and name exactly what is missing."));
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
