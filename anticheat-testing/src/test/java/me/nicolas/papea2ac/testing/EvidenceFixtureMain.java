package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.EvidenceTestSupport.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nicolas.papea2ac.evidence.EvidenceBounds;
import me.nicolas.papea2ac.evidence.EvidenceEvent;
import me.nicolas.papea2ac.evidence.EvidenceLedger;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.InvalidCode;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.UncertainCode;
import me.nicolas.papea2ac.evidence.ValidationRecord;
/**
 * Writes the Phase 9 evidence artifacts under docs/phase9-artifacts: the false-evidence gate over the whole Phase 8 legitimate corpus
 * (32 314 transitions -> positive evidence must be 0), the impossible-movement evidence corpus (one deduplicated event per complete
 * INVALID, per family, with magnitudes), the duplicate-delivery results, the window / ordering / epoch / session results, and the
 * deterministic evidence fixtures (digests of events and snapshots of pinned record streams). Exits 1 on any false positive evidence.
 */
public final class EvidenceFixtureMain {
    private EvidenceFixtureMain() {}
    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args.length > 0 ? args[0] : "docs/phase9-artifacts");
        // ---- legitimate corpora through evidence (one ledger per case)
        EvidenceCorpus.Sink legit = new EvidenceCorpus.Sink("legitimate", false);
        ValidationCorpus.Tally.SINK.set(legit);
        Map<String, Object> classes = new LinkedHashMap<>();
        try {
            ValidationCorpus.Tally fz = ValidationCorpus.fuzzCorpus(90_000, 2_000); classes.put("timing-fuzz", fz.json());
            ValidationCorpus.Tally vel = ValidationCorpus.velocityCorpus(7_000, 1_500, true); classes.put("velocity", vel.json());
            ValidationCorpus.Tally velNo = ValidationCorpus.velocityCorpus(9_000, 200, false); classes.put("velocity-no-tick-ends", velNo.json());
            ValidationCorpus.Tally tp = ValidationCorpus.teleportCorpus(30_000, 500); classes.put("teleport", tp.json());
            List<String> failures = new ArrayList<>(); ValidationCorpus.Tally nat = ValidationCorpus.nativeCorpus(TimelineFixtureDeterminismTest.repoRoot().resolve("docs/phase9-artifacts/physics-fixtures/native-trajectories.json"), failures); classes.put("native-trajectories", nat.json()); if (!failures.isEmpty()) throw new IllegalStateException("native corpus failures: " + failures);
        } finally { ValidationCorpus.Tally.SINK.remove(); }
        Map<String, Object> fe = new LinkedHashMap<>(); fe.put("phase", "9"); fe.put("gate", "PHASE 8 LEGITIMATE CORPUS THROUGH EVIDENCE: positive evidence events must be 0 (the two SEARCH_LIMIT UNCERTAINs included)");
        fe.putAll(legit.json()); fe.put("phase8Classes", classes);
        ValidationCorpus.write(dir.resolve("false-evidence-results.json"), fe);
        System.out.println("legitimate: " + legit.json());
        // ---- impossible corpus through evidence
        EvidenceCorpus.Sink imp = new EvidenceCorpus.Sink("impossible", true);
        ValidationCorpus.Tally.SINK.set(imp);
        Map<String, ValidationCorpus.Tally> tallies;
        try { tallies = ValidationCorpus.impossibleCorpus(7_000, 250); } finally { ValidationCorpus.Tally.SINK.remove(); }
        Map<String, Object> ec = new LinkedHashMap<>(); ec.put("phase", "9"); ec.put("method", "the Phase 8 impossible corpus (controlled observation mutations of legitimate velocity-corpus transmissions, one mutated transmission per case) projected through core's mapper into one evidence ledger per case; every complete INVALID must create exactly one deduplicated EvidenceEvent; magnitudes are the Phase 8 residual excesses of the exhausted set");
        ec.putAll(imp.json());
        Map<String, Object> fam = new LinkedHashMap<>(); for (var e : tallies.entrySet()) { Map<String, Object> f = new LinkedHashMap<>(); f.put("phase8", e.getValue().json()); fam.put(e.getKey(), f); }
        ec.put("phase8Families", fam);
        ValidationCorpus.write(dir.resolve("evidence-corpus-results.json"), ec);
        System.out.println("impossible: " + imp.json());
        // ---- duplicates: every retained impossible chain delivered twice
        imp.duplicatePass();
        Map<String, Object> dd = new LinkedHashMap<>(); dd.put("phase", "9"); dd.put("method", "every record of every impossible-corpus chain delivered twice in a row into a fresh ledger"); dd.put("chains", imp.retained.size()); dd.put("singleDeliveryEvents", imp.events); dd.put("doubleDeliveryEvents", imp.duplicatePassEvents); dd.put("duplicatesReported", imp.duplicatePassDuplicates); dd.put("gate", "doubleDeliveryEvents == singleDeliveryEvents and duplicatesReported == singleDeliveryEvents");
        ValidationCorpus.write(dir.resolve("dedup-results.json"), dd);
        // ---- window / ordering / epoch / session results (synthetic pinned streams)
        Map<String, Object> win = new LinkedHashMap<>(); win.put("phase", "9"); win.put("windowNanos", EvidenceBounds.DEFAULT.windowNanos()); win.put("bounds", Map.of("maxEvents", EvidenceBounds.DEFAULT.maxEvents(), "maxGroups", EvidenceBounds.DEFAULT.maxGroups(), "dedupIds", EvidenceBounds.DEFAULT.dedupIds(), "supportingIds", EvidenceBounds.DEFAULT.supportingIds(), "gapRecords", EvidenceBounds.DEFAULT.gapRecords()));
        Map<String, Object> patterns = new LinkedHashMap<>();
        for (String p : new String[] { "III", "IVI", "IUI", "VIV", "UIU", "IIVVI", "IIUUI", "VVVVV", "UUUUU" }) patterns.put(p, EvidenceCorpus.snapshotJson(EvidenceLedgerTest.feed(p, UncertainCode.SEARCH_LIMIT).snapshot()));
        win.put("patterns", patterns);
        EvidenceLedger exp = new EvidenceLedger(SESSION); exp.ingest(invalid(100, 102, 0)); exp.ingest(invalid(102, 104, 10_000_000_000L)); exp.ingest(valid(104, 106, 35_000_000_000L));
        Map<String, Object> expiry = new LinkedHashMap<>(); expiry.put("atLatestIngest(35s)", EvidenceCorpus.snapshotJson(exp.snapshot())); expiry.put("at45s", EvidenceCorpus.snapshotJson(exp.snapshot(45_000_000_000L))); win.put("expiry", expiry);
        EvidenceLedger ep = new EvidenceLedger(SESSION); ep.ingest(invalid(SESSION, 0, 1, 3, 0, List.of(InvalidCode.NO_LEGAL_INPUT), 0, 0)); ep.ingest(invalid(SESSION, 0, 3, 5, TICK, List.of(InvalidCode.NO_LEGAL_INPUT), 0, 0)); ep.ingest(invalid(SESSION, 1, 5, 7, 2 * TICK, List.of(InvalidCode.NO_LEGAL_INPUT), 0, 0));
        win.put("worldEpochSegmentation", EvidenceCorpus.snapshotJson(ep.snapshot()));
        EvidenceLedger big = new EvidenceLedger(SESSION); long seq = 0; for (int i = 0; i < 400; i++) { big.ingest(invalid(seq, seq + 1, i * TICK)); seq += 10; }
        win.put("fullBoundedState", EvidenceCorpus.snapshotJson(big.snapshot()));
        ValidationCorpus.write(dir.resolve("window-results.json"), win);
        // ---- deterministic fixtures: pinned record streams -> event digests + snapshot digests
        Map<String, Object> fx = new LinkedHashMap<>(); fx.put("phase", "9"); fx.put("source", "EvidenceFixtureMain: pinned synthetic Phase 8 result projections (the same streams as EvidenceLedgerTest) replayed into fresh ledgers; digests are content digests of the events and of the snapshot JSON");
        Map<String, Object> cases = new LinkedHashMap<>();
        for (String p : new String[] { "III", "IVI", "IUI", "VIV", "UIU", "IIVVI" }) {
            EvidenceLedger l = EvidenceLedgerTest.feed(p, UncertainCode.CLIMBABLE_CONTACT); Map<String, Object> c = new LinkedHashMap<>();
            List<String> ids = new ArrayList<>(); for (EvidenceEvent e : l.events()) ids.add(e.id()); c.put("eventIds", ids); c.put("snapshot", EvidenceCorpus.snapshotJson(l.snapshot()));
            c.put("digest", EvidenceCorpus.sha256(MiniJson.write(c))); cases.put(p, c);
        }
        Map<String, Object> corrupted = new LinkedHashMap<>();
        ValidationRecord ok = invalid(100, 102, 0);
        corrupted.put("noProof", EvidenceEvent.check(new ValidationRecord(ok.session(), 0, 100, 102, 0, ok.classification(), ok.completeness(), List.of(), ok.invalidReasons(), ValidationRecord.ProofSummary.NONE, ok.residual(), ok.work(), ok.provenance(), 0)).map(Enum::name).orElse("ACCEPTED"));
        corrupted.put("uncertaintyPresent", EvidenceEvent.check(new ValidationRecord(ok.session(), 0, 100, 102, 0, ok.classification(), ok.completeness(), List.of(UncertainCode.SEARCH_LIMIT), ok.invalidReasons(), ok.proof(), ok.residual(), ok.work(), ok.provenance(), 0)).map(Enum::name).orElse("ACCEPTED"));
        corrupted.put("frontierIncomplete", EvidenceEvent.check(new ValidationRecord(ok.session(), 0, 100, 102, 0, ok.classification(), new ValidationRecord.Completeness(true, true, true, true, true, true, true, true, true, true, true, true, false), List.of(), ok.invalidReasons(), ok.proof(), ok.residual(), ok.work(), ok.provenance(), 0)).map(Enum::name).orElse("ACCEPTED"));
        corrupted.put("complete", EvidenceEvent.check(ok).map(Enum::name).orElse("ACCEPTED"));
        fx.put("cases", cases); fx.put("proofGate", corrupted);
        ValidationCorpus.write(dir.resolve("evidence-fixtures").resolve("evidence-fixtures.json"), fx);
        System.out.println("evidence fixtures: " + cases.size() + " streams; duplicates: single=" + imp.events + " double=" + imp.duplicatePassEvents + " dup=" + imp.duplicatePassDuplicates);
        if (legit.events != 0 || legit.rejected != 0) { System.err.println("FALSE EVIDENCE: legitimate corpus produced " + legit.events + " positive events / " + legit.rejected + " rejections"); System.exit(1); }
        if (imp.duplicatePassEvents != imp.events) { System.err.println("DUPLICATE GATE: " + imp.duplicatePassEvents + " != " + imp.events); System.exit(1); }
    }
}
