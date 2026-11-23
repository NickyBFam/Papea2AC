package me.nicolas.papea2ac.testing;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.function.IntUnaryOperator;
import me.nicolas.papea2ac.combat.*;
import me.nicolas.papea2ac.world.*;
import me.nicolas.papea2ac.world.EntityLookup.Status;
import org.junit.jupiter.api.Test;

/**
 * Phase 11 adversarial QA, round 2: metamorphic relations over the FULL entity vocabulary (passengers, metadata with known / unknown / opaque pose,
 * unsupported motion, relative teleports, head rotation, in-place replacement), compared on attack contexts (target status, exactness and target
 * reasons), not only on id lookups. Relations: deleting an observation (a transport loss) never raises certainty; relabelling network ids is
 * invisible; re-sending an idempotent state packet (same data / same passengers) at a new sequence changes no completeness.
 */
class Phase11QaMetamorphicTest {
    /** One generated step: an entity observation, an attack on {@code attackId}, or a silent observation. */
    record Op(EntityObservation obs, int attackId) {}
    static final int IDS = 10;

    static List<Op> generate(long seed) {
        SplittableRandom r = new SplittableRandom(seed); List<Op> ops = new ArrayList<>();
        for (int k = 0; k < 500; k++) {
            int id = 2 + r.nextInt(IDS), op = r.nextInt(100);
            EntityObservation o = null; int attack = -1;
            if (op < 10) o = new EntityObservation.Spawned(id, new UUID(id, k), r.nextBoolean() ? "minecraft:player" : "minecraft:zombie", r.nextInt(-40, 40) + r.nextDouble(), 64, r.nextDouble() * 9, 0f, 0f, 0f);
            else if (op < 35) o = new EntityObservation.Moved(id, (short) r.nextInt(-9000, 9000), (short) r.nextInt(-50, 50), (short) r.nextInt(-9000, 9000), r.nextInt(4) > 0, r.nextInt(-180, 180), r.nextInt(-90, 90), r.nextBoolean());
            else if (op < 39) o = new EntityObservation.PositionSynced(id, r.nextInt(-40, 40) + 0.5, 64, 0.5, 0f, 0f);
            else if (op < 43) o = new EntityObservation.Teleported(id, r.nextInt(-40, 40), 64, 3, r.nextInt(-180, 180), r.nextInt(-120, 120), r.nextInt(32));
            else if (op < 47) o = new EntityObservation.Removed(List.of(id));
            else if (op < 52) { int p = r.nextInt(4); o = new EntityObservation.DataUpdated(id, p == 0 ? "CROUCHING" : p == 1 ? "NOT_A_POSE" : null, p == 2, r.nextInt(-1, 256)); }
            else if (op < 56) { int v = 2 + r.nextInt(IDS); o = new EntityObservation.Passengers(v, r.nextBoolean() ? List.of(id) : List.of()); }
            else if (op < 58) o = new EntityObservation.UnsupportedMotion(id, "MINECART_LERP_STEPS");
            else if (op < 61) o = new EntityObservation.HeadRotated(id, r.nextInt(-180, 180));
            else if (op < 80) attack = id;
            ops.add(new Op(o, attack));
        }
        return ops;
    }
    /** Runs ops; {@code lost} indices consume their sequence unobserved; {@code ids} relabels every network id. Returns the attack contexts by op index. */
    static Map<Integer, AttackContext> run(List<Op> ops, Set<Integer> lost, IntUnaryOperator ids) {
        var r = new CombatTestSupport.Rig(); Map<Integer, AttackContext> out = new TreeMap<>();
        for (int i = 0; i < ops.size(); i++) {
            Op o = ops.get(i);
            if (lost.contains(i)) { r.gap(); continue; }
            if (o.attackId() >= 0) { var c = r.attack(ids.applyAsInt(o.attackId())); assertNotNull(c, "attack " + i); out.put(i, c); }
            else if (o.obs() == null) r.silent();
            else r.apply(relabel(o.obs(), ids));
        }
        return out;
    }
    static EntityObservation relabel(EntityObservation o, IntUnaryOperator f) {
        return switch (o) {
            case EntityObservation.Spawned s -> new EntityObservation.Spawned(f.applyAsInt(s.entityId()), s.uuid(), s.type(), s.x(), s.y(), s.z(), s.yaw(), s.pitch(), s.headYaw());
            case EntityObservation.Moved m -> new EntityObservation.Moved(f.applyAsInt(m.entityId()), m.dx(), m.dy(), m.dz(), m.hasPosition(), m.yaw(), m.pitch(), m.hasRotation());
            case EntityObservation.PositionSynced p -> new EntityObservation.PositionSynced(f.applyAsInt(p.entityId()), p.x(), p.y(), p.z(), p.yaw(), p.pitch());
            case EntityObservation.Teleported t -> new EntityObservation.Teleported(f.applyAsInt(t.entityId()), t.x(), t.y(), t.z(), t.yaw(), t.pitch(), t.relativeMask());
            case EntityObservation.Removed rm -> new EntityObservation.Removed(rm.ids().stream().map(f::applyAsInt).toList());
            case EntityObservation.DataUpdated d -> new EntityObservation.DataUpdated(f.applyAsInt(d.entityId()), d.pose(), d.poseOpaque(), d.sharedFlags());
            case EntityObservation.Passengers p -> new EntityObservation.Passengers(f.applyAsInt(p.vehicleId()), p.passengers().stream().map(f::applyAsInt).toList(), p.vehicleIsObserver(), p.carriesObserver());
            case EntityObservation.UnsupportedMotion u -> new EntityObservation.UnsupportedMotion(f.applyAsInt(u.entityId()), u.kind());
            case EntityObservation.HeadRotated h -> new EntityObservation.HeadRotated(f.applyAsInt(h.entityId()), h.headYaw());
            case EntityObservation.PoseUnknown u -> new EntityObservation.PoseUnknown(f.applyAsInt(u.entityId()), u.cause());
            case EntityObservation.CoverageBreak b -> b;
        };
    }
    /** Certainty of the target part of a context: 3 = FOUND, EXACT position, no target reason; 2 = FOUND with target reasons; 0 = no state. */
    static int rank(AttackContext c) {
        if (!c.target().found()) return 0;
        boolean targetReason = c.completeness().reasons().stream().anyMatch(x -> x.name().startsWith("TARGET_"));
        return c.target().revision().positionStatus() == EntityRevision.Status.EXACT && !targetReason ? 3 : 2;
    }
    static String shape(AttackContext c) {
        var l = c.target(); var v = l.revision();
        return l.status() + (v == null ? "" : " inc=" + l.key().incarnation() + " " + l.type() + " rev@" + v.sequence() + " " + v.cause() + " pos=" + Double.doubleToRawLongBits(v.x()) + "/" + Double.doubleToRawLongBits(v.y())
            + "/" + Double.doubleToRawLongBits(v.z()) + " " + v.positionStatus() + " rot=" + Float.floatToRawIntBits(v.yaw()) + "/" + Float.floatToRawIntBits(v.pitch()) + " " + v.rotationStatus() + " pose=" + v.pose()
            + " flags=" + v.sharedFlags() + " mounted=" + (v.vehicleId() >= 0)) + " " + c.completeness().reasons();
    }

