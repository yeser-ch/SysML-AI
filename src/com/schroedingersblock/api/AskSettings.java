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
    public String scope = "FULL_MODEL";      // nur fürs Log
}
