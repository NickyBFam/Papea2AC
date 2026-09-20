package me.nicolas.papea2ac.collision;
import me.nicolas.papea2ac.model.BlockStateKey;
import me.nicolas.papea2ac.version.BlockShapeTable;
import me.nicolas.papea2ac.version.CollisionProfile;
import me.nicolas.papea2ac.world.BlockStateDictionary;
import java.util.concurrent.atomic.AtomicReferenceArray;
/**
 * Deterministic shape resolution: normalized observed block state (+ the position and entity context Phase 4 permits)
 * -> {@link ResolvedShape}. Pure function of the pinned {@link CollisionProfile}; never inspects a live world and never
 * calls a platform API (task §5). Version-specific geometry comes from the profile's {@link BlockShapeTable}; the only
 * rules implemented here are the vanilla predicates for the profile's five non-static families, each named:
 * <ul>
 * <li>OFFSET_XZ (bamboo, pointed dripstone, sulfur spike): resting shape moved by {@code Mth.getSeed}-derived XZ offset - exact;</li>
 * <li>CONTEXT (scaffolding): one of the six extracted variants chosen by {@code isAbove(block)}, {@code isAbove(below block)}, descending - exact with context, envelope without;</li>
 * <li>ENTITY_CONTEXT (powder snow): fall distance / boots / above / descending - exact with context, envelope without;</li>
 * <li>BLOCK_ENTITY (shulker boxes, moving piston): lower = resting shape, upper = resting ∪ the block entity's maximal travel - never exact;</li>
 * <li>anything not in the table: UNSUPPORTED_BLOCK_STATE, lower = upper = EMPTY claim-free (a consumer must treat it as unknown, and the status says so).</li>
 * </ul>
 * Cache (task §12): one immutable {@link Entry} per run-local dictionary id, in a fixed {@code AtomicReferenceArray} of
 * {@link BlockStateDictionary#CAPACITY} slots (256 KiB of references, engine-wide, bounded by the dictionary itself);
 * entries are values, publication is safe, a slot is written at most once per id (identical content on a race). Failure
 * to resolve a key is memoized as UNSUPPORTED - deterministic, so memoizing it is exact, not a poisoned cache.
 * Shape instances are interned per table index, so all states sharing geometry share one {@link CollisionShape}.
 */
public final class BlockShapeResolver {
    /** Immutable per-state resolution plan. */
    public static final class Entry {
        final BlockShapeTable.Kind kind; final CollisionShape resting; final ResolvedShape exact; final CollisionShape[] variants; final ResolvedShape envelope;
        final float maxHorizontalOffset; final int axis, sign; final boolean supported;
        private Entry(BlockShapeTable.Kind kind, CollisionShape resting, ResolvedShape exact, CollisionShape[] variants, ResolvedShape envelope, float maxH, int axis, int sign, boolean supported) {
            this.kind = kind; this.resting = resting; this.exact = exact; this.variants = variants; this.envelope = envelope; this.maxHorizontalOffset = maxH; this.axis = axis; this.sign = sign; this.supported = supported;
        }
        public boolean supported() { return supported; }
        public BlockShapeTable.Kind kind() { return kind; }
        /** The resting/empty-context shape (what the table records); for STATIC entries this is the exact geometry. */
        public CollisionShape resting() { return resting; }
        /** Vanilla {@code hasLargeCollisionShape}: true for every non-static kind (vanilla has no cache for them) and for static shapes leaving the unit cube. */
        public boolean large() { return kind != BlockShapeTable.Kind.STATIC || resting.large(); }
        public boolean movingPiston() { return kind == BlockShapeTable.Kind.BLOCK_ENTITY && resting.isEmpty(); }
        /** Position-independent and context-independent: the exact result can be returned without evaluating anything. */
        public boolean constant() { return exact != null; }
    }
    private static final Entry UNSUPPORTED = new Entry(null, CollisionShape.EMPTY, null, null, new ResolvedShape(CollisionShape.EMPTY, CollisionShape.EMPTY, CellStatus.UNSUPPORTED_BLOCK_STATE), 0, -1, 0, false);

    private final CollisionProfile profile; private final BlockShapeTable table; private final BlockStateDictionary dictionary;
    private final CollisionShape[] shapes; private final CollisionShape powderSnowFalling;
    private final AtomicReferenceArray<Entry> cache = new AtomicReferenceArray<>(BlockStateDictionary.CAPACITY);
    private final CollisionMetrics metrics;

