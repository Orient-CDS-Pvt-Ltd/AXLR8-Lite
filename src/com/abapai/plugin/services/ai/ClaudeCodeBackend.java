// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.abapai.plugin.services.ai;

import com.abapai.plugin.preferences.ABAPAIPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.*;

/**
 * AIBackend implementation using the Claude Code CLI subprocess (claude -p).
 * No API key required — runs on the user's local Claude Code subscription.
 *
 * Features:
 *   - Per-task model resolution: heavy/complex tasks use Opus,
 *     quick tasks (chat, review) use Sonnet.
 *   - Auto-retry on rate limit: up to 3 attempts with exponential back-off.
 *   - Context auto-trim: silently trims middle conversation turns when the
 *     flat prompt approaches the model context limit.
 *   - Tool use via text protocol: <tool_call>{...}</tool_call> blocks in
 *     responses are parsed into ToolCall objects, matching ToolUseResponse.
 */
public class ClaudeCodeBackend implements AIBackend {

    private static final Logger LOG = Logger.getLogger(ClaudeCodeBackend.class.getName());

    private static final Pattern TOOL_CALL_PAT =
        Pattern.compile("<tool_call>\\s*(\\{[\\s\\S]*?\\})\\s*</tool_call>", Pattern.DOTALL);
    private static final Pattern RETRY_AFTER_PAT =
        Pattern.compile("(?:retry.?after[:\\s]+)(\\d+)", Pattern.CASE_INSENSITIVE);

    // Rough char count above which we trim old turns (~150K chars ≈ 37K tokens)
    private static final int TRIM_THRESHOLD = 150_000;

    private final String claudePath;
    private final String modelHeavy;
    private final String modelLight;
    private final String onRateLimit;

    private final AtomicBoolean            cancelled      = new AtomicBoolean(false);
    private final AtomicReference<Process> currentProcess = new AtomicReference<>();

    // ── Rate limit exception (internal only) ──────────────────────────────────

