package me.nicolas.papea2ac.testing;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import me.nicolas.papea2ac.core.DecisionCapture;
import me.nicolas.papea2ac.core.TransportReplayDriver;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.replay.*;
import me.nicolas.papea2ac.world.WorldObservation;

/**
 * TESTING TC25 (Phase 9 before 10, run as the Phase 10 gate, Decision 3 step 4): "Sparse movement, dense combat and chunk-churn capsules:
 * measured size/closure and >=99% durable within 400 ms under declared load; otherwise mitigation stays off".
 *
 * <p>The code under test is the production code: {@link DecisionCapture} (the engine's per-session rolling share, 250 ms checkpoints and
 * promotion) and {@link DurableCapsuleWriter} (deflate, force/fsync, atomic capsule and manifest moves, then the receipt). Only the traffic is
 * synthetic. It is protocol-shaped (real {@link PacketPayload}/{@link SemanticPayload} records, an Attempt/Outcome pair for every inbound packet,
 * wire-packed chunk sections), generated in real time at 20 TPS by one "shard" thread per 128 sessions, as in the engine.
 *
 * <p>Declared load, per run: N sessions, all active, for the warmup (prehistory fill) plus the measured window. Each session requests a
 * decision capsule at the per-session maximum the mitigation actor allows (one per 2 s cooldown, random phase). One correlated burst in which
 * EVERY session requests in the same tick is added at mid-window. A request succeeds only if a complete capsule was promoted, accepted and
 * durably stored (receipt durable, manifest promoted) within 400 ms of the decision point. Promotion, queueing and I/O all count, and refusals
 * and failures count as misses.
 *
 * <p>Gate (REVIEW-2 NEW-M1 acceptance "the gate must hold at the maximum configured certified-session capacity for the tier under test"): all
 * three scenarios >= 99 % at the configured capacity {@code gateSessions} (EngineConfiguration default 32). Larger N are measured and reported
 * as the session-count dependence of the rolling share, never as a gate.
 */
final class Tc25Main {
    enum Scenario { SPARSE_MOVEMENT, DENSE_COMBAT, CHUNK_CHURN }
    /**
     * STRESS (the gate load): every session at the per-session cooldown bound (1 request / 2 s, random phase) plus one all-session burst at mid-window.
     * That is far beyond what G2 lets reach capture. G2_MAX is the highest capture rate the G2 proposal guard admits without suspending: its burst
     * gate allows at most 2 distinct sessions within any 1 s, and each of those is bounded by its 2 s cooldown, so 2 requests/s from rotating sessions.
     */
    enum Load { STRESS, G2_MAX }
    static final long TICK = 50_000_000L, DEADLINE = 400_000_000L, COOLDOWN = 2_000_000_000L;
    static final String RULESET = "5".repeat(64), DIGEST = "c".repeat(64);
    static final int CLIENT_PROTOCOL = 775;

    // ---- protocol-shaped traffic --------------------------------------------------------------------------------------------------------
    /** Wire-packed chunk templates (realistic mixed underground palette, surface, sparse trees, air above). */
    static final List<List<SemanticPayload.ChunkSection>> CHUNKS = new ArrayList<>();
    static {
        String[] under = { "minecraft:stone", "minecraft:deepslate", "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:gravel", "minecraft:coal_ore", "minecraft:iron_ore", "minecraft:dirt", "minecraft:tuff" };
        for (int t = 0; t < 16; t++) {
            SplittableRandom r = new SplittableRandom(1000 + t); var sections = new ArrayList<SemanticPayload.ChunkSection>();
            for (int i = 0; i < WorldTestSupport.OVERWORLD_SECTIONS; i++) {
                WorldObservation.SectionContent c;
                if (i < 8) { long seed = r.nextLong(); int base = i < 4 ? 1 : 0; c = WorldTestSupport.section(idx -> { SplittableRandom q = new SplittableRandom(seed ^ idx * 0x9E3779B97F4A7C15L); int v = q.nextInt(100);
                    return v < 6 ? WorldTestSupport.AIR : v < 60 ? BlockStateKey.of(under[base]) : BlockStateKey.of(under[2 + q.nextInt(under.length - 2)]); }); }
                else if (i == 8) { long seed = r.nextLong(); c = WorldTestSupport.section(idx -> { int y = idx >> 8; int h = 4 + (int) ((seed ^ (idx & 0xFF)) & 3);
                    return y < h ? WorldTestSupport.STONE : y < h + 2 ? WorldTestSupport.DIRT : y == h + 2 ? BlockStateKey.parse("minecraft:grass_block[snowy=false]") : WorldTestSupport.AIR; }); }
                else if (i == 9) { long seed = r.nextLong(); c = WorldTestSupport.section(idx -> { int x = idx & 15, z = (idx >> 4) & 15, y = idx >> 8; boolean trunk = ((x * 31 + z * 17 + seed) & 63) == 0;
                    return trunk && y < 5 ? BlockStateKey.parse("minecraft:oak_log[axis=y]") : trunk && y < 8 ? BlockStateKey.parse("minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]") : WorldTestSupport.AIR; }); }
                else c = WorldTestSupport.uniformSection(WorldTestSupport.AIR);
                sections.add(ObserverWorldIntegrationTest.wire(c));
            }
            CHUNKS.add(List.copyOf(sections));
        }
    }

