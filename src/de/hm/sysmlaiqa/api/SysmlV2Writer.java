package de.hm.sysmlaiqa.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Übersetzt den SysML-v1-Export (JSON, siehe docs/EXPORTFORMATE.md) in die textuelle Notation von SysML v2 (FR7).
 *
 * Wichtig für die Auswertung: Es ist KEIN natives SysML-v2-Modell, sondern dasselbe v1-Modell in v2-Schreibweise.
 * Dadurch lässt sich der Einfluss der Notation isoliert untersuchen (gleiche Inhalte, andere Darstellung).
 *
 * Regeln (vollständige Tabelle in docs/EXPORTFORMATE.md):
 * - Jedes Element bekommt seine CATIA-ID als Kurzname: {@code part def <_19_0_abc> Battery}. So bleibt der
 *   ID-Check ("Cite element IDs") auch für v2 möglich.
 * - Besitzstruktur wie im Modell (owner_id): Pakete → package, Blöcke → part def mit Ports/Properties im Rumpf.
 * - Beziehungen stehen direkt hinter dem Element, von dem sie ausgehen (satisfy, allocate, dependency …).
 * - Was es in v2 nicht direkt gibt (z. B. Lifelines), wird als Kommentar mit v1-Typ ausgegeben – es geht nichts verloren.
 */
public final class SysmlV2Writer {

