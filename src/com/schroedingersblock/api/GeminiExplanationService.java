package com.schroedingersblock.api;

import com.nomagic.magicdraw.core.Application;
import com.schroedingersblock.core.SchroedingersBlockPlugin;
import com.nomagic.magicdraw.plugins.Plugin;
import com.nomagic.magicdraw.plugins.PluginDescriptor;
import com.nomagic.magicdraw.plugins.PluginUtils;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anbindung an die Google Gemini API (generateContent).
 *
 * Änderungen gegenüber der Vorgängerversion:
 * - Frage wird frei eingegeben (keine festen Prompt-Templates mehr).
 * - Modell, Temperatur, Token-Limit und Grounding kommen aus dem UI (AskSettings).
 * - Kein stilles Wechseln auf andere Modelle mehr (verfälscht Vergleiche zwischen Modellgrößen).
 *   Fallback nur, wenn im UI ausdrücklich erlaubt – das antwortende Modell wird immer angezeigt.
 * - Grounding erzwingt kein bestimmtes Modell mehr.
 * - System-Prompt wird bei JEDER Anfrage mitgesendet (die API ist zustandslos).
 * - Token-Verbrauch und finishReason werden ausgelesen (für Auswertung/Log).
 */
public class GeminiExplanationService implements ExplanationService {

    private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta/models";

    /** Vorschlagsliste für das Modell-Dropdown (über "Refresh" durch die echte Liste der API ersetzbar). */
    public static final String[] DEFAULT_MODELS = {
            "gemini-3.5-flash",
            "gemini-3.1-flash-lite",
            "gemini-2.5-flash-lite"
    };

    private static final int MAX_429_RETRIES = 2;
    private static final int MAX_RETRY_WAIT_SEC = 20;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(180); // ganzes Modell -> größere Payload

    private static final CircuitBreaker CIRCUIT = new CircuitBreaker();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    // Chatverlauf als fertige JSON-"contents"-Einträge
    private final List<String> conversationHistory = Collections.synchronizedList(new ArrayList<>());

    // ─── Circuit Breaker (unverändert übernommen) ─────────────────────────────
    private static class CircuitBreaker {
        private final Map<String, Integer> failureCounts = new HashMap<>();
        private final Map<String, Long> retryAtMap = new HashMap<>();

        synchronized boolean isAvailable(String model) {
            return System.currentTimeMillis() >= retryAtMap.getOrDefault(model, 0L);
        }

        synchronized void recordSuccess(String model) {
            failureCounts.remove(model);
            retryAtMap.remove(model);
        }

        synchronized void recordFailure(String model) {
            int failures = failureCounts.getOrDefault(model, 0) + 1;
            failureCounts.put(model, failures);
            long lockMs = Math.min((long) Math.pow(2, failures - 1) * 5000, 60_000);
            retryAtMap.put(model, System.currentTimeMillis() + lockMs);
        }
    }

    // ─── Öffentliche API ───────────────────────────────────────────────────────

    @Override
    public void resetConversation() {
        conversationHistory.clear();
    }

