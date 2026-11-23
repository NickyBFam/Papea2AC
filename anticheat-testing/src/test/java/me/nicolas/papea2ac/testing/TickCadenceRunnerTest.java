package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.TickCadenceTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import me.nicolas.papea2ac.core.TickCadenceEngineStatus;
import me.nicolas.papea2ac.core.TickCadenceObservationMapper;
import me.nicolas.papea2ac.core.TickCadenceRunner;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.player.TickCadenceEvent;
import me.nicolas.papea2ac.protocol.Observation;
import me.nicolas.papea2ac.protocol.PacketPayload;
import me.nicolas.papea2ac.protocol.SemanticPayload;
import me.nicolas.papea2ac.version.TickCadenceProfile;
import org.junit.jupiter.api.Test;
/** Phase 14 composition: the observation mapper (every family, bundles) and the per-session runner (anchors, runs, containment, status). */
class TickCadenceRunnerTest {
    static final PacketPayload.Position POS = new PacketPayload.Position(0, 64, 0);
    static final PacketPayload.Rotation ROT = new PacketPayload.Rotation(0f, 0f);
    long seq = 0, attempt = 0, ingress = 0;
    Observation in(long nanos, PacketPayload p) { return new Observation(KEY, ++seq, nanos, 0, new Observation.Attempt(++attempt, ++ingress, p)); }
    Observation out(long nanos, long wire, PacketPayload p) { return new Observation(KEY, ++seq, nanos, 0, new Observation.Outbound(wire, p)); }
    static PacketPayload.Control control(PacketPayload.ControlKind k, long v) { return new PacketPayload.Control(k, v); }

    @Test void theMapperTranslatesEveryCadenceFamilyAndOpensBundles() {
        assertInstanceOf(TickCadenceEvent.TickEnd.class, one(in(1, control(PacketPayload.ControlKind.TICK_END, 0))));
        assertInstanceOf(TickCadenceEvent.Movement.class, one(in(2, new PacketPayload.Movement(POS, null, true, false))));
        assertInstanceOf(TickCadenceEvent.Movement.class, one(in(3, new PacketPayload.Movement(null, null, false, false))), "a status-only packet is a movement packet too");
        assertEquals(new TickCadenceEvent.PingAnswered(seq + 1, 4, 77), one(in(4, control(PacketPayload.ControlKind.PONG, 77))));
        assertEquals(new TickCadenceEvent.PingSent(seq + 1, 5, 77, 9), one(out(5, 9, control(PacketPayload.ControlKind.PING, 77))));
        assertEquals(TickCadenceEvent.Induced.CORRECTION, ((TickCadenceEvent.InducedSent) one(out(6, 10, new SemanticPayload.Correction(3, POS, POS, ROT, Set.of())))).kind());
        assertEquals(TickCadenceEvent.Induced.ROTATION, ((TickCadenceEvent.InducedSent) one(out(7, 11, new SemanticPayload.RotationCorrection(ROT, true, false)))).kind());
        assertEquals(TickCadenceEvent.Induced.ENTITY_TELEPORT, ((TickCadenceEvent.InducedSent) one(out(8, 12, new SemanticPayload.EntityPosition(5, POS, POS, ROT, Set.of(), false)))).kind());
        assertEquals(new TickCadenceEvent.TickRate(seq + 1, 9, 13, 10f, true), one(out(9, 13, new PacketPayload.TickRate(10f, true))));
        assertEquals(new TickCadenceEvent.WriteFailed(seq + 1, 10, 13), one(new Observation(KEY, seq + 1, 10, 0, new Observation.WriteOutcome(13, false)))); seq++;
        assertTrue(TickCadenceObservationMapper.map(new Observation(KEY, ++seq, 11, 0, new Observation.WriteOutcome(14, true))).isEmpty());
        assertTrue(TickCadenceObservationMapper.map(in(12, control(PacketPayload.ControlKind.KEEPALIVE, 1))).isEmpty(), "a keep-alive reply is network-thread: never a fence");
        assertTrue(TickCadenceObservationMapper.map(out(13, 15, control(PacketPayload.ControlKind.KEEPALIVE, 1))).isEmpty());
        assertTrue(TickCadenceObservationMapper.map(in(14, new PacketPayload.Vehicle(POS, ROT, false))).isEmpty());
        var bundle = TickCadenceObservationMapper.map(out(15, 16, new PacketPayload.Bundle(List.of(new SemanticPayload.Correction(4, POS, POS, ROT, Set.of()), new SemanticPayload.EntityPosition(6, POS, POS, ROT, Set.of(), true), new PacketPayload.TickRate(20f, false)))));
        assertEquals(3, bundle.size()); assertTrue(bundle.stream().allMatch(e -> e.sequence() == seq), "children share the bundle's sequence");
    }
    TickCadenceEvent one(Observation o) { var l = TickCadenceObservationMapper.map(o); assertEquals(1, l.size(), o.toString()); return l.get(0); }

