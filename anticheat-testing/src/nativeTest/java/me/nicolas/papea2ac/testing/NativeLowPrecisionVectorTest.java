package me.nicolas.papea2ac.testing;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Method;
import java.util.Random;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import me.nicolas.papea2ac.version.LowPrecisionVector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * P7-F8 differential: {@link LowPrecisionVector#quantize} must reproduce, bit for bit, what the pinned jar's {@code LpVec3.write}
 * followed by {@code LpVec3.read} produces - the vector the 26.2 client actually applies from a {@code ClientboundSetEntityMotionPacket}
 * (whose codec is {@code Vec3.LP_STREAM_CODEC}). Random vectors across the codec's scale regimes (sub-minimum, below 1, integer
 * scales 1..3 without continuation, larger scales with the continuation varint, the clamp bound), plus NaN and the packet's own
 * end-to-end encode/decode through the real stream codec.
 */
class NativeLowPrecisionVectorTest {
    static Class<?> vec3C, lpC; static Method write, read; static java.lang.reflect.Constructor<?> vec3;
    @BeforeAll static void bootstrap() throws Exception {
        NativePacketTest.bootstrap();
        vec3C = Class.forName("net.minecraft.world.phys.Vec3"); lpC = Class.forName("net.minecraft.network.LpVec3");
        write = lpC.getMethod("write", ByteBuf.class, vec3C); read = lpC.getMethod("read", ByteBuf.class); vec3 = vec3C.getConstructor(double.class, double.class, double.class);
    }
    static double[] jar(double x, double y, double z) throws Exception {
        ByteBuf buf = Unpooled.buffer();
        try { write.invoke(null, buf, vec3.newInstance(x, y, z)); Object v = read.invoke(null, buf); assertEquals(0, buf.readableBytes(), "codec consumed everything"); return new double[] { (Double) vec3C.getField("x").get(v), (Double) vec3C.getField("y").get(v), (Double) vec3C.getField("z").get(v) }; }
        finally { buf.release(); }
    }
    static void same(double x, double y, double z) throws Exception {
        double[] j = jar(x, y, z), m = LowPrecisionVector.quantize(x, y, z);
        for (int i = 0; i < 3; i++) assertEquals(Double.doubleToRawLongBits(j[i]), Double.doubleToRawLongBits(m[i]), "(" + x + "," + y + "," + z + ")[" + i + "] jar " + j[i] + " model " + m[i]);
    }
    @Test void randomVectorsRoundTripBitForBit() throws Exception {
        Random r = new Random(0x7F8);
        for (int i = 0; i < 200_000; i++) {
            double x, y, z;
            switch (i % 8) {
                case 0 -> { x = r.nextDouble() * 2 - 1; y = r.nextDouble() * 2 - 1; z = r.nextDouble() * 2 - 1; }                       // scale 1
                case 1 -> { x = (r.nextDouble() * 2 - 1) * 4; y = (r.nextDouble() * 2 - 1) * 4; z = (r.nextDouble() * 2 - 1) * 4; }      // scales 1..4 (continuation at 4)
                case 2 -> { x = (r.nextDouble() * 2 - 1) * 1e4; y = (r.nextDouble() * 2 - 1) * 1e4; z = (r.nextDouble() * 2 - 1) * 1e4; }
                case 3 -> { x = (r.nextDouble() * 2 - 1) * 1e-4; y = (r.nextDouble() * 2 - 1) * 1e-4; z = (r.nextDouble() * 2 - 1) * 1e-4; } // around the minimum
                case 4 -> { x = r.nextGaussian() * 0.4; y = r.nextGaussian() * 0.4; z = r.nextGaussian() * 0.4; }                          // typical knockback/velocity
                case 5 -> { x = Math.round(r.nextDouble() * 40 - 20) / 10.0; y = Math.round(r.nextDouble() * 40 - 20) / 10.0; z = Math.round(r.nextDouble() * 40 - 20) / 10.0; } // decimal command values
                case 6 -> { x = (r.nextDouble() * 2 - 1) * 4e10; y = r.nextDouble(); z = 0; }                                             // beyond the clamp
                default -> { x = r.nextInt(5) - 2; y = r.nextInt(5) - 2; z = r.nextInt(5) - 2; }                                            // exact integers (scale == magnitude)
            }
            same(x, y, z);
        }
        same(0, 0, 0); same(0.3, 0.5, 0.2); same(Double.NaN, 1, 1); same(1, Double.NaN, Double.NaN); same(-3.9, 3.9, 0); same(1e-5, -2e-5, 3e-5); same(LowPrecisionVector.ABS_MIN_VALUE, 0, 0);
        same(Double.POSITIVE_INFINITY, 0, 0); same(0, Double.NEGATIVE_INFINITY, 0);
    }
    @Test void theVelocityPacketItselfDecodesToTheModelledVector() throws Exception {
        Class<?> packetC = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket");
        Object codec = packetC.getField("STREAM_CODEC").get(null);
        Method encode = codec.getClass().getMethod("encode", Object.class, Object.class), decode = codec.getClass().getMethod("decode", Object.class);
        encode.setAccessible(true); decode.setAccessible(true);
        ByteBuf buf = Unpooled.buffer();
        try {
            encode.invoke(codec, buf, packetC.getConstructor(int.class, vec3C).newInstance(42, vec3.newInstance(0.3, 0.5, 0.2)));
            Object decoded = decode.invoke(codec, buf);
            Object v = packetC.getMethod("movement").invoke(decoded);
            double[] m = LowPrecisionVector.quantize(0.3, 0.5, 0.2);
            assertEquals(m[0], (Double) vec3C.getField("x").get(v)); assertEquals(m[1], (Double) vec3C.getField("y").get(v)); assertEquals(m[2], (Double) vec3C.getField("z").get(v));
            assertEquals(0.3000061, m[0], 1e-7); assertEquals(0.5000305, m[1], 1e-7); assertEquals(0.2000244, m[2], 1e-7);
        } finally { buf.release(); }
    }
}
