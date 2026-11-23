package me.nicolas.papea2ac.testing;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.function.ToLongFunction;
import me.nicolas.papea2ac.combat.*;
import me.nicolas.papea2ac.core.CombatRunner;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.player.PlayerTimeline;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.protocol.PacketPayload.Position;
import me.nicolas.papea2ac.protocol.PacketPayload.Rotation;
import me.nicolas.papea2ac.world.*;
import me.nicolas.papea2ac.world.EntityLookup.Status;
import org.junit.jupiter.api.Test;

/**
 * Phase 11 adversarial QA (thread 3). Each test states the client-side truth it holds the model to. Tests named {@code defect*} reproduce a finding
 * filed in the coordination folder (they fail on the current working tree by design); the others are adversarial coverage that currently holds.
 */
class Phase11QaAdversarialTest {
    static final SessionKey A = CombatCorpus.KEY_A, B = CombatCorpus.KEY_B;
    static final int SELF = CombatCorpus.SELF;

    static boolean exactUnmounted(AttackContext c) {
        return c.target().found() && c.target().revision().positionStatus() == EntityRevision.Status.EXACT
            && !c.completeness().reasons().contains(CombatCompleteness.Reason.TARGET_MOUNTED);
    }

    // ---- QA-F1: a dismounted passenger gets back a stale network position labelled EXACT -------------------------------------------------------
    /**
     * While an entity rides, the server tracker sends it rotation only (ServerEntity.sendChanges, passenger branch) and the client positions it on
     * the vehicle (positionRider). On SetPassengers without it, the client's stopRiding leaves it where it rode; the server re-syncs only at its next
     * tracker update (wasRiding forces an EntityPositionSync). Until that sync the passenger's network target is NOT known, yet the model returns
     * the pre-mount spawn position as EXACT with no TARGET_MOUNTED.
     */
    @Test void defectDismountedPassengerRegainsAStaleExactPosition() {
        var t = new CombatCorpus.Trace(A);
        t.zombie(10);                                                   // the rider, at (1.5, 64, 2.5)
        t.spawn(20, "minecraft:horse", 1.5, 64, 2.5);
        t.out(new PacketPayload.Passengers(20, List.of(10)));
        for (int i = 0; i < 40; i++) t.move(20, 8192, 0, 0);           // the horse carries it 80 blocks; the server sends the rider no position
        var riding = t.attack(10);
        assertTrue(riding.completeness().reasons().contains(CombatCompleteness.Reason.TARGET_MOUNTED), riding.describe());
        t.out(new PacketPayload.Passengers(20, List.of()));            // dismount: the client keeps the rider at ~x 81.5
        var after = t.attack(10);
        assertFalse(exactUnmounted(after), "dismounted rider claimed EXACT at its pre-mount position before any position sync: " + after.describe());
    }

    // ---- QA-F2: an entity riding the observer ---------------------------------------------------------------------------------------------------
    /** SetPassengers(vehicle = the observer's own id): the client mounts the entity on its LocalPlayer; the model drops the packet (vehicle "unknown"). */
    @Test void defectAnEntityRidingTheObserverIsReportedExactAndUnmounted() {
        var t = new CombatCorpus.Trace(A);
        t.zombie(10);
        t.out(new PacketPayload.Passengers(SELF, List.of(10)));
        var c = t.attack(10);
        assertFalse(exactUnmounted(c), "an entity riding the observer is positioned by the observer's client, never by its last network target: " + c.describe());
    }

    /**
     * SetPassengers(vehicle, [the observer]): the observer's client controls the vehicle (Entity.isLocalInstanceAuthoritative), so handleMoveEntity and
     * handleEntityPositionSync only set its codec base and return; the client's position for it is local. The mapper filters the observer out of the
     * list, so the model never learns the relation and keeps labelling the vehicle's network target EXACT.
     */
    @Test void defectTheVehicleTheObserverRidesIsReportedExact() {
        var t = new CombatCorpus.Trace(A);
        t.spawn(20, "minecraft:horse", 1.5, 64, 2.5);
        t.out(new PacketPayload.Passengers(20, List.of(SELF)));
        t.move(20, 8192, 0, 0);
        var c = t.attack(20);
        assertFalse(exactUnmounted(c), "the vehicle the observer rides is client-authoritative; its network target is not the client's position: " + c.describe());
    }

    // ---- QA-F3: a legal 65..256-passenger packet is an internal error that taints every live entity --------------------------------------------
    @Test void defectAPassengerListAboveSixtyFourIsAnInternalError() {
        var t = new CombatCorpus.Trace(A);
        t.zombie(10); t.spawn(20, "minecraft:horse", 5, 64, 5);
        List<Integer> riders = new ArrayList<>(); for (int i = 0; i < 65; i++) riders.add(100 + i);   // PacketPayload.Passengers admits 256
        t.out(new PacketPayload.Passengers(20, riders));
        var d = t.runner.diagnostics();
        assertEquals(0, d.internalErrors(), "a protocol-valid passenger list became an internal error: " + d.lastError());
        assertEquals(Status.FOUND, t.lookup(10, t.seq + 1).status(), "an unrelated entity lost exactness");
    }

