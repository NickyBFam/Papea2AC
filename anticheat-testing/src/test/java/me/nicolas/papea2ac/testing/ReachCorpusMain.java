package me.nicolas.papea2ac.testing;

import java.nio.file.*;
import java.util.*;

/**
 * Writes {@code docs/phase12-artifacts/reach-corpus-results.json}: the Phase 12 attack corpus ({@link ReachCorpus}) at its recorded scale,
 * run twice (byte-identical or it fails), with the legitimate gate (0 INVALID) enforced. Usage: {@code ReachCorpusMain <out-dir>}.
 */
public final class ReachCorpusMain {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]); Files.createDirectories(dir);
        long seed = 0x12_C0A2L; int perClass = 400;
        // the single-frontier bound (contract v1) for comparison, then the nested bound (every answered marker), which is the engine's
        ReachCorpus.NESTED = false; var single = ReachCorpus.run(seed, perClass);
        ReachCorpus.NESTED = true;
        var a = ReachCorpus.run(seed, perClass); var b = ReachCorpus.run(seed, perClass);
        if (!a.equals(b)) throw new IllegalStateException("non-deterministic corpus");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seed", Long.toHexString(seed)); out.put("scenariosPerClass", perClass);
        out.put("model", "client-tick simulation: server update every tick, random downlink/jitter/uplink, exact 26.2 interpolation (3 steps, Mth.lerp), combat-time PING after every update (D12-2), vanilla pick via ScenePick (client-exact)");
        int legitInvalid = 0;
        for (var r : a.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("legitimate", r.legitimate()); m.put("attacks", r.attacks()); m.put("valid", r.valid()); m.put("uncertain", r.uncertain()); m.put("invalid", r.invalid());
            m.put("invalidShare", r.attacks() == 0 ? 0 : Math.round(1e4 * r.invalid() / (double) r.attacks()) / 1e4);
            m.put("missClasses", r.missClasses()); m.put("reasons", r.reasons());
            var s1 = single.get(r.name()); Map<String, Object> sm = new LinkedHashMap<>(); sm.put("valid", s1.valid()); sm.put("invalid", s1.invalid()); m.put("singleFrontier", sm);
            out.put(r.name(), m);
            if (r.legitimate()) legitInvalid += r.invalid() + s1.invalid();
        }
        out.put("legitimateInvalid", legitInvalid);
        Files.writeString(dir.resolve("reach-corpus-results.json"), Phase12Json.json(out, 0) + "\n");
        System.out.println("reach corpus: legitimateInvalid=" + legitInvalid + " classes=" + a.size());
        if (legitInvalid != 0) throw new IllegalStateException("legitimate INVALID: " + legitInvalid);
    }
}
