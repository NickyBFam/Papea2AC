package me.nicolas.papea2ac.testing;

import static me.nicolas.papea2ac.testing.CombatTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.RecordComponent;
import java.util.*;
import me.nicolas.papea2ac.combat.*;
import me.nicolas.papea2ac.combat.CombatCompleteness.Reason;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.player.*;
import me.nicolas.papea2ac.world.*;
import me.nicolas.papea2ac.world.EntityLookup.Status;
import org.junit.jupiter.api.Test;

/**
 * Phase 11 combat engine: attack contexts are descriptive, immutable and deterministic; the target resolves at the attack's exact sequence; attacker
 * provenance comes only from the timeline; interactions are never attacks; nothing claims server acceptance; bounds hold.
 */
class CombatEngineTest {
    static TimelineRecord record(long seq, Vector3 pos, float yaw, float pitch, long rotSeq) {
        var st = new PlayerState(new MovementState(pos, true, false, PlayerObservation.ApplyResult.APPLIED, seq), rotSeq > 0 ? new RotationState(new Rotation(yaw, pitch), rotSeq) : RotationState.UNKNOWN,
            TeleportState.EMPTY, VelocityState.EMPTY, TickState.UNKNOWN, 0, 0, seq);
        return new TimelineRecord(seq, TimelineRecord.Kind.MOVEMENT, seq * TICK, false, 0, 0, st, List.of(), new PlayerObservation.MovementClaim(Optional.of(pos), Optional.empty(), true, false), "");
    }
    @Test void anAttackOnAKnownTargetCarriesItsExactNetworkStateAndProvenance() {
        Rig r = new Rig(); r.spawn(5, 3.5, 64, 0); long mv = r.move(5, 2048, 0, 0);
        long s = r.next(); r.h.observeSequence(KEY, s, r.nanos);
        var attacker = new TimelineLookup.Found(record(mv, new Vector3(0.5, 64, 0.5), 90f, 5f, mv));
        var c = r.engine.onAttack(new CombatObservation.Attack(s, 11, 7, 5, r.nanos), r.h, attacker, new CombatProvenance.MovementValidation(true, "rest", "VALID", mv), null);
        assertEquals(Status.FOUND, c.target().status()); assertEquals(4.0, c.target().revision().x()); assertEquals(1, c.target().key().incarnation());
        assertEquals(CombatProvenance.Attacker.Status.FOUND, c.attacker().status()); assertTrue(c.attacker().rotationKnown()); assertEquals(90f, c.attacker().yaw()); assertTrue(c.attacker().movementValidatedThrough());
        assertEquals("A1:" + s, c.id(), "identity: session epoch + ordered capture sequence, no wall clock");
        assertEquals(Set.of(Reason.TIMING_UNAVAILABLE), c.completeness().reasons(), "only the missing timing view makes it incomplete");
        assertEquals(CombatCompleteness.CAPTURE_ONLY, c.completeness().standing(), "every context carries the CAPTURE-ONLY standing limitations");
        assertTrue(c.completeness().standing().contains(CombatCompleteness.Standing.SERVER_OUTCOME_UNSUPPORTED), "an observed attack never implies server acceptance");
    }
    @Test void everyTargetMissMapsToItsOwnReasonAndNeverToAPosition() {
        Rig r = new Rig();
        assertEquals(Reason.TARGET_UNKNOWN, only(r.attack(99)));
        r.spawn(1, 0, 64, 0); r.remove(1); assertEquals(Reason.TARGET_DESPAWNED, only(r.attack(1)));
        Rig g = new Rig(); g.spawn(2, 0, 64, 0); g.gap(); g.move(2, 1, 0, 0); assertEquals(Reason.TARGET_COVERAGE, only(g.attack(2)));
        Rig u = new Rig(); u.spawn(3, 0, 64, 0); u.apply(new EntityObservation.UnsupportedMotion(3, "MINECART_LERP_STEPS")); assertEquals(Reason.TARGET_UNSUPPORTED_MOTION, only(u.attack(3)));
        Rig t = new Rig(); t.spawn(4, 0, 64, 0); t.teleport(4, 1, 0, 0, EntityObservation.Teleported.REL_X); assertEquals(Reason.TARGET_POSITION_INTERPOLATION_DEPENDENT, only(t.attack(4)));
        Rig p = new Rig(); p.spawn(6, 0, 64, 0); p.apply(new EntityObservation.DataUpdated(6, null, true, -1)); assertEquals(Reason.TARGET_POSE_UNKNOWN, only(p.attack(6)));
        Rig m = new Rig(); m.spawn(7, 0, 64, 0); m.spawn(8, 0, 64, 0); m.apply(new EntityObservation.Passengers(7, List.of(8))); assertEquals(EnumSet.of(Reason.TARGET_MOUNTED, Reason.TARGET_VEHICLE_DEPENDENT), targets(m.attack(8)));
        for (Rig any : List.of(r, g, u, t, p, m)) for (var c : any.engine.attacks()) if (!c.target().found()) assertNull(c.target().revision(), "a miss never carries a position");
    }
    static EnumSet<Reason> targets(AttackContext c) { var target = EnumSet.noneOf(Reason.class); for (Reason x : c.completeness().reasons()) if (x.name().startsWith("TARGET_")) target.add(x); return target; }
    /** The target reason alone (the rigs have no timeline or timing, so those reasons are always present and filtered out). */
    static Reason only(AttackContext c) {
        var target = EnumSet.noneOf(Reason.class); for (Reason x : c.completeness().reasons()) if (x.name().startsWith("TARGET_")) target.add(x);
        assertEquals(1, target.size(), c.describe()); return target.iterator().next();
    }
    @Test void attackerProvenanceNeverFallsBackToACurrentPosition() {
        Rig r = new Rig(); r.spawn(1, 0, 64, 0);
        for (TimelineLookup miss : List.<TimelineLookup>of(new TimelineLookup.Evicted(5), new TimelineLookup.PreCapture(5), new TimelineLookup.NeverExisted(5), new TimelineLookup.WorldEpochCrossing(5, 0, 1), new TimelineLookup.IncompleteCoverage(5, "GAP"))) {
            long s = r.next(); r.h.observeSequence(KEY, s, r.nanos);
            var c = r.engine.onAttack(new CombatObservation.Attack(s, s, s, 1, r.nanos), r.h, miss, null, null);
            assertNull(c.attacker().claimedPosition()); assertTrue(c.completeness().reasons().contains(Reason.ATTACKER_HISTORY_UNAVAILABLE), miss.toString());
        }
        long s = r.next(); r.h.observeSequence(KEY, s, r.nanos);
        var noRot = r.engine.onAttack(new CombatObservation.Attack(s, s, s, 1, r.nanos), r.h, new TimelineLookup.Found(record(s - 1, new Vector3(0, 64, 0), 0, 0, 0)), CombatProvenance.MovementValidation.UNAVAILABLE, null);
        assertTrue(noRot.completeness().reasons().contains(Reason.ATTACKER_ROTATION_UNKNOWN)); assertTrue(noRot.completeness().reasons().contains(Reason.ATTACKER_MOVEMENT_UNVALIDATED));
    }
    @Test void timingProvenanceIsDescriptive() {
        Rig r = new Rig(); r.spawn(1, 0, 64, 0);
        var rtt = new RttEstimator.Summary(12, 12, 0, 0, 40_000_000L, 35_000_000L, 60_000_000L, 41_000_000L, 3_000_000L, r.nanos);
        var view = new TimingLedger.View(KEY, r.seq, r.nanos, 0, false, true, r.seq, rtt, null, null, List.of(), List.of(), false);
        long s = r.next(); r.h.observeSequence(KEY, s, r.nanos);
        var c = r.engine.onAttack(new CombatObservation.Attack(s, s, s, 1, r.nanos), r.h, new TimelineLookup.NeverExisted(s), null, view);
        assertEquals(CombatProvenance.Timing.Status.TICK_ALIGNED, c.timing().status()); assertEquals(41_000_000L, c.timing().rttSmoothedNanos()); assertFalse(c.timing().rttStale());
        var stale = CombatProvenance.Timing.of(view, s, r.nanos + 11_000_000_000L); assertTrue(stale.rttStale(), "older than the ten-second marker retention");
        var noTick = CombatProvenance.Timing.of(new TimingLedger.View(KEY, r.seq, r.nanos, 0, false, true, 0, rtt, null, null, List.of(), List.of(), false), s, r.nanos);
        assertEquals(CombatProvenance.Timing.Status.NO_TICK_END_YET, noTick.status());
    }
    @Test void anInteractionIsNeverAnAttack() {
        Rig r = new Rig(); r.spawn(1, 0, 64, 0); long s = r.next(); r.h.observeSequence(KEY, s, r.nanos);
        var i = r.engine.onInteraction(new CombatObservation.Interaction(s, s, s, 1, r.nanos, "MAIN_HAND", 0.1, 1.2, 0.3, true), r.h);
        assertEquals(Status.FOUND, i.target().status()); assertEquals(0, r.engine.status().attacks()); assertEquals(1, r.engine.status().interactions());
        assertEquals(0, r.metrics.attacks.sum()); assertEquals(1, r.metrics.interactions.sum()); assertTrue(r.engine.attacks().isEmpty());
    }
    @Test void outOfOrderInputIsRejectedAndBoundsHold() {
        Rig r = new Rig(); r.spawn(1, 0, 64, 0);
        for (int i = 0; i < CombatEngine.MAX_ATTACKS + 30; i++) r.attack(1);
        assertEquals(CombatEngine.MAX_ATTACKS, r.engine.attacks().size()); assertEquals(CombatEngine.MAX_ATTACKS + 30, r.engine.status().attacks());
        long last = r.engine.attacks().get(r.engine.attacks().size() - 1).observation().sequence();
        assertNull(r.engine.onAttack(new CombatObservation.Attack(last, 1, 1, 1, r.nanos), r.h, new TimelineLookup.NeverExisted(last), null, null), "a duplicate attack observation");
        assertEquals(1, r.engine.status().rejected()); assertTrue(r.engine.status().recentAttacks().size() <= CombatEngine.STATUS_LINES);
    }
    @Test void sameInputsGiveTheSameContexts() {
        List<String> a = run(), b = run(); assertEquals(a, b);
    }
    static List<String> run() {
        Rig r = new Rig(); r.spawn(1, 0.3, 64, 0.7); r.move(1, 123, -5, 77); r.attack(1); r.teleport(1, 9, 9, 9, 0); r.attack(1); r.remove(1); r.attack(1); r.spawn(1, 5, 5, 5); r.attack(1); r.attack(2);
        List<String> out = new ArrayList<>(); for (var c : r.engine.attacks()) out.add(c.describe()); out.add(CombatReplayMapper.digest(CombatReplayMapper.export(r.engine, P.id()))); return out;
    }
    @Test void noVerdictFieldExistsAnywhereInTheCombatTypes() {
        for (Class<?> type : List.of(AttackContext.class, CombatCompleteness.class, CombatProvenance.Attacker.class, CombatProvenance.Timing.class, CombatObservation.Attack.class, CombatEngine.InteractionRecord.class, EntityLookup.class, EntityRevision.class))
            for (RecordComponent rc : type.getRecordComponents()) {
                String n = rc.getName().toLowerCase(Locale.ROOT);
                for (String bad : List.of("reach", "hitbox", "cheat", "aura", "suspicious", "invalid", "punish", "setback", "verdict", "score", "confidence", "cps", "legal", "violation"))
                    assertFalse(n.contains(bad), type.getSimpleName() + "." + rc.getName());
            }
    }
}
