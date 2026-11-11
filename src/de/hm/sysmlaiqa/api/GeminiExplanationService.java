package de.hm.sysmlaiqa.api;

import com.nomagic.magicdraw.core.Application;
import de.hm.sysmlaiqa.core.SysmlAiQaPlugin;
import com.nomagic.magicdraw.plugins.Plugin;
import com.nomagic.magicdraw.plugins.PluginDescriptor;
import com.nomagic.magicdraw.plugins.PluginUtils;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anbindung an die Google Gemini API (generateContent).
 *
 * Modellübertragung:
 * - "cache" (Standard): Das Modell wird EINMAL als Gemini-Cache (cachedContents) hochgeladen.
 *   Danach geht pro Frage nur noch die Frage selbst raus. Neu hochgeladen wird nur, wenn
 *   der Nutzer "Resend model" klickt, sich der Export/Prompt/das KI-Modell ändert oder der Cache abläuft.
 *   Nicht jedes Modell/jeder Tarif unterstützt Caching – dann automatisch "inline" mit Hinweis.
 * - "inline": Das Modell geht mit jeder Anfrage mit (die API ist zustandslos).
 * In beiden Fällen ist der Gesprächsaufbau identisch: [Modelldaten] [Frage 1] [Antwort 1] [Frage 2] …
 *
 * Rate-Limits: Bei HTTP 429 (pro Minute) wird die von Google genannte Wartezeit abgewartet
 * und erneut versucht. Tageslimits werden erkannt und gemeldet.
 */
public class GeminiExplanationService implements ExplanationService {

    // Überschreibbar nur für Tests (-Dsysmlaiqa.apiBase=...)
    private static final String API_BASE = System.getProperty("sysmlaiqa.apiBase", "https://generativelanguage.googleapis.com/v1beta");

    /**
     * Vorschlagsliste für das Modell-Dropdown (Stand Okt. 2026; "Refresh" lädt die echte Liste).
     * Hinweis: gemini-3.5-flash existiert als Alias weiter, wird von Google aber auf gemini-3.6-flash umgeleitet.
     */
    public static final String[] DEFAULT_MODELS = {
            "gemini-3.8-flash",
            "gemini-3.6-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite"
    };

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(180);
    public static final String CANCELLED = "Cancelled by user.";
    private static final int CACHE_TTL_SEC = 3600;          // Upload gilt 60 min
    private static final long CACHE_SAFETY_MS = 2 * 60_000; // 2 min vor Ablauf neu hochladen

    private static final CircuitBreaker CIRCUIT = new CircuitBreaker();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    // Chatverlauf OHNE Modelldaten (die stehen inline davor bzw. im Cache)
    private final List<String> conversationHistory = Collections.synchronizedList(new ArrayList<>());
    // Derselbe Verlauf als Klartext (für "Show request…"): abwechselnd Frage-Nachricht, Antwort
    private final List<String> historyTexts = Collections.synchronizedList(new ArrayList<>());
    private boolean focusSentInChat = false;

    // Abbrechen (NFR4): bricht die laufende HTTP-Anfrage und alle Wartezeiten sofort ab
    private volatile boolean cancelled = false;
    private volatile CompletableFuture<HttpResponse<String>> running;
    // Eingabelimits der Modelle laut Google (models/{id}.inputTokenLimit), -1 = unbekannt
    private final Map<String, Integer> inputLimits = new HashMap<>();

    // Hochgeladene Modelle: Schlüssel = KI-Modell | Export | System-Prompt | Grounding
    private final Map<String, CacheEntry> caches = new HashMap<>();
    // KI-Modelle, bei denen Caching abgelehnt wurde (z. B. Free Tier) -> bis "Resend model" inline
    private final Map<String, String> cacheUnsupported = new HashMap<>();
    // KI-Modelle, die eine bestimmte Denktiefe ablehnen (Schlüssel: Modell|Stufe)
    private final Set<String> thinkingUnsupported = new HashSet<>();

    private static class CacheEntry {
        final String name;        // cachedContents/...
        final long expiresAtMs;
        final String aiModel;

        CacheEntry(String name, long expiresAtMs, String aiModel) {
            this.name = name;
            this.expiresAtMs = expiresAtMs;
            this.aiModel = aiModel;
        }
    }

