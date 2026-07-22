// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.orient.axlr8lite.license;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Offline license-key codec and verifier.
 *
 * <p>A key is an RSA-2048 / SHA-256 signature over the registrant's email
 * address plus the issue date, with the email address <em>carried inside the
 * key</em>. The user pastes one thing and nothing else — the plugin reads the
 * address out of the key, verifies the signature over it, and activates.
 *
 * <p>Verification is fully offline: the plugin never calls home, so an
 * activated install keeps working forever with no network and no server to
 * keep alive. Keys never expire (lifetime validity).
 *
 * <h2>Why the email is embedded rather than typed</h2>
 * Binding each key to one address is what stops a single key being posted
 * publicly and reused by everyone — the registration wall depends on it. But
 * making the user retype the address adds friction and a whole class of
 * support tickets ("it says my key doesn't match"). Carrying the address in
 * the key keeps the binding and removes the typing: activation is paste-only,
 * and the plugin can show whose licence it is.
 *
 * <h2>Why RSA rather than Ed25519</h2>
 * Keys are issued by a Google Apps Script bound to the registration form, and
 * Apps Script signs RSA-SHA256 natively ({@code Utilities.computeRsaSha256Signature}).
 * Ed25519 would give a shorter key but would mean pasting a third-party crypto
 * library into the issuing script. RSA keeps both ends on stock, audited
 * primitives; the cost is a longer key, which users copy and paste anyway.
 *
 * <h2>Key layout</h2>
 * <pre>
 *   AXL8-&lt;base64url&gt;
 *
 *   byte  0            format version (currently 2)
 *   bytes 1..3         issue date, days since 1970-01-01, big-endian
 *   byte  4            email length in bytes (1..255)
 *   bytes 5..5+n-1     email address, UTF-8, lower-cased
 *   final 256 bytes    RSA-2048 signature
 * </pre>
 *
 * <p>The signed message is canonicalised as:
 * <pre>AXLR8LITE|2|&lt;lower-cased email&gt;|&lt;issue days&gt;</pre>
 * The signature covers the embedded address, so editing the address inside a
 * key invalidates it — you cannot retarget someone else's key to yourself.
 *
 * <h2>What ships here</h2>
 * Only the <em>public</em> key is embedded below. The private half never leaves
 * the issuing machine and the Apps Script property store (see
 * {@code tools/licensing/}), so keys cannot be forged even though this file is
 * open source. Note this protects against <em>forgery</em>, not against someone
 * recompiling the plugin with the check deleted — see
 * {@code tools/licensing/README.md} for that trade-off.
 */
public final class LicenseKey {

    private static final Logger LOG = Logger.getLogger(LicenseKey.class.getName());

    private LicenseKey() {} // utility class, no instances

    /** Human-visible prefix, so a key is recognisable in an email. */
    public static final String PREFIX = "AXL8-";

    /**
     * Shown whenever the user needs a human. Declared here, not in
     * {@link LicenseManager}, so this class stays free of Eclipse imports and
     * can be compiled and tested on its own.
     */
    public static final String SUPPORT_EMAIL = "neo@genesispro.ai";

    private static final byte   VERSION = 2;
    private static final String PRODUCT = "AXLR8LITE";

    private static final int SIG_LEN    = 256;  // RSA-2048 signature
    private static final int HEADER_LEN = 5;    // version(1) + date(3) + email length(1)

    private static final String PLACEHOLDER = "PASTE_PUBLIC_KEY_HERE";

    /**
     * RSA public key, X.509 SubjectPublicKeyInfo, Base64 (one line).
     *
     * <p>Generated once by {@code tools/licensing/GenerateKeypair.java}; paste
     * the printed public key here. While the placeholder is in place the
     * plugin refuses every key.
     */
    static final String PUBLIC_KEY_B64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxmbwEBAHwxLnDf5TdnsIrUAesC596pjROjB197zsnL9vrk1YjArqJxSSa1dddAMka/s/KvJEdvbjou9OfVKB4OSYVMUmqEMJCzKFlN+on9xxHHPA8+IVDCK1Z/wQaBXuLs520Ajy7cipLOq9U/csKRaSYkMjhWhPv4fdBM77zxRoY7yX9ZbBa5VQ1+WcfGjcpXz5m2woWqI1kmEDo4QCelYvdC6wlSnlF7MiAoUeE+g36LaDwyC6LYt2NSa8EesthIIKLI4f9nRyVgl+LZ9RepgRNpQm+F+uZ9tLPjEbotBm59wr2a7Ji1wEpBqRjeUU9pb8rgUP/nKJBG/sDcA5YwIDAQAB";

    private static volatile PublicKey cachedKey;

    /** True once a real public key has been compiled in above. */
    public static boolean isConfigured() {
        return !PLACEHOLDER.equals(PUBLIC_KEY_B64) && !PUBLIC_KEY_B64.isBlank();
    }