    private static final class RateLimitException extends RuntimeException {
        final int retryAfterSeconds;
        RateLimitException(int retryAfterSeconds) {
            super("rate_limit");
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    public ClaudeCodeBackend() {
        String saved = ABAPAIPreferences.getClaudeCodePath();
        this.claudePath   = resolveClaudePath(saved);
        this.modelHeavy   = ABAPAIPreferences.getClaudeCodeModelHeavy();
        this.modelLight   = ABAPAIPreferences.getClaudeCodeModelLight();
        this.onRateLimit  = ABAPAIPreferences.getClaudeCodeOnRateLimit();
    }

    /**
     * Resolve the Claude CLI command to launch.
     *
     * <p>If the user configured an explicit path, trust it. Otherwise the
     * default is the bare command {@code claude} — which Java's
     * {@link ProcessBuilder} can launch directly on macOS/Linux (PATH lookup),
     * but NOT on Windows: ProcessBuilder does not consult {@code PATHEXT}, so a
     * batch launcher like {@code claude.cmd} is invisible to a bare
     * {@code "claude"} and fails with "CreateProcess error=2". To make the
     * out-of-the-box experience work on Windows, probe the common npm-global
     * install locations by fast file-existence check (no subprocess, no UI
     * block) and return the first {@code claude.cmd} found.
     */
    private static String resolveClaudePath(String configured) {
        if (configured != null && !configured.isBlank() && !configured.equals("claude")) {
            return configured; // explicit user override — trust it
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String home = System.getProperty("user.home", "");
            String appdata = System.getenv("APPDATA");
            String[] candidates = {
                (appdata == null ? home + "\\AppData\\Roaming" : appdata) + "\\npm\\claude.cmd",
                home + "\\AppData\\Roaming\\npm\\claude.cmd",
                home + "\\AppData\\Roaming\\npm\\claude.exe",
                "C:\\Program Files\\nodejs\\claude.cmd",
            };
            for (String c : candidates) {
                try { if (new java.io.File(c).isFile()) return c; } catch (Throwable ignore) {}
            }
        }
        return "claude"; // macOS/Linux resolve via PATH; or last-resort on Windows
    }

    // ── Per-task model resolution ─────────────────────────────────────────────

    private String resolveModel(AITaskProfile profile) {
        if (profile == null) return modelHeavy;
        // Planning: defer to the planning.model.mode preference. Default "auto"
        // picks the light model on Claude Code because the subprocess doesn't
        // stream tokens \u2014 a faster model matters more than extra reasoning.
        if (profile == AITaskProfile.PLANNING) {
            String mode = ABAPAIPreferences.getPlanningModelMode();
            if ("heavy".equals(mode)) return modelHeavy;
            return modelLight; // "auto" or "light"
        }
        return switch (profile) {
            case HEAVY_CODE, AGENT -> modelHeavy;
            default                -> modelLight;
        };
    }

    // ── Blocking completion ────────────────────────────────────────────────────

    @Override
    public Result complete(String systemPrompt, List<JSONObject> messages,
                           int maxTokens, String reasoning,
                           AITaskProfile profile, boolean fallback) throws Exception {
        List<JSONObject> trimmed = trimIfNeeded(messages);
        String text = runWithRetry(buildFlatPrompt(systemPrompt, trimmed), resolveModel(profile));
        return new Result(text, false);
    }

    // ── Streaming ─────────────────────────────────────────────────────────────
    //
    // Claude Code CLI (claude -p) does not stream token-by-token — it runs the full
    // prompt and emits the complete response when done. Reading from the subprocess
    // pipe with no deadline causes an indefinite hang. We run the subprocess through
    // runWithRetry() (which enforces the 180 s hard timeout) and emit a keepalive
    // tick every 10 s so the UI does not look frozen during long planning calls.

    @Override
    public String stream(String systemPrompt, List<JSONObject> messages,
                         int maxTokens, Consumer<String> onToken) throws Exception {
        cancelled.set(false);
        List<JSONObject> trimmed = trimIfNeeded(messages);
        String prompt = buildFlatPrompt(systemPrompt, trimmed);

        // Keepalive: emit a dot every 10 s so the planning UI shows progress
        AtomicReference<String> resultHolder = new AtomicReference<>(null);
        AtomicReference<Throwable> errorHolder = new AtomicReference<>(null);

        Thread worker = new Thread(() -> {
            try {
                resultHolder.set(runWithRetry(prompt, modelLight));
            } catch (Throwable t) {
                errorHolder.set(t);
            }
        }, "ClaudeCode-stream-worker");
        worker.setDaemon(true);
        worker.start();

        int elapsed = 0;
        while (worker.isAlive()) {
            worker.join(10_000);  // wait up to 10 s
            if (worker.isAlive() && !cancelled.get()) {
                elapsed += 10;
                onToken.accept("[Claude Code working... " + elapsed + "s]\n");
            }
        }

        if (cancelled.get()) return "";

        Throwable err = errorHolder.get();
        if (err != null) {
            if (err instanceof Exception ex) throw ex;
            throw new RuntimeException(err);
        }

        String full = resultHolder.get();
        if (full != null && !full.isBlank()) {
            onToken.accept(full);
        }
        return full != null ? full.trim() : "";
    }

    @Override
    public void cancel() {
        cancelled.set(true);
        Process p = currentProcess.getAndSet(null);
        if (p != null) p.destroyForcibly();
    }

    @Override
    public int maxOutputTokens() {
        // Per-model output caps. Claude Code runs the local Claude CLI
        // subprocess, which targets the underlying Anthropic model, so the
        // same documented caps apply.
        // Source: https://platform.claude.com/docs/en/about-claude/models/overview
        String heavy = ABAPAIPreferences.getClaudeCodeModelHeavy();
        String light = ABAPAIPreferences.getClaudeCodeModelLight();
        // The "active" model for sizing is the heavier of the two configured —
        // Claude Code may route either depending on task; cap at the larger
        // so neither path is starved.
        String active = chooseLargerModel(heavy, light);
        return claudeModelMaxOutput(active);
    }

    @Override
    public int getContextWindow() {
        String heavy = ABAPAIPreferences.getClaudeCodeModelHeavy();
        String light = ABAPAIPreferences.getClaudeCodeModelLight();
        String active = chooseLargerModel(heavy, light);
        return claudeModelContextWindow(active);
    }

    /** Pick the model with the larger output cap (so {@link #maxOutputTokens}
     *  reports the more permissive value when Claude Code can route to either
     *  the heavy or light model). */
    private static String chooseLargerModel(String heavy, String light) {
        int h = claudeModelMaxOutput(heavy);
        int l = claudeModelMaxOutput(light);
        return h >= l ? heavy : light;
    }

    /** Per-model output cap, keyed by the configured Claude model. */
    private static int claudeModelMaxOutput(String model) {
        if (model == null) return 32_768;
        if (model.equals("claude-opus-4-8"))         return 128_000;
        if (model.equals("claude-opus-4-7"))         return 128_000;
        if (model.equals("claude-opus-4-6"))         return 128_000;
        if (model.equals("claude-sonnet-4-6"))       return  64_000;
        if (model.startsWith("claude-sonnet-4-5"))   return  64_000;
        if (model.startsWith("claude-opus-4-5"))     return  64_000;
        if (model.startsWith("claude-opus-4-1"))     return  32_000;
        if (model.startsWith("claude-opus-4-2"))     return  32_000;
        if (model.startsWith("claude-sonnet-4-2"))   return  64_000;
        if (model.startsWith("claude-haiku-4-5"))    return  64_000;
        if (model.contains("3-5") || model.contains("3.5")) return 8_192;
        if (model.contains("haiku"))                 return 8_192;
        return 32_768;
    }

    /** Per-model context window, keyed by the configured Claude model. */
    private static int claudeModelContextWindow(String model) {
        if (model == null) return 200_000;
        if (model.equals("claude-opus-4-8"))         return 1_000_000;
        if (model.equals("claude-opus-4-7"))         return 1_000_000;
        if (model.equals("claude-opus-4-6"))         return 1_000_000;
        if (model.equals("claude-sonnet-4-6"))       return 1_000_000;
        return 200_000;
    }

    // ── Tool use via text protocol ─────────────────────────────────────────────

    @Override
    public ToolUseResponse completeWithTools(String systemPrompt, List<JSONObject> messages,
                                              JSONArray toolDefs, int maxTokens) throws Exception {
        List<JSONObject> trimmed  = trimIfNeeded(messages);
        String fullSystem = systemPrompt + "\n\n" + buildToolInstructions(toolDefs);
        // Agent fix loops are heavy tasks — always use the heavy model for tool calls
        String response   = runWithRetry(buildFlatPrompt(fullSystem, trimmed), modelHeavy);

        List<ToolCall> calls = parseToolCalls(response);
        if (calls.isEmpty()) {
            return new ToolUseResponse("stop", response, List.of(), null);
        }

        String text = TOOL_CALL_PAT.matcher(response).replaceAll("").strip();
        JSONObject assistantMsg = new JSONObject()
            .put("role", "assistant")
            .put("content", response);

        return new ToolUseResponse("tool_use", text, calls, assistantMsg);
    }

    @Override
    public void addToolResults(List<JSONObject> messages,
                                ToolUseResponse response,
                                Map<String, String> resultsByCallId) {
        if (response.rawAssistantMsg != null) {
            messages.add(response.rawAssistantMsg);
        }
        StringBuilder sb = new StringBuilder();
        for (ToolCall call : response.toolCalls) {
            String result = resultsByCallId.getOrDefault(call.id(), "(no result)");
            sb.append("<tool_result id=\"").append(call.id())
              .append("\" tool=\"").append(call.name()).append("\">\n")
              .append(result)
              .append("\n</tool_result>\n\n");
        }
        messages.add(new JSONObject()
            .put("role", "user")
            .put("content", sb.toString().strip()));
    }

    // ── Retry logic ───────────────────────────────────────────────────────────

    private String runWithRetry(String prompt, String model) throws Exception {
        int maxAttempts = "retry".equals(onRateLimit) ? 3 : 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return runSubprocess(prompt, model);
            } catch (RateLimitException e) {
                if (attempt == maxAttempts) {
                    throw new RuntimeException(
                        "Claude Code rate limit persists after " + maxAttempts + " retries. " +
                        "Consider upgrading to Claude Code Max or adding an API key as fallback.");
                }
                int wait = Math.max(e.retryAfterSeconds, 30);
                LOG.warning("Claude Code rate limit — waiting " + wait + "s before retry "
                    + (attempt + 1) + "/" + maxAttempts);
                Thread.sleep(wait * 1000L);
            }
        }
        throw new RuntimeException("Unexpected exit from retry loop");
    }