    @Override
    public Answer ask(String question, String modelJson, String focusInfo, AskSettings s) {
        long start = System.currentTimeMillis();
        String apiKey = getApiKey();
        if (apiKey.isEmpty()) {
            return Answer.error("No API key configured. Use \"API key...\" to enter one.", 0);
        }

        if (s.freshChat) {
            conversationHistory.clear();
        }
        boolean firstMessage = conversationHistory.isEmpty();
        String userText = firstMessage ? PromptLoader.buildFirstMessage(modelJson, focusInfo, question) : question;
        conversationHistory.add(contentJson("user", userText));

        String body = buildRequestBody(s);
        log("Frage gesendet (" + (firstMessage ? "neuer Chat, inkl. Modell" : "Folgefrage") + "), Payload "
                + String.format("%.1f", body.length() / 1024.0) + " KB");

        List<String> models = new ArrayList<>();
        models.add(s.model);
        if (s.allowFallback) {
            for (String m : DEFAULT_MODELS) if (!m.equals(s.model)) models.add(m);
        }

        String lastError = "Unknown error.";
        for (String model : models) {
            if (s.allowFallback && !CIRCUIT.isAvailable(model)) {
                log("Überspringe " + model + " (kurzzeitig gesperrt nach Fehler)");
                continue;
            }
            Attempt a = callModel(model, body, apiKey);
            if (a.answer != null) {
                conversationHistory.add(contentJson("model", a.answer.text));
                long duration = System.currentTimeMillis() - start;
                return new Answer(a.answer.text, model, true, duration,
                        a.answer.promptTokens, a.answer.answerTokens, a.answer.finishReason);
            }
            lastError = a.error;
            if (a.fatal) break; // z. B. ungültiger API-Key -> anderes Modell hilft nicht
        }

        // Fehlgeschlagen: Frage wieder aus dem Verlauf nehmen
        if (!conversationHistory.isEmpty()) conversationHistory.remove(conversationHistory.size() - 1);
        return Answer.error(lastError, System.currentTimeMillis() - start);
    }

