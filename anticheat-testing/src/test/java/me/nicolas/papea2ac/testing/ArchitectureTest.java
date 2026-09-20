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
    /** Phase gate: modules whose phase has not started stay empty. "world" left this list in Phase 3 and "collision" in Phase 4 (their approved phases); each is held to its own boundary test below instead. */
    @Test void noFutureEngineOrForbiddenMathImplementation()throws Exception{
        for(String name:List.of("physics","simulation","combat","checks","evidence","mitigation","interaction")){
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