    static final class Session {
        final int index; final SessionKey key; final Scenario scenario; final SplittableRandom r; final DecisionCapture capture;
        long seq = 1, attempt = 1, ingress = 1, wire = 1, processing = 1, revision = 1, nextDecision; int decisions;
        double x, y = 64, z; float yaw; int chunkX, chunkZ; final ArrayDeque<int[]> pendingChunks = new ArrayDeque<>();
        Session(int index, Scenario scenario, ReplayQuota quota) {
            this.index = index; this.scenario = scenario; key = new SessionKey(new UUID(0x7C25L, index), index + 1L); r = new SplittableRandom(25_000 + index);
            capture = new DecisionCapture(quota, key.epoch(), System.nanoTime() - 600_000_000_000L);
            x = r.nextInt(-2000, 2000); z = r.nextInt(-2000, 2000); chunkX = (int) Math.floor(x / 16); chunkZ = (int) Math.floor(z / 16);
        }
        Observation.Context ctx(Observation.Direction d, Observation.SourceKind k, long id, long tick) {
            return new Observation.Context(1, 1, d, k, id, 0, tick, Observation.ProtocolState.PLAY, CLIENT_PROTOCOL, RULESET);
        }
        void inbound(PacketPayload p, long tick, long now, List<Observation> out) {
            long a = attempt++;
            out.add(new Observation(key, seq++, now - 200_000, 1, new Observation.Attempt(a, ingress++, p), ctx(Observation.Direction.INBOUND, Observation.SourceKind.NETWORK, a, -1)));
            out.add(new Observation(key, seq++, now - 100_000, 1, new Observation.Outcome(a, processing++, revision++, Observation.Result.APPLIED, p, DIGEST), ctx(Observation.Direction.LOCAL, Observation.SourceKind.SERVER_OUTCOME, 0, -1)));
        }
        void outbound(PacketPayload p, long now, List<Observation> out) {
            long w = wire++; out.add(new Observation(key, seq++, now - 50_000, 1, new Observation.Outbound(w, p), ctx(Observation.Direction.OUTBOUND, Observation.SourceKind.NETWORK, w, -1)));
        }
        static PacketPayload.Position pos(double x, double y, double z) { return new PacketPayload.Position(x, y, z); }
        /** One server tick of this session's traffic. */
        void tick(long serverTick, long now, List<Observation> out) {
            out.add(new Observation(key, seq++, now - 300_000, 1, new Observation.Tick(serverTick), ctx(Observation.Direction.LOCAL, Observation.SourceKind.SERVER_OBSERVATION, 0, serverTick)));
            double speed = switch (scenario) { case SPARSE_MOVEMENT -> 0.2158; case DENSE_COMBAT -> 0.28; case CHUNK_CHURN -> 1.5; };
            yaw += (float) r.nextDouble(-2, 2); x += speed * Math.cos(Math.toRadians(yaw)) * (scenario == Scenario.CHUNK_CHURN ? 1 : 0.5); z += speed * (scenario == Scenario.CHUNK_CHURN ? 1 : Math.sin(Math.toRadians(yaw)));
            boolean still = scenario == Scenario.SPARSE_MOVEMENT && serverTick % 4 == 0;
            inbound(new PacketPayload.Movement(still ? null : pos(x, y, z), new PacketPayload.Rotation(yaw, 10f), true, false), serverTick, now, out);
            inbound(new PacketPayload.Control(PacketPayload.ControlKind.TICK_END, 0), serverTick, now, out);
            if (serverTick % 20 == 0) { outbound(new PacketPayload.Control(PacketPayload.ControlKind.KEEPALIVE, serverTick), now, out); inbound(new PacketPayload.Control(PacketPayload.ControlKind.KEEPALIVE, serverTick), serverTick, now, out); }
            switch (scenario) {
                case SPARSE_MOVEMENT -> { if (serverTick % 2 == 0) for (int e = 0; e < 2; e++) outbound(new SemanticPayload.EntityDelta(1000 + e, (short) r.nextInt(-64, 64), (short) 0, (short) r.nextInt(-64, 64), new PacketPayload.Rotation(0f, 0f), true, false, true), now, out); }
                case DENSE_COMBAT -> {
                    if (r.nextInt(10) < 3) inbound(new PacketPayload.Control(PacketPayload.ControlKind.INPUT, r.nextInt(64)), serverTick, now, out);
                    if (r.nextInt(10) < 6) { inbound(new PacketPayload.Control(PacketPayload.ControlKind.SWING, 0), serverTick, now, out); inbound(new SemanticPayload.Attack(2000 + r.nextInt(4)), serverTick, now, out); }
                    if (r.nextInt(10) < 3) { outbound(new PacketPayload.Velocity(index + 1, pos(r.nextDouble(-0.4, 0.4), 0.36, r.nextDouble(-0.4, 0.4))), now, out); outbound(new PacketPayload.Health(12f + r.nextInt(8), 20, 5f), now, out); }
                    for (int e = 0; e < 24; e++) {
                        if (r.nextInt(20) == 0) outbound(new SemanticPayload.EntityPosition(2000 + e, pos(x + r.nextDouble(-8, 8), y, z + r.nextDouble(-8, 8)), pos(0, 0, 0), new PacketPayload.Rotation(r.nextFloat() * 360, 0f), Set.of(), true), now, out);
                        else outbound(new SemanticPayload.EntityDelta(2000 + e, (short) r.nextInt(-400, 400), (short) r.nextInt(-40, 40), (short) r.nextInt(-400, 400), new PacketPayload.Rotation(r.nextFloat() * 360, 0f), true, true, r.nextBoolean()), now, out);
                    }
                    if (serverTick % 5 == 0) { int id = 5000 + (int) (serverTick % 1000);
                        outbound(new SemanticPayload.EntitySpawn(id, new UUID(id, serverTick), "minecraft:arrow", pos(x, y + 1.5, z), pos(0.5, 0.1, 0.5), new PacketPayload.Rotation(0f, 0f), 0f, 0), now, out);
                        outbound(new SemanticPayload.RemoveEntities(List.of(id - 5 < 5000 ? id : id - 5)), now, out); }
                }
                case CHUNK_CHURN -> {
                    int cx = (int) Math.floor(x / 16), cz = (int) Math.floor(z / 16);
                    if (cx != chunkX || cz != chunkZ) { // crossing: unload the trailing row(s), queue the leading row(s) (view distance 10)
                        for (int d = -10; d <= 10; d++) { if (cx != chunkX) { outbound(new SemanticPayload.ChunkUnload(chunkX - 10 * Integer.signum(cx - chunkX), chunkZ + d), now, out); pendingChunks.add(new int[] { cx + 10 * Integer.signum(cx - chunkX), cz + d }); }
                            if (cz != chunkZ) { outbound(new SemanticPayload.ChunkUnload(chunkX + d, chunkZ - 10 * Integer.signum(cz - chunkZ)), now, out); pendingChunks.add(new int[] { cx + d, cz + 10 * Integer.signum(cz - chunkZ) }); } }
                        chunkX = cx; chunkZ = cz;
                    }
                    for (int k = 0; k < 4 && !pendingChunks.isEmpty(); k++) { int[] c = pendingChunks.poll(); var sections = CHUNKS.get(Math.floorMod(c[0] * 31 + c[1], CHUNKS.size())); // Paper send rate ~75 chunks/s
                        outbound(new SemanticPayload.ChunkBaseline(c[0], c[1], 14_000, r.nextInt(3), 4, true, true, sections, ""), now, out); }
                }
            }
        }
    }