    // ---- QA-F4: a serverbound ATTACK carried by a degraded (break-only) observation vanishes -------------------------------------------------------
    /**
     * PacketEngine passes breakOnly=true for every degraded observation whose latch reason is world-relevant, whatever its direction. For a serverbound
     * ATTACK the packet itself was captured intact: it must still be counted and get a context (incomplete), never silently disappear.
     */
    @Test void defectAnAttackOnABreakOnlyObservationIsNeverCounted() {
        var t = new CombatCorpus.Trace(A);
        t.zombie(10);
        t.seq++; t.nanos += CombatCorpus.TICK; t.attempts++;
        var o = new Observation(A, t.seq, t.nanos, 0, new Observation.Attempt(t.attempts, t.attempts, new SemanticPayload.Attack(10)));
        t.runner.apply(o, t.nanos, SELF, 0, "INGRESS_DROPPED", true);
        var s = t.runner.engine().status();
        assertEquals(1, s.attacks() + s.rejected(), "a captured ATTACK packet must be counted (as an incomplete context or an explicit refusal), never dropped silently");
    }

    // ---- QA-F5: the history's horizon runs on the shard processing clock; the Phase 11 replay path feeds the capture clock ------------------------
    static CombatRunner replay(List<Observation> log, ToLongFunction<Observation> now) {
        var r = new CombatRunner(A, CombatProfile(), CombatCorpus.world(A), new PlayerTimeline(A), () -> null, () -> null, new CombatEngine.Metrics());
        for (Observation o : log) r.apply(o, now.applyAsLong(o), SELF, o.context().worldEpoch(), null, false);
        return r;
    }
    static me.nicolas.papea2ac.version.CombatProfile CombatProfile() { return me.nicolas.papea2ac.version.CombatProfile.PAPER_26_2; }
    /** Same observations, two processing schedules (keeping up vs one late burst after a shard backlog): outputs must not depend on the schedule. */
    @Test void defectEntityHistoryDependsOnShardProcessingTimeNotOnTheObservations() {
        var t = new CombatCorpus.Trace(A);
        t.zombie(10); for (int i = 0; i < 80; i++) t.move(10, 1, 0, 0);        // 4 s of capture time, 81 revisions (inside the 96-revision ring)
        var key = t.lookup(10, t.seq + 1).key();
        var keepingUp = replay(t.log, Observation::capturedNanos);
        long burst = t.nanos + 60_000_000_000L; var backlog = replay(t.log, o -> burst + o.sequence());
        var r1 = keepingUp.entities().revisions(key, 1, t.seq + 1, 96).status(); var r2 = backlog.entities().revisions(key, 1, t.seq + 1, 96).status();
        assertEquals(r1, r2, "the same observations answer a range query differently depending on when the shard processed them");
        assertEquals(EntityReplayMapper.digest(EntityReplayMapper.export(keepingUp.entities())), EntityReplayMapper.digest(EntityReplayMapper.export(backlog.entities())),
            "the EntityCapsule projection depends on processing time");
    }

    // ---- QA-F6: lookupKey says DESPAWNED ("id free") across a coverage break; lookupId says INCOMPLETE_COVERAGE for the same question --------------
    @Test void defectKeyLookupClaimsTheIdFreeAcrossACoverageBreak() {
        var r = new CombatTestSupport.Rig();
        r.spawn(7, 0, 64, 0); var k = r.now(7).key(); r.remove(7);
        r.gap(); r.silent();                                              // the lost observation may have been a respawn of id 7
        assertEquals(Status.INCOMPLETE_COVERAGE, r.at(7, r.seq + 1).status());
        assertNotEquals(Status.DESPAWNED, r.h.lookupKey(k, r.seq + 1).status(), "DESPAWNED asserts the id was free; after a loss that is unprovable");
    }
    /** A key whose (networkId, incarnation) pair was never issued is not 'retained once': EVICTED claims a past that never existed. */
    @Test void defectANeverIssuedKeyAnswersEvicted() {
        var r = new CombatTestSupport.Rig();
        r.spawn(7, 0, 64, 0); var k = r.now(7).key();
        var forged = new EntityKey(k.session(), k.worldEpoch(), 99, k.incarnation());
        assertEquals(Status.UNKNOWN, r.h.lookupKey(forged, r.seq + 1).status());
    }

