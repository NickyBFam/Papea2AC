package me.nicolas.papea2ac.testing;

import static me.nicolas.papea2ac.testing.TimingMemoryMain.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import me.nicolas.papea2ac.combat.*;
import me.nicolas.papea2ac.core.CombatRunner;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.player.PlayerTimeline;
import me.nicolas.papea2ac.player.TimelineLookup;
import me.nicolas.papea2ac.version.CombatProfile;
import me.nicolas.papea2ac.world.*;

/**
 * Empirical memory harness for Phase 11 (brief section 56). Same methodology as the Phase 2-10 harnesses: forced GC, used-heap diff, 3 trials,
 * median; estimates on one host, never a supported-capacity claim. Thread-allocation counters give the per-operation garbage. Alongside the heap
 * figures, the entity history's CHARGED bytes (what it counts against the observer world's private tier) are reported, so the accounting can be
 * compared with reality.
 *
 * <p>Per session: the CombatRunner composition (entity history + combat engine + diagnostics) excluding the observer world and player timeline it
 * shares with Phases 3/2. Global/shared: the engine-wide fixed-cardinality metrics and the immutable version profile.
 */
public final class CombatMemoryMain {
    private CombatMemoryMain() {}
    static final CombatProfile P = CombatProfile.PAPER_26_2;
    static final long TICK = 50_000_000L, MS = 1_000_000L;
    static final SessionKey KEY = new SessionKey(new UUID(0xC0B7L, 0x11L), 1);

    /** A standalone history with its own cursor (sequence and nanos advance independently so rings can fill inside the 3 s horizon). */
    static final class H {
        final EntityHistory h = new EntityHistory(KEY, P, null, 2 << 20); final CombatEngine e = new CombatEngine(KEY, P, new CombatEngine.Metrics()); long seq, nanos = 1_000_000_000L;
        void apply(EntityObservation o, long dt) { seq++; nanos += dt; h.apply(KEY, o, seq, 0, nanos); }
        void spawn(int id, String type, double x) { apply(new EntityObservation.Spawned(id, new UUID(id, id), type, x, 64, 0, 0f, 0f, 0f), MS); }
        void move(int id, long dt) { apply(new EntityObservation.Moved(id, (short) 37, (short) 0, (short) -11, true, 12f, 3f, true), dt); }
        AttackContext attack(int id) { seq++; nanos += MS; h.observeSequence(KEY, seq, nanos); return e.onAttack(new CombatObservation.Attack(seq, seq, seq, id, nanos), h, new TimelineLookup.NeverExisted(seq), null, null); }
        void interact(int id) { seq++; nanos += MS; h.observeSequence(KEY, seq, nanos); e.onInteraction(new CombatObservation.Interaction(seq, seq, seq, id, nanos, "MAIN_HAND", 0, 1, 0, false), h); }
    }
    /** 40 mobs, each moved every tick for 3 s (a busy survival neighbourhood). */
    static H typicalRegistry() { H x = new H(); for (int i = 0; i < 40; i++) x.spawn(100 + i, "minecraft:zombie", i); for (int t = 0; t < 60; t++) for (int i = 0; i < 40; i++) x.move(100 + i, i == 0 ? TICK : 0); return x; }
    /** 10 players moving every tick for 3 s plus 20 mobs, 64 attacks and 64 interactions (a busy PvP fight). */
    static H typicalPvp() {
        H x = new H(); for (int i = 0; i < 10; i++) x.spawn(1000 + i, "minecraft:player", i); for (int i = 0; i < 20; i++) x.spawn(100 + i, "minecraft:zombie", i);
        for (int t = 0; t < 60; t++) { for (int i = 0; i < 10; i++) x.move(1000 + i, i == 0 ? TICK : 0); x.attack(1000 + t % 10); x.interact(100 + t % 20); }
        for (int t = 0; t < 8; t++) { x.attack(1000 + t); x.interact(100 + t); }
        return x;
    }
    /** 256 live entities (every summary slot) and 32 deep histories each holding a full 96-revision ring, all inside the horizon. */
    static H fullBounded() {
        H x = new H(); for (int i = 0; i < EntityHistory.MAX_RETAINED; i++) x.spawn(10 + i, "minecraft:zombie", i);
        for (int r = 0; r < EntityHistory.MAX_REVISIONS; r++) for (int i = 0; i < EntityHistory.MAX_DEEP; i++) x.move(10 + i, MS / 8);
        return x;
    }
    static H fullAttackHistory() { H x = new H(); x.spawn(10, "minecraft:zombie", 0); for (int i = 0; i < CombatEngine.MAX_ATTACKS + 10; i++) { x.attack(10); x.interact(10); } return x; }
    static CombatRunner runner(ObserverWorld w, PlayerTimeline t) { return new CombatRunner(KEY, P, w, t, () -> null, () -> null, new CombatEngine.Metrics()); }
    static ObserverWorld world() { return new ObserverWorld(KEY, new WorldBudget(), new BlockStateDictionary()); }

