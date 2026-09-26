package me.nicolas.papea2ac.testing;
import org.openjdk.jmh.annotations.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import me.nicolas.papea2ac.evidence.*;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.BoundKind;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.Classification;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.InvalidCode;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.UncertainCode;
import me.nicolas.papea2ac.model.SessionKey;
/**
 * Phase 9 JMH kernels (JMH 1.37, 3 forks, 5x1s warmup, 10x1s measurement, -prof gc). EVIDENCE-ONLY kernels: ingest of a VALID, an
 * UNCERTAIN, a fresh INVALID (a new event: digest + grouping), a duplicate INVALID, snapshots with 0 / 1 / many active events, a window
 * expiry snapshot, a world-epoch segment, a session replacement (new ledger), a mixed 100-record stream into a fresh ledger, and the
 * full bounded state (256 events / 128 groups) ingest. END-TO-END: Phase 8 validation + Phase 9 projection + ingest for one legitimate and
 * one impossible transition (see ValidationBenchmarks for the Phase 8 cost alone). Descriptive kernels on one host, never a capacity claim.
 */
@BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1) @Measurement(iterations = 10, time = 1) @Fork(value = 3, jvmArgsAppend = { "-Xms512m", "-Xmx512m" })
@State(Scope.Thread)
public class EvidenceBenchmarks {
    static final SessionKey KEY = new SessionKey(new UUID(9, 42), 1); static final long TICK = 50_000_000L;
    static final ValidationRecord.Provenance PROV = new ValidationRecord.Provenance("paper-26.2-124-timing", "paper-26.2-124-movement", "paper-26.2-124-movement-observation", "paper-26.2-124-collision", 7, 0, false);
    static final ValidationRecord.ProofSummary PROOF = new ValidationRecord.ProofSummary(true, 1, 1, 144, 144, 100, 44, 0, "paper-26.2-124-movement-observation", "paper-26.2-124-movement", "paper-26.2-124-collision", "paper-26.2-124-timing", 7, List.of("h1"));
    static ValidationRecord invalid(long start, long end, long nanos, double h) { return new ValidationRecord(KEY, 0, start, end, nanos, Classification.INVALID, ValidationRecord.Completeness.ALL, List.of(), List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.HORIZONTAL_EXCESS), PROOF, new ValidationRecord.ResidualSummary(true, 0.5, 0, 0.3274, 0.42, 0, BoundKind.ENVELOPE, h, 0), new ValidationRecord.WorkSummary(1, 1, 0, 0, 1, 144), PROV, 0); }
    static ValidationRecord valid(long start, long end, long nanos) { return new ValidationRecord(KEY, 0, start, end, nanos, Classification.VALID, ValidationRecord.Completeness.ALL, List.of(), List.of(), ValidationRecord.ProofSummary.NONE, ValidationRecord.ResidualSummary.NONE, new ValidationRecord.WorkSummary(1, 1, 1, 2, 1, 144), PROV, 1); }
    static ValidationRecord uncertain(long start, long end, long nanos) { return new ValidationRecord(KEY, 0, start, end, nanos, Classification.UNCERTAIN, new ValidationRecord.Completeness(true, false, true, true, true, true, true, true, true, true, false, true, true), List.of(UncertainCode.SEARCH_LIMIT), List.of(), ValidationRecord.ProofSummary.NONE, ValidationRecord.ResidualSummary.NONE, new ValidationRecord.WorkSummary(1, 0, 0, 0, 1, 144), PROV, 0); }

    private EvidenceLedger validLedger, uncertainLedger, invalidLedger, dupLedger, emptyLedger, oneLedger, manyLedger, fullLedger, epochLedger; private long seq, nanos; private ValidationRecord dup; private List<ValidationRecord> mixed; private long epochToggle;
    private me.nicolas.papea2ac.simulation.MovementValidator validator; private me.nicolas.papea2ac.simulation.MovementValidationRequest legit, impossible; private EvidenceLedger e2eLedger; private long e2eSeq;

    @Setup public void setup() {
        validLedger = new EvidenceLedger(KEY); uncertainLedger = new EvidenceLedger(KEY); invalidLedger = new EvidenceLedger(KEY); dupLedger = new EvidenceLedger(KEY); emptyLedger = new EvidenceLedger(KEY); epochLedger = new EvidenceLedger(KEY);
        seq = 1_000; nanos = 1_000_000_000L; dup = invalid(10, 12, 0, 0.1); dupLedger.ingest(dup);
        oneLedger = new EvidenceLedger(KEY); oneLedger.ingest(invalid(10, 12, 0, 0.1));
        manyLedger = new EvidenceLedger(KEY); long s = 0; for (int i = 0; i < 64; i++) { manyLedger.ingest(invalid(s, s + 1, i * TICK, 0.1)); s += 10; }
        fullLedger = new EvidenceLedger(KEY); s = 0; for (int i = 0; i < 400; i++) { fullLedger.ingest(invalid(s, s + 1, i * TICK, 0.1)); s += 10; }
        mixed = new ArrayList<>(); SplittableRandom r = new SplittableRandom(9); long ms = 0, mn = 0; for (int i = 0; i < 100; i++) { int k = r.nextInt(100); mn += TICK; mixed.add(k < 60 ? valid(ms, ms + 2, mn) : k < 85 ? uncertain(ms, ms + 2, mn) : invalid(ms, ms + 2, mn, 0.1)); ms += 2; }
        // end to end: a Phase 8 validation + projection + ingest (the Phase 8 kernels of ValidationBenchmarks reused)
        ValidationBenchmarks vb = new ValidationBenchmarks(); vb.setup(); validator = vb.validator(); legit = vb.validSimple(); impossible = vb.invalidHorizontal(); e2eLedger = new EvidenceLedger(KEY);
    }
    // ---- evidence-only kernels
    @Benchmark public EvidenceLedger.Outcome validIngest() { seq += 2; nanos += TICK; return validLedger.ingest(valid(seq, seq + 2, nanos)); }
    @Benchmark public EvidenceLedger.Outcome uncertainIngest() { seq += 2; nanos += TICK; return uncertainLedger.ingest(uncertain(seq, seq + 2, nanos)); }
    @Benchmark public EvidenceLedger.Outcome invalidIngest() { seq += 20; nanos += TICK; return invalidLedger.ingest(invalid(seq, seq + 2, nanos, 0.1)); }
    @Benchmark public EvidenceLedger.Outcome duplicateInvalidIngest() { return dupLedger.ingest(dup); }
    @Benchmark public EvidenceSnapshot snapshotEmpty() { return emptyLedger.snapshot(); }
    @Benchmark public EvidenceSnapshot snapshotOneEvent() { return oneLedger.snapshot(); }
    @Benchmark public EvidenceSnapshot snapshotManyEvents() { return manyLedger.snapshot(); }
    @Benchmark public EvidenceSnapshot snapshotFullBounded() { return fullLedger.snapshot(); }
    @Benchmark public EvidenceSnapshot windowExpirySnapshot() { return manyLedger.snapshot(64 * TICK + 60_000_000_000L); }
    @Benchmark public EvidenceLedger.Outcome worldEpochSegment() { seq += 2; nanos += TICK; epochToggle++; return epochLedger.ingest(new ValidationRecord(KEY, epochToggle, seq, seq + 2, nanos, Classification.VALID, ValidationRecord.Completeness.ALL, List.of(), List.of(), ValidationRecord.ProofSummary.NONE, ValidationRecord.ResidualSummary.NONE, new ValidationRecord.WorkSummary(1, 1, 1, 2, 1, 144), PROV, 1)); }
    @Benchmark public EvidenceSnapshot sessionReplacement() { return new EvidenceLedger(new SessionKey(KEY.player(), 2)).snapshot(); }
    @Benchmark public EvidenceSnapshot mixedStream100() { EvidenceLedger l = new EvidenceLedger(KEY); for (ValidationRecord r : mixed) l.ingest(r); return l.snapshot(); }
    @Benchmark public EvidenceLedger.Outcome fullBoundedIngest() { seq += 20; nanos += TICK; return fullLedger.ingest(invalid(seq, seq + 2, nanos, 0.1)); }
    // ---- end to end: Phase 8 validation + Phase 9 projection + ingest
    @Benchmark public EvidenceLedger.Outcome endToEndValidTransition() { var r = validator.validate(legit); e2eSeq += 2; return e2eLedger.ingest(me.nicolas.papea2ac.core.ValidationEvidenceMapper.record(KEY, r, e2eSeq * TICK)); }
    @Benchmark public EvidenceLedger.Outcome endToEndInvalidTransition() { var r = validator.validate(impossible); e2eSeq += 2; return new EvidenceLedger(KEY).ingest(me.nicolas.papea2ac.core.ValidationEvidenceMapper.record(KEY, r, e2eSeq * TICK)); } // a fresh ledger: the fresh-event path, not the duplicate path
}
