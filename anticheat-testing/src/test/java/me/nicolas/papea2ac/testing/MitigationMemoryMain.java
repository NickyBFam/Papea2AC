package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.TimingMemoryMain.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import me.nicolas.papea2ac.core.DecisionCapture;
import me.nicolas.papea2ac.core.GlobalProposalGuard;
import me.nicolas.papea2ac.core.MitigationRunner;
import me.nicolas.papea2ac.evidence.*;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.InvalidCode;
import me.nicolas.papea2ac.mitigation.*;
import me.nicolas.papea2ac.mitigation.MitigationActor.EffectMode;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.replay.*;

/**
 * Empirical memory harness for Phase 10. The methodology is the same as the Phase 2-9 harnesses: forced GC, used-heap diff, 3 trials, median;
 * estimates on one host, never a supported-capacity claim. Thread-allocation counters give the per-step garbage.
 *
 * <p>Measured: an empty mitigation actor (two movement-setback-v1 policies, bounded rings); an actor filled to its bounds (64 anchors, full
 * chains and group-family maps, predecessor ids, decision/outcome rings); one SafeAnchor; one MitigationIntent; the core MitigationRunner; the
 * G2 proposal guard at its 1024-session window; a RollingCapture at a full 256 KiB share of protocol-shaped movement frames (heap versus the
 * charged share); a DecisionCapture. The shared collision engine and observer world are excluded: they belong to the session's Phase 4/5 state.
 */
