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
     * @param modelJson exportierte Modelldaten (Inhalt im Format settings.format: JSON oder SysML-v2-Text)
     * @param focusInfo optionale Beschreibung des ausgewählten Elements, sonst null
     * @param settings  Einstellungen aus dem UI
     */
    Answer ask(String question, String modelJson, String focusInfo, AskSettings settings);

    /** Verwirft den bisherigen Chatverlauf (ein hochgeladenes Modell bleibt erhalten). */
    void resetConversation();

    /** Verwirft hochgeladene Modelle (Cache). Die nächste Frage lädt das Modell neu hoch. */
    default void discardUploadedModel() { }

    /** Bricht die laufende Anfrage ab (NFR4). */
    default void cancel() { }

    /** Anzahl bisheriger Frage/Antwort-Paare im aktuellen Chat. */
    default int previousTurns() { return 0; }

    /** Klartext dessen, was mit der nächsten Frage gesendet würde (FR3, NFR3). */
    RequestPreview preview(String question, String modelContent, String focusInfo, AskSettings settings);

    /** Alles, was mit der nächsten Frage an die KI ginge – im Klartext. */
    class RequestPreview {
        public String systemPrompt;
        public String systemPromptSource;
        public String modelMessage;
        public java.util.List<String> history = new java.util.ArrayList<>();   // abwechselnd Frage, Antwort
        public String questionMessage;
        public String generationConfig;
        public String transferNote;
    }

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
        /** Insgesamt verbrauchte Tokens laut Google (totalTokenCount: Eingabe + Antwort + Denken + Suche). */
        public int totalTokens = -1;
        /** Tokens der Google-Suche bei Grounding (toolUsePromptTokenCount). */
        public int toolTokens = -1;
        /** Tatsächlich verwendete Denktiefe ("default", wenn das Modell die Einstellung abgelehnt hat). */
        public String thinkingUsed = "default";
        /** Sekunden, die wegen Rate-Limits gewartet wurde. */
        public int rateLimitWaitSec = 0;
        /** Tageslimit erreicht – weitere Anfragen heute sinnlos. */
        public boolean dailyLimit = false;
        /** Hinweis für die Anzeige, z. B. "Caching not available for this model". */
        public String note = "";
        /** Exakter Text der Frage-Nachricht (inkl. ausgewähltem Element, falls mitgeschickt). */
        public String userMessage = "";
        /** Frage/Antwort-Paare, die vor dieser Frage im Chat standen (0 = neuer Chat). */
        public int previousTurns = 0;

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
