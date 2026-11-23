package me.nicolas.papea2ac.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import me.nicolas.papea2ac.combat.geometry.PickMath;
import me.nicolas.papea2ac.combat.reach.ApplicationFrontier;
import me.nicolas.papea2ac.combat.reach.ReachValidation;
import me.nicolas.papea2ac.combat.reach.ReachValidator;
import me.nicolas.papea2ac.combat.reach.TargetHull;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.version.EntityGeometryTable;
import me.nicolas.papea2ac.version.MovementProfile;
import me.nicolas.papea2ac.world.EntityLookup;
import org.junit.jupiter.api.Test;

/**
 * Phase 12 reach validator: fixed scenarios with known vanilla answers, the interpolation hull against an exact simulation of the 26.2 client's
 * {@code InterpolationHandler} under random application schedules, and the marker-proven application frontier.
 */
class ReachValidatorTest {
    static final SessionKey KEY = new SessionKey(new UUID(0x12L, 0x12L), 1);
    static final EntityGeometryTable TABLE = EntityGeometryTable.paper26_2();
    static final MovementProfile TRIG = MovementProfile.paper26_2();
    static final ReachValidator.AirOracle OPEN_AIR = (x, y, z) -> Boolean.TRUE;

    // ---- scenario helpers --------------------------------------------------------------------------------------------------------------------
    static ApplicationFrontier proven(int ticks) { return new ApplicationFrontier(ApplicationFrontier.Status.MARKER_PROVEN, 10, 12, ticks); }

    static ReachValidator.TargetInput husk(double x, double y, double z) {
        PickMath.Box life = new PickMath.Box(x, y, z, x, y, z);
        return new ReachValidator.TargetInput(EntityLookup.Status.FOUND, "minecraft:husk", 7, 3, List.of(new double[]{x, y, z}), true, false, false, life, proven(40), "STANDING", 1.0, false);
    }
    static ReachValidator.EyeInput certifiedEye(double x, double y, double z) {
        return new ReachValidator.EyeInput(ReachValidation.EyeSource.CERTIFIED_CONCRETE, List.of(new ReachValidator.EyeCandidate(x, y, z, "STANDING", true, x, y, z)), null, List.of(), false);
    }
    static ReachValidator.AttackerInput vanillaAttacker() { return new ReachValidator.AttackerInput(3.0, 4.5, 1.0, false, ReachValidator.HeldItem.CLASSIC, "SURVIVAL"); }
    static ReachValidation run(ReachValidator.TargetInput t, ReachValidator.EyeInput e, float yaw, float pitch, ReachValidator.AttackerInput a, ReachValidator.AirOracle air, boolean abstractOk) {
        return ReachValidator.validate(new ReachValidator.Input(KEY, 100, "A1:100", t, e, List.of(new ReachValidator.View(yaw, pitch)), a, air, abstractOk), TABLE, TRIG, "test");
    }

    @Test void thePossibleEntityBoxCoversTheHitboxAndRefusesWhatItCannotBound() {
        // Phase 13's push exclusion (phase13-to-phase12-02): a stationary adult husk at scale 1 is its exact 0.6 x 1.95 box, outward-rounded
        PickMath.Box b = ReachValidator.possibleEntityBox(husk(0.5, 64.0, 9.5), TABLE);
        assertNotNull(b);
        assertTrue(b.minX() <= 0.2 && b.minX() > 0.2 - 1e-6 && b.maxX() >= 0.8 && b.maxX() < 0.8 + 1e-6, b.toString());
        assertTrue(b.minY() <= 64.0 && b.minY() > 64.0 - 1e-6 && b.maxY() >= 65.95 && b.maxY() < 65.95 + 1e-6, b.toString());
        // unknown scale grows to the attribute maximum
        ReachValidator.TargetInput unknownScale = new ReachValidator.TargetInput(EntityLookup.Status.FOUND, "minecraft:husk", 7, 3, List.of(new double[]{0.5, 64, 9.5}), true, false, false,
            new PickMath.Box(0.5, 64, 9.5, 0.5, 64, 9.5), proven(40), "STANDING", null, false);
        assertTrue(ReachValidator.possibleEntityBox(unknownScale, TABLE).maxY() > 64 + 1.95 * 15, "scale up to 16");
        // nothing provable: VARIABLE type, mounted, no lifetime, missing entity
        assertNull(ReachValidator.possibleEntityBox(new ReachValidator.TargetInput(EntityLookup.Status.FOUND, "minecraft:slime", 7, 3, List.of(new double[]{0.5, 64, 9}), true, false, false,
            new PickMath.Box(0.5, 64, 9, 0.5, 64, 9), proven(40), "STANDING", 1.0, false), TABLE));
        assertNull(ReachValidator.possibleEntityBox(new ReachValidator.TargetInput(EntityLookup.Status.FOUND, "minecraft:husk", 7, 3, List.of(new double[]{0.5, 64, 9}), true, true, false,
            new PickMath.Box(0.5, 64, 9, 0.5, 64, 9), proven(40), "STANDING", 1.0, false), TABLE));
        assertNull(ReachValidator.possibleEntityBox(new ReachValidator.TargetInput(EntityLookup.Status.FOUND, "minecraft:husk", 7, 3, List.of(new double[]{0.5, 64, 9}), true, false, false,
            null, proven(40), "STANDING", 1.0, false), TABLE));
        assertNull(ReachValidator.possibleEntityBox(new ReachValidator.TargetInput(EntityLookup.Status.INCOMPLETE_COVERAGE, null, 7, 0, List.of(), false, false, false, null,
            ApplicationFrontier.NONE, null, null, null), TABLE));
    }

