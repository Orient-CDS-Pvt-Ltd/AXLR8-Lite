// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.abapai.plugin.services.ai;

import com.abapai.plugin.preferences.ABAPAIPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * GitHub Models backend.
 *
 * Uses GitHub's official inference REST endpoint with PAT auth.
 *
 * <p><b>Single-flight contract</b> (same as OpenAI/Claude/Gemini backends):
 * each {@code GitHubModelsBackend} instance is single-flight — at most one
 * {@code complete()} / {@code stream()} / {@code completeWithTools()} call at
 * a time. The instance fields {@code activeConn} and {@code cancelled} are
 * overwritten on every call; concurrent calls from multiple threads on the
 * same instance will corrupt cancellation state. Callers needing parallelism
 * must create separate instances; the chat layer serializes on a single
 * backend instance.
 */
public class GitHubModelsBackend implements AIBackend {

    private static final Logger LOG = Logger.getLogger(GitHubModelsBackend.class.getName());

    private static final String API_URL = "https://models.github.ai/inference/chat/completions";
    private static final int TIMEOUT_MS = 120_000;

    private volatile HttpURLConnection activeConn;
    private volatile boolean cancelled;

    private final String overridePat;
    private final String overrideModel;

    public record RateLimitInfo(int limit, int remaining, long resetEpoch) {
        public int used() { return limit - remaining; }
        public String resetIn() {
            long diff = resetEpoch - (System.currentTimeMillis() / 1000);
            if (diff <= 0) return "now";
            long h = diff / 3600; long m = (diff % 3600) / 60;
            if (h > 0) return h + "h " + m + "m";
            return m + "m";
        }
    }

    private volatile RateLimitInfo lastRateLimit;

    public RateLimitInfo getLastRateLimit() { return lastRateLimit; }

    public GitHubModelsBackend() {
        this.overridePat = null;
        this.overrideModel = null;
    }

    public GitHubModelsBackend(String pat, String model) {
        this.overridePat = pat;
        this.overrideModel = model;
    }

    @Override
    public Result complete(String systemPrompt,
                           List<JSONObject> messages,
                           int maxTokens,
                           String reasoning,
                           AITaskProfile profile,
                           boolean fallback) throws Exception {
        return completeWithRetry(systemPrompt, messages, maxTokens, profile, fallback, true);
    }

    private Result completeWithRetry(String systemPrompt,
                                     List<JSONObject> messages,
                                     int maxTokens,
                                     AITaskProfile profile,
                                     boolean fallback,
                                     boolean allowCapRetry) throws Exception {
        cancelled = false;
        String pat = resolvePat();
        if (pat == null || pat.isBlank()) {
            throw new IllegalStateException(
                "GitHub Models token not set. Configure at: Window > Preferences > Orient AXLR8");
        }

        JSONArray msgs = new JSONArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            msgs.put(new JSONObject(Map.of("role", "system", "content", systemPrompt)));
        }
        messages.forEach(msgs::put);

        JSONObject body = new JSONObject();
        body.put("model", overrideModel != null ? overrideModel : resolveModel(profile, fallback));
        body.put("messages", msgs);
        body.put("max_tokens", maxTokens);

