package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.core.BlockDisputeLedger;
import me.nicolas.papea2ac.core.ValidationStatus;
import me.nicolas.papea2ac.model.BlockStateKey;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.ContextEvent;
import me.nicolas.papea2ac.protocol.SemanticPayload;
import org.junit.jupiter.api.Test;

/**
 * P9-F2 regression (found by the Phase 9 real-client qualification; originating component: Phase 3/8 world knowledge, not Phase 5).
 *
 * <p>A block state is not one fact shared by client and observer. The live failure: the owner's client toggled an oak trapdoor
 * ({@code facing=east, half=bottom}) and stepped onto it in the same moment. The client applied its own predicted state immediately
 * (closed: a 3/16 slab it steps onto, rising exactly 0.1875) while this observer still held the previous state (open: shape
 * {@code [0,0,0, 0.1875,1,1]}, a full-height slab on the cell's west face). Every candidate was therefore blocked horizontally
 * ({@code hColl=true}, x pinned) and the observed position penetrated the model's collider - a complete INVALID
 * [NO_LEGAL_INPUT, COLLISION_PENETRATION] on a legitimate player, which Phase 9 faithfully turned into one positive evidence event.
 *
 * <p>The fix is a named missing dimension, never a tolerance: while a cell is in dispute (a client prediction not yet acknowledged, or a
 * change the client cannot have applied yet) the WORLD dimension of any transition whose reach touches it is incomplete. An explained
 * transition stays VALID; an unexplained one is UNCERTAIN [WORLD_INCOMPLETE]; INVALID is impossible.
 */
class BlockDisputeTest {
    static final SessionKey KEY = new SessionKey(new UUID(9, 2), 1);
    static final BlockStateKey TRAPDOOR_CLOSED = BlockStateKey.parse("minecraft:oak_trapdoor[facing=east,half=bottom,open=false,powered=false,waterlogged=false]");
    static final BlockStateKey TRAPDOOR_OPEN = BlockStateKey.parse("minecraft:oak_trapdoor[facing=east,half=bottom,open=true,powered=false,waterlogged=false]");
    static final SemanticPayload.BlockPosition CELL = new SemanticPayload.BlockPosition(9, 70, 8);

    /** The live geometry: the player rests one cell west of the trapdoor and sprints east onto it. */
    static MovementValidationRunnerTest.Harness scene(BlockStateKey observerState) {
        PhysicsScene ps = new PhysicsScene();
        ps.set(9, 70, 8, observerState);
        MovementValidationRunnerTest.Harness h = new MovementValidationRunnerTest.Harness(ps);
        h.ctx(new ContextEvent.GameModeChanged(1, "survival"));
        return h;
    }
    /** Rest-anchors the chain at (8.7, SURFACE, 8.5) and then feeds the client's real motion, which is simulated in the CLIENT's world. */
    static ValidationStatus run(MovementValidationRunnerTest.Harness h, PhysicsScene clientWorld, boolean dispute) {
        PhysicsState rest = settled(clientWorld, 8.7, SURFACE, 8.5);
        h.rec.tickEnd(); h.rec.advance(TICK_NANOS); h.rec.movement(8.7, SURFACE, 8.5, 270f, true);
        List<MovementInput> idle = new ArrayList<>(); for (int i = 0; i < 21; i++) idle.add(MovementInput.idle(270f));
        simulate(clientWorld, clientWorld.env(), rest, idle, List.of(), h.rec, true); h.feed();
        assertTrue(h.status().anchored(), h.status().anchorKind());
        if (dispute) h.runner.attachDisputes(disputed(h.rec.nanos));
        PhysicsState after = settled(clientWorld, 8.7, SURFACE, 8.5);
        List<MovementInput> east = new ArrayList<>(); for (int i = 0; i < 4; i++) east.add(MovementInput.forward(270f, true));
        simulate(clientWorld, clientWorld.env(), after, east, List.of(), h.rec, true, false); h.feed();
        return h.status();
    }
    /** A ledger in which the client has just interacted with the trapdoor cell and no acknowledgement has come back. */
    static BlockDisputeLedger disputed(long nanos) {
        BlockDisputeLedger l = new BlockDisputeLedger(KEY, new BlockDisputeLedger.Metrics());
        l.clientPrediction(new SemanticPayload.UseOn(SemanticPayload.Hand.MAIN_HAND, 7, CELL, SemanticPayload.Face.WEST, new SemanticPayload.Position(9.0, 70.1, 8.5), false, false), nanos);
        return l;
    }

