package org.json;

/**
 * Tiny JSON reader/writer used by the self-test stubs for JSONObject/JSONArray.
 *
 * Scope: what the ported protocol code round-trips (HiChain auth envelopes and
 * the stress-data JSON helpers). Not a general-purpose JSON library and never
 * shipped to Android: on device the real org.json is used.
 */
final class JsonStubParser {
    private final String src;
    private int pos;

    private JsonStubParser(String src) {
        this.src = src;
    }

    static Object parse(String text) throws JSONException {
        if (text == null) throw new JSONException("null JSON text");
        JsonStubParser p = new JsonStubParser(text);
        p.skipWs();
        Object value = p.readValue();
        p.skipWs();
        if (p.pos != p.src.length()) {
            throw new JSONException("Trailing characters at " + p.pos + ": " + text);
        }
        return value;
    }

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            sb.append('"').append(escape((String) value)).append('"');
        } else if (value instanceof JSONObject) {
            sb.append('{');
            boolean first = true;
            for (java.util.Map.Entry<String, Object> e : ((JSONObject) value).raw().entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escape(e.getKey())).append('"').append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof JSONArray) {
            sb.append('[');
            boolean first = true;
            for (Object item : ((JSONArray) value).raw()) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else {
            sb.append(value);
        }
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private Object readValue() throws JSONException {
        skipWs();
        if (pos >= src.length()) throw new JSONException("Unexpected end of JSON");
        char c = src.charAt(pos);
        switch (c) {
            case '{': return readObject();
            case '[': return readArray();
            case '"': return readString();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return readNumber();
        }
    }

    private JSONObject readObject() throws JSONException {
        JSONObject obj = new JSONObject();
        pos++; // '{'
        skipWs();
        if (peek() == '}') { pos++; return obj; }
        while (true) {
            skipWs();
            String key = readString();
            skipWs();
            if (peek() != ':') throw new JSONException("Expected ':' at " + pos);
            pos++;
            obj.raw().put(key, readValue());
            skipWs();
            char c = peek();
            if (c == ',') { pos++; continue; }
            if (c == '}') { pos++; return obj; }
            throw new JSONException("Expected ',' or '}' at " + pos);
        }
    }

    private JSONArray readArray() throws JSONException {
        JSONArray arr = new JSONArray();
        pos++; // '['
        skipWs();
        if (peek() == ']') { pos++; return arr; }
        while (true) {
            arr.raw().add(readValue());
            skipWs();
            char c = peek();
            if (c == ',') { pos++; continue; }
            if (c == ']') { pos++; return arr; }
            throw new JSONException("Expected ',' or ']' at " + pos);
        }
    }

    private String readString() throws JSONException {
        if (peek() != '"') throw new JSONException("Expected string at " + pos);
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) throw new JSONException("Unterminated string");
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            if (pos >= src.length()) throw new JSONException("Unterminated escape");
            char e = src.charAt(pos++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > src.length()) throw new JSONException("Bad unicode escape");
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default: throw new JSONException("Bad escape \\" + e);
            }
        }
    }

    private Number readNumber() throws JSONException {
        int start = pos;
        if (peek() == '-') pos++;
        while (pos < src.length() && "0123456789.eE+-".indexOf(src.charAt(pos)) >= 0) pos++;
        String num = src.substring(start, pos);
        if (num.isEmpty()) throw new JSONException("Bad JSON value at " + start);
        try {
            if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0) {
                return Double.parseDouble(num);
            }
            long l = Long.parseLong(num);
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) l;
            return l;
        } catch (NumberFormatException e) {
            throw new JSONException("Bad number '" + num + "'", e);
        }
    }

    private void expect(String literal) throws JSONException {
        if (!src.startsWith(literal, pos)) {
            throw new JSONException("Expected " + literal + " at " + pos);
        }
        pos += literal.length();
    }

    private char peek() throws JSONException {
        if (pos >= src.length()) throw new JSONException("Unexpected end of JSON");
        return src.charAt(pos);
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }
}
