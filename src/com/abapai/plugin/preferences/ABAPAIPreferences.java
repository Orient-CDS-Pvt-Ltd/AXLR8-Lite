// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.abapai.plugin.preferences;

import com.abapai.plugin.activator.Activator;
import com.abapai.plugin.services.ai.AIProvider;

import org.eclipse.jface.preference.IPreferenceStore;

import java.util.Locale;

/**
 * Preference accessor for AXLR8 Lite.
 *
 * <p>Backs the two LLM providers Lite ships — Claude Code and GitHub Models —
 * plus the SAP target toggle and a couple of token-budget knobs. API tokens
 * are kept in Eclipse secure storage; everything else lives in the normal
 * preference store.
 */
public class ABAPAIPreferences {

    private ABAPAIPreferences() {} // static accessor only

    // ─── Provider selection ─────────────────────────────────────────
    public static final String KEY_PROVIDER = "ai.provider";

    public static AIProvider getProvider() {
        if (Activator.getDefault() == null) {
            String v = System.getProperty(KEY_PROVIDER);
            AIProvider p = AIProvider.fromName(v);
            return p == null ? AIProvider.CLAUDE_CODE : p;
        }
        String v = Activator.getDefault().getPreferenceStore().getString(KEY_PROVIDER);
        return AIProvider.fromNameOrDefault(v);
    }

    // ─── Claude Code (local subscription via CLI) ───────────────────
    public static final String KEY_CLAUDE_CODE_PATH          = "claude.code.path";
    public static final String KEY_CLAUDE_CODE_MODEL         = "claude.code.model";        // legacy, kept for migration
    public static final String KEY_CLAUDE_CODE_MODEL_HEAVY    = "claude.code.model.heavy";
    public static final String KEY_CLAUDE_CODE_MODEL_LIGHT    = "claude.code.model.light";
    public static final String KEY_CLAUDE_CODE_ON_RATE_LIMIT  = "claude.code.on.rate.limit";
    public static final String KEY_CLAUDE_CODE_TIMEOUT_SEC    = "claude.code.timeout.seconds";
    public static final int    DEFAULT_CLAUDE_CODE_TIMEOUT_SEC = 900; // 15 min
    public static final String DEFAULT_CLAUDE_CODE_MODEL_HEAVY = "claude-opus-4-8";
    public static final String DEFAULT_CLAUDE_CODE_MODEL_LIGHT = "claude-sonnet-4-6";

    public static String getClaudeCodePath() {
        if (Activator.getDefault() == null) return "claude";
        String v = store().getString(KEY_CLAUDE_CODE_PATH);
        return (v == null || v.isBlank()) ? "claude" : v;
    }

    /** Legacy accessor — returns the heavy model. */
    public static String getClaudeCodeModel() {
        return getClaudeCodeModelHeavy();
    }

    public static String getClaudeCodeModelHeavy() {
        if (Activator.getDefault() == null) return DEFAULT_CLAUDE_CODE_MODEL_HEAVY;
        String v = store().getString(KEY_CLAUDE_CODE_MODEL_HEAVY);
        if (v == null || v.isBlank()) {
            v = store().getString(KEY_CLAUDE_CODE_MODEL); // migrate from legacy single-model key
        }
        return (v == null || v.isBlank()) ? DEFAULT_CLAUDE_CODE_MODEL_HEAVY : v;
    }

    public static String getClaudeCodeModelLight() {
        if (Activator.getDefault() == null) return DEFAULT_CLAUDE_CODE_MODEL_LIGHT;
        String v = store().getString(KEY_CLAUDE_CODE_MODEL_LIGHT);
        return (v == null || v.isBlank()) ? DEFAULT_CLAUDE_CODE_MODEL_LIGHT : v;
    }

    public static String getClaudeCodeOnRateLimit() {
        if (Activator.getDefault() == null) return "retry";
        String v = store().getString(KEY_CLAUDE_CODE_ON_RATE_LIMIT);
        return (v == null || v.isBlank()) ? "retry" : v;
    }

    public static int getClaudeCodeTimeoutSeconds() {
        if (Activator.getDefault() == null) return DEFAULT_CLAUDE_CODE_TIMEOUT_SEC;
        int v = store().getInt(KEY_CLAUDE_CODE_TIMEOUT_SEC);
        if (v <= 0) return DEFAULT_CLAUDE_CODE_TIMEOUT_SEC;
        if (v < 60) return 60;
        if (v > 3600) return 3600;
        return v;
    }

    // ─── GitHub Models (PAT, free for Copilot subscribers) ──────────
    public static final String KEY_GITHUB_MODELS_MODEL       = "github.models.model";
    public static final String KEY_GITHUB_MODELS_MODEL_LIGHT  = "github.models.model.light";
    public static final String DEFAULT_GITHUB_MODELS_MODEL       = "openai/gpt-4.1";
    public static final String DEFAULT_GITHUB_MODELS_MODEL_LIGHT = "openai/gpt-4o-mini";
    private static final String SEC_GITHUB_MODELS_PAT = "github.models.pat";

    public static String getGitHubModelsPat() {
        return getSecretOrLegacy(SEC_GITHUB_MODELS_PAT, "github.models.pat");
    }

    public static void setGitHubModelsPat(String value) {
        setSecret(SEC_GITHUB_MODELS_PAT, value, "github.models.pat");
    }

