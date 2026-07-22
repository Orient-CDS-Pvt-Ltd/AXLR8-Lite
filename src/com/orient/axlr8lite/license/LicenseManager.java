// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.orient.axlr8lite.license;

import com.abapai.plugin.activator.Activator;

import org.eclipse.jface.preference.IPreferenceStore;

import java.time.LocalDate;

/**
 * Activation state for AXLR8 Lite.
 *
 * <p>Stores one thing — the signed license key — and answers the single
 * question the rest of the plugin asks: {@link #isActivated()}. The licensee's
 * email address is carried inside the key, so it is read back out rather than
 * stored separately or typed by the user.
 *
 * <p><b>There is deliberately no "activated" boolean on disk.</b> The stored
 * key's RSA signature is re-verified, so editing the preference file by hand
 * cannot unlock the plugin — you would need a key signed by the private half,
 * which never ships. The verified key is cached in memory for the session so
 * the check costs nothing after the first call.
 *
 * <p>Licenses are lifetime: once activated, an install stays activated with no
 * expiry, no re-check, and no network access.
 */
public final class LicenseManager {

    private LicenseManager() {} // utility class, no instances

    /** Shown to the user whenever they need a human. Defined in {@link LicenseKey}. */
    public static final String SUPPORT_EMAIL = LicenseKey.SUPPORT_EMAIL;

    private static final String KEY_KEY       = "lite.license.key";
    private static final String KEY_REG_EMAIL = "lite.registration.email";

    /** The key that most recently verified OK, for this session. */
    private static volatile String verifiedKey;

    // ─── Query ──────────────────────────────────────────────────────

    /** True when a valid, signature-checked license key is stored. */
    public static boolean isActivated() {
        String key = pref(KEY_KEY);
        if (key.isBlank()) return false;
        if (key.equals(verifiedKey)) return true;   // already verified this session

        if (LicenseKey.verify(key).isValid()) {
            verifiedKey = key;
            return true;
        }
        return false;
    }

    /** Address this install is licensed to, read out of the key. "" if not activated. */
    public static String getEmail() {
        LicenseKey.Result r = LicenseKey.verify(pref(KEY_KEY));
        return r.isValid() ? r.getEmail() : "";
    }

    /** Issue date of the stored key, or null when not activated. */
    public static LocalDate getIssueDate() {
        LicenseKey.Result r = LicenseKey.verify(pref(KEY_KEY));
        return r.isValid() ? r.getIssued() : null;
    }

    // ─── Mutate ─────────────────────────────────────────────────────

    /**
     * Verify then persist. On success the plugin is activated for good; on
     * failure nothing is written and the reason is returned for display.
     */
    public static LicenseKey.Result activate(String key) {
        LicenseKey.Result result = LicenseKey.verify(key);
        if (result.isValid()) {
            String normalized = LicenseKey.normalizeKey(key);
            IPreferenceStore s = store();
            if (s != null) s.setValue(KEY_KEY, normalized);
            verifiedKey = normalized;
        }
        return result;
    }

    /** Clear the stored license — used by the "Deactivate" action in settings. */
    public static void deactivate() {
        IPreferenceStore s = store();
        if (s != null) s.setToDefault(KEY_KEY);
        verifiedKey = null;
    }

    /**
     * Record the address typed on the registration form. Not used for
     * activation — the key carries its own address — but useful when
     * diagnosing "my key never arrived" support mails.
     */
    public static void rememberRegisteredEmail(String email) {
        IPreferenceStore s = store();
        if (s != null && email != null && !email.isBlank()) {
            s.setValue(KEY_REG_EMAIL, LicenseKey.normalizeEmail(email));
        }
    }

    /** The address typed on the registration form, if any. */
    public static String getRegisteredEmail() {
        return pref(KEY_REG_EMAIL);
    }

    // ─── Internals ──────────────────────────────────────────────────

    private static IPreferenceStore store() {
        Activator a = Activator.getDefault();
        return a == null ? null : a.getPreferenceStore();
    }

    private static String pref(String key) {
        IPreferenceStore s = store();
        if (s == null) return "";
        String v = s.getString(key);
        return v == null ? "" : v;
    }
}
