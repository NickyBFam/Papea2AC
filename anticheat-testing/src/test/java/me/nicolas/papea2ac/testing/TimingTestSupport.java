package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import me.nicolas.papea2ac.model.Rotation;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.player.RttEstimator;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.player.TimingLedger;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.version.MovementObservationProfile;
import me.nicolas.papea2ac.version.TimingProfile;
import java.util.*;
/**
 * Phase 7 test scaffolding: a synthetic session recorder with the two clocks (capture sequence, capture-monotonic nanos), a
 * client-trace generator that runs the Phase 5 simulator under a HIDDEN ground truth (per-tick inputs, the tick each influence
 * was applied to, the client's send rule, tick ends, corrections) and emits exactly the events the ledger would see, and a
 * chain runner that walks the transmissions through TimingReconstruction the way the tracer does. Expected values never come
 * from the resolver: the truth is known by construction (TESTING.md: never derive expected and actual from the same code).
 */
final class TimingTestSupport {
    private TimingTestSupport() {}
    static final long TICK_NANOS = 50_000_000L;
    static final MovementObservationProfile OBS = MovementObservationProfile.PAPER_26_2;

    /** A synthetic session: sequences and nanos advance monotonically; every recorded event is also fed to a real TimingLedger. */
    static final class Recorder {
        final List<TimingEvent> events = new ArrayList<>();
        final TimingLedger ledger;
        long sequence = 100, nanos = 1_000_000_000L, wire = 10;
        Recorder(TimingLedger ledger) { this.ledger = ledger; }
        Recorder() { this(new TimingLedger(new me.nicolas.papea2ac.model.SessionKey(new UUID(7, 1), 1))); }
        <T extends TimingEvent> T add(T e) { events.add(e); ledger.apply(ledger.key(), e); return e; }
        void advance(long dt) { nanos += dt; }
        long nextSeq() { return ++sequence; }
        long nextWire() { return ++wire; }
        TimingEvent.MovementObserved movement(double x, double y, double z, float yaw, boolean onGround) { return add(new TimingEvent.MovementObserved(nextSeq(), nanos, true, new Vector3(x, y, z), true, new Rotation(yaw, 0f), onGround)); }
        TimingEvent.MovementObserved positionOnly(double x, double y, double z, boolean onGround) { return add(new TimingEvent.MovementObserved(nextSeq(), nanos, true, new Vector3(x, y, z), false, new Rotation(0f, 0f), onGround)); }
        TimingEvent.MovementObserved rotationOnly(float yaw, boolean onGround) { return add(new TimingEvent.MovementObserved(nextSeq(), nanos, false, Vector3.ZERO, true, new Rotation(yaw, 0f), onGround)); }
        TimingEvent.ClientTickEnd tickEnd() { return add(new TimingEvent.ClientTickEnd(nextSeq(), nanos)); }
        TimingEvent.MarkerSent marker(TimingEvent.MarkerKind kind, long id) { return add(new TimingEvent.MarkerSent(nextSeq(), nanos, kind, id, nextWire())); }
        TimingEvent.MarkerAcknowledged ack(TimingEvent.MarkerKind kind, long id) { return add(new TimingEvent.MarkerAcknowledged(nextSeq(), nanos, kind, id)); }
        TimingEvent.WriteResolved write(long wire, boolean ok) { return add(new TimingEvent.WriteResolved(nextSeq(), nanos, wire, ok)); }
        TimingEvent.ExternalMotionSent velocity(double x, double y, double z) { return add(new TimingEvent.ExternalMotionSent(nextSeq(), nanos, nextWire(), TimingEvent.Operation.SET, new Vector3(x, y, z), "velocity")); }
        TimingEvent.ExternalMotionSent explosion(double x, double y, double z) { return add(new TimingEvent.ExternalMotionSent(nextSeq(), nanos, nextWire(), TimingEvent.Operation.ADD, new Vector3(x, y, z), "explosion")); }
        TimingEvent.ExternalMotionSent unresolvedMotion() { return add(new TimingEvent.ExternalMotionSent(nextSeq(), nanos, nextWire(), TimingEvent.Operation.UNRESOLVED, Vector3.ZERO, "declared-unknown")); }
        TimingEvent.TeleportSent teleport(long id, double x, double y, double z, float yaw, int relativeMask) { return add(new TimingEvent.TeleportSent(nextSeq(), nanos, id, nextWire(), new Vector3(x, y, z), new Rotation(yaw, 0f), Vector3.ZERO, relativeMask)); }
        TimingEvent.TeleportConfirmed confirm(long id) { return add(new TimingEvent.TeleportConfirmed(nextSeq(), nanos, id)); }
        TimingEvent.ServerTick serverTick(long t) { return add(new TimingEvent.ServerTick(nextSeq(), nanos, t)); }
        TimingEvent.Discontinuity discontinuity(long epoch) { return add(new TimingEvent.Discontinuity(nextSeq(), nanos, epoch)); }
        TimingEvent.CoverageBreak coverageBreak(String reason) { return add(new TimingEvent.CoverageBreak(nextSeq(), nanos, reason)); }
        List<TimingEvent> since(long afterSequence, long toSequenceInclusive) { List<TimingEvent> out = new ArrayList<>(); for (TimingEvent e : events) if (e.sequence() > afterSequence && e.sequence() <= toSequenceInclusive) out.add(e); return out; }
    }

