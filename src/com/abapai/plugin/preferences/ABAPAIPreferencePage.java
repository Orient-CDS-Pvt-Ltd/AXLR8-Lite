// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.abapai.plugin.preferences;

/**
 * Constants holder for the preference-store keys the reused infrastructure
 * references by name.
 *
 * <p>AXLR8 Lite's actual preference UI is
 * {@code com.orient.axlr8lite.preferences.LitePreferencePage}. This class
 * only carries the key strings so the verbatim-reused accessor
 * ({@link ABAPAIPreferences}) and the Lite UI resolve them at compile time.
 *
 * <p>Key names are stable strings; they must not change between releases or
 * users would lose their stored settings.
 */
public final class ABAPAIPreferencePage {

    private ABAPAIPreferencePage() {} // no instances

    public static final String KEY_AI_PROVIDER         = "ai.provider";
    public static final String KEY_CLAUDE_CODE_PATH    = "claude.code.path";
    public static final String KEY_CLAUDE_CODE_MODEL   = "claude.code.model";
    public static final String KEY_GITHUB_MODELS_PAT   = "github.models.pat";
    public static final String KEY_GITHUB_MODELS_MODEL = "github.models.model";

    /**
     * No-op stand-in for the upstream preference page's {@code performOk()}
     * hook. The reused {@code AIServiceFactory} calls this during a reset;
     * Lite drives backend re-creation from its own preference-page lifecycle,
     * so this method is unreferenced at runtime — its presence keeps the
     * reused factory compiling.
     */
    public static void performOk() {
        // no-op
    }
}
