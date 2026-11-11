package de.hm.sysmlaiqa.api;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest SysML-v2-Textnotation (z. B. aus CATIA: File → Export To → SysML v2 Textual Notation) so weit,
 * wie das Plugin es braucht – KEIN vollständiger SysML-v2-Parser:
 * - Anzahl Deklarationen (Definitionen und Usages) und Beziehungen für die Statuszeile
 * - qualifizierte Namen aller Elemente (für den ID-Check: die KI nennt [id:Paket::Element])
 * - ein {@link ModelIndex} mit Elementen und Beziehungen, damit die Schnellfragen echte Namen verwenden
 *
 * Erkannt werden Deklarationen der Form {@code [Modifizierer] schlüsselwort [def] [<kurzname>] Name ...}
 * mit Verschachtelung über geschweifte Klammern, außerdem satisfy, verify, allocate, connect, bind, flow,
 * succession/then, transition, dependency, Spezialisierung (:>) und Typisierung (: Typ).
 */
public final class SysmlV2Reader {

    /** Ergebnis des Lesens. */
    public static final class Result {
        public final ModelIndex index = new ModelIndex();
        public int declarations;
        public int relations;
        /** Qualifizierte Namen (A::B::C) und alle Endstücke davon (B::C, C) – für den ID-Check. */
        public final Set<String> names = new LinkedHashSet<>();
    }

    /** Schlüsselwörter, die eine benannte Modell-Deklaration einleiten (für Zählung und Index). */
    private static final Pattern DECL = Pattern.compile(
            "^\\s*(?:(?:abstract|variation|variant|individual|readonly|derived|end|in|out|inout|ref|public|private|protected|#[\\w']+)\\s+)*"
                    + "(library\\s+package|package|use\\s+case|part|attribute|port|item|action|state|requirement|constraint|calc|"
                    + "connection|interface|allocation|occurrence|verification|analysis|view|viewpoint|concern|enum|metadata|"
                    + "actor|subject|stakeholder|rendering|flow)"
                    + "(\\s+def)?\\b\\s*(?:<\\s*('(?:[^'\\\\]|\\\\.)*'|[\\w]+)\\s*>\\s*)?('(?:[^'\\\\]|\\\\.)*'|[A-Za-z_][\\w]*)?(.*)$");
    /** Verweis: Name, 'Name mit Leerzeichen', qualifizierter Name oder Feature-Kette. */
    private static final String REF = "((?:'(?:[^'\\\\]|\\\\.)*'|[\\w]+)(?:(?:::|\\.)(?:'(?:[^'\\\\]|\\\\.)*'|[\\w]+))*)";
    private static final Pattern SATISFY = Pattern.compile("\\bsatisfy\\s+(?:requirement\\s+)?" + REF + "\\s+by\\s+" + REF);
    private static final Pattern VERIFY = Pattern.compile("\\bverify\\s+(?:requirement\\s+)?" + REF);
    private static final Pattern ALLOCATE = Pattern.compile("\\ballocate\\s+" + REF + "\\s+to\\s+" + REF);
    private static final Pattern CONNECT = Pattern.compile("\\bconnect\\s+" + REF + "\\s+to\\s+" + REF);
    private static final Pattern BIND = Pattern.compile("\\bbind\\s+" + REF + "\\s*=\\s*" + REF);
    private static final Pattern FLOW = Pattern.compile("\\bflow\\b.*?\\bfrom\\s+" + REF + "\\s+to\\s+" + REF);
    private static final Pattern FIRST_THEN = Pattern.compile("\\bfirst\\s+" + REF + ".*?\\bthen\\s+" + REF);
    private static final Pattern DEPENDENCY = Pattern.compile("\\bdependency\\b.*?\\bfrom\\s+" + REF + "\\s+to\\s+" + REF);
    private static final Pattern SPECIALIZES = Pattern.compile("(?::>>|:>|\\bspecializes\\b|\\bsubsets\\b|\\bredefines\\b)\\s*~?" + REF);
    private static final Pattern TYPED = Pattern.compile("(?<![:>])\\s:\\s*(?![:>])~?" + REF);
    /** Wörter, die nach einem Schlüsselwort stehen können, aber kein Name sind. */
    private static final java.util.Set<String> NOT_NAMES = new java.util.HashSet<>(Arrays.asList(
            "connect", "bind", "first", "then", "from", "to", "by", "accept", "of", "def", "specializes", "subsets",
            "redefines", "references", "defined", "typed", "all"));

