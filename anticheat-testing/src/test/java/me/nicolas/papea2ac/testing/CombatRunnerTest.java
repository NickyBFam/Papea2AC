package me.nicolas.papea2ac.testing;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.combat.CombatEngine;
import me.nicolas.papea2ac.core.CombatRunner;
import me.nicolas.papea2ac.core.ValidationStatus;
import me.nicolas.papea2ac.player.PlayerTimeline;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.world.EntityLookup.Status;
import org.junit.jupiter.api.Test;

/**
 * The core Phase 11 composition: the world's coverage truth reaches the entity history (break at an applied observation, break-only observations),
 * internal failures are contained and taint coverage, diagnostics are immutable values rendered off the shard (P11-F8: the entity snapshot refreshes
 * every {@value CombatRunner#SNAPSHOT_TICKS} ticks, attack contexts are live), and the diagnostic text is descriptive only.
 */
class CombatRunnerTest {
    static final java.util.regex.Pattern FORBIDDEN = java.util.regex.Pattern.compile("(?i)cheat|reach hack|killaura|\\bban\\b|punish|confidence|score|verdict");

    @Test void attackContextsAreLiveWhileTheEntitySnapshotRefreshesOncePerSecond() {
        var t = new CombatCorpus.Trace(CombatCorpus.KEY_A); t.zombie(10);
        assertEquals(0, t.runner.diagnostics().entities().live(), "no snapshot yet: spawning does not publish (P11-F8)");
        var c = t.attack(10);
        assertEquals(c, t.runner.diagnostics().combat().lastAttack(), "the attack context is visible immediately (engine status)");
        t.ticks(CombatRunner.SNAPSHOT_TICKS - 1); assertEquals(0, t.runner.diagnostics().entities().live());
        t.tick(); var d = t.runner.diagnostics();
        assertEquals(1, d.entities().live(), "refreshed at the " + CombatRunner.SNAPSHOT_TICKS + "th tick"); assertEquals(1, d.liveEntities().size()); assertEquals(0, d.moreLive());
        for (int id = 20; id < 60; id++) t.zombie(id); t.ticks(CombatRunner.SNAPSHOT_TICKS);
        d = t.runner.diagnostics(); assertEquals(CombatRunner.LIVE_LINES, d.liveEntities().size(), "bounded snapshot"); assertEquals(41 - CombatRunner.LIVE_LINES, d.moreLive());
        List<String> lines = CombatRunner.lines(d, true);
        assertTrue(lines.stream().anyMatch(l -> l.contains("A1:2 target#10 -> FOUND #10/i1@e0")), String.join("\n", lines));
        assertTrue(lines.stream().anyMatch(l -> l.contains("... " + (41 - CombatRunner.LIVE_LINES) + " more")));
        for (String l : lines) { assertFalse(FORBIDDEN.matcher(l).find(), l); assertFalse(l.contains(CombatCorpus.KEY_A.player().toString()), "no UUID in diagnostics: " + l); }
    }
    @Test void worldCoverageBreaksReachTheEntityHistory() {
        var t = new CombatCorpus.Trace(CombatCorpus.KEY_A); t.zombie(10);
        // a loss-class break reported by the world at an applied observation: the observation itself still applies after the break (sub-index order)
        t.seq++; t.nanos += CombatCorpus.TICK;
        var spawn11 = new Observation(t.key, t.seq, t.nanos, 0, new Observation.Outbound(++t.wire, CombatCorpus.spawnPayload(11, "minecraft:zombie", 3, 64, 3)));
        t.runner.apply(spawn11, t.nanos, CombatCorpus.SELF, 0, "OVERLOADED", false);
        assertEquals(Status.INCOMPLETE_COVERAGE, t.lookup(10, t.seq + 1).status(), "tainted by the break"); assertEquals(Status.FOUND, t.lookup(11, t.seq + 1).status(), "applied after the break");
        // break-only: the observation was not applied by the world, so neither here
        t.seq++; t.nanos += CombatCorpus.TICK;
        t.runner.apply(new Observation(t.key, t.seq, t.nanos, 0, new Observation.Outbound(++t.wire, CombatCorpus.spawnPayload(12, "minecraft:zombie", 3, 64, 3))), t.nanos, CombatCorpus.SELF, 0, "SHARD_FAILED", true);
        assertNotEquals(Status.FOUND, t.lookup(12, t.seq + 1).status(), "a break-only observation is not applied");
        assertEquals(Status.INCOMPLETE_COVERAGE, t.lookup(11, t.seq + 1).status());
    }
    /** The engine-wide status line (live qualification counts): a replaced session's lifecycle counters are folded in exactly once and never double counted with its snapshot. */
    @Test void theEngineWideCombatLineCountsLiveAndClosedSessionsOnce() throws Exception {
        var engine = new me.nicolas.papea2ac.core.PacketEngine(1, 1L << 30, e -> {}); var loop = new PacketTruthTest.Loop();
        try {
            UUID u = new UUID(91, 1); long[] w = { 1000 }, n = { 100 };
            var first = engine.connect(u, loop, me.nicolas.papea2ac.packet.TransportCapability.observeOnly());
            first.ingress.bridge(new Observation.ControlChange("SELF_ENTITY", "42"));
            for (PacketPayload p : List.of(CombatCorpus.spawnPayload(10, "minecraft:zombie", 0, 64, 0), CombatCorpus.spawnPayload(11, "minecraft:cow", 2, 64, 2), new SemanticPayload.RemoveEntities(List.of(11)))) {
                first.ingress.bridge(new Observation.Outbound(++w[0], p)); first.ingress.bridge(new Observation.WriteOutcome(w[0], true));
            }
            first.ingress.bridge(ValidationEngineIntegrationTest.in(n, new SemanticPayload.Attack(10)));
            first.ingress.bridge(ValidationEngineIntegrationTest.in(n, new SemanticPayload.Interact(10, SemanticPayload.Hand.MAIN_HAND, new PacketPayload.Position(0, 1, 0), false)));
            for (int t = 1; t <= CombatRunner.SNAPSHOT_TICKS; t++) first.ingress.bridge(new Observation.Tick(t));
            TimingMarkerSchedulingTest.await(loop, 3000, () -> engine.combatStatusLine().contains("generations=2"));
            String line = engine.combatStatusLine();
            for (String s : List.of("attacks=1", "interactions=1", "targetFound=1", "targetNotFound=0", "internalErrors=0", "byTarget{FOUND=1}", "removals=1", "epochResets=0", "sessions{live=1 closed=0}"))
                assertTrue(line.contains(s), s + " in " + line);
            engine.connect(u, loop, me.nicolas.papea2ac.packet.TransportCapability.observeOnly()); // the replacement closes the first session
            TimingMarkerSchedulingTest.await(loop, 3000, () -> engine.combatStatusLine().contains("closed=1"));
            line = engine.combatStatusLine();
            for (String s : List.of("attacks=1", "generations=2", "removals=1", "sessions{live=1 closed=1}")) assertTrue(line.contains(s), "folded once, not double counted: " + s + " in " + line);
            assertFalse(line.contains(u.toString())); assertFalse(FORBIDDEN.matcher(line).find(), line);
        } finally { engine.close(); loop.drain(); }
    }
    @Test void anInternalFailureIsContainedCountedAndTaintsCoverage() {
        var metrics = new CombatEngine.Metrics(); var key = CombatCorpus.KEY_A;
        java.util.function.Supplier<ValidationStatus> failing = () -> { throw new IllegalStateException("injected"); };
        var runner = new CombatRunner(key, CombatCorpus.P, CombatCorpus.world(key), new PlayerTimeline(key), () -> null, failing, metrics);
        runner.apply(new Observation(key, 1, 1, 0, new Observation.Outbound(1, CombatCorpus.spawnPayload(10, "minecraft:zombie", 0, 64, 0))), 1, 1, 0, null, false);
        runner.apply(new Observation(key, 2, 2, 0, new Observation.Attempt(1, 1, new SemanticPayload.Attack(10))), 2, 1, 0, null, false);
        var d = runner.diagnostics(); assertEquals(1, d.internalErrors()); assertTrue(d.lastError().startsWith("IllegalStateException: injected"), d.lastError()); assertEquals(1, metrics.internalErrors.sum());
        assertEquals(Status.INCOMPLETE_COVERAGE, runner.entities().lookupId(key, 0, 10, 3).status(), "never an exact answer after an internal error");
        runner.apply(new Observation(key, 3, 3, 0, new Observation.Tick(1)), 3, 1, 0, null, false); // the session keeps running
        assertEquals(3, runner.entities().lastSequence());
    }
}
