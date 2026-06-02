package com.schroedingersblock.api;

import com.nomagic.magicdraw.core.Application;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.schroedingersblock.core.SchroedingersBlockPlugin;
import com.nomagic.magicdraw.plugins.Plugin;
import com.nomagic.magicdraw.plugins.PluginDescriptor;
import com.nomagic.magicdraw.plugins.PluginUtils;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.prefs.Preferences;

public class GeminiExplanationService implements ExplanationService {

    // ─── Model cascade (tried in order) ──────────────────────────────────────
    private static final String[] MODELS = {
        "gemini-2.0-flash",
        "gemini-2.5-flash",
        "gemini-1.5-flash-latest",
        "gemini-1.5-pro-latest"
    };
    private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    // Static so circuit state persists across all requests in the same session
    private static final CircuitBreaker CIRCUIT = new CircuitBreaker();

    // ─── Circuit Breaker (inner class) ───────────────────────────────────────
    static class CircuitBreaker {
        private static final long COOLDOWN_MS = 5 * 60 * 1000;
        private enum State { CLOSED, OPEN, HALF_OPEN }
        private final Map<String, State> states   = new HashMap<>();
        private final Map<String, Long>  openedAt = new HashMap<>();

        boolean isAvailable(String model) {
            State state = states.getOrDefault(model, State.CLOSED);
            if (state == State.CLOSED || state == State.HALF_OPEN) return true;
            if (System.currentTimeMillis() - openedAt.getOrDefault(model, 0L) > COOLDOWN_MS) {
                states.put(model, State.HALF_OPEN);
                return true;
            }
            return false;
        }

        void recordSuccess(String model) {
            State previous = states.getOrDefault(model, State.CLOSED);
            states.put(model, State.CLOSED);
            openedAt.remove(model);
            if (previous != State.CLOSED)
                System.out.println("[GeminiExplanationService] Circuit CLOSED for " + model + " — recovered.");
        }

        void recordFailure(String model) {
            states.put(model, State.OPEN);
            openedAt.put(model, System.currentTimeMillis());
            System.out.println("[GeminiExplanationService] Circuit OPEN for " + model
                    + " — skipping for " + (COOLDOWN_MS / 60000) + " min.");
        }
    }

    // ─── File Manager (inner class) ──────────────────────────────────────────
    private static class FileManager {
        private static final String UPLOAD_URL =
                "https://generativelanguage.googleapis.com/upload/v1beta/files?uploadType=multipart&key=";
        private static final Path CACHE_FILE = Paths.get(
                System.getProperty("user.home"),
                "Documents", "SchroedingersBlock-MaxWorkspace", "gemini_file_cache.properties");

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
                HttpResponse<String> resp = HttpClient.newHttpClient()
                        .send(req, HttpResponse.BodyHandlers.ofString());
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
    public static String uploadModel(String jsonContent, String apiKey) throws IOException {
        return FileManager.upload(jsonContent, apiKey);
    }

    // ─── Mode configuration ───────────────────────────────────────────────────
    private static final int    EXPERT_MAX_TOKENS  = 9000;
    private static final double EXPERT_TEMPERATURE = 0.0;

    private static final int    BASIC_MAX_TOKENS   = 4000;
    private static final double BASIC_TEMPERATURE  = 0.0;

    private static final String SYSTEM_INSTRUCTION =
            "You are a SysML model analysis assistant embedded in Cameo Systems Modeler. "
            + "ONLY answer questions about SysML, systems engineering, model elements, "
            + "ports, flows, constraints, requirements, and the current model under analysis. "
            + "If the user asks about ANYTHING unrelated to SysML or systems engineering, "
            + "respond with exactly: \"Fix your Attention Span! Back to the Model!\" "
            + "IMPORTANT: Always finish your last sentence completely. "
            + "Never stop mid-sentence. If you are running out of space, wrap up your "
            + "answer early but always end on a complete sentence with a full stop.";

    // ─── Conversation state ───────────────────────────────────────────────────
    private final List<String> conversationHistory = new ArrayList<>();
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

        log("Starte Analyse fuer Element: '" + elementName + "' [" + elementType + "] | Level: " + currentDetailLevel);

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

        String prompt = PromptLoader.buildPrompt(currentDetailLevel, elementName, elementType, ownerName, exportedJson);
        log("Prompt generiert: " + String.format("%.2f", prompt.length() / 1024.0) + " KB");

        return callGemini(prompt, currentDetailLevel, fileUri);
    }

    @Override
    public String fetchFollowUp(String question) {
        log("Sende Follow-Up Frage...");
        return callGemini(question, currentDetailLevel);
    }

    // ─── Internal LLM call with circuit breaker cascade ──────────────────────

    private String callGemini(String userMessage, String mode) {
        return callGemini(userMessage, mode, null);
    }

    private String callGemini(String userMessage, String mode, String fileUri) {
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            return "⚠️ Error: No API key found. Please enter a valid Gemini API key.";
        }

        // Only attach fileUri to the very first message of the session
        String userJson = buildMessageJson("user", userMessage,
                conversationHistory.isEmpty() ? fileUri : null);
        conversationHistory.add(userJson);
        String requestBody = buildRequestBody(mode);

        for (String model : MODELS) {
            if (!CIRCUIT.isAvailable(model)) {
                log("Skipping " + model + " (circuit open)");
                continue;
            }

            log("Trying " + model + " ...");
            long start = System.currentTimeMillis();
            HttpResponse<String> response = sendRequest(model, apiKey, requestBody);
            long duration = System.currentTimeMillis() - start;

            if (isSuccess(response)) {
                CIRCUIT.recordSuccess(model);
                log("Antwort empfangen von " + model + " (" + duration + "ms)");
                return extractAndSave(response.body());
            }

            if (isOverloaded(response)) {
                CIRCUIT.recordFailure(model);
            } else {
                conversationHistory.remove(conversationHistory.size() - 1);
                return formatError(response);
            }
        }

