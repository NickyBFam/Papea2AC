package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2ac.core.MitigationPreconditions;
import me.nicolas.papea2ac.core.MitigationPreconditions.PlatformFacts;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.Category;
import me.nicolas.papea2ac.mitigation.*;
import me.nicolas.papea2ac.mitigation.MitigationVocabulary.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.model.SafeAnchorValidationPort.ClearanceResult;
import me.nicolas.papea2ac.version.CollisionProfile;
import org.junit.jupiter.api.Test;

/**
 * Phase 10 application boundary: the main thread's destination re-check (the shared-geometry {@link SafeAnchorValidator} over a captured
 * {@link DestinationRegion}) and the fixed main-thread precondition order ({@link MitigationPreconditions}) - wrong session, stale (expired)
 * action, wrong world, death, vehicle/gliding, game mode, unresolved correction, distance, unloaded destination, obstruction and unknown
 * coverage each reject with their own reason; only a fully clear, current, same-session intent passes.
 */
class MitigationApplicationTest {
    static final SessionKey KEY = new SessionKey(new UUID(0x10, 1), 1);
    static final Vector3 T = new Vector3(8.5, SURFACE, 8.5);

    static SafeAnchorValidationPort.Bounds box(Vector3 p) { return DestinationRegionCapture.bounds(CollisionTestSupport.standing(p.x(), p.y(), p.z())); }
    /** A captured region over the padded broad-phase range with every cell {@code air} except the listed solid cells. */
    static DestinationRegion region(PhysicsScene ps, Vector3 p, boolean complete, Set<List<Integer>> stone, Set<List<Integer>> missing) {
        int air = ps.scene.dictionary.intern(BlockStateKey.of("minecraft:air")), st = ps.scene.dictionary.intern(BlockStateKey.of("minecraft:stone"));
        var r = DestinationRegionCapture.range(box(p)); List<DestinationRegion.Cell> cells = new ArrayList<>();
        for (int y = r.y0(); y <= r.y1(); y++) for (int z = r.z0(); z <= r.z1(); z++) for (int x = r.x0(); x <= r.x1(); x++) {
            List<Integer> k = List.of(x, y, z); if (missing.contains(k)) continue;
            cells.add(new DestinationRegion.Cell(x, y, z, stone.contains(k) || y < (int) SURFACE ? st : air));
        }
        return new DestinationRegion("minecraft:overworld", 0, 1, complete, cells);
    }

    @Test void theDestinationIsClearOnlyWhenEveryCellIsKnownAndEmpty() {
        PhysicsScene ps = new PhysicsScene(); SafeAnchorValidator v = new SafeAnchorValidator(ps.scene.engine);
        assertEquals(ClearanceResult.CLEAR, v.validate(region(ps, T, true, Set.of(), Set.of()), box(T)), "standing on the floor, air above: clear (touching the floor is not an intersection)");
        assertEquals(ClearanceResult.OBSTRUCTED, v.validate(region(ps, T, true, Set.of(List.of(8, (int) SURFACE + 1, 8)), Set.of()), box(T)), "a block at head height");
        assertEquals(ClearanceResult.UNKNOWN, v.validate(region(ps, T, true, Set.of(), Set.of(List.of(8, (int) SURFACE, 8))), box(T)), "an uncaptured cell is never air");
        assertEquals(ClearanceResult.UNKNOWN, v.validate(region(ps, T, false, Set.of(), Set.of()), box(T)), "an incomplete capture is never clear");
        assertEquals(ClearanceResult.OBSTRUCTED, v.validate(region(ps, new Vector3(8.5, SURFACE - 0.5, 8.5), true, Set.of(), Set.of()), box(new Vector3(8.5, SURFACE - 0.5, 8.5))), "inside the floor");
    }
    @Test void theCaptureRangeUsesThePinnedBroadPhasePadding() {
        assertEquals(CollisionProfile.BROAD_PHASE_PADDING, DestinationRegionCapture.PAD, 0.0, "mitigation's copy of the padding equals the pinned profile");
        var r = DestinationRegionCapture.range(box(T));
        assertEquals(7, r.x0()); assertEquals(9, r.x1()); assertEquals((int) SURFACE - 2, r.y0(), "minY 70.0 - epsilon floors to 69, minus the padding cell"); assertEquals((int) SURFACE + 2, r.y1());
        assertTrue(r.cells() <= DestinationRegionCapture.MAX_CELLS);
    }

