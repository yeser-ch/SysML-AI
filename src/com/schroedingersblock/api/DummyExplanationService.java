package com.schroedingersblock.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.schroedingersblock.core.SchroedingersBlockPlugin;

public class DummyExplanationService implements ExplanationService {

    private static final String MODEL = "gemini-2.5-flash";

    // ─── Modus Konfiguration ───────────────────────────────────────────────────
    private static final int EXPERT_MAX_TOKENS = 8192; // Max-Limit für detaillierte Antworten
    private static final double EXPERT_TEMPERATURE = 0.2;

    private static final int BASIC_MAX_TOKENS = 800;
    private static final double BASIC_TEMPERATURE = 0.3;

    // ─── Conversation State ────────────────────────────────────────────────────
    private final List<String> conversationHistory = new ArrayList<>();
    private String currentDetailLevel = "Basic"; // Standardwert

    @Override
    public void setDetailLevel(String level) {
        this.currentDetailLevel = level;
        System.out.println("[Service] Detail level set to: " + level);
    }

    @Override
    public String fetchExplanation(Element targetElement, String mode) {
        // Element-Informationen für den Prompt extrahieren
        String elementName = (targetElement != null) ? targetElement.getHumanName() : "Unknown element";
        String elementType = (targetElement != null) ? targetElement.getHumanType() : "Unknown type";
        String ownerName = (targetElement != null && targetElement.getOwner() != null)
                ? targetElement.getOwner().getHumanName()
                : "root";

        // TODO: Später durch  JSON-Export der DataExtractionService ersetzen
        String exportedXml = "No XML/JSON export available yet.";

        // Prompt dynamisch über den externen Loader laden und mit Werten füllen
        String initialPrompt = PromptLoader.buildPrompt(
                currentDetailLevel, elementName, elementType, ownerName, exportedXml);

        // Anfrage an die KI senden (mit dem ausgewählten Level)
        return callGemini(initialPrompt, currentDetailLevel);
    }

    @Override
    public String fetchFollowUp(String question) {
        // Follow-Up Fragen nutzen immer den EXPERT-Modus (für tiefgreifende technische Konversationen)
        return callGemini(question, "Expert");
    }

    // ─── Interne LLM API Logik ─────────────────────────────────────────────────

    private String callGemini(String userMessage, String detailLevel) {
        // API-Key sicher aus den lokalen Preferences laden
        Preferences prefs = Preferences.userNodeForPackage(SchroedingersBlockPlugin.class);
        String apiKey = prefs.get(SchroedingersBlockPlugin.API_KEY_PREF, "");

        if (apiKey.isEmpty()) {
            return "⚠️ Error: No API-Key found. Please restart the analysis and enter a valid key.";
        }

        try {
            // 1. JSON für die User-Nachricht bauen und speichern
            String userJson = "{\"role\": \"user\", \"parts\": [{\"text\": \"" + escapeJson(userMessage) + "\"}]}";
            conversationHistory.add(userJson);

            // 2. Dynamische Konfiguration basierend auf dem Detail-Level wählen
            boolean isExpert = "Expert".equalsIgnoreCase(detailLevel);
            int maxTokens = isExpert ? EXPERT_MAX_TOKENS : BASIC_MAX_TOKENS;
            double temp = isExpert ? EXPERT_TEMPERATURE : BASIC_TEMPERATURE;

            // 3. Request-Body zusammenbauen
            String historyArray = String.join(",", conversationHistory);
            String requestBody = "{" +
                    "\"contents\": [" + historyArray + "]," +
                    "\"generationConfig\": {" +
                    "  \"temperature\": " + temp + "," +
                    "  \"maxOutputTokens\": " + maxTokens + "," +
                    "  \"topP\": 0.9" +
                    "}" +
                    "}";

            // 4. HTTP Request konfigurieren
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent?key=" + apiKey;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            // 5. Client öffnen und Request senden
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // 6. Fehlerbehandlung
            if (response.statusCode() != 200) {
                return "⚠️ API error (HTTP " + response.statusCode() + "):\n" + response.body();
            }

            // 7. Text extrahieren
            String responseText = extractTextFromJson(response.body());

            // 8. Antwort der KI im Verlauf speichern
            String modelJson = "{\"role\": \"model\", \"parts\": [{\"text\": \"" + escapeJson(responseText) + "\"}]}";
            conversationHistory.add(modelJson);

            return responseText;

        } catch (Exception e) {
            System.err.println("[Schrödingers Block] Error calling Gemini: " + e.getMessage());
            return "❌ Internal error: " + e.getMessage();
        }
    }

    // ─── JSON Hilfsmethoden ────────────────────────────────────────────────────

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String extractTextFromJson(String json) {
        try {
            String searchString = "\"text\": \"";
            int startIndex = json.indexOf(searchString);
            if (startIndex == -1) return "Could not find text in response.";

            startIndex += searchString.length();
            int endIndex = json.indexOf("\"", startIndex);

            // Escapede Anführungszeichen überspringen
            while (endIndex != -1 && json.charAt(endIndex - 1) == '\\') {
                endIndex = json.indexOf("\"", endIndex + 1);
            }

            if (endIndex == -1) return "Error parsing response.";

            String extracted = json.substring(startIndex, endIndex);
            return extracted.replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        } catch (Exception e) {
            return "Error extracting text from response.";
        }
    }
}