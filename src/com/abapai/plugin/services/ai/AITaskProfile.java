// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.abapai.plugin.services.ai;

/**
 * Hints to the backend which tier of model to use and how much
 * token budget to allocate.  Heavy tasks get more capable models;
 * light tasks (inline, bug-scan) use faster/cheaper ones.
 */
public enum AITaskProfile {
    CHAT_LITE,
    INLINE_COMPLETION,
    BUG_SCAN,
    SEARCH,
    DOCUMENTATION,
    ANALYSIS,
    HEAVY_CODE,
    AGENT,
    /** FSD \u2192 artifact plan (structured JSON). Allowed to use a lighter/faster model
     *  for backends that don't stream (e.g. Claude Code subprocess), since planning
     *  output is well-structured JSON rather than creative code. */
    PLANNING
}
