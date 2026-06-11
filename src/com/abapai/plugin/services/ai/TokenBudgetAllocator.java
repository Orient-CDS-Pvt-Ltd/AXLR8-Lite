// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.abapai.plugin.services.ai;

import java.util.List;
import java.util.logging.Logger;

import org.json.JSONObject;

/**
 * Centralised proactive allocator for AI request {@code max_tokens} budgets.
 *
 * <p>Replaces hand-picked literals scattered across the codebase with a
 * single policy. Two bugs the old approach caused:
 *
 * <ul>
 *   <li>Chat reply 1,024-token cap → reasoning model burned the budget on
 *       internal thinking, returned empty content with
 *       {@code stop_reason: max_tokens}.</li>
 *   <li>FSD summarizer 6,000-token cap → Gemini 2.5 Pro and Opus 4.7 both
 *       truncated; live error in user log:
 *       <em>"Gemini stream truncated (finishReason=MAX_TOKENS).
 *       Output exceeded maxOutputTokens=6000."</em></li>
 * </ul>
 *
 * <h3>The formula</h3>
 *
 * Three orthogonal factors combine, with no double-counting:
 *
 * <pre>
 *   scaledBase = isCodeOutput(profile)
 *                  ? Math.max(profileBase, promptChars / 3)
 *                  : profileBase;
 *   candidate  = scaledBase + reasoningHeadroom(reasoning);
 *   return     Math.min(candidate, backend.maxOutputTokens());
 * </pre>
 *
 * <h3>Reasoning headroom</h3>
 *
 * Reasoning models consume tokens internally before producing visible
 * output, so the allocator reserves headroom. Even "low" effort needs
 * &gt; 0 headroom because some reasoning models cannot fully disable
 * thinking and may use a few hundred tokens even on minimal effort.
 *
 * <ul>
 *   <li>low    → 1,024</li>
 *   <li>medium → 8,192</li>
 *   <li>high   → 16,384</li>
 *   <li>xhigh  → 32,768</li>
 *   <li>max    → 65,536</li>
 * </ul>
 *
 * <h3>Size input</h3>
 *
 * {@code prompt.length()} alone is too small for multi-message calls, so the
 * size estimator includes the system prompt plus all messages.
 * {@link #estimateChars} is the canonical size function.
 *
 * <h3>Telemetry</h3>
 *
 * Every allocator decision logs profile, reasoning, prompt chars, scaledBase,
 * headroom, backend max, and final allocated tokens, so the constants can be
 * tuned from real provider usage data.
 *
 * <h3>Override</h3>
 *
 * Callers may still pass a hand-picked {@code maxTokens} via the legacy
 * {@code singleShot(prompt, reasoning, maxTokens, profile)} overload — that
 * path stays for inline-completion strict caps, cost-sensitive calls, and
 * tests. New code paths should use the allocator-aware overload by default.
 */
public final class TokenBudgetAllocator {

    private static final Logger LOG = Logger.getLogger(TokenBudgetAllocator.class.getName());

    /** Profile-base "visible output" budgets (excluding reasoning headroom).
     *  PLANNING starts at 65K and scales/caps rather than always defaulting
     *  to the model maximum. */
    private static int profileBase(AITaskProfile profile) {
        if (profile == null) return 8_192;
        return switch (profile) {
            case INLINE_COMPLETION -> 1_024;
            case CHAT_LITE         -> 8_192;
            case SEARCH            -> 4_096;
            case DOCUMENTATION     -> 8_192;
            case ANALYSIS          -> 16_384;
            case BUG_SCAN          -> 32_768;
            case HEAVY_CODE        -> 16_384;   // base; scales with input
            case AGENT             -> 16_384;
            case PLANNING          -> 65_536;   // start, scale, cap
            default                -> 8_192;
        };
    }

    /** Reasoning headroom. Even "low" reserves 1K because modern reasoning
     *  models don't let you fully disable internal thinking. */
    private static int reasoningHeadroom(String reasoning) {
        if (reasoning == null || reasoning.isBlank()) return 1_024;
        return switch (reasoning.toLowerCase()) {
            case "low"    -> 1_024;
            case "medium" -> 8_192;
            case "high"   -> 16_384;
            case "xhigh"  -> 32_768;
            case "max"    -> 65_536;
            default       -> 1_024;
        };
    }

