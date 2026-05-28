package com.schroedingersblock.api;

import com.nomagic.magicdraw.core.Application;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.schroedingersblock.core.SchroedingersBlockPlugin;
import com.nomagic.magicdraw.core.GUILog;
import com.nomagic.magicdraw.plugins.Plugin;
import com.nomagic.magicdraw.plugins.PluginDescriptor;
import com.nomagic.magicdraw.plugins.PluginUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;
import java.io.File;
import java.nio.file.Files;

public class GeminiExplanationService implements ExplanationService {

    private static final String MODEL = "gemini-2.5-flash";

    // ─── Modus Konfiguration ───────────────────────────────────────────────────
    private static final int EXPERT_MAX_TOKENS = 9000;
    private static final double EXPERT_TEMPERATURE = 0.0;

    private static final int BASIC_MAX_TOKENS = 3000;
    private static final double BASIC_TEMPERATURE = 0.0;

    // ─── Conversation State ────────────────────────────────────────────────────
    private final List<String> conversationHistory = new ArrayList<>();
    private String currentDetailLevel = "Basic";

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

        GUILog log = Application.getInstance().getGUILog();
        log.log("[GeminiExplanationService] Starte Analyse fuer Element: '" + elementName + "' [" + elementType + "]");
        log.log("[GeminiExplanationService] Detail-Level: " + currentDetailLevel);

        // Echte JSON-Datei von der Platte laden
        String exportedJson = loadLatestExportedJson();

        if (exportedJson.startsWith("Error")) {
            log.log("[GeminiExplanationService] Fehler beim Lesen der lokalen Modelldaten: " + exportedJson);
        } else {
            double jsonKb = exportedJson.length() / 1024.0;
            log.log("[GeminiExplanationService] Lokale JSON erfolgreich geladen. Groesse: " + String.format("%.2f", jsonKb) + " KB");
        }

        // Prompt mit PromptLoader zusammenbauen
        String prompt = PromptLoader.buildPrompt(
                currentDetailLevel, elementName, elementType, ownerName, exportedJson);

        double promptKb = prompt.length() / 1024.0;
        log.log("[GeminiExplanationService] System-Prompt generiert. Payload-Gesamtgroesse (inkl. JSON): " + String.format("%.2f", promptKb) + " KB");

