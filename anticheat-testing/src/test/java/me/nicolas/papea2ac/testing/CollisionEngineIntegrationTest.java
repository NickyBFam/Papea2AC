package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static me.nicolas.papea2ac.testing.CollisionTestSupport.*;
import static me.nicolas.papea2ac.testing.WorldTestSupport.*;
import java.util.*;
import me.nicolas.papea2ac.collision.*;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.packet.*;
import me.nicolas.papea2ac.protocol.*;

/**
 * Phase 1 -> 3 -> 4 through the real engine: clientbound observations enter the single ordered ingress, the shard
 * reduces them into the observer world, and the collision readout (the /papea2ac collision projection) answers
 * against the PUBLISHED snapshot at the PUBLISHED observed position - two explicit poses, geometry only, cross-thread.
 */
class CollisionEngineIntegrationTest {
    @Test void collisionStatusReflectsObservedGeometryAtTheObservedPositionForBothPoses() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> fail(e)); var loop = new PacketTruthTest.Loop(); var player = new UUID(0x4C, 0x11);
        try {
            var session = engine.connect(player, loop, TransportCapability.observeOnly());
            long now = System.nanoTime();
            session.ingress.capture(new Observation.Outbound(1, ObserverWorldIntegrationTest.JOIN), now);
            session.ingress.capture(new Observation.Outbound(2, ObserverWorldIntegrationTest.wireBaseline(0, 0)), now);
            session.ingress.capture(new Observation.Outbound(3, new SemanticPayload.BlockChange(new SemanticPayload.BlockPosition(4, 71, 4), ObserverWorldIntegrationTest.wire(SLAB_TOP))), now); // a top slab at head height (71.5..72), one block off
            session.ingress.capture(new Observation.Attempt(1, 1, new PacketPayload.Movement(new PacketPayload.Position(4.5, 70, 5.5), null, true, false)), now);
            session.ingress.capture(new Observation.Tick(1), now);
            ObserverWorldIntegrationTest.await(() -> session.world.snapshot().lastSequence() == 5 && session.timeline.snapshot().lastCommittedSequence() == 5);
            var status = engine.collisionStatus(player).orElseThrow();
            assertTrue(status.hasPosition()); assertEquals(4.5, status.x()); assertEquals(70.0, status.y()); assertEquals(5.5, status.z()); assertEquals(0, status.worldEpoch()); assertEquals(5, status.worldSequence()); assertEquals("paper-26.2-124-collision", status.profile());
            assertEquals(2, status.poses().size()); assertEquals("STANDING", status.poses().get(0).pose()); assertEquals("CROUCHING", status.poses().get(1).pose());
            for (var p : status.poses()) { assertEquals("COMPLETE", p.certainty(), p.pose() + " " + p.statusCounts()); assertTrue(p.definitelyClear(), "standing beside (not under) the slab: clear"); assertEquals(0, p.hits()); assertTrue(p.cellsVisited() > 0); assertFalse(p.truncated()); }
            // move under the slab: the standing box (1.8 high, to 71.8) overlaps the top slab at 71.5..72; the crouching box (1.5, to 71.5) only touches it
            session.ingress.capture(new Observation.Attempt(2, 2, new PacketPayload.Movement(new PacketPayload.Position(4.5, 70, 4.5), null, true, false)), now);
            session.ingress.capture(new Observation.Tick(2), now);
            ObserverWorldIntegrationTest.await(() -> session.timeline.snapshot().lastCommittedSequence() == 7);
            var under = engine.collisionStatus(player).orElseThrow();
            var standing = under.poses().get(0); assertTrue(standing.definitelyObstructed()); assertEquals(1, standing.hits()); assertEquals(1, standing.boxes()); assertTrue(standing.firstBoxes().get(0).contains("EXACT @4,71,4 [4.0,71.5,4.0 -> 5.0,72.0,5.0]"), standing.firstBoxes().get(0));
            var crouching = under.poses().get(1); assertTrue(crouching.definitelyClear(), "1.5 high: fits under the slab"); assertEquals(0, crouching.hits());
            var es = engine.collisionEngineStatus(); assertEquals(4, es.queries()); assertEquals(4, es.complete()); assertEquals(32366, es.tableStates()); assertEquals(1196, es.tableBlocks()); assertEquals(PROFILE.table().digest(), es.tableDigest()); assertTrue(es.shapeCacheHits() > 0);
            assertTrue(engine.collisionStatus(new UUID(9, 9)).isEmpty(), "unknown player: empty, never a fabricated default");
        } finally { engine.close(); loop.drain(); }
    }
    @Test void unknownWorldRegionIsReportedAsUncertainNeverClearThroughTheEngine() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> fail(e)); var loop = new PacketTruthTest.Loop(); var player = new UUID(0x4C, 0x12);
        try {
            var session = engine.connect(player, loop, TransportCapability.observeOnly());
            long now = System.nanoTime();
            session.ingress.capture(new Observation.Outbound(1, ObserverWorldIntegrationTest.JOIN), now);
            session.ingress.capture(new Observation.Attempt(1, 1, new PacketPayload.Movement(new PacketPayload.Position(100.5, 70, 100.5), null, true, false)), now); // no chunk was ever sent for this position
            session.ingress.capture(new Observation.Tick(1), now);
            ObserverWorldIntegrationTest.await(() -> session.timeline.snapshot().lastCommittedSequence() == 3);
            var status = engine.collisionStatus(player).orElseThrow();
            for (var p : status.poses()) { assertEquals("INCOMPLETE", p.certainty()); assertFalse(p.definitelyClear(), "UNKNOWN != AIR: the readout never claims a clear region it has not observed"); assertFalse(p.definitelyObstructed()); assertTrue(p.statusCounts().contains("UNKNOWN_CHUNK="), p.statusCounts()); }
            // a second session for another player is isolated: its own (missing) world, its own position
            var other = engine.connect(new UUID(0x4C, 0x13), loop, TransportCapability.observeOnly());
            assertTrue(engine.collisionStatus(new UUID(0x4C, 0x13)).orElseThrow().poses().isEmpty(), "no observed position yet: no pose is evaluated and none is assumed");
            assertNotSame(session.world, other.world);
        } finally { engine.close(); loop.drain(); }
    }
    @Test void engineCloseReleasesSessionsAndLeavesTheEngineWideAccountingAtBaseline() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> fail(e)); var loop = new PacketTruthTest.Loop();
        try {
            var session = engine.connect(new UUID(0x4C, 0x14), loop, TransportCapability.observeOnly()); long now = System.nanoTime();
            session.ingress.capture(new Observation.Outbound(1, ObserverWorldIntegrationTest.JOIN), now); session.ingress.capture(new Observation.Outbound(2, ObserverWorldIntegrationTest.wireBaseline(0, 0)), now); session.ingress.capture(new Observation.Tick(1), now);
            ObserverWorldIntegrationTest.await(() -> session.world.snapshot().lastSequence() == 3);
            assertTrue(engine.worldBudgetSnapshot().sharedBytes() > 0);
        } finally { engine.close(); loop.drain(); }
        ObserverWorldIntegrationTest.await(() -> engine.terminated());
        ObserverWorldIntegrationTest.await(() -> engine.worldBudgetSnapshot().observerWorlds() == 0 && engine.worldBudgetSnapshot().sharedBytes() == 0);
        assertEquals(0, engine.worldBudgetSnapshot().observerWorlds()); assertEquals(0, engine.worldBudgetSnapshot().sharedBytes(), "collision holds no world references: accounting returns to the pre-session baseline");
    }
}