public final class MitigationMemoryMain {
    private MitigationMemoryMain() {}
    static final long S = 1_000_000_000L, T0 = 100 * S, TICK = 50_000_000L;
    static final List<InvalidCode> H = List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.HORIZONTAL_EXCESS);

    static MitigationActor actor(MitigationActorTest.H0 env) { return new MitigationActor(env.key, MitigationBounds.DEFAULT, env.admission, i -> true, i -> true, (s, n) -> true, new MitigationActor.Metrics()); }
    /** An actor driven to its bounds through a real Phase 9 ledger (OBSERVE_ONLY: eligibility is reached repeatedly, nothing is applied). */
    static MitigationActor atBounds(MitigationActorTest.H0 env) {
        MitigationActor a = actor(env); EvidenceLedger ledger = new EvidenceLedger(env.key); long seq = 0, t = T0;
        for (int i = 0; i < 200; i++) { seq += 10; t += TICK; a.onAnchorFacts(env.facts(seq, t, MitigationActorTest.ANCHOR), 0, env.ps.scene.world.currentView()); }
        for (int round = 0; round < 40; round++) for (int g = 0; g < 4; g++) {
            seq += 100; t += S / 10; EvidenceLedger.Outcome o = ledger.ingest(EvidenceTestSupport.invalid(env.key, 0, seq, seq + 10, t, H, 0.2, 0));
            a.onEvidence(o, ledger.lastEvent().filter(e -> o == EvidenceLedger.Outcome.POSITIVE_EVENT), ledger.snapshot(), round % 2 == 1, env.cs(t, seq + 10));
        }
        return a;
    }
    /** A typical legitimate session: 20 recent anchors, an interleaved VALID stream and one isolated evidence group (chain 1/4). */
    static MitigationActor typical(MitigationActorTest.H0 env) {
        MitigationActor a = actor(env); EvidenceLedger ledger = new EvidenceLedger(env.key); long seq = 0, t = T0;
        for (int i = 0; i < 20; i++) { seq += 10; t += TICK; a.onAnchorFacts(env.facts(seq, t, MitigationActorTest.ANCHOR), 0, env.ps.scene.world.currentView());
            EvidenceLedger.Outcome o = ledger.ingest(i == 10 ? EvidenceTestSupport.invalid(env.key, 0, seq, seq + 5, t, H, 0.2, 0) : EvidenceTestSupport.valid(env.key, 0, seq, seq + 5, t));
            a.onEvidence(o, ledger.lastEvent().filter(e -> o == EvidenceLedger.Outcome.POSITIVE_EVENT), ledger.snapshot(), false, env.cs(t, seq + 5)); }
        return a;
    }
    public static void main(String[] args) throws IOException {
        MitigationActorTest.H0 env = new MitigationActorTest.H0();
        Map<String, Object> out = new LinkedHashMap<>(); out.put("phase", "10 - Mitigation / Setback");
        out.put("method", "forced GC + used-heap diff, 3 trials, median; thread-allocation counters for per-step garbage; estimates on one host, not capacity");
        out.put("emptyActorBytes", Math.round(measure(() -> actor(env))));
        out.put("typicalActorBytes", Math.round(measure(() -> typical(env))));
        out.put("atBoundsActorBytes", Math.round(measure(() -> atBounds(env))));
        MitigationBounds b = MitigationBounds.DEFAULT;
        out.put("bounds", "anchors <= " + b.anchorHistory() + ", chain <= " + SetbackPolicy.MAX_CHAIN + " groups per policy (2 policies), group families <= " + SetbackPolicy.MAX_GROUP_FAMILIES + ", member ids <= " + SetbackPolicy.MAX_MEMBER_IDS
            + ", predecessor ids <= " + b.predecessorIds() + ", one active intent, bounded decision/outcome/teleport rings");
        SafeAnchor anchor = new SafeAnchor(env.key, 0, 1, 1, T0, MitigationActorTest.ANCHOR, 90f, 10f, true, true, MitigationVocabulary.AnchorSource.VALID_CONCRETE_GROUNDED, List.of("VALID", "ALL13", "GROUNDED", "CLEAR"));
        out.put("safeAnchorBytes", Math.round(measure(() -> new SafeAnchor(env.key, 0, 1, 1, T0, new Vector3(8.5, 64, 8.5), 90f, 10f, true, true, MitigationVocabulary.AnchorSource.VALID_CONCRETE_GROUNDED, List.of("VALID", "ALL13", "GROUNDED", "CLEAR")))));
        List<String> groups = List.of("g1", "g2", "g3", "g4");
        out.put("intentBytes", Math.round(measure(() -> new MitigationIntent(MitigationIntent.actionId(SetbackPolicy.VERSION, env.key, 0, groups, false, 1), MitigationVocabulary.ActionType.SETBACK, env.key, 0, "minecraft:overworld", 1,
            groups, List.of("e1", "e2", "e3", "e4"), EvidenceVocabulary.Category.HORIZONTAL_EXCESS, SetbackPolicy.VERSION, false, anchor, 10, 1, T0, T0 + b.intentDeadlineNanos(), "PENDING"))));
        out.put("runnerBytes(excluding actor)", Math.round(measure(() -> new MitigationRunner(env.key, actor(env), new HealthLatch(), () -> EffectMode.OBSERVE_ONLY, () -> "minecraft:overworld", new MitigationActor.Metrics())) - measure(() -> actor(env))));
        // the burst gate trips at the 3rd distinct session within 1 s, so the reachable untripped window holds at most 2 per second over 10 s (20 entries)
        out.put("proposalGuardReachableMaxBytes", Math.round(measure(() -> { GlobalProposalGuard g = new GlobalProposalGuard(1024); for (int i = 0; i < 20; i++) g.propose(new UUID(7, i), 1024, T0 + i * S / 2 + (i % 2) * S / 4); return g; })));
        // capsule prehistory: a full share of protocol-shaped movement frames
        SessionKey key = new SessionKey(new UUID(0xAE, 1), 1); long origin = System.nanoTime() - 600 * S;
        PacketPayload mv = new PacketPayload.Movement(new PacketPayload.Position(1.5, 64, 2.5), new PacketPayload.Rotation(90f, 10f), true, false);
        java.util.function.Supplier<Object> fullShare = () -> { DecisionCapture c = new DecisionCapture(new ReplayQuota(), 1, origin); long now = System.nanoTime();
            for (long s = 1; s <= 2000; s++) c.observe(new Observation(key, s, now + s * TICK / 4, 1, new Observation.Attempt(s, s, mv)), 1, true, "ENFORCE", () -> DecisionCapture.Cursor.ZERO, 32, now + s * TICK / 4);
            return c; };
        out.put("decisionCaptureFullShareHeapBytes", Math.round(measure(fullShare)));
        out.put("decisionCaptureFullShareChargedBytes", ((DecisionCapture) fullShare.get()).rolling().bytes());
        out.put("rollingShareBudgetBytes", 256L << 10);
        Map<String, Object> per = new LinkedHashMap<>();
        MitigationActor la = actor(env); EvidenceLedger ll = new EvidenceLedger(env.key); long[] seq = { 10_000 }, t = { T0 };
        la.onAnchorFacts(env.facts(1, T0, MitigationActorTest.ANCHOR), 0, env.ps.scene.world.currentView());
        per.put("legitimateStepBytes(ledger+actor)", alloc(() -> { seq[0] += 10; t[0] += TICK; var o = ll.ingest(EvidenceTestSupport.valid(env.key, 0, seq[0], seq[0] + 10, t[0])); la.onEvidence(o, Optional.empty(), ll.snapshot(), false, env.cs(t[0], seq[0] + 10)); }));
        MitigationActor aa = actor(env);
        per.put("anchorAdmissionBytes(incl. clearance query)", alloc(() -> { seq[0] += 10; t[0] += TICK; aa.onAnchorFacts(env.facts(seq[0], t[0], MitigationActorTest.ANCHOR), 0, env.ps.scene.world.currentView()); }));
        DecisionCapture dc = new DecisionCapture(new ReplayQuota(), 1, origin); long[] s = { 1 }, now = { System.nanoTime() };
        per.put("observeExportAppendBytes", alloc(() -> { s[0]++; now[0] += TICK / 4; dc.observe(new Observation(key, s[0], now[0], 1, new Observation.Attempt(s[0], s[0], mv)), 1, true, "ENFORCE", () -> DecisionCapture.Cursor.ZERO, 32, now[0]); }));
        out.put("perOperationAllocationBytes", per);
        out.put("perSessionRetained", "one MitigationRunner + MitigationActor (above) + one DecisionCapture (its rolling share is charged to the global 48 MiB ROLLING pool, q=min(256 KiB, 48 MiB/N)); intents, decisions and capsules are bounded values");
        Path p = Path.of(args[0]); Files.createDirectories(p.getParent()); Files.writeString(p, MiniJson.write(out) + "\n", StandardCharsets.UTF_8);
        System.out.println(MiniJson.write(out));
    }
}
