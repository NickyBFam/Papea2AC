package me.nicolas.papea2AC.platform;
import me.nicolas.papea2ac.core.MitigationPreconditions;
import me.nicolas.papea2ac.core.PacketEngine;
import me.nicolas.papea2ac.mitigation.DestinationRegionCapture;
import me.nicolas.papea2ac.mitigation.MitigationBounds;
import me.nicolas.papea2ac.mitigation.MitigationIntent;
import me.nicolas.papea2ac.mitigation.MitigationVocabulary.Reason;
import me.nicolas.papea2ac.model.DestinationRegion;
import me.nicolas.papea2ac.model.HealthLatch;
import me.nicolas.papea2ac.model.SafeAnchorValidationPort;
import me.nicolas.papea2ac.model.Vector3;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
/**
 * The one platform integration that applies a mitigation intent (MITIGATION.md G1: "only the Paper main-thread executor applies game changes").
 * Runs on the Paper main thread only. For each queued intent it re-checks every precondition in a fixed order - session identity, online,
 * deadline, world identity, death/respawn, vehicle/gliding/flight, game mode, unresolved corrections, distance, loaded destination - then the
 * G2 global admission, the G1 health-latch linearization, the destination region captured from ALREADY-LOADED chunks (never a synchronous
 * load) and validated by the shared-geometry {@link SafeAnchorValidationPort}; only then it records STARTED, re-checks the latch
 * immediately before the call and teleports with absolute coordinates, the player's current rotation and zero delta (see execute()).
 * Every intent ends with exactly one ordered record back to its session: STARTED + SENT, or REJECTED with a typed reason. Nothing here
 * kicks, bans, freezes, changes inventory/health/effects/game mode or applies velocity.
 */
public final class PaperMitigationExecutor {
    /** Intents taken from the queue per tick (bounded main-thread work). */
    public static final int PER_TICK = 8;
    private long alertedDisabled;
    public final LongAdder polled = new LongAdder(), rejected = new LongAdder(), started = new LongAdder(), sent = new LongAdder(), cancelled = new LongAdder(), globalSuspends = new LongAdder();
    private final PacketEngine engine; private final MitigationBounds bounds; private final Function<BlockData, Integer> stateIds;
    private volatile String last = "none";

    public PaperMitigationExecutor(PacketEngine engine, MitigationBounds bounds) {
        this.engine = engine; this.bounds = bounds;
        this.stateIds = data -> {
            try { Object nms = data.getClass().getMethod("getState").invoke(data); return engine.internBlockState(PaperSemanticNormalizer.blockState(nms)); }
            catch (ReflectiveOperationException | RuntimeException e) { return -1; }
        };
    }
    public String last() { return last; }

    /** Main thread, once per tick. */
    public void tick(org.bukkit.Server server) {
        // G2: a tripped proposal guard (counted before capture on the shards) restricts the arbiter before any queued intent can start
        if (engine.applyPendingRestriction()) { globalSuspends.increment(); last = "GlobalAnomalySuspend " + engine.proposalGuardStatus(); server.getLogger().warning("[Papea2AC] " + last + " -> effects OBSERVE_ONLY"); }
        // MITIGATION "three failed corrections within 30 seconds disable further setbacks for that session and raise an operational alert" (informational, rate-limited by construction: once per disabled session)
        long disabled = engine.mitigationMetrics.sessionsDisabled.sum(); if (disabled > alertedDisabled) { server.getLogger().warning("[Papea2AC] mitigation loop breaker: setbacks disabled for " + (disabled - alertedDisabled) + " session(s) (3 failed corrections within 30 s); no further action is taken"); alertedDisabled = disabled; }
        for (int i = 0; i < PER_TICK; i++) {
            MitigationIntent intent = engine.pollMitigationIntent(); if (intent == null) return;
            polled.increment();
            try { execute(server, intent); }
            catch (RuntimeException e) { reject(intent, Reason.INTERNAL_ERROR); }
        }
    }

