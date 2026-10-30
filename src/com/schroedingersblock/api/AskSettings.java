package com.schroedingersblock.api;

/**
 * Alle Einstellungen einer Anfrage – kommen direkt aus dem "Ask the model"-Fenster.
 * Werden pro Antwort ins Evaluations-Log geschrieben, damit Versuche reproduzierbar sind.
 */
public class AskSettings {
    public String model = "gemini-3.5-flash";
    public double temperature = 0.0;
    public int maxOutputTokens = 8192;
    public String detailLevel = "Basic";     // "Basic" oder "Expert"
    public boolean grounding = false;        // Google-Suche (verfälscht Evaluation, daher Standard aus)
    public boolean freshChat = true;         // jede Frage in einem neuen Chat (unabhängige Antworten)
    public boolean allowFallback = false;    // bei Überlastung automatisch anderes Modell nutzen
    public boolean requireIds = false;       // KI soll Element-IDs als [id:...] angeben (Halluzinations-Check)
    public int repetitions = 1;              // wie oft jede Frage gestellt wird (Konsistenz)
    public String scope = "FULL_MODEL";      // nur fürs Log

    public AskSettings copy() {
        AskSettings c = new AskSettings();
        c.model = model;
        c.temperature = temperature;
        c.maxOutputTokens = maxOutputTokens;
        c.detailLevel = detailLevel;
        c.grounding = grounding;
        c.freshChat = freshChat;
        c.allowFallback = allowFallback;
        c.requireIds = requireIds;
        c.repetitions = repetitions;
        c.scope = scope;
        return c;
    }
}