    // ---- QA-F7: the runner applies an observation of another session as its own ----------------------------------------------------------------
    @Test void defectARunnerAppliesAnotherSessionsObservation() {
        var t = new CombatCorpus.Trace(A);
        t.seq++; t.nanos += CombatCorpus.TICK;
        var foreign = new Observation(B, t.seq, t.nanos, 0, new Observation.Outbound(++t.wire, CombatCorpus.spawnPayload(10, "minecraft:zombie", 0, 64, 0)));
        t.runner.apply(foreign, t.nanos, SELF, 0, null, false);
        assertNotEquals(Status.FOUND, t.lookup(10, t.seq + 1).status(), "session B's spawn became session A's target state");
    }

    // ---- adversarial coverage that holds -------------------------------------------------------------------------------------------------------
    /** Spawn, replace, remove and respawn of ONE id inside one bundle: the attack sees only the last incarnation; every earlier key is ID_REUSED. */
    @Test void reuseInsideOneBundleNeverResolvesToAnEarlierIncarnation() {
        var t = new CombatCorpus.Trace(A);
        t.spawn(7, "minecraft:zombie", 0, 64, 0); var k0 = t.lookup(7, t.seq + 1).key();
        t.out(new PacketPayload.Bundle(List.of(CombatCorpus.spawnPayload(7, "minecraft:cow", 1, 64, 1), new SemanticPayload.RemoveEntities(List.of(7)),
            CombatCorpus.spawnPayload(7, "minecraft:pig", 2, 64, 2))));
        var c = t.attack(7);
        assertEquals(Status.FOUND, c.target().status()); assertEquals("minecraft:pig", c.target().type()); assertEquals(k0.incarnation() + 2, c.target().key().incarnation());
        assertEquals(Status.ID_REUSED, t.now(k0).status());
        assertEquals(Status.ID_REUSED, t.now(new EntityKey(A, 0, 7, k0.incarnation() + 1)).status());
        assertEquals(3, t.h().status().generations());
    }
    /** Old incarnations of one id forgotten by capacity: a lookup inside a forgotten life is EVICTED, never UNKNOWN / PRE_SPAWN / a later incarnation. */
    @Test void aForgottenLifeOfAReusedIdIsEvictedNeverALaterIncarnation() {
        var r = new CombatTestSupport.Rig();
        r.spawn(7, 0, 64, 0); long inFirstLife = r.silent(); r.remove(7);
        for (int i = 0; i < 300; i++) { r.spawn(1000 + i, i, 64, 0); r.remove(1000 + i); }
        r.spawn(7, 5, 64, 5);
        var l = r.at(7, inFirstLife + 1);
        assertEquals(Status.EVICTED, l.status(), l.describe());
        assertEquals(Status.FOUND, r.now(7).status()); assertEquals(5.0, r.now(7).revision().x());
    }
    @Test void closedSessionsAnswerNothingAndAcceptNothing() {
        var r = new CombatTestSupport.Rig();
        r.spawn(7, 0, 64, 0); var k = r.now(7).key(); r.h.close();
        assertFalse(r.h.apply(CombatTestSupport.KEY, new EntityObservation.Spawned(8, new UUID(8, 8), "minecraft:zombie", 0, 64, 0, 0f, 0f, 0f), r.seq + 1, 0, r.nanos));
        assertNotEquals(Status.FOUND, r.h.lookupKey(k, r.seq).status()); assertNotEquals(Status.FOUND, r.h.lookupId(CombatTestSupport.KEY, 0, 7, r.seq).status());
    }

