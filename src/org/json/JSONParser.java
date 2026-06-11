// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package org.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JSONParser {
    private final String text;
    private int index;

    private JSONParser(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        JSONParser parser = new JSONParser(text);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.isEnd()) {
            throw new IllegalArgumentException("Unexpected trailing JSON content");
        }
        return value;
    }

    private Object parseValue() {
        skipWhitespace();
        if (isEnd()) throw new IllegalArgumentException("Unexpected end of JSON");
        char ch = text.charAt(index);
        return switch (ch) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't' -> parseLiteral("true", Boolean.TRUE);
            case 'f' -> parseLiteral("false", Boolean.FALSE);
            case 'n' -> parseLiteral("null", null);
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObject() {
        expect('{');
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek('}')) {
            expect('}');
            return map;
        }
        while (true) {
            String key = parseString();
            skipWhitespace();
            expect(':');
            map.put(key, parseValue());
            skipWhitespace();
            if (peek('}')) {
                expect('}');
                return map;
            }
            expect(',');
        }
    }

    private List<Object> parseArray() {
        expect('[');
        ArrayList<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek(']')) {
            expect(']');
            return list;
        }
        while (true) {
            list.add(parseValue());
            skipWhitespace();
            if (peek(']')) {
                expect(']');
                return list;
            }
            expect(',');
        }
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (!isEnd()) {
            char ch = text.charAt(index++);
            if (ch == '"') return sb.toString();
            if (ch == '\\') {
                if (isEnd()) throw new IllegalArgumentException("Invalid JSON escape");
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> sb.append(escaped);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> sb.append(parseUnicode());
                    default -> throw new IllegalArgumentException("Unsupported JSON escape: \\" + escaped);
                }
            } else {
                sb.append(ch);
            }
        }
        throw new IllegalArgumentException("Unterminated JSON string");
    }

    private char parseUnicode() {
        if (index + 4 > text.length()) {
            throw new IllegalArgumentException("Invalid unicode escape");
        }
        String hex = text.substring(index, index + 4);
        index += 4;
        return (char) Integer.parseInt(hex, 16);
    }

    private Object parseLiteral(String literal, Object value) {
        if (!text.startsWith(literal, index)) {
            throw new IllegalArgumentException("Invalid JSON literal");
        }
        index += literal.length();
        return value;
    }

    private Number parseNumber() {
        int start = index;
        if (text.charAt(index) == '-') index++;
        while (!isEnd() && Character.isDigit(text.charAt(index))) index++;
        if (!isEnd() && text.charAt(index) == '.') {
            index++;
            while (!isEnd() && Character.isDigit(text.charAt(index))) index++;
        }
        if (!isEnd() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
            index++;
            if (!isEnd() && (text.charAt(index) == '+' || text.charAt(index) == '-')) index++;
            while (!isEnd() && Character.isDigit(text.charAt(index))) index++;
        }
        String token = text.substring(start, index);
        if (token.contains(".") || token.contains("e") || token.contains("E")) {
            return Double.parseDouble(token);
        }
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException ignored) {
            return Long.parseLong(token);
        }
    }

    private void expect(char expected) {
        skipWhitespace();
        if (isEnd() || text.charAt(index) != expected) {
            throw new IllegalArgumentException("Expected '" + expected + "'");
        }
        index++;
    }

    private boolean peek(char expected) {
        skipWhitespace();
        return !isEnd() && text.charAt(index) == expected;
    }

    private void skipWhitespace() {
        while (!isEnd() && Character.isWhitespace(text.charAt(index))) index++;
    }

    private boolean isEnd() {
        return index >= text.length();
    }
}