    // ── Subprocess execution ───────────────────────────────────────────────────
    // Prompt is written to stdin on a dedicated thread to prevent pipe-buffer
    // deadlock on Windows (pipe buffer ~64 KB; large FSD prompts exceed this).
    // Timeout is read from preferences (default 15 min) so heavy cross-artifact
    // fixes on Opus have headroom, and advanced users can tune it without a rebuild.

    private String runSubprocess(String prompt, String model) throws Exception {
        cancelled.set(false);
        byte[] promptBytes = prompt.getBytes(StandardCharsets.UTF_8);

        Process p = new ProcessBuilder(buildCommand(model))
            .redirectErrorStream(false)
            .start();
        currentProcess.set(p);
        try {
            // Write stdin on a separate thread — prevents deadlock when prompt
            // exceeds the OS pipe buffer before the subprocess starts draining it.
            Thread stdinWriter = new Thread(() -> {
                try (java.io.OutputStream stdin = p.getOutputStream()) {
                    stdin.write(promptBytes);
                } catch (java.io.IOException ignored) {
                    // Process may have exited; ignore broken pipe
                }
            }, "ClaudeCode-stdin");
            stdinWriter.setDaemon(true);
            stdinWriter.start();

            Future<String> stdoutF = ForkJoinPool.commonPool().submit(
                () -> new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            Future<String> stderrF = ForkJoinPool.commonPool().submit(
                () -> new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));

            int timeoutS = ABAPAIPreferences.getClaudeCodeTimeoutSeconds();
            boolean done = p.waitFor(timeoutS, TimeUnit.SECONDS);
            currentProcess.set(null);

            if (!done) {
                p.destroyForcibly();
                throw new RuntimeException(
                    "Claude Code did not finish in " + (timeoutS / 60) + " minutes. " +
                    "Increase the timeout in Preferences > Orient AXLR8 > Claude Code, " +
                    "or press Stop and retry with a smaller scope.");
            }
            if (cancelled.get()) throw new InterruptedException("Cancelled");

            String stdout = stdoutF.get(5, TimeUnit.SECONDS);
            String stderr = stderrF.get(5, TimeUnit.SECONDS);
            String errSrc = stderr.isBlank() ? stdout : stderr;

            if (p.exitValue() != 0) {
                if (isRateLimit(errSrc)) {
                    throw new RateLimitException(parseRetryAfter(errSrc));
                }
                throw new RuntimeException(parseErrorMessage(errSrc));
            }
            return stdout.trim();
        } finally {
            currentProcess.set(null);
        }
    }

