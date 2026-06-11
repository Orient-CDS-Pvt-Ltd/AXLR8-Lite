// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.abapai.plugin.services.ai;

/**
 * AI provider options for AXLR8 Lite.
 *
 * <p>Lite ships 2 providers only — both are subscription / free-tier
 * friendly:
 *   <ul>
 *     <li>{@link #CLAUDE_CODE} — uses the user's Claude Pro/Max subscription
 *         via the local Claude CLI. No API key field.</li>
 *     <li>{@link #GITHUB_MODELS} — uses the user's GitHub PAT (free for
 *         Copilot subscribers).</li>
 *   </ul>
 *
 * <p>The 4 cloud-API providers (OpenAI, Claude API, Gemini, Codex CLI) are
 * Full-only; their enum values have been dropped here.
 */
public enum AIProvider {

    CLAUDE_CODE("Claude Code (no API key needed)"),
    GITHUB_MODELS("GitHub Models (Copilot/GitHub)");

    private final String label;

    AIProvider(String label) { this.label = label; }

    public String getLabel() { return label; }

    /**
     * True if this provider's backend implements {@code completeWithTools}.
     * Both Lite providers support tool use, so this returns true unconditionally
     * — kept for API parity with the Full plugin's AIProvider.
     */
    public boolean supportsToolUse() {
        return true;
    }

    /** Strict lookup: returns null when the name doesn't match any provider. */
    public static AIProvider fromName(String name) {
        if (name == null || name.isBlank()) return null;
        for (AIProvider p : values()) {
            if (p.name().equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    /**
     * Lookup with a default. Lite defaults to CLAUDE_CODE (lowest friction —
     * subscription + CLI, no API key entry needed).
     */
    public static AIProvider fromNameOrDefault(String name) {
        AIProvider p = fromName(name);
        return p != null ? p : CLAUDE_CODE;
    }
}
