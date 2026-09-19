package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static me.nicolas.papea2ac.testing.WorldTestSupport.*;
import java.util.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.packet.*;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.player.*;
import me.nicolas.papea2ac.world.*;
import me.nicolas.papea2ac.world.BlockLookup.Status;

/**
 * Section 56 / section 44: Phase 2 (player timeline) and Phase 3 (observer world) share ONE ordering key - the
 * session's capture sequence assigned by the ordered ingress - so a historical player state at sequence S pairs
 * with exactly the world state observed through S. No collision, physics or check logic is invoked anywhere here.
 *
 * Script: world chunk baseline at A, player movement at B, block update at C, player movement at D.
 */
class PlayerWorldCoherenceTest {
    static final SessionKey KEY = new SessionKey(new UUID(56, 1), 1);
    static final BlockPos CELL = new BlockPos(4, 70, 4);

    @Test void historicalPlayerStatesPairWithTheWorldObservedThroughTheSameSequence() {
        var dict = new BlockStateDictionary(); var budget = new WorldBudget();
        var timeline = new PlayerTimeline(KEY); var world = new ObserverWorld(KEY, budget, dict);
        long seq = 0;
        // Both consumers see every session event in order (PacketEngine.Shard.run() feeds them from the same Observation).
        seq++; world.apply(KEY, OVERWORLD, seq, seq, null);
        final long a = ++seq; world.apply(KEY, baseline(0, 0), a, a, null);
        final long b = ++seq; timeline.append(KEY, new PlayerObservation.MovementClaim(Optional.of(new Vector3(4.5, 70, 4.5)), Optional.empty(), true, false), b, b); world.observeSequence(KEY, b, b); // the shard marks world-silent sequences
        final long c = ++seq; world.apply(KEY, new WorldObservation.BlockChanged(CELL, STAIRS), c, c, null);
        final long d = ++seq; timeline.append(KEY, new PlayerObservation.MovementClaim(Optional.of(new Vector3(5.5, 70, 4.5)), Optional.empty(), true, false), d, d); world.observeSequence(KEY, d, d);

        // player(B) pairs with the world through B: the baseline's air, before the update at C
        var atB = assertInstanceOf(TimelineLookup.Found.class, timeline.stateAtSequence(b));
        assertEquals(new Vector3(4.5, 70, 4.5), atB.record().resultingState().movement().position());
        var worldAtB = world.blockAtSequence(CELL, b);
        assertEquals(Status.KNOWN, worldAtB.status()); assertEquals(AIR, worldAtB.state(), "at B the stairs had not been sent yet");
        assertTrue(atB.record().captureSequence() < c, "B strictly precedes the block update");

        // player(D) pairs with the updated world through D
        var atD = assertInstanceOf(TimelineLookup.Found.class, timeline.stateAtSequence(d));
        assertEquals(new Vector3(5.5, 70, 4.5), atD.record().resultingState().movement().position());
        assertEquals(STAIRS, world.blockAtSequence(CELL, d).state()); assertEquals(STAIRS, world.blockAt(CELL).state());

        // the exact sequence of the update itself already sees the update (sequence semantics are "through S", inclusive)
        assertEquals(STAIRS, world.blockAtSequence(CELL, c).state()); assertEquals(AIR, world.blockAtSequence(CELL, c - 1).state());
        // and the player timeline at the world's own sequences reports what it honestly has: no record was appended AT a or c
        assertFalse(timeline.stateAtSequence(c) instanceof TimelineLookup.Found, "no player record exists exactly at C (world-only sequence)");
        assertEquals(new Vector3(4.5, 70, 4.5), assertInstanceOf(TimelineLookup.Found.class, timeline.stateBefore(c)).record().resultingState().movement().position(), "the player state in force when C was observed");
        // nothing is claimed about a sequence neither side has reached
        assertEquals(Status.FUTURE_SEQUENCE, world.blockAtSequence(CELL, d + 1).status()); assertInstanceOf(TimelineLookup.NeverExisted.class, timeline.stateAtSequence(d + 1));
    }

    @Test void throughTheRealEngineTheSameSequenceCounterOrdersPlayerAndWorldEvents() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> fail(e));
        var loop = new PacketTruthTest.Loop();
        var player = new UUID(56, 2);
        try {
            var session = engine.connect(player, loop, TransportCapability.observeOnly());
            long now = System.nanoTime(); // attempts are certified against the actor's clock: a stale capture time reads as EXPIRED
            session.ingress.capture(new Observation.Outbound(1, ObserverWorldIntegrationTest.JOIN), now);
            session.ingress.capture(new Observation.Outbound(2, ObserverWorldIntegrationTest.wireBaseline(0, 0)), now);                                            // A = 2
            session.ingress.capture(new Observation.Attempt(1, 1, new PacketPayload.Movement(new PacketPayload.Position(4.5, 70, 4.5), null, true, false)), now); // B = 3
            session.ingress.capture(new Observation.Outbound(3, new SemanticPayload.BlockChange(new SemanticPayload.BlockPosition(4, 70, 4), ObserverWorldIntegrationTest.wire(STAIRS))), now); // C = 4
            session.ingress.capture(new Observation.Attempt(2, 2, new PacketPayload.Movement(new PacketPayload.Position(5.5, 70, 4.5), null, true, false)), now); // D = 5
            session.ingress.capture(new Observation.Tick(1), now);                                                                                                    // 6
            ObserverWorldIntegrationTest.await(() -> session.world.snapshot().lastSequence() == 6 && session.timeline.snapshot().lastCommittedSequence() == 6);
            var records = session.timeline.snapshot(); var world = session.world.snapshot();
            assertEquals(new Vector3(5.5, 70, 4.5), records.state().movement().position());
            assertEquals(STAIRS, world.blockAt(CELL, engine.blockStates).state(), () -> "lookup=" + world.blockAt(CELL, engine.blockStates) + " chunks=" + world.chunkCount() + " transport=" + world.transportBroken() + "/" + world.transportBreakReason() + " rev=" + world.revision());
            // the world's last structural change (C=4) and the player's last movement (D=5) are ordered by the same counter
            assertEquals(4, world.chunks().get(new ChunkKey(0, 0)).sequence(), "the chunk's last change is the block update at C");
            assertEquals(5, records.state().movement().observedAtSequence(), "the movement in force is D, observed after C");
            assertTrue(world.chunks().get(new ChunkKey(0, 0)).baselineSequence() < records.state().movement().observedAtSequence());
        } finally { engine.close(); loop.drain(); }
    }
}
