package me.nicolas.papea2ac.core;
import me.nicolas.papea2ac.model.BlockPos;
import me.nicolas.papea2ac.model.BlockStateKey;
import me.nicolas.papea2ac.model.HealthLatch;
import me.nicolas.papea2ac.protocol.Observation;
import me.nicolas.papea2ac.protocol.PacketPayload;
import me.nicolas.papea2ac.protocol.SemanticPayload;
import me.nicolas.papea2ac.world.WorldObservation;
import java.util.*;

/**
 * DAG-mandated translation from Phase 1's protocol.Observation into world-owned WorldObservations (anticheat-world
 * may not depend on protocol/packet; core is the only module that legally sees both). Only CLIENTBOUND world
 * families are mapped - what the server told this client - never a server-side world query. A bundle's children
 * are emitted in wire order; the caller preserves that order via ObserverWorld.apply's subIndex. Coordinates the
 * model cannot represent (outside BlockPos's protocol range) are dropped explicitly, never wrapped.
 */
public final class WorldObservationMapper {
    private WorldObservationMapper() {}

    /**
     * Which Phase 1 health reasons break WORLD coverage. The observer world reconstructs CLIENTBOUND traffic, so only a
     * reason that can mean a clientbound observation was lost, dropped or left undecoded breaks it. Reasons that describe
     * inbound outcome correlation - EXPIRED (no certified outcome within the deadline, the normal state while correlation
     * is UNSUPPORTED), OUTCOME_UNKNOWN, CANCELLED, TRANSFORMED - leave the observed clientbound stream intact and return
     * null. (P3-F3: found by the real-client qualification, where every real session is EXPIRED_OBSERVE_ONLY and the whole
     * world was wrongly TRANSPORT_INCOMPLETE.) The player timeline keeps its own, broader Phase 2 semantics.
     */
    public static String coverageBreakReason(HealthLatch.State state, boolean deliberateClose) {
        if (state.healthy() || deliberateClose) return null;
        return switch (state.reason()) {
            case EXPIRED, OUTCOME_UNKNOWN, CANCELLED, TRANSFORMED -> null;
            // PIPELINE_UNCOVERED is raised by uncovered inbound/outbound PACKETS (state-free by the mandatory gate; on a real session the client's
            // minecraft:brand custom payload trips it at play start). The one world-relevant cause - a compromised capture position - is reported
            // separately by the platform through Session.captureCompromised (P3-F12).
            case PIPELINE_UNCOVERED -> null;
            default -> state.reason().name();
        };
    }

    public static List<WorldObservation> map(Observation observation) {
        return switch (observation.event()) {
            case Observation.Outbound o -> outbound(o.payload());
            case Observation.Tick t -> List.of(new WorldObservation.TickObserved(t.serverTick()));
            case Observation.Attempt ignored -> List.of();
            case Observation.Outcome ignored -> List.of();
            case Observation.WriteOutcome ignored -> List.of();
            case Observation.ControlChange ignored -> List.of();
            case Observation.CheckpointMarker ignored -> List.of();
        };
    }

