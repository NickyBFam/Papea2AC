package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.packet.*;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.replay.*;

/**
 * Regression for a latent Phase 1 race surfaced (and widened enough to reproduce deterministically) by Phase 2's
 * shard-loop wiring: Session.close() invalidates health to CLOSED from the CALLER's thread via
 * ConnectionIngress.close(). If that lands between the shard's pre-process boundary read and its post-process
 * degrade check, the pre-fix code marked the recording INCOMPLETE - although a deliberate close is not data loss
 * (the closing branch finishes the prefix as complete). See PHASE_2_IMPLEMENTATION.md, "Phase 1 defects found".
 * The single-shot original (LiveRecordingTest.actualEngineConsumptionAndCloseProduceReplayableDiagnostic) hit
 * this most of the time on the reference machine after Phase 2; this loops it to amplify the window further.
 */
class DeliberateCloseRaceTest {
    @TempDir Path directory;
    static void await(java.util.function.BooleanSupplier c) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!c.getAsBoolean() && System.nanoTime() < until) Thread.sleep(1);
        assertTrue(c.getAsBoolean());
    }

    @Test void deliberateCloseRacingThePostProcessCheckNeverMarksTheRecordingIncomplete() throws Exception {
        var errors = new CopyOnWriteArrayList<Throwable>();
        var engine = new PacketEngine(1, 1L << 30, errors::add);
        var loop = new PacketTruthTest.Loop();
        engine.enableRecording(directory, "0".repeat(64));
        try {
            for (int cycle = 0; cycle < 40; cycle++) {
                var session = engine.connect(new UUID(40, cycle), loop, TransportCapability.observeOnly());
                session.ingress.capture(new Observation.Attempt(1, 1, PacketTruthTest.MOVE), System.nanoTime());
                await(() -> session.actor.snapshot().pending() == 1);
                session.close(); loop.drain();
                final int expected = cycle + 1;
                await(() -> engine.replayStatus().contains("written=" + expected));
                // The movement claim must have been recorded as a real observation (not mislabeled as a coverage gap)
                // and the session-close appended after it.
                await(() -> session.timeline.snapshot().lastCommittedSequence() == 2);
                assertFalse(session.timeline.snapshot().lastAppendHadCoverageIssue(), "a deliberate close is not a coverage gap");
            }
            List<Path> files; try (var s = Files.list(directory)) { files = s.sorted().toList(); }
            assertEquals(40, files.size());
            for (var file : files) {
                var recorded = CapsuleCodec.decode(Files.readAllBytes(file));
                assertTrue(recorded.complete(), () -> "capsule " + file.getFileName() + " must be complete: " + recorded.incompleteReason());
            }
            assertTrue(errors.isEmpty(), errors.toString());
        } finally { engine.close(); loop.drain(); }
    }
}
