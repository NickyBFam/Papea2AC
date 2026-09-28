package me.nicolas.papea2ac.testing;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import me.nicolas.papea2ac.core.DecisionCapture;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.protocol.Observation;
import me.nicolas.papea2ac.replay.*;
import me.nicolas.papea2ac.schema.TransportCapsule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 10 decision capsules (the TC25 path): RollingCapture retention and promotion (never a gap, never a fabricated checkpoint, bounded by
 * the share, the prehistory horizon and the frame limit), DurableCapsuleWriter group commit (atomic files, manifest SHA-256, verifying reader,
 * corruption, saturation, failure) and DecisionCapture (fair rolling share, refusal, release).
 */
class DecisionCapsuleTest {
    static final long MS = 1_000_000L, S = 1_000_000_000L;
    static final String DIGEST = "d".repeat(64);
    static TransportCapsule.Checkpoint cp(long at, long startSequence) { return new TransportCapsule.Checkpoint(at, startSequence, 1, 1, "PLAY", true, "ENFORCE", 0, 0, 0, 0); }
    static TransportCapsule.Input in(long at, long seq) { return new TransportCapsule.Input(at, at, seq, 1, new TransportCapsule.Context(1, "LOCAL", "SERVER_OBSERVATION", 0, 0, seq, "PLAY", 775, "0".repeat(64)), new TransportCapsule.Tick(seq)); }
    static void assertClosure(TransportCapsule c) {
        assertTrue(c.complete()); assertInstanceOf(TransportCapsule.Checkpoint.class, c.frames().getFirst());
        long next = ((TransportCapsule.Checkpoint) c.frames().getFirst()).startSequence();
        for (var f : c.frames()) if (f instanceof TransportCapsule.Input i) assertEquals(next++, i.sequence(), "contiguous"); else assertFalse(f instanceof TransportCapsule.Barrier);
    }
    /** Feeds inputs every {@code step}, checkpointing whenever due, from sequence {@code seq}; returns the next sequence. */
    static long feed(RollingCapture r, long seq, long from, long to, long step) {
        for (long t = from; t < to; t += step) { if (r.checkpointDue(t)) r.checkpoint(cp(t, seq), t); r.append(in(t, seq++), t); }
        return seq;
    }