    // ---- one measured run ---------------------------------------------------------------------------------------------------------------
    record Sample(boolean promoted, String reason, boolean accepted, boolean durable, long latencyNanos, int frames, long charged, long span, long encoded, long stored, boolean burst) {}
    record Run(Scenario scenario, Load load, int sessions, int shards, long fairShare, List<Sample> samples, long observations, double seconds, long tickOverruns, long maxTickLagNanos,
               long refusedShares, long gaps, long dropped, long overflows, String closureCheck) {}

    static Run run(Scenario scenario, Load load, int sessions, int warmupSeconds, int measureSeconds, Path dir) throws Exception {
        deleteTree(dir); Files.createDirectories(dir);
        ReplayQuota quota = new ReplayQuota(); AtomicReference<Throwable> ioFailure = new AtomicReference<>();
        DurableCapsuleWriter writer = new DurableCapsuleWriter(dir, quota, t -> ioFailure.compareAndSet(null, t));
        List<Session> all = new ArrayList<>(); for (int i = 0; i < sessions; i++) all.add(new Session(i, scenario, quota));
        int shards = Math.max(1, (sessions + 127) / 128);
        long start = System.nanoTime() + 100_000_000L, measureFrom = start + warmupSeconds * 1_000_000_000L, measureTo = measureFrom + measureSeconds * 1_000_000_000L, burstAt = measureFrom + (measureTo - measureFrom) / 2;
        for (Session s : all) s.nextDecision = measureFrom + s.r.nextLong(COOLDOWN);
        // joined by capsule id at the end: the receipt may arrive before the requesting thread records the promotion
        ConcurrentLinkedQueue<Sample> samples = new ConcurrentLinkedQueue<>(); AtomicInteger outstanding = new AtomicInteger();
        ConcurrentHashMap<String, DurableCapsuleWriter.Receipt> receipts = new ConcurrentHashMap<>(); ConcurrentHashMap<String, long[]> accepted = new ConcurrentHashMap<>();
        LongAdder observations = new LongAdder(), overruns = new LongAdder(); AtomicLong maxLag = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(shards);
        List<Future<?>> futures = new ArrayList<>();
        for (int sh = 0; sh < shards; sh++) {
            List<Session> mine = new ArrayList<>(); for (int i = sh; i < sessions; i += shards) mine.add(all.get(i));
            futures.add(pool.submit(() -> {
                List<Observation> buf = new ArrayList<>(256); boolean burstDone = false;
                for (long tick = 0;; tick++) {
                    long due = start + tick * TICK; long wait = due - System.nanoTime();
                    if (wait > 0) LockSupport.parkNanos(wait); else if (-wait > TICK) { overruns.increment(); maxLag.accumulateAndGet(-wait, Math::max); }
                    long now = System.nanoTime(); if (now >= measureTo) break;
                    boolean burst = !burstDone && now >= burstAt; if (burst) burstDone = true;
                    for (Session s : mine) {
                        buf.clear(); s.tick(tick, now, buf);
                        for (Observation o : buf) s.capture.observe(o, 1, true, "ENFORCE", () -> new DecisionCapture.Cursor(s.ingress - 1, s.ingress - 1, s.processing - 1, s.revision - 1), sessions, System.nanoTime());
                        observations.add(buf.size());
                        long t = System.nanoTime();
                        boolean requestDue = load == Load.STRESS ? burst || t >= s.nextDecision : tick % 10 == 0 && Math.floorMod(tick / 10, sessions) == s.index; // G2_MAX: one rotating session every 500 ms
                        if (t >= measureFrom && requestDue) {
                            if (load == Load.STRESS && !burst) s.nextDecision = t + COOLDOWN + s.r.nextLong(200_000_000L);
                            String id = String.format("%016x%016x", s.key.epoch(), ++s.decisions); long t0 = System.nanoTime(); boolean isBurst = burst && load == Load.STRESS;
                            outstanding.incrementAndGet();
                            boolean ok = s.capture.request(writer, id, TransportReplayDriver.PROFILE, DIGEST, s.key.epoch(), rc -> {
                                receipts.put(rc.id(), rc); outstanding.decrementAndGet(); });
                            var promo = s.capture.lastPromotion();
                            if (ok) accepted.put(id, new long[] { t0, promo.chargedBytes(), promo.spanNanos(), isBurst ? 1 : 0 });
                            else { outstanding.decrementAndGet(); samples.add(new Sample(promo.capsule().isPresent(), promo.capsule().isPresent() ? "WRITER_REFUSED" : promo.reason(), false, false, Long.MAX_VALUE, promo.frames(), promo.chargedBytes(), promo.spanNanos(), 0, 0, isBurst)); }
                        }
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();
        long waitUntil = System.nanoTime() + 10_000_000_000L; while (outstanding.get() > 0 && System.nanoTime() < waitUntil) Thread.sleep(10);
        accepted.forEach((id, a) -> { var rc = receipts.get(id); boolean burst = a[3] == 1;
            if (rc == null) samples.add(new Sample(true, "NO_RECEIPT", true, false, Long.MAX_VALUE, 0, a[1], a[2], 0, 0, burst));
            else samples.add(new Sample(true, rc.durable() ? "" : "WRITE_FAILED:" + rc.failure(), true, rc.durable(), rc.durableNanos() - a[0], rc.frames(), a[1], a[2], rc.encodedBytes(), rc.storedBytes(), burst)); });
        long refused = all.stream().filter(s -> s.capture.refused()).count(), gaps = 0, dropped = 0, overflows = 0;
        for (Session s : all) if (s.capture.rolling() != null) { gaps += s.capture.rolling().gaps(); dropped += s.capture.rolling().dropped(); overflows += s.capture.rolling().overflows(); }
        // closure is measured on EVERY durable capsule, read back from disk after the run
        long verified = 0, closureFailures = 0; String firstFailure = "";
        for (var rc : receipts.values()) if (rc.durable()) { String v = verifyClosure(dir, rc.batch(), rc.id()); if (v.equals("VERIFIED")) verified++; else { closureFailures++; if (firstFailure.isEmpty()) firstFailure = rc.id() + ":" + v; } }
        String closure = "readBack=" + (verified + closureFailures) + " verified=" + verified + " failures=" + closureFailures + (firstFailure.isEmpty() ? "" : " first=" + firstFailure);
        writer.close(); for (Session s : all) s.capture.close();
        if (ioFailure.get() != null) closure += " ioFailure=" + ioFailure.get();
        long fair = Math.min(256L << 10, (48L << 20) / sessions);
        Run run = new Run(scenario, load, sessions, shards, fair, List.copyOf(samples), observations.sum(), (warmupSeconds + measureSeconds), overruns.sum(), maxLag.get(), refused, gaps, dropped, overflows, closure);
        deleteTree(dir);
        return run;
    }

    /** Reads a stored capsule back through the verifying reader (promoted manifest, SHA-256 of the stored bytes, bounded inflate, decode) and checks closure: leading checkpoint, contiguous inputs, no barrier, complete. */
    static String verifyClosure(Path dir, String batch, String id) {
        try {
            var c = DurableCapsuleWriter.read(dir, batch, id);
            if (!(c.frames().getFirst() instanceof me.nicolas.papea2ac.schema.TransportCapsule.Checkpoint cp)) return "NO_LEADING_CHECKPOINT";
            long next = cp.startSequence(); int inputs = 0;
            for (var f : c.frames()) if (f instanceof me.nicolas.papea2ac.schema.TransportCapsule.Input in) { if (in.sequence() != next) return "GAP_AT_" + next; next++; inputs++; }
            else if (f instanceof me.nicolas.papea2ac.schema.TransportCapsule.Barrier) return "BARRIER";
            return c.complete() ? "VERIFIED" : "INCOMPLETE";
        } catch (Exception e) { return "CLOSURE_FAILURE:" + e; }
    }
    static void deleteTree(Path dir) throws java.io.IOException {
        if (!Files.exists(dir)) return;
        try (var w = Files.walk(dir)) { for (Path p : w.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
    }

    // ---- statistics and output ------------------------------------------------------------------------------------------------------
    static long pct(long[] sorted, double p) { if (sorted.length == 0) return 0; return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p * sorted.length) - 1 < 0 ? 0 : (int) Math.ceil(p * sorted.length) - 1)]; }
    static String json(Run r, boolean gateRun) {
        List<Sample> s = r.samples(); int n = s.size();
        long ok = s.stream().filter(x -> x.durable() && x.latencyNanos() <= DEADLINE).count();
        long durable = s.stream().filter(Sample::durable).count();
        long[] lat = s.stream().filter(Sample::durable).mapToLong(Sample::latencyNanos).sorted().toArray();
        long[] frames = s.stream().filter(Sample::accepted).mapToLong(Sample::frames).sorted().toArray();
        long[] charged = s.stream().filter(Sample::accepted).mapToLong(Sample::charged).sorted().toArray();
        long[] span = s.stream().filter(Sample::accepted).mapToLong(Sample::span).sorted().toArray();
        long[] enc = s.stream().filter(Sample::durable).mapToLong(Sample::encoded).sorted().toArray();
        long[] sto = s.stream().filter(Sample::durable).mapToLong(Sample::stored).sorted().toArray();
        Map<String, Long> reasons = new TreeMap<>(); for (Sample x : s) if (!x.durable()) reasons.merge(x.reason().isEmpty() ? "NOT_DURABLE" : x.reason(), 1L, Long::sum);
        long burstN = s.stream().filter(Sample::burst).count(), burstOk = s.stream().filter(x -> x.burst() && x.durable() && x.latencyNanos() <= DEADLINE).count();
        double share = n == 0 ? 0 : (double) ok / n; boolean pass = n > 0 && share >= 0.99;
        return "{\"scenario\":\"" + r.scenario() + "\",\"load\":\"" + r.load() + "\",\"sessions\":" + r.sessions() + ",\"gate\":" + gateRun + ",\"shards\":" + r.shards() + ",\"rollingShareBytesPerSession\":" + r.fairShare()
            + ",\"requests\":" + n + ",\"durable\":" + durable + ",\"durableWithin400ms\":" + ok + ",\"share\":" + String.format(Locale.ROOT, "%.5f", share) + ",\"pass\":" + pass
            + ",\"burstRequests\":" + burstN + ",\"burstWithin400ms\":" + burstOk + ",\"misses\":" + mapJson(reasons)
            + ",\"latencyMs\":{\"p50\":" + ms(pct(lat, .5)) + ",\"p95\":" + ms(pct(lat, .95)) + ",\"p99\":" + ms(pct(lat, .99)) + ",\"max\":" + ms(lat.length == 0 ? 0 : lat[lat.length - 1]) + "}"
            + ",\"capsuleFrames\":{\"p50\":" + pct(frames, .5) + ",\"min\":" + (frames.length == 0 ? 0 : frames[0]) + ",\"max\":" + (frames.length == 0 ? 0 : frames[frames.length - 1]) + "}"
            + ",\"capsuleChargedBytes\":{\"p50\":" + pct(charged, .5) + ",\"max\":" + (charged.length == 0 ? 0 : charged[charged.length - 1]) + "}"
            + ",\"encodedBytes\":{\"p50\":" + pct(enc, .5) + ",\"max\":" + (enc.length == 0 ? 0 : enc[enc.length - 1]) + "},\"storedBytes\":{\"p50\":" + pct(sto, .5) + ",\"max\":" + (sto.length == 0 ? 0 : sto[sto.length - 1]) + "}"
            + ",\"prehistorySpanMs\":{\"min\":" + ms(span.length == 0 ? 0 : span[0]) + ",\"p50\":" + ms(pct(span, .5)) + ",\"max\":" + ms(span.length == 0 ? 0 : span[span.length - 1]) + "}"
            + ",\"observationsPerSecond\":" + (long) (r.observations() / r.seconds()) + ",\"tickOverruns\":" + r.tickOverruns() + ",\"maxTickLagMs\":" + ms(r.maxTickLagNanos())
            + ",\"refusedRollingShares\":" + r.refusedShares() + ",\"rollingGaps\":" + r.gaps() + ",\"rollingSegmentsDropped\":" + r.dropped() + ",\"rollingOverflows\":" + r.overflows()
            + ",\"closure\":\"" + r.closureCheck().replace("\"", "'") + "\"}";
    }
    static String ms(long nanos) { return String.format(Locale.ROOT, "%.3f", nanos / 1e6); }
    static String mapJson(Map<String, Long> m) { StringJoiner j = new StringJoiner(",", "{", "}"); m.forEach((k, v) -> j.add("\"" + k + "\":" + v)); return j.toString(); }
    static boolean pass(Run r) { long ok = r.samples().stream().filter(x -> x.durable() && x.latencyNanos() <= DEADLINE).count(); return !r.samples().isEmpty() && (double) ok / r.samples().size() >= 0.99; }

    /** args: outDir [gateSessions=32] [sweep=128,1000] [warmupSeconds=4] [measureSeconds=20] [capsuleDir] */
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]); int gate = args.length > 1 ? Integer.parseInt(args[1]) : 32;
        List<Integer> sweep = new ArrayList<>(); if (args.length > 2 && !args[2].isBlank()) for (String v : args[2].split(",")) sweep.add(Integer.parseInt(v.trim())); else sweep.addAll(List.of(128, 1000));
        int warm = args.length > 3 ? Integer.parseInt(args[3]) : 4, measure = args.length > 4 ? Integer.parseInt(args[4]) : 20;
        Path capsules = args.length > 5 ? Path.of(args[5]) : Path.of("build", "tc25-capsules").toAbsolutePath();
        Files.createDirectories(out);
        List<String> rows = new ArrayList<>(); boolean gatePass = true;
        for (int n : concat(gate, sweep)) for (Scenario sc : Scenario.values()) {
            for (Load load : Load.values()) {
                Run r = run(sc, load, n, warm, measure, capsules.resolve(sc + "-" + load + "-" + n)); boolean isGate = n == gate;
                String row = json(r, isGate); rows.add(row); System.out.println(row);
                if (isGate && !pass(r)) gatePass = false;
            }
        }
        String env = "{\"java\":\"" + System.getProperty("java.vendor") + " " + System.getProperty("java.version") + "\",\"os\":\"" + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch")
            + "\",\"cpus\":" + Runtime.getRuntime().availableProcessors() + ",\"maxHeapMiB\":" + (Runtime.getRuntime().maxMemory() >> 20) + ",\"capsuleDirectory\":\"" + capsules.toString().replace("\\", "/") + "\"}";
        String doc = "{\n\"test\":\"TC25\",\n\"requirement\":\"Sparse movement, dense combat and chunk-churn capsules: measured size/closure and >=99% durable within 400 ms under declared load; otherwise mitigation stays off (TESTING.md)\",\n"
            + "\"declaredLoad\":\"N sessions active for " + warm + " s warmup + " + measure + " s measured at 20 TPS real time. STRESS: one capture request per session per 2 s cooldown (random phase) plus one correlated burst of all N sessions in the same tick at mid-window (far above what the G2 proposal guard admits). G2_MAX: 2 requests/s from rotating sessions (the G2 burst gate maximum without suspension). Both loads at the gate N must pass; success = complete capsule promoted, accepted, fsynced, manifest atomically promoted, receipt durable within 400 ms of the decision point\",\n"
            + "\"codeUnderTest\":\"core DecisionCapture (engine rolling share, 250 ms checkpoints, promotion) + replay DurableCapsuleWriter (deflate, force, atomic capsule+manifest moves)\",\n"
            + "\"gateSessions\":" + gate + ",\n\"gateBasis\":\"REVIEW-2 NEW-M1 acceptance: hold at the maximum configured certified-session capacity for the tier under test (EngineConfiguration default capacity=32); larger N are the measured session-count dependence, not gates\",\n"
            + "\"environment\":" + env + ",\n\"runs\":[\n" + String.join(",\n", rows) + "\n],\n\"pass\":" + gatePass + "\n}\n";
        Files.writeString(out.resolve("tc25-results.json"), doc);
        Files.writeString(out.resolve("tc25-result.properties"), "# TC25 gate result (Tc25Main). The plugin's qualification ENFORCE command requires pass=true and gateSessions >= configured capacity.\npass=" + gatePass + "\ngateSessions=" + gate + "\n");
        System.out.println("TC25 gate(" + gate + " sessions) " + (gatePass ? "PASS" : "FAIL"));
    }
    static List<Integer> concat(int a, List<Integer> b) { List<Integer> l = new ArrayList<>(); l.add(a); for (int x : b) if (x != a) l.add(x); return l; }
}
