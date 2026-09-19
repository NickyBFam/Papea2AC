package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static me.nicolas.papea2ac.testing.WorldTestSupport.*;
import java.util.*;
import java.util.concurrent.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.packet.*;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.world.*;
import me.nicolas.papea2ac.world.BlockLookup.Status;

/**
 * Section 55: the real Phase 1 normalized packet -> ordered PacketEngine session -> WorldObservationMapper ->
 * anticheat-world -> block query flow, through PacketEngine.Shard.run() (the single ingestion point; no second
 * listener, no direct ObserverWorld.apply). Lookups from the test thread go through the published immutable
 * WorldSnapshot, exactly like the /papea2ac world diagnostic and any future cross-thread reader.
 */
class ObserverWorldIntegrationTest {
    static final SemanticPayload.JoinGame JOIN = new SemanticPayload.JoinGame(1, false, "minecraft:overworld", 20, 10, 10, "survival", true, false, -64, 384);
    static final long NOW = System.nanoTime();

    /** Wire-shaped section content from the world test builders (same packing as PalettedContainer.write). */
    static SemanticPayload.ChunkSection wire(WorldObservation.SectionContent c) {
        var palette = new ArrayList<SemanticPayload.BlockState>();
        for (var k : c.palette()) { var props = new ArrayList<SemanticPayload.Property>(); k.properties().forEach((n, v) -> props.add(new SemanticPayload.Property(n, v))); palette.add(new SemanticPayload.BlockState(k.block(), props)); }
        return new SemanticPayload.ChunkSection(c.nonEmptyBlockCount(), 0, palette, c.bitsPerEntry(), c.copyPacked());
    }
    static SemanticPayload.ChunkBaseline wireBaseline(int cx, int cz) {
        var sections = new ArrayList<SemanticPayload.ChunkSection>(); for (var s : baseline(cx, cz).sections()) sections.add(wire(s));
        return new SemanticPayload.ChunkBaseline(cx, cz, 12_000, 0, 4, true, true, sections, "");
    }
    static SemanticPayload.BlockState wire(BlockStateKey k) { return wire(new WorldObservation.SectionContent(0, List.of(k), 0, new long[0])).palette().getFirst(); }

    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(1);
        assertTrue(condition.getAsBoolean());
    }
    static void await(java.util.function.BooleanSupplier condition, java.util.function.Supplier<String> message) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(1);
        assertTrue(condition.getAsBoolean(), message);
    }

    @Test void clientboundWorldPacketsCapturedThroughTheRealEngineBuildTheSessionsObserverWorld() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> fail(e));
        var loop = new PacketTruthTest.Loop();
        var player = new UUID(55, 1);
        try {
            var session = engine.connect(player, loop, TransportCapability.observeOnly());
            assertFalse(engine.worldStatus(player).orElseThrow().hasDimension(), "before JoinGame nothing is known - not even the dimension");
            long wire = 0;
            session.ingress.capture(new Observation.Outbound(++wire, JOIN), NOW);
            session.ingress.capture(new Observation.Outbound(++wire, wireBaseline(0, 0)), NOW);
            session.ingress.capture(new Observation.Outbound(++wire, wireBaseline(-1, 0)), NOW);
            await(() -> session.world.snapshot().chunkCount() == 2);
            var snap = session.world.snapshot();
            assertEquals("minecraft:overworld", snap.dimension().dimensionId()); assertEquals(-64, snap.dimension().minY()); assertEquals(384, snap.dimension().height());
            assertEquals(2, snap.completeChunks());
            assertEquals(STONE, snap.blockAt(new BlockPos(3, 0, 3), engine.blockStates).state(), "Phase 1 section content decoded at absolute Y through the observer world");
            assertEquals(DIRT, snap.blockAt(new BlockPos(-1, 68, 7), engine.blockStates).state()); assertEquals(AIR, snap.blockAt(new BlockPos(5, 100, 5), engine.blockStates).state());
            assertEquals(Status.UNKNOWN_CHUNK, snap.blockAt(new BlockPos(40, 64, 40), engine.blockStates).status(), "never-sent chunk is unknown, never air");

            // single + multi-block updates, then a tick (publication cadence), then an unload
            session.ingress.capture(new Observation.Outbound(++wire, new SemanticPayload.BlockChange(new SemanticPayload.BlockPosition(3, 70, 3), wire(STAIRS))), NOW);
            var updates = new ArrayList<SemanticPayload.BlockUpdate>(); for (int i = 0; i < 16; i++) updates.add(new SemanticPayload.BlockUpdate(new SemanticPayload.BlockPosition(i - 16, 66, 7), wire(WATER)));
            session.ingress.capture(new Observation.Outbound(++wire, new SemanticPayload.SectionBlocksUpdate(-1, 4, 0, updates)), NOW);
            session.ingress.capture(new Observation.Tick(1), NOW);
            await(() -> session.world.snapshot().lastSequence() == 6);
            snap = session.world.snapshot();
            assertEquals(STAIRS, snap.blockAt(new BlockPos(3, 70, 3), engine.blockStates).state()); assertEquals(WATER, snap.blockAt(new BlockPos(-9, 66, 7), engine.blockStates).state());
            assertEquals(STONE, snap.blockAt(new BlockPos(3, 0, 3), engine.blockStates).state(), "unaffected block preserved");
            assertEquals(2, snap.completeChunks(), "updates never downgrade complete baselines");

            session.ingress.capture(new Observation.Outbound(++wire, new SemanticPayload.ChunkUnload(0, 0)), NOW);
            await(() -> session.world.snapshot().chunkCount() == 1);
            assertEquals(Status.UNKNOWN_CHUNK, session.world.snapshot().blockAt(new BlockPos(3, 70, 3), engine.blockStates).status(), "unloaded chunk is no longer current (snapshot view carries no tombstone detail)");
            var status = engine.worldStatus(player).orElseThrow();
            assertEquals(1, status.chunkCount()); assertEquals(1, status.unloadedChunks()); assertEquals(7, status.lastSequence()); assertTrue(status.revision() >= 5); assertFalse(status.transportBroken());
            assertEquals(session.key.epoch(), status.sessionEpoch());
            var budget = engine.worldBudgetSnapshot();
            assertEquals(1, budget.observerWorlds()); assertEquals(1, budget.chunks()); assertTrue(budget.sharedBytes() > 0, "baseline sections live in the shared store");
        } finally { engine.close(); loop.drain(); }
        PlayerTimelineIntegrationTest.awaitDraining(loop, engine::terminated); // session close (and world.close) runs on the shard thread
        assertEquals(0, engine.worldBudgetSnapshot().sharedBytes(), "closing the engine releases every shared section"); assertEquals(0, engine.worldBudgetSnapshot().observerWorlds());
    }

    @Test void aBundleIsAppliedInWireOrderWithinOneSequence() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> fail(e));
        var loop = new PacketTruthTest.Loop();
        var player = new UUID(55, 2);
        try {
            var session = engine.connect(player, loop, TransportCapability.observeOnly());
            var pos = new SemanticPayload.BlockPosition(1, 70, 1);
            var bundle = new PacketPayload.Bundle(List.of(JOIN, wireBaseline(0, 0), new SemanticPayload.BlockChange(pos, wire(DIRT)), new SemanticPayload.BlockChange(pos, wire(STAIRS))));
            session.ingress.capture(new Observation.Outbound(1, bundle), NOW);
            session.ingress.capture(new Observation.Tick(1), NOW);
            await(() -> session.world.snapshot().lastSequence() == 2);
            assertEquals(STAIRS, session.world.snapshot().blockAt(new BlockPos(1, 70, 1), engine.blockStates).state(), "the LAST child in wire order wins");
            assertEquals(1, session.world.snapshot().completeChunks());
        } finally { engine.close(); loop.drain(); }
    }

    @Test void unknownPlayerHasNoWorldStatusAndTransportDegradeBreaksCoverage() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> {});
        var loop = new PacketTruthTest.Loop();
        var player = new UUID(55, 3);
        try {
            assertTrue(engine.worldStatus(new UUID(0, 0)).isEmpty());
            var session = engine.connect(player, loop, TransportCapability.observeOnly());
            session.ingress.capture(new Observation.Outbound(1, JOIN), NOW);
            session.ingress.capture(new Observation.Outbound(2, wireBaseline(0, 0)), NOW);
            await(() -> session.world.snapshot().chunkCount() == 1);
            assertEquals(1, session.world.snapshot().completeChunks());
            // An uncovered inbound packet degrades the session latch (PIPELINE_UNCOVERED) - a real session hits this with the client's minecraft:brand payload at play start.
            // That is not a clientbound observation loss: the world must NOT break on it (P3-F12).
            session.ingress.capture(new Observation.Attempt(1, 1, new PacketPayload.Uncovered(PacketPayload.Family.UNKNOWN, "test")), NOW);
            session.ingress.capture(new Observation.Tick(1), NOW);
            await(() -> session.world.snapshot().lastSequence() >= 4);
            assertEquals(me.nicolas.papea2ac.model.HealthLatch.Reason.PIPELINE_UNCOVERED, session.actor.snapshot().reason());
            assertFalse(session.world.snapshot().transportBroken(), "PIPELINE_UNCOVERED from a packet is not a world break"); assertEquals(1, session.world.snapshot().completeChunks());
            // The world-relevant case - our capture handler removed/displaced - is reported explicitly by the platform and lands on the next ordered observation.
            session.captureCompromised("OUTBOUND_HANDLER_REMOVED");
            session.ingress.capture(new Observation.Tick(2), NOW);
            await(() -> session.world.snapshot().transportBroken());
            var snap = session.world.snapshot();
            assertEquals("CAPTURE_COMPROMISED:OUTBOUND_HANDLER_REMOVED", snap.transportBreakReason());
            assertEquals(1, snap.transportIncompleteChunks()); assertEquals(0, snap.completeChunks());
            assertEquals(Status.TRANSPORT_INCOMPLETE, snap.blockAt(new BlockPos(0, 0, 0), engine.blockStates).status());
            session.ingress.capture(new Observation.Outbound(3, wireBaseline(1, 0)), NOW);
            await(() -> session.world.snapshot().chunkCount() == 2);
            assertEquals(1, session.world.snapshot().transportIncompleteChunks(), "the chunk held at the break stays incomplete"); assertEquals(1, session.world.snapshot().completeChunks(), "a full baseline after the break is a qualifying observation for its chunk (P3-F8)");
            assertEquals(1, engine.worldBudgetSnapshot().coverageBreaks(), "reported once, however many times the platform repeats it");
            session.captureCompromised("AGAIN"); session.ingress.capture(new Observation.Tick(3), NOW); await(() -> session.world.snapshot().lastSequence() >= 7);
            assertEquals(2, engine.worldBudgetSnapshot().coverageBreaks(), "a later, distinct report is a new break event");
        } finally { engine.close(); loop.drain(); }
    }

    @Test void reconnectGetsAFreshEmptyWorldAndTheOldSessionsSharedChargeIsReleased() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> fail(e));
        var loop = new PacketTruthTest.Loop();
        var player = new UUID(55, 4);
        try {
            var first = engine.connect(player, loop, TransportCapability.observeOnly());
            first.ingress.capture(new Observation.Outbound(1, JOIN), NOW); first.ingress.capture(new Observation.Outbound(2, wireBaseline(0, 0)), NOW);
            await(() -> first.world.snapshot().chunkCount() == 1);
            long sharedWithOne = engine.worldBudgetSnapshot().sharedBytes(); assertTrue(sharedWithOne > 0);
            var second = engine.connect(player, loop, TransportCapability.observeOnly()); // replaces the first session
            assertNotSame(first.world, second.world); assertNotEquals(first.key.epoch(), second.key.epoch());
            PlayerTimelineIntegrationTest.awaitDraining(loop, () -> first.world.snapshot().closed());
            assertEquals(0, second.world.snapshot().chunkCount(), "nothing carries over: the new session's world is empty until the server resends");
            assertFalse(second.world.snapshot().dimension() != null);
            assertEquals(0, engine.worldBudgetSnapshot().sharedBytes(), "old session's shared references released on replacement");
            assertEquals(1, engine.worldBudgetSnapshot().observerWorlds());
        } finally { engine.close(); loop.drain(); }
    }
    /** P3-F3 (found by the real-client qualification): every real session is EXPIRED_OBSERVE_ONLY (inbound attempts never get a certified outcome while correlation is UNSUPPORTED). That is an outcome-correlation fact, not a lost clientbound observation - the world must stay HEALTHY and complete; only the player timeline records the coverage gap. */
    @Test void outcomeExpiryDegradesTheSessionButNeverBreaksWorldCoverage() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> fail(e));
        var loop = new PacketTruthTest.Loop();
        var player = new UUID(55, 5);
        try {
            var session = engine.connect(player, loop, TransportCapability.observeOnly());
            // an attempt captured "long ago" (stale capture clock) can never be certified in time: the actor expires it on its next tick -> EXPIRED
            session.ingress.capture(new Observation.Attempt(1, 1, PacketTruthTest.MOVE), System.nanoTime() - TimeUnit.SECONDS.toNanos(60));
            session.ingress.capture(new Observation.Tick(0), NOW); // wakes the shard so its next loop iteration ticks the actor (idle shards park)
            await(() -> session.actor.snapshot().reason() == me.nicolas.papea2ac.model.HealthLatch.Reason.EXPIRED, () -> "actor=" + session.actor.snapshot() + " health=" + session.health.get());
            session.ingress.capture(new Observation.Outbound(1, JOIN), NOW);
            session.ingress.capture(new Observation.Outbound(2, wireBaseline(0, 0)), NOW);
            session.ingress.capture(new Observation.Outbound(3, new SemanticPayload.BlockChange(new SemanticPayload.BlockPosition(3, 70, 3), wire(STAIRS))), NOW);
            session.ingress.capture(new Observation.Tick(1), NOW);
            await(() -> session.world.snapshot().lastSequence() >= 6, () -> "world=" + session.world.snapshot().lastSequence() + "/" + session.world.snapshot().chunkCount() + " timeline=" + session.timeline.snapshot().lastCommittedSequence() + " actor=" + session.actor.snapshot().reason() + " queue=" + session.ingress.queueDepth());
            var snap = session.world.snapshot();
            assertFalse(snap.transportBroken(), "EXPIRED is not a clientbound coverage break");
            assertEquals(1, snap.completeChunks()); assertEquals(0, snap.transportIncompleteChunks());
            assertEquals(STAIRS, snap.blockAt(new BlockPos(3, 70, 3), engine.blockStates).state(), "clientbound updates observed after the expiry are applied normally");
            assertEquals(0, engine.worldBudgetSnapshot().coverageBreaks());
            assertEquals(me.nicolas.papea2ac.model.HealthLatch.Reason.EXPIRED, session.actor.snapshot().reason(), "the Phase 1 session health itself stays EXPIRED (sticky)");
            assertTrue(engine.timelineStatus(player).orElseThrow().lastAppendHadCoverageIssue(), "the player timeline keeps recording the Phase 2 coverage gap");
            // uncovered CLIENTBOUND payloads (state-free by the Phase 1 mandatory gate, or non-packet pipeline objects such as UnconfiguredPipelineHandler$OutboundConfigurationTask) never break the world
            session.ingress.capture(new Observation.Outbound(4, new PacketPayload.Uncovered(PacketPayload.Family.UNKNOWN, "MissingObservationFamily:net.minecraft.network.UnconfiguredPipelineHandler$OutboundConfigurationTask")), NOW);
            session.ingress.capture(new Observation.Outbound(5, new PacketPayload.Uncovered(PacketPayload.Family.WORLD, "MissingObservationFamily")), NOW); // e.g. block_destruction (crack animation)
            session.ingress.capture(new Observation.Tick(2), NOW);
            await(() -> session.world.snapshot().lastSequence() >= 9);
            assertFalse(session.world.snapshot().transportBroken(), "uncovered clientbound payloads are not world coverage breaks");
            assertEquals(1, session.world.snapshot().completeChunks());
            assertEquals("PIPELINE_UNCOVERED:UNKNOWN:UnconfiguredPipelineHandler$OutboundC", WorldObservationMapper.uncoveredReason(new PacketPayload.Uncovered(PacketPayload.Family.UNKNOWN, "MissingObservationFamily:net.minecraft.network.UnconfiguredPipelineHandler$OutboundConfigurationTask")), "diagnostic reason names the object (bounded to 64 chars)");
            assertTrue(WorldObservationMapper.map(new Observation(session.key, 99, 0, 0, new Observation.Outbound(9, new PacketPayload.Uncovered(PacketPayload.Family.WORLD, "x")))).isEmpty());
        } finally { engine.close(); loop.drain(); }
    }
    /** P3-F3: a dropped capture consumes a sequence number (Phase 1 ingress), so the world can see the loss as a gap even when the latch is already stuck on an earlier reason. */
    @Test void aDroppedCaptureLeavesASequenceGapThatBreaksWorldCoverage() {
        var loop = new PacketTruthTest.Loop(); var latch = new me.nicolas.papea2ac.model.HealthLatch();
        var key = new SessionKey(new UUID(55, 6), 1);
        var ingress = new ConnectionIngress(key, loop, latch, 1); // capacity 1, never drained while we fill it
        assertTrue(ingress.capture(new Observation.Tick(1), 0)); assertFalse(ingress.capture(new Observation.Tick(2), 0), "second capture dropped: queue full");
        var first = ingress.poll(); assertEquals(1, first.sequence());
        assertTrue(ingress.capture(new Observation.Tick(3), 0)); var third = ingress.poll();
        assertEquals(3, third.sequence(), "the dropped observation consumed sequence 2 - the gap is visible downstream");
        var world = new ObserverWorld(key, new WorldBudget(), new BlockStateDictionary());
        world.apply(key, OVERWORLD, 1, 0, null); world.apply(key, baseline(0, 0), 2, 0, null); // pretend 1..2 were delivered
        assertEquals(WorldCoverage.COMPLETE, world.coverageOf(new ChunkKey(0, 0)));
        world.apply(key, new WorldObservation.TickObserved(0), 4, 0, null); // sequence 3 never arrived
        assertTrue(world.transportBroken()); assertEquals("SEQUENCE_GAP:3", world.snapshot().transportBreakReason());
        assertEquals(WorldCoverage.TRANSPORT_INCOMPLETE, world.coverageOf(new ChunkKey(0, 0)), "held chunks are no longer a trustworthy client view");
        world.apply(key, baseline(1, 0), 5, 0, null); assertEquals(WorldCoverage.COMPLETE, world.coverageOf(new ChunkKey(1, 0)), "a post-gap full baseline is complete (P3-F8)"); assertEquals(WorldCoverage.TRANSPORT_INCOMPLETE, world.coverageOf(new ChunkKey(0, 0)), "the held chunk is not");
        var w2 = new ObserverWorld(key, new WorldBudget(), new BlockStateDictionary());
        w2.apply(key, OVERWORLD, 1, 0, null); assertTrue(w2.observeSequence(key, 2, 0)); assertTrue(w2.observeSequence(key, 4, 0));
        assertTrue(w2.transportBroken(), "a gap across world-silent sequences is detected too"); assertEquals("SEQUENCE_GAP:3", w2.snapshot().transportBreakReason());
        var w3 = new ObserverWorld(key, new WorldBudget(), new BlockStateDictionary());
        w3.apply(key, OVERWORLD, 7, 0, null); w3.apply(key, baseline(0, 0), 8, 0, null); assertFalse(w3.transportBroken(), "a late first sequence (mid-session activation / checkpoint) is not a gap");
    }
    @Test void coverageBreakReasonClassifiesOnlyClientboundRelevantReasons() {
        for (var r : me.nicolas.papea2ac.model.HealthLatch.Reason.values()) {
            var state = new me.nicolas.papea2ac.model.HealthLatch.State(1, r, 0);
            String expected = switch (r) { case HEALTHY, EXPIRED, OUTCOME_UNKNOWN, CANCELLED, TRANSFORMED, PIPELINE_UNCOVERED -> null; default -> r.name(); };
            assertEquals(expected, WorldObservationMapper.coverageBreakReason(state, false), r.name());
            assertNull(WorldObservationMapper.coverageBreakReason(state, true), "a deliberate close is never a break");
        }
    }
    /** P3-F4 (real-client finding: INGRESS_DROPPED=8 on join): chunk section content (~20 KiB per baseline) was charged to the 192 KiB ingress record arena, so a chunk burst overflowed it and DROPPED observations (sequence gaps -> whole-world TRANSPORT_INCOMPLETE). Bulk content now lives in the per-session bulk exposure pool (PERFORMANCE.md: 4 MiB ceiling, shed - never dropped). */
    @Test void chunkContentIsChargedToTheBulkPoolAndShedNeverDroppedAtTheCeiling() {
        var loop = new PacketTruthTest.Loop(); var latch = new me.nicolas.papea2ac.model.HealthLatch();
        var key = new SessionKey(new UUID(55, 7), 1); var losses = new ArrayList<ConnectionIngress.Loss>();
        var ingress = new ConnectionIngress(key, loop, latch, 1024, e -> Observation.Context.synthetic(), losses::add);
        // a heavy but realistic baseline: 24 non-uniform 4-bit sections (~50 KiB of section content), the shape of a cave/mountain chunk
        var heavy = new ArrayList<SemanticPayload.ChunkSection>(); for (int s = 0; s < 24; s++) { final int k = s; heavy.add(wire(section(idx -> (idx + k) % 7 == 0 ? DIRT : (idx & 1) == 0 ? STONE : AIR))); }
        var chunk = new SemanticPayload.ChunkBaseline(0, 0, 60000, 0, 4, true, true, heavy, ""); int bulk = chunk.bulkBytes(); assertTrue(bulk > 40_000, "heavy baseline: " + bulk);
        int inFlight = 0;
        for (int i = 0; i < 300; i++) { assertTrue(ingress.capture(new Observation.Outbound(i + 1, chunk), 0), "chunk " + i + " must never be dropped"); inFlight++; }
        assertTrue(latch.get().healthy(), "no OVERLOADED: the record arena only carries the record part");
        assertTrue(ingress.queueDepth() == 300 && ingress.retainedBytes() >= ConnectionIngress.BULK_CEILING_BYTES - bulk, "bulk pool is charged: " + ingress.bulkBytes());
        int shed = (int) losses.stream().filter(l -> l == ConnectionIngress.Loss.BULK_SHED).count();
        assertTrue(shed > 0 && shed < 300, "the ceiling sheds content (" + shed + ") instead of dropping records"); assertFalse(losses.contains(ConnectionIngress.Loss.INGRESS_FULL));
        long seq = 0; int retained = 0, notRetained = 0;
        for (int i = 0; i < inFlight; i++) {
            var o = ingress.poll(); assertNotNull(o); assertEquals(++seq, o.sequence(), "contiguous: no gap");
            var b = (SemanticPayload.ChunkBaseline) ((Observation.Outbound) o.event()).payload();
            if (b.contentRetained()) retained++; else { notRetained++; assertEquals("BULK_EXPOSURE_CEILING", b.contentNote()); assertTrue(b.sections().isEmpty()); }
        }
        assertEquals(300 - shed, retained); assertEquals(shed, notRetained); assertEquals(0, ingress.bulkBytes(), "bulk released on poll"); assertEquals(0, ingress.retainedBytes());
        // a shed baseline is honest in the world: CONTENT_NOT_RETAINED with the note, never air, never a gap
        var world = new ObserverWorld(key, new WorldBudget(), new BlockStateDictionary()); world.apply(key, OVERWORLD, 1, 0, null);
        world.apply(key, WorldObservationMapper.map(new Observation(key, 2, 0, 0, new Observation.Outbound(1, chunk.shedBulk("BULK_EXPOSURE_CEILING")))).getFirst(), 2, 0, null);
        assertEquals(WorldCoverage.CONTENT_NOT_RETAINED, world.coverageOf(new ChunkKey(0, 0))); assertEquals("BULK_EXPOSURE_CEILING", world.chunk(new ChunkKey(0, 0)).orElseThrow().coverageNote());
        assertEquals(Status.CONTENT_NOT_RETAINED, world.blockAt(new BlockPos(0, 0, 0)).status()); assertFalse(world.transportBroken());
    }
    /** A join-sized burst of distinct chunks through the real engine: every chunk arrives complete, no gap, no shed, world HEALTHY. */
    @Test void aJoinSizedChunkBurstThroughTheRealEngineLosesNothing() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> fail(e));
        var loop = new PacketTruthTest.Loop();
        var player = new UUID(55, 8);
        try {
            var session = engine.connect(player, loop, TransportCapability.observeOnly());
            session.ingress.capture(new Observation.Outbound(1, JOIN), NOW);
            int n = 0; for (int x = -7; x <= 7; x++) for (int z = -7; z <= 7; z++) { n++; assertTrue(session.ingress.capture(new Observation.Outbound(1 + n, wireBaseline(x, z)), NOW)); }
            final int total = n;
            await(() -> session.world.snapshot().chunkCount() == total);
            var snap = session.world.snapshot();
            assertEquals(total, snap.completeChunks(), "225 chunks, all complete"); assertEquals(0, snap.contentNotRetainedChunks()); assertFalse(snap.transportBroken(), "no gap, no break");
            assertEquals(0, engine.metrics.count(me.nicolas.papea2ac.core.PacketMetrics.Kind.INGRESS_DROPPED)); assertEquals(0, engine.metrics.count(me.nicolas.papea2ac.core.PacketMetrics.Kind.BULK_SHED));
        } finally { engine.close(); loop.drain(); }
    }
    /** P3-F5 (real-client finding: INGRESS_DROPPED=974 at join): a real join floods a few thousand small clientbound packets in one tick. The shard drained at most 32 observations per pass and then parked unconditionally (>= 1 ms on Windows), so the 1024-record ingress overflowed and DROPPED observations (sequence gaps). */
    @Test void aJoinSizedPacketFloodIsDrainedWithoutIngressDrops() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> fail(e));
        var loop = new PacketTruthTest.Loop();
        var player = new UUID(55, 9);
        try {
            var session = engine.connect(player, loop, TransportCapability.observeOnly());
            session.ingress.capture(new Observation.Outbound(1, JOIN), NOW);
            int wire = 1, n = 0;
            for (int x = -7; x <= 7; x++) for (int z = -7; z <= 7; z++) {
                assertTrue(session.ingress.capture(new Observation.Outbound(++wire, wireBaseline(x, z)), NOW), "chunk dropped at " + n); n++;
                for (int e = 0; e < 12; e++) { assertTrue(session.ingress.capture(new Observation.Outbound(++wire, new PacketPayload.Velocity(1000 + n * 16 + e, new PacketPayload.Position(0, 0.1, 0))), NOW), "entity packet dropped at " + n + "/" + e); n++; }
            }
            final int total = 225;
            await(() -> session.world.snapshot().chunkCount() == total);
            assertEquals(0, engine.metrics.count(me.nicolas.papea2ac.core.PacketMetrics.Kind.INGRESS_DROPPED), "a join-sized flood (" + n + " observations) must not overflow the ingress");
            assertFalse(session.world.snapshot().transportBroken()); assertEquals(total, session.world.snapshot().completeChunks());
        } finally { engine.close(); loop.drain(); }
    }
    /** P3-F5b: an idle shard is parked; the first observation of a burst must wake it instead of waiting out the park (>= 1 ms on Windows, during which a join burst overflows the queue). */
    @Test void theFirstObservationWakesAnIdleShardPromptly() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> fail(e));
        var loop = new PacketTruthTest.Loop();
        var player = new UUID(55, 10);
        try {
            var session = engine.connect(player, loop, TransportCapability.observeOnly());
            Thread.sleep(50); // let the shard settle into its idle park
            long[] latencies = new long[20];
            for (int i = 0; i < latencies.length; i++) {
                long t0 = System.nanoTime(); long seq = session.timeline.snapshot().lastCommittedSequence();
                session.ingress.capture(new Observation.Tick(i), NOW);
                while (session.timeline.snapshot().lastCommittedSequence() == seq) { if (System.nanoTime() - t0 > TimeUnit.SECONDS.toNanos(2)) fail("shard never processed the observation"); Thread.onSpinWait(); }
                latencies[i] = System.nanoTime() - t0; Thread.sleep(20); // back to idle between probes
            }
            java.util.Arrays.sort(latencies);
            assertTrue(latencies[latencies.length / 2] < TimeUnit.MILLISECONDS.toNanos(1), "median idle wake-to-processed latency " + latencies[latencies.length / 2] / 1000 + " us must be sub-millisecond (park was 100 us nominal, >= 1 ms real)");
        } finally { engine.close(); loop.drain(); }
    }
    /** P3-F6 (real-client finding: SEQUENCE_GAP with INGRESS_DROPPED=0): an observation whose cost exceeded the shard's 2048-unit slice (a near-maximal entity Bundle, ~64 KiB charged) was silently dropped by the work-quota branch. It must be processed - the slice is fairness, not a size limit - and the world must see a contiguous sequence. */
    @Test void anOversizeBundleIsProcessedNotSilentlyDropped() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> fail(e));
        var loop = new PacketTruthTest.Loop();
        var player = new UUID(55, 11);
        try {
            var session = engine.connect(player, loop, TransportCapability.observeOnly());
            session.ingress.capture(new Observation.Outbound(1, JOIN), NOW);
            session.ingress.capture(new Observation.Outbound(2, wireBaseline(0, 0)), NOW);
            var children = new ArrayList<PacketPayload>(); for (int i = 0; i < 127; i++) children.add(new SemanticPayload.EntityPosition(500 + i, new PacketPayload.Position(1, 64, 1), new PacketPayload.Position(0, 0, 0), new PacketPayload.Rotation(0, 0), Set.of(), true)); // 127 x 512 B + a block change: a maximal bundle (<= the 64 KiB bundle bound)
            children.add(new SemanticPayload.BlockChange(new SemanticPayload.BlockPosition(2, 70, 2), wire(STONE)));
            var bundle = new PacketPayload.Bundle(children);
            int cost = 1 + (new Observation(session.key, 3, 0, 0, new Observation.Outbound(3, bundle)).chargedBytes() + 31) / 32;
            assertTrue(cost > 2048, "the fixture must exceed the slice: cost=" + cost);
            session.ingress.capture(new Observation.Outbound(3, bundle), NOW);
            session.ingress.capture(new Observation.Tick(1), NOW);
            await(() -> session.world.snapshot().lastSequence() >= 4);
            var snap = session.world.snapshot();
            assertFalse(snap.transportBroken(), "no gap: the oversize observation was processed, not dropped");
            assertEquals(STONE, snap.blockAt(new BlockPos(2, 70, 2), engine.blockStates).state(), "the bundle's last child was applied");
            assertEquals(0, engine.metrics.count(me.nicolas.papea2ac.core.PacketMetrics.Kind.INGRESS_DROPPED));
        } finally { engine.close(); loop.drain(); }
    }
    /** P3-F9 (real-client finding: 664 drops / 48 breaks in fast flight after the arena fix): block states were charged at the 256-char worst case (640 + 1152/property), so one multi-block update of a few dozen 4-property states weighed hundreds of KiB. Charges now reflect the retained strings; a realistic burst of such updates must fit the ingress. */
    @Test void multiBlockUpdatesWithPropertyRichStatesAreChargedRealisticallyAndNeverDropped() {
        var stairs = new SemanticPayload.BlockState("minecraft:polished_blackstone_brick_stairs", List.of(new SemanticPayload.Property("facing", "north"), new SemanticPayload.Property("half", "bottom"), new SemanticPayload.Property("shape", "inner_left"), new SemanticPayload.Property("waterlogged", "false")));
        assertTrue(stairs.chargedBytes() < 1024, "a 4-property state is a few hundred retained bytes, not 5 KiB: " + stairs.chargedBytes());
        var updates = new ArrayList<SemanticPayload.BlockUpdate>(); for (int i = 0; i < 64; i++) updates.add(new SemanticPayload.BlockUpdate(new SemanticPayload.BlockPosition(i & 15, 64 + ((i >> 4) & 15), (i >> 2) & 15), stairs));
        var update = new SemanticPayload.SectionBlocksUpdate(0, 4, 0, updates);
        assertTrue(update.chargedBytes() < 65_536, "64 property-rich updates: " + update.chargedBytes() + " charged bytes (was ~344 KiB)");
        var loop = new PacketTruthTest.Loop(); var latch = new me.nicolas.papea2ac.model.HealthLatch(); var key = new SessionKey(new UUID(55, 12), 1);
        var losses = new ArrayList<ConnectionIngress.Loss>(); var ingress = new ConnectionIngress(key, loop, latch, 4096, e -> Observation.Context.synthetic(), losses::add);
        for (int i = 0; i < 60; i++) assertTrue(ingress.capture(new Observation.Outbound(i + 1, update), 0), "update " + i + " dropped"); // 60 x ~43 KiB = 2.5 MiB in flight (one such update used to weigh ~344 KiB: 12 filled the old 4 MiB arena, 0 the 192 KiB one)
        assertTrue(losses.isEmpty()); assertTrue(latch.get().healthy(), "60 maximal property-rich updates in flight fit the arena");
        assertTrue(new Observation(key, 1, 0, 0, new Observation.Outbound(1, update)).chargedBytes() / 32 < 2048, "and one of them fits the shard's slice");
    }
}