    // ---- lifecycle fuzz with an independent identity shadow ------------------------------------------------------------------------------------
    /** Independent shadow of identity only: which incarnation number the client holds for each id, and whether a loss happened since its spawn. */
    static final class Shadow {
        long nextInc = 1; final Map<Integer, Long> live = new HashMap<>(); final Set<Integer> tainted = new HashSet<>(); final Set<Integer> passengersOf = new HashSet<>();
        void spawn(int id) { live.put(id, nextInc++); tainted.remove(id); }
        void remove(int id) { live.remove(id); }
        void epoch() { live.clear(); tainted.clear(); }
        void loss() { tainted.addAll(live.keySet()); }
    }
    static String run(long seed, List<String> violations) {
        var t = new CombatCorpus.Trace(A); var sh = new Shadow(); SplittableRandom r = new SplittableRandom(seed); long epoch = 0;
        for (int step = 0; step < 1200; step++) {
            int id = 2 + r.nextInt(40), op = r.nextInt(100);
            if (op < 14) { t.spawn(id, r.nextBoolean() ? "minecraft:player" : "minecraft:zombie", r.nextInt(-64, 64) + r.nextDouble(), 64, r.nextInt(-64, 64)); sh.spawn(id); }
            else if (op < 40) t.move(id, r.nextInt(-32768, 32768), r.nextInt(-200, 200), r.nextInt(-32768, 32768));
            else if (op < 46) t.rot(id, r.nextInt(-180, 180), r.nextInt(-90, 90));
            else if (op < 50) t.sync(id, r.nextInt(-64, 64) + 0.25, 64, 0.5);
            else if (op < 53) t.teleport(id, r.nextInt(-64, 64), 64, 3, r.nextBoolean() ? Set.of() : Set.of(SemanticPayload.Relative.X));
            else if (op < 58) { t.remove(id); sh.remove(id); }
            else if (op < 61) t.data(id, new SemanticPayload.EntityDataField(6, "POSE", r.nextBoolean() ? "CROUCHING" : "NOT_A_POSE"), new SemanticPayload.EntityDataField(0, "BYTE", Integer.toString(r.nextInt(-128, 128))));
            else if (op < 64) { int v = 2 + r.nextInt(40); t.out(new PacketPayload.Passengers(v, r.nextBoolean() ? List.of(id) : List.of())); }
            else if (op < 66) { // a bundle that reuses the id twice
                t.out(new PacketPayload.Bundle(List.of(new SemanticPayload.RemoveEntities(List.of(id)), CombatCorpus.spawnPayload(id, "minecraft:cow", 0, 64, 0), CombatCorpus.spawnPayload(id, "minecraft:pig", 1, 64, 1))));
                sh.remove(id); sh.spawn(id); sh.spawn(id);
            }
            else if (op < 67) { t.gap(); t.tick(); sh.loss(); }
            else if (op < 68) { epoch++; t.epoch(epoch); sh.epoch(); }
            else if (op < 69) t.out(new SemanticPayload.MinecartMove(id, List.of()));
            else if (op < 70) t.out(new SemanticPayload.HeadRotation(id, r.nextInt(-180, 180)));
            else if (op < 80) t.tick();
            else if (op < 92) {
                var c = t.attack(id);
                if (c == null || c.observation().sequence() != t.seq) { violations.add(seed + "/" + step + " attack produced no context"); continue; }
                var l = c.target();
                if (l.found()) {
                    if (l.key().networkId() != id) violations.add(seed + "/" + step + " cross-id " + l.describe());
                    if (l.key().worldEpoch() != epoch || c.worldEpoch() != epoch) violations.add(seed + "/" + step + " cross-epoch " + l.describe());
                    if (!l.key().session().equals(A)) violations.add(seed + "/" + step + " cross-session");
                    Long inc = sh.live.get(id);
                    if (inc == null) violations.add(seed + "/" + step + " FOUND for an id the client does not hold " + l.describe());
                    else if (inc != l.key().incarnation()) violations.add(seed + "/" + step + " wrong generation " + l.key().incarnation() + " vs " + inc);
                    if (sh.tainted.contains(id)) violations.add(seed + "/" + step + " FOUND across a loss " + l.describe());
                }
                if (c.completeness().status() == CombatCompleteness.Status.COMPLETE) violations.add(seed + "/" + step + " COMPLETE without attacker/timing provenance");
                if (!c.completeness().standing().contains(CombatCompleteness.Standing.SERVER_OUTCOME_UNSUPPORTED)) violations.add(seed + "/" + step + " acceptance implied");
            }
            else { t.interact(id, r.nextBoolean()); }
            var s = t.h().status();
            if (s.live() + s.ended() > EntityHistory.MAX_RETAINED || s.deep() > EntityHistory.MAX_DEEP) violations.add(seed + "/" + step + " bound " + s);
            if (t.runner.diagnostics().internalErrors() > 0) { violations.add(seed + "/" + step + " internal error " + t.runner.diagnostics().lastError()); break; }
        }
        return CombatReplayMapper.digest(CombatReplayMapper.export(t.runner.engine(), CombatProfile().id())) + "|" + EntityReplayMapper.digest(EntityReplayMapper.export(t.h()));
    }
    /**
     * Identity-only fuzz with every lifecycle kind (incl. passengers, metadata, unsupported motion, in-bundle reuse, losses, epochs): no cross-id / epoch /
     * session leak, the generation is always the one the client holds, nothing FOUND across an unhealed loss, no COMPLETE without provenance, bounds
     * held, no internal error, and the whole run is identical twice.
     */
    @Test void lifecycleFuzzKeepsIdentityAndIsDeterministic() {
        List<String> violations = new ArrayList<>();
        for (long seed = 1; seed <= 30; seed++) {
            String first = run(seed, violations), second = run(seed, new ArrayList<>());
            if (!first.equals(second)) violations.add("seed " + seed + " not deterministic");
        }
        assertTrue(violations.isEmpty(), violations.size() + " violations, first: " + violations.subList(0, Math.min(10, violations.size())));
    }
}
