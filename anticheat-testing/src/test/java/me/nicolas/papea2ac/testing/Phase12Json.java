package me.nicolas.papea2ac.testing;

import java.util.Collection;
import java.util.Map;

/** Deterministic JSON rendering for Phase 12 artifacts (insertion order kept; no external library). */
final class Phase12Json {
    private Phase12Json() {}
    static String json(Object o, int indent) {
        String pad = "  ".repeat(indent + 1), end = "  ".repeat(indent);
        if (o instanceof Map<?, ?> m) {
            if (m.isEmpty()) return "{}";
            StringBuilder s = new StringBuilder("{\n"); int i = 0;
            for (var e : m.entrySet()) s.append(pad).append('"').append(e.getKey()).append("\": ").append(json(e.getValue(), indent + 1)).append(++i < m.size() ? ",\n" : "\n");
            return s.append(end).append('}').toString();
        }
        if (o instanceof Collection<?> c) {
            if (c.isEmpty()) return "[]";
            StringBuilder s = new StringBuilder("[\n"); int i = 0;
            for (Object v : c) s.append(pad).append(json(v, indent + 1)).append(++i < c.size() ? ",\n" : "\n");
            return s.append(end).append(']').toString();
        }
        if (o instanceof String str) return '"' + str.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        return String.valueOf(o);
    }
}