    @Test void aStraightHitInRangeIsValidWithAWitness() {
        // attacker feet (0.5, 64, 0.5) looking +z (yaw 0) level; husk 2.0 ahead: its near face is 1.7 from the eye
        ReachValidation r = run(husk(0.5, 64.0, 2.5), certifiedEye(0.5, 64.0, 0.5), 0f, 0f, vanillaAttacker(), OPEN_AIR, false);
        assertEquals(ReachValidation.Status.VALID, r.status(), r.describe());
        assertTrue(r.witness().maxEntryDistance() < 3.0 && r.witness().maxEntryDistance() > 1.6, r.describe());
    }

    @Test void aHitFromFourBlocksIsInvalidReach() {
        ReachValidation r = run(husk(0.5, 64.0, 4.8), certifiedEye(0.5, 64.0, 0.5), 0f, 0f, vanillaAttacker(), OPEN_AIR, false);
        assertEquals(ReachValidation.Status.INVALID, r.status(), r.describe());
        assertEquals(List.of(ReachValidation.MissClass.REACH), r.missClasses());
        assertTrue(r.certificate().minResidual() > 0.9, r.describe());
    }

    @Test void aTargetBesideTheViewIsInvalidAngle() {
        ReachValidation r = run(husk(2.5, 64.0, 2.0), certifiedEye(0.5, 64.0, 0.5), 0f, 0f, vanillaAttacker(), OPEN_AIR, false);
        assertEquals(ReachValidation.Status.INVALID, r.status(), r.describe());
        assertEquals(List.of(ReachValidation.MissClass.ANGLE), r.missClasses());
    }

    @Test void theExactBoundaryIsNeverInvalid() {
        // near face exactly 3.0 from the eye (the half width is the float 0.3F): the client refuses (strict <), but an exclusion needs margin
        double z = 0.5 + 3.0 + (double) (0.6F / 2.0F);
        ReachValidation r = run(husk(0.5, 64.0, z), certifiedEye(0.5, 64.0 - 1.62 + 1.62, 0.5), 0f, 0f, vanillaAttacker(), OPEN_AIR, false);
        assertNotEquals(ReachValidation.Status.INVALID, r.status(), r.describe());
        assertNotEquals(ReachValidation.Status.VALID, r.status(), r.describe());
    }

    @Test void unknownBlocksPreventValidButNotAnExclusion() {
        ReachValidator.AirOracle unknown = (x, y, z) -> null;
        ReachValidation near = run(husk(0.5, 64.0, 2.5), certifiedEye(0.5, 64.0, 0.5), 0f, 0f, vanillaAttacker(), unknown, false);
        assertEquals(ReachValidation.Status.UNCERTAIN, near.status()); assertTrue(near.reasons().contains(ReachValidation.Reason.BLOCKS_UNRESOLVED), near.describe());
        ReachValidation far = run(husk(0.5, 64.0, 4.8), certifiedEye(0.5, 64.0, 0.5), 0f, 0f, vanillaAttacker(), unknown, false);
        assertEquals(ReachValidation.Status.INVALID, far.status(), far.describe());
    }

