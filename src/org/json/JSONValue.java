// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package org.json;

import java.util.Collection;
import java.util.Map;

final class JSONValue {
    private JSONValue() {}

    static Object wrap(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof JSONObject || value instanceof JSONArray) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return new JSONObject(map);
        }
        if (value instanceof Collection<?> collection) {
            return new JSONArray(collection);
        }
        return String.valueOf(value);
    }
}
