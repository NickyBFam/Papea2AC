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
    /** Phase gate: modules whose phase has not started stay empty. "world" left this list in Phase 3 (its approved phase); it is now held to the Phase 3 boundary below instead. */
    @Test void noFutureEngineOrForbiddenMathImplementation()throws Exception{
        for(String name:List.of("collision","physics","simulation","combat","checks","evidence","mitigation","interaction")){
            Path p=ROOT.resolve("anticheat-"+name+"/src/main/java");
            if(Files.exists(p))try(var files=Files.walk(p)){assertEquals(0,files.filter(f->f.toString().endsWith(".java")).count(),name);}
        }
    }
    /** Phase 3 boundary: the world module reconstructs observed state only - no collision, physics, movement validation, check logic, or engine-forbidden transcendental Math (COLLISION_ENGINE.md/NUMERICAL_POLICY). */
    @Test void worldModuleStaysWithinThePhase3Boundary()throws Exception{
        Path dir=ROOT.resolve("anticheat-world/src/main/java");
        var forbidden=Pattern.compile("\\b(AABB|boundingBox|BoundingBox|isOnGround|onGround|gravity|friction|velocity|Velocity|raytrace|RayQuery|MovementQuery|ShapeQuery|collide|Collision|violation|Violation|setback|punish|Speed|Fly|Timer|Reach|KillAura)\\b");
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