        HttpURLConnection conn = openConn(API_URL, pat);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String raw = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);

        if (cancelled) throw new InterruptedIOException("Request cancelled.");
        captureRateLimit(conn);
        if (code < 200 || code >= 300) {
            if (allowCapRetry && code == 400) {
                int actualCap = extractMaxTokensCap(raw);
                if (actualCap > 0 && actualCap < maxTokens) {
                    LOG.warning("GitHub Models rejected max_tokens=" + maxTokens
                        + "; retrying with provider-reported cap=" + actualCap
                        + " (update maxOutputTokens() table for this model)");
                    return completeWithRetry(systemPrompt, messages, actualCap,
                        profile, fallback, false);
                }
            }
            throw new IOException("GitHub Models API error " + code + ": " + raw);
        }

        JSONObject resp = new JSONObject(raw);
        JSONObject choice = resp.getJSONArray("choices").getJSONObject(0);
        String text = choice.getJSONObject("message").optString("content", "");
        boolean truncated = "length".equalsIgnoreCase(choice.optString("finish_reason", ""));
        return new Result(text, truncated);
    }

    @Override
    public String stream(String systemPrompt,
                         List<JSONObject> messages,
                         int maxTokens,
                         Consumer<String> onToken) throws Exception {
        return streamInner(systemPrompt, messages, maxTokens, onToken, true);
    }

    private String streamInner(String systemPrompt,
                               List<JSONObject> messages,
                               int maxTokens,
                               Consumer<String> onToken,
                               boolean allowCapRetry) throws Exception {
        cancelled = false;
        String pat = resolvePat();
        if (pat == null || pat.isBlank()) {
            throw new IllegalStateException(
                "GitHub Models token not set. Configure at: Window > Preferences > Orient AXLR8");
        }

        JSONArray msgs = new JSONArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            msgs.put(new JSONObject(Map.of("role", "system", "content", systemPrompt)));
        }
        messages.forEach(msgs::put);

        String model = overrideModel != null ? overrideModel
            : resolveModel(AITaskProfile.CHAT_LITE, false);

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", msgs);
        body.put("max_tokens", maxTokens);
        body.put("stream", true);

        HttpURLConnection conn = openConn(API_URL, pat);
        conn.setRequestProperty("Accept", "text/event-stream");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            InputStream es = conn.getErrorStream();
            String err = es == null ? "" : new String(es.readAllBytes(), StandardCharsets.UTF_8);
            if (allowCapRetry && code == 400) {
                int actualCap = extractMaxTokensCap(err);
                if (actualCap > 0 && actualCap < maxTokens) {
                    LOG.warning("GitHub Models stream rejected max_tokens=" + maxTokens
                        + "; retrying with cap=" + actualCap);
                    return streamInner(systemPrompt, messages, actualCap, onToken, false);
                }
            }
            throw new IOException("GitHub Models API error " + code + ": " + err);
        }

        StringBuilder full = new StringBuilder();
        StringBuilder pending = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelled) throw new InterruptedIOException("Request cancelled.");
                if (!line.startsWith("data: ")) continue;
                String payload = line.substring(6).trim();
                if ("[DONE]".equals(payload) || payload.isBlank()) continue;

                JSONObject event = new JSONObject(payload);
                JSONArray choices = event.optJSONArray("choices");
                if (choices == null || choices.length() == 0) continue;
                JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                if (delta == null) continue;

                String token = delta.optString("content", "");
                if (token.isEmpty()) continue;

                full.append(token);
                pending.append(token);
                int nl;
                while ((nl = pending.indexOf("\n")) >= 0) {
                    String seg = pending.substring(0, nl + 1);
                    pending.delete(0, nl + 1);
                    onToken.accept(seg);
                }
            }
        }
        if (!pending.isEmpty()) onToken.accept(pending.toString());
        return full.toString();
    }

    @Override
    public ToolUseResponse completeWithTools(String systemPrompt,
                                             List<JSONObject> messages,
                                             JSONArray toolDefs,
                                             int maxTokens) throws Exception {
        return completeWithToolsInner(systemPrompt, messages, toolDefs, maxTokens, true);
    }

    private ToolUseResponse completeWithToolsInner(String systemPrompt,
                                                   List<JSONObject> messages,
                                                   JSONArray toolDefs,
                                                   int maxTokens,
                                                   boolean allowCapRetry) throws Exception {
        cancelled = false;
        String pat = resolvePat();
        if (pat == null || pat.isBlank()) {
            throw new IllegalStateException(
                "GitHub Models token not set. Configure at: Window > Preferences > Orient AXLR8");
        }

        JSONArray msgs = new JSONArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            msgs.put(new JSONObject(Map.of("role", "system", "content", systemPrompt)));
        }
        messages.forEach(msgs::put);

        JSONArray tools = new JSONArray();
        for (int i = 0; i < toolDefs.length(); i++) {
            JSONObject t = toolDefs.getJSONObject(i);
            JSONObject fn = new JSONObject()
                .put("name", t.optString("name", "tool"))
                .put("description", t.optString("description", ""))
                .put("parameters", t.optJSONObject("input_schema") != null
                    ? t.getJSONObject("input_schema")
                    : new JSONObject().put("type", "object").put("properties", new JSONObject()));
            tools.put(new JSONObject().put("type", "function").put("function", fn));
        }

        JSONObject body = new JSONObject();
        body.put("model", resolveModel(AITaskProfile.AGENT, false));
        body.put("messages", msgs);
        body.put("max_tokens", maxTokens);
        body.put("tools", tools);
        body.put("tool_choice", "auto");

        HttpURLConnection conn = openConn(API_URL, pat);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String raw = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
        if (code < 200 || code >= 300) {
            if (allowCapRetry && code == 400) {
                int actualCap = extractMaxTokensCap(raw);
                if (actualCap > 0 && actualCap < maxTokens) {
                    LOG.warning("GitHub Models tool-use rejected max_tokens=" + maxTokens
                        + "; retrying with cap=" + actualCap);
                    return completeWithToolsInner(systemPrompt, messages, toolDefs, actualCap, false);
                }
            }
            throw new IOException("GitHub Models tool-use error " + code + ": " + raw);
        }

        JSONObject resp = new JSONObject(raw);
        JSONObject choice = resp.getJSONArray("choices").getJSONObject(0);
        JSONObject msg = choice.optJSONObject("message");
        if (msg == null) {
            return new ToolUseResponse("stop", "", List.of(), null);
        }

        String text = msg.optString("content", "");
        JSONArray tc = msg.optJSONArray("tool_calls");
        List<ToolCall> calls = new ArrayList<>();
        if (tc != null) {
            for (int i = 0; i < tc.length(); i++) {
                JSONObject c = tc.getJSONObject(i);
                String id = c.optString("id", "call_" + (i + 1));
                JSONObject fn = c.optJSONObject("function");
                if (fn == null) continue;
                String name = fn.optString("name", "");
                JSONObject input;
                String rawArgs = fn.optString("arguments", "{}");
                try {
                    input = new JSONObject(rawArgs);
                } catch (Exception ex) {
                    LOG.warning("Tool call '" + name + "' has malformed JSON arguments — "
                        + "treating as empty input. Raw: "
                        + (rawArgs.length() > 200 ? rawArgs.substring(0, 200) + "..." : rawArgs));
                    input = new JSONObject();
                }
                if (!name.isBlank()) calls.add(new ToolCall(id, name, input));
            }
        }
        return new ToolUseResponse(calls.isEmpty() ? "stop" : "tool_use", text, calls, msg);
    }

    @Override
    public void addToolResults(List<JSONObject> messages,
                               ToolUseResponse response,
                               Map<String, String> resultsByCallId) {
        JSONObject assistant = new JSONObject().put("role", "assistant");
        if (response.rawAssistantMsg != null) {
            Object content = response.rawAssistantMsg.opt("content");
            if (content != null) assistant.put("content", content);
            JSONArray toolCalls = response.rawAssistantMsg.optJSONArray("tool_calls");
            if (toolCalls != null) assistant.put("tool_calls", toolCalls);
        } else if (!response.textContent.isBlank()) {
            assistant.put("content", response.textContent);
        } else {
            assistant.put("content", "");
        }
        messages.add(assistant);

        for (ToolCall call : response.toolCalls) {
            JSONObject tr = new JSONObject()
                .put("role", "tool")
                .put("tool_call_id", call.id())
                .put("content", resultsByCallId.getOrDefault(call.id(), "(no result)"));
            messages.add(tr);
        }
    }

    @Override
    public void cancel() {
        cancelled = true;
        HttpURLConnection c = activeConn;
        if (c != null) c.disconnect();
    }

    /**
     * Per-model completion-token caps enforced by GitHub Models.
     * GitHub Models re-hosts third-party models with its own (lower) caps
     * than the direct provider APIs — e.g. Claude Sonnet 4.6 direct allows
     * 64K output, but via GitHub Models it caps at 16384 (confirmed by a
     * live 400: "max_tokens is too large: 24576. This model supports at
     * most 16384 completion tokens").
     *
     * <p>If a model isn't listed here we default to 16384 (the most common
     * cap we've observed). Update this table when GitHub raises a cap, or
     * when you see a 400 "max_tokens too large" in the log.
     */
    /**
     * Per-model output-token caps for GitHub Models inference API.
     *
     * <p>Values come from the catalog at
     * {@code GET https://models.github.ai/catalog/models}. Each branch lists
     * the model's documented {@code limits.max_output_tokens}. The static
     * table is best-effort; the auto-retry safety net in
     * {@code completeWithRetry} reads the provider-reported cap from a
     * {@code 400 max_tokens too large} error and self-corrects if a model's
     * cap changes.
     */
    @Override
    public int maxOutputTokens() {
        String model = overrideModel != null ? overrideModel
            : ABAPAIPreferences.getGitHubModelsModel();
        if (model == null) return 4_096;
        String m = model.toLowerCase();

        // ── OpenAI family — wildly varied caps; split by sub-family.
        if (m.startsWith("openai/")) {
            if (m.contains("gpt-5"))                       return 100_000;
            if (m.contains("o1") && m.contains("mini"))    return 65_536;
            if (m.contains("o1") && m.contains("preview")) return 32_768;
            if (m.contains("o1") || m.contains("o3") || m.contains("o4")) return 100_000;
            if (m.contains("gpt-4.1"))                     return 32_768;  // mini + nano too
            if (m.contains("gpt-4o-mini"))                 return 4_096;
            if (m.contains("gpt-4o"))                      return 16_384;
            if (m.contains("embedding"))                   return 0;       // embeddings endpoint, not chat
            return 16_384;                                                 // unknown openai default
        }

        // ── Microsoft Phi-4 (the standalone) is unusual: 16K out.
        if (m.contains("microsoft/phi-4") && !m.contains("mini") && !m.contains("multimodal") && !m.contains("reasoning"))
            return 16_384;

        // ── Everything else in the catalog caps at 4,096 out per
        //    /catalog/models (Llama, Mistral, DeepSeek, Cohere, xAI, AI21,
        //    Phi mini/multimodal/reasoning, Microsoft MAI-DS-R1).
        return 4_096;
    }

    @Override
    public int getContextWindow() {
        String model = overrideModel != null ? overrideModel
            : ABAPAIPreferences.getGitHubModelsModel();
        if (model == null) return 128_000;
        String m = model.toLowerCase();

        // OpenAI input caps (per catalog).
        if (m.startsWith("openai/")) {
            if (m.contains("gpt-4.1"))    return 1_048_576;  // 1M
            if (m.contains("gpt-5"))      return 200_000;
            if (m.contains("o1") && m.contains("mini"))    return 128_000;
            if (m.contains("o1") && m.contains("preview")) return 128_000;
            if (m.contains("o1") || m.contains("o3") || m.contains("o4")) return 200_000;
            if (m.contains("gpt-4o"))     return 131_072;
            return 128_000;
        }

        // Meta Llama-4: huge contexts.
        if (m.contains("llama-4") && m.contains("scout"))     return 10_000_000;
        if (m.contains("llama-4") && m.contains("maverick"))  return 1_000_000;
        if (m.contains("llama-3"))                            return 128_000;

        // Mistral.
        if (m.contains("codestral"))                          return 256_000;
        if (m.contains("ministral"))                          return 131_072;
        if (m.contains("mistral-medium") || m.contains("mistral-small")) return 128_000;

        // DeepSeek / Cohere / xAI / Phi mini family / MAI-DS-R1.
        if (m.contains("deepseek"))                           return 128_000;
        if (m.contains("cohere"))                             return 131_072;
        if (m.contains("xai/") || m.contains("grok"))         return 131_072;
        if (m.contains("phi-4") && m.contains("reasoning") && !m.contains("mini"))
                                                              return 32_768;
        if (m.contains("phi-4-mini") || m.contains("phi-4-multimodal"))
                                                              return 128_000;
        if (m.contains("microsoft/phi-4"))                    return 16_384;
        if (m.contains("microsoft/mai-ds-r1"))                return 128_000;

        // AI21 Jamba.
        if (m.contains("jamba"))                              return 262_144;

        return 128_000;
    }

    private volatile String lastHeaderDump;

    public String getLastHeaderDump() { return lastHeaderDump; }

    private void captureRateLimit(HttpURLConnection conn) {
        try {
            StringBuilder dump = new StringBuilder();
            java.util.Map<String, java.util.List<String>> headers = conn.getHeaderFields();
            if (headers != null) {
                for (var entry : headers.entrySet()) {
                    String key = entry.getKey();
                    if (key == null) continue;
                    String lower = key.toLowerCase();
                    if (lower.contains("rate") || lower.contains("limit")
                            || lower.contains("remaining") || lower.contains("reset")
                            || lower.contains("quota") || lower.contains("usage")
                            || lower.contains("retry")) {
                        dump.append(key).append(": ").append(String.join(", ", entry.getValue())).append("\n");
                    }
                }
            }
            lastHeaderDump = dump.isEmpty() ? "No rate-limit headers found in response." : dump.toString().trim();

            int limit = firstValidInt(conn,
                "X-RateLimit-Limit", "x-ratelimit-limit", "RateLimit-Limit", "ratelimit-limit");
            int remaining = firstValidInt(conn,
                "X-RateLimit-Remaining", "x-ratelimit-remaining", "RateLimit-Remaining", "ratelimit-remaining");
            long reset = firstValidLong(conn,
                "X-RateLimit-Reset", "x-ratelimit-reset", "RateLimit-Reset", "ratelimit-reset");
            if (limit > 0 && remaining >= 0) {
                lastRateLimit = new RateLimitInfo(limit, remaining, reset);
            }
        } catch (Exception ignored) {}
    }

    private int firstValidInt(HttpURLConnection conn, String... names) {
        for (String n : names) {
            int v = parseIntHeader(conn, n, -1);
            if (v >= 0) return v;
        }
        return -1;
    }

    private long firstValidLong(HttpURLConnection conn, String... names) {
        for (String n : names) {
            long v = parseLongHeader(conn, n, -1L);
            if (v >= 0) return v;
        }
        return -1L;
    }

    private int parseIntHeader(HttpURLConnection conn, String name, int def) {
        String v = conn.getHeaderField(name);
        if (v == null || v.isBlank()) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private long parseLongHeader(HttpURLConnection conn, String name, long def) {
        String v = conn.getHeaderField(name);
        if (v == null || v.isBlank()) return def;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    /**
     * Parses GitHub Models' "max_tokens too large: X. This model supports
     * at most Y completion tokens" error and returns Y, or -1 if not found.
     * Used by the auto-retry path when our static cap table is out of date.
     */
    private static int extractMaxTokensCap(String errorJson) {
        if (errorJson == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("supports at most (\\d+)\\s+completion tokens")
            .matcher(errorJson);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private String resolvePat() {
        return overridePat != null ? overridePat : ABAPAIPreferences.getGitHubModelsPat();
    }

    private String resolveModel(AITaskProfile profile, boolean fallback) {
        String heavy = ABAPAIPreferences.getGitHubModelsModel();
        if (heavy == null || heavy.isBlank()) heavy = ABAPAIPreferences.DEFAULT_GITHUB_MODELS_MODEL;
        String light = ABAPAIPreferences.getGitHubModelsModelLight();
        if (light == null || light.isBlank()) light = ABAPAIPreferences.DEFAULT_GITHUB_MODELS_MODEL_LIGHT;

        boolean useLite = switch (profile) {
            case CHAT_LITE, INLINE_COMPLETION, BUG_SCAN, SEARCH, DOCUMENTATION -> !fallback;
            default -> fallback;
        };
        return useLite ? light : heavy;
    }

    private HttpURLConnection openConn(String url, String pat) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        activeConn = conn;
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + pat);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("X-GitHub-Api-Version", "2026-03-10");
        conn.setRequestProperty("Content-Type", "application/json");
        return conn;
    }
}

