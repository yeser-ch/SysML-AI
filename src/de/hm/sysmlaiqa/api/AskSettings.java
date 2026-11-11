package de.hm.sysmlaiqa.api;

/**
 * Alle Einstellungen einer Anfrage – kommen direkt aus dem "Ask AI about the model"-Fenster.
 * Werden pro Antwort ins Evaluations-Log geschrieben, damit Versuche reproduzierbar sind.
 */
public class AskSettings {
    public String model = "gemini-3.8-flash";
    public double temperature = 0.0;
    public int maxOutputTokens = 8192;
    public boolean grounding = false;        // Google-Suche (verfälscht Evaluation, daher Standard aus)
    public boolean freshChat = true;         // jede Frage in einem neuen Chat (unabhängige Antworten)
    public boolean allowFallback = false;    // bei Überlastung automatisch anderes Modell nutzen
    public boolean requireIds = false;       // KI soll Element-IDs als [id:...] angeben (Halluzinations-Check)
    public int repetitions = 1;              // wie oft jede Frage gestellt wird (Konsistenz)
    /** Denktiefe des Modells: "default", "minimal", "low", "medium", "high". Weniger = schneller, evtl. schlechter. */
    public String thinkingLevel = "default";
    public boolean cacheModel = true;        // Modell einmal hochladen (Gemini-Cache) statt bei jeder Frage mitschicken
    public int max429WaitSec = 30;           // bei Rate-Limit (pro Minute) höchstens so lange auf Google warten
    public int max429Retries = 2;            // so oft nach einem Rate-Limit erneut versuchen
    public String scope = "FULL_MODEL";      // nur fürs Log: FULL_MODEL, SELECTION, FOCUS_NEIGHBOURHOOD
    /** Format der Modelldaten: ModelExport.FORMAT_V1 (JSON) oder FORMAT_V2 (SysML-v2-Text). Bestimmt auch den System-Prompt. */
    public String format = ModelExport.FORMAT_V1;

    public AskSettings copy() {
        AskSettings c = new AskSettings();
        c.model = model;
        c.temperature = temperature;
        c.maxOutputTokens = maxOutputTokens;
        c.grounding = grounding;
        c.freshChat = freshChat;
        c.allowFallback = allowFallback;
        c.requireIds = requireIds;
        c.repetitions = repetitions;
        c.cacheModel = cacheModel;
        c.thinkingLevel = thinkingLevel;
        c.max429WaitSec = max429WaitSec;
        c.max429Retries = max429Retries;
        c.scope = scope;
        c.format = format;
        return c;
    }
}
