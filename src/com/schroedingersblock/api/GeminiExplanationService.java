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

    private static final int BASIC_MAX_TOKENS = 1000;
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
            log.log("[GeminiExplanationService] Fehler: Kein API-Schluessel in den Einstellungen hinterlegt.");
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

            log.log("[GeminiExplanationService] Sende HTTP-POST an Gemini-Schnittstelle...");

            // Zeitmessung für Performance-Überwachung starten
            long startTime = System.currentTimeMillis();

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            long duration = System.currentTimeMillis() - startTime;

            // Fehler-Status abfangen (z.B. 429 Quota)
            if (response.statusCode() != 200) {
                log.log("[GeminiExplanationService] API-Antwort fehlgeschlagen mit HTTP-Status: " + response.statusCode() + " (Dauer: " + duration + "ms)");
                String cleanError = extractErrorMessageFromJson(response.body());
                return "⚠️ API error (HTTP " + response.statusCode() + "):\n" + cleanError;
            }

            log.log("[GeminiExplanationService] API-Antwort erfolgreich empfangen (HTTP 200 OK | Dauer: " + duration + "ms)");

            String responseText = extractTextFromJson(response.body());
            String modelJson = "{\"role\": \"model\", \"parts\": [{\"text\": \"" + escapeJson(responseText) + "\"}]}";
            conversationHistory.add(modelJson);

            return responseText;

        } catch (Exception e) {
            log.log("[GeminiExplanationService] Interner Verbindungsfehler: " + e.getMessage());
            return "❌ Internal error: " + e.getMessage();
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