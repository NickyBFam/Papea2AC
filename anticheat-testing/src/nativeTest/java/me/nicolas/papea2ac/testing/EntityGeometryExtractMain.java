package me.nicolas.papea2ac.testing;

import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;

/**
 * Phase 12 controlled, version-pinned extraction of vanilla entity attack geometry (COMBAT_MODEL.md B1 "version-specific eye height, pose,
 * target interpolation, dimensions, interaction attributes"; VERSION_SUPPORT.md "entity dimensions/poses ... interaction reach"). Runs ONLY on
 * the nativeTest classpath against the pinned Paper 26.2 build 124 server, and reads the pinned vanilla 26.2 client jar's bytecode (never
 * loads it); production loads the generated resource and never touches NMS.
 *
 * <p>Two sources, each for what it is authoritative on:
 * <ul>
 * <li><b>Runtime registry (Paper natives, after Bootstrap):</b> every {@code BuiltInRegistries.ENTITY_TYPE} entry's {@code EntityDimensions}
 *     (width, height, eye height, fixed), category, update interval and tracking range; the avatar pose table {@code Avatar.POSES} and
 *     {@code LivingEntity.SLEEPING_DIMENSIONS}; the {@code entity_interaction_range} / {@code block_interaction_range} / {@code scale}
 *     attribute default and bounds; the creative interaction-range modifier.</li>
 * <li><b>Class bytecode of BOTH jars (java.lang.classfile, no loading):</b> the type -> class binding from {@code EntityTypes.<clinit>}
 *     (the {@code <init>} handle of each registration's factory), and, along each class's superclass chain up to {@code Entity}, the nearest
 *     declaration of every method that changes attack geometry: pick radius, pick-from-inside, pickability, dimensions / default dimensions /
 *     age scale / bounding box construction, and the interpolation handler (with its step count). The client jar is authoritative for what the
 *     client picks; the server jar for what Paper checks. A disagreement between the two is recorded, never resolved silently.</li>
 * </ul>
 *
 * Usage: {@code EntityGeometryExtractMain <table-out> <summary-json-out> <client-jar>}
 */
public final class EntityGeometryExtractMain {
    static final String PROFILE = "paper-26.2-124";
    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String CLIENT_JAR_SHA256 = "40896ee9f1e2bec3c934daac7e93d41e9e3d9c2f8ae0ca366d52ffbfd1afa290";
    /** Geometry-relevant methods: key = name + descriptor, value = short label used in the table. */
    static final Map<String, String> WATCHED = new LinkedHashMap<>();
    static {
        WATCHED.put("getPickRadius()F", "pickRadius");
        WATCHED.put("canBePickedFromInside()Z", "pickInside");
        WATCHED.put("isPickable()Z", "pickable");
        WATCHED.put("getDimensions(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;", "dimensions");
        WATCHED.put("getDefaultDimensions(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;", "defaultDimensions");
        WATCHED.put("getAgeScale()F", "ageScale");
        WATCHED.put("makeBoundingBox(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/AABB;", "boundingBox");
        WATCHED.put("refreshDimensions()V", "refreshDimensions");
        WATCHED.put("getInterpolation()Lnet/minecraft/world/entity/InterpolationHandler;", "interpolation");
        WATCHED.put("isAttackable()Z", "attackable");
        WATCHED.put("skipAttackInteraction(Lnet/minecraft/world/entity/Entity;)Z", "skipAttack");
        WATCHED.put("isBaby()Z", "isBaby");
        WATCHED.put("canBeABaby()Z", "canBeABaby");
        WATCHED.put("sanitizeScale(F)F", "sanitizeScale");
    }
    static final String DIMENSIONS_DESC = "Lnet/minecraft/world/entity/EntityDimensions;";

    /** What one class declares (only the watched methods), plus its interpolation-handler constructions. */
    record ClassFacts(String name, String superName, Map<String, String> declared, List<Integer> interpolationSteps, List<String> interpolationLengthCalls, byte[] digestInput) {}

