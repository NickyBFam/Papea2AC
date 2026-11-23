package me.nicolas.papea2ac.testing;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import me.nicolas.papea2ac.combat.*;
import me.nicolas.papea2ac.core.CombatRunner;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.player.PlayerTimeline;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.protocol.PacketPayload.Position;
import me.nicolas.papea2ac.protocol.PacketPayload.Rotation;
import me.nicolas.papea2ac.replay.CapsuleCodec;
import me.nicolas.papea2ac.schema.TransportCapsule;
import me.nicolas.papea2ac.version.CombatProfile;
import me.nicolas.papea2ac.world.*;

/**
 * The Phase 11 attack corpus (brief section 54) and replay fixtures (section 55), driven end to end through the production core composition: Phase 1
 * {@link Observation}s (semantic payloads exactly as the normalizer emits them, bundles included) into {@link CombatRunner}, which maps them into the
 * world-owned {@link EntityHistory} and the {@link CombatEngine}. Every fixture carries an explicit expected DESCRIPTIVE result (target resolution,
 * exactness, target-specific incompleteness) written from the specification, never a verdict. The attacker timeline is empty and the timing ledger
 * absent in these traces, so every context also carries ATTACKER_HISTORY_UNAVAILABLE and TIMING_UNAVAILABLE; the summaries list the TARGET_ reasons,
 * the full {@link AttackContext#describe()} is kept in the JSON record.
 */
final class CombatCorpus {
    private CombatCorpus() {}
    static final CombatProfile P = CombatProfile.PAPER_26_2;
    static final int SELF = 1;
    static final long T0 = 1_000_000_000L, TICK = 50_000_000L;
    static final String RULESET = "0".repeat(64);
    static final SessionKey KEY_A = new SessionKey(new UUID(0xC0B7L, 1), 1), KEY_B = new SessionKey(new UUID(0xC0B7L, 1), 2);

    /** One session: a real {@link CombatRunner} over an empty player timeline, fed in strict sequence; every fed observation is logged for replay. */
    static final class Trace {
        final SessionKey key; final PlayerTimeline timeline; final CombatEngine.Metrics metrics = new CombatEngine.Metrics(); final CombatRunner runner;
        final List<Observation> log = new ArrayList<>(); long seq, nanos = T0, epoch, attempts, wire, serverTick;
        Trace(SessionKey key) { this.key = key; timeline = new PlayerTimeline(key); runner = new CombatRunner(key, P, world(key), timeline, () -> null, () -> null, metrics); }
        EntityHistory h() { return runner.entities(); }
        Observation feed(Observation.Event e, Observation.Direction d) {
            seq++; nanos += TICK;
            var o = new Observation(key, seq, nanos, 0, e, new Observation.Context(1, epoch, d, Observation.SourceKind.NETWORK, 0, 0, -1, Observation.ProtocolState.PLAY, -1, RULESET));
            log.add(o); runner.apply(o, nanos, SELF, epoch, null, false); return o;
        }
        long out(PacketPayload p) { feed(new Observation.Outbound(++wire, p), Observation.Direction.OUTBOUND); return seq; }
        long tick() { feed(new Observation.Tick(++serverTick), Observation.Direction.LOCAL); return seq; }
        void ticks(int n) { for (int i = 0; i < n; i++) tick(); }
        long gap() { return ++seq; } // a lost observation: the next one arrives after a sequence gap
        void epoch(long e) { epoch = e; tick(); }
        /** P11-F14: an ATTACK whose own observation carries a loss latch the world recorded as a break only (the entity content is not applied). */
        AttackContext degradedAttack(int id, String reason) { attempts++; seq++; nanos += TICK;
            var o = new Observation(key, seq, nanos, 0, new Observation.Attempt(attempts, attempts, new SemanticPayload.Attack(id)), new Observation.Context(1, epoch, Observation.Direction.INBOUND, Observation.SourceKind.NETWORK, 0, 0, -1, Observation.ProtocolState.PLAY, -1, RULESET));
            log.add(o); runner.apply(o, nanos, SELF, epoch, reason, true); return runner.lastAttack(); }
        AttackContext attack(int id) { attempts++; feed(new Observation.Attempt(attempts, attempts, new SemanticPayload.Attack(id)), Observation.Direction.INBOUND); return runner.lastAttack(); }
        void interact(int id, boolean sneaking) { attempts++; feed(new Observation.Attempt(attempts, attempts, new SemanticPayload.Interact(id, SemanticPayload.Hand.MAIN_HAND, new Position(0, 1, 0), sneaking)), Observation.Direction.INBOUND); }
        long spawn(int id, String type, double x, double y, double z) { return out(spawnPayload(id, type, x, y, z)); }
        long zombie(int id) { return spawn(id, "minecraft:zombie", 1.5, 64, 2.5); }
        long move(int id, int dx, int dy, int dz) { return out(new SemanticPayload.EntityDelta(id, (short) dx, (short) dy, (short) dz, new Rotation(0f, 0f), true, false, true)); }
        long moveRot(int id, int dx, int dy, int dz, float yaw, float pitch) { return out(new SemanticPayload.EntityDelta(id, (short) dx, (short) dy, (short) dz, new Rotation(yaw, pitch), true, true, true)); }
        long rot(int id, float yaw, float pitch) { return out(new SemanticPayload.EntityDelta(id, (short) 0, (short) 0, (short) 0, new Rotation(yaw, pitch), false, true, true)); }
        long teleport(int id, double x, double y, double z, Set<SemanticPayload.Relative> rel) { return out(new SemanticPayload.EntityPosition(id, new Position(x, y, z), new Position(0, 0, 0), new Rotation(0f, 0f), rel, false)); }
        long sync(int id, double x, double y, double z) { return out(new SemanticPayload.EntityPositionSync(id, new Position(x, y, z), new Position(0, 0, 0), new Rotation(0f, 0f), true)); }
        long remove(Integer... ids) { return out(new SemanticPayload.RemoveEntities(List.of(ids))); }
        long data(int id, SemanticPayload.EntityDataField... f) { return out(new SemanticPayload.EntitySyncedData(id, List.of(f))); }
        EntityLookup lookup(int id, long at) { return h().lookupId(key, h().worldEpoch(), id, at); }
        EntityLookup now(EntityKey k) { return h().lookupKey(k, seq + 1); }
    }
    /** The observer world the entity history belongs to (its private byte tier; production composition, PacketEngine). */
    static ObserverWorld world(SessionKey key) { return new ObserverWorld(key, new WorldBudget(), new BlockStateDictionary()); }
    static SemanticPayload.EntitySpawn spawnPayload(int id, String type, double x, double y, double z) {
        return new SemanticPayload.EntitySpawn(id, new UUID(0x5A, id), type, new Position(x, y, z), new Position(0, 0, 0), new Rotation(10f, 5f), 10f, 0);
    }