    public static String getGitHubModelsModel() {
        if (Activator.getDefault() == null) return DEFAULT_GITHUB_MODELS_MODEL;
        String m = store().getString(KEY_GITHUB_MODELS_MODEL);
        return (m == null || m.isBlank()) ? DEFAULT_GITHUB_MODELS_MODEL : m;
    }

    public static String getGitHubModelsModelLight() {
        if (Activator.getDefault() == null) return DEFAULT_GITHUB_MODELS_MODEL_LIGHT;
        String m = store().getString(KEY_GITHUB_MODELS_MODEL_LIGHT);
        return (m == null || m.isBlank()) ? DEFAULT_GITHUB_MODELS_MODEL_LIGHT : m;
    }

    // ─── Planning model mode (Claude Code doesn't stream, so a faster
    //     model can matter more than extra reasoning) ────────────────
    public static final String KEY_PLANNING_MODEL_MODE     = "planning.model.mode";
    public static final String DEFAULT_PLANNING_MODEL_MODE = "auto";

    public static String getPlanningModelMode() {
        if (Activator.getDefault() == null) return DEFAULT_PLANNING_MODEL_MODE;
        String v = store().getString(KEY_PLANNING_MODEL_MODE);
        if (v == null || v.isBlank()) return DEFAULT_PLANNING_MODEL_MODE;
        v = v.toLowerCase(Locale.ROOT).trim();
        return switch (v) {
            case "auto", "light", "heavy" -> v;
            default -> DEFAULT_PLANNING_MODEL_MODE;
        };
    }

    // ─── Output token floor (user safety net; 0 = allocator-driven) ──
    public static final String KEY_OUTPUT_TOKEN_FLOOR     = "ai.output.token.floor";
    public static final int    DEFAULT_OUTPUT_TOKEN_FLOOR = 0;

    public static int getOutputTokenFloor() {
        if (Activator.getDefault() == null) return DEFAULT_OUTPUT_TOKEN_FLOOR;
        int v = store().getInt(KEY_OUTPUT_TOKEN_FLOOR);
        if (v < 0) return 0;
        if (v > 200_000) return 200_000;
        return v;
    }

    // ─── SAP target mode (EHP8 vs S/4HANA) ──────────────────────────
    public static final String  KEY_EHP8_MODE     = "compat.ehp8.mode";
    public static final boolean DEFAULT_EHP8_MODE = false;

    public static boolean isEhp8Mode() {
        if (Activator.getDefault() == null) return DEFAULT_EHP8_MODE;
        return store().getBoolean(KEY_EHP8_MODE);
    }

    public static void setEhp8Mode(boolean enabled) {
        if (Activator.getDefault() == null) return;
        store().setValue(KEY_EHP8_MODE, enabled);
    }

    // ─── Status-bar label ───────────────────────────────────────────
    public static String getActiveModelLabel() {
        return switch (getProvider()) {
            case CLAUDE_CODE   -> getClaudeCodeModelHeavy() + " (Claude Code)";
            case GITHUB_MODELS -> getGitHubModelsModel() + " (GitHub Models)";
        };
    }

    // ─── Internal helpers ───────────────────────────────────────────

    private static IPreferenceStore store() {
        return Activator.getDefault().getPreferenceStore();
    }

    /** Secure-storage node for this bundle. Lite-namespaced so it never
     *  collides with another bundle's secrets. */
    private static final String SEC_NODE = "/com.orient.axlr8lite/secrets";

    private static String getSecretOrLegacy(String secureKey, String legacyKey) {
        String secure = getSecret(secureKey);
        if (!secure.isBlank()) return secure;
        if (Activator.getDefault() == null) {
            String sysProp = System.getProperty(secureKey);
            return (sysProp == null || sysProp.isBlank()) ? "" : sysProp;
        }
        String legacy = store().getString(legacyKey);
        if (legacy != null && !legacy.isBlank()) {
            setSecret(secureKey, legacy, legacyKey);
            return legacy;
        }
        return "";
    }

    private static String getSecret(String key) {
        try {
            Object node = getSecureNode();
            if (node == null) return "";
            String value = (String) node.getClass()
                .getMethod("get", String.class, String.class)
                .invoke(node, key, "");
            return value == null ? "" : value;
        } catch (Throwable e) {
            return "";
        }
    }

    private static void setSecret(String key, String value, String legacyKeyToClear) {
        boolean storedSecurely = false;
        try {
            Object node = getSecureNode();
            if (node != null) {
                if (value == null || value.isBlank()) {
                    node.getClass().getMethod("remove", String.class).invoke(node, key);
                } else {
                    node.getClass()
                        .getMethod("put", String.class, String.class, boolean.class)
                        .invoke(node, key, value, true);
                }
                node.getClass().getMethod("flush").invoke(node);
                storedSecurely = true;
            }
        } catch (Throwable ignored) {
            // keep the plugin resilient even if secure storage is unavailable
        }
        if (Activator.getDefault() != null && legacyKeyToClear != null && !legacyKeyToClear.isBlank()) {
            if (storedSecurely) {
                store().setValue(legacyKeyToClear, ""); // clear plain-text fallback
            } else {
                store().setValue(legacyKeyToClear, value == null ? "" : value); // durable fallback
            }
        }
    }

    private static Object getSecureNode() {
        try {
            Class<?> factory = Class.forName(
                "org.eclipse.equinox.security.storage.SecurePreferencesFactory");
            Object root = factory.getMethod("getDefault").invoke(null);
            return root.getClass().getMethod("node", String.class).invoke(root, SEC_NODE);
        } catch (Throwable t) {
            return null;
        }
    }
}
