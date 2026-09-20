package me.nicolas.papea2ac.collision;
/**
 * The position-independent entity context a few vanilla shapes depend on (COLLISION_ENGINE.md C1: "Context includes
 * pose, equipment/abilities, fluid, neighbors and temporal revision"). Phase 4 carries exactly the fields the pinned
 * profile's context-dependent blocks read (scaffolding: entity bottom Y and descending; powder snow: fall distance,
 * leather boots / walkable tag, bottom Y, descending). {@link #UNKNOWN} means "no entity context is claimed": a
 * context-dependent cell then resolves to its conservative envelope (lower bound empty, upper bound the union of
 * its possible shapes) with certainty CONTEXT_REQUIRED - never a guessed variant.
 *
 * Which context applies to a given player step is a Phase 5 decision (it depends on simulated state); Phase 4 only
 * exposes the contract and evaluates the vanilla predicates exactly.
 */
public record ShapeContext(boolean known, double entityBottomY, boolean descending, double fallDistance, boolean canWalkOnPowderSnow) {
    public ShapeContext {
        if (known && (!Double.isFinite(entityBottomY) || !Double.isFinite(fallDistance))) throw new IllegalArgumentException("non-finite context");
    }
    public static final ShapeContext UNKNOWN = new ShapeContext(false, 0, false, 0, false);
    public static ShapeContext of(double entityBottomY, boolean descending, double fallDistance, boolean canWalkOnPowderSnow) { return new ShapeContext(true, entityBottomY, descending, fallDistance, canWalkOnPowderSnow); }
    /** Vanilla {@code CollisionContext.of(entity)} defaults for a standing/walking body: bottom = box minY, not descending, no fall, no boots. */
    public static ShapeContext body(double entityBottomY) { return of(entityBottomY, false, 0, false); }
}
