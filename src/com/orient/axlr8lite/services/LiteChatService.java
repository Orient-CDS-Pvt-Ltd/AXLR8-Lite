// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.orient.axlr8lite.services;

import com.abapai.plugin.services.ai.AIBackend;
import com.abapai.plugin.services.ai.AIServiceFactory;
import com.abapai.plugin.services.ai.AITaskProfile;
import com.abapai.plugin.services.ai.TokenBudgetAllocator;
import com.abapai.plugin.services.backend.BackendModeService;
import com.abapai.plugin.services.context.FileContextService.FileContext;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * AXLR8 Lite chat orchestrator.
 *
 * <p>Builds the system + user prompt for a chat turn, dispatches it through
 * the configured backend with streaming, and emits filtered tokens to the
 * UI via {@code onToken}.
 *
 * <p>Stateful — each instance holds the multi-turn conversation history.
 * One per chat view (the view creates one and reuses it across turns).
 *
 * <p>What it WIRES UP:
 *   <ul>
 *     <li>Backend selection via {@link AIServiceFactory#createFreshBackend()}
 *         — respects the user's preference (Claude Code or GitHub Models),
 *         plus the fallback chain</li>
 *     <li>Mode-aware system prompt: inline suffix based on
 *         {@link BackendModeService#isEhp8Mode()}</li>
 *     <li>Optional editor context via {@link FileContextService}</li>
 *     <li>Streaming with progress-marker filtering — keepalive tokens
 *         like {@code [Claude Code working... 30s]} are dropped before
 *         they reach the chat display</li>
 *   </ul>
 */
public class LiteChatService {

    private static final Logger LOG = Logger.getLogger(LiteChatService.class.getName());

    private final List<JSONObject> conversation = new ArrayList<>();
    private final AIBackend backend;

    public LiteChatService() {
        this.backend = AIServiceFactory.createFreshBackend();
    }

    /**
     * Send one user turn through the backend.
     *
     * @param userText      the new user message
     * @param editorContext the active editor's content, captured by the caller
     *                      ON THE UI THREAD before dispatching to a worker.
     *                      Pass {@code null} (or {@link FileContext#EMPTY}) to
     *                      omit editor context. Workbench APIs must not be
     *                      touched from this method's (background) thread, so
     *                      the capture has to happen upstream.
     * @param onToken       streamed-token consumer (UI appends to chat display)
     * @return the full assembled assistant response
     * @throws Exception on backend failure
     */
    public String send(String userText, FileContext editorContext,
                       Consumer<String> onToken) throws Exception {

        String systemPrompt = buildSystemPrompt();
        String augmented    = augmentUserMessage(userText, editorContext);

        conversation.add(new JSONObject().put("role", "user").put("content", augmented));

        int budget = TokenBudgetAllocator.allocate(
            AITaskProfile.CHAT_LITE,
            TokenBudgetAllocator.estimateChars(systemPrompt, conversation, null),
            "low",
            backend);

        // Filter progress markers (keepalive "[... working ... 30s]" tokens
        // emitted by the Claude Code / GitHub Models backends during long
        // calls) so they never reach the chat display.
        Consumer<String> filtered = token -> {
            if (!isProgressMarker(token)) onToken.accept(token);
        };

        String full = backend.stream(systemPrompt, conversation, budget, filtered);
        conversation.add(new JSONObject().put("role", "assistant").put("content", full));
        return full;
    }

    /** Clear the multi-turn history (Clear button on the view). */
    public void clearConversation() {
        conversation.clear();
    }

    /** Serialise the conversation history to a JSON array string, for
     *  persisting across view close/reopen. */
    public String exportConversation() {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (JSONObject m : conversation) arr.put(m);
        return arr.toString();
    }

    /** Restore conversation history from a previously {@link #exportConversation()}
     *  string. Replaces any current history. Silently ignores malformed input. */
    public void importConversation(String json) {
        if (json == null || json.isBlank()) return;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            conversation.clear();
            for (int i = 0; i < arr.length(); i++) {
                conversation.add(arr.getJSONObject(i));
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not restore conversation history: " + e.getMessage());
        }
    }

    /** Cancel any in-flight backend call (Cancel/close button on the view). */
    public void cancel() {
        try {
            backend.cancel();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Backend cancel failed: " + e.getMessage(), e);
        }
    }

    // ─── Prompt assembly ────────────────────────────────────────────

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an AI assistant for SAP ABAP developers. ");
        sb.append("Answer questions about ABAP code, suggest improvements, ");
        sb.append("explain SAP concepts, and write ABAP code snippets when asked. ");
        sb.append("Prefer clear, concise responses with code examples in ");
        sb.append("``` ```abap fenced blocks. ");

        if (BackendModeService.getInstance().isEhp8Mode()) {
            sb.append("The user is targeting SAP_BASIS 7.50 / 7.52 (EHP8). ");
            sb.append("Do NOT suggest RAP, CDS view entities, utclong, abap.boolean, ");
            sb.append("or other 7.54+ features. Use classic ABAP constructs ");
            sb.append("(reports, classes, function modules, classic SELECT, ");
            sb.append("WRITE statements, ALV via CL_SALV_TABLE, etc.).");
        } else {
            sb.append("The user is targeting SAP S/4HANA. ");
            sb.append("RAP (managed/unmanaged), CDS view entities, behavior definitions, ");
            sb.append("@Semantics annotations, modern constructor expressions, ");
            sb.append("and all 7.54+ features are available. Prefer modern ABAP ");
            sb.append("constructs where they fit the task.");
        }
        return sb.toString();
    }

    private String augmentUserMessage(String userText, FileContext editorContext) {
        StringBuilder out = new StringBuilder();

        if (editorContext != null && editorContext.hasContent()) {
            out.append(editorContext.toPromptPrefix()).append("\n\n");
        }

        out.append(userText);
        return out.toString();
    }

    /**
     * Recognize keepalive progress markers from non-streaming backends
     * (e.g. Claude Code emits "[Claude Code working... 30s]" during long
     * calls) so they don't pollute the chat display.
     */
    private static boolean isProgressMarker(String token) {
        if (token == null) return false;
        String t = token.strip();
        if (t.isEmpty() || !t.startsWith("[") || !t.endsWith("]")) return false;
        String lower = t.toLowerCase();
        return lower.contains("claude code working")
            || lower.contains("github models working")
            || lower.contains("retrying")
            || lower.contains("stream returned");
    }
}
