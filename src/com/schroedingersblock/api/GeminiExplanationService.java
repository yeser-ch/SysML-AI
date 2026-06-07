package com.schroedingersblock.api;

import com.nomagic.magicdraw.core.Application;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.schroedingersblock.core.SchroedingersBlockPlugin;
import com.nomagic.magicdraw.plugins.Plugin;
import com.nomagic.magicdraw.plugins.PluginDescriptor;
import com.nomagic.magicdraw.plugins.PluginUtils;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.prefs.Preferences;

public class GeminiExplanationService implements ExplanationService {

    // ─── PERFORMANCE-BOOST: Globaler HttpClient für Connection Keep-Alive ──────
    private final HttpClient httpClient = HttpClient.newHttpClient();

    // ─── Modell-Kaskade (Bereinigte IDs aus eurer AI Studio Übersicht) ──────────
    private static final String[] MODELS = {
            "gemini-2.5-flash",       // Primäres Standardmodell
            "gemini-3.1-flash-lite",  // Schneller Retter bei Quota-Limits
            "gemini-2.5-flash-lite",  // Offizielles Flash-Lite Modell
            "gemini-1.5-flash"        // Zuverlässiger, klassischer Fallback-Knoten
    };
    private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    // Statisch, damit der Schalter-Zustand für die gesamte Sitzung erhalten bleibt
    private static final CircuitBreaker CIRCUIT = new CircuitBreaker();