    // ---- RollingCapture -----------------------------------------------------------------------------------------------------------------
    @Test void nothingBeforeTheFirstCheckpointAndNeverAFabricatedOne() {
        RollingCapture r = new RollingCapture(256 << 10);
        assertEquals("NO_CHECKPOINT_YET", r.promote("p", DIGEST, "r", 1).reason());
        r.append(in(0, 1), 0); assertEquals(0, r.frames(), "a frame without a segment is not retained");
        r.checkpoint(cp(MS, 2), MS); r.append(in(MS, 2), MS); r.append(in(2 * MS, 3), 2 * MS);
        var p = r.promote("p", DIGEST, "r", 1); assertTrue(p.capsule().isPresent(), p.reason()); assertClosure(p.capsule().get()); assertEquals(3, p.frames());
    }
    @Test void thePrehistoryHorizonKeepsAboutThreeSecondsAndNoMore() {
        RollingCapture r = new RollingCapture(256 << 10); feed(r, 1, 0, 10 * S, 50 * MS); // 20 inputs/s * 10 s: well inside the share
        var p = r.promote("p", DIGEST, "r", 1); assertTrue(p.capsule().isPresent(), p.reason()); assertClosure(p.capsule().get());
        assertTrue(p.spanNanos() >= 3 * S - 50 * MS && p.spanNanos() <= 3 * S + RollingCapture.CHECKPOINT_INTERVAL_NANOS, "span " + p.spanNanos());
    }
    @Test void theShareBoundsRetentionAndABulkBurstStillLeavesACapsule() {
        long budget = 64 << 10; RollingCapture r = new RollingCapture(budget); long seq = 1;
        for (long t = 0; t < 2 * S; t += MS) { if (r.checkpointDue(t)) r.checkpoint(cp(t, seq), t); r.append(in(t, seq++), t); assertTrue(r.bytes() <= budget + 1024, "bounded: " + r.bytes()); }
        var p = r.promote("p", DIGEST, "r", 1); assertTrue(p.capsule().isPresent(), "size-triggered checkpoints keep an intact capsule: " + p.reason()); assertClosure(p.capsule().get());
        assertTrue(p.chargedBytes() <= budget + 1024); assertEquals(0, r.overflows());
    }
    @Test void aSegmentReachingAQuarterOfTheShareMakesACheckpointDueAtOnce() {
        long budget = 64 << 10; RollingCapture r = new RollingCapture(budget); r.checkpoint(cp(0, 1), 0); long seq = 1;
        while (!r.checkpointDue(MS)) r.append(in(MS, seq++), MS);
        assertTrue((seq - 1) * 576 + 256 >= budget / RollingCapture.SEGMENTS_PER_SHARE, "due only once the quarter is reached");
    }
    @Test void aGapBreaksOnlyItsSegmentAndIsNeverBridged() {
        RollingCapture r = new RollingCapture(256 << 10); long seq = feed(r, 1, 0, S, 50 * MS);
        r.append(in(S, seq + 1), S); // sequence gap
        assertEquals("NEWEST_SEGMENT_BROKEN", r.promote("p", DIGEST, "r", 1).reason()); assertTrue(r.checkpointDue(S + 1), "a fresh checkpoint is due at once"); assertEquals(1, r.gaps());
        r.checkpoint(cp(S + MS, seq + 2), S + MS); r.append(in(S + MS, seq + 2), S + MS);
        var p = r.promote("p", DIGEST, "r", 1); assertTrue(p.capsule().isPresent()); assertClosure(p.capsule().get());
        assertEquals(seq + 2, ((TransportCapsule.Checkpoint) p.capsule().get().frames().getFirst()).startSequence(), "starts after the gap");
    }
    @Test void anExportFailureBreaksTheSegmentUntilTheNextCheckpoint() {
        RollingCapture r = new RollingCapture(256 << 10); long seq = feed(r, 1, 0, S, 50 * MS);
        r.breakSegment(); r.append(in(S, seq), S);
        assertEquals("NEWEST_SEGMENT_BROKEN", r.promote("p", DIGEST, "r", 1).reason()); assertTrue(r.checkpointDue(S));
    }
    @Test void aNonContiguousCheckpointStartsANewRun() {
        RollingCapture r = new RollingCapture(256 << 10); r.checkpoint(cp(0, 1), 0); r.append(in(0, 1), 0); r.append(in(MS, 2), MS);
        r.checkpoint(cp(300 * MS, 10), 300 * MS); r.append(in(300 * MS, 10), 300 * MS); // sequences 3..9 never seen
        var c = r.promote("p", DIGEST, "r", 1).capsule().orElseThrow(); assertClosure(c);
        assertEquals(10, ((TransportCapsule.Checkpoint) c.frames().getFirst()).startSequence());
    }
    @Test void theFrameLimitIsNeverExceeded() {
        RollingCapture r = new RollingCapture(256 << 10); long seq = 1;
        for (long t = 0; t < 3 * S; t += 100_000) { if (r.checkpointDue(t)) r.checkpoint(cp(t, seq), t); r.append(new TransportCapsule.Clock(t), t); r.append(in(t, seq++), t); }
        assertTrue(r.frames() <= RollingCapture.MAX_FRAMES + 1);
        var p = r.promote("p", DIGEST, "r", 1); assertTrue(p.frames() <= RollingCapture.MAX_FRAMES, p.reason());
    }

