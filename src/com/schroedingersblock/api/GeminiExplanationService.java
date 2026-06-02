package com.schroedingersblock.api;

import com.nomagic.magicdraw.core.Application;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.schroedingersblock.core.SchroedingersBlockPlugin;
import com.nomagic.magicdraw.plugins.Plugin;
import com.nomagic.magicdraw.plugins.PluginDescriptor;
import com.nomagic.magicdraw.plugins.PluginUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;
import java.io.File;
import java.nio.file.Files;

public class GeminiExplanationService implements ExplanationService {

    // ─── PERFORMANCE-BOOST: Globaler HttpClient für Connection Keep-Alive ──────
    private final HttpClient httpClient = HttpClient.newHttpClient();

    // ─── Modell-Kaskade (Bereinigte IDs aus eurer AI Studio Übersicht) ──────────
    private static final String[] MODELS = {
            "gemini-2.5-flash",       // Primäres Standardmodell
            "gemini-3.1-flash-lite",  // Schneller Retter bei Quota-Limits
            "gemini-2.5-flash-lite",  // Offizielles Flash-Lite Modell
            "gemini-1.5-flash" // Zuverlässiger, klassischer Fallback-Knoten
    };
    private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    // Statisch, damit der Schalter-Zustand für die gesamte Sitzung erhalten bleibt
    private static final CircuitBreaker CIRCUIT = new CircuitBreaker();

    // ─── Circuit Breaker Pattern (Leitungsschutzschalter) ──────────────────────
    private static class CircuitBreaker {
        private final Map<String, Integer> failureCounts = new HashMap<>();
        private final Map<String, Long>  retryAtMap    = new HashMap<>();

        // Prüft thread-sicher, ob die dynamische Sperre eines Modells abgelaufen ist
        synchronized boolean isAvailable(String model) {
            long retryAt = retryAtMap.getOrDefault(model, 0L);
            return System.currentTimeMillis() >= retryAt;
        }

        synchronized void recordSuccess(String model) {
            failureCounts.remove(model);
            retryAtMap.remove(model);
        }

        synchronized void recordFailure(String model) {
            int failures = failureCounts.getOrDefault(model, 0) + 1;
            failureCounts.put(model, failures);

            // Dynamische Berechnung: 1. Fehler = 5s, 2. Fehler = 15s, 3. Fehler = 45s...
            long lockDurationMs = (long) Math.pow(3, failures - 1) * 5 * 1000;

            // Deckelung bei maximal 5 Minuten, um ein Modell im Free-Tier nicht ewig zu blockieren
            if (lockDurationMs > 5 * 60 * 1000) {
                lockDurationMs = 5 * 60 * 1000;
            }

            retryAtMap.put(model, System.currentTimeMillis() + lockDurationMs);
            // Konsolen-Log für das Teampanel beim Debuggen
            System.out.println("[CircuitBreaker] " + model + " temporaer gesperrt fuer " + (lockDurationMs / 1000) + " Sekunden.");
        }
    }

    // ─── Modus & System-Konfiguration ──────────────────────────────────────────
    private static final int EXPERT_MAX_TOKENS = 9000;
    private static final double EXPERT_TEMPERATURE = 0.0;

    private static final int BASIC_MAX_TOKENS = 3000;
    private static final double BASIC_TEMPERATURE = 0.0;

    // Schutzleitplanken NUR für die erste Analyse (Schützt vor Halluzinationen)
    private static final String SYSTEM_INSTRUCTION =
            "You are a SysML model analysis assistant embedded in CATIA Magic Systems. "
                    + "ONLY answer questions about SysML, systems engineering, model elements, "
                    + "ports, flows, constraints, requirements, and the current model under analysis. "
                    + "If the user asks about ANYTHING unrelated to SysML or systems engineering, "
                    + "respond with exactly: \"Fix your Attention Span! Back to the Model!\" "
                    + "IMPORTANT: Always finish your last sentence completely. "
                    + "Never stop mid-sentence. If you are running out of space, wrap up your "
                    + "answer early but always end on a complete sentence with a full stop.";

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

        log("Starte Analyse fuer Element: '" + elementName + "' [" + elementType + "]");
        log("Detail-Level: " + currentDetailLevel);

        String exportedJson = loadLatestExportedJson();

        if (exportedJson.startsWith("Error")) {
            log("Fehler beim Lesen der lokalen Modelldaten: " + exportedJson);
        } else {
            double jsonKb = exportedJson.length() / 1024.0;
            log("Lokale JSON erfolgreich geladen. Groesse: " + String.format("%.2f", jsonKb) + " KB");
        }

        String prompt = PromptLoader.buildPrompt(
                currentDetailLevel, elementName, elementType, ownerName, exportedJson);

        double promptKb = prompt.length() / 1024.0;
        log("System-Prompt generiert. Payload-Gesamtgroesse (inkl. JSON): " + String.format("%.2f", promptKb) + " KB");

