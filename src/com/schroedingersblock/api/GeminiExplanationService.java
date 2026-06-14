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

    // ─── ERWEITERTE MODELL-KASKADE: Vollständige Integration nach Vorgabe von Nikolai ────────
    private static final String[] MODELS = {
            "gemini-3.1-flash-live-preview",
            "gemini-3.5-flash",       // Next-Gen Flash
            "gemini-3.1-flash-lite",  // Ultra-schnelles Flash-Lite
            "gemini-2.0-flash-lite",  // Leichtgewichtiges v2-Lite
            "gemini-2.0-flash",       // Schnelles v2-Flash
            "gemini-2.5-pro",         // Hochpräzises Pro-Fallback für komplexe Modelle
            "gemini-3-flash",         // Standard v3-Flash
            "gemini-3.1-pro",          // Maximales v3-Pro-Sicherheitsnetz
            "gemini-2.5-flash",       // Standard-Flash
            "gemini-2.5-flash-lite" // Klassisches Flash-Lite
    };

    private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    // Statisch, damit der Schalter-Zustand für die gesamte Sitzung erhalten bleibt
    private static final CircuitBreaker CIRCUIT = new CircuitBreaker();

    // ─── Circuit Breaker Pattern (Leitungsschutzschalter) ──────────────────────
    private static class CircuitBreaker {
        private final Map<String, Integer> failureCounts = new HashMap<>();
        private final Map<String, Long>  retryAtMap    = new HashMap<>();

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

            long lockDurationMs = (long) Math.pow(3, failures - 1) * 5 * 1000;

            if (lockDurationMs > 5 * 60 * 1000) {
                lockDurationMs = 5 * 60 * 1000;
            }

            retryAtMap.put(model, System.currentTimeMillis() + lockDurationMs);
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

    /**
     * Reads the latest exported model JSON from disk, uploads it to the Gemini Files API,
     * and caches the returned URI for subsequent generateContent calls.
     * Intended to be called from a background thread (e.g. inside SwingWorker.doInBackground).
     *
     * @return true if the upload succeeded; false if skipped or failed (inline-JSON fallback applies).
     */
    public boolean uploadModelData() {
        Preferences prefs = Preferences.userNodeForPackage(SchroedingersBlockPlugin.class);
        String apiKey = prefs.get(SchroedingersBlockPlugin.API_KEY_PREF, "");
        if (apiKey.isEmpty()) {
            log("Upload skipped: No API key configured.");
            return false;
        }
        String jsonContent = loadLatestExportedJson();
        if (jsonContent.startsWith("Error")) {
            log("Upload skipped: " + jsonContent);
            return false;
        }
        try {
            String uri = FileManager.upload(jsonContent, apiKey);
            log("Model uploaded to Files API. URI: " + uri);
            return true;
        } catch (IOException e) {
            log("Upload failed — will fall back to inline JSON: " + e.getMessage());
            return false;
        }
    }

    // ─── Modus & System-Konfiguration ──────────────────────────────────────────
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

        // --- REPARATUR: Dynamische Erkennung des initialen Zustands ---
        boolean isInitial = conversationHistory.isEmpty();

        String userJson = buildMessageJson("user", userMessage, isInitial ? fileUri : null);
        conversationHistory.add(userJson);

        // REPARATUR: isInitial-Boolean wird jetzt korrekt an den Body-Builder gereicht
        String requestBody = buildRequestBody(mode, isInitial);

        String fallbackErrorMessage = "⚠️ All Gemini models are currently overloaded. Please try again in a moment.";

        for (String model : MODELS) {
            if (!CIRCUIT.isAvailable(model)) {
                log("Skipping " + model + " (Circuit is OPEN due to previous failure)");
                continue;
            }

            String url = API_BASE + model + ":generateContent?key=" + apiKey;

            // 10 Sekunden pro Modellaufruf ---
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(java.time.Duration.ofSeconds(10)) ///Hier dauer pro Modell
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
                    String modelJson = buildMessageJson("model", responseText, null);
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

            } catch (java.net.http.HttpConnectTimeoutException e) {
                log("Network connection timeout on " + model + ": " + e.getMessage());
                CIRCUIT.recordFailure(model);

            } catch (java.net.http.HttpTimeoutException e) {
                log("API Response Timeout on " + model + ": " + e.getMessage());
                CIRCUIT.recordFailure(model);

            } catch (java.net.UnknownHostException | java.net.ConnectException e) {
                log("Direct network failure on " + model + ": " + e.getMessage());
                CIRCUIT.recordFailure(model);

            } catch (Exception e) {
                log("Unexpected fallback error on " + model + ": " + e.getMessage());
                CIRCUIT.recordFailure(model);
            }
        }

        // Wenn der Code hier ankommt, haben alle Modelle in der Kaskade innerhalb der Fristen versagt
        conversationHistory.remove(conversationHistory.size() - 1);
        return fallbackErrorMessage;
    }

    // ─── Request builders ─────────────────────────────────────────────────────

    private String buildRequestBody(String mode, boolean isInitial) {

        boolean isExpert = "Expert".equalsIgnoreCase(mode);
        int maxTokens = isExpert ? EXPERT_MAX_TOKENS : BASIC_MAX_TOKENS;
        double temp = isExpert ? EXPERT_TEMPERATURE : BASIC_TEMPERATURE;

        String historyArray = String.join(",", conversationHistory);

        String systemInstructionPart = "";
        if (isInitial) {
            systemInstructionPart = "\"systemInstruction\": {\"parts\": [{\"text\": \"" + escapeJson(SYSTEM_INSTRUCTION) + "\"}]},";
        }

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

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String extractTextFromJson(String json) {
        try {
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