    // ---- DurableCapsuleWriter -----------------------------------------------------------------------------------------------------------
    static TransportCapsule capsule(int inputs) { List<TransportCapsule.Frame> f = new ArrayList<>(); f.add(cp(0, 1)); for (int i = 1; i <= inputs; i++) f.add(in(i * MS, i)); return new TransportCapsule(TransportCapsule.CURRENT_SCHEMA, "p", DIGEST, "r", 1, false, f, true, ""); }
    static DurableCapsuleWriter.Receipt await(CompletableFuture<DurableCapsuleWriter.Receipt> f) throws Exception { return f.get(20, TimeUnit.SECONDS); }

    @Test void aDurableReceiptFollowsTheAtomicCapsuleAndManifestAndReadsBackVerified(@TempDir Path dir) throws Exception {
        try (DurableCapsuleWriter w = new DurableCapsuleWriter(dir, new ReplayQuota(), e -> { })) {
            CompletableFuture<DurableCapsuleWriter.Receipt> f = new CompletableFuture<>(); TransportCapsule c = capsule(40);
            assertTrue(w.submit("00000000000000a1", c, f::complete));
            var r = await(f); assertTrue(r.durable(), r.failure()); assertEquals(41, r.frames()); assertEquals(64, r.sha256().length()); assertTrue(r.latencyNanos() >= 0);
            try (var s = Files.list(dir)) { List<String> names = s.map(p -> p.getFileName().toString()).sorted().toList(); assertEquals(List.of("00000000000000a1.capsules", "00000000000000a1.manifest"), names, "no temporary file survives"); }
            assertEquals(c, DurableCapsuleWriter.read(dir, "00000000000000a1"), "the verifying reader returns the exact capsule");
        }
    }
    @Test void aBurstIsGroupCommittedAndEveryCapsuleIsIndividuallyAddressable(@TempDir Path dir) throws Exception {
        try (DurableCapsuleWriter w = new DurableCapsuleWriter(dir, new ReplayQuota(), e -> { })) {
            List<CompletableFuture<DurableCapsuleWriter.Receipt>> fs = new ArrayList<>();
            for (int i = 0; i < 40; i++) { var f = new CompletableFuture<DurableCapsuleWriter.Receipt>(); fs.add(f); assertTrue(w.submit(String.format("%016x", 0xb000 + i), capsule(10 + i), f::complete)); }
            Set<String> batches = new HashSet<>();
            for (int i = 0; i < 40; i++) { var r = await(fs.get(i)); assertTrue(r.durable()); batches.add(r.batch()); assertEquals(capsule(10 + i), DurableCapsuleWriter.read(dir, r.id())); }
            assertTrue(batches.size() < 40, "fewer commits than capsules: " + batches.size()); assertEquals(batches.size(), w.batches());
        }
    }
    @Test void corruptionIsDetectedByTheManifestDigest(@TempDir Path dir) throws Exception {
        try (DurableCapsuleWriter w = new DurableCapsuleWriter(dir, new ReplayQuota(), e -> { })) {
            var f = new CompletableFuture<DurableCapsuleWriter.Receipt>(); w.submit("00000000000000c1", capsule(30), f::complete); assertTrue(await(f).durable());
        }
        Path data = dir.resolve("00000000000000c1.capsules"); byte[] b = Files.readAllBytes(data); b[b.length / 2] ^= 0x40; Files.write(data, b);
        var e = assertThrows(java.io.IOException.class, () -> DurableCapsuleWriter.read(dir, "00000000000000c1")); assertTrue(e.getMessage().contains("SHA-256"), e.getMessage());
        assertThrows(NoSuchFileException.class, () -> DurableCapsuleWriter.read(dir, "00000000000000c2"));
    }
    @Test void incompleteCapsulesInvalidIdsAndASaturatedIoPoolAreRefusedWithoutBlocking(@TempDir Path dir) throws Exception {
        ReplayQuota q = new ReplayQuota();
        try (DurableCapsuleWriter w = new DurableCapsuleWriter(dir, q, e -> { })) {
            var partial = new TransportCapsule(TransportCapsule.CURRENT_SCHEMA, "p", DIGEST, "r", 1, false, List.of(cp(0, 1)), false, "PARTIAL");
            assertFalse(w.submit("00000000000000d1", partial, r -> fail("no receipt for a refused job")), "partial captures never justify effects");
            assertThrows(IllegalArgumentException.class, () -> w.submit("../escape", capsule(1), r -> { }));
            var hog = q.reserve(ReplayQuota.Pool.IO, 1, 16L << 20, 1).orElseThrow();
            assertFalse(w.submit("00000000000000d2", capsule(5), r -> fail("refused")), "IO pool saturated");
            hog.close();
            var f = new CompletableFuture<DurableCapsuleWriter.Receipt>(); assertTrue(w.submit("00000000000000d3", capsule(5), f::complete)); assertTrue(await(f).durable());
            assertEquals(2, w.rejected()); assertEquals(0, q.used(ReplayQuota.Pool.IO), "every lease released with its receipt");
        }
    }
    @Test void aStorageFailureYieldsANonDurableReceiptAndNoManifest(@TempDir Path tmp) throws Exception {
        Path notADirectory = tmp.resolve("blocked"); Files.writeString(notADirectory, "x");
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (DurableCapsuleWriter w = new DurableCapsuleWriter(notADirectory, new ReplayQuota(), errors::add)) {
            var f = new CompletableFuture<DurableCapsuleWriter.Receipt>(); assertTrue(w.submit("00000000000000e1", capsule(5), f::complete));
            var r = await(f); assertFalse(r.durable()); assertFalse(r.failure().isEmpty()); assertEquals(1, w.failed()); assertFalse(errors.isEmpty());
        }
        assertEquals("x", Files.readString(notADirectory));
    }