    private SysmlV2Reader() { }

    public static Result read(String text) {
        Result r = new Result();
        String src = stripComments(text == null ? "" : text);
        Deque<String[]> stack = new ArrayDeque<>();   // {qualifiedName, id}
        Map<String, String> idByName = new HashMap<>(); // einfacher und qualifizierter Name -> id
        List<String[]> pending = new ArrayList<>();      // {type, sourceExpr, targetExpr, scopeId}
        int counter = 0;

        // Anweisungen an ; { } trennen, Klammern verfolgen
        StringBuilder stmt = new StringBuilder();
        for (int i = 0; i <= src.length(); i++) {
            char c = i < src.length() ? src.charAt(i) : ';';
            if (c == '\'') {                      // Namen in Anführungszeichen unverändert übernehmen
                int j = i + 1;
                while (j < src.length() && src.charAt(j) != '\'') { if (src.charAt(j) == '\\') j++; j++; }
                stmt.append(src, i, Math.min(j + 1, src.length()));
                i = j;
                continue;
            }
            if (c != ';' && c != '{' && c != '}') {
                stmt.append(c);
                continue;
            }
            String s = stmt.toString().trim();
            stmt.setLength(0);
            String[] opened = null;
            if (!s.isEmpty()) {
                String scopeId = stack.isEmpty() ? "" : stack.peek()[1];
                String scopeQ = stack.isEmpty() ? "" : stack.peek()[0];
                Matcher d = DECL.matcher(s);
                if (d.find() && !s.startsWith("doc") && !s.startsWith("comment")) {
                    String kw = d.group(1).replaceAll("\\s+", " ");
                    boolean def = d.group(2) != null;
                    String name = unquote(d.group(4));
                    String rest0 = d.group(5) == null ? "" : d.group(5);
                    if (d.group(4) != null && NOT_NAMES.contains(d.group(4))) {   // z. B. "connection connect a to b"
                        rest0 = d.group(4) + rest0;
                        name = "";
                    }
                    String shortName = unquote(d.group(3));
                    String rest = rest0;
                    if (name.isEmpty() && shortName.isEmpty()) {
                        // anonyme Usage, z. B. "part : Battery;" – Name vom Typ ableiten
                        Matcher t = TYPED.matcher(" " + rest);
                        name = t.find() ? lastSegment(unquote(t.group(1))) : "";
                    }
                    String label = name.isEmpty() ? shortName : name;
                    String q = scopeQ.isEmpty() ? label : (label.isEmpty() ? scopeQ : scopeQ + "::" + label);
                    String id = "v2_" + (++counter);
                    r.declarations++;
                    String type = mapType(kw, def);
                    r.index.elements.put(id, new ModelIndex.Elem(id, label, type, scopeId, new ArrayList<>()));
                    if (!label.isEmpty()) {
                        addNames(r.names, q);
                        idByName.putIfAbsent(label, id);
                        idByName.put(q, id);
                        if (!shortName.isEmpty()) { idByName.putIfAbsent(shortName, id); r.names.add(shortName); }
                    }
                    Matcher sp = SPECIALIZES.matcher(rest);
                    while (sp.find()) pending.add(new String[]{"Generalization", id, sp.group(1), scopeId});
                    Matcher ty = TYPED.matcher(" " + rest);
                    if (ty.find()) pending.add(new String[]{"Typed by", id, ty.group(1), scopeId});
                    relations(kw + " " + rest, id, pending, scopeId);
                    if (c == '{') opened = new String[]{q, id};
                } else {
                    relations(s, scopeId, pending, scopeId);
                    if (c == '{') opened = new String[]{scopeQ, scopeId};  // z. B. "objective {" – gleicher Besitzer
                }
            } else if (c == '{') {
                opened = stack.isEmpty() ? new String[]{"", ""} : stack.peek();
            }
            if (c == '{') stack.push(opened);
            else if (c == '}' && !stack.isEmpty()) stack.pop();
        }

        for (String[] p : pending) {
            String s = resolve(p[1], idByName, r);
            String t = resolve(p[2], idByName, r);
            if (!"Typed by".equals(p[0])) r.relations++;
            if (s == null || t == null || s.equals(t)) continue;
            ModelIndex.Relation rel = new ModelIndex.Relation(p[0], s, t);
            r.index.elements.get(s).outgoing.add(rel);
            r.index.elements.get(t).incoming.add(rel);
        }
        return r;
    }

