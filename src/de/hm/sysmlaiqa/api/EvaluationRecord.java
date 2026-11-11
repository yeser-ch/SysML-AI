package de.hm.sysmlaiqa.api;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/** Ein Datensatz = eine Antwort mit vollständigem Versuchsaufbau (eine Zeile im Log). */
public class EvaluationRecord {

    public static final String PLUGIN_VERSION = "3.3-ws2627";

    public String runId = "chat";
    public String testId = "";
    public String category = "";
    public int repetition = 1;
    public String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    public String question = "";
    public String expectedAnswer = "";
    public String focusElement = "";
    public String systemPromptSha = "";
    public String systemPromptSource = "";
    public AskSettings settings;
    public ModelExport export;
    public ExplanationService.Answer answer;
    public IdChecker.Result idCheck;
    public String logWarning;

    public String answerSha() {
        return answer == null ? "" : Hashing.sha256Short(answer.text.trim());
    }

    /** Werte in der Reihenfolge von {@link EvaluationLog#COLUMNS}. */
    List<String> values() {
        AskSettings s = settings;
        ExplanationService.Answer a = answer;
        return Arrays.asList(
                runId, testId, category, String.valueOf(repetition), timestamp,
                question, expectedAnswer, a == null ? "" : a.text, "", "",
                s.model, a == null ? "" : a.modelUsed, a == null ? "" : a.modelVersion,
                a == null ? "false" : String.valueOf(a.success),
                a == null || a.finishReason == null ? "" : a.finishReason,
                String.valueOf(s.temperature), String.valueOf(s.maxOutputTokens),
                s.thinkingLevel, a == null ? "" : a.thinkingUsed,
                String.valueOf(s.grounding), String.valueOf(s.freshChat), String.valueOf(s.requireIds), s.scope,
                focusElement == null ? "" : focusElement, systemPromptSha, systemPromptSource,
                export == null ? "" : export.format, export == null ? "" : export.scopeDetail,
                export == null ? "" : export.file.getName(), export == null ? "" : export.sha,
                export == null ? "" : String.format("%.1f", export.sizeKb()),
                export == null ? "" : String.valueOf(export.elementCount),
                export == null ? "" : String.valueOf(export.relationCount),
                export == null ? "" : String.valueOf(export.approxTokens()),
                export == null || export.exactTokens < 0 ? "" : export.exactTokens + " (" + export.exactTokensModel + ")",
                a == null ? "" : String.valueOf(a.previousTurns), a == null ? "" : a.userMessage,
                a == null ? "" : a.transfer,
                a == null ? "" : String.valueOf(a.promptTokens), a == null ? "" : String.valueOf(a.cachedTokens),
                a == null ? "" : String.valueOf(a.answerTokens),
                a == null ? "" : String.valueOf(a.thoughtsTokens),
                a == null ? "" : String.format("%.2f", a.durationMs / 1000.0),
                a == null ? "" : String.valueOf(a.rateLimitWaitSec),
                idCheck == null ? "" : String.valueOf(idCheck.cited.size()),
                idCheck == null ? "" : String.valueOf(idCheck.found()),
                idCheck == null ? "" : String.valueOf(idCheck.unknown.size()),
                idCheck == null ? "" : String.join(" ", idCheck.unknown),
                answerSha(), PLUGIN_VERSION
        );
    }
}
