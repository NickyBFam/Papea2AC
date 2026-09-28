package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.EvidenceTestSupport.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import me.nicolas.papea2ac.evidence.ValidationRecord;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.UncertainCode;
import me.nicolas.papea2ac.mitigation.*;
import me.nicolas.papea2ac.mitigation.MitigationActor.EffectMode;

/**
 * Phase 10 artifacts under docs/phase10-artifacts. From the synthetic mitigation corpus (MitigationCorpus.synthetic, the section-52 cases),
 * split by group: policy-corpus-results.json (plus the Phase 8/9 legitimate corpus, the impossible corpus and the count-level final live
 * trace), suppression-results.json, target-selection-results.json and setback-results.json. Also mitigation-fixtures/, the deterministic
 * case records with a SHA-256 manifest; the corpus is run twice and must be identical. Exits 1 on any mismatch, on any eligibility or intent
 * from a legitimate stream, or on non-determinism.
 */
public final class MitigationFixtureMain {
    private MitigationFixtureMain() {}
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]); Files.createDirectories(dir); boolean ok = true;
        List<MitigationCorpus.Result> results = MitigationCorpus.synthetic(), again = MitigationCorpus.synthetic();
        boolean deterministic = results.stream().map(MitigationCorpus.Result::json).toList().equals(again.stream().map(MitigationCorpus.Result::json).toList());
        if (!deterministic) { System.err.println("NON-DETERMINISTIC synthetic corpus"); ok = false; }
        Map<String, List<Object>> byGroup = new TreeMap<>(); int mismatches = 0;
        for (var r : results) { byGroup.computeIfAbsent(r.group(), k -> new ArrayList<>()).add(r.json()); if (!r.matches()) { mismatches++; System.err.println("MISMATCH " + r.json()); } }
        if (mismatches > 0) ok = false;

        // policy: synthetic policy cases + the Phase 8/9 corpora through a real ledger and actor (ENFORCE: eligibility precedes any target)
        MitigationCorpus.Sink legit = new MitigationCorpus.Sink("legitimate"); ValidationCorpus.Tally.SINK.set(legit);
        try {
            ValidationCorpus.fuzzCorpus(90_000, 2_000); ValidationCorpus.velocityCorpus(7_000, 1_500, true); ValidationCorpus.velocityCorpus(9_000, 200, false); ValidationCorpus.teleportCorpus(30_000, 500);
            List<String> failures = new ArrayList<>(); ValidationCorpus.nativeCorpus(ValidationCorpusTest.NATIVE, failures); if (!failures.isEmpty()) { System.err.println("native corpus: " + failures); ok = false; }
        } finally { ValidationCorpus.Tally.SINK.remove(); }
        if (legit.eligible != 0 || legit.proposed != 0 || legit.positive != 0) { System.err.println("LEGITIMATE STREAM ELIGIBLE: " + legit.json()); ok = false; }
        MitigationCorpus.Sink impossible = new MitigationCorpus.Sink("impossible"); ValidationCorpus.Tally.SINK.set(impossible);
        try { ValidationCorpus.impossibleCorpus(7_000, 250); } finally { ValidationCorpus.Tally.SINK.remove(); }
        if (impossible.eligible != 0 || impossible.proposed != 0) { System.err.println("single-INVALID cases became eligible: " + impossible.json()); ok = false; }
        Map<String, Object> live = liveCountLevel(); if (!Long.valueOf(0).equals(live.get("policyEligible"))) ok = false;

        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("policy", SetbackPolicy.VERSION); policy.put("requiredIndependentGroups", SetbackPolicy.REQUIRED_GROUPS);
        policy.put("rule", "4 consecutive independent Phase 9 correlation groups of the same positive evidence family, same SessionKey and world epoch, within the 30 s window, all complete/actionable; each group counts once (its largest member); any VALID or UNCERTAIN/incomplete observation between qualifying groups resets the chain; families never combine; no weighting");
        policy.put("syntheticCases", byGroup.getOrDefault("policy", List.of()));
        policy.put("legitimateCorpus(Phase 8/9, 32 451 transitions)", legit.json());
        policy.put("impossibleCorpus(single complete INVALID per case)", impossible.json());
        policy.put("finalPhase9LiveTrace(count level)", live);
        write(dir.resolve("policy-corpus-results.json"), policy);
        write(dir.resolve("suppression-results.json"), Map.of("cases", byGroup.getOrDefault("suppression", List.of()), "note", "every safety precondition refuses with its own typed reason; OBSERVE_ONLY/DISABLED record the would-be target and action id without applying anything"));
        write(dir.resolve("target-selection-results.json"), Map.of("cases", byGroup.getOrDefault("target", List.of()), "rule", "most recent certified safe anchor (VALID, all 13 dimensions, concrete, grounded, no pending teleport/influence/dispute, standing box CLEAR) of the same SessionKey and world epoch at or before the first qualifying group (side chain: the most recent live anchor); age <= 2 s and distance <= 8 blocks else RESYNC_REQUIRED; re-certified against the current world before proposing"));
        write(dir.resolve("setback-results.json"), Map.of("cases", byGroup.getOrDefault("setback", List.of()), "note", "intent lifecycle terminals: RECOVERED, EXPIRED, REJECTED, SUPERSEDED, RESYNC_REQUIRED; exactly one active correction, idempotent action ids, durable capsule before queueing"));

        // deterministic fixtures + manifest
        Path fx = dir.resolve("mitigation-fixtures"); Files.createDirectories(fx);
        Map<String, Object> manifest = new LinkedHashMap<>(); manifest.put("policy", SetbackPolicy.VERSION); manifest.put("deterministicRerun", deterministic);
        Map<String, Object> files = new LinkedHashMap<>();
        for (var e : byGroup.entrySet()) { String name = e.getKey() + "-cases.json"; String text = MiniJson.write(e.getValue()) + "\n"; Files.writeString(fx.resolve(name), text, StandardCharsets.UTF_8); files.put(name, sha256(text)); }
        manifest.put("files", files); manifest.put("cases", results.size()); manifest.put("mismatches", mismatches);
        write(fx.resolve("manifest.json"), manifest);
        System.out.println("synthetic cases=" + results.size() + " mismatches=" + mismatches + " deterministic=" + deterministic + " legit=" + legit.json() + " impossible=" + impossible.json());
        if (!ok) System.exit(1);
    }
    /** The final Phase 9 live result (22 183 transitions: 22 108 VALID, 75 UNCERTAIN, 0 INVALID) replayed at the count level in ENFORCE. */
    static Map<String, Object> liveCountLevel() {
        MitigationActorTest.H0 h = new MitigationActorTest.H0(); h.mode = EffectMode.ENFORCE; long t = MitigationCorpus.T0, seq = 0; int valid = 0, uncertain = 0;
        for (int i = 0; i < 22_183; i++) {
            t += 50_000_000L; long a = seq; seq += 10;
            if (i % 296 == 17 && uncertain < 75) { h.feed(uncertain(h.key, 0, a, seq, t, List.of(UncertainCode.WORLD_INCOMPLETE), new ValidationRecord.Completeness(true, false, false, true, true, true, true, true, true, true, true, true, true))); uncertain++; }
            else { h.anchor(seq, t, MitigationCorpus.ANCHOR); h.feed(valid(h.key, 0, a, seq, t)); valid++; }
        }
        Map<String, Object> m = new LinkedHashMap<>(); m.put("valid", (long) valid); m.put("uncertain", (long) uncertain); m.put("invalid", 0L);
        m.put("policyEligible", h.metrics.eligible.sum()); m.put("intentsProposed", h.metrics.proposed.sum()); m.put("setbacksApplied", (long) h.queued.size());
        m.put("note", "the exact live packet stream was not recorded; its complete Phase 9 outcome counts are replayed in a deterministic interleaving with certified anchors");
        return m;
    }
    static void write(Path p, Map<String, Object> json) throws Exception { Files.createDirectories(p.getParent()); Files.writeString(p, MiniJson.write(json) + "\n", StandardCharsets.UTF_8); }
    static String sha256(String s) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
}
