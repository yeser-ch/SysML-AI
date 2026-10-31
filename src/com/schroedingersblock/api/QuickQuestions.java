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
 * Ist ein Element ausgewählt, drehen sich die Fragen um dieses Element.
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

    /**
     * @param idx        Modell-Index (aus dem Export)
     * @param focusId    ID des ausgewählten Elements oder null
     * @param seed       Zufallswert ("Other elements" ändert ihn)
     */
    public static List<Question> generate(ModelIndex idx, String focusId, long seed) {
        Random rnd = new Random(seed);
        ModelIndex.Elem focus = idx.get(focusId);

        // Anforderung R: ausgewählte Anforderung, sonst eine mit Beziehungen (bevorzugt satisfy)
        List<ModelIndex.Elem> reqs = idx.elements.values().stream()
                .filter(ModelIndex.Elem::isRequirement).filter(ModelIndex.Elem::hasName).collect(Collectors.toList());
        ModelIndex.Elem r;
        List<ModelIndex.Elem> reqsNearFocus = new ArrayList<>();
        if (focus != null && !focus.isRequirement()) {
            for (ModelIndex.Elem x : idx.neighbours(focus)) if (x.isRequirement() && x.hasName()) reqsNearFocus.add(x);
            if (reqsNearFocus.isEmpty()) {
                for (ModelIndex.Elem x : idx.twoHops(focus)) if (x.isRequirement() && x.hasName()) reqsNearFocus.add(x);
            }
        }
        if (focus != null && focus.isRequirement()) {
            r = focus;
        } else if (!reqsNearFocus.isEmpty()) {
            r = pick(reqsNearFocus, rnd); // Anforderung, die mit dem ausgewählten Element zusammenhängt
        } else {
            List<ModelIndex.Elem> linked = reqs.stream().filter(e -> e.incoming.stream().anyMatch(x -> isSatisfy(x.type)))
                    .collect(Collectors.toList());
            if (linked.isEmpty()) linked = reqs.stream().filter(e -> e.degree() > 0).collect(Collectors.toList());
            if (linked.isEmpty()) linked = reqs;
            r = pick(linked, rnd);
        }

        // Element E: ausgewähltes Nicht-Anforderungs-Element, sonst ein gut vernetztes Strukturelement
        ModelIndex.Elem e;
        List<ModelIndex.Elem> linkedToR = r == null ? new ArrayList<>() : idx.neighbours(r).stream()
                .filter(ModelIndex.Elem::hasName).filter(x -> !x.isRequirement()).distinct().collect(Collectors.toList());
        if (focus != null && !focus.isRequirement() && focus.hasName()) {
            e = focus;
        } else if (focus != null && focus.isRequirement() && !linkedToR.isEmpty()) {
            e = pick(linkedToR, rnd); // ausgewählte Anforderung: Element, das mit ihr verknüpft ist
        } else {
            List<ModelIndex.Elem> candidates = idx.elements.values().stream()
                    .filter(ModelIndex.Elem::hasName).filter(x -> !x.isRequirement()).filter(x -> x.degree() > 0)
                    .sorted(Comparator.comparingInt(ModelIndex.Elem::degree).reversed())
                    .collect(Collectors.toList());
            List<ModelIndex.Elem> structural = candidates.stream().filter(ModelIndex.Elem::isStructural).collect(Collectors.toList());
            List<ModelIndex.Elem> pool = structural.isEmpty() ? candidates : structural;
            e = pick(pool.subList(0, Math.min(pool.size(), 12)), rnd);
        }

        // B: Element 2 Beziehungen von E entfernt (indirekte Verbindung)
        ModelIndex.Elem b = e == null ? null : pick(idx.twoHops(e), rnd);
        // Konkrete Beziehung von E für die Semantik-Frage
        ModelIndex.Relation rel = null;
        ModelIndex.Elem relOther = null;
        if (e != null) {
            List<ModelIndex.Relation> rels = new ArrayList<>(e.outgoing);
            rels.addAll(e.incoming);
            rels.removeIf(x -> idx.get(x.sourceId) == null || idx.get(x.targetId) == null
                    || !idx.get(x.sourceId).hasName() || !idx.get(x.targetId).hasName());
            rel = pick(rels, rnd);
            if (rel != null) relOther = idx.get(rel.sourceId == null || rel.sourceId.equals(e.id) ? rel.targetId : rel.sourceId);
        }

        String R = r != null ? q(r.name) : "<requirement>";
        String E = e != null ? q(e.name) : "<element>";
        String B = b != null ? q(b.name) : "<element B>";
        // Für "Erfüllt X die Anforderung R?": bevorzugt ein Element, das wirklich mit R verknüpft ist
        ModelIndex.Elem er = e;
        if (r != null && e != null && !linkedToR.contains(e) && !linkedToR.isEmpty()) er = pick(linkedToR, rnd);
        String ER = er != null ? q(er.name) : E;

        List<Question> out = new ArrayList<>();
        // A · Information Retrieval
        out.add(new Question("A · Retrieval", "Who satisfies " + R + "?",
                "Which model elements satisfy the requirement " + R + "? Name each element and the relationship type."));
        out.add(new Question("A · Retrieval", "Parts, ports, values of " + E,
                "Which parts, ports and value properties does " + E + " own? List each with its type and, if given, its value."));
        // B · Structural Reasoning
        out.add(new Question("B · Structure", "Path " + E + " ↔ " + B,
                "Is " + E + " connected to " + B + ", directly or indirectly? Show the complete chain of relationships "
                        + "between them, element by element."));
        out.add(new Question("B · Structure", "Impact of changing " + E,
                "If " + E + " is changed, which other elements and requirements are affected? "
                        + "List direct and indirect dependencies and give the path for each."));
        // C · Semantic Reasoning
        if (rel != null && relOther != null) {
            ModelIndex.Elem src = idx.get(rel.sourceId), tgt = idx.get(rel.targetId);
            String relName = rel.type.isBlank() ? "relationship" : rel.type;
            out.add(new Question("C · Semantics", relName + ": " + shorten(src.name) + " → " + shorten(tgt.name),
                    "In this model there is a \"" + relName + "\" relationship from " + q(src.name) + " to " + q(tgt.name)
                            + ". What does this relationship mean technically for the system, and what does it NOT guarantee?"));
        } else {
            out.add(new Question("C · Semantics", "Meaning of " + E + "'s relationships",
                    "What do the relationships of " + E + " mean technically for the system, and what do they NOT guarantee?"));
        }
        out.add(new Question("C · Semantics", "Does satisfy prove " + R + "?",
                "Does the satisfy relationship of " + R + " prove that the requirement is actually fulfilled? "
                        + "Justify using the model (verify relationships, test cases, constraints, values)."));
        // D · Uncertainty / Missing Information
        out.add(new Question("D · Uncertainty", "Is " + R + " fulfilled?",
                "Can it be determined from the model alone whether " + R + " is fulfilled? "
                        + "If not, name exactly which values or relationships are missing."));
        out.add(new Question("D · Uncertainty", "Does " + ER + " meet " + R + "?",
                "Based only on the model: does " + ER + " meet the requirement " + R + "? "
                        + "Answer \"cannot be determined\" if the model does not contain the necessary information, and say what is missing."));
        return out;
    }

    static boolean isSatisfy(String type) {
        return type != null && type.toLowerCase().contains("satisf");
    }

    private static <T> T pick(List<T> list, Random rnd) {
        return list == null || list.isEmpty() ? null : list.get(rnd.nextInt(list.size()));
    }

    private static String q(String name) {
        return "\"" + name.trim() + "\"";
    }

    private static String shorten(String s) {
        s = s.trim();
        return s.length() <= 22 ? s : s.substring(0, 21) + "…";
    }
}