    private static List<WorldObservation> outbound(PacketPayload payload) {
        if (payload instanceof PacketPayload.Bundle bundle) {
            var out = new ArrayList<WorldObservation>();
            for (var child : bundle.children()) out.addAll(outbound(child));
            return out;
        }
        // An uncovered clientbound payload is NOT a world coverage break (P3-F3, real-client finding): Phase 1 gates every
        // block/chunk-state-bearing clientbound packet as MANDATORY (127/127 FULLY NORMALIZED against the pinned build), so what
        // reaches here uncovered is by specification state-free (block_destruction crack animation, level_event, debug/UI
        // packets) or not a packet at all (the vanilla UnconfiguredPipelineHandler$OutboundConfigurationTask object, the
        // protocol-switch marker written at login->configuration->play). Breaking on it made every real session
        // TRANSPORT_INCOMPLETE. Real clientbound losses are caught by the session-sequence gap (ObserverWorld.noteGap) and the
        // loss-class latch reasons (coverageBreakReason). Diagnosability of the uncovered object itself is uncoveredReason().
        if (payload instanceof PacketPayload.Uncovered) return List.of();
        WorldObservation single = switch (payload) {
            case SemanticPayload.JoinGame j -> new WorldObservation.DimensionEntered(j.dimension(), j.dimensionMinY(), j.dimensionHeight(), false);
            case SemanticPayload.Respawn r -> new WorldObservation.DimensionEntered(r.dimension(), r.dimensionMinY(), r.dimensionHeight(), true);
            case SemanticPayload.ChunkBaseline b -> baseline(b);
            case SemanticPayload.BlockChange c -> pos(c.position()) == null ? null : new WorldObservation.BlockChanged(pos(c.position()), key(c.state()));
            case SemanticPayload.SectionBlocksUpdate s -> sectionUpdate(s);
            case SemanticPayload.ChunkUnload u -> new WorldObservation.ChunkUnloaded(u.x(), u.z());
            case SemanticPayload.BlockEntityObservation be -> pos(be.position()) == null ? null : new WorldObservation.BlockEntityObserved(pos(be.position()), be.type(), be.encodedTagBytes());
            case SemanticPayload.ChunkCacheCenter c -> new WorldObservation.ViewCenter(c.x(), c.z());
            case SemanticPayload.ChunkCacheRadius r -> new WorldObservation.ViewRadius(r.radius());
            case SemanticPayload.BlockEvent e -> pos(e.position()) == null ? null : new WorldObservation.BlockEventObserved(pos(e.position()), e.block(), e.action(), e.parameter());
            default -> null;
        };
        return single == null ? List.of() : List.of(single);
    }

    /** Bounded break reason: family plus, when the normalizer recorded one, the simple class name of the uncovered pipeline object. */
    public static String uncoveredReason(PacketPayload.Uncovered u) {
        String detail = u.reason().startsWith("MissingObservationFamily:") ? u.reason().substring(u.reason().lastIndexOf('.') + 1) : "";
        String reason = "PIPELINE_UNCOVERED:" + u.family().name() + (detail.isEmpty() ? "" : ":" + detail);
        return reason.length() > 64 ? reason.substring(0, 64) : reason;
    }
    private static WorldObservation baseline(SemanticPayload.ChunkBaseline b) {
        var sections = new ArrayList<WorldObservation.SectionContent>(b.sections().size());
        for (var s : b.sections()) {
            var palette = new ArrayList<BlockStateKey>(s.palette().size());
            for (var state : s.palette()) palette.add(key(state));
            long[] packed = new long[s.packedLength()]; for (int i = 0; i < packed.length; i++) packed[i] = s.packedWord(i);
            sections.add(new WorldObservation.SectionContent(s.nonEmptyBlockCount(), palette, s.bitsPerEntry(), packed));
        }
        return new WorldObservation.ChunkBaselineObserved(b.chunkX(), b.chunkZ(), b.contentRetained(), sections, b.blockEntityCount(), b.contentNote());
    }

    private static WorldObservation sectionUpdate(SemanticPayload.SectionBlocksUpdate s) {
        var changes = new ArrayList<WorldObservation.BlockChange>(s.updates().size());
        for (var u : s.updates()) { var p = pos(u.position()); if (p == null) return null; changes.add(new WorldObservation.BlockChange(p, key(u.state()))); }
        return new WorldObservation.SectionBlocksChanged(s.sectionX(), s.sectionY(), s.sectionZ(), changes);
    }

    static BlockStateKey key(SemanticPayload.BlockState state) {
        var props = new TreeMap<String, String>();
        for (var p : state.properties()) props.put(p.name(), p.value());
        return new BlockStateKey(state.block(), props);
    }
    /** Null when the wire coordinate is outside the representable range (BlockPos never wraps). */
    static BlockPos pos(SemanticPayload.BlockPosition p) {
        try { return new BlockPos(p.x(), p.y(), p.z()); } catch (IllegalArgumentException outOfRange) { return null; }
    }
}
