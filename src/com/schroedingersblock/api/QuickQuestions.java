package com.schroedingersblock.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Erzeugt Schnellfragen mit ECHTEN Elementen und Beziehungen aus dem exportierten Modell,
 * passend zu den vier Untersuchungskategorien:
 *   A Information Retrieval · B Structural Reasoning · C Semantic Reasoning · D Uncertainty
 *
 * Zwei Fragensätze:
 * - Modell-Fragen ("Ask AI about the model"): Gesamtbild – Abdeckung, lange Pfade, Auswirkungen über mehrere Ebenen.
 * - Element-Fragen ("Ask AI about this element"): genau dieses Element – Eigenschaften, Nachbarn, verknüpfte Anforderungen.
 * "Other elements" würfelt neue Elemente aus.
 */
public final class QuickQuestions {

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

    /** Kompatibilität: Element-Fragen, wenn ein Element angegeben ist, sonst Modell-Fragen. */
    public static List<Question> generate(ModelIndex idx, String focusId, long seed) {
        return generate(idx, focusId, seed, focusId != null);
    }

    /**
     * @param idx         Modell-Index (aus dem Export)
     * @param focusId     ID des ausgewählten Elements oder null
     * @param seed        Zufallswert ("Other elements" ändert ihn)
     * @param elementMode true = Fragen zum Element, false = Fragen zum ganzen Modell
     */
    public static List<Question> generate(ModelIndex idx, String focusId, long seed, boolean elementMode) {
        return generate(idx, focusId, null, null, seed, elementMode);
    }

    /**
     * Wie oben; focusName/focusType werden genutzt, falls das Element im Export nicht per ID zu finden ist
     * (dann Suche über den Namen, sonst Element-Fragen ohne Beziehungsdaten).
     */
    public static List<Question> generate(ModelIndex idx, String focusId, String focusName, String focusType,
                                          long seed, boolean elementMode) {
        ModelIndex.Elem focus = findFocus(idx, focusId, focusName);
        if (elementMode) {
            if (focus == null && focusName != null && !focusName.isBlank()) {
                focus = new ModelIndex.Elem(focusId == null ? "" : focusId, focusName,
                        focusType == null ? "" : focusType, "", new ArrayList<>());
            }
            if (focus != null) return elementQuestions(idx, focus, new Random(seed));
        }
        return modelQuestions(idx, new Random(seed));
    }

