package me.nicolas.papea2ac.version;
/**
 * Bit-exact reproduction of the pinned protocol's low-precision vector codec (the pinned jar's {@code LpVec3} class, Paper 26.2
 * build 124), the wire form of the entity-velocity packet ({@code ClientboundSetEntityMotionPacket} uses {@code Vec3.LP_STREAM_CODEC}).
 * The client applies the DECODED vector, not the server's double vector: 15 data bits per component over an integer scale
 * {@code ceil(max|c|)}, so a server velocity (0.3, 0.5, 0.2) reaches the client as (0.3000061, 0.5000305, 0.2000244) - found live
 * in the Phase 7 real-client qualification (P7-F8) when the client's one-tick displacement missed the modelled vector by exactly
 * those amounts. NativeLowPrecisionVectorTest pins this reproduction against the jar's encoder/decoder over random vectors.
 *
 * <pre>
 * write: x,y,z = sanitize (NaN -> 0, clamp to +-1.7179869183E10); m = max(|x|,|y|,|z|)
 *        m &lt; 3.051944088384301E-5 -> the zero vector
 *        scale = (long) ceil(m); q(c) = round((c / scale * 0.5 + 0.5) * 32766)
 * read:  c = (min(q &amp; 32767, 32766) * 2.0 / 32766 - 1) * scale
 * </pre>
 * Explosion knockback and position corrections use the full-precision codec and are not quantized.
 */
public final class LowPrecisionVector {
    private LowPrecisionVector() {}
    public static final double ABS_MAX_VALUE = 1.7179869183E10, ABS_MIN_VALUE = 3.051944088384301E-5, MAX_QUANTIZED_VALUE = 32766.0;
    private static final int DATA_BITS_MASK = 32767;

    /** @return {x, y, z} as the receiving client decodes them. */
    public static double[] quantize(double x, double y, double z) {
        double sx = sanitize(x), sy = sanitize(y), sz = sanitize(z);
        double m = Math.max(Math.abs(sx), Math.max(Math.abs(sy), Math.abs(sz)));
        if (m < ABS_MIN_VALUE) return new double[] { 0, 0, 0 };
        long scale = (long) Math.ceil(m);
        return new double[] { unpack(pack(sx / scale)) * scale, unpack(pack(sy / scale)) * scale, unpack(pack(sz / scale)) * scale };
    }
    private static double sanitize(double d) { return Double.isNaN(d) ? 0 : Math.clamp(d, -ABS_MAX_VALUE, ABS_MAX_VALUE); }
    static long pack(double d) { return Math.round((d * 0.5 + 0.5) * MAX_QUANTIZED_VALUE); }
    static double unpack(long q) { return Math.min(q & DATA_BITS_MASK, MAX_QUANTIZED_VALUE) * 2.0 / MAX_QUANTIZED_VALUE - 1; }
}
