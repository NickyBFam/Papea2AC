package me.nicolas.papea2ac.testing;
import java.util.*;

/** Minimal JSON reader/writer for test artifacts (objects -> LinkedHashMap, arrays -> ArrayList, numbers -> Double/Long, no external dependency on any lane). */
final class MiniJson {
    private MiniJson() {}
    static Object parse(String s) { P p = new P(s); Object v = p.value(); p.ws(); if (p.i != s.length()) throw new IllegalArgumentException("trailing data at " + p.i); return v; }
    private static final class P {
        final String s; int i;
        P(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() {
            ws(); char c = s.charAt(i);
            if (c == '{') { i++; Map<String, Object> m = new LinkedHashMap<>(); ws(); if (s.charAt(i) == '}') { i++; return m; } while (true) { ws(); String k = string(); ws(); expect(':'); m.put(k, value()); ws(); if (s.charAt(i) == ',') { i++; continue; } expect('}'); return m; } }
            if (c == '[') { i++; List<Object> l = new ArrayList<>(); ws(); if (s.charAt(i) == ']') { i++; return l; } while (true) { l.add(value()); ws(); if (s.charAt(i) == ',') { i++; continue; } expect(']'); return l; } }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            if (s.startsWith("NaN", i)) { i += 3; return Double.NaN; }
            if (s.startsWith("Infinity", i)) { i += 8; return Double.POSITIVE_INFINITY; }
            if (s.startsWith("-Infinity", i)) { i += 9; return Double.NEGATIVE_INFINITY; }
            int start = i; while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String num = s.substring(start, i);
            if (num.isEmpty()) throw new IllegalArgumentException("bad json at " + start);
            if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
            long l = Long.parseLong(num); return l;
        }
        void expect(char c) { if (s.charAt(i) != c) throw new IllegalArgumentException("expected " + c + " at " + i + " got " + s.charAt(i)); i++; }
        String string() {
            expect('"'); StringBuilder b = new StringBuilder();
            while (true) { char c = s.charAt(i++); if (c == '"') return b.toString(); if (c == '\\') { char e = s.charAt(i++); switch (e) { case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'r' -> b.append('\r'); case 'b' -> b.append('\b'); case 'f' -> b.append('\f'); case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; } default -> b.append(e); } } else b.append(c); }
        }
    }
    @SuppressWarnings("unchecked") static Map<String, Object> obj(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked") static List<Object> arr(Object o) { return (List<Object>) o; }
    static double num(Object o) { return ((Number) o).doubleValue(); }
    static int integer(Object o) { return ((Number) o).intValue(); }
    static boolean bool(Object o) { return (Boolean) o; }

    static String write(Object o) { StringBuilder sb = new StringBuilder(); write(o, sb, 0); return sb.toString(); }
    private static void write(Object o, StringBuilder sb, int indent) {
        String pad = "  ".repeat(indent + 1), padEnd = "  ".repeat(indent);
        if (o instanceof Map<?, ?> m) { if (m.isEmpty()) { sb.append("{}"); return; } sb.append("{\n"); var it = m.entrySet().iterator(); while (it.hasNext()) { var e = it.next(); sb.append(pad).append('"').append(escape(String.valueOf(e.getKey()))).append("\": "); write(e.getValue(), sb, indent + 1); if (it.hasNext()) sb.append(','); sb.append('\n'); } sb.append(padEnd).append('}'); }
        else if (o instanceof List<?> l) { if (l.isEmpty()) { sb.append("[]"); return; } boolean scalars = true; for (Object x : l) if (x instanceof Map || x instanceof List) { scalars = false; break; }
            if (scalars) { sb.append('['); for (int i = 0; i < l.size(); i++) { if (i > 0) sb.append(", "); write(l.get(i), sb, indent + 1); } sb.append(']'); }
            else { sb.append("[\n"); for (int i = 0; i < l.size(); i++) { sb.append(pad); write(l.get(i), sb, indent + 1); if (i + 1 < l.size()) sb.append(','); sb.append('\n'); } sb.append(padEnd).append(']'); } }
        else if (o instanceof String s) sb.append('"').append(escape(s)).append('"');
        else if (o instanceof Float f) sb.append(Float.toString(f));
        else if (o instanceof Double d) sb.append(d.isNaN() ? "NaN" : d.isInfinite() ? (d > 0 ? "Infinity" : "-Infinity") : Double.toString(d));
        else sb.append(String.valueOf(o));
    }
    private static String escape(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"); }
}
