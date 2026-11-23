package me.nicolas.papea2ac.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import me.nicolas.papea2ac.combat.geometry.PickMath;
import me.nicolas.papea2ac.version.MovementProfile;
import org.junit.jupiter.api.*;

/**
 * Phase 12 native differential of the attack-pick arithmetic ({@link PickMath}) against the pinned vanilla 26.2 CLIENT jar's own classes
 * ({@code AABB}, {@code Vec3}, {@code Mth}, loaded child-first from the client jar, sha256-checked) and, for the entity methods that cannot run
 * without the client's Entity class, against the Paper natives after proving the client and server method bodies are instruction-identical.
 * Every comparison is bit-exact (raw IEEE bits); no tolerance.
 *
 * <p>Case families: uniform random rays around random boxes; axis-parallel and near-threshold direction components (|d| around the 1e-7 face
 * tolerance, ±0.0); rays starting inside, on faces and on edges; grazing rays whose crossing coordinate is within one ulp of a face bound ±1e-7;
 * coordinates near ±3e7; tiny and huge boxes. Counts are printed with the {@code [phase12-geometry]} prefix and written to
 * {@code docs/phase12-artifacts/native-geometry-results.json}.
 */
class NativeReachGeometryTest {
    static final String CLIENT_SHA256 = "40896ee9f1e2bec3c934daac7e93d41e9e3d9c2f8ae0ca366d52ffbfd1afa290";
    static final List<String> CLIENT_FIRST = List.of("net.minecraft.world.phys.AABB", "net.minecraft.util.Mth", "net.minecraft.world.entity.EntityDimensions", "net.minecraft.world.entity.EntityAttachments", "net.minecraft.world.entity.EntityAttachment");
    static final int RANDOM_CASES = 400_000, ADVERSARIAL_CASES = 200_000, VIEW_CASES = 200_000, EYE_CASES = 100_000, DIM_CASES = 50_000;
    static final Map<String, Object> RESULTS = new LinkedHashMap<>();

    static ClassLoader client; static Path clientJar;
    static Class<?> cAabb, cVec; static Constructor<?> cAabbNew, cVecNew; static Field cvx, cvy, cvz, aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ;
    static Method cClip, cContains, cDistBox, cInflate, cExpand, cIntersects, cVecDist, cLerp, cSin, cCos;

