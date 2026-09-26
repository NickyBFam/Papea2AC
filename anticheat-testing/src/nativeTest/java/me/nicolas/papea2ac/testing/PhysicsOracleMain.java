package me.nicolas.papea2ac.testing;

import me.nicolas.papea2ac.collision.PlayerBox;
import me.nicolas.papea2ac.physics.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Phase 5 native differential driver: prepares an isolated pinned Paper server (void superflat, offline, no rcon) with
 * the test-only oracle plugin, feeds it the {@link PhysicsScenarios} catalog, waits for it to run every scenario through
 * the server's own movement code and shut down, then compares every tick against the Papea2AC simulator in two ways:
 * <ul>
 * <li>SINGLE-STEP: the simulator starts each tick from the ORACLE's previous state (per-tick exactness);</li>
 * <li>CHAINED: the simulator follows its own predictions from the scenario start (multi-tick drift at 2/5/20/100 ticks).</li>
 * </ul>
 * Comparison is bit-level (raw double bits) for position and velocity, exact for every flag. Writes
 * {@code native-differential-results.json} and {@code multi-tick-drift-results.json} into the artifacts directory and
 * prints a summary. The oracle log is kept next to the server directory. Never runs in production.
 *
 * Usage: {@code PhysicsOracleMain <.phase1-runtime> <work-dir> <artifacts-dir> <oracle-plugin-jar>}
 */