    public static void main(String[] args) throws Exception {
        Path tableOut = Path.of(args[0]), summaryOut = Path.of(args[1]), clientJar = Path.of(args[2]);
        String clientSha = sha256(Files.readAllBytes(clientJar));
        if (!CLIENT_JAR_SHA256.equals(clientSha)) throw new IllegalStateException("client jar is not the pinned 26.2 client: " + clientSha);
        NativePacketTest.bootstrap();
        Path serverJar = Path.of(Class.forName("net.minecraft.world.entity.Entity").getProtectionDomain().getCodeSource().getLocation().toURI());

        Map<String, ClassFacts> client = scan(clientJar), server = scan(serverJar);
        Map<String, String> clientTypes = typeBindings(clientJar, true), serverTypes = typeBindings(serverJar, false);

        Object registry = Class.forName("net.minecraft.core.registries.BuiltInRegistries").getField("ENTITY_TYPE").get(null);
        Method getKey = Class.forName("net.minecraft.core.Registry").getMethod("getKey", Object.class);
        Class<?> typeC = Class.forName("net.minecraft.world.entity.EntityType"), dimC = Class.forName("net.minecraft.world.entity.EntityDimensions");
        Method getDimensions = typeC.getMethod("getDimensions"), getCategory = typeC.getMethod("getCategory"), updateInterval = typeC.getMethod("updateInterval"), trackingRange = typeC.getMethod("clientTrackingRange");
        Method width = dimC.getMethod("width"), height = dimC.getMethod("height"), eyeHeight = dimC.getMethod("eyeHeight"), fixed = dimC.getMethod("fixed");
        Map<String, Object> byName = new TreeMap<>();
        for (Object t : (Iterable<?>) registry) byName.put(getKey.invoke(registry, t).toString(), t);

        StringBuilder table = new StringBuilder();
        Map<String, Object> summary = new LinkedHashMap<>();
        List<String> disagreements = new ArrayList<>(), unbound = new ArrayList<>(), variable = new ArrayList<>(), pickInflated = new ArrayList<>(), snapping = new ArrayList<>();
        Map<String, Integer> stepsHistogram = new TreeMap<>(), dimsHistogram = new TreeMap<>();
        List<String> typeLines = new ArrayList<>();
        for (var e : byName.entrySet()) {
            String id = e.getKey(); Object t = e.getValue(); Object d = getDimensions.invoke(t);
            String cls = clientTypes.get(id), scls = serverTypes.get(id);
            if (cls == null) { unbound.add(id); cls = "?"; }
            if (scls != null && !scls.equals(cls)) disagreements.add(id + ": client class " + cls + " vs server class " + scls);
            Map<String, String> resolvedClient = cls.equals("?") ? Map.of() : resolve(client, cls), resolvedServer = scls == null ? Map.of() : resolve(server, scls);
            for (String label : WATCHED.values()) if (!Objects.equals(resolvedClient.get(label), resolvedServer.get(label)) && !cls.equals("?")) disagreements.add(id + ": " + label + " client " + resolvedClient.get(label) + " vs server " + resolvedServer.get(label));
            String steps = interpolation(client, cls, resolvedClient);
            stepsHistogram.merge(steps, 1, Integer::sum);
            if (steps.equals("snap")) snapping.add(id);
            String dims = cls.equals("?") ? "VARIABLE:unbound" : dimensionClass(client, cls, resolvedClient);
            if (dims.startsWith("VARIABLE")) variable.add(id + " (" + dims.substring(dims.indexOf(':') + 1) + ")");
            dimsHistogram.merge(dims.contains(":") && !dims.startsWith("BABY") ? dims.substring(0, dims.indexOf(':')) : dims.startsWith("BABY") ? "BABY" : dims, 1, Integer::sum);
            String pick = pickRadius(client, resolvedClient.get("pickRadius"));
            if (!pick.equals("0.0")) pickInflated.add(id + "=" + pick);
            StringBuilder line = new StringBuilder("T ").append(id).append(' ').append(cls.replace('/', '.'))
                .append(' ').append(Float.floatToRawIntBits((float) width.invoke(d))).append(' ').append(Float.floatToRawIntBits((float) height.invoke(d)))
                .append(' ').append(Float.floatToRawIntBits((float) eyeHeight.invoke(d))).append(' ').append((boolean) fixed.invoke(d) ? "fixed" : "scalable")
                .append(' ').append(getCategory.invoke(t)).append(' ').append(updateInterval.invoke(t)).append(' ').append(trackingRange.invoke(t))
                .append(" pick=").append(pick).append(" steps=").append(steps).append(" dims=").append(dims)
                .append(" isPickable=").append(booleanFact(client, resolvedClient.get("pickable"), "pickable")).append(" pickableFromInside=").append(booleanFact(client, resolvedClient.get("pickInside"), "pickInside"));
            for (var w : WATCHED.values()) { String decl = resolvedClient.get(w); line.append(' ').append(w).append('=').append(decl == null ? "-" : simple(decl)); }
            line.append(" # w=").append((float) width.invoke(d)).append(" h=").append((float) height.invoke(d)).append(" eye=").append((float) eyeHeight.invoke(d));
            typeLines.add(line.toString());
        }

        // avatar (player) pose table and the living sleeping box, from the runtime statics
        Class<?> avatar = Class.forName("net.minecraft.world.entity.Avatar"), living = Class.forName("net.minecraft.world.entity.LivingEntity");
        Field posesF = avatar.getDeclaredField("POSES"); posesF.setAccessible(true);
        Field standingF = avatar.getDeclaredField("STANDING_DIMENSIONS"); standingF.setAccessible(true);
        Field sleepingF = living.getDeclaredField("SLEEPING_DIMENSIONS"); sleepingF.setAccessible(true);
        Map<String, Object> poses = new TreeMap<>();
        for (var en : ((Map<?, ?>) posesF.get(null)).entrySet()) poses.put(en.getKey().toString(), en.getValue());
        List<String> poseLines = new ArrayList<>();
        poseLines.add(poseLine("AVATAR_STANDING", standingF.get(null), width, height, eyeHeight, fixed));
        for (var en : poses.entrySet()) poseLines.add(poseLine("AVATAR " + en.getKey(), en.getValue(), width, height, eyeHeight, fixed));
        poseLines.add(poseLine("LIVING_SLEEPING", sleepingF.get(null), width, height, eyeHeight, fixed));

        // interaction attributes (defaults and ranged bounds) and the creative modifier
        List<String> attrLines = new ArrayList<>();
        Class<?> attrs = Class.forName("net.minecraft.world.entity.ai.attributes.Attributes"), holderC = Class.forName("net.minecraft.core.Holder"), rangedC = Class.forName("net.minecraft.world.entity.ai.attributes.RangedAttribute");
        for (String a : List.of("ENTITY_INTERACTION_RANGE", "BLOCK_INTERACTION_RANGE", "SCALE")) {
            Object attr = holderC.getMethod("value").invoke(attrs.getField(a).get(null));
            double def = (double) attr.getClass().getMethod("getDefaultValue").invoke(attr);
            double min = rangedC.isInstance(attr) ? (double) rangedC.getMethod("getMinValue").invoke(attr) : Double.NaN, max = rangedC.isInstance(attr) ? (double) rangedC.getMethod("getMaxValue").invoke(attr) : Double.NaN;
            attrLines.add("A " + a + " " + Double.doubleToRawLongBits(def) + " " + Double.doubleToRawLongBits(min) + " " + Double.doubleToRawLongBits(max) + " # default=" + def + " min=" + min + " max=" + max);
        }
        Class<?> player = Class.forName("net.minecraft.world.entity.player.Player");
        float creative = player.getField("CREATIVE_ENTITY_INTERACTION_RANGE_MODIFIER_VALUE").getFloat(null);
        attrLines.add("M CREATIVE_ENTITY_INTERACTION_RANGE " + Float.floatToRawIntBits(creative) + " # " + creative);

        table.append("# papea2ac entity attack geometry table\n# profile ").append(PROFILE).append('\n');
        table.append("# source runtime: BuiltInRegistries.ENTITY_TYPE / Avatar.POSES / LivingEntity.SLEEPING_DIMENSIONS / Attributes of the pinned Paper 26.2 build 124 server (Mojang mappings)\n");
        table.append("# source bytecode: vanilla 26.2 client jar sha256 ").append(clientSha).append(" (type->class from EntityTypes.<clinit>; nearest declaring class per watched method)\n");
        table.append("# format T <type> <client class> <width float bits> <height float bits> <eye float bits> fixed|scalable <category> <updateInterval> <trackingRange> pick=<radius|dynamic> steps=<n|snap|dynamic> <watched>=<declaring class|-> ... # human readable\n");
        table.append("# format P <pose key> <width bits> <height bits> <eye bits> fixed|scalable | A <attribute> <default bits> <min bits> <max bits> | M <modifier> <float bits>\n");
        for (String s : typeLines) table.append(s).append('\n');
        for (String s : poseLines) table.append(s).append('\n');
        for (String s : attrLines) table.append(s).append('\n');
        byte[] bytes = table.toString().getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(tableOut.getParent()); Files.write(tableOut, bytes);
        summary.put("profile", PROFILE); summary.put("clientJarSha256", clientSha); summary.put("serverJarSha256", sha256(Files.readAllBytes(serverJar)));
        summary.put("entityTypes", typeLines.size()); summary.put("unboundTypes", unbound); summary.put("clientServerDisagreements", disagreements);
        summary.put("variableDimensionTypes", variable); summary.put("pickRadiusNonZero", pickInflated); summary.put("snapWithoutInterpolation", snapping);
        summary.put("interpolationStepsHistogram", stepsHistogram); summary.put("dimensionClassHistogram", dimsHistogram); summary.put("avatarPoses", poseLines.size() - 2);
        summary.put("tableSha256", sha256(bytes)); summary.put("tableBytes", bytes.length);
        Files.createDirectories(summaryOut.getParent()); Files.writeString(summaryOut, json(summary, 0) + "\n");
        System.out.println("types=" + typeLines.size() + " unbound=" + unbound + " disagreements=" + disagreements.size() + " variable=" + variable.size() + " pick=" + pickInflated + " steps=" + stepsHistogram + " sha256=" + sha256(bytes));
    }

