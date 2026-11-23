package me.nicolas.papea2ac.testing;

import static me.nicolas.papea2ac.testing.CombatTestSupport.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import me.nicolas.papea2ac.world.*;
import me.nicolas.papea2ac.world.EntityLookup.Status;

/**
 * Phase 11 artifacts under docs/phase11-artifacts: combat-corpus-results.json (every {@link CombatCorpus} fixture, expected vs observed, run twice),
 * entity-lifecycle-results.json (the lifecycle subset), target-lookup-results.json (one reproducible case per lookup status, all twelve) and
 * replay-results.json (the eight pinned traces replayed twice and through encoded schema-3 capsules). Exits 1 on any mismatch or non-determinism.
 */
public final class CombatFixtureMain {
    private CombatFixtureMain() {}
    static final Set<String> LIFECYCLE = Set.of("attack-after-spawn-bundled", "attack-after-teleport", "attack-after-relative-teleport", "move-after-teleport-decodes-from-codec-base",
        "position-sync-resets-codec-base", "attack-after-removal", "id-reuse", "id-replaced-by-spawn", "epoch-change", "session-replacement", "capacity-refusal", "unsupported-motion",
        "mounted-target", "history-eviction", "transport-gap", "self-is-not-a-target");

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]); Files.createDirectories(dir); boolean ok = true;
        var results = CombatCorpus.run(); var again = CombatCorpus.run();
        boolean deterministic = results.size() == again.size();
        for (int i = 0; deterministic && i < results.size(); i++) deterministic = results.get(i).observed().equals(again.get(i).observed()) && results.get(i).digest().equals(again.get(i).digest());
        long mismatches = results.stream().filter(r -> !r.matches()).count();
        if (mismatches > 0 || !deterministic) { ok = false; results.stream().filter(r -> !r.matches()).forEach(r -> System.err.println("MISMATCH " + r.name() + ": " + r.observed())); }

        Map<String, Object> corpus = new LinkedHashMap<>();
        corpus.put("profile", CombatCorpus.P.id()); corpus.put("path", "Phase 1 Observations -> core CombatRunner -> world EntityHistory + CombatEngine (production composition)");
        corpus.put("fixtures", (long) results.size()); corpus.put("mismatches", mismatches); corpus.put("deterministicRerun", deterministic);
        corpus.put("note", "descriptive results only (target resolution, exactness, target-specific incompleteness); no verdict. Every context also carries ATTACKER_HISTORY_UNAVAILABLE "
            + "and TIMING_UNAVAILABLE (no player timeline / timing ledger in these traces) and the three standing CAPTURE-ONLY limitations; see 'contexts'.");
        corpus.put("cases", results.stream().map(CombatCorpus.Result::json).toList());
        write(dir.resolve("combat-corpus-results.json"), corpus);

        Map<String, Object> lifecycle = new LinkedHashMap<>();
        lifecycle.put("cases", results.stream().filter(r -> LIFECYCLE.contains(r.name())).map(CombatCorpus.Result::json).toList());
        lifecycle.put("rules", List.of("spawn (AddEntity) sets the network target and the delta-codec base; pose defaults to STANDING and shared flags to 0 (pairing sends non-default values only)",
            "relative move: target = decode(base, delta) exactly as VecDeltaCodec (zero delta keeps the base bit-exact), then base = target",
            "position sync (EntityPositionSync): target and base", "teleport (TeleportEntity): target only (calculateAbsolute: (relative ? current : 0) + packet, pitch clamped); relative components INTERPOLATION_DEPENDENT; base untouched",
            "removal ends the incarnation (DESPAWNED); a spawn on a live id replaces it (new incarnation); the old key answers ID_REUSED",
            "world epoch change ends every incarnation; history never crosses epochs or sessions", "updates for unknown ids are dropped (as the client does) and counted",
            "256 retained summaries / 32 deep histories / 96 revisions / 3 s horizon; capacity refusal answers INCOMPLETE_COVERAGE"));
        write(dir.resolve("entity-lifecycle-results.json"), lifecycle);

        List<Map<String, Object>> matrix = lookupMatrix(); long matrixMismatches = matrix.stream().filter(m -> !Boolean.TRUE.equals(m.get("matches"))).count();
        Set<String> covered = new TreeSet<>(); for (var m : matrix) covered.add((String) m.get("status"));
        boolean allStatuses = covered.size() == Status.values().length; if (matrixMismatches > 0 || !allStatuses) ok = false;
        write(dir.resolve("target-lookup-results.json"), lm("statuses", (long) Status.values().length, "covered", new ArrayList<>(covered), "mismatches", matrixMismatches, "cases", matrix,
            "contract", "FOUND carries the revision in effect just before the queried sequence; every other status carries none; a range query is FOUND only when complete, otherwise a flagged partial prefix"));

        Map<String, CombatCorpus.Result> byName = new HashMap<>(); for (var r : results) byName.put(r.name(), r);
        List<Map<String, Object>> traces = new ArrayList<>(); long replayFailures = 0;
        for (var e : CombatCorpus.REPLAY_TRACES.entrySet()) {
            var r = byName.get(e.getValue().getFirst());
            String first = CombatCorpus.replay(r.key(), r.log()), second = CombatCorpus.replay(r.key(), r.log());
            var restored = CombatCorpus.throughCapsule(r.key(), r.log()); String capsule = CombatCorpus.replay(r.key(), restored);
            long segments = 1; for (int i = 1; i < r.log().size(); i++) if (r.log().get(i).sequence() != r.log().get(i - 1).sequence() + 1) segments++;
            boolean same = first.equals(second) && first.equals(r.digest()) && first.equals(capsule) && restored.equals(r.log()); if (!same) replayFailures++;
            Map<String, Object> m = new LinkedHashMap<>(); m.put("trace", e.getKey()); m.put("fixture", r.name()); m.put("observations", (long) r.log().size()); m.put("capsuleSegments", segments);
            m.put("liveDigest", r.digest()); m.put("replay1", first); m.put("replay2", second); m.put("capsuleReplay", capsule); m.put("identical", same);
            traces.add(m);
        }
        if (replayFailures > 0) ok = false;
        write(dir.resolve("replay-results.json"), lm("traces", traces, "replayFailures", replayFailures, "capsuleSchema", (long) me.nicolas.papea2ac.schema.TransportCapsule.CURRENT_SCHEMA,
            "digest", "EntityReplayMapper (EntityCapsule schema 1, canonical bytes, SHA-256) ':' CombatReplayMapper (CombatCapsule schema 1, canonical bytes, SHA-256)",
            "method", "each trace fed twice into fresh runners and once through encoded capsules (a loss closes a segment with a Barrier; the next opens with a Checkpoint)"));
        System.out.println("corpus fixtures=" + results.size() + " mismatches=" + mismatches + " deterministic=" + deterministic + " lookupStatuses=" + covered.size() + "/" + Status.values().length
            + " matrixMismatches=" + matrixMismatches + " replayTraces=" + traces.size() + " replayFailures=" + replayFailures);
        if (!ok) System.exit(1);
    }

    /** One reproducible case per {@link EntityLookup.Status}, with its specification-derived expectation. */
    static List<Map<String, Object>> lookupMatrix() {
        List<Map<String, Object>> out = new ArrayList<>();
        Rig r = new Rig(); long s = r.spawn(7, 1.5, 64, 2.5);
        out.add(row(Status.FOUND, "spawned entity, current lookup", r.now(7)));
        out.add(row(Status.UNKNOWN, "id never observed in this epoch", r.now(99)));
        out.add(row(Status.PRE_SPAWN, "lookup at (not after) the spawn observation", r.at(7, s)));
        out.add(row(Status.FUTURE_SEQUENCE, "sequence beyond the observed-through bound", r.at(7, r.seq + 5)));
        out.add(row(Status.SESSION_MISMATCH, "key of another session", r.h.lookupKey(new EntityKey(OTHER, 0, 7, 1), r.seq + 1)));
        Rig d = new Rig(); d.spawn(7, 0, 64, 0); d.remove(7); out.add(row(Status.DESPAWNED, "removed before the queried sequence", d.now(7)));
        Rig u = new Rig(); u.spawn(7, 0, 64, 0); var old = u.now(7).key(); u.remove(7); u.spawn(7, 3, 64, 3); out.add(row(Status.ID_REUSED, "old incarnation key after the id was reused", u.h.lookupKey(old, u.seq + 1)));
        Rig e = new Rig(); e.spawn(7, 0, 64, 0); var k = e.now(7).key(); for (int i = 0; i < 120; i++) e.move(7, 1, 0, 0);
        out.add(row(Status.EVICTED, "historical sequence older than the retained ring / horizon", e.h.lookupKey(k, 3)));
        Rig w = new Rig(); w.spawn(7, 0, 64, 0); var wk = w.now(7).key(); w.epoch(1); out.add(row(Status.WORLD_EPOCH_MISMATCH, "key of the previous world epoch", w.h.lookupKey(wk, w.seq + 1)));
        Rig g = new Rig(); g.spawn(7, 0, 64, 0); g.gap(); g.silent(); out.add(row(Status.INCOMPLETE_COVERAGE, "a lost observation after the spawn", g.now(7)));
        Rig m = new Rig(); m.spawn(7, 0, 64, 0); m.apply(new EntityObservation.UnsupportedMotion(7, "MINECART_LERP_STEPS")); out.add(row(Status.UNSUPPORTED, "moved by an unreconstructed mechanism", m.now(7)));
        Rig q = new Rig(); q.spawn(7, 0, 64, 0); for (int i = 0; i < 10; i++) q.move(7, 1, 0, 0);
        var range = q.h.revisions(q.now(7).key(), 0, q.seq + 1, 4);
        Map<String, Object> ql = new LinkedHashMap<>(); ql.put("status", Status.QUERY_LIMIT.name()); ql.put("case", "range query of 11 revisions with limit 4");
        ql.put("observed", range.status() + " partial=" + range.revisions().size() + " (" + range.detail() + ")"); ql.put("matches", range.status() == Status.QUERY_LIMIT && range.revisions().size() == 4);
        out.add(ql);
        return out;
    }
    static Map<String, Object> row(Status expected, String what, EntityLookup l) {
        Map<String, Object> m = new LinkedHashMap<>(); m.put("status", expected.name()); m.put("case", what); m.put("observed", l.describe());
        m.put("matches", l.status() == expected && (l.status() == Status.FOUND) == (l.revision() != null)); return m;
    }
    /** An insertion-ordered map (Map.of iteration order varies between JVM runs, which would rewrite the artifact on every run). */
    static Map<String, Object> lm(Object... kv) { Map<String, Object> m = new LinkedHashMap<>(); for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]); return m; }
    static void write(Path p, Map<String, Object> json) throws Exception { Files.createDirectories(p.getParent()); Files.writeString(p, MiniJson.write(json) + "\n", StandardCharsets.UTF_8); }
}
