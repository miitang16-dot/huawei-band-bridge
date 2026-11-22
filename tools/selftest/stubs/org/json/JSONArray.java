package org.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Self-test only stand-in for org.json.JSONArray (see JSONException).
 * Small but functional: enough for the HiChain / stress JSON helpers to compile,
 * so the protocol classes stay testable without pulling the Android framework in.
 */
public class JSONArray {
    private final List<Object> values = new ArrayList<>();

    public JSONArray() {}

    public JSONArray(String json) throws JSONException {
        Object parsed = JsonStubParser.parse(json);
        if (!(parsed instanceof JSONArray)) {
            throw new JSONException("Not a JSON array: " + json);
        }
        values.addAll(((JSONArray) parsed).values);
    }

    public JSONArray(Collection<?> copyFrom) {
        if (copyFrom != null) values.addAll(copyFrom);
    }

    public int length() {
        return values.size();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public JSONArray put(Object value) throws JSONException {
        values.add(value);
        return this;
    }

    public JSONArray put(int index, Object value) throws JSONException {
        values.set(index, value);
        return this;
    }

    public Object remove(int index) {
        return index >= 0 && index < values.size() ? values.remove(index) : null;
    }

    public Object get(int index) throws JSONException {
        return checked(index);
    }

    public Object opt(int index) {
        return index >= 0 && index < values.size() ? values.get(index) : null;
    }

    public String getString(int index) throws JSONException {
        Object v = checked(index);
        if (v instanceof String) return (String) v;
        throw new JSONException("Not a string at index " + index);
    }

    public String optString(int index, String fallback) {
        Object v = opt(index);
        return v instanceof String ? (String) v : fallback;
    }

    public int getInt(int index) throws JSONException {
        Object v = checked(index);
        if (v instanceof Number) return ((Number) v).intValue();
        throw new JSONException("Not a number at index " + index);
    }

    public int optInt(int index, int fallback) {
        Object v = opt(index);
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }

    public long getLong(int index) throws JSONException {
        Object v = checked(index);
        if (v instanceof Number) return ((Number) v).longValue();
        throw new JSONException("Not a number at index " + index);
    }

    public double getDouble(int index) throws JSONException {
        Object v = checked(index);
        if (v instanceof Number) return ((Number) v).doubleValue();
        throw new JSONException("Not a number at index " + index);
    }

    public JSONObject getJSONObject(int index) throws JSONException {
        Object v = checked(index);
        if (v instanceof JSONObject) return (JSONObject) v;
        throw new JSONException("Not a JSONObject at index " + index);
    }

    public JSONArray getJSONArray(int index) throws JSONException {
        Object v = checked(index);
        if (v instanceof JSONArray) return (JSONArray) v;
        throw new JSONException("Not a JSONArray at index " + index);
    }

    private Object checked(int index) throws JSONException {
        if (index < 0 || index >= values.size()) {
            throw new JSONException("Index out of range: " + index);
        }
        return values.get(index);
    }

    @Override
    public String toString() {
        return JsonStubParser.write(this);
    }

    /* package */ List<Object> raw() {
        return values;
    }
}