    @Test void noEyeMeansUncertainAndAnAbstractEyeExcludesOnlyWhenPermitted() {
        ReachValidation none = run(husk(0.5, 64.0, 4.8), ReachValidator.EyeInput.UNAVAILABLE, 0f, 0f, vanillaAttacker(), OPEN_AIR, true);
        assertEquals(ReachValidation.Status.UNCERTAIN, none.status()); assertTrue(none.reasons().contains(ReachValidation.Reason.EYE_UNAVAILABLE));
        ReachValidator.EyeInput abs = new ReachValidator.EyeInput(ReachValidation.EyeSource.ABSTRACT_EXCLUSION_ONLY, List.of(), new PickMath.Box(0.4998, 64.0, 0.4998, 0.5002, 64.0002, 0.5002), List.of("STANDING", "CROUCHING"), false);
        assertEquals(ReachValidation.Status.UNCERTAIN, run(husk(0.5, 64.0, 4.8), abs, 0f, 0f, vanillaAttacker(), OPEN_AIR, false).status(), "B2 strict: no exclusion from an abstract eye");
        assertEquals(ReachValidation.Status.INVALID, run(husk(0.5, 64.0, 4.8), abs, 0f, 0f, vanillaAttacker(), OPEN_AIR, true).status(), "exclusion over the whole abstract set when permitted");
        // an abstract eye never gives VALID
        assertNotEquals(ReachValidation.Status.VALID, run(husk(0.5, 64.0, 2.5), abs, 0f, 0f, vanillaAttacker(), OPEN_AIR, true).status());
    }

    @Test void untrackedOrUnsupportedTargetsAreNeverInvalid() {
        ReachValidator.TargetInput missing = new ReachValidator.TargetInput(EntityLookup.Status.INCOMPLETE_COVERAGE, null, 7, 0, List.of(), false, false, false, null, ApplicationFrontier.NONE, null, null, null);
        assertEquals(ReachValidation.Status.UNCERTAIN, run(missing, certifiedEye(0.5, 64, 0.5), 0f, 0f, vanillaAttacker(), OPEN_AIR, true).status());
        ReachValidator.TargetInput slime = new ReachValidator.TargetInput(EntityLookup.Status.FOUND, "minecraft:slime", 7, 3, List.of(new double[]{0.5, 64, 9}), true, false, false, new PickMath.Box(0.5, 64, 9, 0.5, 64, 9), proven(40), "STANDING", 1.0, false);
        ReachValidation s = run(slime, certifiedEye(0.5, 64, 0.5), 0f, 0f, vanillaAttacker(), OPEN_AIR, true);
        assertEquals(ReachValidation.Status.UNCERTAIN, s.status()); assertTrue(s.reasons().contains(ReachValidation.Reason.TARGET_DIMENSIONS_UNSUPPORTED));
        ReachValidator.TargetInput dependent = new ReachValidator.TargetInput(EntityLookup.Status.FOUND, "minecraft:husk", 7, 3, List.of(new double[]{0.5, 64, 9}), false, false, false, new PickMath.Box(0.5, 64, 9, 0.5, 64, 9), proven(40), "STANDING", 1.0, false);
        assertEquals(ReachValidation.Status.UNCERTAIN, run(dependent, certifiedEye(0.5, 64, 0.5), 0f, 0f, vanillaAttacker(), OPEN_AIR, true).status());
        ReachValidator.TargetInput noLifetime = new ReachValidator.TargetInput(EntityLookup.Status.FOUND, "minecraft:husk", 7, 3, List.of(new double[]{0.5, 64, 9}), true, false, false, null, proven(40), "STANDING", 1.0, false);
        assertEquals(ReachValidation.Status.UNCERTAIN, run(noLifetime, certifiedEye(0.5, 64, 0.5), 0f, 0f, vanillaAttacker(), OPEN_AIR, true).status());
    }

