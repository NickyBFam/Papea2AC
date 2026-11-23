package me.nicolas.papea2ac.combat.geometry;

import java.util.Objects;

/**
 * The vanilla 26.2 client's classic attack pick for ONE concrete scene and ONE candidate target, composed exactly as
 * {@code LocalPlayer.pick(Entity, double, double, float)} (bc 0–186) and {@code ProjectileUtil.getEntityHitResult} (bc 0–228) do it, with
 * {@link PickMath} primitives (bit-exact against the client jar: NativeReachGeometryTest).
 *
 * <p>Scope of one call: the target alone. Other entities that might lie nearer on the ray (the client picks the nearest) are NOT evaluated
 * here (PHASE_12 R8); the caller records that limitation on any witness. The block hit along the ray is an input ({@link BlockClip}), computed by
 * the caller from outline shapes, or known to be absent, or unknown.
 */
public final class ScenePick {
    private ScenePick() {}

    /** The client's block raycast along {@code eye -> eye + view * max(blockRange, entityRange)} (ClipContext OUTLINE, fluids ignored). */
    public record BlockClip(Kind kind, double hitX, double hitY, double hitZ) {
        public enum Kind {
            /** No outline crossed up to the full distance: the client's result is a MISS located at the ray end. */
            MISS,
            /** The first outline hit, at exactly this location (as {@code BlockHitResult.getLocation()}). */
            HIT,
            /** The world along the ray is not known well enough to say (unobserved cell, unmodelled outline). */
            UNKNOWN
        }
        public BlockClip { Objects.requireNonNull(kind); }
        public static final BlockClip MISS = new BlockClip(Kind.MISS, Double.NaN, Double.NaN, Double.NaN);
        public static final BlockClip UNKNOWN = new BlockClip(Kind.UNKNOWN, Double.NaN, Double.NaN, Double.NaN);
        public static BlockClip hit(double x, double y, double z) { return new BlockClip(Kind.HIT, x, y, z); }
    }

    /** What the client's pick does with the target in this scene. */
    public enum Outcome {
        /** The pick returns the target: an ATTACK on it is what the vanilla client sends in this scene. */
        HIT,
        /** The ray never meets the (pick-inflated) target box. */
        MISS_ANGLE,
        /** The ray meets the box, but at a distance the pick refuses ({@code >= range}) or beyond the searched segment. */
        MISS_REACH,
        /** The ray meets the box within range, but a block outline is hit first (or at the same distance). */
        MISS_OCCLUDED,
        /** The block raycast is unknown, so the concrete pick cannot be decided. */
        UNDECIDED_BLOCKS
    }

    /**
     * @param hitDistSqr squared eye distance of the entity clip point ({@code NaN} when there is none)
     * @param fromInside the eye is inside the target box (picked at distance 0)
     */
    public record Result(Outcome outcome, double hitDistSqr, double hitX, double hitY, double hitZ, boolean fromInside, double searchDistance, double blockDistSqr) {
        public Result { Objects.requireNonNull(outcome); }
        public boolean hit() { return outcome == Outcome.HIT; }
    }

