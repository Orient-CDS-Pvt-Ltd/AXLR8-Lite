// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package org.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class JSONArray {
    private final List<Object> values;

    public JSONArray() {
        this.values = new ArrayList<>();
    }

    public JSONArray(String json) {
        Object parsed = JSONParser.parse(json);
        if (!(parsed instanceof List<?> list)) {
            throw new IllegalArgumentException("JSON text is not an array");
        }
        this.values = new ArrayList<>();
        list.forEach(value -> this.values.add(JSONValue.wrap(value)));
    }

    public JSONArray(Collection<?> source) {
        this.values = new ArrayList<>();
        source.forEach(value -> this.values.add(JSONValue.wrap(value)));
    }

    public JSONArray put(Object value) {
        values.add(JSONValue.wrap(value));
        return this;
    }

    public int length() {
        return values.size();
    }

    public JSONObject getJSONObject(int index) {
        Object value = values.get(index);
        if (value instanceof JSONObject object) return object;
        throw new IllegalArgumentException("Array value at index " + index + " is not a JSON object");
    }

    public JSONArray getJSONArray(int index) {
        Object value = values.get(index);
        if (value instanceof JSONArray array) return array;
        throw new IllegalArgumentException("Array value at index " + index + " is not a JSON array");
    }

    public double getDouble(int index) {
        Object value = values.get(index);
        if (value instanceof Number number) return number.doubleValue();
        return Double.parseDouble(String.valueOf(value));
    }

    public String getString(int index) {
        Object value = values.get(index);
        if (value == null) {
            throw new IllegalArgumentException("Array value at index " + index + " is null");
        }
        return String.valueOf(value);
    }

    public Object get(int index) {
        return values.get(index);
    }

    public String optString(int index, String defaultValue) {
        if (index < 0 || index >= values.size()) return defaultValue;
        Object value = values.get(index);
        return value == null ? defaultValue : String.valueOf(value);
    }

    List<Object> rawList() {
        return values;
    }

    @Override
    public String toString() {
        return JSONWriter.write(values);
    }
}
