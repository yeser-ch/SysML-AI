package com.schroedingersblock.api;

import java.io.File;

/**
 * Führt EINE Anfrage aus und macht daraus einen vollständigen Datensatz:
 * Antwort + ID-Check + Prüfsummen + Archivierung + Log-Eintrag.
 * Wird vom Chat und vom Testlauf gleichermaßen genutzt, damit beide identisch protokolliert werden.
 */
public final class Evaluation {

    private Evaluation() { }

    /**
     * @param repetition aktuelle Wiederholung (1-basiert); ab der 2. Wiederholung wird immer ein neuer Chat
     *                   begonnen, damit die Wiederholungen unabhängig voneinander sind.
     * @param runFile    Datei des Testlaufs oder null (Chat)
     */
    public static EvaluationRecord run(ExplanationService service, AskSettings settings, ModelExport export,
                                       String question, String focusInfo, TestSuite.TestCase testCase,
                                       String runId, int repetition, File runFile) {
        AskSettings s = settings.copy();
        if (repetition > 1 || settings.repetitions > 1) s.freshChat = true;

        String systemPrompt = PromptLoader.buildSystemPrompt(GeminiExplanationService.getBaseDirectory(),
                s.detailLevel, s.requireIds);

        EvaluationRecord r = new EvaluationRecord();
        r.runId = runId;
        r.repetition = repetition;
        r.question = question;
        r.focusElement = focusInfo;
        r.settings = s;
        r.export = export;
        r.systemPromptSha = Hashing.sha256Short(systemPrompt);
        if (testCase != null) {
            r.testId = testCase.id;
            r.category = testCase.category;
            r.expectedAnswer = testCase.expectedAnswer;
        }

        r.answer = service.ask(question, export.json, focusInfo, s);
        if (r.answer.success) {
            r.idCheck = IdChecker.check(r.answer.text, export.knownIds);
        }

        EvaluationLog.archive(r.systemPromptSha, systemPrompt, export);
        EvaluationLog.append(r, runFile);
        return r;
    }
}