    static String poseLine(String key, Object d, Method w, Method h, Method e, Method f) throws Exception {
        return "P " + key + " " + Float.floatToRawIntBits((float) w.invoke(d)) + " " + Float.floatToRawIntBits((float) h.invoke(d)) + " " + Float.floatToRawIntBits((float) e.invoke(d)) + " " + ((boolean) f.invoke(d) ? "fixed" : "scalable")
            + " # w=" + w.invoke(d) + " h=" + h.invoke(d) + " eye=" + e.invoke(d);
    }

    static final Set<String> PLAIN_BASES = Set.of("net/minecraft/world/entity/Entity", "net/minecraft/world/entity/LivingEntity");

    /**
     * How the client computes the type's bounding-box dimensions, from the declarations along its class chain:
     * <ul>
     * <li>{@code PLAIN} — type dimensions, scaled by the {@code scale} attribute for living entities (LivingEntity.getDimensions) and by
     *     the age scale only when the type can be a baby (it cannot: isBaby is LivingEntity's constant false);</li>
     * <li>{@code AVATAR} — the Avatar pose table (players);</li>
     * <li>{@code BABY:<w>,<h>,<eye>,<fixed>,<dataId>} — every override is the baby switch: adult = type dimensions, baby = the nearest
     *     switch's BABY_DIMENSIONS, chosen by the synced boolean with data id {@code dataId};</li>
     * <li>{@code VARIABLE:<reason>} — anything else (unsupported until modelled; never INVALID).</li>
     * </ul>
     */
    static String dimensionClass(Map<String, ClassFacts> facts, String cls, Map<String, String> resolved) throws Exception {
        if (!PLAIN_BASES.contains(resolved.get("dimensions"))) return "VARIABLE:getDimensions@" + simple(resolved.get("dimensions"));
        if (!ENTITY.equals(resolved.get("boundingBox"))) return "VARIABLE:makeBoundingBox@" + simple(resolved.get("boundingBox"));
        if (!ENTITY.equals(resolved.get("refreshDimensions"))) return "VARIABLE:refreshDimensions@" + simple(resolved.get("refreshDimensions"));
        String age = resolved.get("ageScale"); if (age != null && !age.equals("net/minecraft/world/entity/LivingEntity")) return "VARIABLE:getAgeScale@" + simple(age);
        String san = resolved.get("sanitizeScale"); if (san != null && !san.equals("net/minecraft/world/entity/LivingEntity")) return "VARIABLE:sanitizeScale@" + simple(san);
        // walk the getDefaultDimensions declarations nearest-first
        String nearestSwitch = null, c = cls; boolean avatar = false;
        while (c != null) {
            ClassFacts f = facts.get(c);
            String v = f.declared().get("defaultDimensions");
            if (v != null) {
                if (v.startsWith("babySwitch:")) { if (nearestSwitch == null) nearestSwitch = v.substring("babySwitch:".length()); }
                else if (c.equals("net/minecraft/world/entity/Avatar")) { avatar = true; break; }
                else if (c.equals("net/minecraft/world/entity/LivingEntity")) break;
                else return "VARIABLE:getDefaultDimensions@" + simple(c);
            }
            if (c.equals(ENTITY)) break;
            c = f.superName();
        }
        if (avatar) return nearestSwitch == null ? "AVATAR" : "VARIABLE:babySwitchOverAvatar";
        boolean living = "net/minecraft/world/entity/LivingEntity".equals(resolved.get("dimensions"));
        if (nearestSwitch == null) return living ? "PLAIN_LIVING" : "PLAIN";
        // the switch only fires when isBaby can be true; find the synced flag
        String babyDecl = resolved.get("isBaby"), flagField;
        if (babyDecl == null || babyDecl.equals("net/minecraft/world/entity/LivingEntity")) return living ? "PLAIN_LIVING" : "PLAIN"; // LivingEntity.isBaby is constant false
        if (babyDecl.equals("net/minecraft/world/entity/AgeableMob")) {
            String can = facts.get(resolved.get("canBeABaby")).declared().get("canBeABaby");
            if ("const:false".equals(can)) return living ? "PLAIN_LIVING" : "PLAIN";
            if (!"const:true".equals(can)) return "VARIABLE:canBeABaby@" + simple(resolved.get("canBeABaby"));
            flagField = "net/minecraft/world/entity/AgeableMob.DATA_BABY_ID"; // client getAge(): DATA_BABY_ID ? -1 : 1 (javap AgeableMob.getAge bc 0-34)
        } else {
            String v = facts.get(babyDecl).declared().get("isBaby");
            if (v == null || !v.startsWith("data:")) return "VARIABLE:isBaby@" + simple(babyDecl);
            flagField = v.substring("data:".length());
        }
        Object baby = staticField(nearestSwitch);
        Class<?> dimC = baby.getClass();
        float bw = (float) dimC.getMethod("width").invoke(baby), bh = (float) dimC.getMethod("height").invoke(baby), be = (float) dimC.getMethod("eyeHeight").invoke(baby);
        boolean bf = (boolean) dimC.getMethod("fixed").invoke(baby);
        Object accessor = staticField(flagField);
        int dataId = (int) accessor.getClass().getMethod("id").invoke(accessor);
        return "BABY:" + Float.floatToRawIntBits(bw) + "," + Float.floatToRawIntBits(bh) + "," + Float.floatToRawIntBits(be) + "," + (bf ? "fixed" : "scalable") + "," + dataId;
    }

