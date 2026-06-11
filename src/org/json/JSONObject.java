// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package org.json;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class JSONObject {
    private final Map<String, Object> values;

    public JSONObject() {
        this.values = new LinkedHashMap<>();
    }

    public JSONObject(String json) {
        Object parsed = JSONParser.parse(json);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("JSON text is not an object");
        }
        this.values = new LinkedHashMap<>();
        map.forEach((key, value) -> this.values.put(String.valueOf(key), JSONValue.wrap(value)));
    }

    public JSONObject(Map<?, ?> map) {
        this.values = new LinkedHashMap<>();
        map.forEach((key, value) -> this.values.put(String.valueOf(key), JSONValue.wrap(value)));
    }

    public JSONObject put(String key, Object value) {
        values.put(key, JSONValue.wrap(value));
        return this;
    }

    public JSONArray getJSONArray(String key) {
        Object value = values.get(key);
        if (value instanceof JSONArray array) return array;
        throw new IllegalArgumentException("Value for key '" + key + "' is not a JSON array");
    }

    public JSONObject getJSONObject(String key) {
        Object value = values.get(key);
        if (value instanceof JSONObject object) return object;
        throw new IllegalArgumentException("Value for key '" + key + "' is not a JSON object");
    }

    public String getString(String key) {
        if (!values.containsKey(key)) {
            throw new IllegalArgumentException("Missing key: " + key);
        }
        return String.valueOf(values.get(key));
    }

    public JSONArray optJSONArray(String key) {
        Object value = values.get(key);
        return value instanceof JSONArray array ? array : null;
    }

    public JSONObject optJSONObject(String key) {
        Object value = values.get(key);
        return value instanceof JSONObject object ? object : null;
    }

    public Object get(String key) {
        if (!values.containsKey(key)) {
            throw new IllegalArgumentException("Missing key: " + key);
        }
        return values.get(key);
    }

    public Object opt(String key) {
        return values.get(key);
    }

    public String optString(String key, String defaultValue) {
        Object value = values.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    public int optInt(String key, int defaultValue) {
        Object value = values.get(key);
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public boolean optBoolean(String key, boolean defaultValue) {
        Object value = values.get(key);
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String text) return Boolean.parseBoolean(text);
        return defaultValue;
    }

    Map<String, Object> rawMap() {
        return values;
    }

    public Set<String> keySet() {
        return values.keySet();
    }

    @Override
    public String toString() {
        return JSONWriter.write(values);
    }
}