    private static void relations(String s, String self, List<String[]> pending, String scopeId) {
        Matcher m;
        if ((m = SATISFY.matcher(s)).find()) pending.add(new String[]{"Satisfy", m.group(2).trim(), m.group(1).trim(), scopeId});
        else if ((m = VERIFY.matcher(s)).find()) pending.add(new String[]{"Verify", self, m.group(1), scopeId});
        if ((m = ALLOCATE.matcher(s)).find()) pending.add(new String[]{"Allocate", m.group(1), m.group(2), scopeId});
        if ((m = CONNECT.matcher(s)).find()) pending.add(new String[]{"Connector", m.group(1), m.group(2), scopeId});
        if ((m = BIND.matcher(s)).find()) pending.add(new String[]{"Binding Connector", m.group(1), m.group(2), scopeId});
        if ((m = FLOW.matcher(s)).find()) pending.add(new String[]{"Item Flow", m.group(1), m.group(2), scopeId});
        if ((m = FIRST_THEN.matcher(s)).find()) pending.add(new String[]{s.contains("transition") ? "Transition" : "Succession", m.group(1), m.group(2), scopeId});
        if ((m = DEPENDENCY.matcher(s)).find()) {
            pending.add(new String[]{"Dependency", m.group(1), m.group(2), scopeId});
        }
    }

    /** Verweis (Name, qualifizierter Name oder Feature-Kette a.b) auf eine Element-ID abbilden. */
    private static String resolve(String expr, Map<String, String> idByName, Result r) {
        if (expr == null) return null;
        String e = expr.trim();
        if (e.startsWith("v2_") && r.index.elements.containsKey(e)) return e;
        String u = unquote(e);
        if (idByName.containsKey(u)) return idByName.get(u);
        String noQuotes = u.replace("'", "");
        if (idByName.containsKey(noQuotes)) return idByName.get(noQuotes);
        // Feature-Kette a.b.c -> letztes bzw. erstes Glied
        String[] chain = noQuotes.split("\\.");
        if (chain.length > 1) {
            if (idByName.containsKey(chain[chain.length - 1])) return idByName.get(chain[chain.length - 1]);
            if (idByName.containsKey(chain[0])) return idByName.get(chain[0]);
        }
        String last = lastSegment(noQuotes);
        return idByName.get(last);
    }

    private static String mapType(String kw, boolean def) {
        switch (kw) {
            case "part": return def ? "Block" : "Part Property";
            case "attribute": return def ? "Value Type" : "Value Property";
            case "port": return def ? "Interface Block" : "Port";
            case "item": return def ? "Block" : "Flow Property";
            case "action": return def ? "Activity" : "Action";
            case "state": return def ? "StateMachine" : "State";
            case "requirement": return "Requirement";
            case "constraint": return def ? "ConstraintBlock" : "Constraint Property";
            case "use case": return "UseCase";
            case "actor": return "Actor";
            case "connection": case "interface": return def ? "Association" : "Connector";
            case "verification": return "Test Case";
            case "package": case "library package": return "Package";
            default: return capitalize(kw) + (def ? " Definition" : " Usage");
        }
    }

    private static void addNames(Set<String> names, String q) {
        String[] parts = q.split("::");
        for (int i = 0; i < parts.length; i++) names.add(String.join("::", Arrays.copyOfRange(parts, i, parts.length)));
    }

    private static String lastSegment(String q) {
        int i = q.lastIndexOf("::");
        return i >= 0 ? q.substring(i + 2) : q;
    }

    private static String unquote(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) s = s.substring(1, s.length() - 1).replace("\\'", "'");
        return s;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Kommentare und doc-Texte entfernen (Zeilen- und Blockkommentare), Zeichenketten bleiben. */
    static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? s.length() : end + 1;
                out.append(';');   // "doc /* … */" und "comment /* … */" enden ohne Semikolon
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                int end = s.indexOf('\n', i);
                i = end < 0 ? s.length() : end - 1;
            } else if (c == '"') {
                int j = i + 1;
                while (j < s.length() && s.charAt(j) != '"') { if (s.charAt(j) == '\\') j++; j++; }
                out.append("\"\"");
                i = j;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