    @Test void aNeverPickableTypeIsInvalidTarget() {
        ReachValidator.TargetInput item = new ReachValidator.TargetInput(EntityLookup.Status.FOUND, "minecraft:item", 7, 3, List.of(new double[]{0.5, 64, 2}), true, false, false, new PickMath.Box(0.5, 64, 2, 0.5, 64, 2), proven(40), "STANDING", null, null);
        ReachValidation r = run(item, ReachValidator.EyeInput.UNAVAILABLE, 0f, 0f, vanillaAttacker(), OPEN_AIR, false);
        assertEquals(ReachValidation.Status.INVALID, r.status()); assertEquals(List.of(ReachValidation.MissClass.INVALID_TARGET), r.missClasses());
    }

    @Test void heldItemAndRangeUnknownsBlockOnlyWhatTheyMust() {
        ReachValidator.AttackerInput unknownItem = new ReachValidator.AttackerInput(3.0, 4.5, 1.0, false, ReachValidator.HeldItem.UNKNOWN, "SURVIVAL");
        ReachValidation far = run(husk(0.5, 64.0, 4.8), certifiedEye(0.5, 64.0, 0.5), 0f, 0f, unknownItem, OPEN_AIR, false);
        assertEquals(ReachValidation.Status.UNCERTAIN, far.status()); assertTrue(far.reasons().contains(ReachValidation.Reason.HELD_ITEM_UNKNOWN));
        ReachValidator.AttackerInput unknownRange = new ReachValidator.AttackerInput(null, 4.5, 1.0, false, ReachValidator.HeldItem.CLASSIC, "SURVIVAL");
        assertEquals(ReachValidation.Status.UNCERTAIN, run(husk(0.5, 64.0, 4.8), certifiedEye(0.5, 64.0, 0.5), 0f, 0f, unknownRange, OPEN_AIR, false).status(), "reach exclusion needs the range");
        assertEquals(ReachValidation.Status.INVALID, run(husk(2.5, 64.0, 2.0), certifiedEye(0.5, 64.0, 0.5), 0f, 0f, unknownRange, OPEN_AIR, false).status(), "an angle exclusion does not need the range");
        ReachValidator.AttackerInput creative = new ReachValidator.AttackerInput(5.0, 5.0, 1.0, false, ReachValidator.HeldItem.CLASSIC, "CREATIVE");
        assertEquals(ReachValidation.Status.VALID, run(husk(0.5, 64.0, 4.8), certifiedEye(0.5, 64.0, 0.5), 0f, 0f, creative, OPEN_AIR, false).status(), "creative range from the synced attribute");
    }

    @Test void withoutMarkerProofTheWholeLifetimeIsAdmissible() {
        // the husk is at z=9 now, but was within reach earlier in its life: without a proven frontier that old position stays admissible
        ReachValidator.TargetInput t = new ReachValidator.TargetInput(EntityLookup.Status.FOUND, "minecraft:husk", 7, 3, List.of(new double[]{0.5, 64, 9}), true, false, false,
            new PickMath.Box(0.5, 64, 2.5, 0.5, 64, 9), ApplicationFrontier.NONE, "STANDING", 1.0, false);
        assertEquals(ReachValidation.Status.UNCERTAIN, run(t, certifiedEye(0.5, 64, 0.5), 0f, 0f, vanillaAttacker(), OPEN_AIR, false).status());
        // with 40 proven ticks since the z=9 update, the old position's weight is below (2/3)^40: excluded
        ReachValidator.TargetInput p = new ReachValidator.TargetInput(EntityLookup.Status.FOUND, "minecraft:husk", 7, 3, List.of(new double[]{0.5, 64, 9}), true, false, false,
            new PickMath.Box(0.5, 64, 2.5, 0.5, 64, 9), proven(40), "STANDING", 1.0, false);
        assertEquals(ReachValidation.Status.INVALID, run(p, certifiedEye(0.5, 64, 0.5), 0f, 0f, vanillaAttacker(), OPEN_AIR, false).status());
    }

    @Test void deterministic() {
        ReachValidation a = run(husk(0.7, 64.2, 3.3), certifiedEye(0.5, 64.0, 0.5), 3.5f, -4.25f, vanillaAttacker(), OPEN_AIR, true), b = run(husk(0.7, 64.2, 3.3), certifiedEye(0.5, 64.0, 0.5), 3.5f, -4.25f, vanillaAttacker(), OPEN_AIR, true);
        assertEquals(a, b);
    }

