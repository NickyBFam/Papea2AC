package me.nicolas.papea2ac.testing;
import me.nicolas.papea2ac.physics.PhysicsResult;
import me.nicolas.papea2ac.physics.PhysicsState;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** SHA-256 over the raw bits of a trajectory (x,y,z,vx,vy,vz,fallDistance,stuck xyz as little-endian doubles + one flag byte per tick): bit-level identity of a replay, never a rounded print. */
final class TrajectoryDigest {
    private TrajectoryDigest() {}
    static String of(List<PhysicsState> states) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ByteBuffer b = ByteBuffer.allocate(10 * 8 + 1).order(ByteOrder.LITTLE_ENDIAN);
            for (PhysicsState s : states) {
                b.clear();
                b.putDouble(s.position().x()).putDouble(s.position().y()).putDouble(s.position().z()).putDouble(s.velocity().x()).putDouble(s.velocity().y()).putDouble(s.velocity().z());
                b.putDouble(s.fallDistance()).putDouble(s.stuck().x()).putDouble(s.stuck().y()).putDouble(s.stuck().z());
                b.put((byte) ((s.onGround() ? 1 : 0) | (s.horizontalCollision() ? 2 : 0) | (s.verticalCollision() ? 4 : 0) | (s.verticalCollisionBelow() ? 8 : 0) | (s.inPowderSnow() ? 16 : 0) | (s.pose().ordinal() << 5)));
                md.update(b.array(), 0, b.position());
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String ofResults(List<PhysicsResult> results) {
        List<PhysicsState> states = new java.util.ArrayList<>(results.size());
        for (PhysicsResult r : results) { if (r.next() == null) break; states.add(r.next()); }
        return of(states);
    }
}
