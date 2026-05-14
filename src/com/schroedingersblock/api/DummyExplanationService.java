package com.schroedingersblock.api;

import com.nomagic.magicdraw.core.Application;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.schroedingersblock.core.SchroedingersBlockPlugin;
import com.nomagic.magicdraw.core.GUILog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;
import java.io.File;
import java.nio.file.Files;

public class DummyExplanationService implements ExplanationService {

    private static final String MODEL = "gemini-2.5-flash";

    // ─── Modus Konfiguration ───────────────────────────────────────────────────
    private static final int EXPERT_MAX_TOKENS = 9000; // Erhöht für tiefe Analysen
    private static final double EXPERT_TEMPERATURE = 0.0;

    private static final int BASIC_MAX_TOKENS = 1000;
    private static final double BASIC_TEMPERATURE = 0.0;

    // ─── Conversation State ────────────────────────────────────────────────────
    private final List<String> conversationHistory = new ArrayList<>();
    private String currentDetailLevel = "Basic"; // Standardwert

    @Override
    public void setDetailLevel(String level) {
        this.currentDetailLevel = level;
    }

    @Override
    public String fetchExplanation(Element targetElement, String mode) {
        String elementName = targetElement != null ? targetElement.getHumanName() : "Unknown element";
        String elementType = targetElement != null ? targetElement.getHumanType() : "Unknown type";
        String ownerName = (targetElement != null && targetElement.getOwner() != null)
                ? targetElement.getOwner().getHumanName()
                : "root";

        // DATENDURCHSTICH: Echte JSON-Datei von der Platte laden!
        String exportedJson = loadLatestExportedJson();

        String prompt = PromptLoader.buildPrompt(
                currentDetailLevel, elementName, elementType, ownerName, exportedJson);

        return callGemini(prompt, currentDetailLevel);
    }

    @Override
    public String fetchFollowUp(String question) {
        // Follow-Up Fragen nutzen immer den Expert-Modus
        return callGemini(question, currentDetailLevel);
    }

    // ─── Interne LLM API Logik ─────────────────────────────────────────────────

    private String callGemini(String userMessage, String mode) {
        GUILog log = Application.getInstance().getGUILog();
        Preferences prefs = Preferences.userNodeForPackage(SchroedingersBlockPlugin.class);
        String apiKey = prefs.get(SchroedingersBlockPlugin.API_KEY_PREF, "");

        if (apiKey.isEmpty()) {
            return "⚠️ Error: No API-Key found. Please restart the analysis and enter a valid key.";
        }

        try {
            String userJson = "{\"role\": \"user\", \"parts\": [{\"text\": \"" + escapeJson(userMessage) + "\"}]}";
            conversationHistory.add(userJson);

            boolean isExpert = "Expert".equalsIgnoreCase(mode);
            int maxTokens = isExpert ? EXPERT_MAX_TOKENS : BASIC_MAX_TOKENS;
            double temp = isExpert ? EXPERT_TEMPERATURE : BASIC_TEMPERATURE;

            String historyArray = String.join(",", conversationHistory);
            String requestBody = "{"
                    + "\"contents\": [" + historyArray + "],"
                    + "\"generationConfig\": {"
                    + "  \"temperature\": " + temp + ","
                    + "  \"maxOutputTokens\": " + maxTokens + ","
                    + "  \"topP\": 0.9"
                    + "}"
                    + "}";

            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent?key=" + apiKey;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            log.log("[Debug] Raw Json:\n" + response.body());

            if (response.statusCode() != 200) {
                return "⚠️ API error (HTTP " + response.statusCode() + "):\n" + response.body();
            }

            String responseText = extractTextFromJson(response.body());

            String modelJson = "{\"role\": \"model\", \"parts\": [{\"text\": \"" + escapeJson(responseText) + "\"}]}";
            conversationHistory.add(modelJson);

            log.log(responseText);

            return responseText;

        } catch (Exception e) {
            return "❌ Internal error: " + e.getMessage();
        }
    }

    // ─── JSON Datendurchstich Logik ────────────────────────────────────────────

    private String loadLatestExportedJson() {
        try {
            String pluginId = "com.schroedingersblock.plugin";
            File pluginDir = new File(System.getProperty("user.dir"), "plugins" + File.separator + pluginId);
            File exportDir = new File(pluginDir, "data_extraction" + File.separator + "raw_model_data");

            if (!exportDir.exists() || !exportDir.isDirectory()) {
                return "Error: Export directory not found at " + exportDir.getAbsolutePath();
            }

            File[] files = exportDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (files == null || files.length == 0) {
                return "Error: No model JSON files found.";
            }

            File newestFile = files[0];
            for (File f : files) {
                if (f.lastModified() > newestFile.lastModified()) {
                    newestFile = f;
                }
            }

            return Files.readString(newestFile.toPath());

        } catch (Exception e) {
            return "Error reading exported model data: " + e.getMessage();
        }
    }

    // ─── JSON Hilfsmethoden ────────────────────────────────────────────────────

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String extractTextFromJson(String json) {
        try {
            String marker = "\"text\": \"";
            int start = json.indexOf(marker);
            if (start == -1) return "Could not find text in response.";

            start += marker.length();
            int end = json.indexOf("\"", start);

            while (end != -1 && json.charAt(end - 1) == '\\') {
                end = json.indexOf("\"", end + 1);
            }

            if (end == -1) return "Error parsing response.";

            return json.substring(start, end).replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
        } catch (Exception e) {
            return "Error extracting text from response.";
        }
    }
}