    private List<String> buildCommand(String model) {
        return List.of(claudePath, "-p", "--model", model);
    }

    // ── Context trim ──────────────────────────────────────────────────────────

    private List<JSONObject> trimIfNeeded(List<JSONObject> messages) {
        if (messages == null || messages.size() <= 2) return messages;

        int total = messages.stream()
            .mapToInt(m -> extractContent(m).length())
            .sum();
        if (total <= TRIM_THRESHOLD) return messages;

        // Keep first message (initial context) + last 3 exchanges (6 turns)
        int keepEnd   = Math.min(6, messages.size() - 1);
        int omitCount = messages.size() - 1 - keepEnd;
        LOG.info("ClaudeCodeBackend: trimming " + omitCount + " middle messages (prompt was "
            + total + " chars)");

        List<JSONObject> result = new ArrayList<>();
        result.add(messages.get(0));
        if (omitCount > 0) {
            result.add(new JSONObject()
                .put("role", "user")
                .put("content", "[" + omitCount + " earlier messages trimmed to stay within context limit]"));
        }
        result.addAll(messages.subList(messages.size() - keepEnd, messages.size()));
        return result;
    }

    // ── Prompt helpers ────────────────────────────────────────────────────────

    private String buildFlatPrompt(String system, List<JSONObject> messages) {
        StringBuilder sb = new StringBuilder();
        if (system != null && !system.isBlank())
            sb.append("SYSTEM:\n").append(system).append("\n\n---\n\n");
        for (JSONObject m : messages) {
            String role = m.optString("role", "user").toUpperCase();
            sb.append(role).append(":\n").append(extractContent(m)).append("\n\n");
        }
        return sb.toString().trim();
    }

