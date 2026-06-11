// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.orient.axlr8lite.handlers;

import com.orient.axlr8lite.views.UpsellDialog;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Single command handler for every Full-only menu item in Lite.
 *
 * <p>Bound to {@code com.orient.axlr8lite.cmd.locked}. The menu entry passes
 * a {@code feature} parameter (e.g. "Task Planner", "Diagnose &amp; Fix",
 * "Orient ACTV8"). The handler shows {@link UpsellDialog} with a contextual
 * description for that feature.
 *
 * <p>One command + one handler + 20+ menu entries means the plugin.xml stays
 * compact and the upsell logic lives in one place.
 */
public class LockedFeatureHandler extends AbstractHandler {

    public static final String PARAM_FEATURE = "feature";

    /** Map feature key → human-readable description shown in the upsell popup. */
    private static final Map<String, String> DESCRIPTIONS = new HashMap<>();
    static {
        // ─── Orient AXLR8 menu items (Full plugin) ───────────────────
        DESCRIPTIONS.put("Task Planner",
            "Break a complex SAP-development goal into ordered, AI-planned "
            + "steps. The Full plugin's Task Planner mode drafts a multi-step "
            + "implementation plan, then steps through each phase with you.");

        DESCRIPTIONS.put("Inline Assist",
            "Inline ABAP code completion — autocomplete + AI-suggested edits "
            + "directly in the editor as you type. Available in the Full plugin.");

        DESCRIPTIONS.put("Generate Unit Tests",
            "Generates ABAP Unit test class scaffolds (ltc_*) for the selected "
            + "class or method, including test-double setup and assertion patterns.");

        DESCRIPTIONS.put("Generate Documentation",
            "Generates inline ABAP DOC comments and external technical "
            + "documentation for the selected class, function module, or report.");

        DESCRIPTIONS.put("Analyze Program",
            "Deep static analysis of the active ABAP program — finds bugs, "
            + "performance issues, anti-patterns, and offers AI-explained fixes.");

        DESCRIPTIONS.put("Diagnose & Fix",
            "The flagship AXLR8 mode: paste a stack trace, dump, or runtime "
            + "error and the AI diagnoses root cause + proposes a fix the "
            + "Full plugin can apply to your SAP system.");

        DESCRIPTIONS.put("Optimize Performance",
            "Targeted SELECT / loop / internal-table optimization. AI rewrites "
            + "hot ABAP paths for SAP HANA + classic DB without changing behavior.");

        DESCRIPTIONS.put("Run ATC Check",
            "Runs SAP ATC (ABAP Test Cockpit) on the selected object and feeds "
            + "the findings to the AI, which proposes fixes for every finding.");

        DESCRIPTIONS.put("Smart Refactor",
            "AI-driven refactoring — rename safely, extract methods, simplify "
            + "control flow, inline duplication, all with cross-artifact awareness.");

        DESCRIPTIONS.put("Convert to OO ABAP",
            "Converts procedural ABAP reports / function modules into clean "
            + "object-oriented ABAP classes.");

        DESCRIPTIONS.put("Convert to RAP",
            "Converts classic CRUD code or BOPF objects into the SAP RESTful "
            + "Application Programming (RAP) model — managed or unmanaged.");

        DESCRIPTIONS.put("Convert to SQLScript",
            "Converts ABAP SELECT loops + nested logic into HANA SQLScript "
            + "(table functions / procedures) for push-down to the database.");

        DESCRIPTIONS.put("Analyze ST22 Dump",
            "Pulls a live ST22 short-dump from your SAP system and walks "
            + "through the cause + fix with the AI.");

        DESCRIPTIONS.put("SELECT Performance Advisor",
            "Audits SELECT statements in the selected program for non-key "
            + "WHERE clauses, missing indexes, SELECT *, FOR ALL ENTRIES "
            + "anti-patterns, and HANA-friendly rewrites.");

        DESCRIPTIONS.put("Review Transport Request",
            "Pulls a transport's contents from SAP and runs an AI code review "
            + "across every object in the TR — pre-import sanity check.");

        DESCRIPTIONS.put("BAPI/FM Discovery",
            "AI-powered BAPI / function-module discovery: describe what you "
            + "need and the AI finds the right standard SAP function with "
            + "interface details.");

        DESCRIPTIONS.put("Query Table (Natural Language)",
            "Natural-language SAP data queries. Type 'top 10 customers by "
            + "revenue last quarter' — AI generates the SELECT, runs it, "
            + "shows results.");

        // ─── Orient ACTV8 menu (Full plugin) ─────────────────────────
        DESCRIPTIONS.put("Orient ACTV8",
            "The ACTV8 end-to-end code generator. Upload a Functional "
            + "Specification Document → AI plans the artifacts → generates "
            + "ABAP for every class / structure / report / RAP object → "
            + "deploys to SAP → runs AI Fix on any activation errors → "
            + "produces a Technical Spec Document. The whole development "
            + "workflow in one click.");

        // ─── Toolbar / generic catch-all ─────────────────────────────
        DESCRIPTIONS.put("Apply to SAP",
            "Deploy AI-suggested ABAP code directly to your SAP system with "
            + "pre-deploy validation, transport request handling, and post-"
            + "deploy AI Fix loop on any activation errors.");

        DESCRIPTIONS.put("AI Fix",
            "Auto-fix loop: when an artifact fails activation on SAP, the AI "
            + "reads the error, proposes a fix, re-deploys, and iterates "
            + "until the artifact is active or the loop gives up gracefully.");

        DESCRIPTIONS.put("Live SAP Context",
            "Every chat prompt is enriched with LIVE definitions of the SAP "
            + "objects you reference — real class signatures, table fields, "
            + "BAPI interfaces, where-used data. Suggestions match your "
            + "actual SAP system instead of being generic.");

        // ─── Settings-page section descriptions (shared with LitePreferencePage) ─
        DESCRIPTIONS.put("Workspace Context (RAG)",
            "Index your entire workspace, embed every chunk with OpenAI / Gemini / "
            + "Voyage / Ollama embeddings, then prepend the top-K semantic hits to "
            + "every chat prompt. The AI gets context from your whole codebase "
            + "instead of just the active file. Available in the full plugin.");

        DESCRIPTIONS.put("Other LLM Providers",
            "Lite ships 2 LLM providers (Claude Code + GitHub Models). The "
            + "full plugin adds 4 more: OpenAI, Claude API (Anthropic), "
            + "Gemini, and Codex CLI (your ChatGPT subscription). Configure "
            + "API keys, fallback chain order, per-task model preferences, "
            + "and more.");
    }

    @Override
    public Object execute(ExecutionEvent event) {
        String feature = event.getParameter(PARAM_FEATURE);
        if (feature == null || feature.isBlank()) feature = "Full-Version Feature";

        UpsellDialog.showForFeature(feature, descriptionFor(feature));
        return null;
    }

    /** Public lookup so the preferences page (or any other caller) can show the
     *  same description text the menu handler uses. Falls back to a generic
     *  one-liner when the key isn't in the table. */
    public static String descriptionFor(String featureKey) {
        return DESCRIPTIONS.getOrDefault(featureKey,
            "This feature is exclusive to the full AXLR8 + ACTV8 plugin.");
    }
}