    @Test void theRunnerClassifiesEveryAnchorCountsDistinctRunsAndPublishes() {
        TickCadenceRunner.Metrics m = new TickCadenceRunner.Metrics();
        TickCadenceRunner r = new TickCadenceRunner(KEY, TickCadenceProfile.PAPER_26_2, true, m);
        long t = 0; long wire = 0;
        // 30 s honest (20 tick ends per second), then 30 s at 2x, then 60 s honest again
        for (int second = 0; second < 120; second++) {
            int ticks = second >= 30 && second < 60 ? 40 : 20;
            long id = second + 1;
            r.onObservation(out(t, ++wire, control(PacketPayload.ControlKind.PING, id)));
            for (int k = 0; k < ticks; k++) r.onObservation(in(t + 10 * MS + k * (S / ticks), control(PacketPayload.ControlKind.TICK_END, 0)));
            r.onObservation(in(t + S - 5 * MS, control(PacketPayload.ControlKind.PONG, id)));
            t += S;
        }
        var s = r.status();
        assertTrue(s.valid() > 25, s.toString()); assertTrue(s.invalid() > 0, s.toString());
        assertEquals(1, s.exclusionRuns(), "one continuous excess is one run");
        assertTrue(s.largestExcess() > 400, s.largestExcessWindow());
        assertTrue(r.lastResult().orElseThrow().invalid(), "windows from before the 2x episode still hold its excess while retained");
        assertEquals(120, s.anchors() <= 352 ? s.pingsAnswered() : -1);
        var e = TickCadenceEngineStatus.of("p", m);
        assertEquals(120, e.anchors()); assertEquals(1, e.exclusionRuns()); assertEquals(0, e.internalErrors());
        // a coverage break ends the run and the windows: after it, honest seconds are VALID again
        r.onCoverageBreak(seq, t, "GAP");
        for (int second = 0; second < 5; second++) {
            long id = 1000 + second;
            r.onObservation(out(t, ++wire, control(PacketPayload.ControlKind.PING, id)));
            for (int k = 0; k < 20; k++) r.onObservation(in(t + 10 * MS + k * 50 * MS, control(PacketPayload.ControlKind.TICK_END, 0)));
            r.onObservation(in(t + S - 5 * MS, control(PacketPayload.ControlKind.PONG, id)));
            t += S;
        }
        assertTrue(r.lastResult().orElseThrow().valid(), r.lastResult().orElseThrow().describe());
        assertEquals(1, r.status().segment()); assertEquals(1, r.status().coverageBreaks());
    }

    @Test void foreignSessionsAreIgnoredAndFailuresAreContained() {
        TickCadenceRunner.Metrics m = new TickCadenceRunner.Metrics();
        TickCadenceRunner r = new TickCadenceRunner(KEY, TickCadenceProfile.PAPER_26_2, false, m);
        r.onObservation(new Observation(new SessionKey(new UUID(9, 9), 1), 1, 1, 0, new Observation.Attempt(1, 1, control(PacketPayload.ControlKind.TICK_END, 0))));
        assertEquals(0, r.status().tickEnds());
        r.onObservation(out(1, 1, control(PacketPayload.ControlKind.PING, 1)));
        r.onObservation(in(2, control(PacketPayload.ControlKind.PONG, 1)));
        r.onObservation(out(3, 2, control(PacketPayload.ControlKind.PING, 2)));
        for (int k = 0; k < 500; k++) r.onObservation(in(4 + k, control(PacketPayload.ControlKind.TICK_END, 0)));
        r.onObservation(in(S, control(PacketPayload.ControlKind.PONG, 2)));
        assertTrue(r.lastResult().orElseThrow().uncertain(), "an unsupported profile never excludes, even with 500 tick ends in a second");
        assertEquals(0, r.status().invalid());
        // a decreasing sequence is rejected by the ledger, not thrown
        r.onObservation(new Observation(KEY, 1, 5 * S, 0, new Observation.Attempt(++attempt, ++ingress, control(PacketPayload.ControlKind.TICK_END, 0))));
        assertEquals(0, TickCadenceEngineStatus.of("p", m).internalErrors());
        r.close(); assertDoesNotThrow(() -> r.onObservation(in(6 * S, control(PacketPayload.ControlKind.TICK_END, 0))));
    }
}
