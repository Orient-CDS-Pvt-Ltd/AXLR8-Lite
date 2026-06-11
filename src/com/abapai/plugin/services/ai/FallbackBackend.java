// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.abapai.plugin.services.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ══════════════════════════════════════════════════════
 * FallbackBackend — tries backends in priority order
 * ══════════════════════════════════════════════════════
 * Walks a list of backends in priority order. If one throws, the next
 * is tried automatically. Provides resilience when a primary API is
 * down, rate-limited, or misconfigured.
 *
 * Two constructors are provided:
 *   - {@link #FallbackBackend(AIBackend, String, AIBackend, String)} for the
 *     classic primary+secondary pair (source-compatible with the original
 *     two-backend API).
 *   - {@link #FallbackBackend(List, List)} for an arbitrary N-length chain.
 *
 * Tool-use sessions lock to the first backend that successfully answers a
 * completeWithTools() call, because conversation message formats (Claude
 * vs OpenAI) differ and cannot switch mid-session without corrupting
 * history. Resets at {@link #resetToolSession()}.
 */
public class FallbackBackend implements AIBackend {

    private static final Logger LOG = Logger.getLogger(FallbackBackend.class.getName());

    /** Ordered list of backends to try in sequence. Always at least 1 element. */
    private final List<AIBackend> backends;
    /** Parallel list of display names, same length as {@link #backends}. */
    private final List<String>    names;

    /**
     * Tracks which backend "owns" the current tool-use conversation.
     * Once a backend answers the first completeWithTools() call, ALL
     * subsequent tool calls in that session use the same backend.
     * Reset to null at the start of each new agent session.
     */
    private volatile AIBackend activeToolBackend = null;

    /** Classic two-backend fallback constructor (kept for source compatibility). */
    public FallbackBackend(AIBackend primary, String primaryName,
                           AIBackend secondary, String secondaryName) {
        this(Arrays.asList(primary, secondary),
             Arrays.asList(primaryName, secondaryName));
    }

    /**
     * N-backend chain constructor. The first backend is primary; subsequent
     * entries are tried in order when the preceding one fails.
     */
    public FallbackBackend(List<AIBackend> backends, List<String> names) {
        if (backends == null || backends.isEmpty()) {
            throw new IllegalArgumentException("FallbackBackend requires at least one backend");
        }
        if (names == null || names.size() != backends.size()) {
            throw new IllegalArgumentException("names must be non-null and same length as backends");
        }
        this.backends = Collections.unmodifiableList(new ArrayList<>(backends));
        this.names    = Collections.unmodifiableList(new ArrayList<>(names));
    }

    @Override
    public Result complete(String systemPrompt,
                           List<JSONObject> messages,
                           int maxTokens,
                           String reasoning,
                           AITaskProfile profile,
                           boolean fallback) throws Exception {
        Exception lastErr = null;
        for (int i = 0; i < backends.size(); i++) {
            AIBackend b = backends.get(i);
            String    n = names.get(i);
            try {
                // Passing fallback=true for all non-primary attempts so the backend
                // can choose a lighter model variant if it distinguishes.
                return b.complete(systemPrompt, messages, maxTokens, reasoning, profile,
                    fallback || i > 0);
            } catch (InterruptedIOException e) {
                throw e; // user cancelled — don't try further backends
            } catch (Exception e) {
                lastErr = e;
                if (i + 1 < backends.size()) {
                    LOG.log(Level.WARNING, n + " failed, falling back to "
                        + names.get(i + 1) + ": " + e.getMessage(), e);
                } else {
                    LOG.log(Level.WARNING, n + " failed and no more fallbacks remain: "
                        + e.getMessage(), e);
                }
            }
        }
        throw lastErr != null ? lastErr
            : new Exception("All configured backends failed with no recorded error");
    }

    @Override
    public String stream(String systemPrompt,
                         List<JSONObject> messages,
                         int maxTokens,
                         Consumer<String> onToken) throws Exception {
        Exception lastErr = null;
        for (int i = 0; i < backends.size(); i++) {
            AIBackend b = backends.get(i);
            String    n = names.get(i);
            try {
                return b.stream(systemPrompt, messages, maxTokens, onToken);
            } catch (InterruptedIOException e) {
                throw e;
            } catch (Exception e) {
                lastErr = e;
                if (i + 1 < backends.size()) {
                    LOG.log(Level.WARNING, n + " stream failed, falling back to "
                        + names.get(i + 1) + ": " + e.getMessage(), e);
                } else {
                    LOG.log(Level.WARNING, n + " stream failed and chain exhausted: "
                        + e.getMessage(), e);
                }
            }
        }
        throw lastErr != null ? lastErr
            : new Exception("All configured backends failed during stream with no recorded error");
    }

    // ─── Tool use ─────────────────────────────────────────────────

    @Override
    public ToolUseResponse completeWithTools(String systemPrompt,
                                              List<JSONObject> messages,
                                              JSONArray toolDefs,
                                              int maxTokens) throws Exception {
        // If a backend already owns this conversation's format, stay with it.
        // Conversation history cannot switch mid-session without corruption.
        if (activeToolBackend != null) {
            return activeToolBackend.completeWithTools(systemPrompt, messages, toolDefs, maxTokens);
        }
        Exception lastErr = null;
        for (int i = 0; i < backends.size(); i++) {
            AIBackend b = backends.get(i);
            String    n = names.get(i);
            try {
                ToolUseResponse result = b.completeWithTools(
                    systemPrompt, messages, toolDefs, maxTokens);
                activeToolBackend = b;
                LOG.info("Tool-use session started on " + n
                    + (i > 0 ? " (primary failed, using fallback chain)" : ""));
                return result;
            } catch (InterruptedIOException e) {
                throw e;
            } catch (Exception e) {
                lastErr = e;
                if (i + 1 < backends.size()) {
                    LOG.log(Level.WARNING, n + " tool-use failed, trying next backend: "
                        + e.getMessage(), e);
                } else {
                    LOG.log(Level.WARNING, n + " tool-use failed and chain exhausted: "
                        + e.getMessage(), e);
                }
            }
        }
        throw lastErr != null ? lastErr
            : new Exception("All configured backends failed during tool-use with no recorded error");
    }

    @Override
    public void addToolResults(List<JSONObject> messages,
                                ToolUseResponse response,
                                Map<String, String> resultsByCallId) {
        // Must use the same backend that built the conversation so far.
        AIBackend b = activeToolBackend != null ? activeToolBackend : backends.get(0);
        b.addToolResults(messages, response, resultsByCallId);
    }

    /** Reset the active tool backend — call before starting a new agent session. */
    public void resetToolSession() {
        activeToolBackend = null;
    }

    @Override
    public void cancel() {
        for (AIBackend b : backends) {
            try { b.cancel(); } catch (Exception ignored) {}
        }
    }

    @Override
    public int maxOutputTokens() {
        return backends.get(0).maxOutputTokens();
    }
}