    /**
     * One concrete classic pick.
     *
     * @param eye {@code Entity.getEyePosition(1.0F)} ({@link PickMath#eyePosition})
     * @param view {@code getViewVector(1.0F)} ({@link PickMath#viewVector})
     * @param entityRange {@code entity_interaction_range} as the client reads it (double)
     * @param blockRange {@code block_interaction_range} (double)
     * @param target the target's bounding box at its client position, already inflated by its pick radius (0 for every pickable living type)
     * @param pickableFromInside {@code canBePickedFromInside()} of the target type
     * @param block the block raycast along the full segment
     */
    public static Result pick(double[] eye, double[] view, double entityRange, double blockRange, PickMath.Box target, boolean pickableFromInside, BlockClip block) {
        double d = Math.max(blockRange, entityRange);           // bc 0–5
        double maxDistSqr = d * d;                              // Mth.square (bc 9)
        if (block.kind() == BlockClip.Kind.UNKNOWN) return undecided(eye, view, target, d);
        double bx, by, bz;
        if (block.kind() == BlockClip.Kind.HIT) { bx = block.hitX(); by = block.hitY(); bz = block.hitZ(); }
        else { double[] end = PickMath.rayEnd(eye, view, d); bx = end[0]; by = end[1]; bz = end[2]; } // a MISS is located at the clip end
        double blockDistSqr = PickMath.distanceToSqr(bx, by, bz, eye[0], eye[1], eye[2]); // hit.getLocation().distanceToSqr(eye) (bc 33–43)
        if (block.kind() == BlockClip.Kind.HIT) { maxDistSqr = blockDistSqr; d = Math.sqrt(maxDistSqr); } // bc 45–65
        double[] end = PickMath.rayEnd(eye, view, d);           // bc 75–104
        // ProjectileUtil.getEntityHitResult for the target alone (bc 54–205)
        double best = maxDistSqr; boolean found = false; double hx = Double.NaN, hy = Double.NaN, hz = Double.NaN; boolean inside = false;
        double t = PickMath.clip(target, eye[0], eye[1], eye[2], end[0], end[1], end[2]);
        if (target.contains(eye[0], eye[1], eye[2])) {
            if (best >= 0.0 && pickableFromInside) {
                found = true; inside = true;
                if (Double.isNaN(t)) { hx = eye[0]; hy = eye[1]; hz = eye[2]; }   // clip.orElse(from)
                else { double[] l = PickMath.clipLocation(t, eye[0], eye[1], eye[2], end[0], end[1], end[2]); hx = l[0]; hy = l[1]; hz = l[2]; }
                best = 0.0;
            }
        } else if (!Double.isNaN(t)) {
            double[] l = PickMath.clipLocation(t, eye[0], eye[1], eye[2], end[0], end[1], end[2]);
            double dd = PickMath.distanceToSqr(eye[0], eye[1], eye[2], l[0], l[1], l[2]); // from.distanceToSqr(location) (bc 142–148)
            if (dd < best || best == 0.0) { found = true; hx = l[0]; hy = l[1]; hz = l[2]; best = dd; }
        }
        if (!found) {
            // Classification only: where would the continued ray meet the box? Beyond a block hit within range = occluded; beyond range = reach.
            double entrySqr = rayEntryDistSqr(eye, view, target, Math.max(blockRange, entityRange));
            Outcome o = Double.isNaN(entrySqr) ? Outcome.MISS_ANGLE
                : block.kind() == BlockClip.Kind.HIT && blockDistSqr <= entrySqr && entrySqr < entityRange * entityRange ? Outcome.MISS_OCCLUDED : Outcome.MISS_REACH;
            return new Result(o, entrySqr, Double.NaN, Double.NaN, Double.NaN, false, d, blockDistSqr);
        }
        double hitDistSqr = PickMath.distanceToSqr(hx, hy, hz, eye[0], eye[1], eye[2]); // entityHit.getLocation().distanceToSqr(eye) (bc 153–163)
        if (!(hitDistSqr < blockDistSqr)) return new Result(Outcome.MISS_OCCLUDED, hitDistSqr, hx, hy, hz, inside, d, blockDistSqr);
        // filterHitResult(entityHit, eye, entityRange): location.closerThan(eye, entityRange) = location.distanceToSqr(eye) < range * range
        if (!(hitDistSqr < entityRange * entityRange)) return new Result(Outcome.MISS_REACH, hitDistSqr, hx, hy, hz, inside, d, blockDistSqr);
        return new Result(Outcome.HIT, hitDistSqr, hx, hy, hz, inside, d, blockDistSqr);
    }

    /** Without the block raycast the pick is undecided; the entity geometry is still reported (diagnostic only). */
    private static Result undecided(double[] eye, double[] view, PickMath.Box target, double d) {
        double[] end = PickMath.rayEnd(eye, view, d);
        double t = PickMath.clip(target, eye[0], eye[1], eye[2], end[0], end[1], end[2]);
        if (Double.isNaN(t)) return new Result(Outcome.UNDECIDED_BLOCKS, Double.NaN, Double.NaN, Double.NaN, Double.NaN, target.contains(eye[0], eye[1], eye[2]), d, Double.NaN);
        double[] l = PickMath.clipLocation(t, eye[0], eye[1], eye[2], end[0], end[1], end[2]);
        return new Result(Outcome.UNDECIDED_BLOCKS, PickMath.distanceToSqr(l[0], l[1], l[2], eye[0], eye[1], eye[2]), l[0], l[1], l[2], false, d, Double.NaN);
    }

    /**
     * Classification only (never a decision input): the squared distance at which the ray, continued well past the searched segment, enters the
     * box (NaN when it never does). Labels a failed pick ANGLE (beside the ray), OCCLUDED (behind a block, within range) or REACH (too far).
     */
    static double rayEntryDistSqr(double[] eye, double[] view, PickMath.Box box, double d) {
        double span = Math.max(box.maxX() - box.minX(), Math.max(box.maxY() - box.minY(), box.maxZ() - box.minZ()));
        double reach = d + Math.sqrt(box.distanceToSqr(eye[0], eye[1], eye[2])) + 2 * span + 64;
        double[] far = PickMath.rayEnd(eye, view, reach);
        double t = PickMath.clip(box, eye[0], eye[1], eye[2], far[0], far[1], far[2]);
        if (Double.isNaN(t)) return Double.NaN;
        double[] l = PickMath.clipLocation(t, eye[0], eye[1], eye[2], far[0], far[1], far[2]);
        return PickMath.distanceToSqr(l[0], l[1], l[2], eye[0], eye[1], eye[2]);
    }
}