    /** One tick of hidden truth: the input, the influences applied before it, the resulting state, and what the client transmitted. */
    record TruthTick(int index, MovementInput input, List<PhysicsEnvironment.ExternalMotion> applied, PhysicsState after, boolean transmittedPosition, TimingEvent.MovementObserved transmission) {}
    record Truth(PhysicsState start, List<TruthTick> ticks, Map<String, Integer> applicationTick, Recorder recorder) {
        /** The state after tick i (1-based). */
        PhysicsState after(int i) { return ticks.get(i - 1).after(); }
    }
    /** A hidden influence: emitted (captured outbound) {@code leadTicks} ticks before the tick it is applied to (0 = inside the same inter-tick gap; more = network delay). */
    record Influence(TimingEvent.Operation op, Vec3d vector, int applicationTick, int leadTicks) {
        Influence { if (applicationTick < 1 || leadTicks < 0 || leadTicks >= applicationTick) throw new IllegalArgumentException("influence timing"); }
        int emitTick() { return applicationTick - leadTicks; }
    }

    /**
     * Runs {@code inputs} from {@code start} with the influences applied before their application tick, transmitting per the client's
     * send rule (position when it left the send-threshold ball or every 20 ticks; rotation-only when only the yaw changed), one tick
     * end per tick after the transmission, and the influence events at their send times. Every step must be EXACT.
     */
    static Truth simulate(PhysicsScene ps, PhysicsEnvironment env, PhysicsState start, List<MovementInput> inputs, List<Influence> influences, Recorder rec, boolean tickEnds) { return simulate(ps, env, start, inputs, influences, rec, tickEnds, true); }
    /** {@code leadingTickEnd}: whether the start state belongs to a tick whose end is still to be transmitted (an ordinary anchor) or not (a correction's application point). */
    static Truth simulate(PhysicsScene ps, PhysicsEnvironment env, PhysicsState start, List<MovementInput> inputs, List<Influence> influences, Recorder rec, boolean tickEnds, boolean leadingTickEnd) {
        List<TruthTick> ticks = new ArrayList<>(); Map<String, Integer> applicationTick = new LinkedHashMap<>();
        PhysicsState s = start; double lx = start.position().x(), ly = start.position().y(), lz = start.position().z(); float lastYaw = inputs.get(0).yaw(); int sinceSend = 0;
        List<Influence> ordered = new ArrayList<>(influences); ordered.sort(Comparator.comparingInt(Influence::applicationTick).thenComparingInt(Influence::emitTick));
        Map<Influence, TimingEvent.ExternalMotionSent> emitted = new LinkedHashMap<>();
        if (tickEnds && leadingTickEnd) { rec.advance(1_000_000L); rec.tickEnd(); } // the end of the resting tick the start state belongs to (the anchor transmission's own tick end)
        for (int i = 1; i <= inputs.size(); i++) {
            // emission: captured outbound in the inter-tick gap before tick i's movement (a lead of L ticks = the packet left L ticks before it took effect: network delay)
            for (Influence inf : ordered) if (inf.emitTick() == i) {
                rec.advance(TICK_NANOS / 8);
                TimingEvent.ExternalMotionSent e = inf.op() == TimingEvent.Operation.SET ? rec.velocity(inf.vector().x(), inf.vector().y(), inf.vector().z()) : rec.explosion(inf.vector().x(), inf.vector().y(), inf.vector().z());
                emitted.put(inf, e);
            }
            // application: the influences the client applied before tick i, in emission (wire) order
            List<PhysicsEnvironment.ExternalMotion> applied = new ArrayList<>();
            for (Influence inf : ordered) if (inf.applicationTick() == i) {
                TimingEvent.ExternalMotionSent e = emitted.get(inf);
                applied.add(new PhysicsEnvironment.ExternalMotion(inf.op() == TimingEvent.Operation.SET ? PhysicsEnvironment.ExternalMotion.Operation.SET : PhysicsEnvironment.ExternalMotion.Operation.ADD, inf.vector(), e.id()));
                applicationTick.put(e.id(), i);
            }
            applied.sort(Comparator.comparingLong(m -> Long.parseLong(m.sourceId().substring(1))));
            rec.advance(TICK_NANOS / 2);
            MovementInput in = inputs.get(i - 1);
            PhysicsResult r = ps.simulator.simulate(s, in, applied.isEmpty() ? env : env.withExternalMotions(applied));
            if (!r.exact()) throw new AssertionError("tick " + i + " not exact: " + r.summary());
            PhysicsState next = r.next();
            boolean sendPosition = !OBS.withinSendThreshold(next.position().x(), next.position().y(), next.position().z(), lx, ly, lz) || ++sinceSend >= 20;
            boolean yawChanged = in.yaw() != lastYaw;
            TimingEvent.MovementObserved tx = null;
            if (sendPosition) { tx = rec.movement(next.position().x(), next.position().y(), next.position().z(), in.yaw(), next.onGround()); lx = next.position().x(); ly = next.position().y(); lz = next.position().z(); sinceSend = 0; lastYaw = in.yaw(); }
            else if (yawChanged) { tx = rec.rotationOnly(in.yaw(), next.onGround()); lastYaw = in.yaw(); }
            if (tickEnds) { rec.advance(1_000_000L); rec.tickEnd(); }
            rec.advance(TICK_NANOS / 2);
            ticks.add(new TruthTick(i, in, applied, next, sendPosition, tx));
            s = next;
        }
        return new Truth(start, ticks, applicationTick, rec);
    }

