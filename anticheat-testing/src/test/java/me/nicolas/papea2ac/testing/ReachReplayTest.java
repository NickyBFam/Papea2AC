package me.nicolas.papea2ac.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import me.nicolas.papea2ac.combat.reach.ReachReplayMapper;
import me.nicolas.papea2ac.combat.reach.ReachValidation;
import me.nicolas.papea2ac.schema.ReachCapsule;
import org.junit.jupiter.api.Test;

/** Phase 12 replay projection: the same inputs give byte-identical capsules; the capsule has no field beyond the engine's fixed vocabularies. */
class ReachReplayTest {
    static List<ReachValidation> results(long seed) {
        Random r = new Random(seed); List<ReachValidation> out = new ArrayList<>();
        for (int s = 0; s < 40 && out.size() < ReachCapsule.MAX_ENTRIES; s++) {
            var f = new ReachCorpus.Fight(r, s % 2 == 0 ? "walk" : "none", 1 + r.nextInt(3), r.nextInt(3), 1 + r.nextInt(2), 0);
            int T = 40 + r.nextInt(20); double[] eye = {0.5, 64 + ReachCorpus.EYE_HEIGHT, 0.5}; double[] tp = f.client[T];
            float[] rot = ReachCorpus.aim(eye, tp[0], tp[1] + 1, tp[2] + (s % 3 == 0 ? 2.5 : 0));
            out.add(ReachCorpus.validate(f, T, 0, eye, rot, s % 2 == 0, r));
        }
        return out;
    }
    @Test void replayingTheSameInputsGivesByteIdenticalCapsules() {
        ReachCapsule a = ReachReplayMapper.export(1, "corpus", results(0x12_7E91L)), b = ReachReplayMapper.export(1, "corpus", results(0x12_7E91L));
        assertEquals(a, b); assertArrayEquals(ReachReplayMapper.encode(a), ReachReplayMapper.encode(b)); assertEquals(ReachReplayMapper.digest(a), ReachReplayMapper.digest(b));
        assertTrue(a.results().stream().anyMatch(x -> x.status().equals("VALID")) && a.results().stream().anyMatch(x -> !x.status().equals("VALID")), "both kinds projected");
        var c = ReachReplayMapper.export(1, "corpus", results(0x12_7E92L));
        assertNotEquals(ReachReplayMapper.digest(a), ReachReplayMapper.digest(c), "different inputs, different digest");
        for (var rc : ReachCapsule.Result.class.getRecordComponents()) assertFalse(rc.getName().toLowerCase(Locale.ROOT).matches(".*(cheat|score|confidence|verdict|ban|punish).*"), rc.getName());
    }
}
