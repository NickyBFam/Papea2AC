package me.nicolas.papea2ac.testing;

import static me.nicolas.papea2ac.testing.TimingMemoryMain.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import me.nicolas.papea2ac.combat.CombatEngine;
import me.nicolas.papea2ac.core.CombatRunner;
import me.nicolas.papea2ac.core.ReachRunner;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.player.PlayerTimeline;
import me.nicolas.papea2ac.player.TimingLedger;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.version.CombatProfile;
import me.nicolas.papea2ac.version.EntityGeometryTable;
import me.nicolas.papea2ac.version.MovementProfile;
import me.nicolas.papea2ac.world.*;

/**
 * Empirical memory harness for Phase 12. Same methodology as the Phase 2–11 harnesses: forced GC, used-heap diff, 3 trials, median; estimates on
 * one host, never a supported-capacity claim. Per session: the {@link ReachRunner} alone (the combat runner, world, timeline and timing ledger
 * it reads are Phase 3/2/7/11's and excluded). Full bounded: 256 target extents + 256 metadata entries, 64 pending attacks and 64 retained
 * results. Global/shared: the engine-wide metrics and the immutable entity geometry table.
 */
public final class ReachMemoryMain {
    private ReachMemoryMain() {}
    static final SessionKey KEY = new SessionKey(new UUID(0x12EEL, 0x11L), 1);
    static final int SELF = 999_999;

    /** A minimal session: world + timeline + timing ledger + combat runner + reach runner, fed in sequence order. */
    static final class S {
        final ObserverWorld w = new ObserverWorld(KEY, new WorldBudget(), new BlockStateDictionary()); final PlayerTimeline t = new PlayerTimeline(KEY); final TimingLedger timing = new TimingLedger(KEY);
        final CombatRunner combat = new CombatRunner(KEY, CombatProfile.PAPER_26_2, w, t, timing::view, () -> null, new CombatEngine.Metrics());
        final ReachRunner reach = new ReachRunner(KEY, EntityGeometryTable.paper26_2(), MovementProfile.paper26_2(), CombatProfile.PAPER_26_2, new ReachRunner.Metrics());
        long seq, nanos = 1_000_000_000L, wire = 1000;
        void feed(Observation.Event e) { seq++; nanos += 1_000_000L; Observation o = new Observation(KEY, seq, nanos, 0, e); combat.apply(o, nanos, SELF, 0, null, false); reach.apply(o, combat, timing, null, null, w, SELF); }
        void spawn(int id, double x) { feed(new Observation.Outbound(++wire, CombatCorpus.spawnPayload(id, "minecraft:zombie", x, 64, 0))); }
        void attack(int id) { feed(new Observation.Attempt(seq + 1, seq + 1, new SemanticPayload.Attack(id))); }
        void tickEnd() { feed(new Observation.Attempt(seq + 1, seq + 1, new PacketPayload.Control(PacketPayload.ControlKind.TICK_END, 0))); }
    }
    static S empty() { return new S(); }
    static S fullTargets() { S s = new S(); for (int i = 0; i < 256; i++) s.spawn(10 + i, i); return s; }
    static S fullPending() { S s = new S(); s.spawn(10, 0); for (int i = 0; i < ReachRunner.MAX_PENDING; i++) s.attack(10); return s; }
    static S fullResults() { S s = new S(); s.spawn(10, 0); for (int i = 0; i < ReachRunner.MAX_RESULTS + 8; i++) { s.attack(10); s.tickEnd(); } return s; }

    public static void main(String[] args) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>(); out.put("phase", "12 - Reach / hitbox / historical ray tracing");
        out.put("method", "forced GC + used-heap diff, 3 trials, median; thread-allocation counters for per-operation garbage; estimates on one host, not capacity");
        double base = measure(() -> { S s = empty(); return new Object[]{s.w, s.t, s.timing, s.combat}; });
        Map<String, Object> per = new LinkedHashMap<>();
        per.put("emptyReachRunnerBytes(beyond the world, timeline, timing ledger and combat runner it reads)", Math.round(measure(ReachMemoryMain::empty) - base));
        double emptyAll = measure(ReachMemoryMain::empty);
        double targetsCombat = measure(() -> { S s = fullTargets(); return new Object[]{s.w, s.t, s.timing, s.combat}; });
        per.put("fullTargetLedgersBytes(256 lifetime extents + 256 metadata entries, beyond the combat runner's own 256 summaries)", Math.round(measure(ReachMemoryMain::fullTargets) - targetsCombat));
        per.put("fullPendingBytes(64 pending attacks with their captured inputs, beyond an empty session)", Math.round(measure(ReachMemoryMain::fullPending) - emptyAll));
        per.put("fullResultsBytes(64 retained results, beyond an empty session)", Math.round(measure(ReachMemoryMain::fullResults) - emptyAll));
        out.put("perSession", per);
        Map<String, Object> global = new LinkedHashMap<>();
        global.put("reachMetricsBytes(one per PacketEngine, fixed cardinality)", Math.round(measure(ReachRunner.Metrics::new)));
        global.put("entityGeometryTable", "one immutable shared table (" + EntityGeometryTable.paper26_2().typeCount() + " types), loaded once");
        out.put("globalShared", global);
        Map<String, Object> alloc = new LinkedHashMap<>();
        S a = new S(); a.spawn(10, 0); for (int i = 0; i < 40; i++) a.tickEnd();
        alloc.put("attackPendingAndDecidedBytes(one ATTACK + the tick end that decides it)", alloc(() -> { a.attack(10); a.tickEnd(); }));
        S b = new S(); for (int i = 0; i < 40; i++) b.spawn(10 + i, i);
        alloc.put("noAttackPathBytes(one tick end, no pending attack)", alloc(b::tickEnd));
        out.put("perOperationAllocationBytes", alloc);
        out.put("bounds", "pending <= " + ReachRunner.MAX_PENDING + " (3 s expiry), results <= " + ReachRunner.MAX_RESULTS + ", target extents <= " + ReachRunner.MAX_TARGETS + ", metadata entries <= " + ReachRunner.MAX_TARGETS + "; no unbounded per-target history (windows are read from the Phase 11 entity history)");
        Path p = Path.of(args[0]); Files.createDirectories(p.getParent()); Files.writeString(p, MiniJson.write(out) + "\n", StandardCharsets.UTF_8);
        System.out.println(MiniJson.write(out));
    }
}
