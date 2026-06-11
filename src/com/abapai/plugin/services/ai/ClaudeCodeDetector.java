// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.abapai.plugin.services.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Detects whether the Claude Code CLI is installed and authenticated.
 * Tries a prioritised list of candidate paths; returns the first that responds.
 */
public class ClaudeCodeDetector {

    private static final Logger LOG = Logger.getLogger(ClaudeCodeDetector.class.getName());

    public enum AuthStatus { AUTHENTICATED, NOT_AUTHENTICATED, UNKNOWN }
    public enum Tier        { PRO, MAX, UNKNOWN }

    public record DetectionResult(
        boolean    found,
        String     path,
        String     version,
        AuthStatus authStatus,
        String     accountEmail,
        Tier       tier,
        String     failReason
    ) {
        /** One-line summary shown in the preferences status label. */
        public String statusLine() {
            if (!found) {
                String reason = (failReason != null && !failReason.isBlank())
                    ? failReason : "claude CLI not found on PATH";
                return "\u2717 Not found \u2014 " + reason;
            }
            StringBuilder sb = new StringBuilder("\u2713 Detected: ").append(version);
            switch (authStatus) {
                case AUTHENTICATED -> {
                    sb.append(" | Logged in");
                    if (accountEmail != null && !accountEmail.isBlank())
                        sb.append(": ").append(accountEmail);
                    if (tier == Tier.MAX)      sb.append(" (Max plan)");
                    else if (tier == Tier.PRO) sb.append(" (Pro plan)");
                }
                case NOT_AUTHENTICATED ->
                    sb.append(" | \u26a0 Not authenticated \u2014 click Re-authenticate");
                default ->
                    sb.append(" | Auth status unknown");
            }
            return sb.toString();
        }
    }

    // ── Candidate paths tried in order ────────────────────────────────────────
    private static final List<String> CANDIDATES;
    static {
        String home = System.getProperty("user.home", "");
        String user = System.getProperty("user.name", "");
        CANDIDATES = new ArrayList<>(List.of(
            // Plain command (works if on PATH)
            "claude",
            // Windows: npm global installs
            home + "\\AppData\\Roaming\\npm\\claude.cmd",
            home + "\\AppData\\Roaming\\npm\\claude",
            "C:\\Program Files\\nodejs\\claude.cmd",
            "C:\\Users\\" + user + "\\AppData\\Roaming\\npm\\claude.cmd",
            // Mac / Linux
            home + "/.nvm/versions/node/current/bin/claude",
            "/usr/local/bin/claude",
            "/usr/bin/claude",
            home + "/bin/claude"
        ));
    }

    /**
     * Auto-detect. If {@code overridePath} is non-blank, only that path is tried.
     * Otherwise the full candidate list is searched.
     */
    public static DetectionResult detect(String overridePath) {
        List<String> toTry = (overridePath != null && !overridePath.isBlank())
            ? List.of(overridePath)
            : CANDIDATES;

        for (String candidate : toTry) {
            try {
                DetectionResult r = tryCandidate(candidate);
                if (r.found()) {
                    LOG.info("Claude Code detected at: " + candidate + " — " + r.version());
                    return r;
                }
            } catch (Exception ignored) {}
        }
        return new DetectionResult(false, null, null, AuthStatus.UNKNOWN, null, Tier.UNKNOWN,
            "claude CLI not found. Install from claude.ai/code, then restart Eclipse.");
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private static DetectionResult tryCandidate(String path) throws Exception {
        // 1. Version check
        Process vp = new ProcessBuilder(path, "--version")
            .redirectErrorStream(true)
            .start();
        if (!vp.waitFor(5, TimeUnit.SECONDS)) {
            vp.destroyForcibly();
            return notFound(path, "version check timed out");
        }
        String vout = new String(vp.getInputStream().readAllBytes()).trim();
        if (vp.exitValue() != 0 || vout.isBlank())
            return notFound(path, "non-zero exit or blank output");

        // 2. Auth check
        AuthStatus auth  = AuthStatus.UNKNOWN;
        String     email = null;
        Tier       tier  = Tier.UNKNOWN;
        try {
            Process ap = new ProcessBuilder(path, "auth", "status")
                .redirectErrorStream(true)
                .start();
            if (ap.waitFor(6, TimeUnit.SECONDS)) {
                String aout = new String(ap.getInputStream().readAllBytes());
                String lower = aout.toLowerCase();
                if (lower.contains("not logged") || lower.contains("not authenticated") ||
                    (lower.contains("login") && lower.contains("required"))) {
                    auth = AuthStatus.NOT_AUTHENTICATED;
                } else if (lower.contains("logged in") || lower.contains("authenticated") ||
                           lower.contains("claude.ai") || lower.contains("@")) {
                    auth = AuthStatus.AUTHENTICATED;
                    // Best-effort email extraction
                    for (String word : aout.split("[\\s,]+")) {
                        if (word.contains("@") && word.contains(".") && word.length() < 80) {
                            email = word.replaceAll("[^a-zA-Z0-9@._\\-]", "");
                            break;
                        }
                    }
                    if (lower.contains("max")) tier = Tier.MAX;
                    else if (lower.contains("pro")) tier = Tier.PRO;
                }
            }
        } catch (Exception ignored) {}

        return new DetectionResult(true, path, vout, auth, email, tier, null);
    }

    private static DetectionResult notFound(String path, String reason) {
        return new DetectionResult(false, path, null, AuthStatus.UNKNOWN, null, Tier.UNKNOWN,
            "Not available at '" + path + "': " + reason);
    }

    private ClaudeCodeDetector() {}
}
