package me.nicolas.papea2ac.testing;
import org.openjdk.jmh.annotations.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import me.nicolas.papea2ac.core.DecisionCapture;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.InvalidCode;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.UncertainCode;
import me.nicolas.papea2ac.evidence.ValidationRecord;
import me.nicolas.papea2ac.mitigation.*;
import me.nicolas.papea2ac.mitigation.MitigationActor.EffectMode;
import me.nicolas.papea2ac.mitigation.MitigationVocabulary.Reason;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.replay.*;
import me.nicolas.papea2ac.schema.TransportCapsule;

/**
 * Phase 10 JMH kernels (JMH 1.37, 3 forks, 5x1s warmup, 10x1s measurement, -prof gc). They run against the REAL observer world and collision
 * engine of the Phase 10 actor test harness (MitigationActorTest.H0, on the classpath through the test output).
 *
 * <p>DECISION kernels (shard thread, per validation result): a legitimate step (VALID: chain reset, recovery check); an UNCERTAIN step; anchor
 * admission of a VALID result (facts plus the standing-box clearance query); a complete OBSERVE_ONLY eligibility cycle (anchor, four
 * independent groups, target selection, re-certification, suppression, then the VALID reset); an ENFORCE proposal (G2 gate, intent, action
 * id, capture request) ended by its deadline; the action-id digest.
 *
 * <p>CAPSULE kernels: DecisionCapture.observe (export plus rolling append, per observation on the shard); promotion of a full 256 KiB share
 * (per decision); encode plus deflate of that capsule (I/O thread, per capsule, excluding fsync; TC25 measures the durable latency).
 * Every kernel's path is checked once in @Setup. Descriptive, one host, never a capacity claim.
 */
@BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1) @Measurement(iterations = 10, time = 1) @Fork(value = 3, jvmArgsAppend = { "-Xms512m", "-Xmx512m" })
@State(Scope.Thread)
public class MitigationBenchmarks {
    static final long S = 1_000_000_000L, T0 = 100 * S, TICK = 50_000_000L;
    static final List<InvalidCode> H = List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.HORIZONTAL_EXCESS);
    private MitigationActorTest.H0 legit, uncertain, admit, cycle, enforce, insufficient, unsafe, duplicate, fullEnv, mixedEnv;
    private ValidationRecord dupRecord; private List<ValidationRecord> mixed; private long unsafeBase = 1_000_000, unsafeT = T0, insuffBase = 1_000_000, insuffT = T0, fullSeq = 10_000_000, fullT = T0 + 10_000 * S, mixedRound;
    private long seq = 10_000, nanos = T0, cycleBase = 1_000_000, cycleT = T0, enforceBase = 1_000_000, enforceT = T0;
    private DecisionCapture capture; private long obsSeq = 1, obsNow; private Observation movement; private SessionKey obsKey;
    private RollingCapture full; private TransportCapsule fullCapsule; private byte[] scratch = new byte[1 << 20];

    @Setup public void setup() {
        legit = new MitigationActorTest.H0(); uncertain = new MitigationActorTest.H0(); admit = new MitigationActorTest.H0(); cycle = new MitigationActorTest.H0(); enforce = new MitigationActorTest.H0(); enforce.mode = EffectMode.ENFORCE;
        legit.anchor(1, T0, MitigationActorTest.ANCHOR); uncertain.anchor(1, T0, MitigationActorTest.ANCHOR);
        insufficient = new MitigationActorTest.H0(); unsafe = new MitigationActorTest.H0(); unsafe.dispute = true; duplicate = new MitigationActorTest.H0(); fullEnv = new MitigationActorTest.H0(); mixedEnv = new MitigationActorTest.H0();
        dupRecord = EvidenceTestSupport.invalid(duplicate.key, 0, 100, 110, T0, H, 0.2, 0); duplicate.feed(dupRecord);
        // full bounded state: 64+ anchors, both chains and the group-family map at their bounds
        long fs = 0, ft = T0; for (int i = 0; i < 200; i++) { fs += 10;  ft += TICK; fullEnv.anchor(fs, ft, MitigationActorTest.ANCHOR); }
        for (int i = 0; i < 160; i++) { fs += 100; ft += S / 10; fullEnv.feed(EvidenceTestSupport.invalid(fullEnv.key, 0, fs, fs + 10, ft, H, 0.2, 0)); }
        fullSeq = fs + 1_000; fullT = ft + S;
        // mixed stream: 100 records, 60 % VALID (with anchors), 25 % UNCERTAIN, 15 % INVALID, deterministic
        mixed = new ArrayList<>(); SplittableRandom r = new SplittableRandom(10);
        for (int i = 0; i < 100; i++) { int k = r.nextInt(100); long a = 10L * i; mixed.add(k < 60 ? EvidenceTestSupport.valid(mixedEnv.key, 0, a, a + 10, (i + 1) * TICK) : k < 85 ? EvidenceTestSupport.uncertain(mixedEnv.key, 0, a, a + 10, (i + 1) * TICK, List.of(UncertainCode.CLIMBABLE_CONTACT), new ValidationRecord.Completeness(true, false, true, true, true, true, true, true, true, true, false, true, true)) : EvidenceTestSupport.invalid(mixedEnv.key, 0, a, a + 10, (i + 1) * TICK, H, 0.2, 0)); }
        // path checks: every kernel measures the path its name says
        if (observeOnlyCycle().reason() != Reason.MODE_OBSERVE_ONLY) throw new IllegalStateException("cycle path");
        if (insufficientEvidence().reason() != Reason.INSUFFICIENT_EVIDENCE) throw new IllegalStateException("insufficient path");
        if (suppressedUnsafeTarget().reason() != Reason.BLOCK_DISPUTE) throw new IllegalStateException("unsafe path");
        if (duplicateEvidence().outcome() != me.nicolas.papea2ac.mitigation.MitigationVocabulary.DecisionOutcome.NONE) throw new IllegalStateException("duplicate path");
        if (enforceProposeExpire() != 1) throw new IllegalStateException("enforce path");
        admissionOfValidResult(); if (admit.actor.status().anchors() == 0) throw new IllegalStateException("admission path: " + admit.actor.status().lastAnchorRefusal());
        // capsule kernels: protocol-shaped movement observations through the production DecisionCapture
        obsKey = new SessionKey(new UUID(0xB10L, 1), 1); obsNow = System.nanoTime();
        capture = new DecisionCapture(new ReplayQuota(), 1, obsNow - 600 * S);
        PacketPayload mv = new PacketPayload.Movement(new PacketPayload.Position(1.5, 64, 2.5), new PacketPayload.Rotation(90f, 10f), true, false);
        movement = new Observation(obsKey, 1, obsNow, 1, new Observation.Attempt(1, 1, mv));
        full = new RollingCapture(256 << 10); long s = 1, t = System.nanoTime();
        for (int i = 0; i < 4000; i++) { t += TICK / 4; if (full.checkpointDue(t)) full.checkpoint(new TransportCapsule.Checkpoint(t - (obsNow - 600 * S), s, 1, 1, "PLAY", true, "ENFORCE", 0, 0, 0, 0), t);
            try { full.append(TransportReplayMapper.export(new Observation(obsKey, s, t, 1, new Observation.Attempt(s, s, mv)), obsNow - 600 * S, t), t); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
            s++; }
        fullCapsule = full.promote("p", "e".repeat(64), "r", 1).capsule().orElseThrow();
    }
    // ---- decision kernels
    @Benchmark public MitigationActor.Decision legitimateStep() { seq += 10; nanos += TICK; return legit.feed(EvidenceTestSupport.valid(legit.key, 0, seq, seq + 10, nanos)); }
    @Benchmark public MitigationActor.Decision uncertainStep() { seq += 10; nanos += TICK; return uncertain.feed(EvidenceTestSupport.uncertain(uncertain.key, 0, seq, seq + 10, nanos, List.of(UncertainCode.CLIMBABLE_CONTACT), new ValidationRecord.Completeness(true, false, true, true, true, true, true, true, true, true, false, true, true))); }
    @Benchmark public int admissionOfValidResult() { seq += 10; nanos += TICK; admit.anchor(seq, nanos, MitigationActorTest.ANCHOR); return admit.actor.status().anchors(); }
    @Benchmark public MitigationActor.Decision observeOnlyCycle() {
        cycleBase += 10_000; cycleT += 5 * S; cycle.anchor(cycleBase, cycleT, MitigationActorTest.ANCHOR); MitigationActor.Decision d = null;
        for (int i = 1; i <= 4; i++) d = cycle.feed(EvidenceTestSupport.invalid(cycle.key, 0, cycleBase + 100L * i, cycleBase + 100L * i + 10, cycleT + i * S / 10, H, 0.2, 0));
        cycle.feed(EvidenceTestSupport.valid(cycle.key, 0, cycleBase + 900, cycleBase + 910, cycleT + S)); return d;
    }
    @Benchmark public int enforceProposeExpire() {
        enforceBase += 10_000; enforceT += 5 * S; enforce.captured.clear(); enforce.anchor(enforceBase, enforceT, MitigationActorTest.ANCHOR);
        for (int i = 1; i <= 4; i++) enforce.feed(EvidenceTestSupport.invalid(enforce.key, 0, enforceBase + 100L * i, enforceBase + 100L * i + 10, enforceT + i * S / 10, H, 0.2, 0));
        enforce.actor.onTick(enforceT + S + MitigationBounds.DEFAULT.intentDeadlineNanos());
        return enforce.captured.size();
    }
    @Benchmark public MitigationActor.Decision insufficientEvidence() { insuffBase += 10_000; insuffT += 5 * S; insufficient.anchor(insuffBase, insuffT, MitigationActorTest.ANCHOR);
        MitigationActor.Decision d = insufficient.feed(EvidenceTestSupport.invalid(insufficient.key, 0, insuffBase + 100, insuffBase + 110, insuffT + S / 10, H, 0.2, 0));
        insufficient.feed(EvidenceTestSupport.valid(insufficient.key, 0, insuffBase + 200, insuffBase + 210, insuffT + S / 5)); return d; }
    @Benchmark public MitigationActor.Decision suppressedUnsafeTarget() {
        unsafeBase += 10_000; unsafeT += 5 * S; unsafe.anchor(unsafeBase, unsafeT, MitigationActorTest.ANCHOR); MitigationActor.Decision d = null;
        for (int i = 1; i <= 4; i++) d = unsafe.feed(EvidenceTestSupport.invalid(unsafe.key, 0, unsafeBase + 100L * i, unsafeBase + 100L * i + 10, unsafeT + i * S / 10, H, 0.2, 0));
        unsafe.feed(EvidenceTestSupport.valid(unsafe.key, 0, unsafeBase + 900, unsafeBase + 910, unsafeT + S)); return d;
    }
    @Benchmark public MitigationActor.Decision duplicateEvidence() { return duplicate.feed(dupRecord); }
    @Benchmark public MitigationActor.Decision fullBoundedStep() { fullSeq += 100; fullT += S / 10; fullEnv.anchor(fullSeq, fullT, MitigationActorTest.ANCHOR); return fullEnv.feed(EvidenceTestSupport.invalid(fullEnv.key, 0, fullSeq + 10, fullSeq + 20, fullT, H, 0.2, 0)); }
    @Benchmark public MitigationActor.Decision mixedStream100() { MitigationActorTest.H0 h = mixedEnv; MitigationActor.Decision d = null; long off = ++mixedRound * 10_000, toff = mixedRound * 100 * S;
        for (ValidationRecord v : mixed) { ValidationRecord x = new ValidationRecord(v.session(), v.worldEpoch(), v.startSequence() + off, v.endSequence() + off, v.nanos() + toff, v.classification(), v.completeness(), v.uncertainReasons(), v.invalidReasons(), v.proof(), v.residual(), v.work(), v.provenance(), v.witnesses());
            if (x.classification() == me.nicolas.papea2ac.evidence.EvidenceVocabulary.Classification.VALID) h.anchor(x.endSequence(), x.nanos(), MitigationActorTest.ANCHOR); d = h.feed(x); }
        return d; }
    @Benchmark public String actionIdDigest() { seq++; return MitigationIntent.actionId(SetbackPolicy.VERSION, legit.key, 0, List.of("g1", "g2", "g3", "g4"), false, seq); }
    // ---- capsule kernels
    @Benchmark public int observeExportAppend() { obsSeq++; obsNow += TICK / 4; capture.observe(new Observation(obsKey, obsSeq, obsNow, 1, new Observation.Attempt(obsSeq, obsSeq, ((Observation.Attempt) movement.event()).payload())), 1, true, "ENFORCE", () -> DecisionCapture.Cursor.ZERO, 32, obsNow); return capture.rolling().frames(); }
    @Benchmark public RollingCapture.Promotion promoteFullShare() { return full.promote("p", "e".repeat(64), "r", 1); }
    @Benchmark public int encodeDeflateFullShare() throws java.io.IOException {
        byte[] encoded = CapsuleCodec.encode(fullCapsule); Deflater d = new Deflater(Deflater.BEST_SPEED); d.setInput(encoded); d.finish(); int n = 0; while (!d.finished()) n += d.deflate(scratch); d.end(); return n;
    }
}
