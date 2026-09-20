package me.nicolas.papea2ac.collision;
import me.nicolas.papea2ac.model.Aabb;
import me.nicolas.papea2ac.version.CollisionProfile;
import java.util.Objects;
/**
 * Player bounding-box CONTRACT (task §4): the geometry input Phase 5 will pass to {@link CollisionEngine}. The
 * dimensions per pose come from the version profile's extracted {@code Avatar.POSES} table (0.6x1.8 standing,
 * 0.6x1.5 crouching, 0.6x0.6 swimming / fall-flying / spin-attack, 0.2x0.2 sleeping / dying) and the box is built
 * exactly like vanilla {@code EntityDimensions.makeBoundingBox}: {@code float half = width / 2f} and {@code float h
 * = height}, then double arithmetic around the feet position. The scale attribute is applied as vanilla
 * {@code EntityDimensions.scale} does (float multiply) before the box is built.
 *
 * Nothing here selects a pose: which pose a player is in at a given sequence depends on simulated/observed state that
 * Phase 5 owns. A caller must state the pose explicitly - there is no "default standing" entry point.
 */
public final class PlayerBox {
    public enum Pose { STANDING, CROUCHING, SWIMMING, FALL_FLYING, SPIN_ATTACK, SLEEPING, DYING }
    private PlayerBox() {}

    /** {width, height} for a pose from the profile table (as floats, as vanilla stores them). */
    public static float[] dimensions(CollisionProfile profile, Pose pose) {
        float[] d = profile.table().poseDimensions(Objects.requireNonNull(pose).name());
        if (d == null) throw new IllegalStateException("profile has no dimensions for pose " + pose);
        return d;
    }
    /** Vanilla {@code EntityDimensions.makeBoundingBox(x, y, z)} for the pose at unit scale. */
    public static Aabb of(CollisionProfile profile, Pose pose, double x, double y, double z) { return of(profile, pose, 1.0f, x, y, z); }
    /** With the vanilla scale attribute: {@code EntityDimensions.scale(f)} multiplies width and height as floats first. */
    public static Aabb of(CollisionProfile profile, Pose pose, float scale, double x, double y, double z) {
        if (!(scale > 0) || !Float.isFinite(scale)) throw new IllegalArgumentException("scale");
        float[] d = dimensions(profile, pose);
        float width = d[0] * scale, height = d[1] * scale;
        float half = width / 2.0F;
        return new Aabb(x - (double) half, y, z - (double) half, x + (double) half, y + (double) height, z + (double) half);
    }
}
