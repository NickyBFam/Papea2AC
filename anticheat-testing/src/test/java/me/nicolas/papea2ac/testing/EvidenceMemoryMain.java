package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.EvidenceTestSupport.*;
import static me.nicolas.papea2ac.testing.TimingMemoryMain.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import me.nicolas.papea2ac.core.EvidenceRunner;
import me.nicolas.papea2ac.evidence.*;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.InvalidCode;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.UncertainCode;
import me.nicolas.papea2ac.model.SessionKey;
/**
 * Empirical memory harness for Phase 9 (same methodology as the Phase 2-8 harnesses: forced GC, used-heap diff, 3 trials, median;
 * estimates on one host, never a supported-capacity claim) plus thread-allocation measurements: an empty session evidence ledger, a typical
 * active state (a handful of events among VALIDs and gaps), the full bounded state (256 events / 128 groups / 512 ids), one EvidenceEvent,
 * one EvidenceSnapshot (empty / typical / full), 100 retained snapshots, the core EvidenceRunner (two ledgers + rings) and the per-ingest
 * allocation per classification.
 */
public final class EvidenceMemoryMain {
    private EvidenceMemoryMain() {}
    static EvidenceLedger typical() { EvidenceLedger l = new EvidenceLedger(SESSION); long seq = 0, nanos = 0; for (int i = 0; i < 200; i++) { seq += 2; nanos += TICK; int k = i % 20; l.ingest(k == 7 || k == 8 ? invalid(seq - 2, seq, nanos) : k == 13 ? uncertain(seq - 2, seq, nanos, UncertainCode.SEARCH_LIMIT) : valid(seq - 2, seq, nanos)); } return l; }
    static EvidenceLedger full() { EvidenceLedger l = new EvidenceLedger(SESSION); long seq = 0; for (int i = 0; i < 600; i++) { l.ingest(invalid(SESSION, 0, seq, seq + 1, i * TICK, List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.HORIZONTAL_EXCESS, InvalidCode.VERTICAL_EXCESS, InvalidCode.IMPOSSIBLE_ASCENT), 0.2, 0.5)); seq += 10; } for (UncertainCode c : UncertainCode.values()) l.ingest(uncertain(seq + c.ordinal() * 2, seq + 1 + c.ordinal() * 2, 700 * TICK + c.ordinal(), c)); return l; }
    public static void main(String[] args) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>(); out.put("phase", "9 - Evidence Engine"); out.put("method", "forced GC + used-heap diff, 3 trials, median; thread-allocation counters for per-ingest garbage; estimates on one host, not capacity");
        out.put("emptyLedgerBytes", Math.round(measure(() -> new EvidenceLedger(SESSION))));
        out.put("typicalLedgerBytes", Math.round(measure(EvidenceMemoryMain::typical)));
        out.put("fullBoundedLedgerBytes", Math.round(measure(EvidenceMemoryMain::full)));
        out.put("bounds", "events <= " + EvidenceBounds.DEFAULT.maxEvents() + ", groups <= " + EvidenceBounds.DEFAULT.maxGroups() + ", dedup ids <= " + EvidenceBounds.DEFAULT.dedupIds() + ", gap records <= " + EvidenceBounds.DEFAULT.gapRecords() + ", supporting ids <= " + EvidenceBounds.DEFAULT.supportingIds() + "; window " + EvidenceBounds.DEFAULT.windowNanos() / 1_000_000L + " ms; no per-player unbounded history, nothing keyed by UUID beyond the session map");
        out.put("eventBytes", Math.round(measure(() -> EvidenceEvent.from(invalid(1, 3, 0)).orElseThrow())));
        EvidenceLedger e = new EvidenceLedger(SESSION), t = typical(), f = full();
        out.put("snapshotEmptyBytes", Math.round(measure(e::snapshot))); out.put("snapshotTypicalBytes", Math.round(measure(t::snapshot))); out.put("snapshotFullBytes", Math.round(measure(f::snapshot)));
        out.put("retainedBytesPer100TypicalSnapshots", Math.round(measure(() -> { List<Object> l = new ArrayList<>(); for (int i = 0; i < 100; i++) l.add(t.snapshot()); return l; })));
        out.put("runnerEmptyBytes", Math.round(measure(() -> new EvidenceRunner(SESSION, new EvidenceRunner.Metrics()))));
        Map<String, Object> per = new LinkedHashMap<>();
        long[] seq = { 10_000 }; long[] nanos = { 10_000L * TICK };
        EvidenceLedger lv = new EvidenceLedger(SESSION); per.put("validIngestBytes", alloc(() -> { seq[0] += 2; nanos[0] += TICK; lv.ingest(valid(seq[0] - 2, seq[0], nanos[0])); }));
        EvidenceLedger lu = new EvidenceLedger(SESSION); per.put("uncertainIngestBytes", alloc(() -> { seq[0] += 2; nanos[0] += TICK; lu.ingest(uncertain(seq[0] - 2, seq[0], nanos[0], UncertainCode.CLIMBABLE_CONTACT)); }));
        EvidenceLedger li = new EvidenceLedger(SESSION); per.put("invalidIngestBytes(newEvent)", alloc(() -> { seq[0] += 20; nanos[0] += TICK; li.ingest(invalid(seq[0] - 2, seq[0], nanos[0])); }));
        EvidenceLedger ld = new EvidenceLedger(SESSION); ValidationRecord dup = invalid(1, 2, 0); ld.ingest(dup); per.put("duplicateIngestBytes", alloc(() -> ld.ingest(dup)));
        per.put("snapshotTypicalAllocBytes", alloc(t::snapshot)); per.put("snapshotFullAllocBytes", alloc(f::snapshot));
        out.put("perOperationAllocationBytes", per);
        out.put("perSessionRetained", "one EvidenceRunner = live + side EvidenceLedger (above) + two 16-line rings + the published EvidenceStatus; events, records and snapshots are values the caller drops");
        Path p = Path.of(args[0]); Files.createDirectories(p.getParent()); Files.writeString(p, MiniJson.write(out) + "\n", StandardCharsets.UTF_8);
        System.out.println(MiniJson.write(out));
    }
}