        conversationHistory.remove(conversationHistory.size() - 1);
        return "❌ Service unreachable: All Gemini models are currently unavailable due to high server load.\n"
             + "Please wait a few minutes and try again.";
    }

    // ─── API key resolution (Cameo Preferences → ApiKeyManager fallback) ─────

    private String resolveApiKey() {
        try {
            Preferences prefs = Preferences.userNodeForPackage(SchroedingersBlockPlugin.class);
            String key = prefs.get(SchroedingersBlockPlugin.API_KEY_PREF, "");
            if (!key.isEmpty()) return key;
        } catch (Throwable ignored) {}
        return ApiKeyManager.loadKey();
    }

    // ─── HTTP helper ──────────────────────────────────────────────────────────

    private HttpResponse<String> sendRequest(String model, String apiKey, String body) {
        try {
            String url = API_BASE + model + ":generateContent?key=" + apiKey;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log("Request failed: " + e.getMessage());
            return null;
        }
    }

    private boolean isSuccess(HttpResponse<String> r)   { return r != null && r.statusCode() == 200; }
    private boolean isOverloaded(HttpResponse<String> r) {
        if (r == null) return true;
        int c = r.statusCode();
        return c == 404 || c == 429 || c == 500 || c == 503;
    }
    private String formatError(HttpResponse<String> r) {
        if (r == null) return "❌ No response from server.";
        return "⚠️ API error (HTTP " + r.statusCode() + "):\n" + extractErrorMessage(r.body());
    }
    private String extractAndSave(String body) {
        String text = extractTextFromJson(body);
        conversationHistory.add(buildMessageJson("model", text));
        return text;
    }

    // ─── Request builders ─────────────────────────────────────────────────────

    private String buildRequestBody(String mode) {
        boolean isExpert  = "Expert".equalsIgnoreCase(mode) || "EXPERT".equalsIgnoreCase(mode);
        int     maxTokens = isExpert ? EXPERT_MAX_TOKENS : BASIC_MAX_TOKENS;
        double  temp      = isExpert ? EXPERT_TEMPERATURE : BASIC_TEMPERATURE;

        return "{"
                + "\"systemInstruction\": {\"parts\": [{\"text\": \"" + escapeJson(SYSTEM_INSTRUCTION) + "\"}]},"
                + "\"contents\": [" + String.join(",", conversationHistory) + "],"
                + "\"generationConfig\": {"
                + "  \"temperature\": " + temp + ","
                + "  \"maxOutputTokens\": " + maxTokens + ","
                + "  \"topP\": 0.9"
                + "}"
                + "}";
    }

    private String buildMessageJson(String role, String text) {
        return buildMessageJson(role, text, null);
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
            File exportDir = new File(getBaseDirectory(), "data_extraction" + File.separator + "raw_model_data");
            if (!exportDir.exists()) return "Error: Export directory not found at " + exportDir.getAbsolutePath();

            File[] files = exportDir.listFiles((d, name) -> name.endsWith(".json"));
            if (files == null || files.length == 0) return "Error: No model JSON files found.";

            File newest = files[0];
            for (File f : files) if (f.lastModified() > newest.lastModified()) newest = f;
            return Files.readString(newest.toPath());
        } catch (Exception e) {
            return "Error reading exported model data: " + e.getMessage();
        }
    }

    private File getBaseDirectory() {
        String PLUGIN_ID = "com.schroedingersblock.plugin";
        try {
            for (Plugin plugin : PluginUtils.getPlugins()) {
                PluginDescriptor descriptor = plugin.getDescriptor();
                if (descriptor != null && PLUGIN_ID.equals(descriptor.getID()))
                    return descriptor.getPluginDirectory();
            }
        } catch (Throwable ignored) {}
        return new File(System.getProperty("user.dir"), "plugins" + File.separator + PLUGIN_ID);
    }

    // ─── Logging (Cameo GUILog with System.out fallback for tests) ───────────

    private void log(String msg) {
        String prefixed = "[GeminiExplanationService] " + msg;
        try {
            Application.getInstance().getGUILog().log(prefixed);
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

    String extractTextFromJson(String json) {
        try {
            String marker = "\"text\": \"";
            int start = json.indexOf(marker);
            if (start == -1) return "Could not find text in response.";
            start += marker.length();
            StringBuilder result = new StringBuilder();
            int i = start;
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char next = json.charAt(i + 1);
                    switch (next) {
                        case 'n':  result.append('\n'); break;
                        case 't':  result.append('\t'); break;
                        case 'r':  result.append('\r'); break;
                        case '"':  result.append('"');  break;
                        case '\\': result.append('\\'); break;
                        default:   result.append(next);
                    }
                    i += 2;
                } else if (c == '"') {
                    break;
                } else {
                    result.append(c);
                    i++;
                }
            }
            return result.toString();
        } catch (Exception e) {
            return "Error extracting text from response.";
        }
    }

    private String extractErrorMessage(String json) {
        try {
            String marker = "\"message\": \"";
            int start = json.indexOf(marker);
            if (start == -1) return json;
            start += marker.length();
            int end = json.indexOf("\"", start);
            while (end != -1 && json.charAt(end - 1) == '\\') end = json.indexOf("\"", end + 1);
            if (end == -1) return json;
            return json.substring(start, end).replace("\\n", "\n").replace("\\\"", "\"");
        } catch (Exception e) {
            return json;
        }
    }
}