    static final class ClientFirstLoader extends ClassLoader {
        private final JarFile jar;
        ClientFirstLoader(JarFile jar, ClassLoader parent) { super("vanilla-26.2-client-phase12", parent); this.jar = jar; }
        static boolean clientFirst(String name) { for (String c : CLIENT_FIRST) if (name.equals(c) || name.startsWith(c + "$")) return true; return false; }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!clientFirst(name)) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    ZipEntry e = jar.getEntry(name.replace('.', '/') + ".class");
                    if (e == null) throw new ClassNotFoundException(name + " (not in the client jar)");
                    try (InputStream in = jar.getInputStream(e)) { byte[] b = in.readAllBytes(); c = defineClass(name, b, 0, b.length); }
                    catch (IOException x) { throw new ClassNotFoundException(name, x); }
                }
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }

    static Path locateClientJar() {
        List<Path> candidates = new ArrayList<>();
        String property = System.getProperty("papea2ac.clientJar"); if (property != null) candidates.add(Path.of(property));
        candidates.add(Path.of("..", ".phase1-runtime", "client", "26.2.jar")); candidates.add(Path.of(".phase1-runtime", "client", "26.2.jar"));
        String appdata = System.getenv("APPDATA"); if (appdata != null) candidates.add(Path.of(appdata, ".minecraft", "versions", "26.2", "26.2.jar"));
        for (Path p : candidates) if (Files.isRegularFile(p)) return p;
        return null;
    }

    @BeforeAll static void bootstrap() throws Exception {
        NativePacketTest.bootstrap();
        clientJar = locateClientJar();
        Assumptions.assumeTrue(clientJar != null, "pinned vanilla 26.2 client jar not found (set -Dpapea2ac.clientJar or copy it to .phase1-runtime/client/26.2.jar)");
        assertEquals(CLIENT_SHA256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(clientJar))), "the pinned client jar");
        client = new ClientFirstLoader(new JarFile(clientJar.toFile()), NativeReachGeometryTest.class.getClassLoader());
        cAabb = client.loadClass("net.minecraft.world.phys.AABB"); cVec = client.loadClass("net.minecraft.world.phys.Vec3");
        Class<?> cMth = client.loadClass("net.minecraft.util.Mth");
        for (String c : CLIENT_FIRST) assertNotSame(Class.forName(c), client.loadClass(c), c + " must come from the client jar");
        cAabbNew = cAabb.getConstructor(double.class, double.class, double.class, double.class, double.class, double.class);
        cVecNew = cVec.getConstructor(double.class, double.class, double.class);
        cvx = cVec.getField("x"); cvy = cVec.getField("y"); cvz = cVec.getField("z");
        aMinX = cAabb.getField("minX"); aMinY = cAabb.getField("minY"); aMinZ = cAabb.getField("minZ"); aMaxX = cAabb.getField("maxX"); aMaxY = cAabb.getField("maxY"); aMaxZ = cAabb.getField("maxZ");
        cClip = cAabb.getMethod("clip", cVec, cVec); cContains = cAabb.getMethod("contains", double.class, double.class, double.class);
        cDistBox = cAabb.getMethod("distanceToSqr", cVec); cInflate = cAabb.getMethod("inflate", double.class, double.class, double.class);
        cExpand = cAabb.getMethod("expandTowards", double.class, double.class, double.class); cIntersects = cAabb.getMethod("intersects", cAabb);
        cVecDist = cVec.getMethod("distanceToSqr", cVec);
        cLerp = cMth.getMethod("lerp", double.class, double.class, double.class); cSin = cMth.getMethod("sin", double.class); cCos = cMth.getMethod("cos", double.class);
        RESULTS.put("clientJarSha256", CLIENT_SHA256);
    }

    @AfterAll static void writeResults() throws Exception {
        if (RESULTS.size() <= 1) return;
        Path out = Path.of("..", "docs", "phase12-artifacts", "native-geometry-results.json");
        if (!Files.isDirectory(out.getParent())) out = Path.of("docs", "phase12-artifacts", "native-geometry-results.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, EntityGeometryExtractMain.json(RESULTS, 0) + "\n");
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------------------------------
    static Object cBox(PickMath.Box b) throws Exception { return cAabbNew.newInstance(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()); }
    static double[] cBoxValues(Object a) throws Exception { return new double[]{aMinX.getDouble(a), aMinY.getDouble(a), aMinZ.getDouble(a), aMaxX.getDouble(a), aMaxY.getDouble(a), aMaxZ.getDouble(a)}; }
    static double[] boxValues(PickMath.Box b) { return new double[]{b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()}; }
    static Object cv(double x, double y, double z) throws Exception { return cVecNew.newInstance(x, y, z); }
    static boolean same(double a, double b) { return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b); }
    static boolean same(double[] a, double[] b) { if (a.length != b.length) return false; for (int i = 0; i < a.length; i++) if (!same(a[i], b[i])) return false; return true; }
    static double coord(Random r, double scale) { return (r.nextDouble() * 2 - 1) * scale; }
    /** Insertion-ordered result map (the artifact is byte-stable across JVM runs, unlike Map.of). */
    static Map<String, Object> ordered(Object... kv) { Map<String, Object> m = new LinkedHashMap<>(); for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]); return m; }

    /** One clip case through both implementations; returns null on agreement, else a description. */
    static String clipCase(PickMath.Box b, double fx, double fy, double fz, double tx, double ty, double tz) throws Exception {
        Optional<?> c = (Optional<?>) cClip.invoke(cBox(b), cv(fx, fy, fz), cv(tx, ty, tz));
        double t = PickMath.clip(b, fx, fy, fz, tx, ty, tz);
        if (c.isEmpty() != Double.isNaN(t)) return "presence differs: client " + c.isPresent() + " model t=" + t + " box " + b + " from (" + fx + "," + fy + "," + fz + ") to (" + tx + "," + ty + "," + tz + ")";
        if (c.isPresent()) {
            Object v = c.get(); double[] loc = PickMath.clipLocation(t, fx, fy, fz, tx, ty, tz);
            if (!same(loc, new double[]{cvx.getDouble(v), cvy.getDouble(v), cvz.getDouble(v)})) return "location differs: client (" + cvx.getDouble(v) + "," + cvy.getDouble(v) + "," + cvz.getDouble(v) + ") model " + Arrays.toString(loc);
            double cd = (double) cVecDist.invoke(cv(fx, fy, fz), v);
            if (!same(cd, PickMath.distanceToSqr(fx, fy, fz, loc[0], loc[1], loc[2]))) return "distance differs";
        }
        boolean in = (boolean) cContains.invoke(cBox(b), fx, fy, fz);
        if (in != b.contains(fx, fy, fz)) return "contains differs";
        double cdist = (double) cDistBox.invoke(cBox(b), cv(fx, fy, fz));
        if (!same(cdist, b.distanceToSqr(fx, fy, fz))) return "box distance differs";
        return null;
    }

    static PickMath.Box randomBox(Random r, double scale) {
        double x = coord(r, scale), y = coord(r, scale), z = coord(r, scale);
        double w = r.nextInt(8) == 0 ? r.nextDouble() * 1e-6 : r.nextDouble() * 4, h = r.nextInt(8) == 0 ? r.nextDouble() * 1e-6 : r.nextDouble() * 4;
        return PickMath.makeBoundingBox((float) w, (float) h, x, y, z);
    }

    // ---- tests --------------------------------------------------------------------------------------------------------------------------------
    @Test void clipMatchesTheClientOnRandomRays() throws Exception {
        Random r = new Random(0x12_0001L); int hits = 0, inside = 0, mismatches = 0; List<String> first = new ArrayList<>();
        for (int i = 0; i < RANDOM_CASES; i++) {
            double scale = switch (i % 4) { case 0 -> 8; case 1 -> 300; case 2 -> 30_000_000; default -> 2; };
            PickMath.Box b = randomBox(r, scale);
            double cx = (b.minX() + b.maxX()) / 2, cy = (b.minY() + b.maxY()) / 2, cz = (b.minZ() + b.maxZ()) / 2;
            double fx = cx + coord(r, 6), fy = cy + coord(r, 6), fz = cz + coord(r, 6);
            double len = r.nextDouble() * 9;
            double dx = coord(r, 1), dy = coord(r, 1), dz = coord(r, 1);
            // aim through the box half of the time
            if (r.nextBoolean()) { dx = cx + coord(r, 1.5) - fx; dy = cy + coord(r, 1.5) - fy; dz = cz + coord(r, 1.5) - fz; }
            String d = clipCase(b, fx, fy, fz, fx + dx * len, fy + dy * len, fz + dz * len);
            if (d != null) { mismatches++; if (first.size() < 5) first.add(d); }
            if (!Double.isNaN(PickMath.clip(b, fx, fy, fz, fx + dx * len, fy + dy * len, fz + dz * len))) hits++;
            if (b.contains(fx, fy, fz)) inside++;
        }
        RESULTS.put("clipRandom", ordered("cases", RANDOM_CASES, "hits", hits, "startInside", inside, "mismatches", mismatches));
        System.out.println("[phase12-geometry] clip random cases=" + RANDOM_CASES + " hits=" + hits + " inside=" + inside + " mismatches=" + mismatches);
        assertEquals(0, mismatches, String.join("\n", first));
    }

    @Test void clipMatchesTheClientOnAdversarialRays() throws Exception {
        Random r = new Random(0x12_0002L); int mismatches = 0, hits = 0; List<String> first = new ArrayList<>();
        double[] tiny = {0.0, -0.0, 1.0E-7, -1.0E-7, Math.nextUp(1.0E-7), Math.nextDown(1.0E-7), -Math.nextUp(1.0E-7), -Math.nextDown(1.0E-7), 2.0E-7, 1e-300, Double.MIN_VALUE};
        for (int i = 0; i < ADVERSARIAL_CASES; i++) {
            PickMath.Box b = randomBox(r, i % 3 == 0 ? 30_000_000 : 16);
            double[] lo = {b.minX(), b.minY(), b.minZ()}, hi = {b.maxX(), b.maxY(), b.maxZ()};
            double[] from = new double[3], dir = new double[3];
            int kind = i % 5;
            for (int a = 0; a < 3; a++) {
                double span = hi[a] - lo[a];
                from[a] = switch (kind) {
                    case 0 -> lo[a] - r.nextDouble() * 3;                                            // outside, below
                    case 1 -> r.nextBoolean() ? lo[a] : hi[a];                                         // exactly on a face plane
                    case 2 -> lo[a] + r.nextDouble() * span;                                           // inside
                    case 3 -> (r.nextBoolean() ? lo[a] : hi[a]) + (r.nextBoolean() ? 1 : -1) * 1.0E-7 * (1 + r.nextInt(3)); // near the tolerance band
                    default -> (r.nextBoolean() ? lo[a] - 1 : hi[a] + 1) + coord(r, 1e-9);
                };
                dir[a] = r.nextInt(3) == 0 ? tiny[r.nextInt(tiny.length)] : coord(r, 4);
            }
            // grazing: steer one crossing coordinate to land on a face bound +- 1e-7 +- ulp
            if (kind == 4 && Math.abs(dir[0]) > 1e-3) {
                double plane = dir[0] > 0 ? lo[0] : hi[0]; double s = (plane - from[0]) / dir[0];
                if (s > 0) { double target = (r.nextBoolean() ? hi[1] + 1.0E-7 : lo[1] - 1.0E-7); target = r.nextBoolean() ? Math.nextUp(target) : Math.nextDown(target); dir[1] = (target - from[1]) / s; }
            }
            double tx = from[0] + dir[0], ty = from[1] + dir[1], tz = from[2] + dir[2];
            String d = clipCase(b, from[0], from[1], from[2], tx, ty, tz);
            if (d != null) { mismatches++; if (first.size() < 5) first.add(d); }
            if (!Double.isNaN(PickMath.clip(b, from[0], from[1], from[2], tx, ty, tz))) hits++;
        }
        RESULTS.put("clipAdversarial", ordered("cases", ADVERSARIAL_CASES, "hits", hits, "mismatches", mismatches));
        System.out.println("[phase12-geometry] clip adversarial cases=" + ADVERSARIAL_CASES + " hits=" + hits + " mismatches=" + mismatches);
        assertEquals(0, mismatches, String.join("\n", first));
    }

    @Test void boxOperationsMatchTheClient() throws Exception {
        Random r = new Random(0x12_0003L); int mismatches = 0; List<String> first = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) {
            PickMath.Box b = randomBox(r, i % 2 == 0 ? 50 : 30_000_000), o = randomBox(r, 50);
            double ix = coord(r, 3), iy = coord(r, 3), iz = coord(r, 3);
            if (i % 7 == 0) { ix = r.nextBoolean() ? 0.0 : -0.0; }
            Object cb = cBox(b);
            if (!same(cBoxValues(cInflate.invoke(cb, ix, iy, iz)), boxValues(b.inflate(ix, iy, iz)))) { mismatches++; if (first.size() < 5) first.add("inflate " + b + " " + ix); }
            if (!same(cBoxValues(cExpand.invoke(cb, ix, iy, iz)), boxValues(b.expandTowards(ix, iy, iz)))) { mismatches++; if (first.size() < 5) first.add("expandTowards " + b + " " + ix); }
            if ((boolean) cIntersects.invoke(cb, cBox(o)) != b.intersects(o)) { mismatches++; if (first.size() < 5) first.add("intersects " + b + " " + o); }
            double l = (double) cLerp.invoke(null, 1.0, ix * 1e5, iy * 1e5);
            if (!same(l, PickMath.lerp(1.0, ix * 1e5, iy * 1e5))) { mismatches++; if (first.size() < 5) first.add("lerp"); }
        }
        RESULTS.put("boxOperations", ordered("cases", 100_000, "mismatches", mismatches));
        System.out.println("[phase12-geometry] box ops cases=100000 mismatches=" + mismatches);
        assertEquals(0, mismatches, String.join("\n", first));
    }

    @Test void pinnedSineTableIsTheClientsTable() throws Exception {
        MovementProfile p = MovementProfile.paper26_2(); int mismatches = 0;
        for (int i = 0; i < 65536; i++) {
            double d = i / 10430.378350470453 + 1e-9;
            if (Float.floatToRawIntBits((float) cSin.invoke(null, d)) != Float.floatToRawIntBits(p.sin(d))) mismatches++;
            if (Float.floatToRawIntBits((float) cCos.invoke(null, d)) != Float.floatToRawIntBits(p.cos(d))) mismatches++;
        }
        RESULTS.put("sineTable", ordered("entries", 65536, "mismatches", mismatches));
        assertEquals(0, mismatches);
    }

    /**
     * Entity methods: the client's {@code Entity.calculateViewVector}, {@code Entity.getEyePosition(float)}, {@code EntityDimensions.makeBoundingBox}
     * / {@code scale} bodies are instruction-identical to Paper's, so Paper's natives are the client's; they are then compared bit-exactly with
     * PickMath on an uninitialized Marker (fields set reflectively).
     */
    @Test void entityViewEyeAndDimensionsMatchTheNatives() throws Exception {
        Path serverJar = Path.of(Class.forName("net.minecraft.world.entity.Entity").getProtectionDomain().getCodeSource().getLocation().toURI());
        // The natives executed below (and the Paper Vec3 the client AABB runs against) must be the client's code: identical instruction lists.
        List<String> compared = new ArrayList<>();
        for (String[] m : new String[][]{
            {"net/minecraft/world/entity/Entity", "calculateViewVector", "(FF)Lnet/minecraft/world/phys/Vec3;"},
            {"net/minecraft/world/entity/Entity", "getEyePosition", "(F)Lnet/minecraft/world/phys/Vec3;"},
            {"net/minecraft/world/phys/Vec3", "<init>", "(DDD)V"},
            {"net/minecraft/world/phys/Vec3", "add", "(DDD)Lnet/minecraft/world/phys/Vec3;"},
            {"net/minecraft/world/phys/Vec3", "distanceToSqr", "(Lnet/minecraft/world/phys/Vec3;)D"}}) {
            assertEquals(body(clientJar, m[0], m[1], m[2]), body(serverJar, m[0], m[1], m[2]), m[0] + "." + m[1] + " differs between the client and Paper");
            compared.add(m[0].substring(m[0].lastIndexOf('/') + 1) + "." + m[1]);
        }
        // Recorded, not asserted: bodies Phase 12 reimplements from the client bytecode but never executes natively (Paper may compile them
        // differently; a difference here is a reason to re-read the client body, not a model error).
        Map<String, Boolean> documented = new LinkedHashMap<>();
        for (String[] m : new String[][]{
            {"net/minecraft/world/entity/LivingEntity", "getDimensions", "(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;"},
            {"net/minecraft/world/entity/LivingEntity", "getDefaultDimensions", "(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;"},
            {"net/minecraft/world/entity/projectile/ProjectileUtil", "getEntityHitResult", "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;"},
            {"net/minecraft/world/item/component/AttackRange", "isInRange", "(Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/ToDoubleFunction;D)Z"}})
            documented.put(m[0].substring(m[0].lastIndexOf('/') + 1) + "." + m[1], body(clientJar, m[0], m[1], m[2]).equals(body(serverJar, m[0], m[1], m[2])));
        RESULTS.put("documentedBodiesIdentical", documented);
        // Paper natives on an uninitialized concrete entity (no constructor ran; only the fields the methods read are set)
        Class<?> marker = Class.forName("net.minecraft.world.entity.Marker"), entity = Class.forName("net.minecraft.world.entity.Entity"), vec3 = Class.forName("net.minecraft.world.phys.Vec3");
        Field unsafeF = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe"); unsafeF.setAccessible(true); Object unsafe = unsafeF.get(null);
        Object e = unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, marker);
        Method calc = entity.getDeclaredMethod("calculateViewVector", float.class, float.class); calc.setAccessible(true);
        Method eye = entity.getMethod("getEyePosition", float.class);
        Field xo = entity.getDeclaredField("xo"), yo = entity.getDeclaredField("yo"), zo = entity.getDeclaredField("zo"), pos = entity.getDeclaredField("position"), eyeH = entity.getDeclaredField("eyeHeight");
        for (Field f : List.of(xo, yo, zo, pos, eyeH)) f.setAccessible(true);
        Field vx = vec3.getField("x"), vy = vec3.getField("y"), vz = vec3.getField("z"); Constructor<?> vNew = vec3.getConstructor(double.class, double.class, double.class);
        MovementProfile p = MovementProfile.paper26_2(); Random r = new Random(0x12_0004L); int viewMismatches = 0, eyeMismatches = 0;
        for (int i = 0; i < VIEW_CASES; i++) {
            float pitch = switch (i % 5) { case 0 -> (float) coord(r, 90); case 1 -> (float) coord(r, 1000); case 2 -> r.nextBoolean() ? 90f : -90f; case 3 -> r.nextBoolean() ? 0f : -0f; default -> Float.intBitsToFloat(r.nextInt()) % 400f; };
            float yaw = switch (i % 4) { case 0 -> (float) coord(r, 180); case 1 -> (float) coord(r, 1e6); case 2 -> (float) (r.nextInt(8) * 45.0); default -> Math.nextUp((float) coord(r, 360)); };
            if (!Float.isFinite(pitch)) pitch = 0f;
            Object v = calc.invoke(e, pitch, yaw); double[] m = PickMath.viewVector(p, pitch, yaw);
            if (!same(m, new double[]{vx.getDouble(v), vy.getDouble(v), vz.getDouble(v)})) { viewMismatches++; }
        }
        for (int i = 0; i < EYE_CASES; i++) {
            double scale = i % 2 == 0 ? 300 : 30_000_000;
            double x = coord(r, scale), y = coord(r, 320), z = coord(r, scale);
            double ox = x - coord(r, 0.7), oy = y - coord(r, 1.5), oz = z - coord(r, 0.7);
            float eh = new float[]{1.62f, 1.27f, 0.4f, 0.2f, 1.62f * 0.0625f, 1.62f * 16f}[i % 6];
            xo.setDouble(e, ox); yo.setDouble(e, oy); zo.setDouble(e, oz); pos.set(e, vNew.newInstance(x, y, z)); eyeH.setFloat(e, eh);
            Object v = eye.invoke(e, 1.0F); double[] m = PickMath.eyePosition(ox, oy, oz, x, y, z, eh);
            if (!same(m, new double[]{vx.getDouble(v), vy.getDouble(v), vz.getDouble(v)})) eyeMismatches++;
        }
        // dimensions: the client's own EntityDimensions (child-first) for scale and makeBoundingBox
        Class<?> dims = client.loadClass("net.minecraft.world.entity.EntityDimensions"); // the client's own class (child-first)
        Method scalable = dims.getMethod("scalable", float.class, float.class), fixedM = dims.getMethod("fixed", float.class, float.class), withEye = dims.getMethod("withEyeHeight", float.class);
        Method make = dims.getMethod("makeBoundingBox", double.class, double.class, double.class), scaleM = dims.getMethod("scale", float.class, float.class);
        Method dw = dims.getMethod("width"), dh = dims.getMethod("height"), de = dims.getMethod("eyeHeight");
        Class<?> aabb = cAabb;
        Field bx0 = aabb.getField("minX"), by0 = aabb.getField("minY"), bz0 = aabb.getField("minZ"), bx1 = aabb.getField("maxX"), by1 = aabb.getField("maxY"), bz1 = aabb.getField("maxZ");
        int dimMismatches = 0;
        for (int i = 0; i < DIM_CASES; i++) {
            float w = (float) (r.nextDouble() * 4), h = (float) (r.nextDouble() * 4), eh = (float) (r.nextDouble() * h); boolean fx = i % 5 == 0;
            Object d = withEye.invoke(fx ? fixedM.invoke(null, w, h) : scalable.invoke(null, w, h), eh);
            float s = new float[]{1f, 0.5f, 0.0625f, 16f, (float) (0.0625 + r.nextDouble() * 15.9375)}[i % 5];
            Object ds = scaleM.invoke(d, s, s);
            float[] ms = PickMath.scale(w, h, eh, fx, s, s);
            if (Float.floatToRawIntBits((float) dw.invoke(ds)) != Float.floatToRawIntBits(ms[0]) || Float.floatToRawIntBits((float) dh.invoke(ds)) != Float.floatToRawIntBits(ms[1]) || Float.floatToRawIntBits((float) de.invoke(ds)) != Float.floatToRawIntBits(ms[2])) dimMismatches++;
            double x = coord(r, 30_000_000), y = coord(r, 320), z = coord(r, 30_000_000);
            Object bb = make.invoke(ds, x, y, z); PickMath.Box mb = PickMath.makeBoundingBox(ms[0], ms[1], x, y, z);
            if (!same(new double[]{bx0.getDouble(bb), by0.getDouble(bb), bz0.getDouble(bb), bx1.getDouble(bb), by1.getDouble(bb), bz1.getDouble(bb)}, boxValues(mb))) dimMismatches++;
        }
        RESULTS.put("entityNatives", ordered("identicalBodies", compared, "viewCases", VIEW_CASES, "viewMismatches", viewMismatches, "eyeCases", EYE_CASES, "eyeMismatches", eyeMismatches, "dimensionCases", DIM_CASES, "dimensionMismatches", dimMismatches));
        System.out.println("[phase12-geometry] identical bodies=" + compared.size() + " view=" + VIEW_CASES + "/" + viewMismatches + " eye=" + EYE_CASES + "/" + eyeMismatches + " dims=" + DIM_CASES + "/" + dimMismatches);
        assertEquals(0, viewMismatches); assertEquals(0, eyeMismatches); assertEquals(0, dimMismatches);
    }

    /** A method body as its instruction list, with constant-pool references resolved symbolically (indices differ between jars). */
    static String body(Path jar, String owner, String name, String desc) throws Exception {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry en = z.getEntry(owner + ".class"); assertNotNull(en, owner + " in " + jar);
            byte[] b; try (InputStream in = z.getInputStream(en)) { b = in.readAllBytes(); }
            ClassModel cm = ClassFile.of().parse(b);
            for (MethodModel m : cm.methods()) {
                if (!m.methodName().stringValue().equals(name) || !m.methodType().stringValue().equals(desc)) continue;
                StringBuilder s = new StringBuilder();
                for (CodeElement ce : m.code().orElseThrow()) if (ce instanceof Instruction i) s.append(i).append('\n');
                return s.toString();
            }
        }
        fail(owner + "." + name + desc + " not found in " + jar);
        return null;
    }
}