    public BlockShapeResolver(CollisionProfile profile, BlockStateDictionary dictionary, CollisionMetrics metrics) {
        this.profile = profile; this.table = profile.table(); this.dictionary = dictionary; this.metrics = metrics;
        shapes = new CollisionShape[table.shapeCount()];
        for (int i = 0; i < shapes.length; i++) shapes[i] = CollisionShape.of(table.shape(i), table.grid(i));
        powderSnowFalling = shapes[table.namedShape("powder_snow_falling")];
    }
    public CollisionProfile profile() { return profile; }
    /** Interned shape for a table index (shared instance). */
    public CollisionShape shape(int tableIndex) { return shapes[tableIndex]; }

    /** Cached resolution plan for a run-local dictionary id; UNSUPPORTED for an id the dictionary never issued. */
    public Entry entry(int stateId) {
        if (stateId < 0 || stateId >= BlockStateDictionary.CAPACITY) return UNSUPPORTED;
        Entry e = cache.get(stateId);
        if (e != null) { metrics.shapeCacheHits.increment(); return e; }
        metrics.shapeCacheMisses.increment();
        BlockStateKey key = dictionary.key(stateId);
        e = key == null ? UNSUPPORTED : plan(key);
        if (!cache.compareAndSet(stateId, null, e)) e = cache.get(stateId); // a racing thread computed the identical value
        return e;
    }
    /** Uncached resolution plan for a canonical key (tests, diagnostics, fixtures). */
    public Entry plan(BlockStateKey key) {
        BlockShapeTable.Entry t;
        try { t = table.lookup(key); } catch (RuntimeException failure) { t = null; } // a malformed key is UNSUPPORTED, never a crash and never air
        if (t == null) { metrics.unsupportedStates.increment(); return UNSUPPORTED; }
        CollisionShape resting = shapes[t.shapeIndex()];
        switch (t.block().kind()) {
            case STATIC: return new Entry(t.block().kind(), resting, ResolvedShape.exact(resting, resting.isEmpty() ? CellStatus.EXACT_EMPTY : CellStatus.EXACT), null, null, 0, -1, 0, true);
            case OFFSET_XZ: return new Entry(t.block().kind(), resting, null, null, null, t.block().maxHorizontalOffset(), -1, 0, true);
            case CONTEXT: {
                CollisionShape[] v = new CollisionShape[6]; CollisionShape union = CollisionShape.EMPTY; boolean allSame = true;
                for (int i = 0; i < 6; i++) { v[i] = shapes[t.block().contextVariant(t.state(), i)]; if (v[i] != v[0]) allSame = false; }
                for (int i = 0; i < 6; i++) if (v[i] != union && !containsAllBoxes(union, v[i])) union = union.unionWith(v[i]);
                ResolvedShape envelope = new ResolvedShape(allSame ? v[0] : CollisionShape.EMPTY, union, CellStatus.CONTEXT_REQUIRED);
                return new Entry(t.block().kind(), resting, null, v, envelope, 0, -1, 0, true);
            }
            case ENTITY_CONTEXT: return new Entry(t.block().kind(), resting, null, null, new ResolvedShape(CollisionShape.EMPTY, CollisionShape.FULL_CUBE, CellStatus.CONTEXT_REQUIRED), 0, -1, 0, true);
            case BLOCK_ENTITY: {
                String facing = key.properties().get("facing"); int axis = axisOf(facing), sign = signOf(facing);
                if (axis < 0) { metrics.unsupportedStates.increment(); return UNSUPPORTED; }
                CollisionShape upper;
                if (resting.isEmpty()) { // moving piston: the moved block's full shape travels one cell along the facing axis in either sense (extension and retraction both use this state)
                    double[] box = { 0, 0, 0, 1, 1, 1 }; box[axis] = -1; box[axis + 3] = 2; upper = CollisionShape.of(box);
                } else { // shulker box: closed body always present; the lid travels up to SHULKER_LID_EXTENT toward facing
                    double[] box = { 0, 0, 0, 1, 1, 1 }; if (sign > 0) box[axis + 3] = 1 + CollisionProfile.SHULKER_LID_EXTENT; else box[axis] = -CollisionProfile.SHULKER_LID_EXTENT; upper = CollisionShape.of(box);
                }
                return new Entry(t.block().kind(), resting, null, null, new ResolvedShape(resting, upper, CellStatus.DYNAMIC_BLOCK_ENTITY), 0, axis, sign, true);
            }
            default: return UNSUPPORTED;
        }
    }
    private static boolean containsAllBoxes(CollisionShape union, CollisionShape v) {
        outer: for (int i = 0; i < v.boxCount(); i++) {
            for (int k = 0; k < union.boxCount(); k++) if (union.minX(k) == v.minX(i) && union.minY(k) == v.minY(i) && union.minZ(k) == v.minZ(i) && union.maxX(k) == v.maxX(i) && union.maxY(k) == v.maxY(i) && union.maxZ(k) == v.maxZ(i)) continue outer;
            return false;
        }
        return true;
    }
    static int axisOf(String facing) { if (facing == null) return -1; return switch (facing) { case "west", "east" -> 0; case "down", "up" -> 1; case "north", "south" -> 2; default -> -1; }; }
    static int signOf(String facing) { if (facing == null) return 0; return switch (facing) { case "east", "up", "south" -> 1; case "west", "down", "north" -> -1; default -> 0; }; }

