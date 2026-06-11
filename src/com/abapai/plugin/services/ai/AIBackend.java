// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.abapai.plugin.services.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * ══════════════════════════════════════════════════════
 * AIBackend — Provider-agnostic AI completion interface
 * ══════════════════════════════════════════════════════
 *
 * Implementations: ClaudeCodeBackend, GitHubModelsBackend. Callers go
 * through this interface so the chat layer stays provider-independent.
 */
public interface AIBackend {

    /**
     * Result of a non-streaming completion call.
     *
     * @param text      The generated text.
     * @param truncated true when the model hit its token limit and the
     *                  response may be incomplete (the caller may retry with
     *                  a larger token budget).
     */
    record Result(String text, boolean truncated) {}

    /**
     * Blocking completion call.
     *
     * @param systemPrompt System/developer instructions (empty string → backend default).
     * @param messages     Conversation turns in OpenAI role/content format,
     *                     WITHOUT a system message (that goes into systemPrompt).
     * @param maxTokens    Max output tokens.
     * @param reasoning    Reasoning level hint: "low" | "medium" | "high".
     *                     Backends translate this to their own parameter format.
     * @param profile      Task profile used for model tier selection.
     * @param fallback     true → prefer the lighter/fallback model variant.
     */
    Result complete(String systemPrompt,
                    List<JSONObject> messages,
                    int maxTokens,
                    String reasoning,
                    AITaskProfile profile,
                    boolean fallback) throws Exception;

    /**
     * Streaming completion for low-latency use cases (inline completion).
     * Tokens are delivered to {@code onToken} as they arrive.
     *
     * @return The full assembled response text.
     */
    String stream(String systemPrompt,
                  List<JSONObject> messages,
                  int maxTokens,
                  Consumer<String> onToken) throws Exception;

    /**
     * Cancel any in-progress HTTP request on this backend.
     * Safe to call from any thread.
     */
    void cancel();

    /**
     * Maximum output tokens this backend's current model supports.
     * Used by callers to cap dynamic token budgets so the API never
     * rejects the request.  Implementations should return a
     * conservative default based on the configured model.
     */
    default int maxOutputTokens() { return 16384; }

    /**
     * Total context window size (input + output) for the configured model.
     * Used to compute how many output tokens are available after accounting
     * for the input.  Implementations override with model-specific values.
     */
    default int getContextWindow() { return 128_000; }

    // ─── Tool use (provider-agnostic) ────────────────────────────

    /** A single tool call requested by the model. */
    record ToolCall(String id, String name, JSONObject input) {}

    /** Normalized response from a tool-use API call. */
    class ToolUseResponse {
        public final String         stopReason;       // "tool_use" or "stop"
        public final String         textContent;      // any text the model emitted alongside tools
        public final List<ToolCall> toolCalls;
        public final JSONObject     rawAssistantMsg;  // ready to append to conversation

        public ToolUseResponse(String stopReason, String textContent,
                                List<ToolCall> toolCalls, JSONObject rawAssistantMsg) {
            this.stopReason      = stopReason;
            this.textContent     = textContent != null ? textContent : "";
            this.toolCalls       = toolCalls   != null ? toolCalls   : List.of();
            this.rawAssistantMsg = rawAssistantMsg;
        }
        public boolean hasToolCalls() { return !toolCalls.isEmpty(); }
    }

    /**
     * Tool-use completion. Tool definitions use Claude's schema format:
     * {@code [{"name":"...", "description":"...", "input_schema":{...}}]}.
     * Each backend translates to its own wire format internally.
     */
    default ToolUseResponse completeWithTools(String systemPrompt,
                                               List<JSONObject> messages,
                                               JSONArray toolDefs,
                                               int maxTokens) throws Exception {
        throw new UnsupportedOperationException(
            "Tool use not supported by current AI backend. Switch to Claude or OpenAI in Preferences.");
    }

    /**
     * Append the assistant turn + tool results to the conversation in this
     * backend's wire format. Mutates {@code messages} in-place.
     *
     * @param messages         conversation to append to
     * @param response         the last ToolUseResponse
     * @param resultsByCallId  toolCall.id → result string
     */
    default void addToolResults(List<JSONObject> messages,
                                 ToolUseResponse response,
                                 Map<String, String> resultsByCallId) {
        throw new UnsupportedOperationException(
            "Tool use not supported by current AI backend.");
    }
}