    // ─── Circuit Breaker (inner class) ───────────────────────────────────────
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
            System.out.println("[CircuitBreaker] " + model + " temporär gesperrt für " + (lockDurationMs / 1000) + " Sekunden.");
        }
    }


    // ─── File Manager (inner class) ──────────────────────────────────────────
    private static class FileManager {
        private static final String UPLOAD_URL =
                "https://generativelanguage.googleapis.com/upload/v1beta/files?uploadType=multipart&key=";
        private static final Path CACHE_FILE = Paths.get(
                System.getProperty("user.home"),
                "Documents", "SchroedingersBlock-MaxWorkspace", "gemini_file_cache.properties");

        // Wiederverwendbarer statischer Client zur Vermeidung von Warnungen bezüglich try-with-resources
        private static final HttpClient FILE_HTTP_CLIENT = HttpClient.newHttpClient();

        @SuppressWarnings("unused")
        static String upload(String jsonContent, String apiKey) throws IOException {
            String boundary = "sb_upload_boundary";
            String CRLF = "\r\n";
            String body = "--" + boundary + CRLF
                    + "Content-Type: application/json; charset=UTF-8" + CRLF + CRLF
                    + "{\"file\":{\"displayName\":\"SysML Model Export\",\"mimeType\":\"application/json\"}}" + CRLF
                    + "--" + boundary + CRLF
                    + "Content-Type: application/json" + CRLF + CRLF
                    + jsonContent + CRLF
                    + "--" + boundary + "--";
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(UPLOAD_URL + apiKey))
                        .header("Content-Type", "multipart/related; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)))
                        .build();
                HttpResponse<String> resp = FILE_HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200)
                    throw new IOException("Upload failed (HTTP " + resp.statusCode() + "): " + resp.body());
                String uri        = extractField(resp.body(), "\"uri\"");
                String expiration = extractField(resp.body(), "\"expirationTime\"");
                saveCache(uri, expiration);
                return uri;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Upload interrupted", e);
            }
        }

        static String getValidUri() {
            try {
                Properties p = loadCache();
                String uri   = p.getProperty("uri", "");
                long expires = Long.parseLong(p.getProperty("expires", "0"));
                if (!uri.isEmpty() && System.currentTimeMillis() < expires) return uri;
            } catch (Exception ignored) {}
            return null;
        }

        private static void saveCache(String uri, String expirationIso) {
            try {
                long ms = (expirationIso != null && !expirationIso.isEmpty())
                        ? Instant.parse(expirationIso).toEpochMilli()
                        : System.currentTimeMillis() + 47L * 3600 * 1000;
                Properties p = new Properties();
                p.setProperty("uri",     uri);
                p.setProperty("expires", String.valueOf(ms));
                Files.createDirectories(CACHE_FILE.getParent());
                try (Writer w = Files.newBufferedWriter(CACHE_FILE)) { p.store(w, null); }
            } catch (IOException e) {
                System.err.println("[GeminiExplanationService] Cache write failed: " + e.getMessage());
            }
        }

        private static Properties loadCache() throws IOException {
            Properties p = new Properties();
            if (Files.exists(CACHE_FILE)) {
                try (Reader r = Files.newBufferedReader(CACHE_FILE)) { p.load(r); }
            }
            return p;
        }

        private static String extractField(String json, String key) {
            String marker = key + ": \"";
            int start = json.indexOf(marker);
            if (start == -1) { marker = key + ":\""; start = json.indexOf(marker); }
            if (start == -1) return "";
            start += marker.length();
            int end = json.indexOf("\"", start);
            return end == -1 ? "" : json.substring(start, end);
        }
    }

    // ─── Public upload entry point ────────────────────────────────────────────
    @SuppressWarnings("unused")
    public static String uploadModel(String jsonContent, String apiKey) throws IOException {
        return FileManager.upload(jsonContent, apiKey);
    }

    // ─── Mode configuration ───────────────────────────────────────────────────
    private static final int    EXPERT_MAX_TOKENS  = 9000;
    private static final double EXPERT_TEMPERATURE = 0.0;

    private static final int    BASIC_MAX_TOKENS   = 4000;
    private static final double BASIC_TEMPERATURE  = 0.0;

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
    private final List<String> conversationHistory = Collections.synchronizedList(new ArrayList<>());
    private String currentDetailLevel = "Basic";

    // ─── ExplanationService interface ─────────────────────────────────────────

    @Override
    public void setDetailLevel(String level) {
        this.currentDetailLevel = level;
    }

    @Override
    public String fetchExplanation(Element targetElement, String mode) {
        String elementName = targetElement != null ? targetElement.getHumanName() : "Unknown element";
        String elementType = targetElement != null ? targetElement.getHumanType() : "Unknown type";
        String ownerName   = (targetElement != null && targetElement.getOwner() != null)
                ? targetElement.getOwner().getHumanName() : "root";

        log("Starte Analyse für Element: '" + elementName + "' [" + elementType + "] | Level: " + currentDetailLevel);
        log("Detail-Level: " + currentDetailLevel);

        String fileUri = FileManager.getValidUri();
        String exportedJson;

        if (fileUri != null) {
            log("Verwende hochgeladene Modelldatei (Files API).");
            exportedJson = "[Model context provided as uploaded file]";
        } else {
            exportedJson = loadLatestExportedJson();
            if (exportedJson.startsWith("Error")) {
                log("Fehler beim Lesen der Modelldaten: " + exportedJson);
            } else {
                log("JSON geladen: " + String.format("%.2f", exportedJson.length() / 1024.0) + " KB");
            }
        }

        String prompt = PromptLoader.buildPrompt(getBaseDirectory(), currentDetailLevel, elementName, elementType, ownerName, exportedJson);
        double promptKb = prompt.length() / 1024.0;
        log("System-Prompt generiert. Payload-Gesamtgröße (inkl. JSON): " + String.format("%.2f", promptKb) + " KB");

        return callGemini(prompt, currentDetailLevel, fileUri);
    }

    @Override
    public String fetchFollowUp(String question) {
        log("Sende Follow-Up Frage\u00A0...");
        return callGemini(question, currentDetailLevel);
    }

    // ─── Internal LLM call with circuit breaker cascade ──────────────────────

    private String callGemini(String userMessage, String mode) {
        return callGemini(userMessage, mode, null);
    }

    private String callGemini(String userMessage, String mode, String fileUri) {
        Preferences prefs = Preferences.userNodeForPackage(SchroedingersBlockPlugin.class);
        String apiKey = prefs.get(SchroedingersBlockPlugin.API_KEY_PREF, "");

        if (apiKey.isEmpty()) {
            log("Error: No API key found in settings.");
            return "⚠️ Error: No API key found. Please enter a valid Gemini API key.";
        }

        // Only attach fileUri to the very first message of the session
        String userJson = buildMessageJson("user", userMessage,
                conversationHistory.isEmpty() ? fileUri : null);
        conversationHistory.add(userJson);
        String requestBody = buildRequestBody(mode, fileUri);

        // Variable, um die Fehlermeldung des letzten Versuchs zu speichern, falls alle Modelle fehlschlagen
        String fallbackErrorMessage = "❌ Service unreachable: All Gemini models are currently unavailable.";

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

            log("Sending HTTP POST to " + model + "\u00A0...");
            long startTime = System.currentTimeMillis();

            try {
                HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                long duration = System.currentTimeMillis() - startTime;

                if (response.statusCode() == 200) {
                    CIRCUIT.recordSuccess(model);
                    log("Circuit CLOSED for " + model + " — recovered.");
                    log("API response received successfully from " + model + " (HTTP 200 OK | Duration: " + duration + "ms)");

                    String responseText = extractTextFromJson(response.body());
                    // KORREKTUR: Verwende buildMessageJson zur Serialisierung, um Parameter-Warnung zu beheben
                    String modelJson = buildMessageJson("model", responseText, null);
                    conversationHistory.add(modelJson);

                    return responseText; // Erfolgreicher Ausstieg!
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
                fallbackErrorMessage = "⚠️ API error (HTTP " + response.statusCode() + "): Modelle temporär überlastet.";

                // --- KORREKTUR: Kein "return" mehr in den Schleifen-Exceptions! Wir loggen, sperren den Circuit und machen weiter (continue) ---
            } catch (java.net.http.HttpConnectTimeoutException e) {
                log("Network connection timeout on " + model + ": " + e.getMessage());
                CIRCUIT.recordFailure(model);
                fallbackErrorMessage = "⚠️ Connection Error: Keine Internetverbindung oder der Server ist nicht erreichbar. Bitte prüfe dein Netzwerk.";

            } catch (java.net.http.HttpTimeoutException e) {
                log("API Response Timeout on " + model + ": " + e.getMessage());
                CIRCUIT.recordFailure(model);
                fallbackErrorMessage = "⚠️ Timeout Error: Die Anfrage wurde übermittelt, aber die Antwort von " + model + " hat das Zeitlimit überschritten.";

            } catch (java.net.UnknownHostException | java.net.ConnectException e) {
                log("Direct network failure on " + model + ": " + e.getMessage());
                CIRCUIT.recordFailure(model);
                fallbackErrorMessage = "⚠️ Connection Error: Netzwerk-Infrastruktur offline. Bitte verbinde dich mit dem Internet.";

            } catch (Exception e) {
                log("Unexpected fallback error on " + model + ": " + e.getMessage());
                CIRCUIT.recordFailure(model);
                fallbackErrorMessage = "❌ Unexpected Error: " + e.getMessage();
            }
        }

        // Wenn der Code hier ankommt, sind alle Modelle in der Kaskade fehlgeschlagen
        conversationHistory.remove(conversationHistory.size() - 1);
        return fallbackErrorMessage;
    }

    // ─── Request builders ─────────────────────────────────────────────────────

    private String buildRequestBody(String mode, String isInitial) {

        boolean isExpert = "Expert".equalsIgnoreCase(mode);
        int maxTokens = isExpert ? EXPERT_MAX_TOKENS : BASIC_MAX_TOKENS;
        double temp = isExpert ? EXPERT_TEMPERATURE : BASIC_TEMPERATURE;

        String historyArray = String.join(",", conversationHistory);

        // DYNAMISCHE SYSTEM INSTRUCTION: Nur beim ersten Aufruf mitsenden!
        String systemInstructionPart = "";
        if (isInitial != null) {
            systemInstructionPart = "\"systemInstruction\": {\"parts\": [{\"text\": \"" + escapeJson(SYSTEM_INSTRUCTION) + "\"}]},";
        }

        // KORREKTUR: systemInstructionPart wird jetzt korrekt eingebunden und tools (Google Search) bleibt aktiv
        return "{"
                + systemInstructionPart
                + "\"tools\": [{\"google_search\": {}}],"
                + "\"contents\": [" + historyArray + "],"
                + "\"generationConfig\": {"
                + "  \"temperature\": " + temp + ","
                + "  \"maxOutputTokens\": " + maxTokens + ","
                + "  \"topP\": 0.9"
                + "}"
                + "}";
    }

    private String buildMessageJson(String role, String text, String fileUri) {
        if (fileUri != null && !fileUri.isEmpty()) {
            return "{\"role\": \"" + role + "\", \"parts\": ["
                    + "{\"fileData\": {\"mimeType\": \"application/json\", \"fileUri\": \""
                    + escapeJson(fileUri) + "\"}},"
                    + "{\"text\": \"" + escapeJson(text) + "\"}"
                    + "]}";
        }
        return "{\"role\": \"" + role + "\", \"parts\": [{\"text\": \"" + escapeJson(text) + "\"}]}";
    }

    // ─── JSON model loader ────────────────────────────────────────────────────

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
            //Ignored
        }
        return new File(System.getProperty("user.dir"), "plugins" + File.separator + PLUGIN_ID);
    }

    // ─── Logging (Cameo GUILog with System.out fallback for tests) ───────────

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

    // ─── JSON utilities ───────────────────────────────────────────────────────

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String extractTextFromJson(String json) {
        try {
            // Verdopplungsschutz: Wir ignorieren alles, was nach den eigentlichen Text-Candidates kommt
            int metaIndex = json.indexOf("\"groundingMetadata\"");
            String searchScope = (metaIndex != -1) ? json.substring(0, metaIndex) : json;

            StringBuilder fullText = new StringBuilder();
            String marker = "\"text\": \"";
            int currentPos = 0;

            while (true) {
                int start = searchScope.indexOf(marker, currentPos);
                if (start == -1) break;
                start += marker.length();

                int end = searchScope.indexOf("\"", start);
                while (end != -1 && searchScope.charAt(end - 1) == '\\') {
                    int backslashCount = 0;
                    int i = end - 1;
                    while (i >= start && searchScope.charAt(i) == '\\') {
                        backslashCount++;
                        i--;
                    }
                    if (backslashCount % 2 == 1) {
                        end = searchScope.indexOf("\"", end + 1);
                    } else {
                        break;
                    }
                }

                if (end == -1) break;

                String partText = searchScope.substring(start, end)
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