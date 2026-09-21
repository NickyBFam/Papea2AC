package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.security.*;
import java.io.*;
import me.nicolas.papea2ac.model.*;
class ArchitectureTest {
    private static final Path ROOT=Path.of(System.getProperty("repo.root"));
    private static Map<String,List<String>> graph()throws IOException{
        String text=Files.readString(ROOT.resolve("docs/ARCHITECTURE.md")).split("\\| Module \\|")[1].split("This ordering")[0];
        Map<String,List<String>> graph=new LinkedHashMap<>();
        for(String row:text.split("\\R")){
            String[] c=row.split("\\|");if(c.length<4||c[1].strip().equals("---"))continue;
            String n=c[1].strip(),d=c[2].strip();
            graph.put(n,n.equals("testing")?new ArrayList<>(graph.keySet()):d.equals("none")?List.of():List.of(d.split(", ")));
        }
        return graph;
    }
    @Test void gradleGraphExactlyMatchesApprovedDirectDependencies()throws Exception{
        var graph=graph();assertEquals(20,graph.size());Set<String> earlier=new HashSet<>();
        for(var e:graph.entrySet()){
            assertTrue(earlier.containsAll(e.getValue()),e.getKey()+" cycle/forward edge");earlier.add(e.getKey());
            String script=Files.readString(ROOT.resolve("anticheat-"+e.getKey()+"/build.gradle.kts"));
            Matcher matcher=Pattern.compile("project\\(\":anticheat-([^\"]+)\"\\)").matcher(script);Set<String> actual=new HashSet<>();
            while(matcher.find())actual.add(matcher.group(1));
            assertEquals(new HashSet<>(e.getValue()),actual,e.getKey());
            if(e.getKey().equals("testing"))assertFalse(script.contains("\n    implementation("));
        }
    }
    @Test void importsRespectDirectEdgesAndPlatformIsolation()throws Exception{
        var graph=graph();
        for(var e:graph.entrySet()){
            Path dir=ROOT.resolve("anticheat-"+e.getKey()+"/src/main/java");if(!Files.exists(dir))continue;
            try(var files=Files.walk(dir)){
                for(Path file:files.filter(p->p.toString().endsWith(".java")).toList()){
                    String text=Files.readString(file);
                    if(!e.getKey().equals("platform-paper"))assertFalse(Pattern.compile("(org\\.bukkit|io\\.papermc|net\\.minecraft|io\\.netty)\\.").matcher(text).find(),file.toString());
                    Matcher imports=Pattern.compile("(?:import\\s+(?:static\\s+)?)me\\.nicolas\\.papea2ac\\.([a-z]+)\\.").matcher(text);
                    while(imports.find()){
                        String module=imports.group(1).equals("schema")?"replay-schema":imports.group(1);
                        assertTrue(module.equals(e.getKey())||e.getValue().contains(module),file+" -> "+module);
                    }
                }
            }
        }
    }
    @Test void publicApiAndModelAndSchemaAreLeaves()throws Exception{var g=graph();assertEquals(List.of(),g.get("api"));assertEquals(List.of(),g.get("model"));assertEquals(List.of(),g.get("replay-schema"));assertEquals(List.of("replay-schema"),g.get("replay"));}
    @Test void clearancePortNamesModelPayloadWithNoWorldDependency()throws Exception{
        assertEquals(DestinationRegion.class,SafeAnchorValidationPort.class.getMethod("validate",DestinationRegion.class,SafeAnchorValidationPort.Bounds.class).getParameterTypes()[0]);
        for(var c:DestinationRegion.class.getRecordComponents())assertTrue(c.getType().isPrimitive()||c.getType().getPackageName().startsWith("java."));
        assertFalse(Files.readString(ROOT.resolve("anticheat-model/build.gradle.kts")).contains("project("));
    }
    @Test void frozenSpecificationAndReviewsStillMatchBaseline()throws Exception{
        String manifest=Files.readString(ROOT.resolve("docs/review-artifacts/review2-manifest.json"));
        Matcher m=Pattern.compile("\"path\": \"([^\"]+)\",\\s*\"sha256\": \"([0-9a-f]+)\"").matcher(manifest);
        int count=0;
        while(m.find()){
            String path=m.group(1);if(!path.endsWith(".md"))continue;
            assertEquals(m.group(2),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(ROOT.resolve(path)))),path);count++;
        }
        assertEquals(27,count);
    }
    /** Phase gate: modules whose phase has not started stay empty. "world" left this list in Phase 3, "collision" in Phase 4, "physics" in Phase 5 and "simulation" in Phase 6 (their approved phases); each is held to its own boundary test below instead. */
    @Test void noFutureEngineOrForbiddenMathImplementation()throws Exception{
        for(String name:List.of("combat","checks","evidence","mitigation","interaction")){
            Path p=ROOT.resolve("anticheat-"+name+"/src/main/java");
            if(Files.exists(p))try(var files=Files.walk(p)){assertEquals(0,files.filter(f->f.toString().endsWith(".java")).count(),name);}
        }
    }
    /** Phase 4 boundary: the collision module is pure geometry over observer-world views - no physics (gravity/friction/velocity/step/movement validation), no checks, no live-world reads, no NMS/Bukkit, and no engine-forbidden transcendental Math (COLLISION_ENGINE.md / NUMERICAL_POLICY). */
    @Test void collisionModuleStaysWithinThePhase4Boundary()throws Exception{
        Path dir=ROOT.resolve("anticheat-collision/src/main/java");
        var forbidden=Pattern.compile("\\b(gravity|friction|Friction|velocity|Velocity|acceleration|drag|jump|Jump|stepUp|stepHeight|onGround|isOnGround|simulate|Simulation|predict|Prediction|VALID|INVALID|violation|Violation|setback|punish|Speed|Fly|Timer|Reach|KillAura|Scaffold|getBlockAt|getBlockState|getChunkAt|Bukkit|getHandle)\\b");
        var transcendental=Pattern.compile("(Strict)?Math\\.(sin|cos|tan|asin|acos|atan|atan2|exp|log|log10|pow|sqrt|cbrt|hypot|sinh|cosh|tanh)\\(");
        try(var files=Files.walk(dir)){
            var sources=files.filter(p->p.toString().endsWith(".java")).toList();
            assertTrue(sources.size()>=8,"Phase 4 collision module must exist");
            for(Path file:sources){
                String code=Files.readString(file).replaceAll("(?s)/\\*.*?\\*/","").replaceAll("//.*","");
                var m=forbidden.matcher(code);boolean found=m.find();assertFalse(found,file+" contains a Phase 5+ concept or a live-world access: "+(found?code.substring(Math.max(0,m.start()-20),Math.min(code.length(),m.end()+20)):""));
                assertFalse(transcendental.matcher(code).find(),file+" uses forbidden transcendental Math");
                assertFalse(code.contains("java.lang.reflect")||code.contains("Class.forName"),file+" uses reflection");
            }
        }
        // the shape table is data loaded from a resource, never NMS: the version module holds no platform import either (covered by importsRespectDirectEdgesAndPlatformIsolation)
        assertTrue(Files.exists(ROOT.resolve("anticheat-version/src/main/resources/me/nicolas/papea2ac/version/collision-shapes-paper-26.2-124.tsv")),"pinned shape table resource");
    }
    /** Phase 5 boundary: the physics module is a pure forward model over explicit inputs and observer-world views - no input inference or candidate search (Phase 6), no verdicts/violations/setbacks (Phase 8+), no live-world reads, no NMS/Bukkit, no reflection, no wall-clock or randomness (determinism), and no engine-forbidden transcendental Math (NUMERICAL_POLICY: sqrt is IEEE-exact and permitted, sin/cos go through the profile table). */
    @Test void physicsModuleStaysWithinThePhase5Boundary()throws Exception{
        Path dir=ROOT.resolve("anticheat-physics/src/main/java");
        var forbidden=Pattern.compile("\\b(VALID|INVALID|UNCERTAIN_SEARCH|violation|Violation|setback|punish|KillAura|Reach|Timer|cheat|Cheat|inferInput|reconstructInput|enumerateInputs|candidateInputs|witness|Witness|getBlockAt|getBlockState|getChunkAt|Bukkit|getHandle|nanoTime|currentTimeMillis|ThreadLocalRandom|SplittableRandom|Random)\\b");
        var transcendental=Pattern.compile("(Strict)?Math\\.(sin|cos|tan|asin|acos|atan|atan2|exp|log|log10|pow|cbrt|hypot|sinh|cosh|tanh)\\(");
        try(var files=Files.walk(dir)){
            var sources=files.filter(p->p.toString().endsWith(".java")).toList();
            assertTrue(sources.size()>=8,"Phase 5 physics module must exist");
            for(Path file:sources){
                String code=Files.readString(file).replaceAll("(?s)/\\*.*?\\*/","").replaceAll("//.*","");
                var m=forbidden.matcher(code);boolean found=m.find();assertFalse(found,file+" contains a Phase 6+ concept, a live-world access or a non-deterministic source: "+(found?code.substring(Math.max(0,m.start()-20),Math.min(code.length(),m.end()+20)):""));
                assertFalse(transcendental.matcher(code).find(),file+" uses forbidden transcendental Math");
                assertFalse(code.contains("java.lang.reflect")||code.contains("Class.forName"),file+" uses reflection");
            }
        }
        assertTrue(Files.exists(ROOT.resolve("anticheat-version/src/main/resources/me/nicolas/papea2ac/version/mth-sin-paper-26.2-124.bin")),"pinned sine table resource");
        assertTrue(Files.exists(ROOT.resolve("anticheat-version/src/main/resources/me/nicolas/papea2ac/version/block-movement-paper-26.2-124.tsv")),"pinned block movement table resource");
    }
    /** Phase 6 boundary: the simulation module reconstructs INPUT HYPOTHESES only - no verdicts (VALID/INVALID/CHEATING), no violations/alerts/setbacks/punishments (Phase 8+), no latency/ping/ACK/teleport timing logic (Phase 7), no live-world reads, no NMS/Bukkit, no reflection, no wall clock or randomness, no hash-order iteration over candidates, and no engine-forbidden transcendental Math. */
    @Test void simulationModuleStaysWithinThePhase6Boundary()throws Exception{
        Path dir=ROOT.resolve("anticheat-simulation/src/main/java");
        var forbidden=Pattern.compile("\\b(VALID|INVALID|SUSPICIOUS|CHEATING|cheat|Cheat|violation|Violation|violationLevel|alert|Alert|setback|Setback|punish|Punish|KillAura|Reach|Timer|ping|Ping|rtt|RTT|latency|Latency|acknowledg|Acknowledg|teleportConfirm|rewind|Rewind|beam|Beam|getBlockAt|getBlockState|getChunkAt|Bukkit|getHandle|nanoTime|currentTimeMillis|ThreadLocalRandom|SplittableRandom|Random|HashSet|HashMap)\\b");
        var transcendental=Pattern.compile("(Strict)?Math\\.(sin|cos|tan|asin|acos|atan|atan2|exp|log|log10|pow|cbrt|hypot|sinh|cosh|tanh)\\(");
        try(var files=Files.walk(dir)){
            var sources=files.filter(p->p.toString().endsWith(".java")&&!p.getFileName().toString().startsWith("Timing")&&!p.getFileName().toString().startsWith("MovementValidat")).toList(); // Timing* = Phase 7, MovementValidat* = Phase 8 (own boundaries below)
            assertTrue(sources.size()>=7,"Phase 6 simulation module must exist");
            for(Path file:sources){
                String code=Files.readString(file).replaceAll("(?s)/\\*.*?\\*/","").replaceAll("//.*","");
                var m=forbidden.matcher(code);boolean found=m.find();assertFalse(found,file+" contains a Phase 7/8 concept, a live-world access, a non-deterministic source or a hash-order collection: "+(found?code.substring(Math.max(0,m.start()-30),Math.min(code.length(),m.end()+30)):""));
                assertFalse(transcendental.matcher(code).find(),file+" uses forbidden transcendental Math");
                assertFalse(code.contains("java.lang.reflect")||code.contains("Class.forName"),file+" uses reflection");
            }
        }
        // the simulation module never re-implements a physics kernel: the impulse scaling and the input vector come from anticheat-physics
        String transfer=Files.readString(ROOT.resolve("anticheat-simulation/src/main/java/me/nicolas/papea2ac/simulation/AbstractTransfer.java"));
        assertTrue(transfer.contains("PhysicsKernels.clientMoveVector(")&&transfer.contains("simulator.inputVector("),"one authoritative input-processing implementation");
        assertFalse(transfer.contains("* 0.98")||transfer.contains("0.98F")||transfer.contains("0.2F"),"no duplicated input constants in the abstract lane");
    }
    /**
     * Phase 7 boundary: the timing layer (the Timing* files of anticheat-simulation - the approved DAG's "interval/constraint solver"
     * owner - and the player-owned ledgers) models WHEN, never whether: no verdict, violation, alert, setback or punishment vocabulary,
     * no Speed/Fly/Timer check, no "ping/2 = one-way delay" arithmetic, no wall clock, no Bukkit/NMS/Netty, no reflection, no
     * hash-order collections, and the physics question always goes through InputReconstructor (never a duplicated simulator call).
     */
    @Test void timingLayerStaysWithinThePhase7Boundary()throws Exception{
        var forbidden=Pattern.compile("\\b(VALID|INVALID|SUSPICIOUS|CHEATING|cheat|Cheat|violation|Violation|violationLevel|alert|Alert|setback|Setback|punish|Punish|KillAura|Reach|SpeedCheck|FlyCheck|TimerCheck|rewind|Rewind|beam|Beam|getBlockAt|getBlockState|getChunkAt|Bukkit|CraftBukkit|net\\.minecraft|io\\.netty|currentTimeMillis|Instant|LocalDateTime|Random|ThreadLocalRandom|HashMap|HashSet|nanoTime)\\b|(rtt|Rtt|RTT|ping|Ping)[A-Za-z]*\\s*/\\s*2\\b|oneWay|halfRtt");
        var transcendental=Pattern.compile("(Strict)?Math\\.(sin|cos|tan|asin|acos|atan|atan2|exp|log|log10|pow|cbrt|hypot|sinh|cosh|tanh)\\(");
        java.util.List<Path> files=new java.util.ArrayList<>();
        try(var s=Files.walk(ROOT.resolve("anticheat-simulation/src/main/java"))){s.filter(p->p.getFileName().toString().startsWith("Timing")).forEach(files::add);}
        for(String f:new String[]{"TimingEvent","TimingLedger","RttEstimator","MarkerLedger"})files.add(ROOT.resolve("anticheat-player/src/main/java/me/nicolas/papea2ac/player/"+f+".java"));
        files.add(ROOT.resolve("anticheat-version/src/main/java/me/nicolas/papea2ac/version/TimingProfile.java"));
        assertTrue(files.size()>=10,"Phase 7 timing files must exist: "+files);
        for(Path file:files){
            String code=Files.readString(file).replaceAll("(?s)/\\*.*?\\*/","").replaceAll("//.*","");
            var m=forbidden.matcher(code);boolean found=m.find();assertFalse(found,file+" contains a verdict/check concept, ping-halving, a wall clock, a platform type or a hash-order collection: "+(found?code.substring(Math.max(0,m.start()-30),Math.min(code.length(),m.end()+30)):""));
            assertFalse(transcendental.matcher(code).find(),file+" uses forbidden transcendental Math");
            assertFalse(code.contains("java.lang.reflect")||code.contains("Class.forName"),file+" uses reflection");
        }
        String adapter=Files.readString(ROOT.resolve("anticheat-simulation/src/main/java/me/nicolas/papea2ac/simulation/TimingReconstruction.java"));
        assertTrue(adapter.contains("reconstructor.reconstruct(")&&!adapter.contains(".simulate("),"the timing adapter asks InputReconstructor, never a simulator directly");
        // the timing layer stays out of the frozen Phase 6 engine: InputReconstructor does not know Timing types
        String engine=Files.readString(ROOT.resolve("anticheat-simulation/src/main/java/me/nicolas/papea2ac/simulation/InputReconstructor.java"));
        assertFalse(engine.contains("Timing"),"InputReconstructor is unchanged by Phase 7");
        // player's ledgers import nothing beyond model (approved DAG: player -> model, replay-schema)
        for(String f:new String[]{"TimingEvent","TimingLedger","RttEstimator","MarkerLedger"}){
            String code=Files.readString(ROOT.resolve("anticheat-player/src/main/java/me/nicolas/papea2ac/player/"+f+".java"));
            for(String line:code.split("\n"))if(line.startsWith("import me.nicolas."))assertTrue(line.startsWith("import me.nicolas.papea2ac.model.")||line.startsWith("import me.nicolas.papea2ac.player."),f+" imports outside player/model: "+line);
        }
    }
    /**
     * Phase 8 boundary: movement validation is owned by anticheat-simulation (MovementValidat* files: the only place VALID / UNCERTAIN /
     * INVALID are produced), composed by core (runner, context mapper, status records) and displayed by the platform. None of these
     * files knows a violation level, an alert, a setback, a punishment, a threshold, a "closest candidate", a cheat brand, a wall clock,
     * a platform type, reflection, a hash-order collection or a transcendental Math call; the validator consumes the Phase 7 adapter
     * ({@code TimingReconstruction.run}) and never re-implements physics, timing or reconstruction; the frozen Phase 6/7 engines do not
     * know the validation types; and the platform never derives a verdict of its own (it prints the validator's words).
     */
    @Test void movementValidationStaysWithinThePhase8Boundary()throws Exception{
        var forbidden=Pattern.compile("\\b(SUSPICIOUS|CHEATING|cheat|Cheat|violation|Violation|violationLevel|vl|VL|alert|Alert|setback|Setback|punish|Punish|kick|Kick|ban|Ban|flagged|Flagged|threshold|Threshold|closest|Closest|nearestIsValid|KillAura|SpeedCheck|FlyCheck|TimerCheck|Vape|Wurst|LiquidBounce|Meteor|Impact|rewind|Rewind|getBlockAt|getBlockState|getChunkAt|Bukkit|CraftBukkit|net\\.minecraft|io\\.netty|currentTimeMillis|Instant|LocalDateTime|Random|ThreadLocalRandom|HashMap|HashSet|nanoTime)\\b");
        var transcendental=Pattern.compile("(Strict)?Math\\.(sin|cos|tan|asin|acos|atan|atan2|exp|log|log10|pow|cbrt|hypot|sinh|cosh|tanh)\\(");
        java.util.List<Path> files=new java.util.ArrayList<>();
        try(var s=Files.walk(ROOT.resolve("anticheat-simulation/src/main/java"))){s.filter(p->p.getFileName().toString().startsWith("MovementValidat")).forEach(files::add);}
        for(String f:new String[]{"MovementValidationRunner","ContextObservationMapper","ValidationStatus","ValidationEngineStatus"})files.add(ROOT.resolve("anticheat-core/src/main/java/me/nicolas/papea2ac/core/"+f+".java"));
        for(String f:new String[]{"ContextEvent","MovementContextLedger"})files.add(ROOT.resolve("anticheat-player/src/main/java/me/nicolas/papea2ac/player/"+f+".java"));
        assertTrue(files.size()>=10,"Phase 8 validation files must exist: "+files);
        for(Path file:files){
            String code=Files.readString(file).replaceAll("(?s)/\\*.*?\\*/","").replaceAll("//.*","");
            var m=forbidden.matcher(code);boolean found=m.find();assertFalse(found,file+" contains an enforcement/threshold/brand concept, a wall clock, a platform type or a hash-order collection: "+(found?code.substring(Math.max(0,m.start()-30),Math.min(code.length(),m.end()+30)):""));
            assertFalse(transcendental.matcher(code).find(),file+" uses forbidden transcendental Math");
            assertFalse(code.contains("java.lang.reflect")||code.contains("Class.forName"),file+" uses reflection");
        }
        // the classification rule lives in the simulation module only: VALID/UNCERTAIN/INVALID are produced nowhere else
        String validator=Files.readString(ROOT.resolve("anticheat-simulation/src/main/java/me/nicolas/papea2ac/simulation/MovementValidator.java"));
        assertTrue(validator.contains("timing.run(")&&!validator.contains("reconstructor.reconstruct(")&&!validator.contains("new TimingResolver("),"the validator consumes the Phase 7 adapter and never re-runs timing or reconstruction itself");
        assertFalse(validator.contains("0.98")||validator.contains("0.08")||validator.contains("0.6F")||validator.contains("0.91"),"no duplicated physics constants in the validator");
        for(String f:new String[]{"anticheat-core/src/main/java/me/nicolas/papea2ac/core/MovementValidationRunner.java","anticheat-platform-paper/src/main/java/me/nicolas/papea2AC/Papea2AC.java"}){
            String code=Files.readString(ROOT.resolve(f)).replaceAll("(?s)/\\*.*?\\*/","").replaceAll("//.*","");
            assertFalse(code.contains("Classification.INVALID")&&code.contains("new MovementValidationResult("),f+" builds a verdict of its own");
            assertFalse(Pattern.compile("\\b(residual|excess)[A-Za-z]*\\s*[<>]=?\\s*[0-9]").matcher(code).find(),f+" compares a residual to a threshold");
        }
        // controlled injection is SIDE-CHAIN ONLY: the runner holds no ledger, timeline or world reference it could mutate (it receives published views), never calls apply(), and inject() is reachable only through PacketEngine.validationInject (the platform command), never from the validation path
        String runner=Files.readString(ROOT.resolve("anticheat-core/src/main/java/me/nicolas/papea2ac/core/MovementValidationRunner.java")).replaceAll("(?s)/\\*.*?\\*/","").replaceAll("//.*","");
        assertFalse(runner.contains(".apply(")||runner.contains("PlayerTimeline")||runner.contains("ObserverWorld")||runner.contains("TimingLedger timing;")||runner.contains("MovementContextLedger context;"),"the runner mutates nothing it does not own");
        assertFalse(runner.contains("inject(")&&runner.indexOf("inject(")!=runner.lastIndexOf("inject(")&&!runner.contains("public synchronized String inject("),"inject is a single public entry point");
        try(var s=Files.walk(ROOT.resolve("anticheat-core/src/main/java"))){for(Path p:s.filter(q->q.toString().endsWith(".java")&&!q.getFileName().toString().equals("MovementValidationRunner.java")).toList()){String c=Files.readString(p);assertFalse(c.contains(".inject(")&&!c.contains("validationInject"),p+" calls the injection harness outside the diagnostic entry point");}}
        // the frozen engines do not know the validation types
        for(String f:new String[]{"InputReconstructor","TimingReconstruction","TimingResolver","PhysicsSimulator"}){
            Path p=f.equals("PhysicsSimulator")?ROOT.resolve("anticheat-physics/src/main/java/me/nicolas/papea2ac/physics/PhysicsSimulator.java"):ROOT.resolve("anticheat-simulation/src/main/java/me/nicolas/papea2ac/simulation/"+f+".java");
            assertFalse(Files.readString(p).contains("MovementValidat"),f+" is independent of Phase 8");
        }
        // player's context ledger imports nothing beyond model/player (approved DAG)
        for(String f:new String[]{"ContextEvent","MovementContextLedger"}){
            String code=Files.readString(ROOT.resolve("anticheat-player/src/main/java/me/nicolas/papea2ac/player/"+f+".java"));
            for(String line:code.split("\n"))if(line.startsWith("import me.nicolas."))assertTrue(line.startsWith("import me.nicolas.papea2ac.model.")||line.startsWith("import me.nicolas.papea2ac.player."),f+" imports outside player/model: "+line);
        }
    }
    /** Phase 3 boundary: the world module reconstructs observed state only - no collision, physics, movement validation, check logic, or engine-forbidden transcendental Math (COLLISION_ENGINE.md/NUMERICAL_POLICY). */
    @Test void worldModuleStaysWithinThePhase3Boundary()throws Exception{
        Path dir=ROOT.resolve("anticheat-world/src/main/java");
        var forbidden=Pattern.compile("\\b(gravity|friction|Friction|velocity|Velocity|acceleration|drag|jump|Jump|stepUp|stepHeight|onGround|isOnGround|simulate|Simulation|predict|Prediction|VALID|INVALID|violation|Violation|setback|punish|Speed|Fly|Timer|Reach|KillAura|Scaffold|getBlockAt|getBlockState|getChunkAt|Bukkit|getHandle)\\b");
        var transcendental=Pattern.compile("Math\\.(sin|cos|tan|asin|acos|atan|atan2|exp|log|log10|pow|sqrt|cbrt|hypot|sinh|cosh|tanh)\\(");
        try(var files=Files.walk(dir)){
            var sources=files.filter(p->p.toString().endsWith(".java")).toList();
            assertTrue(sources.size()>0,"Phase 3 world module must exist");
            for(Path file:sources){
                String code=Files.readString(file).replaceAll("(?s)/\\*.*?\\*/","").replaceAll("//.*","");
                assertFalse(forbidden.matcher(code).find(),file+" contains a Phase 4+ concept");
                assertFalse(transcendental.matcher(code).find(),file+" uses forbidden transcendental Math");
            }
        }
    }
}