        return callGemini(prompt, currentDetailLevel);
    }

    @Override
    public String fetchFollowUp(String question) {
        GUILog log = Application.getInstance().getGUILog();
        log.log("[GeminiExplanationService] Sende Follow-Up Frage an das Modell...");
        return callGemini(question, currentDetailLevel);
    }

    // ─── Interne LLM API Logik ─────────────────────────────────────────────────

    private String callGemini(String userMessage, String mode) {
        GUILog log = Application.getInstance().getGUILog();
        Preferences prefs = Preferences.userNodeForPackage(SchroedingersBlockPlugin.class);
        String apiKey = prefs.get(SchroedingersBlockPlugin.API_KEY_PREF, "");

        if (apiKey.isEmpty()) {
            log.log("[GeminiExplanationService] Error: No API key found in settings.");
            return "⚠️ Error: No API key found. Please restart the analysis and enter a valid key.";
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

            log.log("[GeminiExplanationService] Sending HTTP POST to Gemini API...");
            long startTime = System.currentTimeMillis();

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            long duration = System.currentTimeMillis() - startTime;

            // --- Refined HTTP Error Handling (Fully in English & Context-Specific) ---
            if (response.statusCode() != 200) {
                log.log("[GeminiExplanationService] API request failed with HTTP status: " + response.statusCode());
                String cleanError = extractErrorMessageFromJson(response.body());

                // CASE 1: True authentication/invalid key error (400 Bad Request / 403 Forbidden)
                if (response.statusCode() == 400 || response.statusCode() == 403) {
                    prefs.put(SchroedingersBlockPlugin.API_KEY_PREF, "");
                    log.log("[GeminiExplanationService] Info: Invalid API key has been automatically reset.");

                    return "⚠️ API error (HTTP " + response.statusCode() + "):\n" + cleanError
                            + "\n\n(The API key was invalid and has been reset. Please reopen the analysis window to enter a valid key.)";
                }

                // CASE 2: Quota exceeded or temporary server issues (429 Rate Limit, 503 Overloaded, etc.)
                else {
                    log.log("[GeminiExplanationService] Info: Temporary server error or quota limit reached. Key remains saved.");

                    return "⚠️ API error (HTTP " + response.statusCode() + "):\n" + cleanError
                            + "\n\n(This is a temporary issue or rate limit. Please wait a moment and try again.)";
                }
            }

            log.log("[GeminiExplanationService] API response received successfully (HTTP 200 OK | Duration: " + duration + "ms)");

            String responseText = extractTextFromJson(response.body());
            String modelJson = "{\"role\": \"model\", \"parts\": [{\"text\": \"" + escapeJson(responseText) + "\"}]}";
            conversationHistory.add(modelJson);

            return responseText;

        } catch (Exception e) {
            // --- Network and connection error handling ---
            log.log("[GeminiExplanationService] Internal connection error: " + e.toString());

            String friendlyNotice = (e.getMessage() != null && !e.getMessage().equals("null")) ? e.getMessage() : e.getClass().getSimpleName();

            return "❌ Connection Error: No internet connection or the server is unreachable (" + friendlyNotice + ").\n"
                    + "Please check your network connection and try again.";
        }
    }

    // ─── JSON Datendurchstich Logik ────────────────────────────────────────────

    private String loadLatestExportedJson() {
        try {
            File pluginDir = getBaseDirectory();
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

    @SuppressWarnings("DuplicatedCode")
    private File getBaseDirectory() {
        String PLUGIN_ID = "com.schroedingersblock.plugin";
        try {
            for (Plugin plugin : PluginUtils.getPlugins()) {
                PluginDescriptor descriptor = plugin.getDescriptor();
                if (descriptor != null && PLUGIN_ID.equals(descriptor.getID())) {
                    return descriptor.getPluginDirectory();
                }
            }
        } catch (Throwable t) {
            // Ignoriere Fehler
        }
        return new File(System.getProperty("user.dir"), "plugins" + File.separator + PLUGIN_ID);
    }

    // ─── JSON Hilfsmethoden ────────────────────────────────────────────────────

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String extractTextFromJson(String json) {
        try {
            StringBuilder fullText = new StringBuilder();
            String marker = "\"text\": \"";
            int currentPos = 0;

            // Schleife über alle "text": " Fragmente im JSON
            while (true) {
                int start = json.indexOf(marker, currentPos);
                if (start == -1) {
                    break; // Keine weiteren Text-Blöcke gefunden
                }
                start += marker.length();

                int end = json.indexOf("\"", start);
                while (end != -1 && json.charAt(end - 1) == '\\') {
                    // Robustheits-Check: Zähle Backslashes direkt vor der Quote
                    int backslashCount = 0;
                    int i = end - 1;
                    while (i >= start && json.charAt(i) == '\\') {
                        backslashCount++;
                        i--;
                    }
                    // Wenn ungerade, ist es eine unvollständige escaped Quote (\") -> weitersuchen
                    if (backslashCount % 2 == 1) {
                        end = json.indexOf("\"", end + 1);
                    } else {
                        break; // Gerade Anzahl bedeutet, die Quote beendet den String (z.B. \\")
                    }
                }

                if (end == -1) break;

                // Text-Fragment extrahieren und parsen
                String partText = json.substring(start, end)
                        .replace("\\n", "\n")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                        .replace("\\t", "\t");

                fullText.append(partText);

                // Suche ab der aktuellen Position im nächsten Schleifendurchlauf weiter
                currentPos = end + 1;
            }

            if (fullText.length() == 0) {
                return "Could not find text in response.";
            }

            return fullText.toString();
        } catch (Exception e) {
            return "Error extracting text from response.";
        }
    }

    private String extractErrorMessageFromJson(String json) {
        try {
            String marker = "\"message\": \"";
            int start = json.indexOf(marker);
            if (start == -1) return json;

            start += marker.length();
            int end = json.indexOf("\"", start);

            while (end != -1 && json.charAt(end - 1) == '\\') {
                end = json.indexOf("\"", end + 1);
            }

            if (end == -1) return json;

            return json.substring(start, end).replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
        } catch (Exception e) {
            return json;
        }
    }
}