    @Test void aStaleBlockStateUnderTheClientsFeetProducesACompleteInvalidWithoutTheGate() {
        // the observer holds the OPEN trapdoor (a full-height slab on the cell's west face); the client moves in a world where it is CLOSED
        PhysicsScene client = new PhysicsScene(); client.set(9, 70, 8, TRAPDOOR_CLOSED);
        MovementValidationRunnerTest.Harness h = scene(TRAPDOOR_OPEN);
        ValidationStatus st = run(h, client, false);
        assertTrue(st.invalid() > 0, "the live P9-F2 failure must reproduce without the gate: " + String.join("\n", st.notable()));
        assertTrue(String.join(" ", st.notable()).contains("COLLISION_PENETRATION"), String.join("\n", st.notable()));
    }
    @Test void theDisputeGateTurnsItIntoUncertainWithTheWorldDimensionNamed() {
        PhysicsScene client = new PhysicsScene(); client.set(9, 70, 8, TRAPDOOR_CLOSED);
        MovementValidationRunnerTest.Harness h = scene(TRAPDOOR_OPEN);
        ValidationStatus st = run(h, client, true);
        assertEquals(0, st.invalid(), "a disputed cell in reach forbids INVALID: " + String.join("\n", st.notable()));
        assertTrue(st.uncertain() > 0, String.join("\n", st.recent()));
        String notable = String.join(" ", st.notable());
        assertTrue(notable.contains("WORLD_INCOMPLETE"), notable);
        assertTrue(notable.contains("world") && notable.contains("dispute"), "the missing dimension is named, never a tolerance: " + notable);
    }
    @Test void anUndisputedWorldStillClassifiesNormally() {
        // the same scene with both sides agreeing: the step onto the closed trapdoor is ordinary legal movement
        PhysicsScene client = new PhysicsScene(); client.set(9, 70, 8, TRAPDOOR_CLOSED);
        MovementValidationRunnerTest.Harness h = scene(TRAPDOOR_CLOSED);
        ValidationStatus st = run(h, client, false);
        assertEquals(0, st.invalid(), String.join("\n", st.notable()));
        assertEquals(st.transitions(), st.valid(), String.join("\n", st.recent()));
        // and with the gate armed but the dispute elsewhere, detection is untouched
        MovementValidationRunnerTest.Harness far = scene(TRAPDOOR_OPEN);
        BlockDisputeLedger l = new BlockDisputeLedger(KEY, new BlockDisputeLedger.Metrics());
        l.clientPrediction(new SemanticPayload.UseOn(SemanticPayload.Hand.MAIN_HAND, 3, new SemanticPayload.BlockPosition(200, 70, 200), SemanticPayload.Face.UP, new SemanticPayload.Position(200.5, 71.0, 200.5), false, false), 1L);
        far.runner.attachDisputes(l);
        ValidationStatus st2 = run(far, client, false);
        assertTrue(st2.invalid() > 0, "a dispute far from the transition must not weaken detection: " + String.join("\n", st2.notable()));
    }
    @Test void theLedgerIsBoundedAndSettlesDeterministically() {
        BlockDisputeLedger l = new BlockDisputeLedger(KEY, new BlockDisputeLedger.Metrics());
        l.clientPrediction(new SemanticPayload.UseOn(SemanticPayload.Hand.MAIN_HAND, 5, CELL, SemanticPayload.Face.UP, new SemanticPayload.Position(9.5, 71.0, 8.5), false, false), 1_000L);
        assertEquals(2, l.cellCount(), "the targeted cell and the placement neighbour");
        assertTrue(l.disputed(9, 70, 8, 9, 71, 8, 2_000L));
        assertFalse(l.disputed(20, 70, 20, 21, 71, 21, 2_000L), "a cell outside the region is not disputed");
        // the acknowledgement is sent before the authoritative change is broadcast, so it must NOT settle the cell (the second live failure)
        l.acknowledged(5, 3_000L);
        assertEquals(2, l.cellCount(), "an acknowledgement alone leaves the observer world still holding the previous state");
        assertTrue(l.disputed(9, 70, 8, 9, 71, 8, 3_000L), "still disputed after the ack");
        l.applied(9, 70, 8, 3_500L, 0L);
        assertFalse(l.disputed(9, 70, 8, 9, 70, 8, 3_500L + BlockDisputeLedger.TRANSPORT_MARGIN_NANOS + 1), "the applied change settles the prediction");
        assertTrue(l.disputed(9, 71, 8, 9, 71, 8, 3_500L), "the other predicted cell is still open until its own change or the horizon");
        assertFalse(l.disputed(9, 71, 8, 9, 71, 8, 1_000L + BlockDisputeLedger.PREDICTION_HORIZON_NANOS + 1), "the horizon bounds it");
        // an applied server change stays disputed for the client's transport delay only
        l.applied(9, 70, 8, 10_000_000L, 50_000_000L);
        assertTrue(l.disputed(9, 70, 8, 9, 70, 8, 20_000_000L));
        assertFalse(l.disputed(9, 70, 8, 9, 70, 8, 10_000_000L + 50_000_000L + BlockDisputeLedger.TRANSPORT_MARGIN_NANOS + 1));
        // overflow degrades to a whole-world dispute rather than forgetting cells
        BlockDisputeLedger big = new BlockDisputeLedger(KEY, new BlockDisputeLedger.Metrics());
        for (int i = 0; i < BlockDisputeLedger.MAX_CELLS + 20; i++) big.applied(i, 70, 0, 1_000L, 0L);
        assertTrue(big.cellCount() <= BlockDisputeLedger.MAX_CELLS);
        assertTrue(big.disputed(-500, 0, -500, 500, 255, 500, 2_000L), "an overflowed ledger disputes everything until it drains");
        assertTrue(big.describe(2_000L).contains("WHOLE-WORLD-DISPUTED"));
    }
}
