package com.schroedingersblock.api;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lädt Testfälle aus einer CSV-Datei (Trennzeichen ";" oder "," – wird automatisch erkannt).
 *
 * Spalten (Kopfzeile, Groß-/Kleinschreibung egal, deutsche Namen gehen auch):
 *   test_id (id)            – z. B. A-01
 *   category (kategorie)    – z. B. A, B, C, D
 *   question (frage)        – PFLICHT
 *   expected_answer (erwartete_antwort, referenzantwort) – Ground Truth für die Bewertung
 *   notes (notizen)         – optional
 * Zeilen ohne Frage oder mit "#" am Anfang der ID werden übersprungen.
 */
public class TestSuite {

    public static class TestCase {
        public final String id;
        public final String category;
        public final String question;
        public final String expectedAnswer;
        public final String notes;

        public TestCase(String id, String category, String question, String expectedAnswer, String notes) {
            this.id = id;
            this.category = category;
            this.question = question;
            this.expectedAnswer = expectedAnswer;
            this.notes = notes;
        }
    }

    public final File file;
    public final List<TestCase> cases;

    private TestSuite(File file, List<TestCase> cases) {
        this.file = file;
        this.cases = cases;
    }

    public static TestSuite load(File f) throws IOException {
        String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        String firstLine = text.contains("\n") ? text.substring(0, text.indexOf('\n')) : text;
        char delim = count(firstLine, ';') >= count(firstLine, ',') ? ';' : ',';

        List<List<String>> rows = parseCsv(text, delim);
        if (rows.isEmpty()) throw new IOException("The file is empty.");

        Map<String, Integer> col = new LinkedHashMap<>();
        List<String> header = rows.get(0);
        for (int i = 0; i < header.size(); i++) {
            String h = normalize(header.get(i));
            col.putIfAbsent(h, i);
        }
        int qCol = find(col, "question", "frage");
        if (qCol < 0) {
            throw new IOException("No 'question' column found. Header must contain: test_id;category;question;expected_answer");
        }
        int idCol = find(col, "test_id", "id", "testid", "testfall");
        int catCol = find(col, "category", "kategorie", "cat");
        int expCol = find(col, "expected_answer", "expected", "erwartete_antwort", "referenzantwort", "ground_truth");
        int notesCol = find(col, "notes", "notizen", "note", "bemerkung");

        List<TestCase> cases = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String q = get(row, qCol);
            String id = get(row, idCol);
            if (q.isBlank() || id.startsWith("#")) continue;
            if (id.isBlank()) id = "T" + String.format("%02d", cases.size() + 1);
            cases.add(new TestCase(id, get(row, catCol), q, get(row, expCol), get(row, notesCol)));
        }
        if (cases.isEmpty()) throw new IOException("No test cases with a question found.");
        return new TestSuite(f, cases);
    }

    /** Zusammenfassung wie "A×5, B×3, C×4, D×2". */
    public String categorySummary() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (TestCase c : cases) m.merge(c.category.isBlank() ? "?" : c.category, 1, Integer::sum);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append("×").append(e.getValue());
        }
        return sb.toString();
    }

    private static String normalize(String h) {
        return h.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    private static int find(Map<String, Integer> col, String... names) {
        for (String n : names) if (col.containsKey(n)) return col.get(n);
        return -1;
    }

    private static String get(List<String> row, int idx) {
        return (idx < 0 || idx >= row.size()) ? "" : row.get(idx).trim();
    }

    private static int count(String s, char c) {
        int n = 0;
        for (char x : s.toCharArray()) if (x == c) n++;
        return n;
    }

    /** CSV-Parser mit Anführungszeichen, "" als Escape und Zeilenumbrüchen in Feldern. */
    static List<List<String>> parseCsv(String text, char delim) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else inQuotes = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == delim) {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(field.toString());
                field.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
