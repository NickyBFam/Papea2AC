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
    /** Vanilla {@code Entity.getInputVector(input, speed, yaw)} over the profile's sine table. */
    public static Vec3d inputVector(MovementProfile profile, double x, double y, double z, float speed, float yaw) { return new PhysicsSimulator(profile).inputVector(x, y, z, speed, yaw); }
    /** Vanilla {@code BlockGetter.forEachBlockIntersectedBetween(from, to, box, visitor)}: the cells (with step numbers) the inside-block sampler visits, in order. */
    public static void traverseBlocks(Vec3d from, Vec3d to, Aabb box, InsideBlockSampler.Visitor visitor) { InsideBlockSampler.forEachBlockIntersectedBetween(from, to, box, visitor); }
    /** Vanilla static {@code AABB.clip(min.., max.., from, to)}: the entry point or null. */
    public static Vec3d clipBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, Vec3d from, Vec3d to) { return InsideBlockSampler.clip(minX, minY, minZ, maxX, maxY, maxZ, from, to); }
}