    // ─── Circuit Breaker (nur für Fallback-Kaskade) ───────────────────────────
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

    // ═══════════════════════════════════════════════════════════════════════
    // Öffentliche API
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void resetConversation() {
        conversationHistory.clear();
        historyTexts.clear();
        focusSentInChat = false;
    }

    /** Bricht die laufende Anfrage ab (auch Wartezeiten bei Rate-Limits). Die Antwort lautet dann "Cancelled by user". */
    @Override
    public void cancel() {
        cancelled = true;
        CompletableFuture<HttpResponse<String>> f = running;
        if (f != null) f.cancel(true);
    }

    @Override
    public int previousTurns() {
        return conversationHistory.size() / 2;
    }

    /** "Resend model": alle Uploads löschen (auch bei Google), nächste Frage lädt neu hoch. */
    @Override
    public void discardUploadedModel() {
        List<CacheEntry> old;
        synchronized (caches) {
            old = new ArrayList<>(caches.values());
            caches.clear();
            cacheUnsupported.clear();
        }
        String apiKey = getApiKey();
        if (old.isEmpty() || apiKey.isEmpty()) return;
        Thread t = new Thread(() -> {
            for (CacheEntry e : old) deleteCache(e.name, apiKey);
        }, "SysML-AI-QA-cache-cleanup");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public Answer ask(String question, String modelJson, String focusInfo, AskSettings s) {
        long start = System.currentTimeMillis();
        String apiKey = getApiKey();
        if (apiKey.isEmpty()) {
            return Answer.error("No API key configured. Use \"API key...\" to enter one.", 0);
        }

        cancelled = false;
        if (s.freshChat) resetConversation();
        int previousTurns = previousTurns();
        boolean firstInChat = conversationHistory.isEmpty();
        String questionText = PromptLoader.buildQuestionMessage(focusInfo, question, firstInChat || !focusSentInChat);
        String questionTurn = contentJson("user", questionText);
        String systemPrompt = PromptLoader.buildSystemPrompt(getBaseDirectory(), s.requireIds, s.format);
        String modelTurn = contentJson("user", PromptLoader.buildModelMessage(modelJson, s.format));
        String exportSha = Hashing.sha256Short(modelJson);
        String promptSha = Hashing.sha256Short(systemPrompt);

        List<String> models = new ArrayList<>();
        models.add(s.model);
        if (s.allowFallback) {
            for (String m : DEFAULT_MODELS) if (!m.equals(s.model)) models.add(m);
        }

        RetryStats stats = new RetryStats();
        String lastError = "Unknown error.";
        boolean daily = false;
        for (String model : models) {
            if (cancelled) break;
            if (s.allowFallback && !CIRCUIT.isAvailable(model)) {
                log("Skipping " + model + " (temporarily blocked after an error)");
                continue;
            }

            // 1. Modell hochladen bzw. vorhandenen Upload verwenden
            String transfer = "inline";
            String cacheName = null;
            String note = "";
            if (s.cacheModel) {
                CacheResult cr = ensureCache(model, systemPrompt, modelTurn, s, exportSha, promptSha, apiKey, stats);
                if (cr.name != null) {
                    cacheName = cr.name;
                    transfer = cr.created ? "cache_new" : "cache";
                } else {
                    note = cr.message;
                    if (cr.dailyLimit) {
                        lastError = cr.message;
                        daily = true;
                        break;
                    }
                    if (cr.tooLarge) {          // zu groß für dieses Modell: inline würde genauso scheitern
                        lastError = cr.message;
                        continue;
                    }
                    if (cancelled) break;
                }
            }

            // 2. Frage senden (Denktiefe wie eingestellt; lehnt das Modell sie ab, einmal ohne)
            String thinking = effectiveThinking(model, s.thinkingLevel);
            Attempt a = callModel(model, buildRequestBody(s, systemPrompt, modelTurn, questionTurn, cacheName, thinking), apiKey, s, stats);
            if (a.answer == null && !"default".equals(thinking) && a.error != null && a.error.toLowerCase().contains("think")) {
                synchronized (thinkingUnsupported) { thinkingUnsupported.add(model + "|" + thinking); }
                note = (note.isEmpty() ? "" : note + " ") + model + " does not support thinking level \"" + thinking
                        + "\" – used the model's default instead.";
                thinking = "default";
                a = callModel(model, buildRequestBody(s, systemPrompt, modelTurn, questionTurn, cacheName, thinking), apiKey, s, stats);
            }
            if (a.cacheMissing && cacheName != null) {
                // Cache bei Google abgelaufen/gelöscht -> einmal neu hochladen
                forgetCache(cacheKey(model, exportSha, promptSha, s.grounding));
                CacheResult cr = ensureCache(model, systemPrompt, modelTurn, s, exportSha, promptSha, apiKey, stats);
                cacheName = cr.name;
                transfer = cacheName != null ? "cache_new" : "inline";
                if (cacheName == null) note = cr.message;
                a = callModel(model, buildRequestBody(s, systemPrompt, modelTurn, questionTurn, cacheName, thinking), apiKey, s, stats);
            }

            if (a.answer != null) {
                conversationHistory.add(questionTurn);
                conversationHistory.add(contentJson("model", a.answer.text));
                historyTexts.add(questionText);
                historyTexts.add(a.answer.text);
                if (focusInfo != null) focusSentInChat = true;
                Answer res = new Answer(a.answer.text, model, true, System.currentTimeMillis() - start,
                        a.answer.promptTokens, a.answer.answerTokens, a.answer.finishReason);
                res.modelVersion = a.answer.modelVersion;
                res.cachedTokens = a.answer.cachedTokens;
                res.thoughtsTokens = a.answer.thoughtsTokens;
                res.toolTokens = a.answer.toolTokens;
                res.totalTokens = a.answer.totalTokens;
                res.thinkingUsed = thinking;
                res.transfer = transfer;
                res.rateLimitWaitSec = stats.waitedSec;
                res.note = note;
                res.userMessage = questionText;
                res.previousTurns = previousTurns;
                return res;
            }
            lastError = a.error;
            if (a.dailyLimit) { daily = true; break; }
            if (a.fatal || cancelled) break;
        }

        Answer err = cancelled
                ? new Answer(CANCELLED, "-", false, System.currentTimeMillis() - start, -1, -1, "CANCELLED")
                : Answer.error(lastError, System.currentTimeMillis() - start);
        err.rateLimitWaitSec = stats.waitedSec;
        err.dailyLimit = daily;
        err.userMessage = questionText;
        err.previousTurns = previousTurns;
        return err;
    }

    /** Liste der Modelle, die generateContent unterstützen (für das Dropdown). Leer bei Fehlern. */
    public List<String> listAvailableModels() {
        List<String> result = new ArrayList<>();
        String apiKey = getApiKey();
        if (apiKey.isEmpty()) return result;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/models?pageSize=1000&key=" + apiKey))
                    .timeout(Duration.ofSeconds(20))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log("Could not load model list (HTTP " + resp.statusCode() + ")");
                return result;
            }
            String json = resp.body();
            Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"models/([^\"]+)\"").matcher(json);
            List<Integer> starts = new ArrayList<>();
            List<String> names = new ArrayList<>();
            while (m.find()) {
                names.add(m.group(1));
                starts.add(m.start());
            }
            for (int i = 0; i < names.size(); i++) {
                int to = (i + 1 < names.size()) ? starts.get(i + 1) : json.length();
                String section = json.substring(starts.get(i), to);
                if (section.contains("\"generateContent\"") && names.get(i).startsWith("gemini")) {
                    result.add(names.get(i));
                }
            }
            Collections.sort(result);
        } catch (Exception e) {
            log("Could not load model list: " + e.getMessage());
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Cache (Modell einmal hochladen)
    // ═══════════════════════════════════════════════════════════════════════

    private static class CacheResult {
        String name;        // null = kein Cache, inline senden
        boolean created;
        boolean dailyLimit;
        boolean tooLarge;
        String message = "";
    }

    private static String cacheKey(String model, String exportSha, String promptSha, boolean grounding) {
        return model + "|" + exportSha + "|" + promptSha + "|" + grounding;
    }

    private void forgetCache(String key) {
        synchronized (caches) {
            caches.remove(key);
        }
    }

    private CacheResult ensureCache(String model, String systemPrompt, String modelTurn, AskSettings s,
                                    String exportSha, String promptSha, String apiKey, RetryStats stats) {
        CacheResult r = new CacheResult();
        String key = cacheKey(model, exportSha, promptSha, s.grounding);
        List<CacheEntry> outdated = new ArrayList<>();
        synchronized (caches) {
            if (cacheUnsupported.containsKey(model)) {
                return r; // schon gemeldet -> still inline senden (steht als model_transfer=inline im Log)
            }
            CacheEntry e = caches.get(key);
            if (e != null && System.currentTimeMillis() < e.expiresAtMs - CACHE_SAFETY_MS) {
                r.name = e.name;
                return r;
            }
            // Ältere Uploads desselben KI-Modells (anderer Export/Prompt) werden ersetzt
            caches.entrySet().removeIf(en -> {
                if (en.getValue().aiModel.equals(model)) {
                    outdated.add(en.getValue());
                    return true;
                }
                return false;
            });
        }
        for (CacheEntry old : outdated) deleteCache(old.name, apiKey);

        StringBuilder body = new StringBuilder("{");
        body.append("\"model\":\"models/").append(escapeJson(model)).append("\",");
        body.append("\"displayName\":\"SysML-AI-QA ").append(exportSha).append("\",");
        body.append("\"systemInstruction\":{\"parts\":[{\"text\":\"").append(escapeJson(systemPrompt)).append("\"}]},");
        if (s.grounding) body.append("\"tools\":[{\"google_search\":{}}],");
        body.append("\"contents\":[").append(modelTurn).append("],");
        body.append("\"ttl\":\"").append(CACHE_TTL_SEC).append("s\"}");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/cachedContents?key=" + apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .timeout(REQUEST_TIMEOUT)
                .build();
        log("Uploading model for " + model + " (cache, valid " + (CACHE_TTL_SEC / 60) + " min) ...");
        Sent sent = sendWithRateLimit(req, model, s, stats);
        if (sent.response == null) {
            r.message = "Model upload failed (" + sent.error + ") – model is sent with each question instead.";
            r.dailyLimit = sent.dailyLimit;
            if (sent.dailyLimit) r.message = sent.error;
            return r;
        }
        int status = sent.response.statusCode();
        String resp = sent.response.body();
        Matcher nm = Pattern.compile("\"(cachedContents/[^\"]+)\"").matcher(resp);
        if (status == 200 && nm.find()) {
            long expires = System.currentTimeMillis() + CACHE_TTL_SEC * 1000L;
            String exp = extractString(resp, "expireTime");
            try {
                if (!exp.isEmpty()) expires = Instant.parse(exp).toEpochMilli();
            } catch (Exception ignored) { }
            synchronized (caches) {
                caches.put(key, new CacheEntry(nm.group(1), expires, model));
            }
            r.name = nm.group(1);
            r.created = true;
            log("Model uploaded: " + r.name + " (" + extractInt(resp, "totalTokenCount") + " tokens)");
            return r;
        }
        if (status == 400 && isTokenLimit(resp)) {
            r.tooLarge = true;
            r.message = tokenLimitMessage(model, shorten(extractString(resp, "message"), 300));
            return r;
        }
        // Caching nicht möglich (z. B. Free Tier für dieses Modell, Modell zu klein) -> inline bis "Resend model"
        String msg = "Caching not available for " + model + " (HTTP " + status + ": "
                + shorten(extractString(resp, "message"), 200) + "). The model is sent with each question instead.";
        synchronized (caches) {
            cacheUnsupported.put(model, msg);
        }
        log(msg);
        r.message = msg;
        return r;
    }

    private void deleteCache(String name, String apiKey) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/" + name + "?key=" + apiKey))
                    .timeout(Duration.ofSeconds(20))
                    .DELETE().build();
            httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignored) {
            // Läuft nach TTL ohnehin ab
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HTTP
    // ═══════════════════════════════════════════════════════════════════════

    private static class RetryStats {
        int waitedSec = 0;
    }

    private static class Sent {
        HttpResponse<String> response; // null bei Fehler
        String error = "";
        boolean dailyLimit;
        boolean rateLimited;
    }

    /** Sendet eine Anfrage; wartet bei HTTP 429 (pro Minute) die von Google genannte Zeit und versucht es erneut. */
    private Sent sendWithRateLimit(HttpRequest request, String model, AskSettings s, RetryStats stats) {
        Sent out = new Sent();
        int attempt = 0;
        int overloadAttempt = 0;
        while (true) {
            try {
                if (cancelled) {
                    out.error = CANCELLED;
                    return out;
                }
                HttpResponse<String> resp = sendCancellable(request);
                if (resp.statusCode() == 503 || resp.statusCode() == 500) {
                    // Google überlastet ("high demand") – meist nach Sekunden vorbei: kurz warten und erneut versuchen
                    overloadAttempt++;
                    if (overloadAttempt <= 3) {
                        int wait = 5 * overloadAttempt; // 5 s, 10 s, 15 s
                        log(model + ": overloaded (HTTP " + resp.statusCode() + "), retrying in " + wait + " s (" + overloadAttempt + "/3)");
                        sleepCancellable(wait * 1000L);
                        stats.waitedSec += wait;
                        continue;
                    }
                    out.response = resp;
                    return out;
                }
                if (resp.statusCode() != 429) {
                    out.response = resp;
                    return out;
                }
                String body = resp.body();
                String apiMsg = shorten(extractString(body, "message"), 300);
                if (body.contains("PerDay")) {
                    out.dailyLimit = true;
                    out.error = "Daily limit reached for " + model + " (HTTP 429). Try again tomorrow or use another model. " + apiMsg;
                    return out;
                }
                int wait = extractRetryDelaySeconds(body);
                attempt++;
                if (attempt > s.max429Retries || wait > s.max429WaitSec) {
                    out.rateLimited = true;
                    out.error = "Rate limit for " + model + " (HTTP 429, Google suggests waiting " + wait + " s). " + apiMsg;
                    return out;
                }
                log(model + ": per-minute rate limit, waiting " + wait + " s (attempt " + attempt + "/" + s.max429Retries + ")");
                sleepCancellable((wait + 1) * 1000L);
                stats.waitedSec += wait + 1;
            } catch (InterruptedException | java.util.concurrent.CancellationException ie) {
                out.error = CANCELLED;
                return out;
            } catch (java.net.http.HttpTimeoutException e) {
                out.error = "Timeout: " + model + " did not answer within " + REQUEST_TIMEOUT.getSeconds() + " s. "
                        + "Try again, reduce \"Thinking\" or choose a smaller context.";
                return out;
            } catch (java.io.IOException e) {
                out.error = connectionError(e);
                return out;
            }
        }
    }

    private static class Attempt {
        Answer answer;
        String error;
        boolean fatal;
        boolean dailyLimit;
        boolean cacheMissing;
    }

    private Attempt callModel(String model, String body, String apiKey, AskSettings s, RetryStats stats) {
        Attempt result = new Attempt();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/models/" + model + ":generateContent?key=" + apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(REQUEST_TIMEOUT)
                .build();
        log("Sending question to " + model + " (" + String.format("%.1f", body.length() / 1024.0) + " KB) ...");
        long t0 = System.currentTimeMillis();
        Sent sent = sendWithRateLimit(request, model, s, stats);
        if (sent.response == null) {
            result.error = sent.error;
            result.dailyLimit = sent.dailyLimit;
            result.fatal = CANCELLED.equals(sent.error);
            if (!result.fatal) CIRCUIT.recordFailure(model);
            return result;
        }

        int status = sent.response.statusCode();
        String resp = sent.response.body();
        if (status == 200) {
            CIRCUIT.recordSuccess(model);
            String text = extractAnswerText(resp);
            String finish = extractString(resp, "finishReason");
            if (text.isBlank()) {
                // FR12: leere Antwort ist ein Fehler mit konkretem Grund – keine "erfolgreiche" leere Zeile im Log
                String blockReason = extractString(resp, "blockReason");
                result.error = emptyAnswerMessage(model, finish, blockReason, extractInt(resp, "thoughtsTokenCount"));
                return result;
            }
            if ("MAX_TOKENS".equals(finish)) {
                text += "\n\n[Answer cut off: max. output tokens reached. Increase \"Max tokens\".]";
            }
            Answer a = new Answer(text, model, true, 0,
                    extractInt(resp, "promptTokenCount"), extractInt(resp, "candidatesTokenCount"), finish);
            a.modelVersion = extractString(resp, "modelVersion");
            a.cachedTokens = extractInt(resp, "cachedContentTokenCount");
            a.thoughtsTokens = extractInt(resp, "thoughtsTokenCount");
            a.toolTokens = extractInt(resp, "toolUsePromptTokenCount");
            a.totalTokens = extractInt(resp, "totalTokenCount");
            if (a.totalTokens < 0 && a.promptTokens >= 0) {   // falls Google die Summe nicht liefert: selbst addieren
                a.totalTokens = a.promptTokens + Math.max(0, a.answerTokens) + Math.max(0, a.thoughtsTokens) + Math.max(0, a.toolTokens);
            }
            log(model + " (" + a.modelVersion + ") answered in " + (System.currentTimeMillis() - t0) + " ms, Tokens in/out: "
                    + a.promptTokens + "/" + a.answerTokens + (a.cachedTokens > 0 ? ", from cache: " + a.cachedTokens : ""));
            result.answer = a;
            return result;
        }

        String apiMessage = shorten(extractString(resp, "message"), 300);
        if ((status == 403 || status == 404 || status == 400) && resp.toLowerCase().contains("cachedcontent")
                && (resp.contains("not found") || resp.contains("NOT_FOUND") || resp.contains("expired") || resp.contains("PERMISSION_DENIED"))) {
            result.cacheMissing = true;
            result.error = "Uploaded model no longer available: " + apiMessage;
            return result;
        }
        if ((status == 400 || status == 403) && (resp.contains("API_KEY_INVALID") || resp.contains("API key not valid"))) {
            result.error = "Invalid API key (HTTP " + status + "). Enter a new one via \"API key...\".";
            result.fatal = true;
            return result;
        }
        if (status == 400 && isTokenLimit(resp)) {
            result.error = tokenLimitMessage(model, apiMessage);
            result.fatal = true;
            return result;
        }
        if (status == 400) {
            result.error = "Request rejected by " + model + " (HTTP 400): " + apiMessage;
            result.fatal = true;
            return result;
        }
        CIRCUIT.recordFailure(model);
        result.error = model + " failed (HTTP " + status + "): " + apiMessage
                + (status == 503 ? " Tried 4 times. Try again in a minute or choose another AI model (e.g. gemini-3.6-flash)." : "");
        return result;
    }

    /**
     * Mit Cache: nur Verlauf + Frage (System-Prompt, Modell und Tools liegen im Cache – die API erlaubt sie
     * dann nicht zusätzlich in der Anfrage). Ohne Cache: System-Prompt + Modell + Verlauf + Frage.
     */
    private String effectiveThinking(String model, String requested) {
        String t = requested == null ? "default" : requested.toLowerCase();
        synchronized (thinkingUnsupported) {
            return thinkingUnsupported.contains(model + "|" + t) ? "default" : t;
        }
    }

    private String buildRequestBody(AskSettings s, String systemPrompt, String modelTurn, String questionTurn, String cacheName,
                                    String thinking) {
        List<String> contents = new ArrayList<>();
        if (cacheName == null) contents.add(modelTurn);
        contents.addAll(conversationHistory);
        contents.add(questionTurn);

        StringBuilder sb = new StringBuilder("{");
        if (cacheName != null) {
            sb.append("\"cachedContent\":\"").append(escapeJson(cacheName)).append("\",");
        } else {
            sb.append("\"systemInstruction\":{\"parts\":[{\"text\":\"").append(escapeJson(systemPrompt)).append("\"}]},");
            if (s.grounding) sb.append("\"tools\":[{\"google_search\":{}}],");
        }
        sb.append("\"contents\":[").append(String.join(",", contents)).append("],");
        sb.append("\"generationConfig\":{")
                .append("\"temperature\":").append(s.temperature).append(",")
                .append("\"maxOutputTokens\":").append(s.maxOutputTokens);
        if (thinking != null && !"default".equals(thinking)) {
            sb.append(",\"thinkingConfig\":{\"thinkingLevel\":\"").append(escapeJson(thinking)).append("\"}");
        }
        sb.append("}}");
        return sb.toString();
    }

    private String contentJson(String role, String text) {
        return "{\"role\":\"" + role + "\",\"parts\":[{\"text\":\"" + escapeJson(text) + "\"}]}";
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Abbrechen, Fehlermeldungen (FR12, NFR4)
    // ═══════════════════════════════════════════════════════════════════════

    /** Wie httpClient.send, aber jederzeit über {@link #cancel()} abbrechbar. */
    private HttpResponse<String> sendCancellable(HttpRequest request) throws java.io.IOException, InterruptedException {
        CompletableFuture<HttpResponse<String>> f = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        running = f;
        try {
            while (true) {
                if (cancelled) {
                    f.cancel(true);
                    throw new InterruptedException("cancelled");
                }
                try {
                    return f.get(200, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // weiter warten, Abbruch prüfen
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable c = ee.getCause();
                    if (c instanceof java.io.IOException) throw (java.io.IOException) c;
                    throw new java.io.IOException(c == null ? ee.toString() : c.toString(), c);
                }
            }
        } finally {
            running = null;
        }
    }

    private void sleepCancellable(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cancelled) throw new InterruptedException("cancelled");
            Thread.sleep(Math.min(200, Math.max(1, end - System.currentTimeMillis())));
        }
    }

    /** Konkrete Meldung, wenn die API nicht erreichbar ist (kein Internet, Proxy/Firewall, DNS). */
    static String connectionError(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String what = root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
        if (root instanceof java.net.UnknownHostException || root instanceof java.net.ConnectException
                || root instanceof java.nio.channels.UnresolvedAddressException) {
            return "Google Gemini API not reachable (" + what + "). Check the internet connection; "
                    + "a company or university network may block generativelanguage.googleapis.com (proxy/firewall/VPN).";
        }
        if (root instanceof javax.net.ssl.SSLException) {
            return "Secure connection to the Gemini API failed (" + what + "). A proxy or antivirus may be intercepting HTTPS.";
        }
        return "Connection to the Gemini API failed (" + what + "). Try again in a moment.";
    }

    /** FR12: leere Antwort – warum, und was hilft. */
    static String emptyAnswerMessage(String model, String finish, String blockReason, int thoughts) {
        StringBuilder sb = new StringBuilder("Empty answer from ").append(model).append(" (finishReason=")
                .append(finish.isEmpty() ? "none" : finish);
        if (!blockReason.isEmpty()) sb.append(", blockReason=").append(blockReason);
        sb.append("). ");
        if ("MAX_TOKENS".equals(finish)) {
            sb.append("The output limit was used up").append(thoughts > 0 ? " by internal thinking (" + thoughts + " tokens)" : "")
                    .append(" before any text was written. Increase \"Max tokens\" or lower \"Thinking\".");
        } else if (!blockReason.isEmpty() || "SAFETY".equals(finish) || "PROHIBITED_CONTENT".equals(finish)
                || "BLOCKLIST".equals(finish) || "RECITATION".equals(finish)) {
            sb.append("Google's content filter blocked the answer. Rephrase the question.");
        } else {
            sb.append("The model returned no text. Try again or choose another AI model.");
        }
        return sb.toString();
    }

    static boolean isTokenLimit(String resp) {
        String l = resp.toLowerCase();
        return l.contains("token") && (l.contains("exceed") || l.contains("too long") || l.contains("too large")
                || l.contains("maximum number of tokens") || l.contains("input token count"));
    }

    /** FR12: Export zu groß für das Eingabelimit des Modells. */
    String tokenLimitMessage(String model, String apiMessage) {
        int limit = inputTokenLimit(model);
        return "Model data too large for " + model + (limit > 0 ? String.format(" (input limit %,d tokens)", limit) : "")
                + ". Choose a smaller context (selected packages or element surroundings), the other export format, "
                + "or an AI model with a larger input limit. Google: " + apiMessage;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tokenzahl und Eingabelimit (FR2)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Exakte Tokenzahl der Modelldaten-Nachricht laut Google (countTokens; zählt nicht gegen das Anfrage-Kontingent).
     * @return Tokens oder -1, wenn die Zählung nicht möglich war
     */
    public int countTokens(String model, String modelContent, String format) {
        String apiKey = getApiKey();
        if (apiKey.isEmpty()) return -1;
        String body = "{\"contents\":[" + contentJson("user", PromptLoader.buildModelMessage(modelContent, format)) + "]}";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/models/" + model + ":countTokens?key=" + apiKey))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(60))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log("countTokens for " + model + " failed (HTTP " + resp.statusCode() + ")");
                return -1;
            }
            return extractInt(resp.body(), "totalTokens");
        } catch (Exception e) {
            log("countTokens for " + model + " failed: " + e.getMessage());
            return -1;
        }
    }

    /** Eingabelimit des Modells in Tokens laut Google (einmal abgefragt, dann gemerkt); -1 = unbekannt. */
    public int inputTokenLimit(String model) {
        synchronized (inputLimits) {
            Integer v = inputLimits.get(model);
            if (v != null) return v;
        }
        int limit = -1;
        String apiKey = getApiKey();
        if (!apiKey.isEmpty()) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(API_BASE + "/models/" + model + "?key=" + apiKey))
                        .timeout(Duration.ofSeconds(20))
                        .GET().build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) limit = extractInt(resp.body(), "inputTokenLimit");
            } catch (Exception ignored) { }
        }
        synchronized (inputLimits) {
            inputLimits.put(model, limit);
        }
        return limit;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Vorschau der Anfrage (FR3, NFR3)
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public RequestPreview preview(String question, String modelContent, String focusInfo, AskSettings s) {
        RequestPreview p = new RequestPreview();
        PromptLoader.Template tpl = PromptLoader.loadTemplate(getBaseDirectory(), s.format);
        p.systemPrompt = PromptLoader.fill(tpl.text, s.requireIds, s.format);
        p.systemPromptSource = tpl.source + (tpl.file != null ? " (" + tpl.file.getAbsolutePath() + ")" : "");
        p.modelMessage = PromptLoader.buildModelMessage(modelContent, s.format);
        boolean fresh = s.freshChat || s.repetitions > 1;
        if (!fresh) p.history.addAll(historyTexts);
        boolean firstInChat = fresh || conversationHistory.isEmpty();
        p.questionMessage = PromptLoader.buildQuestionMessage(focusInfo, question, firstInChat || !focusSentInChat);
        String thinking = effectiveThinking(s.model, s.thinkingLevel);
        p.generationConfig = "model: " + s.model + "\ntemperature: " + s.temperature + "\nmaxOutputTokens: " + s.maxOutputTokens
                + "\nthinkingLevel: " + thinking + "\ngrounding (Google Search): " + (s.grounding ? "on" : "off");
        p.transferNote = s.cacheModel
                ? "System prompt and model data are uploaded once (Gemini cache) and then referenced; the content is identical."
                : "System prompt and model data are sent with every request.";
        return p;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Response-Auswertung (ohne JSON-Bibliothek)
    // ═══════════════════════════════════════════════════════════════════════

    /** Sammelt alle "text"-Teile der Antwort, ohne interne "thought"-Teile. */
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
                    default: sb.append(n); i += 2; continue;
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

    static int extractRetryDelaySeconds(String json) {
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

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + " …";
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Hilfsfunktionen
    // ═══════════════════════════════════════════════════════════════════════

    private String getApiKey() {
        return Preferences.userNodeForPackage(SysmlAiQaPlugin.class)
                .get(SysmlAiQaPlugin.API_KEY_PREF, "");
    }

    public static File getBaseDirectory() {
        String pluginId = "de.hm.sysmlaiqa.plugin";
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

    /** Nur für Tests: Anzahl hochgeladener Modelle. */
    int uploadedCount() {
        synchronized (caches) {
            return caches.size();
        }
    }

    /** Nur für Tests: als nicht cachebar markierte Modelle. */
    Set<String> unsupportedModels() {
        synchronized (caches) {
            return new HashSet<>(cacheUnsupported.keySet());
        }
    }

    private void log(String msg) {
        String prefixed = SysmlAiQaPlugin.LOG_PREFIX + msg;
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