public final class PhysicsOracleMain {
    public static void main(String[] args) throws Exception {
        Path runtime = Path.of(args[0]), work = Path.of(args[1]), artifacts = Path.of(args[2]), oracleJar = Path.of(args[3]);
        Path server = work.resolve("server");
        Files.createDirectories(server.resolve("plugins")); Files.createDirectories(artifacts);
        // ---- server directory: paperclip launcher + junctions to the prepared versions/libraries (no download, same pinned bytes)
        Files.copy(runtime.resolve("paper.jar"), server.resolve("paper.jar"), StandardCopyOption.REPLACE_EXISTING);
        link(server.resolve("versions"), runtime.resolve("versions"));
        link(server.resolve("libraries"), runtime.resolve("libraries"));
        Files.writeString(server.resolve("eula.txt"), "eula=true\n");
        Files.writeString(server.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=25599", "server-ip=127.0.0.1", "enable-rcon=false", "enable-query=false", "enable-status=false", "max-players=1", "view-distance=4", "simulation-distance=4",
            "level-name=oracle", "level-type=minecraft\\:flat", "generator-settings={\"layers\":[],\"biome\":\"minecraft:the_void\",\"features\":false,\"lakes\":false}",
            "generate-structures=false", "spawn-monsters=false", "spawn-animals=false", "spawn-npcs=false", "difficulty=normal", "pause-when-empty-seconds=-1", "sync-chunk-writes=false",
            "spawn-protection=0", "allow-nether=false", "enforce-secure-profile=false", "log-ips=false", "use-native-transport=false", "network-compression-threshold=-1", "motd=Papea2AC physics oracle") + "\n");
        Files.copy(oracleJar, server.resolve("plugins").resolve("physics-oracle.jar"), StandardCopyOption.REPLACE_EXISTING);
        List<PhysicsScenarios.Scenario> scenarios = PhysicsScenarios.all();
        Files.writeString(server.resolve("oracle-scenarios.json"), MiniJson.write(scenarioSpec(scenarios)), StandardCharsets.UTF_8);
        Files.deleteIfExists(server.resolve("oracle-results.json"));
        // ---- run the server until it shuts itself down
        String java = Path.of(System.getProperty("java.home"), "bin", "java" + (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? ".exe" : "")).toString();
        Path log = work.resolve("oracle-server.log");
        long t0 = System.nanoTime();
        Process p = new ProcessBuilder(java, "-Xms1g", "-Xmx1g", "-XX:+UseG1GC", "-Dcom.mojang.eula.agree=true", "-jar", "paper.jar", "nogui").directory(server.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        p.getOutputStream().close();
        boolean exited = p.waitFor(15, TimeUnit.MINUTES);
        if (!exited) { p.destroyForcibly(); throw new IllegalStateException("oracle server did not finish within 15 minutes; see " + log); }
        double serverSeconds = (System.nanoTime() - t0) / 1e9;
        Path resultsFile = server.resolve("oracle-results.json");
        if (!Files.exists(resultsFile)) throw new IllegalStateException("oracle produced no results (exit " + p.exitValue() + "); see " + log);
        Map<String, Object> oracle = MiniJson.obj(MiniJson.parse(Files.readString(resultsFile, StandardCharsets.UTF_8)));
        if (oracle.get("error") != null) throw new IllegalStateException("oracle error: " + oracle.get("error"));
        Map<String, Object> byId = new HashMap<>();
        for (Object o : MiniJson.arr(oracle.get("scenarios"))) byId.put((String) MiniJson.obj(o).get("id"), o);
        // ---- compare
        List<Object> perScenario = new ArrayList<>(); List<Object> driftRows = new ArrayList<>();
        int scenariosCompared = 0, scenariosExactSingle = 0, scenariosExactChained = 0, ticksCompared = 0, ticksExactSingle = 0, ticksExactChained = 0, deferred = 0, oracleErrors = 0;
        double worstSingle = 0, worstChained = 0; String worstSingleId = "-", worstChainedId = "-";
        int[] horizons = { 2, 5, 20, 100 }; double[] worstAtHorizon = new double[horizons.length]; String[] worstAtHorizonId = { "-", "-", "-", "-" };
        for (PhysicsScenarios.Scenario sc : scenarios) {
            Map<String, Object> row = new LinkedHashMap<>(); row.put("id", sc.id()); row.put("group", sc.group()); row.put("ticks", sc.ticks().size());
            Map<String, Object> or = MiniJson.obj(byId.get(sc.id()));
            if (or == null || or.get("error") != null) { row.put("oracle", or == null ? "MISSING" : "ERROR"); row.put("error", or == null ? null : String.valueOf(or.get("error")).lines().findFirst().orElse("")); oracleErrors++; perScenario.add(row); continue; }
            List<Object> oracleTicks = MiniJson.arr(or.get("ticks"));
            ScenarioWorld w = new ScenarioWorld(sc);
            PhysicsEnvironment env = w.environment();
            if (sc.fluidDeferred()) {
                PhysicsResult r = w.simulator.simulate(sc.start().state(), sc.input(sc.ticks().get(0)), env);
                row.put("oracle", "RAN"); row.put("simulator", r.status().name()); row.put("deferred", true); row.put("expected", "UNSUPPORTED_FLUID");
                row.put("pass", r.status() == PhysicsResult.Status.UNSUPPORTED_FLUID); deferred++; perScenario.add(row); continue;
            }
            scenariosCompared++;
            // single-step
            int exactSingle = 0, firstMismatchSingle = -1; List<Object> mismatches = new ArrayList<>(); double maxSingle = 0; Set<String> statuses = new TreeSet<>();
            PhysicsState prev = sc.start().state();
            for (int i = 0; i < oracleTicks.size(); i++) {
                Map<String, Object> ot = MiniJson.obj(oracleTicks.get(i));
                PhysicsResult r = w.simulator.simulate(prev, sc.input(sc.ticks().get(i)), env);
                statuses.add(r.status().name());
                List<String> diff = r.next() == null ? List.of("no state: " + r.status()) : diff(r.next(), ot);
                double d = r.next() == null ? Double.NaN : maxAbs(r.next(), ot);
                if (!Double.isNaN(d)) maxSingle = Math.max(maxSingle, d);
                if (diff.isEmpty()) exactSingle++;
                else { if (firstMismatchSingle < 0) firstMismatchSingle = i; if (mismatches.size() < 6) { Map<String, Object> m = new LinkedHashMap<>(); m.put("tick", i); m.put("fields", diff); m.put("simulator", r.next() == null ? r.summary() : describe(r.next())); m.put("oracle", describeOracle(ot)); m.put("status", r.status().name()); mismatches.add(m); } }
                prev = fromOracle(ot);
            }
            // chained
            int exactChained = 0, firstMismatchChained = -1; double maxChained = 0; double[] atHorizon = new double[horizons.length]; Arrays.fill(atHorizon, Double.NaN);
            PhysicsState s = sc.start().state();
            for (int i = 0; i < oracleTicks.size(); i++) {
                Map<String, Object> ot = MiniJson.obj(oracleTicks.get(i));
                PhysicsResult r = w.simulator.simulate(s, sc.input(sc.ticks().get(i)), env);
                if (r.next() == null) { if (firstMismatchChained < 0) firstMismatchChained = i; break; }
                double d = maxAbs(r.next(), ot); maxChained = Math.max(maxChained, d);
                boolean exact = diff(r.next(), ot).isEmpty();
                if (exact) exactChained++; else if (firstMismatchChained < 0) firstMismatchChained = i;
                for (int h = 0; h < horizons.length; h++) if (i + 1 == horizons[h]) atHorizon[h] = d;
                s = r.next();
            }
            ticksCompared += oracleTicks.size(); ticksExactSingle += exactSingle; ticksExactChained += exactChained;
            if (exactSingle == oracleTicks.size()) scenariosExactSingle++;
            if (exactChained == oracleTicks.size()) scenariosExactChained++;
            if (maxSingle > worstSingle) { worstSingle = maxSingle; worstSingleId = sc.id(); }
            if (maxChained > worstChained) { worstChained = maxChained; worstChainedId = sc.id(); }
            for (int h = 0; h < horizons.length; h++) if (!Double.isNaN(atHorizon[h]) && atHorizon[h] > worstAtHorizon[h]) { worstAtHorizon[h] = atHorizon[h]; worstAtHorizonId[h] = sc.id(); }
            row.put("oracle", "RAN"); row.put("simulatorStatuses", new ArrayList<>(statuses));
            row.put("singleStepExactTicks", exactSingle); row.put("singleStepFirstMismatch", firstMismatchSingle); row.put("singleStepMaxAbsDiff", maxSingle);
            row.put("chainedExactTicks", exactChained); row.put("chainedFirstMismatch", firstMismatchChained); row.put("chainedMaxAbsDiff", maxChained);
            row.put("pass", exactSingle == oracleTicks.size());
            if (!mismatches.isEmpty()) row.put("mismatches", mismatches);
            perScenario.add(row);
            Map<String, Object> dr = new LinkedHashMap<>(); dr.put("id", sc.id()); dr.put("ticks", oracleTicks.size());
            for (int h = 0; h < horizons.length; h++) dr.put("driftAt" + horizons[h], Double.isNaN(atHorizon[h]) ? null : atHorizon[h]);
            dr.put("maxDrift", maxChained); dr.put("exactChainedTicks", exactChained); driftRows.add(dr);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("phase", "5 - Physics Simulator"); out.put("oracle", "pinned Paper 26.2 build 124 server process (" + oracle.get("server") + "), test-only OraclePlayer driving the jar's own LivingEntity/Entity/Player movement code; comparison is bit-level on position and velocity, exact on flags");
        out.put("serverRunSeconds", Math.round(serverSeconds * 10) / 10.0); out.put("oracleLog", log.toString());
        out.put("scenarios", scenarios.size()); out.put("scenariosCompared", scenariosCompared); out.put("scenariosDeferredFluid", deferred); out.put("oracleErrors", oracleErrors);
        out.put("scenariosExactSingleStep", scenariosExactSingle); out.put("scenariosExactChained", scenariosExactChained);
        out.put("ticksCompared", ticksCompared); out.put("ticksExactSingleStep", ticksExactSingle); out.put("ticksExactChained", ticksExactChained);
        out.put("worstSingleStepAbsDiff", worstSingle); out.put("worstSingleStepScenario", worstSingleId); out.put("worstChainedAbsDiff", worstChained); out.put("worstChainedScenario", worstChainedId);
        out.put("perScenario", perScenario);
        Files.writeString(artifacts.resolve("native-differential-results.json"), MiniJson.write(out) + "\n", StandardCharsets.UTF_8);
        Map<String, Object> drift = new LinkedHashMap<>();
        drift.put("phase", "5 - Physics Simulator"); drift.put("method", "chained: the simulator follows its own predicted state from the scenario start; drift = max abs difference over position and velocity components against the oracle at the same tick");
        Map<String, Object> worst = new LinkedHashMap<>(); for (int h = 0; h < horizons.length; h++) { worst.put("driftAt" + horizons[h], worstAtHorizon[h]); worst.put("driftAt" + horizons[h] + "Scenario", worstAtHorizonId[h]); } drift.put("worst", worst);
        drift.put("perScenario", driftRows);
        Files.writeString(artifacts.resolve("multi-tick-drift-results.json"), MiniJson.write(drift) + "\n", StandardCharsets.UTF_8);
        // ---- pinned native trajectories: per scenario a digest over the raw bits of every tick's (x,y,z,vx,vy,vz) plus flags, replayed offline by PhysicsOracleFixtureTest
        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("phase", "5 - Physics Simulator"); fixtures.put("source", "oracle-results.json of the pinned Paper 26.2 build 124 server run (native trajectories); digest = SHA-256 over the little-endian raw bits of x,y,z,vx,vy,vz,fallDistance,stuck xyz and the flag byte per tick");
        fixtures.put("server", String.valueOf(oracle.get("server")));
        Map<String, Object> perFixture = new LinkedHashMap<>();
        for (PhysicsScenarios.Scenario sc : scenarios) {
            Map<String, Object> or = MiniJson.obj(byId.get(sc.id()));
            if (or == null || or.get("error") != null || sc.fluidDeferred()) continue;
            List<Object> oracleTicks = MiniJson.arr(or.get("ticks"));
            List<PhysicsState> states = new ArrayList<>(); for (Object o : oracleTicks) states.add(fromOracle(MiniJson.obj(o)));
            Map<String, Object> f = new LinkedHashMap<>(); f.put("ticks", states.size()); f.put("digest", TrajectoryDigest.of(states));
            PhysicsState last = states.get(states.size() - 1);
            f.put("last", List.of(last.position().x(), last.position().y(), last.position().z(), last.velocity().x(), last.velocity().y(), last.velocity().z()));
            perFixture.put(sc.id(), f);
        }
        fixtures.put("scenarios", perFixture);
        Files.createDirectories(artifacts.resolve("physics-fixtures"));
        Files.writeString(artifacts.resolve("physics-fixtures").resolve("native-trajectories.json"), MiniJson.write(fixtures) + "\n", StandardCharsets.UTF_8);
        // ---- Phase 6: input reconstruction against the ORACLE's own per-tick states (start = the server's state before the tick, observation = the server's
        //      state after it, declared input hidden): the declared input must be an exact witness on every tick. The plain-test gate replays the same
        //      trajectories through the digest-verified chain; this pass uses the server's states directly.
        Map<String, Object> rec = new LinkedHashMap<>(); List<Object> recRows = new ArrayList<>(); int recTicks = 0, recIncluded = 0, recWitness = 0, recUnique = 0, recEquivalent = 0, recDistinct = 0, recIncomplete = 0; List<String> recFailures = new ArrayList<>();
        for (PhysicsScenarios.Scenario sc : scenarios) {
            Map<String, Object> or = MiniJson.obj(byId.get(sc.id()));
            if (or == null || or.get("error") != null || sc.fluidDeferred()) continue;
            List<Object> oracleTicks = MiniJson.arr(or.get("ticks"));
            ScenarioWorld w = new ScenarioWorld(sc); PhysicsEnvironment env = w.environment(); me.nicolas.papea2ac.simulation.InputReconstructor reconstructor = new me.nicolas.papea2ac.simulation.InputReconstructor(w.simulator);
            PhysicsState start = sc.start().state(); int sInc = 0, sUnique = 0, sEq = 0, sDist = 0, sInc2 = 0;
            for (int i = 0; i < oracleTicks.size(); i++) {
                PhysicsState next = fromOracle(MiniJson.obj(oracleTicks.get(i))); MovementInput declared = sc.input(sc.ticks().get(i));
                me.nicolas.papea2ac.simulation.TickObservation obs = me.nicolas.papea2ac.simulation.TickObservation.exact(next.position().x(), next.position().y(), next.position().z(), declared.yaw(), next.onGround() ? me.nicolas.papea2ac.simulation.TickObservation.GroundClaim.TRUE : me.nicolas.papea2ac.simulation.TickObservation.GroundClaim.FALSE, i + 1);
                me.nicolas.papea2ac.simulation.ReconstructionResult r = reconstructor.reconstruct(me.nicolas.papea2ac.simulation.ReconstructionRequest.of(start, obs, env));
                recTicks++; boolean adm = r.admits(List.of(declared)), wit = r.witnesses(List.of(declared));
                if (adm) { recIncluded++; sInc++; } if (wit) recWitness++;
                if (!adm) recFailures.add(sc.id() + " tick " + (i + 1) + ": declared " + me.nicolas.papea2ac.simulation.InputDomain.describe(declared) + " EXCLUDED: " + String.join(" | ", r.describe(4)));
                if (!r.complete()) { recIncomplete++; sInc2++; } else switch (r.ambiguity()) { case UNIQUE -> { recUnique++; sUnique++; } case EQUIVALENT_INPUTS -> { recEquivalent++; sEq++; } case DISTINCT_STATES -> { recDistinct++; sDist++; } case NONE -> { } }
                start = next; // continue from the SERVER's state, never from the simulator's
            }
            Map<String, Object> row = new LinkedHashMap<>(); row.put("id", sc.id()); row.put("ticks", oracleTicks.size()); row.put("declaredIncluded", sInc); row.put("unique", sUnique); row.put("equivalentInputs", sEq); row.put("distinctStates", sDist); row.put("incomplete", sInc2); recRows.add(row);
        }
        rec.put("phase", "6 - Input Reconstruction"); rec.put("oracle", "per-tick states of the pinned Paper 26.2 build 124 server process (this run), start = server state before the tick, observation = server state after it, declared input hidden");
        rec.put("ticksChecked", recTicks); rec.put("declaredInputIncluded", recIncluded); rec.put("declaredInputExactWitness", recWitness); rec.put("falseExclusions", recTicks - recIncluded);
        rec.put("uniqueTicks", recUnique); rec.put("equivalentInputTicks", recEquivalent); rec.put("distinctStateTicks", recDistinct); rec.put("incompleteTicks", recIncomplete); rec.put("perScenario", recRows); rec.put("failures", new ArrayList<Object>(recFailures));
        Path phase6 = artifacts; Files.createDirectories(phase6); // since P9-F3 the artifacts directory IS docs/phase9-artifacts (P6-F1 pinned docs/phase6-artifacts; both kept as history)
        Files.writeString(phase6.resolve("native-reconstruction-oracle-results.json"), MiniJson.write(rec) + "\n", StandardCharsets.UTF_8);
        System.out.println("INPUT RECONSTRUCTION vs ORACLE STATES: ticks=" + recTicks + " declaredIncluded=" + recIncluded + " witness=" + recWitness + " unique=" + recUnique + " equivalentInputs=" + recEquivalent + " distinctStates=" + recDistinct + " incomplete=" + recIncomplete + " falseExclusions=" + (recTicks - recIncluded));
        for (String f : recFailures) System.out.println("  FALSE EXCLUSION " + f);
        System.out.println("PHYSICS NATIVE DIFFERENTIAL: scenarios=" + scenarios.size() + " compared=" + scenariosCompared + " exactSingleStep=" + scenariosExactSingle + " exactChained=" + scenariosExactChained + " deferredFluid=" + deferred + " oracleErrors=" + oracleErrors);
        System.out.println("  ticks compared=" + ticksCompared + " exactSingle=" + ticksExactSingle + " exactChained=" + ticksExactChained + " worstSingle=" + worstSingle + " (" + worstSingleId + ") worstChained=" + worstChained + " (" + worstChainedId + ") server=" + Math.round(serverSeconds) + "s");
        for (Object o : perScenario) { Map<String, Object> r = MiniJson.obj(o); if (!Boolean.TRUE.equals(r.get("pass"))) System.out.println("  MISMATCH " + r.get("id") + ": " + r); }
    }

    static void link(Path link, Path target) throws IOException, InterruptedException {
        if (Files.exists(link)) return;
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toAbsolutePath().toString(), target.toAbsolutePath().toString()).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes(); p.waitFor();
            if (Files.exists(link)) return;
        }
        try { Files.createSymbolicLink(link, target.toAbsolutePath()); } catch (IOException e) { throw new IOException("cannot link " + link + " -> " + target + " (junction and symlink both failed)", e); }
    }

    static Map<String, Object> scenarioSpec(List<PhysicsScenarios.Scenario> scenarios) {
        Map<String, Object> root = new LinkedHashMap<>(); root.put("profile", "paper-26.2-124");
        List<Object> list = new ArrayList<>();
        for (PhysicsScenarios.Scenario sc : scenarios) {
            Map<String, Object> o = new LinkedHashMap<>(); o.put("id", sc.id()); o.put("group", sc.group());
            List<Object> blocks = new ArrayList<>(); for (PhysicsScenarios.Block b : sc.blocks()) { Map<String, Object> bo = new LinkedHashMap<>(); bo.put("x", b.x()); bo.put("y", b.y()); bo.put("z", b.z()); bo.put("state", b.state()); blocks.add(bo); } o.put("blocks", blocks);
            PhysicsScenarios.Start st = sc.start(); Map<String, Object> so = new LinkedHashMap<>();
            so.put("x", st.x()); so.put("y", st.y()); so.put("z", st.z()); so.put("vx", st.vx()); so.put("vy", st.vy()); so.put("vz", st.vz()); so.put("yaw", st.yaw()); so.put("onGround", st.onGround()); so.put("pose", st.pose().name());
            so.put("fallDistance", st.fallDistance()); so.put("stuckX", st.stuck().x()); so.put("stuckY", st.stuck().y()); so.put("stuckZ", st.stuck().z()); so.put("inPowderSnow", st.inPowderSnow()); so.put("noJumpDelay", st.noJumpDelay());
            so.put("support", st.support() == null ? null : List.of(st.support()[0], st.support()[1], st.support()[2])); so.put("sprinting", st.sprinting()); so.put("shift", st.shift()); o.put("start", so);
            Map<String, Object> attrs = new LinkedHashMap<>(); for (var e : new TreeMap<>(sc.attributes()).entrySet()) attrs.put(e.getKey(), e.getValue()); o.put("attributes", attrs);
            List<Object> effects = new ArrayList<>(); for (PhysicsScenarios.Effect e : sc.effects()) { Map<String, Object> eo = new LinkedHashMap<>(); eo.put("id", e.id()); eo.put("amplifier", e.amplifier()); effects.add(eo); } o.put("effects", effects);
            o.put("boots", sc.boots());
            List<Object> ticks = new ArrayList<>(); for (PhysicsScenarios.Tick t : sc.ticks()) { Map<String, Object> to = new LinkedHashMap<>(); to.put("f", t.f()); to.put("s", t.s()); to.put("jump", t.jump()); to.put("shift", t.shift()); to.put("sprint", t.sprint()); to.put("use", t.use()); to.put("yaw", t.yaw()); ticks.add(to); } o.put("ticks", ticks);
            list.add(o);
        }
        root.put("scenarios", list);
        return root;
    }
    static PhysicsState fromOracle(Map<String, Object> t) {
        Object sup = t.get("support");
        PhysicsState.Support support = sup == null ? new PhysicsState.Support(false, 0, 0, 0, MiniJson.bool(t.get("onGroundNoBlocks"))) : new PhysicsState.Support(true, MiniJson.integer(MiniJson.arr(sup).get(0)), MiniJson.integer(MiniJson.arr(sup).get(1)), MiniJson.integer(MiniJson.arr(sup).get(2)), MiniJson.bool(t.get("onGroundNoBlocks")));
        return new PhysicsState(new Vec3d(MiniJson.num(t.get("x")), MiniJson.num(t.get("y")), MiniJson.num(t.get("z"))), new Vec3d(MiniJson.num(t.get("vx")), MiniJson.num(t.get("vy")), MiniJson.num(t.get("vz"))),
            PlayerBox.Pose.valueOf((String) t.get("pose")), MiniJson.bool(t.get("onGround")), MiniJson.bool(t.get("hc")), MiniJson.bool(t.get("vc")), MiniJson.bool(t.get("vcb")), MiniJson.num(t.get("fall")),
            new Vec3d(MiniJson.num(t.get("stuckX")), MiniJson.num(t.get("stuckY")), MiniJson.num(t.get("stuckZ"))), MiniJson.bool(t.get("inPowderSnow")), support, MiniJson.integer(t.get("noJumpDelay")), PhysicsState.Locomotion.WALKING);
    }
    static List<String> diff(PhysicsState s, Map<String, Object> t) {
        List<String> d = new ArrayList<>();
        bits(d, "x", s.position().x(), MiniJson.num(t.get("x"))); bits(d, "y", s.position().y(), MiniJson.num(t.get("y"))); bits(d, "z", s.position().z(), MiniJson.num(t.get("z")));
        bits(d, "vx", s.velocity().x(), MiniJson.num(t.get("vx"))); bits(d, "vy", s.velocity().y(), MiniJson.num(t.get("vy"))); bits(d, "vz", s.velocity().z(), MiniJson.num(t.get("vz")));
        if (s.onGround() != MiniJson.bool(t.get("onGround"))) d.add("onGround " + s.onGround() + "!=" + t.get("onGround"));
        if (s.horizontalCollision() != MiniJson.bool(t.get("hc"))) d.add("horizontalCollision " + s.horizontalCollision() + "!=" + t.get("hc"));
        if (s.verticalCollision() != MiniJson.bool(t.get("vc"))) d.add("verticalCollision " + s.verticalCollision() + "!=" + t.get("vc"));
        if (s.verticalCollisionBelow() != MiniJson.bool(t.get("vcb"))) d.add("verticalCollisionBelow " + s.verticalCollisionBelow() + "!=" + t.get("vcb"));
        bits(d, "fallDistance", s.fallDistance(), MiniJson.num(t.get("fall")));
        bits(d, "stuckX", s.stuck().x(), MiniJson.num(t.get("stuckX"))); bits(d, "stuckY", s.stuck().y(), MiniJson.num(t.get("stuckY"))); bits(d, "stuckZ", s.stuck().z(), MiniJson.num(t.get("stuckZ")));
        if (!s.pose().name().equals(t.get("pose"))) d.add("pose " + s.pose() + "!=" + t.get("pose"));
        if (s.inPowderSnow() != MiniJson.bool(t.get("inPowderSnow"))) d.add("inPowderSnow " + s.inPowderSnow() + "!=" + t.get("inPowderSnow"));
        if (s.noJumpDelay() != MiniJson.integer(t.get("noJumpDelay"))) d.add("noJumpDelay " + s.noJumpDelay() + "!=" + t.get("noJumpDelay"));
        Object sup = t.get("support");
        String os = sup == null ? "none" : MiniJson.arr(sup).toString(), ss = s.support().present() ? List.of((long) s.support().x(), (long) s.support().y(), (long) s.support().z()).toString() : "none";
        if (!os.equals(ss)) d.add("support " + ss + "!=" + os);
        if (s.support().onGroundNoBlocks() != MiniJson.bool(t.get("onGroundNoBlocks"))) d.add("onGroundNoBlocks " + s.support().onGroundNoBlocks() + "!=" + t.get("onGroundNoBlocks"));
        return d;
    }
    private static void bits(List<String> d, String name, double a, double b) { if (Double.doubleToRawLongBits(a) != Double.doubleToRawLongBits(b)) d.add(name + " " + a + "!=" + b + (Math.abs(a - b) < 1e-12 ? " (bits)" : "")); }
    static double maxAbs(PhysicsState s, Map<String, Object> t) {
        double m = 0;
        m = Math.max(m, Math.abs(s.position().x() - MiniJson.num(t.get("x")))); m = Math.max(m, Math.abs(s.position().y() - MiniJson.num(t.get("y")))); m = Math.max(m, Math.abs(s.position().z() - MiniJson.num(t.get("z"))));
        m = Math.max(m, Math.abs(s.velocity().x() - MiniJson.num(t.get("vx")))); m = Math.max(m, Math.abs(s.velocity().y() - MiniJson.num(t.get("vy")))); m = Math.max(m, Math.abs(s.velocity().z() - MiniJson.num(t.get("vz"))));
        return m;
    }
    static String describe(PhysicsState s) { return String.format(Locale.ROOT, "pos=(%s,%s,%s) vel=(%s,%s,%s) ground=%s hc=%s fall=%s pose=%s stuck=%s support=%s njd=%d", s.position().x(), s.position().y(), s.position().z(), s.velocity().x(), s.velocity().y(), s.velocity().z(), s.onGround(), s.horizontalCollision(), s.fallDistance(), s.pose(), s.stuck(), s.support(), s.noJumpDelay()); }
    static String describeOracle(Map<String, Object> t) { return String.format(Locale.ROOT, "pos=(%s,%s,%s) vel=(%s,%s,%s) ground=%s hc=%s fall=%s pose=%s stuck=(%s,%s,%s) support=%s njd=%s", t.get("x"), t.get("y"), t.get("z"), t.get("vx"), t.get("vy"), t.get("vz"), t.get("onGround"), t.get("hc"), t.get("fall"), t.get("pose"), t.get("stuckX"), t.get("stuckY"), t.get("stuckZ"), t.get("support"), t.get("noJumpDelay")); }
}