    /** Fokus-Element im Export: zuerst über die ID, sonst über den (eindeutigen) Namen. */
    public static ModelIndex.Elem findFocus(ModelIndex idx, String focusId, String focusName) {
        ModelIndex.Elem e = idx.get(focusId);
        if (e != null || focusName == null || focusName.isBlank()) return e;
        List<ModelIndex.Elem> byName = idx.elements.values().stream()
                .filter(x -> focusName.trim().equals(x.name.trim())).collect(Collectors.toList());
        return byName.isEmpty() ? null : byName.stream().max(Comparator.comparingInt(ModelIndex.Elem::degree)).get();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Fragen zum GANZEN Modell
    // ═══════════════════════════════════════════════════════════════════════

    private static List<Question> modelQuestions(ModelIndex idx, Random rnd) {
        List<ModelIndex.Elem> reqs = requirements(idx);
        List<ModelIndex.Elem> linkedReqs = reqs.stream().filter(e -> e.incoming.stream().anyMatch(x -> isSatisfy(x.type)))
                .collect(Collectors.toList());
        ModelIndex.Elem r = pick(!linkedReqs.isEmpty() ? linkedReqs : reqs, rnd);

        // Gut vernetztes Strukturelement ("Knotenpunkt")
        List<ModelIndex.Elem> hubs = idx.elements.values().stream()
                .filter(ModelIndex.Elem::hasName).filter(x -> !x.isRequirement()).filter(x -> x.degree() > 0)
                .sorted(Comparator.comparingInt(ModelIndex.Elem::degree).reversed()).collect(Collectors.toList());
        List<ModelIndex.Elem> structural = hubs.stream().filter(ModelIndex.Elem::isStructural).collect(Collectors.toList());
        List<ModelIndex.Elem> pool = structural.isEmpty() ? hubs : structural;
        ModelIndex.Elem hub = pick(pool.subList(0, Math.min(pool.size(), 12)), rnd);

        // Weit entfernte Elemente für lange Pfade (3 Schritte, sonst 2)
        ModelIndex.Elem start = pick(pool.subList(0, Math.min(pool.size(), 25)), rnd);
        ModelIndex.Elem far = null;
        if (start != null) {
            far = pick(idx.atDistance(start, 3), rnd);
            if (far == null) far = pick(idx.atDistance(start, 2), rnd);
        }
        ModelIndex.Relation rel = randomRelation(idx, null, rnd);

        String R = name(r, "<requirement>");
        String H = name(hub, "<element>");
        String S = name(start, "<element A>");
        String F = name(far, "<element B>");

        List<Question> out = new ArrayList<>();
        out.add(new Question("A · Retrieval", "Who satisfies " + R + "?",
                "Which model elements satisfy the requirement " + R + "? Name each element and the relationship type."));
        out.add(new Question("A · Retrieval", "Requirements without satisfy",
                "Which requirements in this model are not satisfied by any element? List them by name."));
        out.add(new Question("B · Structure", "Path " + S + " ↔ " + F,
                "How is " + S + " connected to " + F + "? Show the complete chain of relationships between them, "
                        + "element by element, including the relationship type at each step."));
        out.add(new Question("B · Structure", "Impact of changing " + H,
                "If " + H + " is changed, which other elements and requirements in the model are affected, "
                        + "directly and indirectly? Give the path for each."));
        out.add(relationQuestion(idx, rel, H));
        out.add(new Question("C · Semantics", "Does satisfy prove " + R + "?",
                "Does the satisfy relationship of " + R + " prove that the requirement is actually fulfilled? "
                        + "Justify using the model (verify relationships, test cases, constraints, values)."));
        out.add(new Question("D · Uncertainty", "Is " + R + " fulfilled?",
                "Can it be determined from the model alone whether " + R + " is fulfilled? "
                        + "If not, name exactly which values or relationships are missing."));
        out.add(new Question("D · Uncertainty", "Which requirements can't be checked?",
                "For which requirements in this model is it impossible to decide from the model whether they are fulfilled? "
                        + "For each, name the missing information."));
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Fragen zu EINEM Element
    // ═══════════════════════════════════════════════════════════════════════

    private static List<Question> elementQuestions(ModelIndex idx, ModelIndex.Elem f, Random rnd) {
        String F = name(f, "\"the selected element\"");
        List<ModelIndex.Elem> direct = idx.neighbours(f).stream().filter(ModelIndex.Elem::hasName).distinct().collect(Collectors.toList());
        ModelIndex.Elem indirect = pick(idx.atDistance(f, 2), rnd);
        ModelIndex.Relation rel = randomRelation(idx, f, rnd);
        List<Question> out = new ArrayList<>();

        if (f.isRequirement()) {
            List<ModelIndex.Elem> linked = direct.stream().filter(x -> !x.isRequirement()).collect(Collectors.toList());
            ModelIndex.Elem x = pick(linked, rnd);
            String X = name(x, "<element>");
            out.add(new Question("A · Retrieval", "Who satisfies " + F + "?",
                    "Which elements satisfy, verify or refine the requirement " + F + "? Name each element and the relationship type."));
            out.add(new Question("A · Retrieval", "Text and context of " + F,
                    "What exactly does the requirement " + F + " demand (its text), and which requirements contain it or are derived from it?"));
            out.add(new Question("B · Structure", "Direct links of " + F,
                    "Which model elements are directly linked to " + F + "? List each with the relationship type and direction."));
            out.add(new Question("B · Structure", "Indirect links of " + F,
                    "Which elements are linked to " + F + " only indirectly (via another element)? Show the path for each."
                            + (indirect != null ? " For example: how is " + q(indirect.name) + " related to it?" : "")));
            out.add(relationQuestion(idx, rel, F));
            out.add(new Question("C · Semantics", "Does satisfy prove " + F + "?",
                    "Does the satisfy relationship of " + F + " prove that the requirement is actually fulfilled? "
                            + "Justify using the model (verify relationships, test cases, constraints, values)."));
            out.add(new Question("D · Uncertainty", "Is " + F + " fulfilled?",
                    "Can it be determined from the model alone whether " + F + " is fulfilled? "
                            + "If not, name exactly which values or relationships are missing."));
            out.add(new Question("D · Uncertainty", "Does " + X + " meet " + F + "?",
                    "Based only on the model: does " + X + " meet the requirement " + F + "? "
                            + "Answer \"cannot be determined\" if the model does not contain the necessary information, and say what is missing."));
            return out;
        }

        // Nicht-Anforderung (Block, Port, Aktivität …)
        List<ModelIndex.Elem> reqs = direct.stream().filter(ModelIndex.Elem::isRequirement).collect(Collectors.toList());
        if (reqs.isEmpty()) reqs = idx.atDistance(f, 2).stream().filter(ModelIndex.Elem::isRequirement).collect(Collectors.toList());
        if (reqs.isEmpty()) reqs = requirements(idx); // kein verknüpftes: irgendeine Anforderung (Antwort sollte "nicht bestimmbar" sein)
        ModelIndex.Elem r = pick(reqs, rnd);
        String R = name(r, "<requirement>");

        out.add(new Question("A · Retrieval", "Properties of " + F,
                "What type is " + F + ", which element owns it, and which parts, ports and value properties does it have "
                        + "(with types and values, if given)?"));
        out.add(new Question("A · Retrieval", "Requirements of " + F,
                "Which requirements are linked to " + F + ", and through which relationship types (satisfy, verify, refine, allocate …)?"));
        out.add(new Question("B · Structure", "Direct neighbours of " + F,
                "Which elements are directly connected to " + F + "? List each with the relationship or connector type and the direction."));
        out.add(new Question("B · Structure", "Indirect links of " + F,
                "Which elements are connected to " + F + " only indirectly, via one intermediate element? Show the path for each."
                        + (indirect != null ? " For example: how is " + q(indirect.name) + " related to " + F + "?" : "")));
        out.add(relationQuestion(idx, rel, F));
        out.add(new Question("C · Semantics", "Role of " + F + " in the system",
                "Based only on its relationships in the model: what role does " + F + " play in the system? "
                        + "Separate what the model states explicitly from what you infer."));
        out.add(new Question("D · Uncertainty", "Does " + F + " meet " + R + "?",
                "Based only on the model: does " + F + " meet the requirement " + R + "? "
                        + "Answer \"cannot be determined\" if the model does not contain the necessary information, and say what is missing."));
        out.add(new Question("D · Uncertainty", "What's missing about " + F + "?",
                "Which information about " + F + " is missing in the model to judge whether it works correctly in the system "
                        + "(e.g. values, interfaces, behaviour, verification)?"));
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Hilfsfunktionen
    // ═══════════════════════════════════════════════════════════════════════

    private static Question relationQuestion(ModelIndex idx, ModelIndex.Relation rel, String fallbackName) {
        if (rel != null) {
            ModelIndex.Elem src = idx.get(rel.sourceId), tgt = idx.get(rel.targetId);
            String relName = rel.type.isBlank() ? "relationship" : rel.type;
            return new Question("C · Semantics", relName + ": " + shorten(src.name) + " → " + shorten(tgt.name),
                    "In this model there is a \"" + relName + "\" relationship from " + q(src.name) + " to " + q(tgt.name)
                            + ". What does this relationship mean technically for the system, and what does it NOT guarantee?");
        }
        return new Question("C · Semantics", "Meaning of " + fallbackName + "'s relationships",
                "What do the relationships of " + fallbackName + " mean technically for the system, and what do they NOT guarantee?");
    }

    /** Zufällige benannte Beziehung – von/zu "around", oder irgendwo im Modell. */
    private static ModelIndex.Relation randomRelation(ModelIndex idx, ModelIndex.Elem around, Random rnd) {
        List<ModelIndex.Relation> rels = new ArrayList<>();
        if (around != null) {
            rels.addAll(around.outgoing);
            rels.addAll(around.incoming);
        } else {
            for (ModelIndex.Elem e : idx.elements.values()) rels.addAll(e.outgoing);
        }
        rels.removeIf(x -> idx.get(x.sourceId) == null || idx.get(x.targetId) == null
                || !idx.get(x.sourceId).hasName() || !idx.get(x.targetId).hasName());
        // Bevorzugt aussagekräftige Beziehungen (keine reinen Besitz-/Generalisierungsbeziehungen)
        List<ModelIndex.Relation> meaningful = rels.stream()
                .filter(x -> !x.type.equalsIgnoreCase("Generalization")).collect(Collectors.toList());
        return pick(meaningful.isEmpty() ? rels : meaningful, rnd);
    }

    private static List<ModelIndex.Elem> requirements(ModelIndex idx) {
        return idx.elements.values().stream().filter(ModelIndex.Elem::isRequirement).filter(ModelIndex.Elem::hasName)
                .collect(Collectors.toList());
    }

    static boolean isSatisfy(String type) {
        return type != null && type.toLowerCase().contains("satisf");
    }

    private static <T> T pick(List<T> list, Random rnd) {
        return list == null || list.isEmpty() ? null : list.get(rnd.nextInt(list.size()));
    }

    private static String name(ModelIndex.Elem e, String fallback) {
        return e == null || !e.hasName() ? fallback : q(e.name);
    }

    private static String q(String name) {
        return "\"" + name.trim() + "\"";
    }

    private static String shorten(String s) {
        s = s.trim();
        return s.length() <= 22 ? s : s.substring(0, 21) + "…";
    }
}