    /**
     * Holt die Liste der Modelle, die generateContent unterstützen (für das Dropdown).
     * Gibt bei Fehlern eine leere Liste zurück.
     */
    public List<String> listAvailableModels() {
        List<String> result = new ArrayList<>();
        String apiKey = getApiKey();
        if (apiKey.isEmpty()) return result;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "?pageSize=1000&key=" + apiKey))
                    .timeout(Duration.ofSeconds(20))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log("Modellliste nicht abrufbar (HTTP " + resp.statusCode() + ")");
                return result;
            }
            // Grobe Auswertung ohne JSON-Bibliothek: Abschnitte je Modell
            String json = resp.body();
            Pattern p = Pattern.compile("\"name\"\\s*:\\s*\"models/([^\"]+)\"");
            Matcher m = p.matcher(json);
            List<int[]> spans = new ArrayList<>();
            List<String> names = new ArrayList<>();
            while (m.find()) {
                names.add(m.group(1));
                spans.add(new int[]{m.start(), 0});
            }
            for (int i = 0; i < names.size(); i++) {
                int from = spans.get(i)[0];
                int to = (i + 1 < names.size()) ? spans.get(i + 1)[0] : json.length();
                String section = json.substring(from, to);
                if (section.contains("\"generateContent\"") && names.get(i).startsWith("gemini")) {
                    result.add(names.get(i));
                }
            }
            Collections.sort(result);
        } catch (Exception e) {
            log("Modellliste nicht abrufbar: " + e.getMessage());
        }
        return result;
    }

    // ─── HTTP-Aufruf eines Modells ────────────────────────────────────────────

    private static class Attempt {
        Answer answer;   // gesetzt bei Erfolg
        String error;    // gesetzt bei Fehler
        boolean fatal;   // Fehler, bei dem ein anderes Modell nicht hilft
    }

    private Attempt callModel(String model, String body, String apiKey) {
        Attempt result = new Attempt();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/" + model + ":generateContent?key=" + apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(REQUEST_TIMEOUT)
                .build();

        int attempt429 = 0;
        while (true) {
            log("Sende Anfrage an " + model + " ...");
            long t0 = System.currentTimeMillis();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                String resp = response.body();

                if (status == 200) {
                    CIRCUIT.recordSuccess(model);
                    String text = extractAnswerText(resp);
                    int promptTokens = extractInt(resp, "promptTokenCount");
                    int answerTokens = extractInt(resp, "candidatesTokenCount");
                    String finish = extractString(resp, "finishReason");
                    if (text.isEmpty()) {
                        String blockReason = extractString(resp, "blockReason");
                        text = "(No text in response. finishReason=" + finish
                                + (blockReason.isEmpty() ? "" : ", blockReason=" + blockReason) + ")";
                    }
                    if ("MAX_TOKENS".equals(finish)) {
                        text += "\n\n[Answer cut off: max. output tokens reached. Increase \"Max tokens\".]";
                    }
                    log(model + " antwortete in " + (System.currentTimeMillis() - t0) + " ms, Tokens in/out: "
                            + promptTokens + "/" + answerTokens);
                    result.answer = new Answer(text, model, true, 0, promptTokens, answerTokens, finish);
                    return result;
                }

                String apiMessage = extractString(resp, "message");
                if ((status == 400 || status == 403)
                        && (resp.contains("API_KEY_INVALID") || resp.contains("API key not valid"))) {
                    result.error = "Invalid API key (HTTP " + status + "). Enter a new one via \"API key...\".";
                    result.fatal = true;
                    return result;
                }
                if (status == 400) {
                    // z. B. Modell kann kein Grounding, Payload zu groß (Kontextfenster) -> Meldung der API zeigen
                    result.error = "Request rejected by " + model + " (HTTP 400): " + apiMessage;
                    result.fatal = true;
                    return result;
                }
                if (status == 429) {
                    int waitSec = extractRetryDelaySeconds(resp);
                    attempt429++;
                    if (attempt429 <= MAX_429_RETRIES && waitSec <= MAX_RETRY_WAIT_SEC) {
                        log(model + " Rate-Limit (429), warte " + waitSec + " s (Versuch " + attempt429 + ")");
                        Thread.sleep(waitSec * 1000L);
                        continue;
                    }
                    CIRCUIT.recordFailure(model);
                    result.error = "Rate limit reached for " + model + " (HTTP 429). "
                            + "Free-tier limits (requests or tokens per minute/day) exceeded. " + apiMessage;
                    return result;
                }
                CIRCUIT.recordFailure(model);
                result.error = model + " failed (HTTP " + status + "): " + apiMessage;
                return result;

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                result.error = "Request cancelled.";
                result.fatal = true;
                return result;
            } catch (java.net.http.HttpTimeoutException e) {
                CIRCUIT.recordFailure(model);
                result.error = "Timeout: " + model + " did not answer within " + REQUEST_TIMEOUT.getSeconds() + " s.";
                return result;
            } catch (java.io.IOException e) {
                CIRCUIT.recordFailure(model);
                result.error = "Connection error: " + e.getMessage();
                return result;
            }
        }
    }

    // ─── Request ──────────────────────────────────────────────────────────────

    private String buildRequestBody(AskSettings s) {
        String systemPrompt = PromptLoader.buildSystemPrompt(getBaseDirectory(), s.detailLevel, s.requireIds);
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"systemInstruction\":{\"parts\":[{\"text\":\"").append(escapeJson(systemPrompt)).append("\"}]},");
        if (s.grounding) {
            sb.append("\"tools\":[{\"google_search\":{}}],");
        }
        sb.append("\"contents\":[").append(String.join(",", conversationHistory)).append("],");
        sb.append("\"generationConfig\":{")
                .append("\"temperature\":").append(s.temperature).append(",")
                .append("\"maxOutputTokens\":").append(s.maxOutputTokens)
                .append("}}");
        return sb.toString();
    }

    private String contentJson(String role, String text) {
        return "{\"role\":\"" + role + "\",\"parts\":[{\"text\":\"" + escapeJson(text) + "\"}]}";
    }

    // ─── Response-Auswertung (ohne JSON-Bibliothek) ───────────────────────────

    /** Sammelt alle "text"-Teile der ersten Antwort, ohne interne "thought"-Teile. */
    static String extractAnswerText(String json) {
        int end = json.length();
        for (String stop : new String[]{"\"groundingMetadata\"", "\"usageMetadata\"", "\"citationMetadata\""}) {
            int i = json.indexOf(stop);
            if (i != -1 && i < end) end = i;
        }
        String scope = json.substring(0, end);
        StringBuilder out = new StringBuilder();
        Matcher m = Pattern.compile("\"text\"\\s*:\\s*\"").matcher(scope);
        int pos = 0;
        while (m.find(pos)) {
            int[] endIdx = new int[1];
            String part = readJsonString(scope, m.end(), endIdx);
            // Teil-Objekt prüfen: {"text": "...", "thought": true} -> überspringen
            int objEnd = scope.indexOf('}', endIdx[0]);
            String tail = objEnd == -1 ? "" : scope.substring(endIdx[0], objEnd);
            int objStart = scope.lastIndexOf('{', m.start());
            String head = objStart == -1 ? "" : scope.substring(objStart, m.start());
            boolean thought = tail.matches("(?s).*\"thought\"\\s*:\\s*true.*") || head.matches("(?s).*\"thought\"\\s*:\\s*true.*");
            if (!thought) out.append(part);
            pos = endIdx[0];
        }
        return out.toString();
    }

    /** Liest einen JSON-String ab Position start (direkt hinter dem öffnenden "). */
    static String readJsonString(String json, int start, int[] endOut) {
        StringBuilder sb = new StringBuilder();
        int i = start;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '"') { i++; break; }
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(i + 1);
                switch (n) {
                    case 'n': sb.append('\n'); i += 2; continue;
                    case 't': sb.append('\t'); i += 2; continue;
                    case 'r': sb.append('\r'); i += 2; continue;
                    case 'b': sb.append('\b'); i += 2; continue;
                    case 'f': sb.append('\f'); i += 2; continue;
                    case 'u':
                        if (i + 5 < json.length()) {
                            try {
                                sb.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
                                i += 6;
                                continue;
                            } catch (NumberFormatException ignored) { }
                        }
                        sb.append(n); i += 2; continue;
                    default: sb.append(n); i += 2; continue; // \" \\ \/
                }
            }
            sb.append(c);
            i++;
        }
        endOut[0] = i;
        return sb.toString();
    }

    static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"").matcher(json);
        if (!m.find()) return "";
        return readJsonString(json, m.end(), new int[1]);
    }

    static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private int extractRetryDelaySeconds(String json) {
        Matcher m = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 10;
    }

    static String escapeJson(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (char c : text.toCharArray()) {
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    // ─── Hilfsfunktionen ──────────────────────────────────────────────────────

    private String getApiKey() {
        return Preferences.userNodeForPackage(SchroedingersBlockPlugin.class)
                .get(SchroedingersBlockPlugin.API_KEY_PREF, "");
    }

    public static File getBaseDirectory() {
        String pluginId = "com.schroedingersblock.plugin";
        try {
            for (Plugin plugin : PluginUtils.getPlugins()) {
                PluginDescriptor descriptor = plugin.getDescriptor();
                if (descriptor != null && pluginId.equals(descriptor.getID())) {
                    return descriptor.getPluginDirectory();
                }
            }
        } catch (Throwable ignored) { }
        return new File(System.getProperty("user.dir"), "plugins" + File.separator + pluginId);
    }

    /** Ergänzt Standardmodelle und zuletzt verwendete Modelle zu einer Liste ohne Duplikate. */
    public static List<String> mergeModelLists(List<String> fromApi, String current) {
        Set<String> set = new LinkedHashSet<>();
        if (current != null && !current.isBlank()) set.add(current);
        Collections.addAll(set, DEFAULT_MODELS);
        if (fromApi != null) set.addAll(fromApi);
        return new ArrayList<>(set);
    }

    private void log(String msg) {
        String prefixed = "[Schrödinger's Block] " + msg;
        try {
            javax.swing.SwingUtilities.invokeLater(() -> {
                try {
                    Application.getInstance().getGUILog().log(prefixed);
                } catch (Throwable ignored) { }
            });
        } catch (Throwable t) {
            System.out.println(prefixed);
        }
    }
}
