package com.schroedingersblock.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest den JSON-Export (flache Elementliste) in eine kleine Struktur ein:
 * Elemente mit Name, Typ, Stereotypen und Beziehungen (ein- und ausgehend).
 * Wird genutzt, um Schnellfragen mit ECHTEN Elementen und Beziehungen aus eurem Modell zu erzeugen.
 */
public class ModelIndex {

    public static class Relation {
        public final String type;       // z. B. "Satisfy", "Abstraction", "Generalization", "Item Flow"
        public final String sourceId;
        public final String targetId;

        Relation(String type, String sourceId, String targetId) {
            this.type = type;
            this.sourceId = sourceId;
            this.targetId = targetId;
        }
    }

    public static class Elem {
        public final String id;
        public final String name;
        public final String type;
        public final String ownerId;
        public final List<String> stereotypes;
        public final List<Relation> outgoing = new ArrayList<>();
        public final List<Relation> incoming = new ArrayList<>();

        Elem(String id, String name, String type, String ownerId, List<String> stereotypes) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.ownerId = ownerId;
            this.stereotypes = stereotypes;
        }

        public boolean isRequirement() {
            return type.contains("Requirement") || stereotypes.stream().anyMatch(s -> s.contains("Requirement"));
        }

        public boolean hasName() {
            return name != null && !name.isBlank();
        }

        public int degree() {
            return outgoing.size() + incoming.size();
        }

        /** Strukturelement, über das sich Fragen lohnen (keine Pakete, Diagramme, Kanten …). */
        public boolean isStructural() {
            String t = type.replace(" ", "");
            return t.equals("Block") || t.contains("Port") || t.contains("Part") || t.equals("Activity")
                    || t.equals("ConstraintBlock") || t.equals("UseCase") || t.equals("Actor") || t.equals("StateMachine")
                    || t.equals("Class") || t.equals("Interface") || t.equals("InterfaceBlock");
        }
    }

    public final Map<String, Elem> elements = new LinkedHashMap<>();

    private static final Pattern ELEMENT_START = Pattern.compile("\\{\"id\":\"");
    private static final Pattern RELATION = Pattern.compile(
            "\\{\"relation_type\":\"((?:[^\"\\\\]|\\\\.)*)\",\"relation_stereotypes\":\\[([^\\]]*)\\],\"target_id\":\"([^\"]*)\"\\}");
    private static final Pattern ROUTING = Pattern.compile(
            "\"routing_details\":\\{\"source_id\":\"([^\"]*)\",\"target_id\":\"([^\"]*)\"\\}");

    public static ModelIndex parse(String json) {
        ModelIndex idx = new ModelIndex();
        if (json == null) return idx;
        List<Integer> starts = new ArrayList<>();
        Matcher m = ELEMENT_START.matcher(json);
        while (m.find()) starts.add(m.start());

        List<String[]> pendingRelations = new ArrayList<>(); // {type, source, target}
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = i + 1 < starts.size() ? starts.get(i + 1) : json.length();
            String seg = json.substring(from, to);
            String id = field(seg, "id");
            if (id.isEmpty()) continue;
            String name = field(seg, "name");
            String type = field(seg, "type");
            String owner = field(seg, "owner_id");
            List<String> stereo = new ArrayList<>();
            Matcher sm = Pattern.compile("\"stereotypes\":\\[([^\\]]*)\\]").matcher(seg);
            if (sm.find()) stereo = strings(sm.group(1));
            idx.elements.put(id, new Elem(id, name, type, owner, stereo));

            Matcher rm = RELATION.matcher(seg);
            while (rm.find()) {
                List<String> relSt = strings(rm.group(2));
                String relType = relSt.isEmpty() ? unescape(rm.group(1)) : relSt.get(0);
                pendingRelations.add(new String[]{relType, id, rm.group(3)});
            }
            Matcher route = ROUTING.matcher(seg);
            if (route.find() && !route.group(1).isEmpty() && !route.group(2).isEmpty()) {
                pendingRelations.add(new String[]{type, route.group(1), route.group(2)});
            }
        }
        for (String[] r : pendingRelations) {
            Elem s = idx.elements.get(r[1]);
            Elem t = idx.elements.get(r[2]);
            if (s == null || t == null || s == t) continue;
            Relation rel = new Relation(r[0], r[1], r[2]);
            s.outgoing.add(rel);
            t.incoming.add(rel);
        }
        return idx;
    }

    public Elem get(String id) {
        return id == null ? null : elements.get(id);
    }

    /** Alle direkt verbundenen Elemente (Beziehungen in beide Richtungen). */
    public List<Elem> neighbours(Elem e) {
        List<Elem> out = new ArrayList<>();
        for (Relation r : e.outgoing) { Elem t = get(r.targetId); if (t != null) out.add(t); }
        for (Relation r : e.incoming) { Elem s = get(r.sourceId); if (s != null) out.add(s); }
        return out;
    }

    /** Elemente genau 2 Beziehungen entfernt (nicht direkt verbunden) – für Fragen zu indirekten Verbindungen. */
    public List<Elem> twoHops(Elem e) {
        Map<String, Elem> direct = new HashMap<>();
        for (Elem n : neighbours(e)) direct.put(n.id, n);
        Map<String, Elem> result = new LinkedHashMap<>();
        for (Elem n : direct.values()) {
            for (Elem nn : neighbours(n)) {
                if (nn != e && !direct.containsKey(nn.id) && nn.hasName()) result.put(nn.id, nn);
            }
        }
        return new ArrayList<>(result.values());
    }

    private static String field(String seg, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\":\"").matcher(seg);
        if (!m.find()) return "";
        return GeminiExplanationService.readJsonString(seg, m.end(), new int[1]);
    }

    private static List<String> strings(String arrayBody) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(arrayBody);
        while (m.find()) out.add(unescape(m.group(1)));
        return out;
    }

    private static String unescape(String s) {
        return GeminiExplanationService.readJsonString(s + "\"", 0, new int[1]);
    }
}
