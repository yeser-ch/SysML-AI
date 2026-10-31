package com.schroedingersblock.api;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Protokolliert jede Frage/Antwort mit dem kompletten Versuchsaufbau.
 *
 * Ordner: Dokumente/SysML-AI-QA/
 *   answers_log.csv          – alle Antworten (Chat und Testläufe)
 *   runs/run_<id>.csv        – eine Datei pro Testlauf (gleiche Spalten, zum Bewerten)
 *   archive/prompts/<sha>.md – jeder verwendete System-Prompt im Wortlaut
 *   archive/exports/<sha>.json – jeder verwendete Modell-Export
 *
 * CSV: Trennzeichen ";", UTF-8 mit BOM -> öffnet direkt in Excel.
 * Die Spalten "rating" und "rating_notes" sind leer und für eure manuelle Bewertung gedacht.
 * Das Plugin hängt nur Zeilen an und liest die Dateien nie zurück – Zeilen/Dateien dürfen gelöscht werden.
 */
public final class EvaluationLog {

    static final String[] COLUMNS = {
            "run_id", "test_id", "category", "repetition", "timestamp",
            "question", "expected_answer", "answer", "rating", "rating_notes",
            "model_requested", "model_used", "model_version", "success", "finish_reason",
            "detail_level", "temperature", "max_tokens", "thinking_level", "thinking_used", "grounding", "fresh_chat", "require_ids", "scope",
            "focus_element", "system_prompt_sha", "export_file", "export_sha", "export_kb",
            "export_elements", "export_relations", "model_transfer", "prompt_tokens", "cached_tokens",
            "answer_tokens", "thoughts_tokens", "duration_s", "rate_limit_wait_s",
            "ids_cited", "ids_found", "ids_unknown", "unknown_ids", "answer_sha", "plugin_version"
    };
    static final String HEADER = String.join(";", COLUMNS);

    private EvaluationLog() { }

    public static File getBaseDir() {
        return new File(System.getProperty("user.home"), "Documents" + File.separator + "SysML-AI-QA");
    }

    public static File getLogFile() {
        return new File(getBaseDir(), "answers_log.csv");
    }

    public static File newRunFile(String runId) {
        return new File(getBaseDir(), "runs" + File.separator + "run_" + runId + ".csv");
    }

    public static String newRunId() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    /**
     * Schreibt einen Datensatz ins Gesamt-Log und – falls angegeben – in die Datei des Testlaufs.
     * Ist eine Datei gesperrt (z. B. in Excel geöffnet), wird in eine Ersatzdatei "..._while-open.csv" geschrieben.
     *
     * @return Warnung für die Anzeige oder null, wenn alles normal gespeichert wurde
     */
    public static synchronized String append(EvaluationRecord r, File runFile) {
        StringBuilder warn = new StringBuilder();
        for (File f : runFile == null ? new File[]{getLogFile()} : new File[]{getLogFile(), runFile}) {
            if (writeLine(f, r)) continue;
            File alt = new File(f.getParentFile(), f.getName().replace(".csv", "_while-open.csv"));
            if (writeLine(alt, r)) {
                warn.append(f.getName()).append(" is open in another program – saved to ").append(alt.getName()).append(". ");
            } else {
                warn.append("Could not save to ").append(f.getName()).append(" (close it in Excel). ");
            }
        }
        return warn.length() == 0 ? null : warn.toString().trim();
    }

    /** Legt System-Prompt und Modell-Export einmalig im Archiv ab (Dateiname = Prüfsumme). */
    public static synchronized void archive(String systemPromptSha, String systemPrompt, ModelExport export) {
        try {
            File p = new File(getBaseDir(), "archive" + File.separator + "prompts" + File.separator + systemPromptSha + ".md");
            if (!p.exists()) {
                p.getParentFile().mkdirs();
                Files.writeString(p.toPath(), systemPrompt, StandardCharsets.UTF_8);
            }
            if (export != null && export.file != null && export.file.exists()) {
                File e = new File(getBaseDir(), "archive" + File.separator + "exports" + File.separator + export.sha + ".json");
                if (!e.exists()) {
                    e.getParentFile().mkdirs();
                    Files.copy(export.file.toPath(), e.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException ex) {
            System.err.println(com.schroedingersblock.core.SchroedingersBlockPlugin.LOG_PREFIX + "Archivierung fehlgeschlagen: " + ex.getMessage());
        }
    }

    private static boolean writeLine(File f, EvaluationRecord r) {
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            rotateIfOldFormat(f);
            boolean isNew = !f.exists();
            StringBuilder line = new StringBuilder();
            if (isNew) line.append('﻿').append(HEADER).append("\r\n");
            List<String> values = r.values();
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) line.append(';');
                line.append(csv(values.get(i)));
            }
            line.append("\r\n");
            Files.write(f.toPath(), line.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException e) {
            System.err.println(com.schroedingersblock.core.SchroedingersBlockPlugin.LOG_PREFIX + "Log konnte nicht geschrieben werden: " + e.getMessage());
            return false;
        }
    }

    /** Hat eine bestehende Datei andere Spalten (ältere Version), wird sie umbenannt statt vermischt. */
    private static void rotateIfOldFormat(File f) throws IOException {
        if (!f.exists()) return;
        String first;
        try (java.io.BufferedReader br = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            first = br.readLine();
        }
        if (first == null) return;
        if (first.startsWith("﻿")) first = first.substring(1);
        if (!first.equals(HEADER)) {
            File old = new File(f.getParentFile(), f.getName().replace(".csv", "_old_" + newRunId() + ".csv"));
            Files.move(f.toPath(), old.toPath());
        }
    }

    private static String csv(String v) {
        if (v == null) return "";
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}
