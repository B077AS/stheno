package io.github.b077as.stheno.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader for the small bodies exchanged with the fake Access Point (objects, arrays, strings, numbers, booleans,
 * null). Not a general purpose parser; keeps the test module free of a JSON library choice.
 */
final class MiniJson {

    private final String s;
    private int pos;

    private MiniJson(String s) {
        this.s = s;
    }

    static Object parse(String json) {
        MiniJson p = new MiniJson(json);
        p.skipWhitespace();
        Object value = p.value();
        p.skipWhitespace();
        if (p.pos != json.length()) {
            throw p.error("trailing content");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String json) {
        Object o = parse(json);
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException("Not a JSON object: " + json);
        }
        return (Map<String, Object>) o;
    }

    private Object value() {
        skipWhitespace();
        if (pos >= s.length()) {
            throw error("unexpected end");
        }
        char c = s.charAt(pos);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = string();
            skipWhitespace();
            expect(':');
            map.put(key, value());
            skipWhitespace();
            if (peek() == ',') {
                pos++;
            } else {
                expect('}');
                return map;
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        pos++;
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(value());
            skipWhitespace();
            if (peek() == ',') {
                pos++;
            } else {
                expect(']');
                return list;
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (pos < s.length()) {
            char c = s.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char e = s.charAt(pos++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        throw error("unterminated string");
    }

    private Object number() {
        int start = pos;
        while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
            pos++;
        }
        if (start == pos) {
            throw error("unexpected character");
        }
        String n = s.substring(start, pos);
        return n.contains(".") || n.contains("e") || n.contains("E") ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n);
    }

    private Object literal(String word, Object value) {
        if (!s.startsWith(word, pos)) {
            throw error("unexpected literal");
        }
        pos += word.length();
        return value;
    }

    private char peek() {
        return pos < s.length() ? s.charAt(pos) : '\0';
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("expected '" + c + "'");
        }
        pos++;
    }

    private void skipWhitespace() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
            pos++;
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("Invalid JSON at " + pos + " (" + what + "): " + s);
    }
}