    /** One step of the chain: the target transmission, the timing/reconstruction result, and whether the truth state of that tick is in the next frontier. */
    record ChainStep(TimingEvent.MovementObserved target, TimingReconstruction.Result result, PhysicsState truthAfter, boolean truthRetained) {}
    static TimingRequest request(Recorder rec, long anchorSeq, long anchorNanos, Vector3 lastSent, float lastYaw, TimingEvent.MovementObserved target, Collection<TimingEvent.ExternalMotionSent> influences, TimingProfile profile, TimingBudget budget, long now) {
        TimingLedger.View v = rec.ledger.view();
        return new TimingRequest(rec.since(anchorSeq, target.sequence()), anchorSeq, anchorNanos, true, lastSent, lastYaw, target, new ArrayList<>(influences), v.teleports(), v.rtt(), v.tickEndCapable(), v.counters().coverageBreaks() > 0, v.worldEpoch(), now, profile, budget);
    }
    /** Walks every transmission of a simulated truth through TimingReconstruction from the true start; asserts nothing itself. */
    static List<ChainStep> chain(PhysicsScene ps, PhysicsEnvironment env, Truth truth, TimingProfile profile, TimingBudget budget) {
        TimingReconstruction timing = new TimingReconstruction(new TimingResolver(), new InputReconstructor(ps.simulator));
        Recorder rec = truth.recorder();
        TimingReconstruction.Frontier frontier = TimingReconstruction.Frontier.of(truth.start());
        long anchorSeq = 100, anchorNanos = 1_000_000_000L; boolean anchorInTick = true; Vector3 lastSent = new Vector3(truth.start().position().x(), truth.start().position().y(), truth.start().position().z()); float lastYaw = truth.ticks().get(0).input().yaw();
        Map<String, TimingEvent.ExternalMotionSent> influences = new LinkedHashMap<>();
        List<ChainStep> out = new ArrayList<>(); int from = 0;
        for (int i = 0; i < rec.events.size(); i++) {
            TimingEvent e = rec.events.get(i);
            if (e instanceof TimingEvent.ExternalMotionSent m) influences.put(m.id(), m);
            if (!(e instanceof TimingEvent.MovementObserved target)) continue;
            TimingRequest req = new TimingRequest(new ArrayList<>(rec.events.subList(from, i + 1)), anchorSeq, anchorNanos, anchorInTick, lastSent, lastYaw, target, new ArrayList<>(influences.values()), rec.ledger.view().teleports(), rec.ledger.view().rtt(), rec.ledger.view().tickEndCapable(), rec.ledger.view().counters().coverageBreaks() > 0, rec.ledger.view().worldEpoch(), target.nanos(), profile, budget); // evaluation time = the interval's close (P7-F7)
            from = i + 1;
            TimingReconstruction.Result r = timing.run(req, frontier, env, ReconstructionRequest.SearchBudget.DEFAULT);
            PhysicsState truthAfter = null; for (TruthTick t : truth.ticks()) if (t.transmission() == target) truthAfter = t.after();
            boolean retained = false; if (truthAfter != null) for (TimingReconstruction.Node n : r.next().nodes()) if (n.state().equals(truthAfter)) { retained = true; break; }
            out.add(new ChainStep(target, r, truthAfter, retained));
            if (r.next().isEmpty()) break;
            frontier = r.next(); anchorSeq = target.sequence(); anchorNanos = target.nanos(); anchorInTick = r.targetInTick();
            if (target.hasPosition()) lastSent = target.position(); if (target.hasRotation()) lastYaw = target.rotation().yaw();
        }
        return out;
    }
    static RttEstimator.Summary noRtt() { return RttEstimator.Summary.EMPTY; }
    static String describe(List<ChainStep> steps) { StringBuilder sb = new StringBuilder(); for (ChainStep s : steps) { sb.append(s.target().sequence()).append(' ').append(s.result().status()).append(s.truthRetained() ? " retained" : s.truthAfter() == null ? " (rot-only)" : " LOST").append('\n'); for (String l : s.result().describe()) sb.append("   ").append(l).append('\n'); } return sb.toString(); }
}