    // ---- DecisionCapture ----------------------------------------------------------------------------------------------------------------
    static Observation tick(SessionKey k, long seq, long now) { return new Observation(k, seq, now - 1000, 1, new Observation.Tick(seq)); }
    @Test void theSessionCaptureUsesItsFairShareIsRefusedBeyondItAndReleasesItOnClose(@TempDir Path dir) throws Exception {
        ReplayQuota q = new ReplayQuota(); SessionKey a = new SessionKey(new UUID(1, 1), 1), b = new SessionKey(new UUID(2, 2), 2);
        long origin = System.nanoTime() - 600 * S, now = System.nanoTime();
        DecisionCapture ca = new DecisionCapture(q, a.epoch(), origin), cb = new DecisionCapture(q, b.epoch(), origin);
        for (long s = 1; s <= 50; s++) { ca.observe(tick(a, s, now + s * MS), 1, true, "ENFORCE", () -> DecisionCapture.Cursor.ZERO, 1, now + s * MS); cb.observe(tick(b, s, now + s * MS), 1, true, "ENFORCE", () -> DecisionCapture.Cursor.ZERO, 1, now + s * MS); }
        assertEquals(256L << 10, q.used(ReplayQuota.Pool.ROLLING), "one fair share (N=1)"); assertTrue(cb.refused(), "a second session beyond the declared active count is refused, not over-committed");
        try (DurableCapsuleWriter w = new DurableCapsuleWriter(dir, q, e -> { })) {
            var f = new CompletableFuture<DurableCapsuleWriter.Receipt>();
            assertTrue(ca.request(w, "00000000000000f1", "p", DIGEST, a.epoch(), f::complete)); var r = await(f); assertTrue(r.durable());
            TransportCapsule c = DurableCapsuleWriter.read(dir, "00000000000000f1"); assertClosure(c); assertEquals(51, c.frames().size(), "checkpoint + 50 contiguous inputs");
            assertFalse(cb.request(w, "00000000000000f2", "p", DIGEST, b.epoch(), x -> fail("refused"))); assertEquals("ROLLING_SHARE_REFUSED", cb.lastPromotion().reason());
        }
        ca.close(); assertEquals(0, q.used(ReplayQuota.Pool.ROLLING), "released with the session");
    }
}
