package org.json;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Self-test only stand-in for org.json.JSONObject (see JSONException).
 * Behaviour mirrors the real class for the handful of calls the ported protocol
 * code makes (HiChain JSON envelopes, stress JSON dumps); it never ships.
 */
public class JSONObject {
    private final LinkedHashMap<String, Object> map = new LinkedHashMap<>();

    public JSONObject() {}

    public JSONObject(String json) throws JSONException {
        Object parsed = JsonStubParser.parse(json);
        if (!(parsed instanceof JSONObject)) {
            throw new JSONException("Not a JSON object: " + json);
        }
        map.putAll(((JSONObject) parsed).map);
    }

    public JSONObject(Map<?, ?> copyFrom) {
        if (copyFrom != null) {
            for (Map.Entry<?, ?> e : copyFrom.entrySet()) {
                map.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
    }

    public JSONObject put(String key, Object value) throws JSONException {
        if (key == null) throw new JSONException("Null key");
        map.put(key, normalize(value));
        return this;
    }

    public JSONObject putOnce(String key, Object value) throws JSONException {
        return put(key, value);
    }

    public JSONObject putOpt(String key, Object value) throws JSONException {
        if (key == null || value == null) return this;
        return put(key, value);
    }

    public Object remove(String key) {
        return map.remove(key);
    }

    public boolean has(String key) {
        return key != null && map.containsKey(key);
    }

    public boolean isNull(String key) {
        return !has(key) || map.get(key) == null;
    }

    public int length() {
        return map.size();
    }

    public Iterator<String> keys() {
        return map.keySet().iterator();
    }

    public Object get(String key) throws JSONException {
        if (key == null || !map.containsKey(key)) {
            throw new JSONException("No value for " + key);
        }
        Object v = map.get(key);
        if (v == null) throw new JSONException("Value is null for " + key);
        return v;
    }

    public Object opt(String key) {
        return key == null ? null : map.get(key);
    }

    public String getString(String key) throws JSONException {
        Object v = get(key);
        if (v instanceof String) return (String) v;
        return String.valueOf(v);
    }

    public String optString(String key) {
        return optString(key, "");
    }

    public String optString(String key, String fallback) {
        Object v = opt(key);
        return v instanceof String ? (String) v : (v == null ? fallback : String.valueOf(v));
    }

    public int getInt(String key) throws JSONException {
        Object v = get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        throw new JSONException("Not a number for " + key);
    }

    public int optInt(String key, int fallback) {
        Object v = opt(key);
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }

    public long getLong(String key) throws JSONException {
        Object v = get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        throw new JSONException("Not a number for " + key);
    }

    public long optLong(String key, long fallback) {
        Object v = opt(key);
        return v instanceof Number ? ((Number) v).longValue() : fallback;
    }

    public double getDouble(String key) throws JSONException {
        Object v = get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        throw new JSONException("Not a number for " + key);
    }

    public boolean getBoolean(String key) throws JSONException {
        Object v = get(key);
        if (v instanceof Boolean) return (Boolean) v;
        throw new JSONException("Not a boolean for " + key);
    }

    public boolean optBoolean(String key, boolean fallback) {
        Object v = opt(key);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    public JSONObject getJSONObject(String key) throws JSONException {
        Object v = get(key);
        if (v instanceof JSONObject) return (JSONObject) v;
        throw new JSONException("Not a JSONObject for " + key);
    }

    public JSONObject optJSONObject(String key) {
        Object v = opt(key);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    public JSONArray getJSONArray(String key) throws JSONException {
        Object v = get(key);
        if (v instanceof JSONArray) return (JSONArray) v;
        throw new JSONException("Not a JSONArray for " + key);
    }

    public JSONArray optJSONArray(String key) {
        Object v = opt(key);
        return v instanceof JSONArray ? (JSONArray) v : null;
    }

    private static Object normalize(Object value) throws JSONException {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Number || value instanceof JSONObject
                || value instanceof JSONArray) {
            return value;
        }
        if (value instanceof Collection) return new JSONArray((Collection<?>) value);
        if (value instanceof Map) return new JSONObject((Map<?, ?>) value);
        return value;
    }

    @Override
    public String toString() {
        return JsonStubParser.write(this);
    }

    /* package */ LinkedHashMap<String, Object> raw() {
        return map;
    }
}
