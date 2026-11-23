package me.nicolas.papea2ac.testing;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
/**
 * Phase 13 boundary (ROADMAP: "Shared simulation consumption and impulse ordering; no duplicate motion formula"):
 * <ul>
 * <li>the knockback layer ({@code KnockbackAttribution}, {@code VelocityRunner}) contains no motion formula, no physics call, no timing solver
 *     or reconstruction call of its own: it reads Phase 8 results and the request they answered;</li>
 * <li>no enforcement, punishment, score, threshold, cheat-brand vocabulary, wall clock, platform type or hash-order collection;</li>
 * <li>it feeds neither evidence nor mitigation (the movement result is already the one causal family);</li>
 * <li>the live engine runs {@code TimingProfile.PAPER_26_2_FENCED} (user-approved default, 2026-10-03); {@code PAPER_26_2} still claims no fence;</li>
 * <li>the only new impulse kind the physics module learned is the client's own attacker slowdown, with the pinned constant.</li>
 * </ul>
 */
class VelocityArchitectureTest {
    static final Path ROOT = TimelineFixtureDeterminismTest.repoRoot();
    static String code(Path p) throws Exception { return Files.readString(p).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", ""); }

    @Test void theKnockbackLayerStaysWithinThePhase13Boundary() throws Exception {
        var forbidden = Pattern.compile("\\b(kick|Kick|ban|Ban|punish|Punish|cheater|Cheater|isCheater|violationLevel|vl|VL|score|Score|confidence|Confidence|probability|Probability|threshold|Threshold|setback|Setback|Vape|Wurst|LiquidBounce|Meteor|Bukkit|CraftBukkit|net\\.minecraft|io\\.netty|currentTimeMillis|nanoTime|Instant|LocalDateTime|Random|ThreadLocalRandom|HashMap|HashSet)\\b");
        var physics = Pattern.compile("\\b(simulate|PhysicsSimulator|InputReconstructor|reconstruct|TimingReconstruction\\.run|resolve\\(|Vec3d|friction|gravity|0\\.98|0\\.91|0\\.6\\b)");
        List<Path> files = List.of(ROOT.resolve("anticheat-simulation/src/main/java/me/nicolas/papea2ac/simulation/KnockbackAttribution.java"), ROOT.resolve("anticheat-core/src/main/java/me/nicolas/papea2ac/core/VelocityRunner.java"));
        for (Path f : files) {
            String c = code(f);
            var m = forbidden.matcher(c); assertFalse(m.find(), f + " contains enforcement/score/brand/clock/platform vocabulary: " + (m.find(0) ? c.substring(Math.max(0, m.start() - 30), Math.min(c.length(), m.end() + 30)) : ""));
            var p = physics.matcher(c); assertFalse(p.find(), f + " duplicates motion/timing computation: " + (p.find(0) ? c.substring(Math.max(0, p.start() - 30), Math.min(c.length(), p.end() + 30)) : ""));
            assertFalse(c.contains("papea2ac.evidence") || c.contains("EvidenceRunner") || c.contains("papea2ac.mitigation") || c.contains("MitigationRunner"), f + " feeds evidence or mitigation");
        }
        // the live engine runs the fenced profile (user-approved 2026-10-03); the frozen profile constant itself is untouched
        String engine = code(ROOT.resolve("anticheat-core/src/main/java/me/nicolas/papea2ac/core/PacketEngine.java"));
        assertTrue(engine.contains("timingProfile=me.nicolas.papea2ac.version.TimingProfile.PAPER_26_2_FENCED;"), "PacketEngine runs the user-approved fenced profile");
        assertFalse(code(ROOT.resolve("anticheat-version/src/main/java/me/nicolas/papea2ac/version/TimingProfile.java")).contains("PAPER_26_2 = new TimingProfile(\"paper-26.2-124-timing\", 1_000_000_000L, true, 3_000_000_000L, true, true)"), "the frozen profile never claims the fence");
        // one new impulse kind, with its pinned constant, applied only by the simulator / envelope
        String env = code(ROOT.resolve("anticheat-physics/src/main/java/me/nicolas/papea2ac/physics/PhysicsEnvironment.java"));
        assertTrue(env.contains("enum Operation { SET, ADD, UNRESOLVED, ATTACKER_SLOWDOWN }"));
        assertTrue(code(ROOT.resolve("anticheat-version/src/main/java/me/nicolas/papea2ac/version/MovementProfile.java")).contains("ATTACKER_SLOWDOWN_HORIZONTAL = 0.6;"));
    }
}
