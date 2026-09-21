package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import static me.nicolas.papea2ac.testing.ReconstructionTestSupport.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.physics.PhysicsEnvironment.*;
import me.nicolas.papea2ac.simulation.*;
import me.nicolas.papea2ac.simulation.ReconstructionRequest.*;

/**
 * The Phase 6 replay fixture catalog (§57): deterministic reconstruction cases whose outcome summary (status, completeness,
 * ambiguity, exact counts, and a digest of the canonical candidate set) is pinned in
 * {@code docs/phase6-artifacts/reconstruction-fixtures/reconstruction-fixtures.json}. No live cache or mutable object is
 * serialized - only the request recipe and the result summary. A changed digest is a reviewed event.
 */
final class ReconstructionFixtures {
    private ReconstructionFixtures() {}
    record Case(String id, String description, java.util.function.Supplier<ReconstructionResult> run) {}

    static List<Case> all() {
        List<Case> c = new ArrayList<>();
        c.add(new Case("unique_walk", "rest -> hold W, exact observation: one witness", () -> { PhysicsScene ps = new PhysicsScene(); return reconstruct(ps, settled(ps, 8.5, SURFACE, 8.5), W); }));
        c.add(new Case("unique_sprint_yaw", "rest facing 37.5 -> W+sprint", () -> { PhysicsScene ps = new PhysicsScene(); return reconstruct(ps, settled(ps, 8.5, SURFACE, 8.5), new MovementInput(1, 0, false, false, true, false, 37.5f)); }));
        c.add(new Case("ambiguous_idle", "rest -> no keys: standing/crouching states, sprint flag and item use unobservable", () -> { PhysicsScene ps = new PhysicsScene(); return reconstruct(ps, settled(ps, 8.5, SURFACE, 8.5), IDLE); }));
        c.add(new Case("ambiguous_airborne_jump_key", "mid-jump -> W: jump key held or released", () -> { PhysicsScene ps = new PhysicsScene(); return reconstruct(ps, ps.step(settled(ps, 8.5, SURFACE, 8.5), JUMP).next(), W); }));
        c.add(new Case("ambiguous_wall_push", "walking into a wall: forward variants share the blocked state", () -> { PhysicsScene ps = new PhysicsScene(); for (int x = 6; x <= 10; x++) { ps.set(x, 70, 9, WorldTestSupport.STONE); ps.set(x, 71, 9, WorldTestSupport.STONE); } PhysicsState s = settled(ps, 8.5, SURFACE, 8.5); s = ps.step(s, W).next(); s = ps.step(s, W).next(); return reconstruct(ps, s, W_SPRINT); }));
        c.add(new Case("no_modelled_candidate", "rest -> an observation one block away: every input excluded", () -> { PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); return reconstructor(ps).reconstruct(ReconstructionRequest.of(settled(ps, 8.5, SURFACE, 8.5), TickObservation.exact(9.5, 70.0, 8.5, 0f, TickObservation.GroundClaim.TRUE, 1), env)); }));
        c.add(new Case("unknown_world", "start beside an unobserved chunk: incomplete world, nothing excluded", () -> { PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); PhysicsState s = standingAt(31.2, SURFACE, 31.2).withVelocity(new Vec3d(0.0, -0.0784000015258789, 0.0)); PhysicsResult pr = ps.step(s, W, env); return reconstructor(ps).reconstruct(ReconstructionRequest.of(s, observe(pr.next(), 0f, 1), env)); }));
        c.add(new Case("unknown_attribute", "movement attributes unknown: undecided, never defaulted", () -> { PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5); return reconstructor(ps).reconstruct(ReconstructionRequest.of(s, observations(ps, env, s, List.of(W)), env.withAttributes(Attributes.ALL_UNKNOWN))); }));
        c.add(new Case("unknown_effects", "effect list incomplete", () -> { PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5); return reconstructor(ps).reconstruct(ReconstructionRequest.of(s, observations(ps, env, s, List.of(W)), env.withEffects(Effects.UNKNOWN))); }));
        c.add(new Case("unknown_entities", "entity neighbourhood unknown: possible, never excluded", () -> { PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5); return reconstructor(ps).reconstruct(ReconstructionRequest.of(s, observations(ps, env, s, List.of(W)), env.withEntities(EntityNeighborhood.UNKNOWN))); }));
        c.add(new Case("external_motion_unresolved", "a declared influence with unknown vector", () -> { PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5); return reconstructor(ps).reconstruct(ReconstructionRequest.of(s, observations(ps, env, s, List.of(W)), env).withBranches(List.of(ExternalMotionBranch.firstTick("velocity?", ExternalMotion.unresolved("velocity-1"))))); }));
        c.add(new Case("external_motion_branches", "knockback applied vs not: only the applied branch explains the observation", () -> { PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5); ExternalMotion kb = new ExternalMotion(ExternalMotion.Operation.SET, new Vec3d(0.0, 0.4, 0.5), "velocity-3"); PhysicsResult w = ps.step(s, IDLE, env.withExternalMotions(List.of(kb))); return reconstructor(ps).reconstruct(ReconstructionRequest.of(s, observe(w.next(), 0f, 9), env).withBranches(List.of(ExternalMotionBranch.NONE, ExternalMotionBranch.firstTick("applied", kb)))); }));
        c.add(new Case("search_limit", "a 3-evaluation budget: partial evidence, complete=false", () -> { PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5); return reconstructor(ps).reconstruct(ReconstructionRequest.of(s, observations(ps, env, s, List.of(IDLE)), env).withBudget(new SearchBudget(256, 3, 3, 256, 64, 4096)).withEnvelopePruning(false)); }));
        c.add(new Case("multi_tick_2_exact", "rest -> W, W+A observed at both ticks", () -> { PhysicsScene ps = new PhysicsScene(); return reconstruct(ps, settled(ps, 8.5, SURFACE, 8.5), List.of(W, W_A)); }));
        c.add(new Case("multi_tick_3_exact", "rest -> W, jump, W+sprint observed at every tick", () -> { PhysicsScene ps = new PhysicsScene(); return reconstruct(ps, settled(ps, 8.5, SURFACE, 8.5), List.of(W, JUMP, W_SPRINT)); }));
        c.add(new Case("multi_tick_2_silent_middle", "rest -> idle (silent tick), then W observed", () -> { PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5); List<TickObservation> o = observations(ps, env, s, List.of(IDLE, W)); return reconstructor(ps).reconstruct(ReconstructionRequest.of(s, List.of(TickObservation.silent(8.5, 70.0, 8.5, 0f, 100), o.get(1)), env)); }));
        c.add(new Case("multi_tick_2_unobserved_middle", "rest -> W (unobserved), W observed: envelope lookahead keeps it complete", () -> { PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5); List<TickObservation> o = observations(ps, env, s, List.of(W, W)); return reconstructor(ps).reconstruct(ReconstructionRequest.of(s, List.of(TickObservation.unobserved(0f, 100), o.get(1)), env)); }));
        c.add(new Case("world_epoch_mismatch", "observation epoch differs from the view", () -> { PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5); return reconstructor(ps).reconstruct(ReconstructionRequest.of(s, observations(ps, env, s, List.of(W)), env).withWorldEpoch(env.worldEpoch() + 1)); }));
        c.add(new Case("discontinuity", "a teleport inside the transition", () -> { PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5); return reconstructor(ps).reconstruct(ReconstructionRequest.of(s, observations(ps, env, s, List.of(W)), env).withDiscontinuity(true)); }));
        c.add(new Case("fluid_deferred", "standing in water: UNSUPPORTED_FLUID", () -> { PhysicsScene ps = new PhysicsScene(); ps.set(8, 70, 8, WATER); return reconstructor(ps).reconstruct(ReconstructionRequest.of(standingAt(8.5, SURFACE, 8.5), TickObservation.exact(8.5, 70.0, 8.5, 0f, TickObservation.GroundClaim.TRUE, 1), ps.env())); }));
        c.add(new Case("envelope_only_abstract_start", "start known only inside a silent-tick ball: envelope possibilities, no witness", () -> { PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); PhysicsState rest = settled(ps, 8.5, SURFACE, 8.5); PhysicsResult w = ps.step(rest, W, env); AbstractState.Box box = new AbstractState.Box(new Interval(8.4999, 8.5001), Interval.point(70.0), new Interval(8.4999, 8.5001), Interval.ZERO, Interval.point(-0.0784000015258789), Interval.ZERO, AbstractState.GROUND_ON, AbstractState.POSE_STANDING, 0, 0); return reconstructor(ps).reconstruct(ReconstructionRequest.of(rest, observe(w.next(), 0f, 7), env).withFrontier(List.of(new AbstractStart(AbstractState.of(box), "ball")))); }));
        c.add(new Case("known_constraints_witness", "sprint flag and strafe known, reported input tried first", () -> { PhysicsScene ps = new PhysicsScene(); PhysicsEnvironment env = ps.env(); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5); return reconstructor(ps).reconstruct(ReconstructionRequest.of(s, observations(ps, env, s, List.of(W_SPRINT)), env).withConstraints(InputDomain.Constraints.NONE.withSprinting(true).withStrafe(0)).withWitness(W_SPRINT)); }));
        c.add(new Case("step_up_slab", "walking onto a slab: outside the envelope pilot, concrete lane only", () -> { PhysicsScene ps = new PhysicsScene(); ps.set(8, 70, 10, me.nicolas.papea2ac.model.BlockStateKey.parse("minecraft:oak_slab[type=bottom,waterlogged=false]")); PhysicsState s = settled(ps, 8.5, SURFACE, 8.5); for (int i = 0; i < 5; i++) s = ps.step(s, W).next(); return reconstruct(ps, s, W); }));
        c.add(new Case("ice_decel_silent", "sliding on ice below the send threshold: the first silent tick constrains the decay", () -> {
            PhysicsScene ps = new PhysicsScene(); for (int x = 0; x <= 15; x++) for (int z = 0; z <= 15; z++) ps.set(x, 69, z, ICE); PhysicsEnvironment env = ps.env();
            PhysicsState before = ps.step(settled(ps, 8.5, SURFACE, 8.5), W).next(); Vec3d lastSent = before.position();
            for (int i = 0; i < 120; i++) {
                PhysicsState after = ps.step(before, IDLE, env).next();
                if (me.nicolas.papea2ac.version.MovementObservationProfile.PAPER_26_2.withinSendThreshold(after.position().x(), after.position().y(), after.position().z(), lastSent.x(), lastSent.y(), lastSent.z()))
                    return reconstructor(ps).reconstruct(ReconstructionRequest.of(before, TickObservation.silent(lastSent.x(), lastSent.y(), lastSent.z(), 0f, i), env));
                lastSent = after.position(); before = after;
            }
            throw new IllegalStateException("no silent tick within 120 ticks");
        }));
        return c;
    }
    static Map<String, Object> summary(ReconstructionResult r) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.status().name()); m.put("complete", r.complete()); m.put("ambiguity", r.ambiguity().name());
        m.put("matchingStates", r.counts().matchingStates()); m.put("matchingHistories", r.counts().matchingHistories()); m.put("excludedExact", r.counts().excludedExact()); m.put("excludedEnvelope", r.counts().excludedEnvelope()); m.put("undecided", r.counts().undecided());
        m.put("evaluations", r.work().evaluations()); m.put("candidates", r.candidates().size());
        List<String> canon = new ArrayList<>(); for (ReconstructionResult.Candidate c : r.candidates()) canon.add(keys(c.input()) + ":" + c.historyCount() + ":" + c.match());
        m.put("canonicalCandidates", new ArrayList<Object>(canon.size() > 12 ? canon.subList(0, 12) : canon));
        m.put("candidateSetDigest", digest(String.join("\n", ReconstructionRoundTripTest.candidateSet(r))));
        return m;
    }
    static String digest(String s) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
}
