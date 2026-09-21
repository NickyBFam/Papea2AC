package me.nicolas.papea2ac.physics;
import me.nicolas.papea2ac.model.Aabb;
import me.nicolas.papea2ac.version.MovementProfile;

/**
 * Public entry points to the step's arithmetic kernels for the native differential lane and the benchmarks
 * (TESTING.md: kernels are qualified against the pinned jar independently of whole-tick runs). Each is exactly the
 * routine the simulator itself calls; nothing here is a second implementation.
 */
public final class PhysicsKernels {
    private PhysicsKernels() {}
    /**
     * The local client's key vector as {@code travel} receives it - {@code {xxa (left), zza (forward)}} - in the client's
     * float arithmetic (P6-F1, see {@link MovementProfile#VEC2_NORMALIZE_EPSILON}): {@code Vec2(strafe, forward)} from the
     * {@code KEY_IMPULSE} key impulses, {@code normalized()}, {@code scale(0.98F)}, {@code scale(0.2F)} while using an item,
     * {@code scale(sneakingSpeed)} while moving slowly, then the square-movement rescale. Straight keys reduce to the
     * per-axis impulse ({@code key * factors}); a diagonal is {@code 0.7071067F} per component. The simulator and the Phase 6
     * abstract lane both call this (one implementation).
     */
    public static float[] clientMoveVector(int forward, int strafe, boolean movingSlowly, boolean usingItem, float sneakingSpeed) {
        float x = strafe * MovementProfile.KEY_IMPULSE, y = forward * MovementProfile.KEY_IMPULSE;
        // Vec2.normalized(): Mth.sqrt(x*x + y*y) in float, ZERO below the epsilon, per-component division
        float len = (float) Math.sqrt((double) (x * x + y * y));
        if (len < MovementProfile.VEC2_NORMALIZE_EPSILON) return new float[] { 0f, 0f };
        x = x / len; y = y / len;
        // LocalPlayer input scaling: 0.98F, item use, sneaking - each a Vec2.scale (per-component float multiply)
        x *= MovementProfile.INPUT_FRICTION; y *= MovementProfile.INPUT_FRICTION;
        if (usingItem) { x *= MovementProfile.USE_ITEM_INPUT_FACTOR; y *= MovementProfile.USE_ITEM_INPUT_FACTOR; }
        if (movingSlowly) { x *= sneakingSpeed; y *= sneakingSpeed; }
        // modifyInputSpeedForSquareMovement: unit direction u = v.scale(1/|v|), length min(|v| * distanceToUnitSquare(u), 1)
        float f = (float) Math.sqrt((double) (x * x + y * y));
        if (f <= 0.0F) return new float[] { x, y };
        float inv = 1.0F / f, ux = x * inv, uy = y * inv;
        float ax = Math.abs(ux), ay = Math.abs(uy), h = ay > ax ? ax / ay : ay / ax;
        float g = (float) Math.sqrt((double) (1.0F + h * h));
        float m = Math.min(f * g, MovementProfile.SQUARE_MOVEMENT_CAP);
        return new float[] { ux * m, uy * m };
    }
    /** Vanilla {@code Entity.getInputVector(input, speed, yaw)} over the profile's sine table. */
    public static Vec3d inputVector(MovementProfile profile, double x, double y, double z, float speed, float yaw) { return new PhysicsSimulator(profile).inputVector(x, y, z, speed, yaw); }
    /** Vanilla {@code BlockGetter.forEachBlockIntersectedBetween(from, to, box, visitor)}: the cells (with step numbers) the inside-block sampler visits, in order. */
    public static void traverseBlocks(Vec3d from, Vec3d to, Aabb box, InsideBlockSampler.Visitor visitor) { InsideBlockSampler.forEachBlockIntersectedBetween(from, to, box, visitor); }
    /** Vanilla static {@code AABB.clip(min.., max.., from, to)}: the entry point or null. */
    public static Vec3d clipBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, Vec3d from, Vec3d to) { return InsideBlockSampler.clip(minX, minY, minZ, maxX, maxY, maxZ, from, to); }
}
