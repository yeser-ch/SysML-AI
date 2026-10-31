package de.hm.sysmlaiqa.api;

/**
 * Schnittstelle zwischen UI und LLM.
 * Statt fester Prompts wird eine frei formulierte Frage zusammen mit den Modelldaten gestellt.
 */
public interface ExplanationService {

    /**
     * Stellt eine Frage zum Modell.
     *
     * @param question  frei formulierte Frage des Nutzers
     * @param modelJson exportierte Modelldaten
     * @param focusInfo optionale Beschreibung des ausgewählten Elements, sonst null
     * @param settings  Einstellungen aus dem UI
     */
    Answer ask(String question, String modelJson, String focusInfo, AskSettings settings);

    /** Verwirft den bisherigen Chatverlauf (ein hochgeladenes Modell bleibt erhalten). */
    void resetConversation();

    /** Verwirft hochgeladene Modelle (Cache). Die nächste Frage lädt das Modell neu hoch. */
    default void discardUploadedModel() { }

    /** Ergebnis einer Anfrage inkl. Metadaten für die Auswertung. */
    class Answer {
        public final String text;
        public final String modelUsed;     // angefragte Modell-ID, die geantwortet hat (wichtig bei Fallback)
        public final boolean success;
        public final long durationMs;
        public final int promptTokens;     // -1 wenn unbekannt
        public final int answerTokens;     // -1 wenn unbekannt
        public final String finishReason;  // z. B. STOP, MAX_TOKENS

        /** Tatsächliche Modellversion laut Google (Aliase wie gemini-3.5-flash werden umgeleitet!). */
        public String modelVersion = "";
        /** "inline" = Modell in der Anfrage, "cache" = vorhandener Upload, "cache_new" = gerade hochgeladen. */
        public String transfer = "";
        /** Tokens, die aus einem Cache kamen (explizit oder automatisch), -1 wenn unbekannt. */
        public int cachedTokens = -1;
        /** Tokens, die das Modell intern zum "Nachdenken" verbraucht hat (-1 = unbekannt). Treiben die Antwortzeit. */
        public int thoughtsTokens = -1;
        /** Tatsächlich verwendete Denktiefe ("default", wenn das Modell die Einstellung abgelehnt hat). */
        public String thinkingUsed = "default";
        /** Sekunden, die wegen Rate-Limits gewartet wurde. */
        public int rateLimitWaitSec = 0;
        /** Tageslimit erreicht – weitere Anfragen heute sinnlos. */
        public boolean dailyLimit = false;
        /** Hinweis für die Anzeige, z. B. "Caching not available for this model". */
        public String note = "";

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
