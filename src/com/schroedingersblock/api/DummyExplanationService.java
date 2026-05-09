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
    private final List<String> conversationHistory = new ArrayList<>();

    @Override
    public String fetchExplanation(Element targetElement, String mode) {
        // Hier nutzen wir das targetElement, um den Namen des Blocks zu bekommen
        String elementName = (targetElement != null) ? targetElement.getHumanName() : "Unbekanntes Element";

        String initialPrompt = "Du bist ein SysML-Experte. Analysiere das Element '" + elementName + "' im Modus: " + mode + ".\n" +
                "Bitte erkläre kurz, was es im Kontext der Systemarchitektur bedeutet.";

        return callGemini(initialPrompt);
    }

    @Override
    public String fetchFollowUp(String question) {
        return callGemini(question);
    }

    private String callGemini(String userMessage) {
        // --- NEU: API-Key dynamisch aus den Preferences laden ---
        Preferences prefs = Preferences.userNodeForPackage(SchroedingersBlockPlugin.class);
        String apiKey = prefs.get(SchroedingersBlockPlugin.API_KEY_PREF, "");

        if (apiKey.isEmpty()) {
            return "⚠️ Error: No API-Key found. Please restart the analysis and enter a valid key.";
        }
        // ---------------------------------------------------------

        try {
            // 1. JSON für die Nachricht bauen
            String userJson = "{\"role\": \"user\", \"parts\": [{\"text\": \"" + escapeJson(userMessage) + "\"}]}";
            conversationHistory.add(userJson);

            // 2. Request-Body zusammenbauen
            String historyArray = String.join(",", conversationHistory);
            String requestBody = "{" +
                    "\"contents\": [" + historyArray + "]," +
                    "\"generationConfig\": {" +
                    "  \"temperature\": 0.7," +
                    "  \"maxOutputTokens\": 8192" +
                    "}" +
                    "}";

            // 3. HTTP Request konfigurieren
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent?key=" + apiKey;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            // 4. Client öffnen und senden (Normal, OHNE den extra try-Block!)
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "⚠️ Fehler von der KI (HTTP " + response.statusCode() + "):\n" + response.body();
            }

            // 5. Text extrahieren
            String responseText = extractTextFromJson(response.body());

            // 6. Antwort im Verlauf speichern
            String modelJson = "{\"role\": \"model\", \"parts\": [{\"text\": \"" + escapeJson(responseText) + "\"}]}";
            conversationHistory.add(modelJson);

            return responseText;

        } catch (Exception e) {
            System.err.println("[Schrödingers Block] Fehler: " + e.getMessage());
            return "❌ Interner Fehler: " + e.getMessage();
        }
    }

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
            if (startIndex == -1) return "Konnte Text nicht finden.";

            startIndex += searchString.length();
            int endIndex = json.indexOf("\"", startIndex);

            while (endIndex != -1 && json.charAt(endIndex - 1) == '\\') {
                endIndex = json.indexOf("\"", endIndex + 1);
            }

            if (endIndex == -1) return "Fehler beim Parsen der Antwort.";

            String extracted = json.substring(startIndex, endIndex);
            return extracted.replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        } catch (Exception e) {
            return "Fehler beim Text-Extrakt.";
        }
    }
}