    /** Resolve one cell. Allocation-free for constant entries (the common case); offsets allocate one translated shape. */
    public ResolvedShape resolve(Entry e, int x, int y, int z, ShapeContext ctx) {
        if (e.exact != null) return e.exact;
        if (!e.supported) return e.envelope;
        switch (e.kind) {
            case OFFSET_XZ: {
                long seed = seed(x, 0, z); float maxH = e.maxHorizontalOffset;
                double ox = clamp(((double) ((float) (seed & 15L) / 15.0F) - 0.5) * 0.5, -(double) maxH, (double) maxH);
                double oz = clamp(((double) ((float) (seed >> 8 & 15L) / 15.0F) - 0.5) * 0.5, -(double) maxH, (double) maxH);
                return ResolvedShape.exact(e.resting.translated(ox, 0.0, oz), CellStatus.EXACT_OFFSET);
            }
            case CONTEXT: {
                if (!ctx.known()) { metrics.contextRequired.increment(); return e.envelope; }
                // vanilla ScaffoldingBlock.getCollisionShape: isAbove(block) && !descending -> stable; else distance!=0 && bottom && isAbove(belowBlock) -> unstable-bottom; else empty.
                // The six extracted variants are indexed by bottom class (0: above top face, 1: inside, 2: below bottom face) x descending; the predicates below reproduce isAbove exactly.
                boolean aboveTop = ctx.entityBottomY() > (double) y + 1.0 - CollisionProfile.ABOVE_TOLERANCE;
                boolean aboveBottom = ctx.entityBottomY() > (double) y + 0.0 - CollisionProfile.ABOVE_TOLERANCE;
                int variant = (aboveTop ? 0 : aboveBottom ? 2 : 4) + (ctx.descending() ? 1 : 0);
                return ResolvedShape.exact(e.variants[variant], CellStatus.EXACT_CONTEXT);
            }
            case ENTITY_CONTEXT: {
                if (!ctx.known()) { metrics.contextRequired.increment(); return e.envelope; }
                // vanilla PowderSnowBlock.getCollisionShape (entity present, not a placement context): fallDistance > 2.5 -> falling shape;
                // canEntityWalkOnPowderSnow && isAbove(block, false) && !descending -> full cube; else empty. (FallingBlockEntity branch is not a player.)
                if (ctx.fallDistance() > CollisionProfile.POWDER_SNOW_FALL_DISTANCE) return ResolvedShape.exact(powderSnowFalling, CellStatus.EXACT_CONTEXT);
                boolean above = ctx.entityBottomY() > (double) y + 1.0 - CollisionProfile.ABOVE_TOLERANCE;
                return ResolvedShape.exact(ctx.canWalkOnPowderSnow() && above && !ctx.descending() ? CollisionShape.FULL_CUBE : CollisionShape.EMPTY, CellStatus.EXACT_CONTEXT);
            }
            case BLOCK_ENTITY: metrics.dynamicCells.increment(); return e.envelope;
            default: return UNSUPPORTED.envelope;
        }
    }

    /** Vanilla {@code Mth.getSeed(int,int,int)} bit for bit: int multiply (wrapping) widened, long xors, then l*l*42317861 + l*11, >> 16. */
    static long seed(int x, int y, int z) {
        long l = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
        l = l * l * 42317861L + l * 11L;
        return l >> 16;
    }
    /** Vanilla {@code Mth.clamp(double,double,double)}: {@code v < min ? min : Math.min(v, max)}. */
    static double clamp(double v, double min, double max) { return v < min ? min : Math.min(v, max); }
}
