package me.nicolas.papea2ac.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import me.nicolas.papea2ac.core.PacketEngine;
import me.nicolas.papea2ac.packet.TransportCapability;
import me.nicolas.papea2ac.protocol.*;
import org.junit.jupiter.api.Test;

/**
 * Phase 12 end to end through the real {@link PacketEngine} shard: a session joins in survival, a husk spawns, the client moves and ticks,
 * a PING marker is answered (the application frontier), then attacks arrive and close their client tick. Expected: an attack from four blocks
 * is INVALID (REACH) from the attacker's own claims (D12-1; Phase 8 cannot certify movement without world data here), an attack within
 * reach is not INVALID, and the diagnostics / status line are descriptive (no verdict vocabulary, no UUID).
 */
class ReachRunnerIntegrationTest {
    static final java.util.regex.Pattern FORBIDDEN = java.util.regex.Pattern.compile("(?i)cheat|hack|killaura|\\bban\\b|punish|confidence|score|verdict");

    final long[] wire = {1000}, att = {0};
    void out(PacketEngine.Session s, PacketPayload p) { s.ingress.bridge(new Observation.Outbound(++wire[0], p)); s.ingress.bridge(new Observation.WriteOutcome(wire[0], true)); }
    void in(PacketEngine.Session s, PacketPayload p) { long a = ++att[0]; s.ingress.bridge(new Observation.Attempt(a, a, p)); }
    void tick(PacketEngine.Session s, double x, double y, double z, float yaw, float pitch) {
        in(s, new PacketPayload.Movement(new PacketPayload.Position(x, y, z), new PacketPayload.Rotation(yaw, pitch), true, false));
        in(s, new PacketPayload.Control(PacketPayload.ControlKind.TICK_END, 0));
    }

    @Test void attacksFromTheirClientTickAreDecidedEndToEnd() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> {}); var loop = new PacketTruthTest.Loop();
        try {
            UUID u = new UUID(0x12, 0x77);
            var s = engine.connect(u, loop, TransportCapability.observeOnly());
            s.ingress.bridge(new Observation.ControlChange("SELF_ENTITY", "42"));
            out(s, new SemanticPayload.JoinGame(42, false, "minecraft:overworld", 20, 10, 10, "SURVIVAL", true, false, -64, 384));
            out(s, CombatCorpus.spawnPayload(70, "minecraft:husk", 0.5, 64, 4.8));   // near face 4.5 from x=0.5,z=0.5: 4.0 from the eye
            out(s, CombatCorpus.spawnPayload(71, "minecraft:husk", 2.0, 64, 2.0));   // diagonal, its nearest corner ~1.7 from the eye when looked at
            for (int t = 0; t < 4; t++) tick(s, 0.5, 64, 0.5, 0f, 0f);
            out(s, new PacketPayload.Control(PacketPayload.ControlKind.PING, 0x0100_0001)); // marker after the spawns
            in(s, new PacketPayload.Control(PacketPayload.ControlKind.PONG, 0x0100_0001));
            for (int t = 0; t < 3; t++) tick(s, 0.5, 64, 0.5, 0f, 0f);
            // tick A: attack the far husk straight ahead, then this tick's movement and tick end
            in(s, new SemanticPayload.Attack(70)); tick(s, 0.5, 64, 0.5, 0f, 0f);
            // tick B: turn towards the near husk (the rotation arrives AFTER the attack, in the same tick: M3) and attack it
            in(s, new SemanticPayload.Attack(71)); tick(s, 0.5, 64, 0.5, -45f, 0f);
            for (int t = 1; t <= 20; t++) s.ingress.bridge(new Observation.Tick(t));
            TimingMarkerSchedulingTest.await(loop, 5000, () -> engine.reachStatusLine().contains("attacks=2") && !engine.reachStatusLine().contains("valid=0 uncertain=0 invalid=0"));
            TimingMarkerSchedulingTest.pump(loop, 200);
            String line = engine.reachStatusLine();
            assertTrue(line.contains("attacks=2"), line);
            assertTrue(line.contains("invalid=1"), line);
            assertTrue(line.contains("REACH=1"), line);
            assertTrue(line.contains("markerProven=2"), line);
            List<String> lines = engine.combatDiagnosticLines(u, false);
            String far = lines.stream().filter(l -> l.contains("target=minecraft:husk#70")).findFirst().orElse("");
            String near = lines.stream().filter(l -> l.contains("target=minecraft:husk#71")).findFirst().orElse("");
            assertTrue(far.contains("INVALID [REACH]"), String.join("\n", lines));
            assertTrue(far.contains("eye=ABSTRACT_EXCLUSION_ONLY"), far);
            assertFalse(near.contains("INVALID"), "a hit within reach is never INVALID: " + near);
            for (String l : lines) { assertFalse(FORBIDDEN.matcher(l).find(), l); assertFalse(l.contains(u.toString()), l); }
            assertFalse(FORBIDDEN.matcher(line).find(), line);
        } finally { engine.close(); loop.drain(); }
    }
}
