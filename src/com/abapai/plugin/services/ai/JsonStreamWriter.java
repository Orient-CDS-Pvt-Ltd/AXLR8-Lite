// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.abapai.plugin.services.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Streams a {@link JSONObject} directly to an {@link OutputStream} as UTF-8
 * bytes, never materializing the full body as an intermediate {@code String}.
 *
 * <p>Replaces the pattern:
 * <pre>{@code
 *   os.write(body.toString().getBytes(StandardCharsets.UTF_8));
 * }</pre>
 * with:
 * <pre>{@code
 *   JsonStreamWriter.writeTo(body, os);
 * }</pre>
 *
 * <h3>Why this exists (H5b — plaintext-boundary minimization)</h3>
 *
 * The replaced pattern materializes the entire request body (including any
 * system prompt and conversation history) as one immutable {@code String} in
 * the heap, then a UTF-8 byte[] copy of it. Both stay reachable until GC —
 * a {@code jmap --dump} during the request finds the prompt twice. This
 * class eliminates both the full-body String and the full-body byte[].
 *
 * <p>Per Oracle Java 17 docs, characters passed to {@link OutputStreamWriter#write}
 * are not buffered as chars; encoded bytes are buffered before reaching the
 * underlying stream. So no long-lived char buffer holds the full prompt
 * either. Residual exposure is only the transient byte buffer inside
 * OutputStreamWriter (small) plus whatever buffering the underlying
 * connection / TLS layer does (also transient per JSSE contract).
 *
 * <p>This is H5b-shallow — it does not eliminate the {@code String systemPrompt}
 * parameter at the caller boundary (that's H5b-deep / Day 8 work, via
 * {@code EncryptedPrompt}).
 *
 * <h3>Byte-identical to {@code body.toString().getBytes(UTF_8)}</h3>
 *
 * Output must match the existing wire bytes exactly. Two non-obvious rules
 * inherited from {@link org.json.JSONWriter#write}:
 * <ul>
 *   <li>Only 7 chars are escaped: {@code \\ " \b \f \n \r \t}. Control
 *       characters below 0x20 (other than those 5) are written raw — NOT
 *       escaped as {@code \\u00XX}. This is non-standard JSON but matches
 *       what the existing wire bytes are today.</li>
 *   <li>Numbers + Booleans are formatted via {@code String.valueOf}, no
 *       custom formatting.</li>
 * </ul>
 *
 * <p>Iteration order matches existing output because {@code JSONObject}'s
 * backing map is a {@code LinkedHashMap} (insertion-order preserving).
 *
 * <h3>Caller responsibility</h3>
 *
 * Caller owns the {@link OutputStream} lifecycle (typically via
 * try-with-resources). This class flushes the writer but does NOT close
 * the underlying stream — closing would propagate to the connection's
 * stream and cause double-close issues with the caller's
 * try-with-resources block.
 */
final class JsonStreamWriter {

    private JsonStreamWriter() { /* no instances */ }

    /**
     * Stream {@code body} as UTF-8 JSON bytes directly to {@code os}.
     * Output is byte-identical to {@code body.toString().getBytes(UTF_8)}.
     *
     * @throws IOException if the underlying stream errors
     */
    static void writeTo(JSONObject body, OutputStream os) throws IOException {
        OutputStreamWriter w = new OutputStreamWriter(os, StandardCharsets.UTF_8);
        try {
            writeValue(w, body);
        } finally {
            // Flush so all encoded bytes reach `os` before caller's
            // try-with-resources closes it. Do not close `w` — that would
            // propagate to closing `os`, which the caller owns.
            w.flush();
        }
    }

    private static void writeValue(Writer w, Object value) throws IOException {
        if (value == null) {
            w.write("null");
        } else if (value instanceof String s) {
            writeJsonString(w, s);
        } else if (value instanceof JSONObject obj) {
            writeJsonObject(w, obj);
        } else if (value instanceof JSONArray arr) {
            writeJsonArray(w, arr);
        } else if (value instanceof Number || value instanceof Boolean) {
            // Matches org.json.JSONWriter.write() line 16: String.valueOf(value).
            w.write(String.valueOf(value));
        } else {
            // Per JSONValue.wrap(), no other types reach a stored value, but
            // be defensive: fallback to the same shape org.json.JSONWriter
            // uses for unknown types — quoted, escape-applied String form.
            writeJsonString(w, String.valueOf(value));
        }
    }

    private static void writeJsonObject(Writer w, JSONObject obj) throws IOException {
        w.write('{');
        boolean first = true;
        // keySet() of LinkedHashMap-backed JSONObject preserves insertion order,
        // matching org.json.JSONWriter's entrySet() iteration.
        for (String key : obj.keySet()) {
            if (!first) w.write(',');
            first = false;
            writeJsonString(w, key);
            w.write(':');
            writeValue(w, obj.opt(key));
        }
        w.write('}');
    }

    private static void writeJsonArray(Writer w, JSONArray arr) throws IOException {
        w.write('[');
        int n = arr.length();
        for (int i = 0; i < n; i++) {
            if (i > 0) w.write(',');
            writeValue(w, arr.get(i));
        }
        w.write(']');
    }

    /**
     * Writes a JSON-quoted string char by char. Matches
     * {@link org.json.JSONWriter#escape} byte-for-byte:
     *
     * <ul>
     *   <li>Escapes only: {@code \\ " \b \f \n \r \t}</li>
     *   <li>Does NOT escape other control characters below 0x20 — they
     *       are written raw to match the existing wire bytes. (This is
     *       non-standard JSON but matches the plugin's current output.)</li>
     * </ul>
     *
     * <p>This is the critical method for H5b: when {@code s} is the system
     * prompt (or other prompt content), each character streams directly
     * into the writer's small encoded-byte buffer and out to the socket,
     * with no full-body String holding it.
     */
    private static void writeJsonString(Writer w, String s) throws IOException {
        w.write('"');
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> w.write("\\\\");
                case '"'  -> w.write("\\\"");
                case '\b' -> w.write("\\b");
                case '\f' -> w.write("\\f");
                case '\n' -> w.write("\\n");
                case '\r' -> w.write("\\r");
                case '\t' -> w.write("\\t");
                default   -> w.write(c);
            }
        }
        w.write('"');
    }
}