    // ---- the interpolation hull against an exact client simulation ------------------------------------------------------------------------
    /** {@code InterpolationHandler} of the 26.2 client (javap bc 0–321), without local displacement: interpolateTo resets steps; interpolate lerps by 1/steps. */
    static final class ClientEntity {
        double x, y, z, tx, ty, tz; int steps; final int maxSteps;
        ClientEntity(double x, double y, double z, int maxSteps) { this.x = x; this.y = y; this.z = z; this.maxSteps = maxSteps; }
        void interpolateTo(double nx, double ny, double nz) {
            if (maxSteps == 0) { x = nx; y = ny; z = nz; return; }
            if (steps > 0 && tx == nx && ty == ny && tz == nz) return;
            tx = nx; ty = ny; tz = nz; steps = maxSteps;
        }
        void tick() {
            if (steps <= 0) return;
            double d = 1.0 / (double) steps;
            x = PickMath.lerp(d, x, tx); y = PickMath.lerp(d, y, ty); z = PickMath.lerp(d, z, tz); steps--;
        }
    }

    @Test void theInterpolationHullContainsEveryClientPosition() {
        Random r = new Random(0x12_2001L); int checks = 0; int[] nestedChecks = {0};
        for (int run = 0; run < 3_000; run++) {
            int steps = r.nextInt(5) == 0 ? 0 : 3;
            double sx = (r.nextDouble() - 0.5) * 1e5, sy = 64 + r.nextDouble() * 20, sz = (r.nextDouble() - 0.5) * 1e5;
            // server targets: a random walk with occasional jumps (knockback / teleport-like syncs)
            int n = 20 + r.nextInt(60); double[][] q = new double[n][3]; double cx = sx, cy = sy, cz = sz;
            for (int i = 0; i < n; i++) {
                double jump = r.nextInt(15) == 0 ? 6 : 0.4;
                cx += (r.nextDouble() - 0.5) * jump; cy += (r.nextDouble() - 0.5) * jump * 0.5; cz += (r.nextDouble() - 0.5) * jump; q[i] = new double[]{cx, cy, cz};
            }
            // client application ticks: monotone, sometimes several per tick, sometimes gaps
            int[] applied = new int[n]; int tick = 1;
            for (int i = 0; i < n; i++) { tick += r.nextInt(4) == 0 ? 0 : 1 + (r.nextInt(6) == 0 ? r.nextInt(5) : 0); applied[i] = tick; }
            ClientEntity c = new ClientEntity(sx, sy, sz, steps);
            int last = applied[n - 1] + 6, ui = 0;
            PickMath.Box lifetime = box(List.of(new double[]{sx, sy, sz}));
            for (int t2 = 0; t2 <= last; t2++) {
                // a frame's packets are handled before the tick
                while (ui < n && applied[ui] == t2) { c.interpolateTo(q[ui][0], q[ui][1], q[ui][2]); ui++; }
                // an attack at the start of tick t2 (before this tick's entity step): check the hull for every proven frontier choice
                if (t2 > applied[0] && r.nextInt(3) == 0) {
                    int sentBefore = ui; // updates "sent before the attack": at least the applied ones; add up to 3 not yet applied
                    sentBefore = Math.min(n, sentBefore + r.nextInt(4));
                    List<double[]> sinceSpawn = new ArrayList<>(); sinceSpawn.add(new double[]{sx, sy, sz}); for (int i = 0; i < sentBefore; i++) sinceSpawn.add(q[i]);
                    lifetime = box(sinceSpawn);
                    // proven: update j applied at or before the pong tick kp <= t2; ticks since the pong = t2 - kp
                    int j = r.nextInt(Math.max(1, ui)); int kp = applied[j] + r.nextInt(Math.max(1, t2 - applied[j] + 1)); if (kp > t2) kp = t2;
                    List<double[]> window = new ArrayList<>(); for (int i = j; i < sentBefore; i++) window.add(q[i]);
                    PickMath.Box pos = TargetHull.positions(window, lifetime, steps, t2 - kp);
                    assertTrue(inside(pos, c), () -> "client position outside the hull: steps " + steps + " pos (" + c.x + "," + c.y + "," + c.z + ") hull " + pos);
                    // every answered marker at once (the nested bound): markers after a random subset of the applied updates, each proving that update
                    // applied at or before its pong tick, with the ticks since that pong
                    List<double[]> history = new ArrayList<>(); for (int i = 0; i < sentBefore; i++) history.add(q[i]);
                    List<TargetHull.Bound> bounds = new ArrayList<>();
                    for (int i = 0; i < ui; i++) if (r.nextInt(3) != 0) { int pong = applied[i] + r.nextInt(Math.max(1, t2 - applied[i] + 1)); if (pong > t2) pong = t2; bounds.add(new TargetHull.Bound(i, t2 - pong)); }
                    if (!bounds.isEmpty() && !history.isEmpty()) {
                        PickMath.Box nested = TargetHull.nested(history, lifetime, steps, bounds);
                        assertTrue(inside(nested, c), () -> "client position outside the nested hull: steps " + steps + " pos (" + c.x + "," + c.y + "," + c.z + ") hull " + nested);
                        nestedChecks[0]++;
                    }
                    // horizon only (no proof): the lifetime box is admissible as a whole
                    List<double[]> all = new ArrayList<>(window); all.add(new double[]{lifetime.minX(), lifetime.minY(), lifetime.minZ()}); all.add(new double[]{lifetime.maxX(), lifetime.maxY(), lifetime.maxZ()});
                    assertTrue(inside(TargetHull.positions(all, lifetime, steps, 0), c));
                    checks++;
                }
                c.tick();
            }
        }
        System.out.println("[phase12-hull] interpolation hull checks=" + checks + " nested=" + nestedChecks[0] + " escapes=0");
        assertTrue(nestedChecks[0] > 10_000, "nested checks " + nestedChecks[0]);
        assertTrue(checks > 10_000, "checks " + checks);
    }
    static PickMath.Box box(List<double[]> ps) {
        double a = Double.POSITIVE_INFINITY, b = a, cc = a, d = Double.NEGATIVE_INFINITY, e = d, f = d;
        for (double[] p : ps) { a = Math.min(a, p[0]); b = Math.min(b, p[1]); cc = Math.min(cc, p[2]); d = Math.max(d, p[0]); e = Math.max(e, p[1]); f = Math.max(f, p[2]); }
        return new PickMath.Box(a, b, cc, d, e, f);
    }
    static boolean inside(PickMath.Box b, ClientEntity c) { return b.minX() <= c.x && c.x <= b.maxX() && b.minY() <= c.y && c.y <= b.maxY() && b.minZ() <= c.z && c.z <= b.maxZ(); }