    /** A static field of a server-side (Paper runtime) class, by internal-name {@code owner.field}. */
    static Object staticField(String ownerDotField) throws Exception {
        int dot = ownerDotField.lastIndexOf('.');
        Field f = Class.forName(ownerDotField.substring(0, dot).replace('/', '.')).getDeclaredField(ownerDotField.substring(dot + 1));
        f.setAccessible(true);
        return f.get(null);
    }

    /** Nearest declaring class (internal name) of every watched method along the superclass chain, stopping at Entity (inclusive). */
    static Map<String, String> resolve(Map<String, ClassFacts> facts, String cls) {
        Map<String, String> out = new LinkedHashMap<>();
        String c = cls; int guard = 0;
        while (c != null && guard++ < 64) {
            ClassFacts f = facts.get(c);
            if (f == null) throw new IllegalStateException("class not in jar: " + c + " (chain of " + cls + ")");
            for (var d : f.declared().entrySet()) out.putIfAbsent(d.getKey(), c);
            if (c.equals(ENTITY)) return out;
            c = f.superName();
        }
        throw new IllegalStateException("chain of " + cls + " does not reach Entity");
    }

    /** Interpolation steps on the client: "snap" when getInterpolation is Entity's (returns null); otherwise the constant the declaring chain constructs. */
    static String interpolation(Map<String, ClassFacts> facts, String cls, Map<String, String> resolved) {
        String decl = resolved.get("interpolation");
        if (decl == null || decl.equals(ENTITY)) return "snap";
        // the handler is constructed in a constructor of the class chain at or below the declaring class; take the nearest construction
        String c = cls; Set<Integer> seen = new TreeSet<>(); boolean lengthCall = false;
        while (c != null && !c.equals(ENTITY)) {
            ClassFacts f = facts.get(c);
            if (!f.interpolationLengthCalls().isEmpty()) lengthCall = true;
            if (!f.interpolationSteps().isEmpty()) { seen.addAll(f.interpolationSteps()); break; }
            c = f.superName();
        }
        if (seen.size() != 1 || lengthCall) return "dynamic";
        return Integer.toString(seen.iterator().next());
    }

