// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.orient.axlr8lite.preferences;

import com.abapai.plugin.activator.Activator;
import com.abapai.plugin.preferences.ABAPAIPreferences;
import com.abapai.plugin.preferences.ABAPAIPreferencePage;
import com.abapai.plugin.services.ai.AIProvider;
import com.abapai.plugin.services.backend.BackendMode;
import com.abapai.plugin.services.backend.BackendModeService;

import org.eclipse.jface.preference.IPreferenceStore;

/**
 * Lite-specific preference accessor.
 *
 * <p>Thin facade over {@link ABAPAIPreferences} (the verbatim main-plugin
 * preference class). Lite has fewer settings than Full, so this class
 * exposes only the keys/getters Lite actually uses, hiding the wider Full
 * surface from the rest of the Lite codebase.
 *
 * <p>Preference store: shared with the Activator bundle. All keys are
 * persisted via Eclipse's standard preference store under the
 * {@code com.orient.axlr8lite} bundle ID (see {@link Activator#PLUGIN_ID}).
 *
 * <p>Backed by {@link ABAPAIPreferences} so that key names match — important
 * if we ever want users to migrate settings between Lite and Full without
 * re-entering everything.
 */
public final class LitePreferences {

    private LitePreferences() {} // utility class, no instances

    /** Underlying preference store. */
    public static IPreferenceStore store() {
        return Activator.getDefault().getPreferenceStore();
    }

    // ─── AI provider selection ──────────────────────────────────────

    public static AIProvider getProvider() {
        return ABAPAIPreferences.getProvider();
    }

    public static void setProvider(AIProvider p) {
        store().setValue(ABAPAIPreferencePage.KEY_AI_PROVIDER, p.name());
    }

    // ─── Claude Code (subscription) ─────────────────────────────────

    public static String getClaudeCodePath() {
        return ABAPAIPreferences.getClaudeCodePath();
    }

    public static String getClaudeCodeModel() {
        return ABAPAIPreferences.getClaudeCodeModelHeavy();
    }

    // ─── GitHub Models (PAT) ────────────────────────────────────────

    public static String getGitHubPat() {
        return ABAPAIPreferences.getGitHubModelsPat();
    }

    public static String getGitHubModel() {
        return ABAPAIPreferences.getGitHubModelsModel();
    }

    // ─── EHP8 / S/4 mode ────────────────────────────────────────────

    public static BackendMode getMode() {
        return BackendModeService.getInstance().getMode();
    }

    public static boolean isEhp8Mode() {
        return BackendModeService.getInstance().isEhp8Mode();
    }

    public static void setMode(BackendMode mode) {
        BackendModeService.getInstance().setMode(mode);
    }

    // ─── Editor-context toggle (per-session default for the chat toolbar) ─

    private static final String KEY_INCLUDE_EDITOR = "lite.include.editor";

    public static boolean isIncludeEditorByDefault() {
        if (Activator.getDefault() == null) return true; // default ON
        return store().getBoolean(KEY_INCLUDE_EDITOR);
    }

    public static void setIncludeEditorByDefault(boolean enabled) {
        store().setValue(KEY_INCLUDE_EDITOR, enabled);
    }

    /** One-time default seeding — called by LitePreferencePage's initializer. */
    public static void seedDefaults() {
        IPreferenceStore s = store();
        s.setDefault(KEY_INCLUDE_EDITOR, true);
        s.setDefault(ABAPAIPreferencePage.KEY_AI_PROVIDER, AIProvider.CLAUDE_CODE.name());
    }
}