        // WICHTIG: 'true' signalisiert die initiale Analyse mit Leitplanken
        return callGemini(prompt, currentDetailLevel, true);
    }

    @Override
    public String fetchFollowUp(String question) {
        log("Sende Follow-Up Frage an das Modell...");
        // WICHTIG: 'false' signalisiert ein freies Follow-Up OHNE Leitplanken-Filter
        return callGemini(question, currentDetailLevel, false);
    }

    // ─── Interne LLM API Logik mit Circuit Breaker Kaskade ─────────────────────

    private String callGemini(String userMessage, String mode, boolean isInitial) {
        Preferences prefs = Preferences.userNodeForPackage(SchroedingersBlockPlugin.class);
        String apiKey = prefs.get(SchroedingersBlockPlugin.API_KEY_PREF, "");

        if (apiKey.isEmpty()) {
            log("Error: No API key found in settings.");
            return "⚠️ Error: No API key found. Please restart the analysis and enter a valid key.";
        }

        String userJson = "{\"role\": \"user\", \"parts\": [{\"text\": \"" + escapeJson(userMessage) + "\"}]}";
        conversationHistory.add(userJson);

        boolean isExpert = "Expert".equalsIgnoreCase(mode);
        int maxTokens = isExpert ? EXPERT_MAX_TOKENS : BASIC_MAX_TOKENS;
        double temp = isExpert ? EXPERT_TEMPERATURE : BASIC_TEMPERATURE;

        String historyArray = String.join(",", conversationHistory);

        // DYNAMISCHE SYSTEM INSTRUCTION: Nur beim ersten Aufruf mitsenden!
        String systemInstructionPart = "";
        if (isInitial) {
            systemInstructionPart = "\"systemInstruction\": {\"parts\": [{\"text\": \"" + escapeJson(SYSTEM_INSTRUCTION) + "\"}]},";
        }

        // KORREKTUR: systemInstructionPart wird jetzt korrekt eingebunden und tools (Google Search) bleibt aktiv
        String requestBody = "{"
                + systemInstructionPart
                + "\"tools\": [{\"google_search\": {}}],"
                + "\"contents\": [" + historyArray + "],"
                + "\"generationConfig\": {"
                + "  \"temperature\": " + temp + ","
                + "  \"maxOutputTokens\": " + maxTokens + ","
                + "  \"topP\": 0.9"
                + "}"
                + "}";

        // --- Iteriere über die Kaskade der verfügbaren Modelle ---
        for (String model : MODELS) {
            if (!CIRCUIT.isAvailable(model)) {
                log("Skipping " + model + " (Circuit is OPEN due to previous failure)");
                continue;
            }

            String url = API_BASE + model + ":generateContent?key=" + apiKey;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(java.time.Duration.ofSeconds(3)) // 3s Timeout gegen Kaskaden-Lags
                    .build();

            log("Sending HTTP POST to " + model + "...");
            long startTime = System.currentTimeMillis();

            try {
                HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                long duration = System.currentTimeMillis() - startTime;

                if (response.statusCode() == 200) {
                    CIRCUIT.recordSuccess(model);
                    log("Circuit CLOSED for " + model + " — recovered.");
                    log("API response received successfully from " + model + " (HTTP 200 OK | Duration: " + duration + "ms)");

                    String responseText = extractTextFromJson(response.body());
                    String modelJson = "{\"role\": \"model\", \"parts\": [{\"text\": \"" + escapeJson(responseText) + "\"}]}";
                    conversationHistory.add(modelJson);

                    return responseText;
                }

                if (response.statusCode() == 400 || response.statusCode() == 403) {
                    String responseBody = response.body();

                    if (responseBody.contains("API key not valid") ||
                            responseBody.contains("API_KEY_INVALID") ||
                            responseBody.contains("not valid")) {

                        prefs.put(SchroedingersBlockPlugin.API_KEY_PREF, "");
                        log("Info: Invalid API key has been automatically reset.");
                        conversationHistory.remove(conversationHistory.size() - 1);

                        String cleanError = extractErrorMessageFromJson(responseBody);
                        return "⚠️ API error (HTTP " + response.statusCode() + "):\n" + cleanError
                                + "\n\n(The API key was invalid and has been reset. Please reopen the analysis window to enter a valid key.)";
                    }
                }

                log("Model " + model + " failed with HTTP " + response.statusCode() + ". Triggering Circuit Breaker.");
                CIRCUIT.recordFailure(model);

            } catch (Exception e) {
                log("Network/Connection error or timeout on " + model + ": " + e.getMessage());
                CIRCUIT.recordFailure(model);
            }
        }

        conversationHistory.remove(conversationHistory.size() - 1);

        return "❌ Service unreachable: All Gemini models are currently unavailable due to high server load or network issues.\n"
                + "Please wait a few minutes, check your internet connection, and try again.";
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

    private void log(String msg) {
        String prefixed = "[GeminiExplanationService] " + msg;
        try {
            javax.swing.SwingUtilities.invokeLater(() -> {
                try {
                    Application.getInstance().getGUILog().log(prefixed);
                } catch (Throwable ignored) {}
            });
        } catch (Throwable t) {
            System.out.println(prefixed);
        }
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

            while (true) {
                int start = json.indexOf(marker, currentPos);
                if (start == -1) break;
                start += marker.length();

                int end = json.indexOf("\"", start);
                while (end != -1 && json.charAt(end - 1) == '\\') {
                    int backslashCount = 0;
                    int i = end - 1;
                    while (i >= start && json.charAt(i) == '\\') {
                        backslashCount++;
                        i--;
                    }
                    if (backslashCount % 2 == 1) {
                        end = json.indexOf("\"", end + 1);
                    } else {
                        break;
                    }
                }

                if (end == -1) break;

                String partText = json.substring(start, end)
                        .replace("\\n", "\n")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                        .replace("\\t", "\t");

                fullText.append(partText);
                currentPos = end + 1;
            }

            if (fullText.isEmpty()) return "Could not find text in response.";
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