    // ---- descriptive summaries (no verdict vocabulary) ---------------------------------------------------------------------------------------
    static String target(EntityLookup l) {
        StringBuilder b = new StringBuilder(l.status().name());
        if (l.status() == EntityLookup.Status.FOUND) {
            var v = l.revision();
            b.append(" inc=").append(l.key().incarnation()).append(' ').append(l.type()).append(" rev@").append(v.sequence())
                .append(" pos=(").append(v.x()).append(',').append(v.y()).append(',').append(v.z()).append(") ").append(v.positionStatus())
                .append(" rot=(").append(v.yaw()).append(',').append(v.pitch()).append(") ").append(v.rotationStatus()).append(" head=").append(v.headYaw())
                .append(" pose=").append(v.pose() != null ? v.pose() : v.poseOpaque() ? "OPAQUE" : "UNKNOWN");
            if (v.vehicleId() >= 0) b.append(" vehicle=").append(v.vehicleId());
        }
        return b.toString();
    }
    static String reasons(AttackContext c) { return c.completeness().reasons().stream().map(Enum::name).filter(n -> n.startsWith("TARGET_")).toList().toString(); }
    static String attack(AttackContext c) { return target(c.target()) + " " + reasons(c); }

    record Fixture(String name, String covers, String expected, Function<Trace, String> run) {}
    record Result(String name, String covers, String expected, String observed, boolean matches, List<String> contexts, List<Observation> log, SessionKey key, String digest) {
        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>(); m.put("fixture", name); m.put("covers", covers); m.put("expected", expected); m.put("observed", observed); m.put("matches", matches);
            m.put("observations", (long) log.size()); m.put("contexts", contexts); return m;
        }
    }

    static final String Z = "minecraft:zombie";
    static final String ZOMBIE_AT_SPAWN = " minecraft:zombie rev@1 pos=(1.5,64.0,2.5) EXACT rot=(10.0,5.0) EXACT head=10.0 pose=STANDING";

    static List<Fixture> fixtures() {
        List<Fixture> f = new ArrayList<>();
        f.add(new Fixture("attack-known-target", "attack known target", "FOUND inc=1" + ZOMBIE_AT_SPAWN + " []", t -> { t.zombie(10); return attack(t.attack(10)); }));
        f.add(new Fixture("attack-unknown-target", "attack unknown target (id never sent)", "UNKNOWN [TARGET_UNKNOWN]", t -> { t.zombie(10); return attack(t.attack(99)); }));
        f.add(new Fixture("attack-before-spawn", "attack before spawn: unknown at the attack; a later lookup at the attack's position says PRE_SPAWN, never the later entity",
            "UNKNOWN [TARGET_UNKNOWN] ; later@attack=PRE_SPAWN", t -> { var c = t.attack(10); t.zombie(10); return attack(c) + " ; later@attack=" + target(t.lookup(10, c.observation().sequence())); }));
        f.add(new Fixture("attack-after-spawn-bundled", "attack after spawn (spawn delivered as the second child of a bundle)",
            "FOUND inc=2 minecraft:zombie rev@1 pos=(1.5,64.0,2.5) EXACT rot=(10.0,5.0) EXACT head=10.0 pose=STANDING []",
            t -> { t.out(new PacketPayload.Bundle(List.of(spawnPayload(11, "minecraft:cow", 8, 64, 8), spawnPayload(10, Z, 1.5, 64, 2.5)))); return attack(t.attack(10)); }));
        f.add(new Fixture("attack-immediately-before-relative-move", "an update after the attack is not in its context",
            "FOUND inc=1" + ZOMBIE_AT_SPAWN + " [] ; now x=2.5", t -> { t.zombie(10); var c = t.attack(10); t.move(10, 4096, 0, 0); return attack(c) + " ; now x=" + t.now(c.target().key()).revision().x(); }));
        f.add(new Fixture("attack-immediately-after-relative-move", "a delta decodes exactly as the client codec (from the base, 1/4096 grid)",
            "FOUND inc=1 minecraft:zombie rev@2 pos=(2.5,64.0,2.0) EXACT rot=(10.0,5.0) EXACT head=10.0 pose=STANDING []", t -> { t.zombie(10); t.move(10, 4096, 0, -2048); return attack(t.attack(10)); }));
        f.add(new Fixture("attack-before-teleport", "the teleport after the attack is not in its context",
            "FOUND inc=1" + ZOMBIE_AT_SPAWN + " []", t -> { t.zombie(10); var c = t.attack(10); t.teleport(10, 30, 70, -4, Set.of()); return attack(c); }));
        f.add(new Fixture("attack-after-teleport", "absolute teleport: exact target, rotation replaced",
            "FOUND inc=1 minecraft:zombie rev@2 pos=(30.25,70.0,-4.0) EXACT rot=(0.0,0.0) EXACT head=10.0 pose=STANDING []", t -> { t.zombie(10); t.teleport(10, 30.25, 70, -4, Set.of()); return attack(t.attack(10)); }));
        f.add(new Fixture("attack-after-relative-teleport", "relative teleport components resolve against the client's interpolated position: INTERPOLATION_DEPENDENT, never EXACT",
            "FOUND inc=1 minecraft:zombie rev@2 pos=(2.5,64.0,2.5) INTERPOLATION_DEPENDENT rot=(0.0,0.0) EXACT head=10.0 pose=STANDING [TARGET_POSITION_INTERPOLATION_DEPENDENT]",
            t -> { t.zombie(10); t.teleport(10, 1, 0, 0, Set.of(SemanticPayload.Relative.X, SemanticPayload.Relative.Y, SemanticPayload.Relative.Z)); return attack(t.attack(10)); }));
        f.add(new Fixture("move-after-teleport-decodes-from-codec-base", "a teleport does not reset the delta-codec base: the next delta decodes from the spawn base (client behaviour)",
            "FOUND inc=1 minecraft:zombie rev@3 pos=(2.5,64.0,2.5) EXACT rot=(0.0,0.0) EXACT head=10.0 pose=STANDING []",
            t -> { t.zombie(10); t.teleport(10, 30, 70, -4, Set.of()); t.move(10, 4096, 0, 0); return attack(t.attack(10)); }));
        f.add(new Fixture("position-sync-resets-codec-base", "a position sync sets both the target and the codec base: the delta after it decodes from the synced position",
            "FOUND inc=1 minecraft:zombie rev@5 pos=(6.0,64.0,5.0) EXACT rot=(0.0,0.0) EXACT head=10.0 pose=STANDING [] ; afterTeleportMove x=2.5",
            t -> { t.zombie(10); t.teleport(10, 30, 70, -4, Set.of()); long m = t.move(10, 4096, 0, 0); t.sync(10, 5, 64, 5); t.move(10, 4096, 0, 0);
                return attack(t.attack(10)) + " ; afterTeleportMove x=" + t.lookup(10, m + 1).revision().x(); }));
        f.add(new Fixture("attack-before-removal", "attack before removal: found; the history still answers at the attack's position after the removal",
            "FOUND inc=1" + ZOMBIE_AT_SPAWN + " [] ; later@attack=FOUND", t -> { t.zombie(10); var c = t.attack(10); t.remove(10); return attack(c) + " ; later@attack=" + t.lookup(10, c.observation().sequence()).status(); }));
        f.add(new Fixture("attack-after-removal", "attack after removal: DESPAWNED, never the last position", "DESPAWNED [TARGET_DESPAWNED]", t -> { t.zombie(10); t.remove(10); return attack(t.attack(10)); }));
        f.add(new Fixture("id-reuse", "remove + respawn of the same network id is a new incarnation; the old key never resolves to the new entity",
            "c1=FOUND inc=1 minecraft:zombie ; c2=FOUND inc=2 minecraft:skeleton ; oldKeyNow=ID_REUSED",
            t -> { t.zombie(10); var c1 = t.attack(10); t.remove(10); t.spawn(10, "minecraft:skeleton", 5, 64, 5); var c2 = t.attack(10);
                return "c1=" + c1.target().status() + " inc=" + c1.target().key().incarnation() + " " + c1.target().type() + " ; c2=" + c2.target().status() + " inc=" + c2.target().key().incarnation() + " " + c2.target().type()
                    + " ; oldKeyNow=" + t.now(c1.target().key()).status(); }));
        f.add(new Fixture("id-replaced-by-spawn", "a spawn on a live id replaces the entity (ClientLevel.addEntity): new incarnation, old key ID_REUSED",
            "FOUND inc=2 minecraft:skeleton rev@2 pos=(5.0,64.0,5.0) EXACT rot=(10.0,5.0) EXACT head=10.0 pose=STANDING [] ; oldKeyNow=ID_REUSED",
            t -> { t.zombie(10); var old = t.lookup(10, t.seq + 1).key(); t.spawn(10, "minecraft:skeleton", 5, 64, 5); return attack(t.attack(10)) + " ; oldKeyNow=" + t.now(old).status(); }));
        f.add(new Fixture("target-player", "player target", "FOUND inc=1 minecraft:player rev@1 pos=(0.5,65.0,0.5) EXACT rot=(10.0,5.0) EXACT head=10.0 pose=STANDING [TARGET_PLAYER_INFO_UNTRACKED]",
            t -> { t.spawn(20, "minecraft:player", 0.5, 65, 0.5); return attack(t.attack(20)); }));
        f.add(new Fixture("target-mob", "mob target", "FOUND inc=1 minecraft:skeleton rev@1 pos=(-3.0,70.0,12.75) EXACT rot=(10.0,5.0) EXACT head=10.0 pose=STANDING []",
            t -> { t.spawn(30, "minecraft:skeleton", -3, 70, 12.75); return attack(t.attack(30)); }));
        f.add(new Fixture("target-stationary", "stationary target: 20 silent ticks change nothing; the answer is the spawn revision",
            "FOUND inc=1" + ZOMBIE_AT_SPAWN + " []", t -> { t.zombie(10); t.ticks(20); return attack(t.attack(10)); }));
        f.add(new Fixture("target-moving", "moving target: five delta moves with rotation, decoded from the base each time",
            "FOUND inc=1 minecraft:zombie rev@6 pos=(2.720703125,64.0,3.1103515625) EXACT rot=(90.0,0.0) EXACT head=10.0 pose=STANDING []",
            t -> { t.zombie(10); for (int i = 0; i < 5; i++) t.moveRot(10, 1000, 0, 500, 90f, 0f); return attack(t.attack(10)); }));
        f.add(new Fixture("rotation-update", "rotation-only delta and head rotation update the target's rotation, not its position",
            "FOUND inc=1 minecraft:zombie rev@3 pos=(1.5,64.0,2.5) EXACT rot=(45.0,-30.0) EXACT head=60.0 pose=STANDING []",
            t -> { t.zombie(10); t.rot(10, 45f, -30f); t.out(new SemanticPayload.HeadRotation(10, 60f)); return attack(t.attack(10)); }));
        f.add(new Fixture("pose-update", "synced pose (entity data id 6, POSE) is kept by name",
            "FOUND inc=1 minecraft:zombie rev@2 pos=(1.5,64.0,2.5) EXACT rot=(10.0,5.0) EXACT head=10.0 pose=CROUCHING []",
            t -> { t.zombie(10); t.data(10, new SemanticPayload.EntityDataField(6, "POSE", "CROUCHING")); return attack(t.attack(10)); }));
        f.add(new Fixture("pose-opaque", "a pose recorded OPAQUE (pre-P11-F1 capture) is UNKNOWN, never STANDING",
            "FOUND inc=1 minecraft:zombie rev@2 pos=(1.5,64.0,2.5) EXACT rot=(10.0,5.0) EXACT head=10.0 pose=OPAQUE [TARGET_POSE_UNKNOWN]",
            t -> { t.zombie(10); t.data(10, new SemanticPayload.EntityDataField(6, "OPAQUE", "")); return attack(t.attack(10)); }));
        f.add(new Fixture("transport-gap", "a lost observation taints every live entity (INCOMPLETE_COVERAGE); an entity spawned after the gap is exact",
            "c10=INCOMPLETE_COVERAGE [TARGET_COVERAGE] ; c11=FOUND inc=2 minecraft:zombie rev@5 pos=(7.0,64.0,7.0) EXACT rot=(10.0,5.0) EXACT head=10.0 pose=STANDING []",
            t -> { t.zombie(10); t.gap(); t.tick(); var c10 = t.attack(10); t.spawn(11, Z, 7, 64, 7); return "c10=" + attack(c10) + " ; c11=" + attack(t.attack(11)); }));
        f.add(new Fixture("history-eviction", "bounded history: the 96-revision ring evicts the oldest (EVICTED, never a nearest match); a removed id stays DESPAWNED past the 3 s revision "
            + "horizon while its ended summary is retained, and is EVICTED (not UNKNOWN) once capacity churn forgets that summary",
            "attack=FOUND rev@121 x=1.529296875 ; history@3=EVICTED ; afterHorizon=DESPAWNED ; afterChurn=EVICTED",
            t -> { t.zombie(10); for (int i = 0; i < 120; i++) t.move(10, 1, 0, 0); var c = t.attack(10); var k = c.target().key();
                t.spawn(12, Z, 9, 64, 9); t.remove(12); t.ticks(80); var afterHorizon = t.attack(12).target().status();
                for (int id = 1000; id < 1000 + EntityHistory.MAX_RETAINED; id++) { t.spawn(id, Z, 0, 64, 0); t.remove(id); }
                return "attack=" + c.target().status() + " rev@" + c.target().revision().sequence() + " x=" + c.target().revision().x() + " ; history@3=" + t.h().lookupKey(k, 3).status()
                    + " ; afterHorizon=" + afterHorizon + " ; afterChurn=" + t.attack(12).target().status(); }));
        f.add(new Fixture("epoch-change", "a world/dimension change ends every incarnation: the id is unknown in the new epoch and the old key is WORLD_EPOCH_MISMATCH",
            "UNKNOWN [TARGET_UNKNOWN] ; oldKey=WORLD_EPOCH_MISMATCH",
            t -> { t.zombie(10); var c = t.attack(10); t.epoch(1); return attack(t.attack(10)) + " ; oldKey=" + t.now(c.target().key()).status(); }));
        f.add(new Fixture("session-replacement", "a reconnect is a new SessionKey: nothing crosses; the old key is SESSION_MISMATCH; attack ids differ",
            "UNKNOWN [TARGET_UNKNOWN] ; oldKey=SESSION_MISMATCH ; ids A1:2 A2:1", t -> { t.zombie(10); var ca = t.attack(10);
                Trace b = new Trace(KEY_B); var cb = b.attack(10);
                return attack(cb) + " ; oldKey=" + b.h().lookupKey(ca.target().key(), b.seq + 1).status() + " ; ids " + ca.id() + " " + cb.id(); }));
        f.add(new Fixture("capacity-refusal", "the 257th live entity is refused: its id is INCOMPLETE_COVERAGE, never UNKNOWN; admitted ones stay FOUND",
            "c300=INCOMPLETE_COVERAGE [TARGET_COVERAGE] ; c2=FOUND", t -> { for (int id = 2; id < 2 + EntityHistory.MAX_RETAINED; id++) t.spawn(id, Z, id, 64, 0); t.spawn(300, Z, 0, 64, 0);
                return "c300=" + attack(t.attack(300)) + " ; c2=" + t.attack(2).target().status(); }));
        f.add(new Fixture("unsupported-motion", "a minecart lerp step (not reconstructed) makes the incarnation UNSUPPORTED",
            "UNSUPPORTED [TARGET_UNSUPPORTED_MOTION]", t -> { t.spawn(40, "minecraft:minecart", 0, 64, 0);
                t.out(new SemanticPayload.MinecartMove(40, List.of(new SemanticPayload.MinecartStepFact(1, 64, 0, 0f, 0f)))); return attack(t.attack(40)); }));
        f.add(new Fixture("legacy-capture-position", "a pre-schema-3 capture's tag-16 position (sync OR absolute teleport, P11-F9): whether the codec base moved is unknowable, so the incarnation is UNSUPPORTED, never guessed EXACT",
            "UNSUPPORTED [TARGET_UNSUPPORTED_MOTION]", t -> { t.zombie(10); t.out(new SemanticPayload.LegacyEntityPosition(10, new Position(4, 64, 4), new Position(0, 0, 0), new Rotation(0f, 0f), true));
                t.move(10, 4096, 0, 0); return attack(t.attack(10)); }));
        f.add(new Fixture("mounted-target", "a passenger target is reported with its vehicle and marked incomplete (vehicle geometry is capability-gated)",
            "FOUND inc=1 minecraft:zombie rev@3 pos=(1.5,64.0,2.5) VEHICLE_DEPENDENT rot=(10.0,5.0) EXACT head=10.0 pose=STANDING vehicle=41 [TARGET_MOUNTED, TARGET_VEHICLE_DEPENDENT]",
            t -> { t.zombie(10); t.spawn(41, "minecraft:pig", 1.5, 64, 2.5); t.out(new PacketPayload.Passengers(41, List.of(10))); return attack(t.attack(10)); }));
        f.add(new Fixture("dismount-before-resync", "P11-F12: a dismounted rider stays where the client rode it, so its position is VEHICLE_DEPENDENT until the next position sync; the sync restores exactness",
            "dismounted=FOUND inc=1 minecraft:zombie rev@5 pos=(1.5,64.0,2.5) VEHICLE_DEPENDENT rot=(10.0,5.0) EXACT head=10.0 pose=STANDING [TARGET_VEHICLE_DEPENDENT] ; synced=FOUND inc=1 minecraft:zombie rev@7 pos=(81.5,64.0,2.5) EXACT rot=(0.0,0.0) EXACT head=10.0 pose=STANDING []", t -> { t.zombie(10); t.spawn(20, "minecraft:horse", 1.5, 64, 2.5); t.out(new PacketPayload.Passengers(20, List.of(10))); t.move(20, 80 * 4096, 0, 0);
                t.out(new PacketPayload.Passengers(20, List.of())); var before = t.attack(10); t.sync(10, 81.5, 64, 2.5); return "dismounted=" + attack(before) + " ; synced=" + attack(t.attack(10)); }));
        f.add(new Fixture("vehicle-removed", "P11-F12: removing the vehicle dismounts its rider as the client does: no vehicle id survives into a reuse of the vehicle's id",
            "FOUND inc=1 minecraft:zombie rev@4 pos=(1.5,64.0,2.5) VEHICLE_DEPENDENT rot=(10.0,5.0) EXACT head=10.0 pose=STANDING [TARGET_VEHICLE_DEPENDENT]", t -> { t.zombie(10); t.spawn(20, "minecraft:horse", 1.5, 64, 2.5); t.out(new PacketPayload.Passengers(20, List.of(10))); t.remove(20); t.spawn(20, "minecraft:pig", 9, 64, 9);
                return attack(t.attack(10)); }));
        f.add(new Fixture("rides-observer", "P11-F13: an entity riding the observer is positioned by the observer's client: VEHICLE_DEPENDENT and mounted on the observer",
            "FOUND inc=1 minecraft:zombie rev@2 pos=(1.5,64.0,2.5) VEHICLE_DEPENDENT rot=(10.0,5.0) EXACT head=10.0 pose=STANDING vehicle=1 [TARGET_MOUNTED, TARGET_VEHICLE_DEPENDENT]", t -> { t.zombie(10); t.out(new PacketPayload.Passengers(SELF, List.of(10))); return attack(t.attack(10)); }));
        f.add(new Fixture("observer-vehicle", "P11-F13: the vehicle the observer rides is client-authoritative: its network moves are not its position; after the observer leaves it stays VEHICLE_DEPENDENT until a sync",
            "aboard=FOUND inc=1 minecraft:horse rev@3 pos=(2.5,64.0,2.5) VEHICLE_DEPENDENT rot=(10.0,5.0) VEHICLE_DEPENDENT head=10.0 pose=STANDING [TARGET_VEHICLE_DEPENDENT] ; left=FOUND inc=1 minecraft:horse rev@5 pos=(2.5,64.0,2.5) VEHICLE_DEPENDENT rot=(10.0,5.0) VEHICLE_DEPENDENT head=10.0 pose=STANDING [TARGET_VEHICLE_DEPENDENT] ; synced=FOUND inc=1 minecraft:horse rev@7 pos=(3.5,64.0,2.5) EXACT rot=(0.0,0.0) EXACT head=10.0 pose=STANDING []", t -> { t.spawn(20, "minecraft:horse", 1.5, 64, 2.5); t.out(new PacketPayload.Passengers(20, List.of(SELF))); t.move(20, 4096, 0, 0); var aboard = t.attack(20);
                t.out(new PacketPayload.Passengers(20, List.of())); var left = t.attack(20); t.sync(20, 3.5, 64, 2.5);
                return "aboard=" + attack(aboard) + " ; left=" + attack(left) + " ; synced=" + attack(t.attack(20)); }));
        f.add(new Fixture("degraded-attack", "P11-F14: an ATTACK on an observation whose loss latch broke coverage is counted and resolves INCOMPLETE_COVERAGE, never dropped",
            "INCOMPLETE_COVERAGE [TARGET_COVERAGE] ; attacks=1", t -> { t.zombie(10); var c = t.degradedAttack(10, "INGRESS_DROPPED"); return attack(c) + " ; attacks=" + t.runner.engine().status().attacks(); }));
        f.add(new Fixture("spear-stab-is-not-a-targeted-attack", "P11-F19: the spear's STAB player action is counted as a target-less piercing attack, never as an attack context or a target resolution",
            "attacks=0 stabs=1 contexts=0", t -> { t.zombie(10); t.attempts++; t.feed(new Observation.Attempt(t.attempts, t.attempts, new SemanticPayload.Dig("STAB", new SemanticPayload.BlockPosition(0, 0, 0), SemanticPayload.Face.DOWN, 0)), Observation.Direction.INBOUND);
                var st = t.runner.engine().status(); return "attacks=" + st.attacks() + " stabs=" + st.piercingAttacks() + " contexts=" + t.runner.engine().attacks().size(); }));
        f.add(new Fixture("death-event-before-pose-sync", "P11-F22: entity event 3 runs die() on the client (DYING) before the server's pose data: the pose is UNKNOWN until that data, never the old STANDING",
            "event=FOUND inc=1 minecraft:zombie rev@2 pos=(1.5,64.0,2.5) EXACT rot=(10.0,5.0) EXACT head=10.0 pose=UNKNOWN [TARGET_POSE_UNKNOWN] ; data=FOUND inc=1 minecraft:zombie rev@4 pos=(1.5,64.0,2.5) EXACT rot=(10.0,5.0) EXACT head=10.0 pose=DYING []", t -> { t.zombie(10); t.out(new SemanticPayload.EntitySimpleEvent(10, (byte) 3)); var dying = t.attack(10);
                t.data(10, new SemanticPayload.EntityDataField(6, "POSE", "DYING")); return "event=" + attack(dying) + " ; data=" + attack(t.attack(10)); }));
        f.add(new Fixture("out-of-range-pitch", "P11-F23: the client clamps every pitch it applies (Entity.setXRot: clamp(p % 360, -90, 90)); the model records the clamped value",
            "FOUND inc=1 minecraft:zombie rev@2 pos=(1.5,64.0,2.5) EXACT rot=(30.0,90.0) EXACT head=10.0 pose=STANDING []", t -> { t.zombie(10); t.rot(10, 30f, 120f); return attack(t.attack(10)); }));
        f.add(new Fixture("interaction-is-not-an-attack", "INTERACT is recorded as a non-attack interaction with its target, never as an attack",
            "attacks=0 interactions=1 target=FOUND", t -> { t.zombie(10); t.interact(10, true); var st = t.runner.engine().status();
                return "attacks=" + st.attacks() + " interactions=" + st.interactions() + " target=" + st.recentInteractions().getFirst().target().status(); }));
        f.add(new Fixture("self-is-not-a-target", "the observer's own entity is the attacker (timeline-owned), never an entity target",
            "UNKNOWN [TARGET_UNKNOWN]", t -> { t.spawn(SELF, "minecraft:player", 0, 64, 0); return attack(t.attack(SELF)); }));
        return f;
    }

    static List<Result> run() {
        List<Result> out = new ArrayList<>();
        for (Fixture fx : fixtures()) {
            Trace t = new Trace(KEY_A); String observed;
            try { observed = fx.run().apply(t); } catch (RuntimeException e) { observed = "EXCEPTION " + e; }
            List<String> contexts = new ArrayList<>(); for (var c : t.runner.engine().attacks()) contexts.add(c.describe());
            String digest = EntityReplayMapper.digest(EntityReplayMapper.export(t.h())) + ":" + CombatReplayMapper.digest(CombatReplayMapper.export(t.runner.engine(), P.id()));
            out.add(new Result(fx.name(), fx.covers(), fx.expected(), observed, fx.expected().equals(observed), contexts, List.copyOf(t.log), t.key, digest));
        }
        return out;
    }

    // ---- replay (brief section 55) -------------------------------------------------------------------------------------------------------------
    /** The pinned replay traces, each the log of one corpus fixture (or a pair, for the teleport trace). */
    static final Map<String, List<String>> REPLAY_TRACES = new LinkedHashMap<>();
    static {
        REPLAY_TRACES.put("stationary-target", List.of("target-stationary"));
        REPLAY_TRACES.put("moving-target", List.of("target-moving"));
        REPLAY_TRACES.put("teleporting-target", List.of("position-sync-resets-codec-base"));
        REPLAY_TRACES.put("despawn-reuse", List.of("id-reuse"));
        REPLAY_TRACES.put("target-player", List.of("target-player"));
        REPLAY_TRACES.put("target-mob", List.of("target-mob"));
        REPLAY_TRACES.put("gap-incomplete", List.of("transport-gap"));
        REPLAY_TRACES.put("epoch-transition", List.of("epoch-change"));
    }
    /** Feeds a recorded log into a FRESH runner (world epoch from each observation's context) and projects both replay mappers. */
    static String replay(SessionKey key, List<Observation> log) {
        var timeline = new PlayerTimeline(key); var runner = new CombatRunner(key, P, world(key), timeline, () -> null, () -> null, new CombatEngine.Metrics());
        for (Observation o : log) runner.apply(o, o.capturedNanos(), SELF, o.context().worldEpoch(), null, false);
        return EntityReplayMapper.digest(EntityReplayMapper.export(runner.entities())) + ":" + CombatReplayMapper.digest(CombatReplayMapper.export(runner.engine(), P.id()));
    }
    /**
     * The same log through transport capsules: export each observation, encode at the current container schema, decode, restore. A capsule never
     * spans a sequence gap (TransportCapsule requires a gap-free prefix), so, as the recorder does, a loss closes the segment with a Barrier naming the
     * first missing sequence and the next segment opens with a Checkpoint at its first sequence; the replay then sees the same gap.
     */
    static List<Observation> throughCapsule(SessionKey key, List<Observation> log) throws IOException {
        List<List<Observation>> segments = new ArrayList<>(); List<Observation> current = new ArrayList<>(); long last = 0;
        for (Observation o : log) { if (last > 0 && o.sequence() != last + 1) { segments.add(current); current = new ArrayList<>(); } current.add(o); last = o.sequence(); }
        segments.add(current);
        List<Observation> back = new ArrayList<>();
        for (int s = 0; s < segments.size(); s++) {
            List<Observation> seg = segments.get(s); List<TransportCapsule.Frame> frames = new ArrayList<>(); long origin = 0;
            if (s > 0) frames.add(new TransportCapsule.Checkpoint(seg.getFirst().capturedNanos(), seg.getFirst().sequence(), 0, 1, "PLAY", true, "OBSERVE_ONLY", 0, 0, 0, 0));
            for (Observation o : seg) frames.add(TransportReplayMapper.export(o, origin, o.capturedNanos()));
            if (s + 1 < segments.size()) frames.add(new TransportCapsule.Barrier(seg.getLast().capturedNanos(), "LOST_SERVER_OBSERVATION", seg.getLast().sequence() + 1));
            var capsule = new TransportCapsule(TransportCapsule.CURRENT_SCHEMA, "combat-corpus", RULESET, "phase11", key.epoch(), false, frames, true);
            byte[] encoded = CapsuleCodec.encode(capsule); var decoded = CapsuleCodec.decode(encoded);
            // Payload wraps a byte[] (record equality is identity there): the round trip is judged on the canonical encoding
            if (decoded.schema() != TransportCapsule.CURRENT_SCHEMA || !Arrays.equals(encoded, CapsuleCodec.encode(decoded))) throw new IOException("capsule round trip changed the capsule");
            for (var fr : decoded.frames()) if (fr instanceof TransportCapsule.Input in) back.add(TransportReplayMapper.restore(in, key));
        }
        return back;
    }
}
