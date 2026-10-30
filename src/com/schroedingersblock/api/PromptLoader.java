package com.schroedingersblock.api;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Baut die Nachrichten an das LLM.
 *
 * Neu: Es gibt keine festen Fragen mehr. Der Nutzer tippt seine Frage frei ein.
 * - System-Prompt: prompts/System_Prompt.md (editierbar, ohne neu zu kompilieren).
 *   Platzhalter {answerStyle} wird je nach Basic/Expert ersetzt.
 * - Erste Nachricht eines Chats: Modelldaten (JSON) + optionaler Fokus + Frage.
 * - Folgefragen im selben Chat: nur die Frage (Modell ist schon im Verlauf).
 *
 * Wichtig für die Evaluation: Der System-Prompt ist eine Versuchsvariable.
 * Änderungen an System_Prompt.md bitte versionieren/dokumentieren.
 */
public class PromptLoader {

    private static final String PROMPT_DIR = "prompts";
    private static final String SYSTEM_PROMPT_FILE = "System_Prompt.md";

    static final String STYLE_BASIC =
            "Answer briefly and in plain language (at most three short paragraphs).";
    static final String STYLE_EXPERT =
            "Answer in technical detail for an experienced systems engineer. "
                    + "Name the concrete model elements (with their names) that support each statement.";

    /** Fallback, falls die Datei fehlt – inhaltlich identisch zu installer/ressources/System_Prompt.md. */
    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are an assistant for SysML v1 models created in CATIA Magic.\n"
                    + "With the first user message you receive the model as a JSON export: a flat list of elements, "
                    + "each with id, name, type, stereotypes, owner_id, documentation, values and relations "
                    + "(outgoing_relations, routing_details, association_ends, connector_details). "
                    + "Relations reference other elements by id.\n"
                    + "Answer the user's questions about this model. Base your answers on the model data.\n"
                    + "Answer in the language of the question.\n"
                    + "{answerStyle}";

    public static String buildSystemPrompt(File baseDir, String detailLevel) {
        String template = DEFAULT_SYSTEM_PROMPT;
        File promptFile = new File(baseDir, PROMPT_DIR + File.separator + SYSTEM_PROMPT_FILE);
        if (promptFile.exists()) {
            try {
                template = Files.readString(promptFile.toPath(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // Fallback auf DEFAULT_SYSTEM_PROMPT
            }
        }
        String style = "Expert".equalsIgnoreCase(detailLevel) ? STYLE_EXPERT : STYLE_BASIC;
        return template.replace("{answerStyle}", style);
    }

    /**
     * Erste Nachricht eines Chats: Modell + (optional) Fokus-Element + Frage.
     *
     * @param modelJson  JSON-Export des Modells
     * @param focusInfo  z. B. "Battery [Block], owner: Power Subsystem" oder null
     * @param question   Frage des Nutzers
     */
    public static String buildFirstMessage(String modelJson, String focusInfo, String question) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Model data (JSON export)\n")
                .append(modelJson != null ? modelJson : "No model data available.")
                .append("\n\n");
        if (focusInfo != null && !focusInfo.isBlank()) {
            sb.append("# Currently selected element\n").append(focusInfo).append("\n\n");
        }
        sb.append("# Question\n").append(question);
        return sb.toString();
    }
}
