package me.nicolas.papea2ac.version;
import java.util.Objects;
/**
 * Phase 15: the native client's mouse-to-rotation arithmetic, as a version-owned numeric profile (NUMERICAL_POLICY NUM1).
 *
 * <p>Provenance (independently obtained, no third-party anticheat source): {@code javap -c -p} of the unobfuscated vanilla 26.2 client jar,
 * methods {@code MouseHandler.turnPlayer(double)}, {@code MouseHandler.onMove}, {@code Entity.turn(double,double)}, {@code Entity.setXRot(float)},
 * {@code Entity.setYRot(float)}, {@code LocalPlayer.sendPosition()} and {@code Options.sensitivity} ({@code UnitDouble}, default 0.5).
 * Per rendered frame the client computes, in this order and with these types:
 * <pre>
 *   double f = sensitivity * 0.6000000238418579 + 0.20000000298023224;   // the float literals 0.6F/0.2F widened to double
 *   double k = f * f * f;  if (!(firstPerson &amp;&amp; scoping)) k = k * 8.0;  // spyglass scoping drops the 8x factor
 *   (smooth camera replaces dx*k by SmoothDouble output - not quantized)
 *   Entity.turn(dx * k, dy * k):  float d = (float) (dx * k) * 0.15F;  yRot = yRot + d  (never wrapped);
 *                                 xRot = clamp((xRot + (float) (dy * k) * 0.15F) % 360F, -90F, 90F)
 * </pre>
 * {@code dx/dy} are the frame's accumulated cursor deltas (whole counts under raw mouse motion; fractional on some platforms). The tick's
 * movement packet carries the raw float {@code yRot/xRot} and is sent only when {@code yRot - yRotLast} or {@code xRot - xRotLast} (float
 * subtraction) is non-zero, so consecutive received rotations reproduce the client's own per-packet float delta bit for bit.
 * Inversion options negate {@code dx}/{@code dy} before {@code turn}; they do not change the step size.
 */
public record RotationProfile(String profileId, double sensitivityScale, double sensitivityOffset, double unscopedMultiplier,
                              float perCountScale, float pitchMin, float pitchMax, double sensitivityMin, double sensitivityMax,
                              boolean yawWrapped, String provenance) {
    public RotationProfile {
        Objects.requireNonNull(profileId); Objects.requireNonNull(provenance);
        if (!(sensitivityScale > 0) || !(unscopedMultiplier > 0) || !(perCountScale > 0) || !(pitchMin < pitchMax) || !(sensitivityMin < sensitivityMax))
            throw new IllegalArgumentException("rotation profile");
    }
    /** The pinned native profile for the 26.2 client. */
    public static final RotationProfile NATIVE_26_2 = new RotationProfile("native-26.2-rotation-1",
            (double) 0.6F, (double) 0.2F, 8.0d, 0.15F, -90.0F, 90.0F, 0.0d, 1.0d, false,
            "javap 26.2 client: MouseHandler.turnPlayer/onMove, Entity.turn/setXRot/setYRot, LocalPlayer.sendPosition, Options.sensitivity");
}