    private String extractContent(JSONObject msg) {
        Object content = msg.opt("content");
        if (content instanceof String s) return s;
        if (content instanceof JSONArray arr) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject part;
                try { part = arr.getJSONObject(i); } catch (Exception ignored) { continue; }
                switch (part.optString("type", "text")) {
                    case "text"        -> sb.append(part.optString("text", ""));
                    case "tool_result" -> sb.append(extractToolResultText(part));
                    case "tool_use"    ->
                        sb.append("[tool_call: ").append(part.optString("name", "?")).append("]");
                }
            }
            return sb.toString();
        }
        return content != null ? content.toString() : "";
    }

    private String extractToolResultText(JSONObject r) {
        Object c = r.opt("content");
        if (c instanceof String s) return s;
        if (c instanceof JSONArray arr) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p;
                try { p = arr.getJSONObject(i); } catch (Exception ignored) { continue; }
                if ("text".equals(p.optString("type", "")))
                    sb.append(p.optString("text", ""));
            }
            return sb.toString();
        }
        return c != null ? c.toString() : "";
    }

    // ── Tool instructions ──────────────────────────────────────────────────────

    private String buildToolInstructions(JSONArray tools) {
        StringBuilder sb = new StringBuilder("""
            ## Tool Use Protocol

            When you need to call a tool, emit a <tool_call> block with valid JSON:

            <tool_call>
            {"id": "call_1", "name": "tool_name", "input": {"param": "value"}}
            </tool_call>

            Rules:
            - Use a unique id per call (call_1, call_2, ...)
            - One <tool_call> block per tool invocation
            - Do NOT invent results — wait for <tool_result> blocks in the next message
            - Emit reasoning text before tool_call blocks as needed

            ## Available Tools

            """);

        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool;
            try { tool = tools.getJSONObject(i); } catch (Exception ignored) { continue; }
            String name = tool.optString("name", "");
            String desc = tool.optString("description", "");
            sb.append("### ").append(name).append("\n");
            if (!desc.isBlank()) sb.append(desc).append("\n");
            JSONObject schema = tool.optJSONObject("input_schema");
            if (schema != null) {
                JSONObject props  = schema.optJSONObject("properties");
                JSONArray  reqArr = schema.optJSONArray("required");
                Set<String> req   = new HashSet<>();
                if (reqArr != null)
                    for (int j = 0; j < reqArr.length(); j++) req.add(reqArr.getString(j));
                if (props != null) {
                    sb.append("Parameters:\n");
                    for (String key : props.keySet()) {
                        JSONObject prop  = props.optJSONObject(key);
                        String     type  = prop != null ? prop.optString("type", "string") : "string";
                        String     pdesc = prop != null ? prop.optString("description", "") : "";
                        sb.append("  - ").append(key).append(" (").append(type).append(")");
                        if (req.contains(key)) sb.append(" [required]");
                        if (!pdesc.isBlank()) sb.append(": ").append(pdesc);
                        sb.append("\n");
                    }
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ── Tool call parsing ──────────────────────────────────────────────────────

    private List<ToolCall> parseToolCalls(String response) {
        List<ToolCall> calls = new ArrayList<>();
        Matcher m = TOOL_CALL_PAT.matcher(response);
        int seq = 1;
        while (m.find()) {
            try {
                JSONObject j     = new JSONObject(m.group(1).trim());
                String     id    = j.optString("id", "call_" + seq++);
                String     name  = j.optString("name", "");
                JSONObject input = j.optJSONObject("input");
                if (input == null) input = new JSONObject();
                if (!name.isBlank()) calls.add(new ToolCall(id, name, input));
            } catch (Exception e) {
                LOG.warning("ClaudeCodeBackend: failed to parse <tool_call>: " + e.getMessage());
            }
        }
        return calls;
    }

    // ── Error helpers ──────────────────────────────────────────────────────────

    private boolean isRateLimit(String s) {
        String lower = s.toLowerCase();
        return lower.contains("rate limit") || lower.contains("429") ||
               lower.contains("too many requests");
    }

    private int parseRetryAfter(String stderr) {
        Matcher m = RETRY_AFTER_PAT.matcher(stderr);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return 60; // default wait
    }

    private String parseErrorMessage(String raw) {
        if (raw == null || raw.isBlank())
            return "Claude Code process failed (no output). " +
                "Ensure 'claude' is on PATH and you are logged in.";
        String lower = raw.toLowerCase();
        if (isRateLimit(raw))
            return "Claude Code: rate limit reached. Wait a moment and retry.";
        if (lower.contains("subscription") && (lower.contains("limit") || lower.contains("exceed")))
            return "Claude Code: monthly usage limit reached. " +
                "Upgrade to Max plan or add a Claude API key as fallback.";
        if (lower.contains("not logged in") || lower.contains("not authenticated") ||
            lower.contains("authentication required") || lower.contains("login required"))
            return "Claude Code: not authenticated. Go to Preferences > Orient and click Re-authenticate.";
        if (lower.contains("context") && lower.contains("length"))
            return "Claude Code: input too long even after trimming. Start a new session.";
        if (lower.contains("model") && (lower.contains("not found") || lower.contains("not available")))
            return "Selected model (" + modelHeavy + ") is not available on your Claude Code plan. " +
                "Try claude-sonnet-4-6 in Preferences > Orient.";
        if (lower.contains("command not found") || lower.contains("no such file") ||
            lower.contains("is not recognized"))
            return "Claude Code CLI not found. Install from claude.ai/code and restart Eclipse.";
        return raw.length() > 400 ? raw.substring(0, 400) + "\u2026" : raw.trim();
    }
}
