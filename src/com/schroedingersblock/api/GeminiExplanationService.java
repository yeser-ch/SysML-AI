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
            "gemini-3.5-flash",       // (Niko) Primär — Ziel-Modell (GA, free-tier-fähig)
            "gemini-3.1-flash-lite",  // (Niko) Fallback 1 — günstig, höhere Free-Limits
            "gemini-2.5-flash-lite"   // (Niko) Fallback 2 — stabiler Rückfall
            // (Niko) entfernt: gemini-2.5-flash (Abschaltung ~17.06.2026) + gemini-1.5-flash (tot) → vermeidet 404
    };
    // (Niko) Einziges grounding-fähiges Modell im Free Tier — wird bei aktivem Grounding nach vorn sortiert
    private static final String GROUNDING_MODEL = "gemini-2.5-flash-lite";
    private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    // (Niko) Konfiguration für typ-spezifisches 429-Handling (Rate-Limit)
    private static final int MAX_429_RETRIES = 2;       // (Niko) max. Wiederholungen am selben Modell bei HTTP 429
    private static final int MAX_RETRY_WAIT_SEC = 20;   // (Niko) längere RetryInfo-Delays = Tageslimit → nicht warten, Modell wechseln

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

            // (Niko) Dynamische Berechnung (Verdopplung): 1. Fehler = 5s, 2. = 10s, 3. = 20s, 4. = 40s ...
            long lockDurationMs = (long) Math.pow(2, failures - 1) * 5 * 1000;

            // (Niko) Deckelung bei maximal 60 Sekunden
            if (lockDurationMs > 60 * 1000) {
                lockDurationMs = 60 * 1000;
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

        // (Niko) Grounding nur inline: hochgeladene Datei (fileData) + google_search → HTTP 500.
        // (Niko) Bei aktivem Grounding den Upload ignorieren, damit das Modell inline mitgeht.
        boolean grounding = Preferences.userNodeForPackage(SchroedingersBlockPlugin.class)
                .getBoolean(SchroedingersBlockPlugin.GROUNDING_PREF, false);
        if (grounding) {
            fileUri = null;
        }

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

        // (Niko) Bei aktivem Grounding zuerst das grounding-fähige 2.5-Modell, 3.5/3.1 nur als Fallback
        boolean grounding = prefs.getBoolean(SchroedingersBlockPlugin.GROUNDING_PREF, false);
        String[] models = resolveModelCascade(grounding);

        for (String model : models) {
            if (!CIRCUIT.isAvailable(model)) {
                log("Skipping " + model + " (Circuit is OPEN due to previous failure)");
                continue;
            }

            String url = API_BASE + model + ":generateContent?key=" + apiKey;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(java.time.Duration.ofSeconds(30)) // (Niko) 3s war zu kurz für Flash + ~12 KB Payload
                    .build();

            // (Niko) Innere Retry-Schleife: bei HTTP 429 warten + Retry am SELBEN Modell statt rotieren (Free-Limits sind projektweit)
            boolean moveToNext = false; // (Niko) true = dieses Modell aufgeben, nächstes versuchen
            int attempt429 = 0;         // (Niko) Zähler für 429-Wiederholungen an diesem Modell
            while (!moveToNext) {

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

                // (Niko) Typ-spezifische Behandlung der HTTP-Statuscodes
                int status = response.statusCode(); // (Niko)
                if (status == 404) { // (Niko) Modell-ID aktuell nicht verfügbar → temporär sperren (Backoff), später erneut probieren
                    log("Model " + model + " returned HTTP 404 (not found). Temporarily skipping, will retry after backoff."); // (Niko)
                    CIRCUIT.recordFailure(model); // (Niko) kein Permanent-Lock mehr → Modell rotiert nach Backoff zurück
                    fallbackErrorMessage = "⚠️ API error (HTTP 404): Modell '" + model + "' aktuell nicht verfügbar."; // (Niko)
                    moveToNext = true; // (Niko)
                } else if (status == 429) { // (Niko) Rate-Limit → warten + Retry am SELBEN Modell, nicht rotieren
                    int waitSec = extractRetryDelaySeconds(response.body()); // (Niko) Googles RetryInfo, falls vorhanden
                    attempt429++; // (Niko)
                    if (attempt429 <= MAX_429_RETRIES && waitSec <= MAX_RETRY_WAIT_SEC) { // (Niko) zumutbares Delay → warten und nochmal
                        log("Model " + model + " rate-limited (HTTP 429). Waiting " + waitSec + "s, retry " + attempt429 + "/" + MAX_429_RETRIES + " on same model."); // (Niko)
                        try { // (Niko)
                            Thread.sleep(waitSec * 1000L); // (Niko) blockiert nur den SwingWorker-Hintergrundthread, UI bleibt frei
                        } catch (InterruptedException ie) { // (Niko)
                            Thread.currentThread().interrupt(); // (Niko) Interrupt-Flag wiederherstellen
                            CIRCUIT.recordFailure(model); // (Niko)
                            fallbackErrorMessage = "⚠️ Abgebrochen während Rate-Limit-Wartezeit."; // (Niko)
                            moveToNext = true; // (Niko)
                        }
                        // (Niko) bei Erfolg kein moveToNext → while wiederholt denselben Request am selben Modell
                    } else { // (Niko) Delay zu groß (= Tageslimit) oder Versuche erschöpft → Circuit + nächstes Modell
                        log("Model " + model + " still rate-limited (HTTP 429, suggested wait " + waitSec + "s). Giving up on this model."); // (Niko)
                        CIRCUIT.recordFailure(model); // (Niko)
                        fallbackErrorMessage = "⚠️ API error (HTTP 429): Rate-Limit erreicht (Free Tier). Bitte später erneut versuchen."; // (Niko)
                        moveToNext = true; // (Niko)
                    }
                } else { // (Niko) sonstige Fehler (z.B. 5xx) → transient behandeln, nächstes Modell
                    log("Model " + model + " failed with HTTP " + status + ". Triggering Circuit Breaker."); // (Niko)
                    CIRCUIT.recordFailure(model);
                    fallbackErrorMessage = "⚠️ API error (HTTP " + status + "): Modelle temporär überlastet.";
                    moveToNext = true; // (Niko)
                }
            } catch (java.net.http.HttpConnectTimeoutException e) {
                log("Network connection timeout on " + model + ": " + e.getMessage());
                CIRCUIT.recordFailure(model);
                fallbackErrorMessage = "⚠️ Connection Error: Keine Internetverbindung oder der Server ist nicht erreichbar. Bitte prüfe dein Netzwerk.";
                moveToNext = true; // (Niko)

            } catch (java.net.http.HttpTimeoutException e) {
                log("API Response Timeout on " + model + ": " + e.getMessage());
                CIRCUIT.recordFailure(model);
                fallbackErrorMessage = "⚠️ Timeout Error: Die Anfrage wurde übermittelt, aber die Antwort von " + model + " hat das Zeitlimit überschritten.";
                moveToNext = true; // (Niko)

            } catch (java.net.UnknownHostException | java.net.ConnectException e) {
                log("Direct network failure on " + model + ": " + e.getMessage());
                CIRCUIT.recordFailure(model);
                fallbackErrorMessage = "⚠️ Connection Error: Netzwerk-Infrastruktur offline. Bitte verbinde dich mit dem Internet.";
                moveToNext = true; // (Niko)

            } catch (Exception e) {
                log("Unexpected fallback error on " + model + ": " + e.getMessage());
                CIRCUIT.recordFailure(model);
                fallbackErrorMessage = "❌ Unexpected Error: " + e.getMessage();
                moveToNext = true; // (Niko)
            }
            } // (Niko) Ende innere Retry-Schleife
        }

        // Wenn der Code hier ankommt, sind alle Modelle in der Kaskade fehlgeschlagen
        conversationHistory.remove(conversationHistory.size() - 1);
        return fallbackErrorMessage;
    }

    // (Niko) Bei aktivem Grounding NUR das grounding-fähige 2.5-Modell — kein Fallback auf 3.5/3.1,
    // (Niko) die im Free Tier kein Grounding können (sonst sinnloses 429-Durchlaufen der Kaskade).
    private String[] resolveModelCascade(boolean grounding) {
        if (!grounding) return MODELS;
        return new String[]{ GROUNDING_MODEL };
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

        // (Niko) Grounding (google_search) nur senden, wenn im Settings-Dialog aktiviert (Default aus).
        // (Niko) Auf Free Tier funktioniert google_search nur mit gemini-2.5 (3.5/3.1 → HTTP 429); aus = umgeht zudem den fragilen groundingMetadata-Parserpfad.
        boolean grounding = Preferences.userNodeForPackage(SchroedingersBlockPlugin.class)
                .getBoolean(SchroedingersBlockPlugin.GROUNDING_PREF, false);
        String toolsPart = grounding ? "\"tools\": [{\"google_search\": {}}]," : "";

        return "{"
                + systemInstructionPart
                + toolsPart // (Niko) ersetzt das vormals fest aktivierte google_search
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

    // (Niko) Liest das RetryInfo-Feld ("retryDelay": "27s") aus einer 429-Antwort und gibt die Sekunden zurück.
    // (Niko) Fehlt das Feld oder ist es unparsbar, wird ein konservativer Default (10s) geliefert.
    private int extractRetryDelaySeconds(String json) {
        try {
            String marker = "\"retryDelay\"";
            int start = json.indexOf(marker);
            if (start == -1) return 10;
            int quote = json.indexOf("\"", start + marker.length());
            if (quote == -1) return 10;
            int endQuote = json.indexOf("\"", quote + 1);
            if (endQuote == -1) return 10;
            String value = json.substring(quote + 1, endQuote).trim(); // z.B. "27s" oder "27.5s"
            StringBuilder digits = new StringBuilder();
            for (char c : value.toCharArray()) {
                if (Character.isDigit(c)) digits.append(c);
                else break;
            }
            if (digits.length() == 0) return 10;
            return Integer.parseInt(digits.toString());
        } catch (Exception e) {
            return 10;
        }
    }
}