package me.nicolas.papea2ac.checks.rotation;
import java.util.Objects;
/**
 * Phase 15: the native client's mouse-to-rotation arithmetic, reproduced operation for operation.
 *
 * <p>The values come from the version-owned {@code RotationProfile} (checks may not depend on {@code version}; core copies the profile into
 * this record at composition, and a test asserts the copy is exact). Every method mirrors the client's types and evaluation order, so the
 * float results are bit-identical to the client's; nothing here is a tolerance. {@link #sensitivityForUnit} is the one diagnostic inverse
 * and is not used to decide anything.
 */
public record RotationKinematics(String profileId, double sensitivityScale, double sensitivityOffset, double unscopedMultiplier,
                                 float perCountScale, float pitchMin, float pitchMax, double sensitivityMin, double sensitivityMax) {
    public RotationKinematics {
        Objects.requireNonNull(profileId);
        if (!(sensitivityScale > 0) || !(unscopedMultiplier > 0) || !(perCountScale > 0) || !(pitchMin < pitchMax) || !(sensitivityMin < sensitivityMax))
            throw new IllegalArgumentException("rotation kinematics");
    }

    /** {@code MouseHandler.turnPlayer}: f = s*scale + offset; k = f*f*f; times the unscoped multiplier unless first-person scoping. */
    public double multiplier(double sensitivity, boolean scoping) {
        double f = sensitivity * sensitivityScale + sensitivityOffset;
        double cube = f * f * f;
        return scoping ? cube : cube * unscopedMultiplier;
    }

    /** {@code Entity.turn}: one frame's angle step for accumulated cursor counts, {@code (float) (counts * k) * 0.15F}. */
    public float frameStep(double counts, double multiplier) {
        return (float) (counts * multiplier) * perCountScale;
    }

    /** The step of a single cursor count: the quantization unit of every legitimate raw-input rotation at that sensitivity. */
    public float unit(double sensitivity, boolean scoping) {
        return frameStep(1.0d, multiplier(sensitivity, scoping));
    }

    /** Smallest unit the profile's sensitivity range can produce (minimum sensitivity, scoping). */
    public float minUnit() { return unit(sensitivityMin, true); }

    /** Largest unit the profile's sensitivity range can produce (maximum sensitivity, not scoping). */
    public float maxUnit() { return unit(sensitivityMax, false); }

    /** {@code Entity.setYRot(getYRot() + step)}: yaw is never wrapped by the client. */
    public float turnYaw(float yaw, float step) {
        return yaw + step;
    }

    /** {@code Entity.turn} pitch path: {@code setXRot(x + step)} ({@code % 360} then clamp), then {@code setXRot(clamp(x))}. */
    public float turnPitch(float pitch, float step) {
        float once = clamp((pitch + step) % 360.0F);
        return clamp(clamp(once) % 360.0F);
    }

    /** True when a pitch can be produced by the native client (inside the clamp range). */
    public boolean pitchInProfile(float pitch) {
        return pitch >= pitchMin && pitch <= pitchMax;
    }

    /** Diagnostic only: the sensitivity whose unit is {@code unit}. Uses StrictMath.cbrt (NUMERICAL_POLICY: deterministic diagnostics). */
    public double sensitivityForUnit(double unit, boolean scoping) {
        double k = unit / perCountScale;
        double cube = scoping ? k : k / unscopedMultiplier;
        return (StrictMath.cbrt(cube) - sensitivityOffset) / sensitivityScale;
    }

    private float clamp(float value) {
        return Math.clamp(value, pitchMin, pitchMax);
    }
}
