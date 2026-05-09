package com.schroedingersblock.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;

public class DummyExplanationService implements ExplanationService {

    // ─── Configuration ────────────────────────────────────────────────────────
    // TODO: move API_KEY to a config file or environment variable — never commit it
    private static final String API_KEY = "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX";
    private static final String MODEL   = "gemini-2.5-flash";
    private static final String API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/"
            + MODEL + ":generateContent?key=" + API_KEY;

    // ─── Mode configuration ───────────────────────────────────────────────────
    private static final int    EXPERT_MAX_TOKENS = 2048;
    private static final double EXPERT_TEMPERATURE = 0.2;

    private static final int    BASIC_MAX_TOKENS  = 800;
    private static final double BASIC_TEMPERATURE  = 0.3;

    // ─── Conversation state ───────────────────────────────────────────────────
    private final List<String> conversationHistory = new ArrayList<>();

    // ─── ExplanationService interface ─────────────────────────────────────────

    @Override
    public String fetchExplanation(Element targetElement, String mode) {

        String elementName = targetElement != null
                ? targetElement.getHumanName()
                : "Unknown element";

        String elementType = targetElement != null
                ? targetElement.getHumanType()
                : "Unknown type";

        String ownerName = (targetElement != null && targetElement.getOwner() != null)
                ? targetElement.getOwner().getHumanName()
                : "root";

        // TODO: replace with real Cameo XML export when available
        String exportedXml = "No XML export available yet.";

        String prompt = PromptLoader.buildPrompt(
                mode, elementName, elementType, ownerName, exportedXml);

        return callGemini(prompt, mode);
    }

    @Override
    public String fetchFollowUp(String question) {
        // Follow-up messages use EXPERT config — they are part of a technical conversation
        return callGemini(question, "EXPERT");
    }

    // ─── Internal LLM call ────────────────────────────────────────────────────

    private String callGemini(String userMessage, String mode) {
        try {
            // 1. Add user message to history
            String userJson = buildMessageJson("user", userMessage);
            conversationHistory.add(userJson);

            // 2. Build request body with mode-specific config
            String requestBody = buildRequestBody(mode);

            // 3. Send HTTP POST
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            // 4. Handle non-200 responses
            if (response.statusCode() != 200) {
                return "⚠️ API error (HTTP " + response.statusCode() + "):\n" + response.body();
            }

            // 5. Extract response text
            String responseText = extractTextFromJson(response.body());

            // 6. Save model response to conversation history
            conversationHistory.add(buildMessageJson("model", responseText));

            return responseText;

        } catch (Exception e) {
            System.err.println("[Schrödingers Block] Error calling Gemini: " + e.getMessage());
            return "❌ Internal error: " + e.getMessage();
        }
    }

    // ─── Request builders ─────────────────────────────────────────────────────

    private String buildRequestBody(String mode) {
        boolean isExpert  = mode.equalsIgnoreCase("EXPERT");
        int     maxTokens = isExpert ? EXPERT_MAX_TOKENS : BASIC_MAX_TOKENS;
        double  temp      = isExpert ? EXPERT_TEMPERATURE : BASIC_TEMPERATURE;

        String historyArray = String.join(",", conversationHistory);

        return "{"
                + "\"contents\": [" + historyArray + "],"
                + "\"generationConfig\": {"
                + "  \"temperature\": " + temp + ","
                + "  \"maxOutputTokens\": " + maxTokens + ","
                + "  \"topP\": 0.9"
                + "}"
                + "}";
    }

    private String buildMessageJson(String role, String text) {
        return "{\"role\": \"" + role + "\", "
                + "\"parts\": [{\"text\": \"" + escapeJson(text) + "\"}]}";
    }

    // ─── JSON utilities ───────────────────────────────────────────────────────

    /**
     * Minimal JSON string escaper.
     * NOTE: replace with Gson or Jackson once XML context is passed —
     * XML contains characters that will corrupt hand-rolled JSON.
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String extractTextFromJson(String json) {
        try {
            String marker = "\"text\": \"";
            int start = json.indexOf(marker);
            if (start == -1) return "Could not find text in response.";

            start += marker.length();
            int end = json.indexOf("\"", start);

            // Skip escaped quotes
            while (end != -1 && json.charAt(end - 1) == '\\') {
                end = json.indexOf("\"", end + 1);
            }

            if (end == -1) return "Error parsing response.";

            return json.substring(start, end)
                    .replace("\\n",  "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");

        } catch (Exception e) {
            return "Error extracting text from response.";
        }
    }
}