    /** A pick radius declared as {@code return <float constant>} is recorded as its value; anything else is "dynamic" (the table never guesses). */
    static String pickRadius(Map<String, ClassFacts> facts, String decl) {
        if (decl == null || decl.equals(ENTITY)) return "0.0";
        ClassFacts f = facts.get(decl); String v = f.declared().get("pickRadius");
        return v == null ? "dynamic" : v;
    }

    /**
     * A boolean entity fact as the client computes it: "true"/"false" for a constant body, "alive" for LivingEntity.isPickable
     * ({@code !isRemoved()}, true for every target an attack can name), "dynamic" otherwise.
     */
    static String booleanFact(Map<String, ClassFacts> facts, String decl, String label) {
        if (decl == null) return "absent";
        if (label.equals("pickable") && decl.equals("net/minecraft/world/entity/LivingEntity")) return "alive";
        String v = facts.get(decl).declared().get(label);
        return v != null && v.startsWith("const:") ? v.substring(6) : "dynamic";
    }

    static String simple(String internal) { return internal == null ? null : internal.substring(internal.lastIndexOf('/') + 1); }

    /** Parses every class under net/minecraft/ with java.lang.classfile; records only watched declarations and interpolation-handler constructions. */
    static Map<String, ClassFacts> scan(Path jar) throws Exception {
        Map<String, ClassFacts> out = new HashMap<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (var en : Collections.list(z.entries())) {
                String n = en.getName();
                if (!n.startsWith("net/minecraft/") || !n.endsWith(".class")) continue;
                byte[] b; try (var in = z.getInputStream(en)) { b = in.readAllBytes(); }
                ClassModel cm = ClassFile.of().parse(b);
                String name = cm.thisClass().asInternalName(); String sup = cm.superclass().map(s -> s.asInternalName()).orElse(null);
                Map<String, String> declared = new LinkedHashMap<>(); List<Integer> steps = new ArrayList<>(); List<String> lengthCalls = new ArrayList<>();
                for (MethodModel m : cm.methods()) {
                    String key = m.methodName().stringValue() + m.methodType().stringValue();
                    String label = WATCHED.get(key);
                    if (label != null && (m.flags().flagsMask() & ClassFile.ACC_ABSTRACT) == 0) {
                        String value = "declared";
                        if (label.equals("pickRadius")) value = constantFloatReturn(m);
                        if (label.equals("defaultDimensions")) value = babySwitch(m);
                        if (label.equals("isBaby")) value = babyFlag(m);
                        if (label.equals("canBeABaby") || label.equals("pickable") || label.equals("pickInside")) value = constantBooleanReturn(m);
                        declared.put(label, value);
                    }
                    var code = m.code();
                    if (code.isEmpty()) continue;
                    Integer lastInt = null;
                    for (CodeElement ce : code.get()) {
                        if (ce instanceof ConstantInstruction ci && ci.constantValue() instanceof Integer i) lastInt = i;
                        else if (ce instanceof InvokeInstruction ii) {
                            String owner = ii.owner().asInternalName(), mn = ii.name().stringValue(), desc = ii.typeSymbol().descriptorString();
                            if (owner.equals("net/minecraft/world/entity/InterpolationHandler") && mn.equals("<init>")) {
                                // (Entity, int[, Consumer]) carries its steps; (Entity[, Consumer]) delegates with DEFAULT_INTERPOLATION_STEPS = 3 (iconst_3 in the delegating constructor)
                                if (desc.startsWith("(Lnet/minecraft/world/entity/Entity;I")) steps.add(lastInt == null ? -1 : lastInt); else steps.add(3);
                            }
                            if (owner.equals("net/minecraft/world/entity/InterpolationHandler") && mn.equals("setInterpolationLength")) lengthCalls.add(m.methodName().stringValue());
                            lastInt = null;
                        } else if (!(ce instanceof LoadInstruction) && !(ce instanceof StackInstruction) && !(ce instanceof Label) && !(ce instanceof LineNumber) && !(ce instanceof LocalVariable)) lastInt = null;
                    }
                }
                out.put(name, new ClassFacts(name, sup, declared, steps, lengthCalls, b));
            }
        }
        return out;
    }

    static List<Instruction> instructions(MethodModel m) {
        List<Instruction> ins = new ArrayList<>();
        m.code().ifPresent(c -> { for (CodeElement ce : c) if (ce instanceof Instruction i) ins.add(i); });
        return ins;
    }

    /**
     * The babyable-dimension switch {@code return isBaby() ? <Owner>.<FIELD> : super.getDefaultDimensions(pose);} (javap of Zombie, Husk, Cow,
     * Villager, Chicken ...): exactly one {@code isBaby()} call, one static EntityDimensions read, one super {@code getDefaultDimensions} and
     * nothing else invoked. Returns {@code babySwitch:<owner>.<field>}, or "declared" for any other body (the type is then VARIABLE).
     */
    static String babySwitch(MethodModel m) {
        String field = null; int isBaby = 0, sup = 0, other = 0;
        for (Instruction i : instructions(m)) {
            if (i instanceof InvokeInstruction ii) {
                String n = ii.name().stringValue(), d = ii.typeSymbol().descriptorString();
                if (n.equals("isBaby") && d.equals("()Z")) isBaby++;
                else if (n.equals("getDefaultDimensions") && ii.opcode() == Opcode.INVOKESPECIAL) sup++;
                else other++;
            } else if (i instanceof FieldInstruction fi && fi.opcode() == Opcode.GETSTATIC && fi.typeSymbol().descriptorString().equals(DIMENSIONS_DESC)) {
                if (field != null) other++; else field = fi.owner().asInternalName() + "." + fi.name().stringValue();
            } else if (i instanceof FieldInstruction) other++;
        }
        return isBaby == 1 && sup == 1 && other == 0 && field != null ? "babySwitch:" + field : "declared";
    }

    /** {@code return getEntityData().get(<Owner>.<FIELD>);} (a synced Boolean) -> {@code data:<owner>.<field>}; anything else "declared". */
    static String babyFlag(MethodModel m) {
        String field = null; int other = 0; boolean get = false;
        for (Instruction i : instructions(m)) {
            if (i instanceof FieldInstruction fi && fi.opcode() == Opcode.GETSTATIC && fi.typeSymbol().descriptorString().equals("Lnet/minecraft/network/syncher/EntityDataAccessor;")) field = fi.owner().asInternalName() + "." + fi.name().stringValue();
            else if (i instanceof InvokeInstruction ii) {
                String n = ii.name().stringValue();
                if (n.equals("get") && ii.owner().asInternalName().equals("net/minecraft/network/syncher/SynchedEntityData")) get = true;
                else if (!n.equals("getEntityData") && !n.equals("booleanValue")) other++;
            } else if (i instanceof FieldInstruction) other++;
        }
        return field != null && get && other == 0 ? "data:" + field : "declared";
    }

    /** {@code return true|false;} -> "const:true|false"; anything else "dynamic". */
    static String constantBooleanReturn(MethodModel m) {
        List<Instruction> ins = instructions(m);
        if (ins.size() == 2 && ins.get(0) instanceof ConstantInstruction c && c.constantValue() instanceof Integer v && ins.get(1) instanceof ReturnInstruction) return "const:" + (v != 0);
        return "dynamic";
    }

    /** {@code return <float constant>;} -> its value as text; anything else "dynamic". */
    static String constantFloatReturn(MethodModel m) {
        var code = m.code(); if (code.isEmpty()) return "dynamic";
        List<Instruction> ins = new ArrayList<>();
        for (CodeElement ce : code.get()) if (ce instanceof Instruction i) ins.add(i);
        if (ins.size() == 2 && ins.get(0) instanceof ConstantInstruction c && c.constantValue() instanceof Float f && ins.get(1) instanceof ReturnInstruction) return Float.toString(f);
        return "dynamic";
    }

    static final String FACTORY_SAM = "(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)";

    /**
     * EntityTypes.&lt;clinit&gt; registers each type as {@code register(EntityTypeIds.X, Builder.of(<factory>, category)...)}. The factory is either a
     * LambdaMetafactory call whose instantiated type is {@code (EntityType, Level) -> <entity class>} (a constructor reference), or a static helper
     * of EntityTypes returning an EntityFactory whose own lambda has that instantiated type (boats, minecarts). The id is the static field name of
     * EntityTypeIds read just before; it is mapped to the registry id from the runtime class. Every registry entry must bind exactly once.
     */
    static Map<String, String> typeBindings(Path jar, boolean clientSide) throws Exception {
        Map<String, String> byField = new TreeMap<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            var en = z.getEntry("net/minecraft/world/entity/EntityTypes.class");
            if (en == null) throw new IllegalStateException("EntityTypes missing in " + jar);
            byte[] b; try (var in = z.getInputStream(en)) { b = in.readAllBytes(); }
            ClassModel cm = ClassFile.of().parse(b);
            Map<String, String> helperProduct = new HashMap<>(); // static helper name+desc -> entity class its factory lambda instantiates
            for (MethodModel m : cm.methods()) {
                if (m.methodName().stringValue().equals("<clinit>") || m.code().isEmpty()) continue;
                if (!m.methodType().stringValue().endsWith(")Lnet/minecraft/world/entity/EntityType$EntityFactory;")) continue;
                for (CodeElement ce : m.code().get()) if (ce instanceof InvokeDynamicInstruction indy) { String p = factoryProduct(indy); if (p != null) helperProduct.put(m.methodName().stringValue() + m.methodType().stringValue(), p); }
            }
            for (MethodModel m : cm.methods()) {
                if (!m.methodName().stringValue().equals("<clinit>")) continue;
                String field = null, product = null;
                for (CodeElement ce : m.code().orElseThrow()) {
                    if (ce instanceof FieldInstruction fi && fi.opcode() == Opcode.GETSTATIC && fi.owner().asInternalName().equals("net/minecraft/world/entity/EntityTypeIds")) { field = fi.name().stringValue(); product = null; }
                    else if (ce instanceof InvokeDynamicInstruction indy) { String p = factoryProduct(indy); if (p != null) product = p; }
                    else if (ce instanceof InvokeInstruction ii) {
                        String key = ii.name().stringValue() + ii.typeSymbol().descriptorString();
                        if (ii.owner().asInternalName().equals("net/minecraft/world/entity/EntityTypes") && helperProduct.containsKey(key)) product = helperProduct.get(key);
                        if (ii.owner().asInternalName().equals("net/minecraft/world/entity/EntityTypes") && ii.name().stringValue().equals("register") && field != null) {
                            // the player type has no factory (Builder.createNothing): the observer's client represents other players as RemotePlayer, the
                            // server as ServerPlayer; each jar's own chain is scanned, so what each side really runs is compared
                            if (product == null && field.equals("PLAYER")) product = clientSide ? "net/minecraft/client/player/RemotePlayer" : "net/minecraft/server/level/ServerPlayer";
                            if (product == null) throw new IllegalStateException("no factory class for " + field + " in " + jar);
                            if (byField.put(field, product) != null) throw new IllegalStateException("registered twice: " + field);
                            field = null; product = null;
                        }
                    }
                }
            }
        }
        Map<String, String> out = new TreeMap<>();
        Class<?> ids = Class.forName("net.minecraft.world.entity.EntityTypeIds"); Class<?> rk = Class.forName("net.minecraft.resources.ResourceKey");
        for (var e : byField.entrySet()) {
            Object key = ids.getField(e.getKey()).get(null);
            out.put(rk.getMethod("identifier").invoke(key).toString(), e.getValue());
        }
        return out;
    }

    /** The entity class a LambdaMetafactory call instantiates when its instantiated type is the EntityFactory shape, else null. */
    static String factoryProduct(InvokeDynamicInstruction indy) {
        var args = indy.bootstrapArgs();
        if (args.size() < 3 || !(args.get(2) instanceof java.lang.constant.MethodTypeDesc t)) return null;
        String d = t.descriptorString();
        if (!d.startsWith(FACTORY_SAM)) return null;
        String ret = d.substring(FACTORY_SAM.length());
        return ret.startsWith("L") ? ret.substring(1, ret.length() - 1) : null;
    }

    static String sha256(byte[] b) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }

    static String json(Object o, int indent) {
        String pad = "  ".repeat(indent + 1), end = "  ".repeat(indent);
        if (o instanceof Map<?, ?> m) {
            if (m.isEmpty()) return "{}";
            StringBuilder s = new StringBuilder("{\n"); int i = 0;
            for (var e : m.entrySet()) s.append(pad).append('"').append(e.getKey()).append("\": ").append(json(e.getValue(), indent + 1)).append(++i < m.size() ? ",\n" : "\n");
            return s.append(end).append('}').toString();
        }
        if (o instanceof Collection<?> c) {
            if (c.isEmpty()) return "[]";
            StringBuilder s = new StringBuilder("[\n"); int i = 0;
            for (Object v : c) s.append(pad).append(json(v, indent + 1)).append(++i < c.size() ? ",\n" : "\n");
            return s.append(end).append(']').toString();
        }
        if (o instanceof String str) return '"' + str.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        return String.valueOf(o);
    }
}
