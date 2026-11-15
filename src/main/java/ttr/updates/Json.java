package ttr.updates;

import java.io.IOException;
import java.util.*;

/** Small bounded JSON reader; no dependency on removed JavaScript engines. */
final class Json {
    private final String text;
    private int at;
    private Json(String text) { this.text = text; }
    static Object parse(String text) throws IOException {
        Json parser = new Json(text);
        Object value = parser.value(0);
        parser.space();
        if (parser.at != text.length()) throw parser.error();
        return value;
    }
    private IOException error() { return new IOException("Invalid JSON at " + at); }
    private void space() { while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++; }
    private boolean take(char c) { space(); if (at < text.length() && text.charAt(at) == c) { at++; return true; } return false; }
    private Object value(int depth) throws IOException {
        if (depth > 40) throw error();
        space();
        if (at >= text.length()) throw error();
        if (text.charAt(at) == '"') return string();
        if (take('{')) {
            Map<String,Object> result = new LinkedHashMap<String,Object>();
            if (take('}')) return result;
            do {
                space(); String key = string();
                if (!take(':') || result.containsKey(key)) throw error();
                result.put(key, value(depth + 1));
                if (take('}')) return result;
            } while (take(','));
            throw error();
        }
        if (take('[')) {
            List<Object> result = new ArrayList<Object>();
            if (take(']')) return result;
            do { result.add(value(depth + 1)); if (take(']')) return result; } while (take(','));
            throw error();
        }
        for (String literal : new String[]{"true", "false", "null"}) {
            if (text.startsWith(literal, at)) { at += literal.length(); return literal.equals("null") ? null : Boolean.valueOf(literal); }
        }
        int start = at;
        if (at < text.length() && text.charAt(at) == '-') at++;
        if (at >= text.length() || !Character.isDigit(text.charAt(at))) throw error();
        if (text.charAt(at) == '0') at++; else while (at < text.length() && Character.isDigit(text.charAt(at))) at++;
        if (at < text.length() && text.charAt(at) == '.') {
            at++; int digits = at;
            while (at < text.length() && Character.isDigit(text.charAt(at))) at++;
            if (at == digits) throw error();
        }
        if (at < text.length() && "eE".indexOf(text.charAt(at)) >= 0) {
            at++; if (at < text.length() && "+-".indexOf(text.charAt(at)) >= 0) at++;
            int digits = at; while (at < text.length() && Character.isDigit(text.charAt(at))) at++;
            if (at == digits) throw error();
        }
        try { return new java.math.BigDecimal(text.substring(start, at)); } catch (NumberFormatException ex) { throw error(); }
    }
    private String string() throws IOException {
        if (at >= text.length() || text.charAt(at++) != '"') throw error();
        StringBuilder result = new StringBuilder();
        while (at < text.length()) {
            char c = text.charAt(at++);
            if (c == '"') return result.toString();
            if (c < 32) throw error();
            if (c == '\\') {
                if (at >= text.length()) throw error();
                c = text.charAt(at++);
                switch (c) {
                    case '"': case '\\': case '/': break;
                    case 'b': c = '\b'; break; case 'f': c = '\f'; break;
                    case 'n': c = '\n'; break; case 'r': c = '\r'; break; case 't': c = '\t'; break;
                    case 'u':
                        if (at + 4 > text.length()) throw error();
                        try { c = (char)Integer.parseInt(text.substring(at, at + 4), 16); } catch (NumberFormatException ex) { throw error(); }
                        at += 4; break;
                    default: throw error();
                }
            }
            result.append(c);
        }
        throw error();
    }
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object value) throws IOException {
        if (!(value instanceof Map)) throw new IOException("Expected JSON object"); return (Map<String,Object>)value;
    }
    @SuppressWarnings("unchecked") static List<Object> array(Object value) throws IOException {
        if (!(value instanceof List)) throw new IOException("Expected JSON array"); return (List<Object>)value;
    }
    static String string(Map<String,Object> object, String key) throws IOException {
        Object value = object.get(key); if (!(value instanceof String)) throw new IOException("Missing string: " + key); return (String)value;
    }
}
