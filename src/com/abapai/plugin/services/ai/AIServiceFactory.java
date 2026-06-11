// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.abapai.plugin.services.ai;

import com.abapai.plugin.preferences.ABAPAIPreferences;

import java.util.logging.Logger;

/**
 * Returns the active AIBackend based on the preference-page provider selection.
 * Call reset() after a preference change to force re-creation.
 *
 * <p>Fallback chain is user-configured via the "Fallback Order" section in
 * preferences. Empty/disabled = no fallback (primary only). Auth errors do
 * NOT fall back — only network/429/5xx do.
 */
public class AIServiceFactory {

    private static final Logger LOG = Logger.getLogger(AIServiceFactory.class.getName());

    private static volatile AIBackend instance;

    public static AIBackend getBackend() {
        if (instance == null) {
            synchronized (AIServiceFactory.class) {
                if (instance == null) instance = createBackend();
            }
        }
        return instance;
    }

    /** Force re-creation (called from ABAPAIPreferencePage.performOk). */
    public static synchronized void reset() { instance = null; }

    /**
     * Create a fresh (non-cached) backend instance.
     * Use this when you need a clean-slate backend — e.g. the self-healing
     * agent needs a fresh FallbackBackend so its activeToolBackend is null
     * at the start of every session, regardless of prior runs.
     */
    public static AIBackend createFreshBackend() { return createBackend(); }

    private static AIBackend createBackend() {
        AIProvider provider = ABAPAIPreferences.getProvider();

        AIBackend primary = forProvider(provider);

        // Read user-configured fallback chain from preferences
        boolean fallbackDisabled = false;
        String chainCsv = "";
        if (com.abapai.plugin.activator.Activator.getDefault() != null) {
            var store = com.abapai.plugin.activator.Activator.getDefault().getPreferenceStore();
            fallbackDisabled = store.getBoolean("ai.fallback.disabled");
            chainCsv = store.getString("ai.fallback.chain");
        }

        if (fallbackDisabled || chainCsv == null || chainCsv.isBlank()) {
            LOG.info("AI backend: " + provider + " (no fallback)");
            return primary;
        }

        // Build chain from user's ordered list, skipping unconfigured providers
        java.util.List<AIBackend> chain = new java.util.ArrayList<>();
        java.util.List<String>    names = new java.util.ArrayList<>();
        chain.add(primary);
        names.add(provider.getLabel());

        for (String s : chainCsv.split(",")) {
            s = s.trim();
            if (s.isEmpty()) continue;
            AIProvider fp = AIProvider.fromName(s);
            if (fp == provider) continue; // skip primary (already first)
            if (!isConfigured(fp)) continue;
            chain.add(forProvider(fp));
            names.add(fp.getLabel());
        }

        if (chain.size() > 1) {
            LOG.info("AI backend chain: " + String.join(" \u2192 ", names));
            return new FallbackBackend(chain, names);
        }
        LOG.info("AI backend: " + provider + " (no configured fallbacks in chain)");
        return primary;
    }

    private static AIBackend forProvider(AIProvider p) {
        // AXLR8 Lite ships only 2 backends: Claude Code (subscription) and
        // GitHub Models (PAT). Other providers are Full-only and the enum
        // values have been removed from AIProvider in this build.
        return switch (p) {
            case GITHUB_MODELS -> new GitHubModelsBackend();
            case CLAUDE_CODE   -> new ClaudeCodeBackend();
        };
    }

    private static boolean isConfigured(AIProvider p) {
        return switch (p) {
            case GITHUB_MODELS -> hasKey(ABAPAIPreferences.getGitHubModelsPat());
            case CLAUDE_CODE   -> true; // local subscription, always "configured"
        };
    }

    private static boolean hasKey(String key) {
        return key != null && !key.isBlank();
    }

    private AIServiceFactory() {}
}
