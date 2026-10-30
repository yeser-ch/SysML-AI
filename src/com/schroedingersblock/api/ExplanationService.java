package com.schroedingersblock.api;

/**
 * Schnittstelle zwischen UI und LLM.
 * Statt fester Prompts wird jetzt eine frei formulierte Frage mit den Modelldaten gesendet.
 */
public interface ExplanationService {

    /**
     * Stellt eine Frage zum Modell.
     *
     * @param question  frei formulierte Frage des Nutzers
     * @param modelJson exportierte Modelldaten (werden nur bei der ersten Frage eines Chats mitgeschickt)
     * @param focusInfo optionale Beschreibung des ausgewählten Elements, sonst null
     * @param settings  Einstellungen aus dem UI
     */
    Answer ask(String question, String modelJson, String focusInfo, AskSettings settings);

    /** Verwirft den bisherigen Chatverlauf. */
    void resetConversation();

    /** Ergebnis einer Anfrage inkl. Metadaten für die Auswertung. */
    class Answer {
        public final String text;
        public final String modelUsed;     // tatsächlich antwortendes Modell (wichtig bei Fallback)
        public final boolean success;
        public final long durationMs;
        public final int promptTokens;     // -1 wenn unbekannt
        public final int answerTokens;     // -1 wenn unbekannt
        public final String finishReason;  // z. B. STOP, MAX_TOKENS

        public Answer(String text, String modelUsed, boolean success, long durationMs,
                      int promptTokens, int answerTokens, String finishReason) {
            this.text = text;
            this.modelUsed = modelUsed;
            this.success = success;
            this.durationMs = durationMs;
            this.promptTokens = promptTokens;
            this.answerTokens = answerTokens;
            this.finishReason = finishReason;
        }

        public static Answer error(String text, long durationMs) {
            return new Answer(text, "-", false, durationMs, -1, -1, "ERROR");
        }
    }
}