    // ─── Verification ───────────────────────────────────────────────

    /**
     * Verify a pasted key. Everything needed is inside the key itself — the
     * user supplies nothing else. Never throws; every failure path returns an
     * invalid {@link Result} carrying a user-facing reason.
     */
    public static Result verify(String key) {
        if (key == null || key.isBlank()) {
            return Result.invalid("Enter the license key from your email.");
        }
        if (!isConfigured()) {
            // Fail closed: an unconfigured build must not activate anyone.
            LOG.severe("No license public key compiled into this build — run "
                + "tools/licensing/GenerateKeypair.java and paste the public key "
                + "into LicenseKey.PUBLIC_KEY_B64.");
            return Result.invalid("This build has no license public key. Please contact "
                + SUPPORT_EMAIL + ".");
        }

        String cleaned = normalizeKey(key);
        if (cleaned.length() <= PREFIX.length()) {
            return Result.invalid("That key looks incomplete — paste the whole line from the email.");
        }

        byte[] blob;
        try {
            blob = Base64.getUrlDecoder().decode(cleaned.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return Result.invalid("That key is malformed. Paste it exactly as it appears "
                + "in the email, with no characters missing.");
        }

        if (blob.length < HEADER_LEN + 1 + SIG_LEN) {
            return Result.invalid("That key is too short — it may have been truncated "
                + "or line-wrapped by your email client.");
        }
        if (blob[0] != VERSION) {
            return Result.invalid("That key was issued for a different version of "
                + "AXLR8 Lite. Please request a new one.");
        }

        int days = ((blob[1] & 0xFF) << 16) | ((blob[2] & 0xFF) << 8) | (blob[3] & 0xFF);
        int emailLen = blob[4] & 0xFF;

        if (blob.length != HEADER_LEN + emailLen + SIG_LEN) {
            return Result.invalid("That key is the wrong length — it may have been "
                + "truncated or line-wrapped by your email client.");
        }

        String email = new String(blob, HEADER_LEN, emailLen, StandardCharsets.UTF_8);
        byte[] sig = Arrays.copyOfRange(blob, HEADER_LEN + emailLen, blob.length);

        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey());
            verifier.update(signedMessage(email, days));
            if (!verifier.verify(sig)) {
                return Result.invalid("That key is not valid. Please paste the key exactly "
                    + "as it appears in your email, or contact " + SUPPORT_EMAIL + ".");
            }
        } catch (GeneralSecurityException e) {
            LOG.log(Level.WARNING, "License verification failed: " + e.getMessage(), e);
            return Result.invalid("Could not verify the key on this Java runtime. "
                + "Please contact " + SUPPORT_EMAIL + ".");
        }

        return Result.valid(email, LocalDate.ofEpochDay(days));
    }

    // ─── Canonical forms ────────────────────────────────────────────

    /** The exact bytes the issuer signs. Must match the generator byte-for-byte. */
    static byte[] signedMessage(String email, int issueDays) {
        String canonical = PRODUCT + "|" + VERSION + "|" + normalizeEmail(email) + "|" + issueDays;
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    /** Email addresses are stored and signed lower-cased and trimmed. */
    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Strip whitespace an email client may have inserted and normalise the
     * prefix. The Base64 body stays case-sensitive.
     */
    static String normalizeKey(String key) {
        String stripped = key.replaceAll("\\s", "");
        if (stripped.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return PREFIX + stripped.substring(PREFIX.length());
        }
        // Tolerate a key pasted without its prefix.
        return PREFIX + stripped;
    }

    private static PublicKey publicKey() throws GeneralSecurityException {
        PublicKey local = cachedKey;
        if (local == null) {
            byte[] der = Base64.getDecoder().decode(PUBLIC_KEY_B64.replaceAll("\\s", ""));
            local = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            cachedKey = local;
        }
        return local;
    }

    // ─── Result ─────────────────────────────────────────────────────

    /** Outcome of a verification attempt. */
    public static final class Result {
        private final boolean valid;
        private final String reason;
        private final String email;
        private final LocalDate issued;

        private Result(boolean valid, String reason, String email, LocalDate issued) {
            this.valid = valid;
            this.reason = reason;
            this.email = email;
            this.issued = issued;
        }

        static Result valid(String email, LocalDate issued) {
            return new Result(true, null, email, issued);
        }

        static Result invalid(String reason) {
            return new Result(false, reason, null, null);
        }

        public boolean isValid()     { return valid; }
        /** User-facing explanation; null when {@link #isValid()}. */
        public String getReason()    { return reason; }
        /** Address the key was issued to, read out of the key; null when invalid. */
        public String getEmail()     { return email; }
        /** Date the key was issued; null when invalid. Keys never expire. */
        public LocalDate getIssued() { return issued; }
    }
}
