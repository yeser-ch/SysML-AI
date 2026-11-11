package de.hm.sysmlaiqa.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kleiner JSON-Leser ohne externe Bibliothek (das Plugin soll ohne Zusatz-JARs auskommen).
 * Ergebnis: Map (Objekt, Reihenfolge bleibt erhalten), List (Array), String, Double, Boolean oder null.
 * Tolerant gegenüber einem fehlenden Komma bzw. einem überzähligen Komma vor ] oder } –
 * so etwas kann bei Velocity-Exporten in Randfällen entstehen.
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json p = new Json(text == null ? "" : text);
        p.skipWs();
        if (p.i < p.s.length() && p.s.charAt(p.i) == '﻿') p.i++;
        Object v = p.value();
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<>();
    }

    public static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private Object value() {
        skipWs();
        if (i >= s.length()) return null;
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': i += 4; return Boolean.TRUE;
            case 'f': i += 5; return Boolean.FALSE;
            case 'n': i += 4; return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // {
        while (true) {
            skipWs();
            if (i >= s.length()) return m;
            char c = s.charAt(i);
            if (c == '}') { i++; return m; }
            if (c == ',') { i++; continue; }
            if (c != '"') { i++; continue; } // unerwartetes Zeichen überspringen
            String key = string();
            skipWs();
            if (i < s.length() && s.charAt(i) == ':') i++;
            m.put(key, value());
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++; // [
        while (true) {
            skipWs();
            if (i >= s.length()) return l;
            char c = s.charAt(i);
            if (c == ']') { i++; return l; }
            if (c == ',') { i++; continue; }
            int before = i;
            l.add(value());
            if (i == before) i++; // Endlosschleife bei kaputtem Text vermeiden
        }
    }

    private String string() {
        int[] end = new int[1];
        String v = GeminiExplanationService.readJsonString(s, i + 1, end);
        i = end[0];
        return v;
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (i == start) { i++; return null; }
        try {
            return Double.parseDouble(s.substring(start, i));
        } catch (NumberFormatException e) {
            return s.substring(start, i);
        }
    }

    private void skipWs() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }
}