    public static void main(String[] args) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>(); out.put("phase", "11 - Combat Engine (descriptive infrastructure)");
        out.put("method", "forced GC + used-heap diff, 3 trials, median; thread-allocation counters for per-operation garbage; estimates on one host, not capacity");
        double worldAndTimeline = measure(() -> new Object[]{world(), new PlayerTimeline(KEY)});
        double runnerComposed = measure(() -> { var w = world(); var t = new PlayerTimeline(KEY); return new Object[]{w, t, runner(w, t)}; });
        Map<String, Object> per = new LinkedHashMap<>();
        per.put("emptyCombatSessionBytes(CombatRunner: empty history + engine + diagnostics; excludes world/timeline)", Math.round(runnerComposed - worldAndTimeline));
        per.put("typicalEntityRegistryBytes(40 mobs, 3 s of per-tick moves)", Math.round(measure(() -> typicalRegistry().h)));
        per.put("typicalPvpSessionBytes(10 players + 20 mobs moving, 64 attacks + 64 interactions)", Math.round(measure(CombatMemoryMain::typicalPvp)));
        per.put("fullBoundedRegistryBytes(256 live summaries, 32 deep x 96 revisions)", Math.round(measure(() -> fullBounded().h)));
        per.put("fullBoundedRegistryChargedBytes", fullBounded().h.bytes());
        per.put("fullBoundedCeilingChargedBytes(256 x 256 + 32 x 96 x 160)", (long) EntityHistory.MAX_RETAINED * EntityHistory.SUMMARY_BYTES + (long) EntityHistory.MAX_DEEP * EntityHistory.MAX_REVISIONS * EntityRevision.CHARGED_BYTES);
        double emptyEngine = measure(() -> new CombatEngine(KEY, P, new CombatEngine.Metrics()));
        per.put("attackHistoryStorageBytes(64 attack contexts + 64 interactions, full rings, beyond an empty engine)", Math.round(measure(() -> fullAttackHistory().e) - emptyEngine));
        out.put("perSession", per);
        H full = fullBounded(); H pvp = typicalPvp();
        Map<String, Object> replay = new LinkedHashMap<>();
        replay.put("entityCapsuleHeapBytes(full bounded)", Math.round(measure(() -> EntityReplayMapper.export(full.h))));
        replay.put("entityCapsuleEncodedBytes(full bounded)", (long) EntityReplayMapper.encode(EntityReplayMapper.export(full.h)).length);
        replay.put("combatCapsuleHeapBytes(typical PvP)", Math.round(measure(() -> CombatReplayMapper.export(pvp.e, P.id()))));
        replay.put("note", "projections exist only while a replay/export is being produced; nothing is retained per session for replay");
        out.put("replayProjection", replay);
        Map<String, Object> global = new LinkedHashMap<>();
        global.put("engineMetricsBytes(one per PacketEngine, fixed cardinality)", Math.round(measure(CombatEngine.Metrics::new)));
        global.put("combatProfile", "one immutable static record (18 pose names), shared");
        out.put("globalShared", global);
        Map<String, Object> alloc = new LinkedHashMap<>();
        H a = typicalRegistry(); int[] target = {100};
        alloc.put("attackIngestKnownTargetBytes", alloc(() -> a.attack(target[0] = 100 + (target[0] + 1) % 40)));
        H b = typicalRegistry(); alloc.put("attackIngestUnknownTargetBytes", alloc(() -> b.attack(999)));
        H c = typicalRegistry(); int[] mover = {0}; alloc.put("relativeMoveUpdateBytes(deep)", alloc(() -> c.move(100 + (mover[0]++ % 32), MS)));
        H d = typicalRegistry(); alloc.put("currentLookupBytes", alloc(() -> d.h.lookupId(KEY, 0, 105, d.seq + 1)));
        H e = typicalRegistry(); alloc.put("historicalLookupBytes", alloc(() -> e.h.lookupId(KEY, e.h.worldEpoch(), 105, e.seq - 400)));
        out.put("perOperationAllocationBytes", alloc);
        out.put("bounds", "entity summaries <= " + EntityHistory.MAX_RETAINED + " (" + EntityHistory.SUMMARY_BYTES + " B charged), deep histories <= " + EntityHistory.MAX_DEEP + " x " + EntityHistory.MAX_REVISIONS
            + " revisions (" + EntityRevision.CHARGED_BYTES + " B charged) within a 3 s horizon, attack contexts <= " + CombatEngine.MAX_ATTACKS + ", interactions <= " + CombatEngine.MAX_INTERACTIONS
            + "; entity bytes share the observer world's private tier (world history shed first, then entity deep revisions, then chunks); no unbounded per-entity history");
        Path p = Path.of(args[0]); Files.createDirectories(p.getParent()); Files.writeString(p, MiniJson.write(out) + "\n", StandardCharsets.UTF_8);
        System.out.println(MiniJson.write(out));
    }
}