    private static final Pattern BASIC_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "about", "abstract", "accept", "action", "actor", "after", "alias", "all", "allocate", "allocation",
            "analysis", "and", "as", "assert", "assign", "assume", "at", "attribute", "bind", "binding", "by", "calc",
            "case", "comment", "concern", "connect", "connection", "constraint", "crosses", "decide", "def", "default",
            "defined", "dependency", "derived", "do", "doc", "else", "end", "entry", "enum", "event", "exhibit", "exit",
            "expose", "false", "filter", "first", "flow", "for", "fork", "frame", "from", "hastype", "if", "implies",
            "import", "in", "include", "individual", "inout", "interface", "istype", "item", "join", "language",
            "library", "locale", "loop", "merge", "message", "meta", "metadata", "nonunique", "not", "null", "objective",
            "occurrence", "of", "or", "ordered", "out", "package", "parallel", "part", "perform", "port", "private",
            "protected", "public", "redefines", "ref", "references", "render", "rendering", "rep", "require",
            "requirement", "return", "satisfy", "send", "snapshot", "specializes", "stakeholder", "standard", "state",
            "subject", "subsets", "succession", "terminate", "then", "timeslice", "to", "transition", "true", "until",
            "use", "variant", "variation", "verification", "verify", "via", "view", "viewpoint", "when", "while", "xor"));

    /** Stereotypen, die das v2-Schlüsselwort schon ausdrückt (werden nicht zusätzlich als #Metadaten genannt). */
    private static final Set<String> IMPLIED_STEREOTYPES = new HashSet<>(Arrays.asList(
            "Block", "Requirement", "ValueType", "ConstraintBlock", "ConstraintProperty", "PartProperty",
            "ValueProperty", "ReferenceProperty", "FlowPort", "ProxyPort", "FullPort", "FlowProperty", "ItemFlow",
            "BindingConnector", "TestCase", "View", "Viewpoint", "Rationale", "Problem", "InterfaceBlock",
            "Satisfy", "Verify", "Refine", "DeriveReqt", "Trace", "Copy", "Allocate", "Activity", "StateMachine",
            "UseCase", "Actor", "Model", "Package", "Interaction"));

    private final Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
    private final Map<String, List<Map<String, Object>>> children = new HashMap<>();
    private final StringBuilder out = new StringBuilder();
    private final Set<String> written = new HashSet<>();
    private int stats;

    private SysmlV2Writer() { }

    /** @return SysML-v2-Text; leer, wenn der Export keine Elemente enthält. */
    public static String convert(String json, String header) {
        SysmlV2Writer w = new SysmlV2Writer();
        return w.run(json, header);
    }

    private String run(String json, String header) {
        Map<String, Object> root = Json.obj(Json.parse(json));
        String project = Json.str(Json.obj(root.get("global_context")).get("project_name"));
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Object o : Json.arr(root.get("elements"))) {
            Map<String, Object> e = Json.obj(o);
            String id = Json.str(e.get("id"));
            if (id.isEmpty() || byId.containsKey(id)) continue; // doppelte Einträge nur einmal
            byId.put(id, e);
        }
        for (Map<String, Object> e : byId.values()) {
            String owner = Json.str(e.get("owner_id"));
            if (byId.containsKey(owner) && !owner.equals(Json.str(e.get("id")))) {
                children.computeIfAbsent(owner, k -> new ArrayList<>()).add(e);
            } else {
                roots.add(e);
            }
        }
        if (header != null && !header.isEmpty()) {
            for (String line : header.split("\n")) out.append("// ").append(line).append('\n');
            out.append('\n');
        }
        String pkg = project.isEmpty() ? "Model" : project;
        out.append("package ").append(name(pkg)).append(" {\n");
        for (Map<String, Object> e : roots) element(e, 1);
        out.append("}\n");
        return out.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Elemente
    // ═══════════════════════════════════════════════════════════════════════

    private void element(Map<String, Object> e, int depth) {
        String id = Json.str(e.get("id"));
        if (!written.add(id)) return;
        stats++;
        String t = type(e);
        String ind = indent(depth);

        switch (t) {
            case "Region":                       // Regionen sind in v2 kein eigenes Element: Inhalt direkt ausgeben
            case "InteractionOperand":
                body(e, depth);
                return;
            case "Trigger":                      // steht in der Transition (accept …)
                return;
            default:
                break;
        }

        String keyword = keyword(e, t);
        if (keyword == null) {                   // keine v2-Entsprechung: als Kommentar, damit nichts verloren geht
            out.append(ind).append("// v1 ").append(humanType(e)).append(' ').append(label(e))
                    .append(" [id:").append(id).append(']').append(relationsInline(e)).append('\n');
            body(e, depth);
            return;
        }

        // Beziehungsartige Elemente: eigene Syntax
        switch (t) {
            case "ControlFlow":
                out.append(ind).append("succession ").append(decl(e)).append("first ").append(ref(src(e)))
                        .append(" then ").append(ref(tgt(e))).append(";\n");
                return;
            case "ObjectFlow":
                out.append(ind).append("flow ").append(decl(e)).append("from ").append(ref(src(e)))
                        .append(" to ").append(ref(tgt(e))).append(";\n");
                return;
            case "ItemFlow": {
                Map<String, Object> d = Json.obj(e.get("item_flow_details"));
                String conveyed = Json.str(d.get("conveyed_id"));
                out.append(ind).append("flow ").append(decl(e));
                if (byId.containsKey(conveyed)) out.append("of ").append(ref(conveyed)).append(' ');
                out.append("from ").append(ref(src(e))).append(" to ").append(ref(tgt(e))).append(";\n");
                return;
            }
            case "Transition":
                transition(e, ind);
                return;
            case "Message":
                out.append(ind).append("message ").append(decl(e)).append("from ").append(ref(src(e)))
                        .append(" to ").append(ref(tgt(e))).append(";\n");
                return;
            case "Connector":
            case "BindingConnector":
                connector(e, t, ind);
                return;
            case "Usage":
            case "InterfaceRealization":
                out.append(ind).append("dependency ").append(decl(e)).append("from ").append(ref(src(e)))
                        .append(" to ").append(ref(tgt(e))).append("; // v1 ").append(humanType(e)).append('\n');
                return;
            case "Association":
            case "AssociationClass":
                association(e, ind);
                return;
            case "Rationale":
            case "Problem": {
                String text = firstNonEmpty(Json.str(e.get("documentation")), Json.str(e.get("name")));
                out.append(ind).append("comment ").append(shortName(id)).append(" /* ").append(t).append(": ")
                        .append(commentText(text)).append(" */\n");
                return;
            }
            default:
                break;
        }

        // Normale Deklaration: [#Metadaten] schlüsselwort <id> name [:> Super] [: Typ] [mult] [= Wert] { … }
        StringBuilder line = new StringBuilder(ind);
        line.append(metadata(e));
        line.append(keyword).append(' ').append(decl(e));
        List<String> supers = targets(e, "Generalization");
        if (!supers.isEmpty()) {
            line.append(":> ");
            for (int k = 0; k < supers.size(); k++) line.append(k > 0 ? ", " : "").append(ref(supers.get(k)));
            line.append(' ');
        }
        String typeId = Json.str(e.get("property_type_id"));
        String typeName = Json.str(e.get("property_type_name"));
        if (!typeId.isEmpty() || !typeName.isEmpty()) {
            line.append(": ").append(byId.containsKey(typeId) ? ref(typeId) : name(typeName.isEmpty() ? typeId : typeName)).append(' ');
        }
        line.append(multiplicity(e));
        String def = Json.str(e.get("default_value"));
        if (!def.isEmpty() && keyword.startsWith("attribute")) line.append("= ").append(literal(def)).append(' ');

        List<String> inner = innerLines(e);
        boolean hasChildren = children.containsKey(id);
        if (inner.isEmpty() && !hasChildren) {
            trimEnd(line);
            out.append(line).append(";\n");
        } else {
            trimEnd(line);
            out.append(line).append(" {\n");
            for (String l : inner) out.append(indent(depth + 1)).append(l).append('\n');
            body(e, depth);
            out.append(ind).append("}\n");
        }
        relations(e, ind);
    }

    private void body(Map<String, Object> e, int depth) {
        List<Map<String, Object>> kids = children.get(Json.str(e.get("id")));
        if (kids == null) return;
        boolean transparent = "Region".equals(type(e)) || "InteractionOperand".equals(type(e));
        for (Map<String, Object> k : kids) element(k, transparent ? depth : depth + 1);
    }

    /** Zeilen im Rumpf vor den Kind-Elementen: Dokumentation, Anforderungstext, Gleichung, Tagged Values. */
    private List<String> innerLines(Map<String, Object> e) {
        List<String> l = new ArrayList<>();
        String reqText = Json.str(e.get("requirement_text"));
        String doc = Json.str(e.get("documentation"));
        if (!reqText.isEmpty()) {
            l.add("doc /* " + commentText(reqText) + " */");
            if (!doc.isEmpty() && !doc.equals(reqText)) l.add("comment /* Documentation: " + commentText(doc) + " */");
        } else if (!doc.isEmpty()) {
            l.add("doc /* " + commentText(doc) + " */");
        }
        String eq = Json.str(e.get("constraint_equation"));
        if (!eq.isEmpty()) l.add("comment /* Constraint expression (from SysML v1): " + commentText(eq) + " */");
        String dir = Json.str(e.get("direction"));
        if (!dir.isEmpty() && type(e).contains("Port")) l.add("// direction: " + dir);
        if ("ActivityPartition".equals(type(e))) {
            String rep = Json.str(e.get("represents_block_id"));
            if (byId.containsKey(rep)) l.add("// represents " + ref(rep));
        }
        Map<String, Object> tags = Json.obj(e.get("tagged_values"));
        StringBuilder tv = new StringBuilder();
        for (Map.Entry<String, Object> en : tags.entrySet()) {
            String v = Json.str(en.getValue());
            if (v.isEmpty() || v.equals(reqText)) continue;
            if (tv.length() > 0) tv.append("; ");
            tv.append(en.getKey()).append(" = \"").append(v.replace("\"", "'")).append('"');
        }
        if (tv.length() > 0) l.add("// tagged values: " + tv);
        return l;
    }

    /** Ausgehende Beziehungen (outgoing_relations) als eigene Anweisungen direkt hinter dem Element. */
    private void relations(Map<String, Object> e, String ind) {
        String self = Json.str(e.get("id"));
        for (Object o : Json.arr(e.get("outgoing_relations"))) {
            Map<String, Object> r = Json.obj(o);
            String rt = Json.str(r.get("relation_type"));
            String target = Json.str(r.get("target_id"));
            if (target.isEmpty() || "Generalization".equalsIgnoreCase(rt)) continue; // Generalisierung steht als :>
            String kind = relationKind(rt, Json.arr(r.get("relation_stereotypes")));
            switch (kind) {
                case "satisfy":
                    out.append(ind).append("satisfy ").append(ref(target)).append(" by ").append(ref(self)).append(";\n");
                    break;
                case "allocate":
                    out.append(ind).append("allocate ").append(ref(self)).append(" to ").append(ref(target)).append(";\n");
                    break;
                default:
                    out.append(ind).append("dependency ").append(kind).append(" from ").append(ref(self))
                            .append(" to ").append(ref(target)).append(";\n");
            }
        }
    }

    /** Für Elemente ohne v2-Entsprechung: Beziehungen kurz im Kommentar. */
    private String relationsInline(Map<String, Object> e) {
        StringBuilder sb = new StringBuilder();
        String src = src(e), tgt = tgt(e);
        if (!src.isEmpty() || !tgt.isEmpty()) sb.append(" from ").append(ref(src)).append(" to ").append(ref(tgt));
        return sb.toString();
    }

    private static String relationKind(String relationType, List<Object> stereotypes) {
        String all = (relationType + " " + stereotypes).toLowerCase();
        if (all.contains("satisfy")) return "satisfy";
        if (all.contains("allocate")) return "allocate";
        if (all.contains("verify")) return "verifies";
        if (all.contains("refine")) return "refines";
        if (all.contains("derive")) return "derives";
        if (all.contains("copy")) return "copies";
        if (all.contains("trace")) return "traces";
        if (all.contains("include")) return "includes";
        if (all.contains("extend")) return "extends";
        if (all.contains("realization")) return "realizes";
        for (Object st : stereotypes) {
            String s = Json.str(st);
            if (BASIC_NAME.matcher(s).matches() && !KEYWORDS.contains(s)) return s;
        }
        return "depends";
    }

    private void transition(Map<String, Object> e, String ind) {
        StringBuilder sb = new StringBuilder(ind).append("transition ").append(decl(e)).append("first ").append(ref(src(e)));
        List<Map<String, Object>> kids = children.get(Json.str(e.get("id")));
        if (kids != null) {
            for (Map<String, Object> k : kids) {
                if ("Trigger".equals(type(k))) {
                    written.add(Json.str(k.get("id")));
                    String n = Json.str(k.get("name"));
                    sb.append(" accept ").append(n.isEmpty() ? shortName(Json.str(k.get("id"))) : name(n));
                }
            }
        }
        sb.append(" then ").append(ref(tgt(e))).append(';');
        out.append(sb).append('\n');
        // weitere Kinder (z. B. Guard-Kommentare) nicht verlieren
        if (kids != null) {
            for (Map<String, Object> k : kids) if (!"Trigger".equals(type(k))) element(k, 1 + ind.length() / 4);
        }
    }

    private void connector(Map<String, Object> e, String t, String ind) {
        List<String> ends = new ArrayList<>();
        for (Object o : Json.arr(e.get("connector_details"))) {
            Map<String, Object> end = Json.obj(o);
            String part = Json.str(end.get("part_id"));
            String role = Json.str(end.get("role_id"));
            String roleName = Json.str(end.get("role_name"));
            String r = byId.containsKey(role) ? ref(role) : name(roleName.isEmpty() ? role : roleName);
            ends.add(byId.containsKey(part) ? ref(part) + "." + r : r);
        }
        while (ends.size() < 2) ends.add("?");
        if ("BindingConnector".equals(t)) {
            out.append(ind).append("binding ").append(decl(e)).append("bind ").append(ends.get(0)).append(" = ")
                    .append(ends.get(1)).append(";\n");
        } else {
            out.append(ind).append("connection ").append(decl(e)).append("connect ").append(ends.get(0)).append(" to ")
                    .append(ends.get(1)).append(";\n");
        }
    }

    private void association(Map<String, Object> e, String ind) {
        out.append(ind).append("connection def ").append(decl(e)).append("{\n");
        int n = 0;
        for (Object o : Json.arr(e.get("association_ends"))) {
            Map<String, Object> end = Json.obj(o);
            String prop = Json.str(end.get("property_id"));
            String type = Json.str(end.get("property_type_id"));
            String endName = byId.containsKey(prop) ? label(byId.get(prop)) : "end" + (++n);
            out.append(ind).append("    end ").append(name(endName));
            if (!type.isEmpty()) out.append(" : ").append(ref(type));
            out.append(";\n");
        }
        out.append(ind).append("}\n");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Zuordnung v1-Typ → v2-Schlüsselwort
    // ═══════════════════════════════════════════════════════════════════════

    private String keyword(Map<String, Object> e, String t) {
        switch (t) {
            case "Package": case "Model": return "package";
            case "Block": return "part def";
            case "ValueType": return "attribute def";
            case "ConstraintBlock": return "constraint def";
            case "Requirement": return "requirement";
            case "TestCase": return "verification def";
            case "UseCase": return "use case def";
            case "Actor": return "part def";            // v2 kennt Akteure nur als Rolle in Use Cases
            case "Activity": return "action def";
            case "StateMachine": return "state def";
            case "State": case "FinalState": case "Pseudostate": return "state";
            case "Interaction": return "occurrence def";
            case "View": return "view def";
            case "Viewpoint": return "viewpoint def";
            case "PartProperty": return "part";
            case "ReferenceProperty": return "ref part";
            case "ValueProperty": return "attribute";
            case "ConstraintProperty": return "constraint";
            case "FlowProperty": {
                String d = Json.str(e.get("direction")).toLowerCase();
                return (d.equals("in") || d.equals("out") || d.equals("inout") ? d + " " : "") + "item";
            }
            case "Property": return "ref";
            case "Port": case "ProxyPort": case "FullPort": case "FlowPort": return "port";
            case "ActivityPartition": return "part";
            case "DecisionNode": return "decide";
            case "MergeNode": return "merge";
            case "ForkNode": return "fork";
            case "JoinNode": return "join";
            case "Lifeline": return "ref part";
            case "ControlFlow": case "ObjectFlow": case "ItemFlow": case "Transition": case "Message":
            case "Connector": case "BindingConnector": case "Usage": case "InterfaceRealization":
            case "Association": case "AssociationClass": case "Rationale": case "Problem":
                return "relation";
            default:
                if (t.contains("Action")) return "action";
                if (t.contains("Node")) return "action";   // Initial-/Final-/Object-Knoten
                return null;
        }
    }

    /** Für Knoten/Zustände ohne eigenes v2-Schlüsselwort den v1-Typ als Kommentar ergänzen. */
    private static boolean needsTypeComment(String t) {
        return t.equals("Actor") || t.equals("Pseudostate") || t.equals("FinalState") || t.equals("ActivityPartition")
                || t.equals("Lifeline") || (t.contains("Node") && !t.equals("DecisionNode") && !t.equals("MergeNode")
                && !t.equals("ForkNode") && !t.equals("JoinNode"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Hilfsfunktionen
    // ═══════════════════════════════════════════════════════════════════════

    private static String type(Map<String, Object> e) {
        return Json.str(e.get("type")).replace(" ", "");
    }

    private static String humanType(Map<String, Object> e) {
        return Json.str(e.get("type"));
    }

    /** {@code <id> Name } bzw. nur {@code <id> } bei unbenannten Elementen (mit Leerzeichen am Ende). */
    private String decl(Map<String, Object> e) {
        String id = Json.str(e.get("id"));
        String n = Json.str(e.get("name"));
        String t = type(e);
        StringBuilder sb = new StringBuilder(shortName(id)).append(' ');
        if (!n.isEmpty()) sb.append(name(n)).append(' ');
        if (needsTypeComment(t)) sb.append("/* v1 ").append(humanType(e)).append(" */ ");
        return sb.toString();
    }

    private static String shortName(String id) {
        return "<" + name(id) + ">";
    }

    /** Verweis auf ein anderes Element über seinen Namen (lesbar); unbenannte Elemente über die ID. */
    private String ref(String id) {
        if (id == null || id.isEmpty()) return "?";
        Map<String, Object> e = byId.get(id);
        if (e == null) return name(id);
        String n = Json.str(e.get("name"));
        return n.isEmpty() ? name(id) : name(n);
    }

    private static String label(Map<String, Object> e) {
        String n = Json.str(e.get("name"));
        return n.isEmpty() ? Json.str(e.get("id")) : n;
    }

    static String name(String n) {
        if (BASIC_NAME.matcher(n).matches() && !KEYWORDS.contains(n)) return n;
        return "'" + n.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private String metadata(Map<String, Object> e) {
        StringBuilder sb = new StringBuilder();
        for (Object o : Json.arr(e.get("stereotypes"))) {
            String st = Json.str(o);
            if (st.isEmpty() || IMPLIED_STEREOTYPES.contains(st.replace(" ", ""))) continue;
            sb.append('#').append(name(st)).append(' ');
        }
        return sb.toString();
    }

    private static String multiplicity(Map<String, Object> e) {
        Map<String, Object> m = Json.obj(e.get("multiplicity"));
        if (m.isEmpty()) return "";
        String lo = num(Json.str(m.get("lower")));
        String up = num(Json.str(m.get("upper")));
        if (up.equals("-1")) up = "*";
        if (lo.isEmpty() && up.isEmpty()) return "";
        if (lo.isEmpty()) lo = "0";
        if (up.isEmpty()) up = lo;
        if (lo.equals("1") && up.equals("1")) return "";
        return lo.equals(up) ? "[" + lo + "] " : "[" + lo + ".." + up + "] ";
    }

    private static String num(String s) {
        s = s.trim();
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static String literal(String v) {
        String s = v.trim();
        if (s.matches("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?") || s.equals("true") || s.equals("false")) return s;
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private List<String> targets(Map<String, Object> e, String relationType) {
        List<String> l = new ArrayList<>();
        for (Object o : Json.arr(e.get("outgoing_relations"))) {
            Map<String, Object> r = Json.obj(o);
            if (relationType.equalsIgnoreCase(Json.str(r.get("relation_type"))) && !Json.str(r.get("target_id")).isEmpty()) {
                l.add(Json.str(r.get("target_id")));
            }
        }
        return l;
    }

    private static String src(Map<String, Object> e) {
        return Json.str(Json.obj(e.get("routing_details")).get("source_id"));
    }

    private static String tgt(Map<String, Object> e) {
        return Json.str(Json.obj(e.get("routing_details")).get("target_id"));
    }

    private static String commentText(String s) {
        return s.replace("*/", "* /").trim();
    }

    private static String firstNonEmpty(String a, String b) {
        return a == null || a.isEmpty() ? (b == null ? "" : b) : a;
    }

    private static String indent(int depth) {
        return "    ".repeat(Math.max(0, depth));
    }

    private static void trimEnd(StringBuilder sb) {
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') sb.setLength(sb.length() - 1);
    }
}