    @Test void deletingAnyObservationNeverRaisesAttackCertainty() {
        List<String> bad = new ArrayList<>();
        for (long seed = 1; seed <= 40; seed++) {
            var ops = generate(seed); var original = run(ops, Set.of(), x -> x); var rnd = new SplittableRandom(seed * 31);
            for (int t = 0; t < 6; t++) {
                int idx = rnd.nextInt(ops.size()); if (ops.get(idx).attackId() >= 0) continue;
                var variant = run(ops, Set.of(idx), x -> x);
                for (var e : variant.entrySet()) {
                    var a = original.get(e.getKey()); var b = e.getValue();
                    if (a.target().status() == Status.EVICTED) continue;
                    if (rank(b) > rank(a)) bad.add("seed " + seed + " delete " + idx + " attack@" + e.getKey() + ": " + shape(b) + "  vs original " + shape(a));
                    else if (rank(b) == 3 && !shape(a).replaceAll(" inc=[0-9]+", "").equals(shape(b).replaceAll(" inc=[0-9]+", ""))) bad.add("seed " + seed + " delete " + idx + " exact value changed at attack@" + e.getKey() + ": " + shape(b) + " vs " + shape(a));
                }
            }
        }
        assertTrue(bad.isEmpty(), bad.size() + " violations, first: " + bad.subList(0, Math.min(5, bad.size())));
    }
    @Test void relabellingNetworkIdsIsInvisible() {
        for (long seed = 1; seed <= 40; seed++) {
            var ops = generate(seed); var a = run(ops, Set.of(), x -> x); var b = run(ops, Set.of(), x -> 1_000_003 - x * 7);
            for (var e : a.entrySet()) assertEquals(shape(e.getValue()), shape(b.get(e.getKey())), "seed " + seed + " attack@" + e.getKey());
        }
    }
    /** A SetEntityData / SetPassengers re-sent with identical content at a later sequence is idempotent on the client: no completeness may change. */
    @Test void reSendingIdempotentStateChangesNoCompleteness() {
        for (long seed = 1; seed <= 40; seed++) {
            var ops = generate(seed); List<Op> doubled = new ArrayList<>(); List<Integer> map = new ArrayList<>();
            for (int i = 0; i < ops.size(); i++) {
                map.add(doubled.size()); doubled.add(ops.get(i));
                var o = ops.get(i).obs();
                if (o instanceof EntityObservation.DataUpdated || o instanceof EntityObservation.Passengers) doubled.add(ops.get(i));
            }
            var a = run(ops, Set.of(), x -> x); var b = run(doubled, Set.of(), x -> x);
            for (var e : a.entrySet()) {
                var x = e.getValue(); var y = b.get(map.get(e.getKey()));
                assertEquals(x.completeness().reasons(), y.completeness().reasons(), "seed " + seed + " attack@" + e.getKey() + ": " + shape(y) + " vs " + shape(x));
                assertEquals(x.target().status(), y.target().status());
            }
        }
    }
}
