package me.nicolas.papea2ac.version;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import me.nicolas.papea2ac.model.BlockStateKey;

/**
 * Version-pinned vanilla block OUTLINE shapes (Phase 12 R7: the client's attack pick clips blocks with {@code ClipContext.Block.OUTLINE}, not
 * with collision shapes). Loaded once from the generated resource {@code outline-shapes-paper-26.2-124.tsv} that the nativeTest-only extraction
 * ({@code OutlineShapeExtractMain}) produces from the pinned Paper 26.2 build 124 server. Production never touches NMS.
 *
 * <p>Per block: STATIC (the shape is a pure function of the state), OFFSET (the un-offset shape; consumers widen it by the maximum horizontal /
 * vertical offsets), CONTEXT or LEVEL (unknown to consumers). An unknown block, property or value resolves to {@code null}, never a guess.
 */
public final class OutlineShapeTable {
    public enum Kind { STATIC, OFFSET, CONTEXT, LEVEL }
    /** One resolved state: its kind, its canonical boxes (6 doubles each, block-local) and, for OFFSET, the maximum offsets. */
    public record Outline(Kind kind, double[] boxes, float maxHorizontalOffset, float maxVerticalOffset) {
        public boolean empty() { return boxes.length == 0; }
    }
    private record Block(Kind kind, float maxH, float maxV, String[] properties, List<List<String>> values, int[] radix, int[] shapeIndex) {}

    public static final String PAPER_26_2_RESOURCE = "/me/nicolas/papea2ac/version/outline-shapes-paper-26.2-124.tsv";
    private static final class Holder { static final OutlineShapeTable TABLE = load(PAPER_26_2_RESOURCE); }
    public static OutlineShapeTable paper26_2() { return Holder.TABLE; }

    private final Map<String, Block> blocks; private final double[][] shapes; private final String digest;
    private OutlineShapeTable(Map<String, Block> blocks, double[][] shapes, String digest) { this.blocks = blocks; this.shapes = shapes; this.digest = digest; }
    public String digest() { return digest; }
    public int blockCount() { return blocks.size(); }
    public int shapeCount() { return shapes.length; }

    /** The outline of a canonical state, or null when the block, a property or a value is unknown. The returned boxes array is a copy. */
    public Outline lookup(BlockStateKey key) {
        Block b = blocks.get(key.block());
        if (b == null || key.properties().size() != b.properties().length) return null;
        int idx = 0;
        for (int i = 0; i < b.properties().length; i++) {
            String v = key.properties().get(b.properties()[i]); if (v == null) return null;
            int vi = b.values().get(i).indexOf(v); if (vi < 0) return null;
            idx = idx * b.radix()[i] + vi;
        }
        return new Outline(b.kind(), shapes[b.shapeIndex()[idx]].clone(), b.maxH(), b.maxV());
    }

    static OutlineShapeTable load(String resource) {
        try (InputStream in = OutlineShapeTable.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing resource " + resource);
            return parse(in.readAllBytes());
        } catch (IOException e) { throw new IllegalStateException("cannot read " + resource, e); }
    }

    public static OutlineShapeTable parse(byte[] bytes) {
        String digest;
        try { digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception e) { throw new IllegalStateException(e); }
        List<double[]> shapes = new ArrayList<>(); Map<String, Block> blocks = new HashMap<>();
        String pendingName = null; Kind pendingKind = null; float pmh = 0, pmv = 0; String[] pprops = null; List<List<String>> pvalues = null; int[] pradix = null; int pstates = 0;
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] t = line.trim().split(" ");
            switch (t[0]) {
                case "S" -> {
                    int index = Integer.parseInt(t[1]); if (index != shapes.size()) throw new IllegalArgumentException("shape order " + line);
                    double[] boxes = new double[t.length - 2]; for (int i = 2; i < t.length; i++) boxes[i - 2] = Double.parseDouble(t[i]);
                    if (boxes.length % 6 != 0) throw new IllegalArgumentException("shape " + index);
                    shapes.add(boxes);
                }
                case "B" -> {
                    pendingName = t[1]; pstates = Integer.parseInt(t[2]); pendingKind = Kind.valueOf(t[3]); int i = 4;
                    if (pendingKind == Kind.OFFSET) { pmh = Float.parseFloat(t[4]); pmv = Float.parseFloat(t[5]); i = 6; } else { pmh = 0; pmv = 0; }
                    List<String> ps = new ArrayList<>(); pvalues = new ArrayList<>();
                    for (; i < t.length; i++) { int eq = t[i].indexOf('='); ps.add(t[i].substring(0, eq)); pvalues.add(List.of(t[i].substring(eq + 1).split(","))); }
                    pprops = ps.toArray(String[]::new); pradix = new int[pprops.length]; for (int k = 0; k < pradix.length; k++) pradix[k] = pvalues.get(k).size();
                }
                case "I" -> {
                    int[] idx = new int[pstates]; int n = 0;
                    for (int i = 1; i < t.length; i++) { String[] rc = t[i].split("\\*"); int v = Integer.parseInt(rc[0]), c = rc.length > 1 ? Integer.parseInt(rc[1]) : 1; for (int k = 0; k < c; k++) idx[n++] = v; }
                    if (n != pstates) throw new IllegalArgumentException("state count of " + pendingName);
                    blocks.put(pendingName, new Block(pendingKind, pmh, pmv, pprops, pvalues, pradix, idx));
                }
                default -> throw new IllegalArgumentException("unknown line " + line);
            }
        }
        return new OutlineShapeTable(Map.copyOf(blocks), shapes.toArray(double[][]::new), digest);
    }
}