    /** True for profiles whose output size grows with input size (refactor,
     *  full-class regeneration, bug-fix, plan-from-FSD). False for chat
     *  replies, search, documentation snippets — those are roughly fixed
     *  output regardless of how big the input is. */
    private static boolean isCodeOutput(AITaskProfile profile) {
        if (profile == null) return false;
        return switch (profile) {
            case HEAVY_CODE, BUG_SCAN, PLANNING -> true;
            default                              -> false;
        };
    }

    /**
     * Allocate a {@code max_tokens} budget for an AI call.
     *
     * @param profile     Task profile — drives the visible-output base.
     * @param promptChars Total character count of EVERYTHING the model will
     *                    see (system prompt + all messages + tool/JSON schemas).
     *                    Use {@link #estimateChars} to compute correctly.
     * @param reasoning   Reasoning effort string ({@code "low"}/{@code "medium"}/
     *                    {@code "high"}/{@code "xhigh"}/{@code "max"}). Drives
     *                    the headroom term. Null/blank = "low".
     * @param backend     Active AI backend — provides {@code maxOutputTokens()}
     *                    as the hard ceiling.
     * @return Allocated budget. Always &gt;= 1024. Never exceeds
     *         {@code backend.maxOutputTokens()}.
     */
    public static int allocate(AITaskProfile profile, int promptChars,
                                String reasoning, AIBackend backend) {
        int base = profileBase(profile);
        int scaledBase = isCodeOutput(profile)
                ? Math.max(base, promptChars / 3)
                : base;
        int headroom = reasoningHeadroom(reasoning);
        int candidate = scaledBase + headroom;

        int backendMax = backend != null ? backend.maxOutputTokens() : 32_768;
        int allocated = Math.min(candidate, backendMax);
        if (allocated < 1_024) allocated = 1_024;

        // User safety-net floor (Window > Preferences > Orient AXLR8 > Min output
        // tokens). If the user has set a floor above what the allocator
        // computed, raise the allocation — but still cap at backendMax so we
        // never send an invalid request to the provider.
        int userFloor = readUserFloorSafe();
        if (userFloor > 0 && userFloor > allocated) {
            allocated = Math.min(userFloor, backendMax);
        }

        // Single INFO line per allocation; tune the constants from real
        // provider usage data.
        LOG.info(String.format(
                "TokenBudgetAllocator: profile=%s reasoning=%s promptChars=%d "
                        + "base=%d scaledBase=%d headroom=%d backendMax=%d "
                        + "userFloor=%d allocated=%d",
                profile, reasoning, promptChars,
                base, scaledBase, headroom, backendMax, userFloor, allocated));
        return allocated;
    }

    /** Read user-configured token floor from preferences. Returns 0 if the
     *  preferences subsystem isn't initialised (e.g. standalone tests). */
    private static int readUserFloorSafe() {
        try {
            return com.abapai.plugin.preferences.ABAPAIPreferences.getOutputTokenFloor();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Convenience: estimate chars for a single-string prompt. Use the
     *  list-aware overload for chat / agent / tool-use calls. */
    public static int estimateChars(String prompt) {
        return prompt == null ? 0 : prompt.length();
    }

    /** Estimate total chars including system prompt + all messages + any
     *  serialized tool/JSON schema. {@code prompt.length()} alone is too
     *  small for tool/agent calls — schemas can run thousands of chars. */
    public static int estimateChars(String systemPrompt,
                                     List<JSONObject> messages,
                                     List<JSONObject> toolSchemas) {
        int total = systemPrompt != null ? systemPrompt.length() : 0;
        if (messages != null) {
            for (JSONObject m : messages) {
                if (m == null) continue;
                Object content = m.opt("content");
                if (content instanceof String s) {
                    total += s.length();
                } else if (content != null) {
                    total += content.toString().length();
                }
            }
        }
        if (toolSchemas != null) {
            for (JSONObject schema : toolSchemas) {
                if (schema == null) continue;
                total += schema.toString().length();
            }
        }
        return total;
    }

    private TokenBudgetAllocator() {
        // Utility class — no instances.
    }
}
