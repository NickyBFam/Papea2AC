package me.nicolas.papea2ac.model;
/** Immutable observed yaw/pitch. Raw yaw may wrap and is stored as observed, never forcibly clamped here:
 * version-profile-defined angle limits are a later phase's concern, not this value type's. */
public record Rotation(float yaw, float pitch) {
    public Rotation {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) throw new IllegalArgumentException("non-finite rotation");
    }
    /** Yaw folded into [0,360) for comparison/display; does not replace the stored raw value. */
    public float normalizedYaw() {
        float wrapped = yaw % 360f;
        return wrapped < 0 ? wrapped + 360f : wrapped;
    }
}
