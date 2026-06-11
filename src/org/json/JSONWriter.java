// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package org.json;

import java.util.Iterator;
import java.util.Map;

final class JSONWriter {
    private JSONWriter() {}

    static String write(Object value) {
        if (value == null) return "null";
        if (value instanceof JSONObject object) return write(object.rawMap());
        if (value instanceof JSONArray array) return write(array.rawList());
        if (value instanceof Map<?, ?> map) return writeObject(map);
        if (value instanceof Iterable<?> iterable) return writeArray(iterable);
        if (value instanceof String text) return "\"" + escape(text) + "\"";
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return "\"" + escape(String.valueOf(value)) + "\"";
    }

    private static String writeObject(Map<?, ?> map) {
        StringBuilder sb = new StringBuilder("{");
        Iterator<? extends Map.Entry<?, ?>> iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<?, ?> entry = iterator.next();
            sb.append("\"").append(escape(String.valueOf(entry.getKey()))).append("\":");
            sb.append(write(entry.getValue()));
            if (iterator.hasNext()) sb.append(',');
        }
        return sb.append('}').toString();
    }

    private static String writeArray(Iterable<?> iterable) {
        StringBuilder sb = new StringBuilder("[");
        Iterator<?> iterator = iterable.iterator();
        while (iterator.hasNext()) {
            sb.append(write(iterator.next()));
            if (iterator.hasNext()) sb.append(',');
        }
        return sb.append(']').toString();
    }

    private static String escape(String text) {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\f", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
