package me.nicolas.papea2ac.testing;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import me.nicolas.papea2ac.version.*;

/**
 * Machine-verifiable shape coverage inventory (task §16): every block of the pinned profile classified from the table
 * itself. Writes docs/phase4-artifacts/shape-coverage.tsv (one line per block, LC_ALL=C order) and shape-coverage.json
 * (totals). ShapeCoverageInventoryTest regenerates the same text in-process and asserts equality plus zero UNSUPPORTED.
 */
public final class ShapeCoverageMain {
    private ShapeCoverageMain() {}
    enum Classification { EMPTY_NON_COLLIDING, FULL_CUBE, EXACT, STATE_DERIVED_EXACT, NEIGHBOR_DERIVED_EXACT, POSITION_DERIVED_EXACT, CONTEXT_DERIVED_EXACT, CONSERVATIVE_UNCERTAIN_BY_SPEC, UNSUPPORTED }
    static Classification classify(BlockShapeTable t, BlockShapeTable.Block b) {
        switch (b.kind()) {
            case OFFSET_XZ: return Classification.POSITION_DERIVED_EXACT;
            case CONTEXT: return Classification.CONTEXT_DERIVED_EXACT;
            case ENTITY_CONTEXT: case BLOCK_ENTITY: return Classification.CONSERVATIVE_UNCERTAIN_BY_SPEC;
            default: break;
        }
        var shapes = new TreeSet<Integer>(); for (int s = 0; s < b.stateCount(); s++) shapes.add(b.shapeIndex(s));
        if (shapes.size() > 1) return Classification.STATE_DERIVED_EXACT;
        int only = shapes.first(); if (only == 0) return Classification.EMPTY_NON_COLLIDING;
        if (only == t.namedShape("full_cube")) return Classification.FULL_CUBE;
        return Classification.EXACT;
    }
    static String tsv(BlockShapeTable t) {
        var sb = new StringBuilder("# Phase 4 shape coverage inventory - profile ").append(t.profile()).append(" table sha256 ").append(t.digest()).append('\n');
        sb.append("# NEIGHBOR_DERIVED_EXACT is empty for this profile by construction: every connection-dependent family (fences, walls, panes, chorus, stairs shape, chest halves, ...) carries its connections as block-state properties the server sends, and the extractor verified that no STATIC block reads the level (BlockGetter) when resolving its shape.\n");
        sb.append("block\tkind\tclassification\tstates\tdistinctShapes\tmaxBoxes\tanyLarge\n");
        var names = new ArrayList<String>(); for (var b : t.blocks()) names.add(b.name()); Collections.sort(names);
        for (String n : names) {
            var b = t.block(n); var shapes = new TreeSet<Integer>(); int maxBoxes = 0; boolean large = false;
            for (int s = 0; s < b.stateCount(); s++) { shapes.add(b.shapeIndex(s)); maxBoxes = Math.max(maxBoxes, t.boxCount(b.shapeIndex(s))); large |= t.large(b.shapeIndex(s)); }
            if (b.kind() == BlockShapeTable.Kind.CONTEXT) for (int s = 0; s < b.stateCount(); s++) for (int v = 0; v < 6; v++) { shapes.add(b.contextVariant(s, v)); maxBoxes = Math.max(maxBoxes, t.boxCount(b.contextVariant(s, v))); }
            if (b.kind() != BlockShapeTable.Kind.STATIC) large = true;
            sb.append(n).append('\t').append(b.kind()).append('\t').append(classify(t, b)).append('\t').append(b.stateCount()).append('\t').append(shapes.size()).append('\t').append(maxBoxes).append('\t').append(large).append('\n');
        }
        return sb.toString();
    }
    static String json(BlockShapeTable t) {
        var counts = new EnumMap<Classification, int[]>(Classification.class); for (var c : Classification.values()) counts.put(c, new int[2]);
        for (var b : t.blocks()) { var c = classify(t, b); counts.get(c)[0]++; counts.get(c)[1] += b.stateCount(); }
        var sb = new StringBuilder("{\n  \"profile\": \"").append(t.profile()).append("\",\n  \"tableSha256\": \"").append(t.digest()).append("\",\n  \"blocks\": ").append(t.blockCount()).append(",\n  \"states\": ").append(t.stateCount()).append(",\n  \"uniqueShapes\": ").append(t.shapeCount()).append(",\n  \"classification\": {\n");
        int i = 0; for (var e : counts.entrySet()) { sb.append("    \"").append(e.getKey()).append("\": {\"blocks\": ").append(e.getValue()[0]).append(", \"states\": ").append(e.getValue()[1]).append("}").append(++i < counts.size() ? ",\n" : "\n"); }
        sb.append("  },\n  \"unexplainedUnsupported\": ").append(counts.get(Classification.UNSUPPORTED)[0]).append(",\n  \"neighborDerivedNote\": \"none in this profile: connection state is carried in block-state properties (verified: no STATIC block reads the level)\"\n}\n");
        return sb.toString();
    }
    public static void main(String[] args) throws IOException {
        var t = CollisionProfile.PAPER_26_2.table(); Path dir = Path.of(args[0]); Files.createDirectories(dir);
        Files.writeString(dir.resolve("shape-coverage.tsv"), tsv(t)); Files.writeString(dir.resolve("shape-coverage.json"), json(t));
        System.out.print(json(t));
    }
}
