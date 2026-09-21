package me.nicolas.papea2ac.testing;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.packet.*;
import me.nicolas.papea2ac.protocol.*;
import org.junit.jupiter.api.Test;

/**
 * Phase 8 at the engine level: the live validation runner is wired behind the timing ledger on the session's shard (no main-thread
 * pipeline), anchors at a confirmed correction fed through the real capture path, classifies every later transmission (here
 * UNCERTAIN / WORLD_INCOMPLETE: the test feeds no chunk data, so the world is unknown - never INVALID), exposes a bounded status
 * and engine-wide counters without UUID labels, runs a controlled injection on a side chain only, and starts every replacement
 * session with a fresh runner (session/world epoch safety, brief 59).
 */
class ValidationEngineIntegrationTest {
    static final double Y = 64.0;
    static Observation.Attempt in(long[] n, PacketPayload p) { long a = ++n[0]; return new Observation.Attempt(a, a, p); }

    @Test void theRunnerAnchorsAtACorrectionFedThroughTheCaptureAndClassifiesLaterTransmissionsOnTheShard() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> {}); var loop = new PacketTruthTest.Loop();
        try {
            UUID u = new UUID(80, 1);
            var session = engine.connect(u, loop, TransportCapability.observeOnly());
            long[] n = { 100 }; long[] w = { 1000 };
            session.ingress.bridge(new Observation.ControlChange("SELF_ENTITY", "42"));
            session.ingress.bridge(new Observation.Outbound(++w[0], new SemanticPayload.GameEvent(3, 0f)));            // survival
            session.ingress.bridge(new Observation.Outbound(++w[0], new SemanticPayload.BorderInitialize(0, 0, 5.9999968E7, 5.9999968E7, 0, 29999984, 5, 15)));
            assertTrue(engine.validationStatus(u).isPresent()); assertFalse(engine.validationStatus(u).get().anchored());
            // an absolute correction, confirmed and echoed by the client: the chain anchors at its application point
            session.ingress.bridge(new Observation.Outbound(++w[0], new SemanticPayload.Correction(7, new PacketPayload.Position(8.5, Y, 8.5), new PacketPayload.Position(0, 0, 0), new PacketPayload.Rotation(0f, 0f), Set.of())));
            session.ingress.bridge(new Observation.WriteOutcome(w[0], true));
            session.ingress.bridge(in(n, new PacketPayload.Control(PacketPayload.ControlKind.TELEPORT_CONFIRM, 7)));
            session.ingress.bridge(in(n, new PacketPayload.Movement(new PacketPayload.Position(8.5, Y, 8.5), new PacketPayload.Rotation(0f, 0f), false, false)));
            session.ingress.bridge(in(n, new PacketPayload.Control(PacketPayload.ControlKind.TICK_END, 0)));
            TimingMarkerSchedulingTest.await(loop, 3000, () -> engine.validationStatus(u).map(ValidationStatus::anchored).orElse(false));
            ValidationStatus st = engine.validationStatus(u).orElseThrow();
            assertTrue(st.anchored(), st.toString()); assertTrue(st.anchorKind().startsWith("correction 7"), st.anchorKind()); assertEquals(6, st.frontierNodes()); assertTrue(st.frontierComplete());
            assertEquals(1, engine.validationEngineStatus().anchors());
            // a controlled injection runs on a side chain: with no chunk data the synthetic transmission is UNCERTAIN / WORLD_INCOMPLETE (and CLIMBABLE_CONTACT: an unknown cell within reach counts as possible climbable contact, conservatively) without a continuation, the side chain ends, the live chain is untouched
            String started = engine.validationInject(u, new Vector3(0.5, 0, 0), 2); assertTrue(started.startsWith("INJECTING 2"), started);
            for (int i = 0; i < 3; i++) session.ingress.bridge(in(n, new PacketPayload.Control(PacketPayload.ControlKind.TICK_END, 0)));
            TimingMarkerSchedulingTest.await(loop, 3000, () -> engine.validationStatus(u).map(s -> s.injected().size() >= 2).orElse(false));
            st = engine.validationStatus(u).orElseThrow();
            assertTrue(st.injected().size() >= 2, String.join("\n", st.injected())); assertTrue(st.injected().get(0).startsWith("injected +h0.500") && st.injected().get(0).contains("UNCERTAIN [WORLD_INCOMPLETE, CLIMBABLE_CONTACT]"), st.injected().get(0));
            assertTrue(st.injected().stream().anyMatch(l -> l.contains("side chain ended")), String.join("\n", st.injected()));
            assertTrue(st.anchored(), "the live chain never saw the synthetic packets: " + st); assertEquals(0, st.transitions()); assertEquals(0, st.invalid());
            assertEquals(1, engine.validationEngineStatus().injectedRequests()); assertEquals(0, engine.validationEngineStatus().requests(), "injected results are not live requests");
            // later transmissions are validated on the shard; without chunk data the world is unknown: UNCERTAIN / WORLD_INCOMPLETE, never INVALID;
            // an unknown world admits no continuation, so the chain loses its anchor and the following transmissions are START_UNAVAILABLE until the next correction or rest
            for (int i = 1; i <= 3; i++) {
                session.ingress.bridge(in(n, new PacketPayload.Movement(new PacketPayload.Position(8.5 + 0.1 * i, Y, 8.5), new PacketPayload.Rotation(0f, 0f), true, false)));
                session.ingress.bridge(in(n, new PacketPayload.Control(PacketPayload.ControlKind.TICK_END, 0)));
            }
            TimingMarkerSchedulingTest.await(loop, 3000, () -> engine.validationStatus(u).map(s -> s.recent().size() >= 3).orElse(false)); // the status is published after every counter of the event moved
            st = engine.validationStatus(u).orElseThrow();
            assertEquals(1, st.transitions(), st.toString()); assertEquals(0, st.invalid()); assertEquals(1, st.uncertain()); assertFalse(st.anchored()); assertTrue(st.anchorKind().contains("without continuation"), st.anchorKind());
            assertEquals("UNCERTAIN", st.lastClassification()); assertTrue(String.join("\n", st.lastResult()).contains("WORLD_INCOMPLETE"), String.join("\n", st.lastResult()));
            assertEquals(3, st.recent().size()); assertTrue(st.recent().get(0).startsWith("seq=") && st.recent().get(0).contains("WORLD_INCOMPLETE"), st.recent().get(0)); assertTrue(st.recent().get(2).contains("START_UNAVAILABLE"), st.recent().get(2));
            var es = engine.validationEngineStatus();
            assertEquals(3, es.requests()); assertEquals(3, es.uncertain()); assertEquals(0, es.invalid()); assertEquals(0, es.valid()); assertEquals(1, es.uncertainUnsupported(), es + " " + st); assertEquals(0, es.uncertainWorldIncomplete(), "an unknown cell within reach counts as possible climbable contact, and the unsupported family precedes the world family in the metric: " + es); assertEquals(2, es.uncertainUnanchored(), es.toString()); assertEquals(1, es.anchors(), es.toString()); assertEquals(1, es.anchorsLost(), es.toString()); assertEquals(0, es.internalErrors());
            assertTrue(es.timingBranches() >= 1, "the Phase 7 union is consumed: " + es.timingBranches());
            String text = es.toString(); assertFalse(text.contains(u.toString()), "no UUID label in engine counters");
        } finally { engine.close(); loop.drain(); }
    }
    @Test void aReplacementSessionStartsWithAFreshRunnerAndUnknownPlayersAreRefused() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> {}); var loop = new PacketTruthTest.Loop();
        try {
            UUID u = new UUID(80, 2);
            assertTrue(engine.validationStatus(u).isEmpty()); assertEquals("REFUSED: no admitted session", engine.validationInject(u, new Vector3(1, 0, 0), 1));
            var first = engine.connect(u, loop, TransportCapability.observeOnly());
            long[] n = { 100 }; long[] w = { 1000 };
            first.ingress.bridge(new Observation.ControlChange("SELF_ENTITY", "42"));
            first.ingress.bridge(new Observation.Outbound(++w[0], new SemanticPayload.GameEvent(3, 0f)));
            first.ingress.bridge(new Observation.Outbound(++w[0], new SemanticPayload.Correction(3, new PacketPayload.Position(8.5, Y, 8.5), new PacketPayload.Position(0, 0, 0), new PacketPayload.Rotation(0f, 0f), Set.of())));
            first.ingress.bridge(new Observation.WriteOutcome(w[0], true));
            first.ingress.bridge(in(n, new PacketPayload.Control(PacketPayload.ControlKind.TELEPORT_CONFIRM, 3)));
            first.ingress.bridge(in(n, new PacketPayload.Movement(new PacketPayload.Position(8.5, Y, 8.5), new PacketPayload.Rotation(0f, 0f), false, false)));
            first.ingress.bridge(in(n, new PacketPayload.Control(PacketPayload.ControlKind.TICK_END, 0)));
            TimingMarkerSchedulingTest.await(loop, 3000, () -> engine.validationStatus(u).map(ValidationStatus::anchored).orElse(false));
            ValidationStatus before = engine.validationStatus(u).orElseThrow(); assertTrue(before.anchored());
            assertEquals("REFUSED: no admitted session", engine.validationInject(new UUID(80, 3), new Vector3(1, 0, 0), 1));
            var second = engine.connect(u, loop, TransportCapability.observeOnly()); // replacement: the first session closes
            TimingMarkerSchedulingTest.pump(loop, 200);
            ValidationStatus after = engine.validationStatus(u).orElseThrow();
            assertNotEquals(before.sessionEpoch(), after.sessionEpoch()); assertEquals(second.key.epoch(), after.sessionEpoch());
            assertFalse(after.anchored(), after.toString()); assertEquals(0, after.transitions()); assertTrue(after.recent().isEmpty()); assertTrue(after.injected().isEmpty());
            assertTrue(engine.validationInject(u, new Vector3(1, 0, 0), 1).startsWith("REFUSED: the live chain is not anchored"));
            // a late transmission of the old session never reaches the new runner
            first.ingress.bridge(in(n, new PacketPayload.Movement(new PacketPayload.Position(9.5, Y, 8.5), new PacketPayload.Rotation(0f, 0f), true, false)));
            TimingMarkerSchedulingTest.pump(loop, 200);
            assertEquals(0, engine.validationStatus(u).orElseThrow().transitions());
        } finally { engine.close(); loop.drain(); }
    }
}
