package com.schroedingersblock.api;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Schreibt jede Frage/Antwort mit allen Einstellungen in eine CSV-Datei
 * (Dokumente/SchroedingersBlock/answers_log.csv, Trennzeichen ";", UTF-8 mit BOM -> direkt in Excel öffnen).
 * Grundlage für die Auswertung: welche Frage, welches Modell, welche Einstellungen, welche Antwort.
 */
public class EvaluationLog {

    private static final String HEADER = "timestamp;question;focus_element;answer;model_requested;model_used;"
            + "success;detail_level;temperature;max_tokens;grounding;fresh_chat;scope;export_file;export_kb;"
            + "prompt_tokens;answer_tokens;finish_reason;duration_s";

    public static File getLogFile() {
        return new File(System.getProperty("user.home"),
                "Documents" + File.separator + "SchroedingersBlock" + File.separator + "answers_log.csv");
    }

    public static synchronized void append(String question, String focusInfo, ExplanationService.Answer a,
                                           AskSettings s, File exportFile) {
        File f = getLogFile();
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            boolean isNew = !f.exists();
            StringBuilder line = new StringBuilder();
            if (isNew) line.append('﻿').append(HEADER).append("\r\n");
            String[] values = {
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    question,
                    focusInfo == null ? "" : focusInfo,
                    a.text,
                    s.model,
                    a.modelUsed,
                    String.valueOf(a.success),
                    s.detailLevel,
                    String.valueOf(s.temperature),
                    String.valueOf(s.maxOutputTokens),
                    String.valueOf(s.grounding),
                    String.valueOf(s.freshChat),
                    s.scope,
                    exportFile == null ? "" : exportFile.getName(),
                    exportFile == null ? "" : String.format("%.1f", exportFile.length() / 1024.0),
                    String.valueOf(a.promptTokens),
                    String.valueOf(a.answerTokens),
                    a.finishReason == null ? "" : a.finishReason,
                    String.format("%.2f", a.durationMs / 1000.0)
            };
            for (int i = 0; i < values.length; i++) {
                if (i > 0) line.append(';');
                line.append(csv(values[i]));
            }
            line.append("\r\n");
            Files.write(f.toPath(), line.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[Schrödinger's Block] Log konnte nicht geschrieben werden: " + e.getMessage());
        }
    }

    private static String csv(String v) {
        if (v == null) return "";
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}