    private void execute(org.bukkit.Server server, MitigationIntent it) {
        java.util.UUID id = it.session().player();
        Player p = server.getPlayer(id);
        boolean online = p != null && p.isOnline();
        Vector3 t = it.anchor().position();
        SafeAnchorValidationPort.Bounds box = standing(t);
        DestinationRegionCapture.CellRange r = DestinationRegionCapture.range(box);
        String worldKey = "offline"; boolean dead = false, vehicle = false, gameMode = false, loaded = false; double distance = Double.POSITIVE_INFINITY;
        SafeAnchorValidationPort.ClearanceResult clear = SafeAnchorValidationPort.ClearanceResult.UNKNOWN; World w = null; Location cur = null;
        if (online) {
            w = p.getWorld(); cur = p.getLocation(); worldKey = w.getKey().asString(); dead = p.isDead();
            vehicle = p.isInsideVehicle() || p.isGliding() || p.isFlying() || p.isRiptiding() || p.isSleeping();
            gameMode = p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
            double dx = cur.getX() - t.x(), dy = cur.getY() - t.y(), dz = cur.getZ() - t.z(); distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            loaded = r.cells() <= DestinationRegionCapture.MAX_CELLS && worldKey.equals(it.worldKey());
            for (int cx = r.x0() >> 4; loaded && cx <= r.x1() >> 4; cx++) for (int cz = r.z0() >> 4; loaded && cz <= r.z1() >> 4; cz++) loaded = w.isChunkLoaded(cx, cz); // never a synchronous load
            if (loaded) clear = engine.safeAnchorValidator.validate(capture(w, it, r), box);
        }
        var facts = new MitigationPreconditions.PlatformFacts(engine.currentSession(it.session()), online, System.nanoTime(), worldKey, dead, vehicle, gameMode, engine.unresolvedTeleports(id), distance, loaded, clear);
        var refusal = MitigationPreconditions.check(it, facts, bounds);
        if (refusal.isPresent()) { reject(it, refusal.get()); return; }
        // G2 global admission (mode, window and burst guards), then the G1 transport-health linearization point
        if (!engine.admitEffect(id, System.nanoTime())) { reject(it, Reason.GLOBAL_GUARD); return; }
        HealthLatch latch = engine.healthLatch(id);
        if (latch == null || !latch.authorize(it.transportGeneration())) { reject(it, Reason.TRANSPORT_UNHEALTHY); return; }
        engine.reportMitigation(id, it.shortId() + ":S"); started.increment();
        if (!latch.authorize(it.transportGeneration())) { reject(it, Reason.TRANSPORT_UNHEALTHY); return; } // "rechecks immediately before the platform call"
        // pinned 26.2 Paper: TeleportFlag.Relative YAW/PITCH are unsupported (CraftEntity.deltaRelativeToNMS maps only the velocity flags), so the
        // rotation is the player's CURRENT server-side yaw/pitch (preserved up to at most one tick of not-yet-sent mouse movement) and the delta is
        // Vec3.ZERO (no velocity flag): the grounded anchor's velocity context, never an applied velocity effect.
        Location dest = new Location(w, t.x(), t.y(), t.z(), cur.getYaw(), cur.getPitch());
        boolean ok = p.teleport(dest, PlayerTeleportEvent.TeleportCause.PLUGIN);
        if (!ok) { cancelled.increment(); reject(it, Reason.PLATFORM_CANCELLED); return; }
        sent.increment(); last = "SENT " + it.shortId() + String.format(java.util.Locale.ROOT, " -> (%.3f,%.3f,%.3f)", t.x(), t.y(), t.z());
        engine.reportMitigation(id, it.shortId() + ":X");
    }
    /** The bounded destination region from ALREADY-LOADED chunks (the caller proved every chunk loaded); cells outside the dimension are exactly empty. */
    private DestinationRegion capture(World w, MitigationIntent it, DestinationRegionCapture.CellRange r) {
        List<DestinationRegion.Cell> cells = new ArrayList<>((int) r.cells()); boolean complete = true; int air = engine.internBlockState(AIR);
        for (int y = r.y0(); y <= r.y1() && complete; y++) for (int z = r.z0(); z <= r.z1() && complete; z++) for (int x = r.x0(); x <= r.x1() && complete; x++) {
            if (y < w.getMinHeight() || y >= w.getMaxHeight()) { cells.add(new DestinationRegion.Cell(x, y, z, air)); continue; }
            int sid = stateIds.apply(w.getBlockAt(x, y, z).getBlockData());
            if (sid < 0) complete = false; else cells.add(new DestinationRegion.Cell(x, y, z, sid));
        }
        return new DestinationRegion(it.worldKey(), it.worldEpoch(), it.decisionWorldRevision(), complete, cells);
    }
    /** Plugin disable: every still-queued intent is rejected (SHUTDOWN) before the hooks are removed; nothing queued can execute later. */
    public void shutdown() { MitigationIntent it; while ((it = engine.pollMitigationIntent()) != null) { polled.increment(); reject(it, Reason.SHUTDOWN); } }
    /** The pinned standing on-foot box, from core (identical to the geometry the shard's anchor admission certified). */
    private SafeAnchorValidationPort.Bounds standing(Vector3 t) { return engine.standingBounds(t); }
    private void reject(MitigationIntent it, Reason r) {
        rejected.increment(); last = "REJECTED " + it.shortId() + " " + r;
        engine.reportMitigation(it.session().player(), it.shortId() + ":R:" + r.ordinal());
    }
    private static final me.nicolas.papea2ac.protocol.SemanticPayload.BlockState AIR = new me.nicolas.papea2ac.protocol.SemanticPayload.BlockState("minecraft:air", List.of());
}
