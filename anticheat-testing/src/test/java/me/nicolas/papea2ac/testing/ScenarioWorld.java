package me.nicolas.papea2ac.testing;
import me.nicolas.papea2ac.collision.CollisionEngine;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.physics.*;
import me.nicolas.papea2ac.version.BlockMovementTable;
import me.nicolas.papea2ac.version.CollisionProfile;
import me.nicolas.papea2ac.version.MovementProfile;
import me.nicolas.papea2ac.world.*;
import java.util.*;

/**
 * The simulator-side twin of the oracle's void world for one {@link PhysicsScenarios.Scenario}: an observer world whose
 * chunks around the arena are fully observed as air, plus the scenario's placed blocks applied as ordered block-change
 * observations (WORLD_MODEL.md: every cell the physics reads is an observed clientbound fact). The environment mirrors
 * the scenario's attributes (server-sent bases over the profile defaults), effects and boots, with a KNOWN empty entity
 * neighbourhood and the vanilla default border - exactly what the oracle server has.
 */
final class ScenarioWorld {
    static final int CHUNK_RADIUS = 3;
    final BlockStateDictionary dictionary = new BlockStateDictionary();
    final SessionKey key = new SessionKey(new UUID(0x5A, 1), 1);
    final ObserverWorld world = new ObserverWorld(key, new WorldBudget(), dictionary);
    final CollisionEngine engine = new CollisionEngine(CollisionProfile.PAPER_26_2, dictionary);
    final BlockBehaviorQuery behaviors = new BlockBehaviorQuery(BlockMovementTable.paper26_2(), CollisionProfile.PAPER_26_2.table(), dictionary);
    final PhysicsSimulator simulator = new PhysicsSimulator(MovementProfile.paper26_2());
    final PhysicsScenarios.Scenario scenario;
    long seq;

    ScenarioWorld(PhysicsScenarios.Scenario scenario) {
        this.scenario = scenario;
        apply(new WorldObservation.DimensionEntered("minecraft:overworld", -64, 384, false));
        List<WorldObservation.SectionContent> air = new ArrayList<>();
        for (int i = 0; i < 24; i++) air.add(new WorldObservation.SectionContent(0, List.of(BlockStateKey.of("minecraft:air")), 0, new long[0]));
        for (int cx = -CHUNK_RADIUS; cx <= CHUNK_RADIUS; cx++) for (int cz = -CHUNK_RADIUS; cz <= CHUNK_RADIUS; cz++) apply(new WorldObservation.ChunkBaselineObserved(cx, cz, true, air, 0, ""));
        for (PhysicsScenarios.Block b : scenario.blocks()) apply(new WorldObservation.BlockChanged(new BlockPos(b.x(), b.y(), b.z()), BlockStateKey.parse(b.state())));
    }
    private void apply(WorldObservation o) { long s = ++seq; if (!world.apply(key, o, s, s, null)) throw new IllegalStateException("apply refused " + o); }

    PhysicsEnvironment environment() {
        MovementProfile profile = MovementProfile.paper26_2();
        PhysicsEnvironment.Attributes attrs = PhysicsEnvironment.Attributes.defaults(profile);
        for (var e : scenario.attributes().entrySet()) attrs = attrs.withBase(e.getKey(), e.getValue());
        List<PhysicsEnvironment.EffectInstance> effects = new ArrayList<>();
        for (PhysicsScenarios.Effect ef : scenario.effects()) effects.add(new PhysicsEnvironment.EffectInstance(ef.id(), ef.amplifier()));
        return PhysicsEnvironment.of(world.currentView(), world.worldEpoch(), engine, behaviors, profile)
            .withAttributes(attrs).withEffects(new PhysicsEnvironment.Effects(PhysicsEnvironment.Knowledge.KNOWN, effects))
            .withEquipment(scenario.boots() ? PhysicsEnvironment.Equipment.LEATHER_BOOTS : PhysicsEnvironment.Equipment.NO_BOOTS);
    }
    /** Runs the whole scenario chained on the simulator's own predictions; results align with the tick list. */
    List<PhysicsResult> runChained() {
        PhysicsEnvironment env = environment();
        List<PhysicsResult> out = new ArrayList<>(); PhysicsState s = scenario.start().state();
        for (PhysicsScenarios.Tick t : scenario.ticks()) { PhysicsResult r = simulator.simulate(s, scenario.input(t), env); out.add(r); if (r.next() == null) break; s = r.next(); }
        return out;
    }
}
