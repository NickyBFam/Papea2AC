package me.nicolas.papea2ac.world;
import me.nicolas.papea2ac.model.BlockStateKey;
import me.nicolas.papea2ac.version.BlockMovementTable;
import me.nicolas.papea2ac.version.BlockShapeTable;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * WORLD_MODEL.md W3: "World owns BlockBehaviorQuery ...; version provides pure definitions, physics applies them,
 * collision owns geometry." Resolves one observed cell of a {@link WorldView} to its movement behaviour: the world's
 * coverage status first (UNKNOWN is never "normal friction or zero flow"), then the version table's pure definitions
 * for the interned state (friction, speed/jump factor, bounce restitution, climbable/fence/wall tags, inside-effect
 * and step-on classification, fluid state). A state the profile does not know is UNSUPPORTED, never defaulted.
 *
 * Per-state resolution is cached by run-local dictionary id (states are interned once per engine; the cache is a
 * lock-free array of immutable values so any thread holding a view may query). Nothing here samples, integrates or
 * judges - the sampling rule (which cells, which order) is the physics step's, as MOVEMENT_SIMULATION.md requires.
 */
public final class BlockBehaviorQuery {
    public enum Status {
        /** The cell is observed and the profile defines its behaviour. */ KNOWN,
        /** The cell is not observed (see {@link Behavior#worldStatus()} for the Phase 3 reason). */ UNKNOWN,
        /** The cell is observed but its state is outside the pinned profile (unknown block or property set). */ UNSUPPORTED
    }
    /** Immutable answer; {@code block}/{@code fluid}/{@code stateIndex} are meaningful only for KNOWN ({@code fluid} may be null = no fluid). */
    public record Behavior(Status status, BlockLookup.Status worldStatus, int stateId, BlockMovementTable.Block block, BlockMovementTable.Fluid fluid, int stateIndex) {
        public Behavior { Objects.requireNonNull(status); Objects.requireNonNull(worldStatus); if ((status == Status.KNOWN) != (block != null)) throw new IllegalArgumentException("block present exactly for KNOWN"); }
        /** True for the synthetic void_air answer above/below the dimension (KNOWN, air, no state id). */
        public boolean outsideDimension() { return worldStatus == BlockLookup.Status.OUTSIDE_DIMENSION; }
        public boolean known() { return status == Status.KNOWN; }
        /** Vanilla {@code BlockState.isAir()} (air, cave_air, void_air). */
        public boolean air() { return block != null && block.air(); }
        public boolean hasFluid() { return fluid != null; }
    }
    private static final Behavior[] BY_WORLD_STATUS = new Behavior[BlockLookup.Status.values().length];
    static { for (BlockLookup.Status s : BlockLookup.Status.values()) if (s != BlockLookup.Status.KNOWN) BY_WORLD_STATUS[s.ordinal()] = new Behavior(Status.UNKNOWN, s, -1, null, null, -1); }

    private final BlockMovementTable table; private final BlockShapeTable shapes; private final BlockStateDictionary dictionary;
    private final AtomicReferenceArray<Behavior> cache = new AtomicReferenceArray<>(BlockStateDictionary.CAPACITY);
    /** Vanilla {@code Level.getBlockState} above/below the build height returns {@code void_air}: an exactly known, effect-free, non-fluid air cell. */
    private final Behavior outside;

    public BlockBehaviorQuery(BlockMovementTable table, BlockShapeTable shapes, BlockStateDictionary dictionary) {
        this.table = Objects.requireNonNull(table); this.shapes = Objects.requireNonNull(shapes); this.dictionary = Objects.requireNonNull(dictionary);
        BlockMovementTable.Block voidAir = table.block("minecraft:void_air");
        if (voidAir == null) throw new IllegalStateException("profile lacks minecraft:void_air");
        outside = new Behavior(Status.KNOWN, BlockLookup.Status.OUTSIDE_DIMENSION, -1, voidAir, null, -1);
    }
    public BlockMovementTable table() { return table; }
    public BlockStateDictionary dictionary() { return dictionary; }

    /** Behaviour of the cell at (x, y, z) in {@code view}; the view's own availability is honoured first (an unavailable view answers UNKNOWN with its reason). */
    public Behavior at(WorldView view, int x, int y, int z) {
        BlockLookup.Status availability = view.availability();
        if (availability != BlockLookup.Status.KNOWN) return BY_WORLD_STATUS[availability.ordinal()];
        DimensionProfile dim = view.dimension();
        int sectionIndex = (y >> 4) - dim.minSectionY();
        if (sectionIndex < 0 || sectionIndex >= dim.sectionCount()) return outside;
        WorldView.SectionLookup s = view.section(x >> 4, z >> 4, sectionIndex);
        if (s.status() != BlockLookup.Status.KNOWN) return BY_WORLD_STATUS[s.status().ordinal()];
        int id = s.section().stateIdAt(((y & 15) << 8) | ((z & 15) << 4) | (x & 15));
        if (id < 0) return BY_WORLD_STATUS[BlockLookup.Status.UNKNOWN_CELL.ordinal()];
        return forState(id);
    }
    /** Behaviour of an interned state id (cached). */
    public Behavior forState(int id) {
        Behavior b = cache.get(id);
        if (b != null) return b;
        BlockStateKey key = dictionary.key(id);
        Behavior r;
        if (key == null) r = new Behavior(Status.UNSUPPORTED, BlockLookup.Status.KNOWN, id, null, null, -1);
        else {
            BlockMovementTable.Block block = table.block(key.block());
            BlockShapeTable.Block shapeBlock = shapes.block(key.block());
            int stateIndex = shapeBlock == null ? -1 : shapeBlock.stateIndex(key);
            if (block == null || stateIndex < 0) r = new Behavior(Status.UNSUPPORTED, BlockLookup.Status.KNOWN, id, null, null, -1);
            else r = new Behavior(Status.KNOWN, BlockLookup.Status.KNOWN, id, block, table.fluid(key.block(), stateIndex), stateIndex);
        }
        cache.compareAndSet(id, null, r);
        return r;
    }
}