    // ---- the application frontier --------------------------------------------------------------------------------------------------------
    @Test void onlyAnsweredPingsBeforeTheAttackProveApplication() {
        List<TimingEvent> ev = new ArrayList<>();
        ev.add(new TimingEvent.MarkerSent(10, 10, TimingEvent.MarkerKind.KEEPALIVE, 1, 1));
        ev.add(new TimingEvent.MarkerAcknowledged(12, 12, TimingEvent.MarkerKind.KEEPALIVE, 1));
        assertEquals(ApplicationFrontier.Status.HORIZON_ONLY, ApplicationFrontier.of(ev, 100).status(), "a keep-alive is answered off the main thread");
        ev.add(new TimingEvent.MarkerSent(20, 20, TimingEvent.MarkerKind.PING, 7, 2));
        ev.add(new TimingEvent.ClientTickEnd(21, 21));
        ev.add(new TimingEvent.MarkerAcknowledged(30, 30, TimingEvent.MarkerKind.PING, 7));
        ev.add(new TimingEvent.ClientTickEnd(31, 31)); ev.add(new TimingEvent.ClientTickEnd(32, 32));
        ev.add(new TimingEvent.MarkerSent(40, 40, TimingEvent.MarkerKind.PING, 8, 3));
        ApplicationFrontier f = ApplicationFrontier.of(ev, 35);
        assertEquals(ApplicationFrontier.Status.MARKER_PROVEN, f.status()); assertEquals(20, f.markerSequence()); assertEquals(2, f.ticksSincePong());
        assertTrue(f.proves(19)); assertFalse(f.proves(20)); assertFalse(f.proves(25));
        assertEquals(ApplicationFrontier.Status.HORIZON_ONLY, ApplicationFrontier.of(ev, 30).status(), "a pong at the attack's own position does not precede it");
        ev.add(new TimingEvent.MarkerAcknowledged(50, 50, TimingEvent.MarkerKind.PING, 8));
        assertEquals(40, ApplicationFrontier.of(ev, 60).markerSequence(), "the newest answered marker wins");
    }
}
