package me.nicolas.papea2ac.version;
import me.nicolas.papea2ac.model.BlockStateKey;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Version-pinned vanilla block collision geometry (VERSION_SUPPORT.md: "Pure VersionProfile includes ...
 * collision/outline shapes"). Loaded once from a generated, reviewable text resource produced by the controlled
 * nativeTest-only extraction ({@code CollisionShapeExtractMain}) from the pinned Paper 26.2 build 124 server:
 * every state of {@code Block.BLOCK_STATE_REGISTRY} mapped to a canonical box list, plus the block-level
 * classification of which states are a pure function of the canonical state and which need position / entity
 * context / block-entity knowledge. Production never touches NMS: the table is data, the resolver in
 * anticheat-collision applies it.
 *
 * Lookup is allocation-free after load: a block's states are addressed by the same mixed-radix index vanilla's
 * {@code StateDefinition.getPossibleStates()} uses (properties in name order, first property most significant),
 * which the extractor verified against the registry order for every state. A key naming an unknown block, a
 * missing/unknown property or an extra property resolves to {@code null} = UNSUPPORTED - never a guessed cube.
 *
 * Immutable and safely publishable; one shared instance per profile ({@link #PAPER_26_2}).
 */
public final class BlockShapeTable {
    /** Block-level classification, from the extractor's empirical checks (task §16 inventory vocabulary in PHASE_4_IMPLEMENTATION.md). */
    public enum Kind {
        /** Shape is a pure function of the canonical state (verified constant over positions, contexts and level access). */
        STATIC,
        /** Shape is the resting shape translated by the deterministic per-position XZ offset ({@code Mth.getSeed}). */
        OFFSET_XZ,
        /** Shape depends on the colliding entity's bottom Y / descending flag only; six extracted context variants per state. */
        CONTEXT,
        /** Shape depends on entity attributes no context probe can supply without an entity (powder snow: fall distance, boots). */
        ENTITY_CONTEXT,
        /** Shape depends on a block entity (shulker box lid, moving piston progress): only bounds are knowable from observed traffic. */
        BLOCK_ENTITY
    }
    public static final class Block {
        final String name; final Kind kind; final String[] properties; final String[][] values; final int[] radix;
        final int[] shapeIndex; final int[] contextVariants; final float maxHorizontalOffset, maxVerticalOffset;
        Block(String name, Kind kind, String[] properties, String[][] values, int[] shapeIndex, int[] contextVariants, float maxH, float maxV) {
            this.name = name; this.kind = kind; this.properties = properties; this.values = values; this.shapeIndex = shapeIndex; this.contextVariants = contextVariants;
            this.maxHorizontalOffset = maxH; this.maxVerticalOffset = maxV;
            radix = new int[properties.length]; for (int i = 0; i < radix.length; i++) radix[i] = values[i].length;
        }
        public String name() { return name; }
        public Kind kind() { return kind; }
        public int stateCount() { return shapeIndex.length; }
        public int propertyCount() { return properties.length; }
        public String property(int i) { return properties[i]; }
        public List<String> values(int i) { return List.of(values[i]); }
        public int shapeIndex(int state) { return shapeIndex[state]; }
        /** Shape index of one of the six extracted entity-context variants (CONTEXT kind only): variant = bottomClass*2 + (descending?1:0), bottomClass 0=above top face, 1=inside, 2=below. */
        public int contextVariant(int state, int variant) { return contextVariants[state * 6 + variant]; }
        public float maxHorizontalOffset() { return maxHorizontalOffset; }
        public float maxVerticalOffset() { return maxVerticalOffset; }
        /** Mixed-radix state index of a canonical key, or -1 when the key's properties do not exactly match this block's property axes. */
        public int stateIndex(BlockStateKey key) {
            var props = key.properties();
            if (props.size() != properties.length) return -1;
            int idx = 0;
            for (int i = 0; i < properties.length; i++) {
                String v = props.get(properties[i]);
                if (v == null) return -1;
                int vi = -1; String[] vs = values[i];
                for (int k = 0; k < vs.length; k++) if (vs[k].equals(v)) { vi = k; break; }
                if (vi < 0) return -1;
                idx = idx * radix[i] + vi;
            }
            return idx;
        }
    }
    /**
     * Exact vanilla voxel grid of one shape: per-axis cell boundary coordinates ({@code n+1} values for {@code n} cells)
     * and the filled-cell bitset in {@code DiscreteVoxelShape} order (index {@code (x*ny+y)*nz+z}). The box list is a
     * merged decomposition of this grid; the grid is what {@code VoxelShape.collide} walks, so a penetrated shape clips
     * to its internal grid lines exactly as vanilla does. Arrays are defensive copies.
     */
    public record Grid(double[] xs, double[] ys, double[] zs, long[] filled) {
        public Grid { Objects.requireNonNull(xs); Objects.requireNonNull(ys); Objects.requireNonNull(zs); Objects.requireNonNull(filled); }
        public int nx() { return xs.length - 1; }
        public int ny() { return ys.length - 1; }
        public int nz() { return zs.length - 1; }
        public boolean isFull(int x, int y, int z) { int i = (x * ny() + y) * nz() + z; return (filled[i >>> 6] & (1L << (i & 63))) != 0; }
    }
    /** One resolved table entry for a canonical state (a value, so callers can cache it per run-local id). */
    public record Entry(Block block, int state, int shapeIndex) {}

    public static final String PAPER_26_2_RESOURCE = "/me/nicolas/papea2ac/version/collision-shapes-paper-26.2-124.tsv";
    private static final class Holder { static final BlockShapeTable TABLE = loadResource(PAPER_26_2_RESOURCE); }
    /** The pinned profile's table; loading failure surfaces as an ExceptionInInitializerError to the composition root (nothing partially initialized is ever published). */
    public static BlockShapeTable paper26_2() { return Holder.TABLE; }

    private final String profile, digest; private final int bytes;
    private final double[][] shapes; private final Grid[] grids; private final boolean[] large;
    private final Map<String, Block> blocks; private final Map<String, Integer> namedShapes; private final Map<String, float[]> poseDimensions;
    private final int stateCount;

    private BlockShapeTable(String profile, String digest, int bytes, double[][] shapes, Grid[] grids, Map<String, Block> blocks, Map<String, Integer> namedShapes, Map<String, float[]> poseDimensions) {
        this.profile = profile; this.digest = digest; this.bytes = bytes; this.shapes = shapes; this.grids = grids; this.blocks = blocks; this.namedShapes = namedShapes; this.poseDimensions = poseDimensions;
        large = new boolean[shapes.length];
        int states = 0;
        for (int i = 0; i < shapes.length; i++) { double[] s = shapes[i]; for (int k = 0; k < s.length; k++) if (s[k] < 0.0 || s[k] > 1.0) { large[i] = true; break; } }
        for (Block b : blocks.values()) states += b.shapeIndex.length;
        stateCount = states;
    }

    public String profile() { return profile; }
    /** SHA-256 of the exact resource bytes: the table identity recorded in evidence/replay metadata. */
    public String digest() { return digest; }
    public int resourceBytes() { return bytes; }
    public int shapeCount() { return shapes.length; }
    public int blockCount() { return blocks.size(); }
    public int stateCount() { return stateCount; }
    /** Flat box list (6 doubles per box, block-local coordinates) for a shape index; index 0 is always the empty shape. Defensive copy. */
    public double[] shape(int index) { return shapes[index].clone(); }
    public int boxCount(int index) { return shapes[index].length / 6; }
    /** Exact voxel grid of a shape index (null for the empty shape 0). Defensive copy. */
    public Grid grid(int index) { Grid g = grids[index]; return g == null ? null : new Grid(g.xs.clone(), g.ys.clone(), g.zs.clone(), g.filled.clone()); }
    /** Vanilla {@code hasLargeCollisionShape}: some coordinate lies outside [0,1], so the shape can reach into a neighbouring cell. */
    public boolean large(int index) { return large[index]; }
    public Block block(String name) { return blocks.get(name); }
    public Collection<Block> blocks() { return blocks.values(); }
    public Integer namedShape(String name) { return namedShapes.get(name); }
    /** Avatar bounding-box dimensions {width, height} for a vanilla Pose name, extracted from {@code Avatar.POSES}/{@code STANDING_DIMENSIONS}; null when the pose is unknown to the profile. */
    public float[] poseDimensions(String pose) { float[] d = poseDimensions.get(pose); return d == null ? null : d.clone(); }
    public Set<String> poses() { return poseDimensions.keySet(); }

    /** Null = UNSUPPORTED (unknown block, or a property set that is not one of this block's registry states). */
    public Entry lookup(BlockStateKey key) {
        Block b = blocks.get(key.block());
        if (b == null) return null;
        int state = b.stateIndex(key);
        return state < 0 ? null : new Entry(b, state, b.shapeIndex[state]);
    }

    static BlockShapeTable loadResource(String resource) {
        try (InputStream in = BlockShapeTable.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("collision shape table resource missing: " + resource);
            return parse(in.readAllBytes());
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    /** Strict parser: any malformed line fails the whole load (no partially usable table is ever returned). */
    public static BlockShapeTable parse(byte[] bytes) {
        String digest;
        try { digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception e) { throw new IllegalStateException(e); }
        String profile = null; List<double[]> shapes = new ArrayList<>(); List<Grid> grids = new ArrayList<>(); Map<String, Block> blocks = new LinkedHashMap<>(); Map<String, Integer> named = new LinkedHashMap<>(); Map<String, float[]> poses = new LinkedHashMap<>();
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n");
        String pendingName = null; Kind pendingKind = null; String[] pendingProps = null; String[][] pendingValues = null; int pendingStates = 0; int[] pendingVariants = null; float pendingMaxH = 0, pendingMaxV = 0;
        for (String raw : lines) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) { if (line.startsWith("# profile ")) profile = line.substring(10).trim(); continue; }
            String[] t = line.split(" ");
            switch (t[0]) {
                case "S" -> {
                    int idx = Integer.parseInt(t[1]);
                    if (idx != shapes.size()) throw new IllegalStateException("shape index order at " + idx);
                    if ((t.length - 2) % 6 != 0) throw new IllegalStateException("shape arity at " + idx);
                    double[] s = new double[t.length - 2];
                    for (int i = 0; i < s.length; i++) { s[i] = Double.parseDouble(t[i + 2]); if (!Double.isFinite(s[i])) throw new IllegalStateException("non-finite shape " + idx); }
                    for (int b = 0; b < s.length; b += 6) if (s[b] > s[b + 3] || s[b + 1] > s[b + 4] || s[b + 2] > s[b + 5]) throw new IllegalStateException("inverted box in shape " + idx);
                    shapes.add(s);
                }
                case "G" -> {
                    int idx = Integer.parseInt(t[1]);
                    if (idx != shapes.size() - 1 || grids.size() != idx) throw new IllegalStateException("grid order at " + idx);
                    int nx = Integer.parseInt(t[2]), ny = Integer.parseInt(t[3]), nz = Integer.parseInt(t[4]);
                    if (nx == 0 && ny == 0 && nz == 0) { if (shapes.get(idx).length != 0) throw new IllegalStateException("gridless non-empty shape " + idx); grids.add(null); break; }
                    if (nx < 1 || ny < 1 || nz < 1 || nx > 64 || ny > 64 || nz > 64) throw new IllegalStateException("grid size at " + idx);
                    int cells = nx * ny * nz; int at = 5;
                    double[] xs = new double[nx + 1], ys = new double[ny + 1], zs = new double[nz + 1];
                    for (int i = 0; i <= nx; i++) xs[i] = Double.parseDouble(t[at++]); for (int i = 0; i <= ny; i++) ys[i] = Double.parseDouble(t[at++]); for (int i = 0; i <= nz; i++) zs[i] = Double.parseDouble(t[at++]);
                    for (double[] c : new double[][] { xs, ys, zs }) for (int i = 0; i < c.length; i++) if (!Double.isFinite(c[i]) || (i > 0 && c[i] <= c[i - 1])) throw new IllegalStateException("grid coordinates at " + idx);
                    String hex = t[at++]; if (at != t.length || hex.length() != (cells + 3) / 4) throw new IllegalStateException("grid bits at " + idx);
                    long[] filled = new long[(cells + 63) / 64]; int any = 0;
                    for (int i = 0; i < cells; i++) { int nib = Character.digit(hex.charAt(i / 4), 16); if (nib < 0) throw new IllegalStateException("grid hex at " + idx); if ((nib >> (3 - (i & 3)) & 1) != 0) { filled[i >>> 6] |= 1L << (i & 63); any++; } }
                    if (any == 0) throw new IllegalStateException("empty grid for non-empty shape " + idx);
                    grids.add(new Grid(xs, ys, zs, filled));
                }
                case "B" -> {
                    if (pendingName != null) throw new IllegalStateException("block without indices: " + pendingName);
                    pendingName = t[1]; pendingStates = Integer.parseInt(t[2]); pendingVariants = null; pendingMaxH = 0; pendingMaxV = 0;
                    int i = 3; String cls = t[i++];
                    if (cls.equals("STATIC")) pendingKind = Kind.STATIC;
                    else if (cls.equals("DYNAMIC")) {
                        String detail = t[i++];
                        if (detail.contains("OFFSET_XZ")) { pendingKind = Kind.OFFSET_XZ; pendingMaxH = Float.parseFloat(detail.substring(detail.indexOf("maxH=") + 5, detail.indexOf(','))); pendingMaxV = Float.parseFloat(detail.substring(detail.indexOf("maxV=") + 5, detail.indexOf(')'))); }
                        else if (detail.equals("CONTEXT")) pendingKind = Kind.CONTEXT;
                        else if (detail.equals("FLAG_ONLY")) pendingKind = Kind.ENTITY_CONTEXT;
                        else if (detail.equals("LEVEL")) pendingKind = Kind.BLOCK_ENTITY;
                        else throw new IllegalStateException("unknown dynamic kind " + detail + " for " + pendingName);
                    } else throw new IllegalStateException("unknown classification " + cls);
                    List<String> props = new ArrayList<>(); List<String[]> values = new ArrayList<>();
                    for (; i < t.length; i++) { int eq = t[i].indexOf('='); if (eq <= 0) throw new IllegalStateException("property axis " + t[i]); props.add(t[i].substring(0, eq)); values.add(t[i].substring(eq + 1).split(",")); }
                    pendingProps = props.toArray(String[]::new); pendingValues = values.toArray(String[][]::new);
                    long product = 1; for (String[] v : pendingValues) product *= v.length;
                    if (product != pendingStates) throw new IllegalStateException("state count mismatch for " + pendingName);
                }
                case "C" -> {
                    if (pendingName == null || pendingKind != Kind.CONTEXT) throw new IllegalStateException("context variants outside a CONTEXT block");
                    pendingVariants = new int[pendingStates * 6];
                    if (t.length - 1 != pendingVariants.length) throw new IllegalStateException("context variant count for " + pendingName);
                    for (int i = 0; i < pendingVariants.length; i++) { pendingVariants[i] = Integer.parseInt(t[i + 1]); if (pendingVariants[i] < 0 || pendingVariants[i] >= shapes.size()) throw new IllegalStateException("variant shape index for " + pendingName); }
                }
                case "I" -> {
                    if (pendingName == null) throw new IllegalStateException("indices without block");
                    int[] idx = new int[pendingStates]; int n = 0;
                    for (int i = 1; i < t.length; i++) {
                        int star = t[i].indexOf('*'); int v = Integer.parseInt(star < 0 ? t[i] : t[i].substring(0, star)); int count = star < 0 ? 1 : Integer.parseInt(t[i].substring(star + 1));
                        if (v < 0 || v >= shapes.size() || count < 1 || n + count > pendingStates) throw new IllegalStateException("shape indices for " + pendingName);
                        for (int k = 0; k < count; k++) idx[n++] = v;
                    }
                    if (n != pendingStates) throw new IllegalStateException("shape index count for " + pendingName);
                    if (pendingKind == Kind.CONTEXT && pendingVariants == null) throw new IllegalStateException("CONTEXT block without variants: " + pendingName);
                    if (blocks.put(pendingName, new Block(pendingName, pendingKind, pendingProps, pendingValues, idx, pendingVariants, pendingMaxH, pendingMaxV)) != null) throw new IllegalStateException("duplicate block " + pendingName);
                    pendingName = null;
                }
                case "X" -> { int v = Integer.parseInt(t[2]); if (v < 0 || v >= shapes.size()) throw new IllegalStateException("named shape " + t[1]); named.put(t[1], v); }
                case "P" -> { float w = Float.parseFloat(t[2]), h = Float.parseFloat(t[3]); if (!(w > 0 && h > 0)) throw new IllegalStateException("pose " + t[1]); poses.put(t[1], new float[] { w, h }); }
                default -> throw new IllegalStateException("unknown record " + t[0]);
            }
        }
        if (pendingName != null) throw new IllegalStateException("block without indices: " + pendingName);
        if (profile == null || shapes.isEmpty() || shapes.get(0).length != 0 || blocks.isEmpty() || grids.size() != shapes.size()) throw new IllegalStateException("incomplete table");
        if (!named.containsKey("full_cube") || !named.containsKey("powder_snow_falling") || !poses.containsKey("STANDING")) throw new IllegalStateException("table lacks required named entries");
        return new BlockShapeTable(profile, digest, bytes.length, shapes.toArray(double[][]::new), grids.toArray(Grid[]::new), Collections.unmodifiableMap(blocks), Collections.unmodifiableMap(named), Collections.unmodifiableMap(poses));
    }
}