    static MitigationIntent intent(long proposed) {
        SafeAnchor a = new SafeAnchor(KEY, 0, 1, 50, proposed - 100, T, 0f, 0f, true, false, AnchorSource.VALID_CONCRETE_GROUNDED, List.of("test"));
        return new MitigationIntent(MitigationIntent.actionId(SetbackPolicy.VERSION, KEY, 0, List.of("g1", "g2", "g3", "g4"), false, 50), ActionType.SETBACK, KEY, 0, "minecraft:overworld", 0,
            List.of("g1", "g2", "g3", "g4"), List.of(), Category.HORIZONTAL_EXCESS, SetbackPolicy.VERSION, false, a, 400, 1, proposed, proposed + MitigationBounds.DEFAULT.intentDeadlineNanos(), "capsule");
    }
    static PlatformFacts ok(long now) { return new PlatformFacts(true, true, now, "minecraft:overworld", false, false, true, 0, 1.0, true, ClearanceResult.CLEAR); }

    @Test void onlyACurrentClearSameSessionIntentPassesTheMainThreadChecks() {
        long p = 1_000_000_000L; MitigationIntent it = intent(p); MitigationBounds b = MitigationBounds.DEFAULT;
        assertEquals(Optional.empty(), MitigationPreconditions.check(it, ok(p + 1), b));
        Map<Reason, PlatformFacts> cases = new LinkedHashMap<>();
        cases.put(Reason.SESSION_ENDED, new PlatformFacts(false, true, p + 1, "minecraft:overworld", false, false, true, 0, 1.0, true, ClearanceResult.CLEAR));
        cases.put(Reason.PLAYER_OFFLINE, new PlatformFacts(true, false, p + 1, "offline", false, false, false, 0, Double.POSITIVE_INFINITY, false, ClearanceResult.UNKNOWN));
        cases.put(Reason.DEADLINE_EXPIRED, ok(it.deadlineNanos() + 1));
        cases.put(Reason.WORLD_CHANGED, new PlatformFacts(true, true, p + 1, "minecraft:the_nether", false, false, true, 0, 1.0, true, ClearanceResult.CLEAR));
        cases.put(Reason.DEATH_OR_RESPAWN, new PlatformFacts(true, true, p + 1, "minecraft:overworld", true, false, true, 0, 1.0, true, ClearanceResult.CLEAR));
        cases.put(Reason.VEHICLE_OR_GLIDING, new PlatformFacts(true, true, p + 1, "minecraft:overworld", false, true, true, 0, 1.0, true, ClearanceResult.CLEAR));
        cases.put(Reason.UNSUPPORTED_LOCOMOTION, new PlatformFacts(true, true, p + 1, "minecraft:overworld", false, false, false, 0, 1.0, true, ClearanceResult.CLEAR));
        cases.put(Reason.UNRESOLVED_TELEPORT, new PlatformFacts(true, true, p + 1, "minecraft:overworld", false, false, true, 1, 1.0, true, ClearanceResult.CLEAR));
        cases.put(Reason.ANCHOR_TOO_FAR, new PlatformFacts(true, true, p + 1, "minecraft:overworld", false, false, true, 0, 8.01, true, ClearanceResult.CLEAR));
        cases.put(Reason.DESTINATION_UNLOADED, new PlatformFacts(true, true, p + 1, "minecraft:overworld", false, false, true, 0, 1.0, false, ClearanceResult.UNKNOWN));
        cases.put(Reason.COLLISION_OBSTRUCTED, new PlatformFacts(true, true, p + 1, "minecraft:overworld", false, false, true, 0, 1.0, true, ClearanceResult.OBSTRUCTED));
        cases.put(Reason.COLLISION_UNKNOWN, new PlatformFacts(true, true, p + 1, "minecraft:overworld", false, false, true, 0, 1.0, true, ClearanceResult.UNKNOWN));
        for (var e : cases.entrySet()) assertEquals(Optional.of(e.getKey()), MitigationPreconditions.check(it, e.getValue(), b), e.getKey().name());
        assertEquals(Optional.of(Reason.ANCHOR_TOO_FAR), MitigationPreconditions.check(it, new PlatformFacts(true, true, p + 1, "minecraft:overworld", false, false, true, 0, Double.NaN, true, ClearanceResult.CLEAR), b), "a NaN distance is never within bounds");
    }
    @Test void theActionIdentityIsStableAndDistinguishesEverySourceOfTheDecision() {
        String a = MitigationIntent.actionId(SetbackPolicy.VERSION, KEY, 0, List.of("a", "b", "c", "d"), false, 50);
        assertEquals(a, MitigationIntent.actionId(SetbackPolicy.VERSION, KEY, 0, List.of("a", "b", "c", "d"), false, 50));
        assertNotEquals(a, MitigationIntent.actionId(SetbackPolicy.VERSION, KEY, 1, List.of("a", "b", "c", "d"), false, 50), "epoch");
        assertNotEquals(a, MitigationIntent.actionId(SetbackPolicy.VERSION, new SessionKey(KEY.player(), 2), 0, List.of("a", "b", "c", "d"), false, 50), "session");
        assertNotEquals(a, MitigationIntent.actionId(SetbackPolicy.VERSION, KEY, 0, List.of("a", "b", "c", "e"), false, 50), "evidence");
        assertNotEquals(a, MitigationIntent.actionId(SetbackPolicy.VERSION, KEY, 0, List.of("a", "b", "c", "d"), true, 50), "chain");
        assertNotEquals(a, MitigationIntent.actionId(SetbackPolicy.VERSION, KEY, 0, List.of("a", "b", "c", "d"), false, 51), "anchor");
        assertEquals(64, a.length()); assertEquals(16, intent(1_000_000_000L).shortId().length());
    }
    /** P10-F2 (live qualification): `/papea2ac effects resume` from qualification ENFORCE threw "cannot resume through enforce" after already
     *  telling the sessions OBSERVE_ONLY, leaving the main-thread arbiter in ENFORCE. The way back is the arbiter's restriction, arbiter first. */
    @Test void resumingFromQualificationEnforceRestrictsTheArbiterAndTheSessionsTogether() throws Exception {
        var engine = new me.nicolas.papea2ac.core.PacketEngine(1, 1L << 30, e -> fail(e)); UUID id = new UUID(10, 2);
        try {
            engine.enableEnforce(true, true);
            assertFalse(engine.effectsSuspended(), "qualification ENFORCE enabled");
            assertDoesNotThrow(engine::resumeEffects, "resume is the documented way back from qualification ENFORCE");
            assertTrue(engine.effectsSuspended(), "the sessions' mode is OBSERVE_ONLY");
            assertFalse(engine.admitEffect(id, System.nanoTime()), "and the main-thread arbiter no longer admits any effect");
            assertDoesNotThrow(engine::resumeEffects, "resuming again from OBSERVE_ONLY stays a no-op restriction");
            engine.enableEnforce(true, true); engine.suspendEffects();
            assertTrue(engine.effectsSuspended()); assertFalse(engine.admitEffect(id, System.nanoTime()));
            assertDoesNotThrow(engine::resumeEffects, "DISABLED -> OBSERVE_ONLY"); assertTrue(engine.effectsSuspended()); assertFalse(engine.admitEffect(id, System.nanoTime()));
        } finally { engine.close(); }
    }
}
