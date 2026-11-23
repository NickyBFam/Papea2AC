package me.nicolas.papea2ac.combat.reach;

import java.util.*;
import me.nicolas.papea2ac.combat.geometry.PickMath;
import me.nicolas.papea2ac.combat.geometry.RayBoxBounds;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.version.EntityGeometryTable;
import me.nicolas.papea2ac.version.MovementProfile;
import me.nicolas.papea2ac.world.EntityLookup;

/**
 * The Phase 12 decision for one attack, as a pure function of explicit inputs (PHASE_12 R1–R12). Deterministic: same input, same result. Bounded:
 * at most {@link #MAX_SCENES} scenes and {@link #MAX_WORK} geometry work units (COMBAT_MODEL.md bounds), each exhaustion is UNCERTAIN.
 *
 * <p>Order: (1) collect every reason a dimension is unknown; (2) build the admissible target position box (interpolation hull over the
 * marker-bounded window) and the box dimensions' min/max; (3) for each scene (eye variant × same-step view) run the outer exclusion and, for
 * certified eyes, the robust witness with the block check; (4) VALID if a witness exists, INVALID if every scene is excluded and nothing needed
 * for exclusion is unknown, otherwise UNCERTAIN with every reason.
 */
public final class ReachValidator {
    private ReachValidator() {}
    public static final int MAX_SCENES = 64, MAX_WORK = 4096;
    /** A witness entry must be below the range by this margin (relative) so client rounding cannot flip it. */
    static final double WITNESS_MARGIN = 1.0E-9;
    /** Bound on |lerp(1.0, xo, x) − x| when xo is not certified: exact under Sterbenz (|x − xo| ≤ |x|), otherwise a few ulps of the larger operand; legitimate movement per tick is far below 4096 blocks. */
    static double eyeLerpPad(double x) { return 4 * Math.ulp(Math.max(Math.abs(x), 4096.0)); }

    /** Main-hand / active item class for the pick path (R11). */
    public enum HeldItem { CLASSIC, ATTACK_RANGE, PIERCING, UNKNOWN }
    /** Is the block at a cell known to have an EMPTY outline (air)? {@code null} = unknown. */
    @FunctionalInterface public interface AirOracle { Boolean air(int x, int y, int z); }
    /**
     * The OUTLINE boxes of the block at a cell, in world coordinates (6 doubles per box; an empty array = no outline), or {@code null} when the
     * cell or its outline is unknown (unobserved, CONTEXT / LEVEL outline). OFFSET outlines are given as their envelope.
     */
    @FunctionalInterface public interface CellOutlines { double[] outline(int x, int y, int z); }
    /** Air cells have no outline; anything else is unknown (the v1 rule, before the outline table). */
    public static CellOutlines airOnly(AirOracle air) { return air == null ? null : (x, y, z) -> Boolean.TRUE.equals(air.air(x, y, z)) ? new double[0] : null; }

    /**
     * One certified attacker state at the end of the tick before the attack: feet position, pose (Pose constant name), and the previous tick's
     * certified feet position when known (then the eye is computed exactly as {@code lerp(1.0, xo, x)}).
     */
    public record EyeCandidate(double x, double y, double z, String pose, boolean previousKnown, double xo, double yo, double zo) {}

    /**
     * @param source CERTIFIED_CONCRETE: {@code certified} lists the frontier states; ABSTRACT_EXCLUSION_ONLY: {@code feet} bounds the feet
     *               position (and {@code poses} the possible poses); UNAVAILABLE: no eye
     */
    public record EyeInput(ReachValidation.EyeSource source, List<EyeCandidate> certified, PickMath.Box feet, List<String> poses, boolean frontierConflict) {
        public EyeInput { Objects.requireNonNull(source); certified = List.copyOf(certified); poses = List.copyOf(poses); }
        public static final EyeInput UNAVAILABLE = new EyeInput(ReachValidation.EyeSource.UNAVAILABLE, List.of(), null, List.of(), false);
    }

    /**
     * @param lookupStatus Phase 11 lookup status at the attack
     * @param window network targets of the admissible window, oldest first
     * @param windowExact every revision in the window has EXACT position status (no INTERPOLATION_/VEHICLE_DEPENDENT / UNSUPPORTED)
     * @param lifetime box of every target since the spawn snap; {@code null} when coverage since the spawn is not complete
     * @param pose the target's pose name at the attack ({@code null} = unknown)
     * @param scale the target's scale attribute ({@code null} = unknown)
     * @param baby the target's synced baby flag ({@code null} = unknown)
     */
    public record TargetInput(EntityLookup.Status lookupStatus, String type, int networkId, long incarnation, List<double[]> window, boolean windowExact,
                              boolean mounted, boolean localDisplacementPossible, PickMath.Box lifetime, ApplicationFrontier frontier, String pose, Double scale, Boolean baby,
                              List<double[]> history, List<TargetHull.Bound> bounds) {
        public TargetInput { Objects.requireNonNull(lookupStatus); window = List.copyOf(window); Objects.requireNonNull(frontier); history = List.copyOf(history); bounds = List.copyOf(bounds); }
        /** Without the nested history (the single newest frontier only): the contract-v1 shape. */
        public TargetInput(EntityLookup.Status lookupStatus, String type, int networkId, long incarnation, List<double[]> window, boolean windowExact,
                           boolean mounted, boolean localDisplacementPossible, PickMath.Box lifetime, ApplicationFrontier frontier, String pose, Double scale, Boolean baby) {
            this(lookupStatus, type, networkId, incarnation, window, windowExact, mounted, localDisplacementPossible, lifetime, frontier, pose, scale, baby, List.of(), List.of());
        }
    }

    /**
     * @param entityRange {@code entity_interaction_range} as the client computes it ({@code null} = unknown)
     * @param scale the attacker's scale attribute ({@code null} = unknown)
     */
    public record AttackerInput(Double entityRange, double blockRange, Double scale, boolean passenger, HeldItem heldItem, String gameMode) {
        public AttackerInput { Objects.requireNonNull(heldItem); }
    }

    /** One rotation hypothesis (the same-step rotation, M3); several when the step cannot be aligned. */
    public record View(float yaw, float pitch) {}

    public record Input(SessionKey session, long sequence, String attackId, TargetInput target, EyeInput eye, List<View> views, AttackerInput attacker,
                        CellOutlines cells, boolean exclusionPermittedForAbstractEye) {
        public Input { Objects.requireNonNull(session); Objects.requireNonNull(attackId); Objects.requireNonNull(target); Objects.requireNonNull(eye); views = List.copyOf(views); Objects.requireNonNull(attacker); }
        /** The contract-v1 shape: air cells only. */
        public Input(SessionKey session, long sequence, String attackId, TargetInput target, EyeInput eye, List<View> views, AttackerInput attacker, AirOracle air, boolean exclusionPermittedForAbstractEye) {
            this(session, sequence, attackId, target, eye, views, attacker, airOnly(air), exclusionPermittedForAbstractEye);
        }
    }

    public static ReachValidation validate(Input in, EntityGeometryTable table, MovementProfile trig, String profileId) {
        EnumSet<ReachValidation.Reason> reasons = EnumSet.noneOf(ReachValidation.Reason.class);
        List<String> notes = new ArrayList<>();
        TargetInput t = in.target();
        // ---- target identity and kind
        EntityGeometryTable.Type type = t.type() == null ? null : table.type(t.type());
        if (t.lookupStatus() != EntityLookup.Status.FOUND) return uncertain(in, ReachValidation.Reason.TARGET_NOT_FOUND, "lookup " + t.lookupStatus(), profileId);
        if (type == null) return uncertain(in, ReachValidation.Reason.TARGET_TYPE_UNKNOWN, "type " + t.type(), profileId);
        // a type the vanilla client can never pick: every scene fails, whatever the geometry (R9 INVALID_TARGET)
        if (type.pickable() == EntityGeometryTable.Pickable.FALSE)
            return new ReachValidation(in.session(), in.sequence(), in.attackId(), ReachValidation.Status.INVALID, List.of(ReachValidation.MissClass.INVALID_TARGET), List.of(),
                in.eye().source(), t.frontier(), t.type(), t.networkId(), t.incarnation(), 0, 1, null,
                new ReachValidation.Certificate(0, List.of(ReachValidation.MissClass.INVALID_TARGET), Double.NaN, Double.NaN), nz(in.attacker().entityRange()), nz(in.attacker().scale()), profileId,
                List.of("type " + t.type() + " is never pickable (isPickable constant false)"));
        boolean geometryUsable = true;
        if (type.dimensionClass() == EntityGeometryTable.DimensionClass.VARIABLE) { reasons.add(ReachValidation.Reason.TARGET_DIMENSIONS_UNSUPPORTED); notes.add("dims " + type.variableReason()); geometryUsable = false; }
        if (type.interpolationSteps() < 0) { reasons.add(ReachValidation.Reason.TARGET_INTERPOLATION_UNSUPPORTED); geometryUsable = false; }
        if (type.pickRadius() == null) { reasons.add(ReachValidation.Reason.TARGET_DIMENSIONS_UNSUPPORTED); notes.add("dynamic pick radius"); geometryUsable = false; }
        if (!t.windowExact()) { reasons.add(ReachValidation.Reason.TARGET_POSITION_NOT_EXACT); geometryUsable = false; }
        if (t.mounted() || t.localDisplacementPossible()) { reasons.add(ReachValidation.Reason.TARGET_MOUNTED); geometryUsable = false; }
        if (t.window().isEmpty()) { reasons.add(ReachValidation.Reason.TARGET_WINDOW_EMPTY); geometryUsable = false; }
        if (t.lifetime() == null) { reasons.add(ReachValidation.Reason.TARGET_LIFETIME_INCOMPLETE); geometryUsable = false; }
        boolean pickableKnown = type.pickable() == EntityGeometryTable.Pickable.TRUE || type.pickable() == EntityGeometryTable.Pickable.ALIVE;
        if (!pickableKnown) notes.add("target pickability is computed per instance (" + type.pickable() + "): a witness assumes it is pickable");
        // ---- dimensions (min/max over every unknown dimension input)
        float[] dims = geometryUsable ? targetDimensions(type, t, table, reasons) : null;
        // ---- attacker
        AttackerInput a = in.attacker();
        boolean rangeKnown = a.entityRange() != null;
        if (!rangeKnown) reasons.add(ReachValidation.Reason.RANGE_UNKNOWN);
        if (a.passenger()) reasons.add(ReachValidation.Reason.ATTACKER_PASSENGER);
        if (a.heldItem() == HeldItem.UNKNOWN) reasons.add(ReachValidation.Reason.HELD_ITEM_UNKNOWN);
        if (a.heldItem() == HeldItem.ATTACK_RANGE || a.heldItem() == HeldItem.PIERCING) reasons.add(ReachValidation.Reason.ATTACK_RANGE_ITEM_UNSUPPORTED);
        if ("spectator".equalsIgnoreCase(a.gameMode())) reasons.add(ReachValidation.Reason.GAME_MODE_UNSUPPORTED);
        if (a.scale() == null) reasons.add(ReachValidation.Reason.ATTACKER_SCALE_UNKNOWN);
        boolean classicPick = !a.passenger() && a.heldItem() == HeldItem.CLASSIC && !"spectator".equalsIgnoreCase(a.gameMode());
        if (in.views().isEmpty()) reasons.add(ReachValidation.Reason.ROTATION_UNKNOWN);
        // ---- eye
        EyeInput e = in.eye();
        if (e.frontierConflict()) reasons.add(ReachValidation.Reason.ATTACKER_FRONTIER_CONFLICT);
        if (e.source() == ReachValidation.EyeSource.UNAVAILABLE) reasons.add(ReachValidation.Reason.EYE_UNAVAILABLE);
        if (e.source() == ReachValidation.EyeSource.ABSTRACT_EXCLUSION_ONLY) reasons.add(ReachValidation.Reason.EYE_ABSTRACT_ONLY);
        boolean eyeForExclusion = !e.frontierConflict() && (e.source() == ReachValidation.EyeSource.CERTIFIED_CONCRETE
            || (e.source() == ReachValidation.EyeSource.ABSTRACT_EXCLUSION_ONLY && in.exclusionPermittedForAbstractEye()));
        if (!geometryUsable || dims == null || in.views().isEmpty() || e.source() == ReachValidation.EyeSource.UNAVAILABLE || a.passenger())
            return result(in, reasons, notes, ReachValidation.Status.UNCERTAIN, List.of(), null, null, 0, 0, profileId);

        // ---- admissible target positions
        PickMath.Box positions = positions(t, type);
        float wMin = dims[0], hMin = dims[1], wMax = dims[2], hMax = dims[3];
        PickMath.Box hull = RayBoxBounds.hull(positions, wMax, hMax);
        PickMath.Box core = RayBoxBounds.core(positions, wMin, hMin);
        float attackerScale = a.scale() == null ? 1.0F : (float) (double) a.scale();

        // ---- eye variants: (box of eye points, certified?, pose)
        List<double[]> eyeBoxes = new ArrayList<>(); List<Boolean> eyeCertified = new ArrayList<>();
        if (e.source() == ReachValidation.EyeSource.CERTIFIED_CONCRETE) {
            for (EyeCandidate c : e.certified()) {
                float[] eh = eyeHeights(table, List.of(c.pose()), a.scale(), reasons);
                if (eh == null) continue;
                for (float h : eh) { eyeBoxes.add(eyeBox(c, h)); eyeCertified.add(a.scale() != null); }
            }
        } else if (e.source() == ReachValidation.EyeSource.ABSTRACT_EXCLUSION_ONLY && e.feet() != null) {
            float[] eh = eyeHeights(table, e.poses(), a.scale(), reasons);
            if (eh != null) {
                float lo = eh[0], hi = eh[0]; for (float h : eh) { lo = Math.min(lo, h); hi = Math.max(hi, h); }
                PickMath.Box f = e.feet(); double pad = eyeLerpPad(Math.max(Math.abs(f.maxX()), Math.max(Math.abs(f.maxY()), Math.abs(f.maxZ()))));
                eyeBoxes.add(new double[]{Math.nextDown(f.minX() - pad), Math.nextDown(Math.nextDown(f.minY() - pad) + lo), Math.nextDown(f.minZ() - pad),
                    Math.nextUp(f.maxX() + pad), Math.nextUp(Math.nextUp(f.maxY() + pad) + hi), Math.nextUp(f.maxZ() + pad)});
                eyeCertified.add(false);
            }
        }
        if (eyeBoxes.isEmpty()) { reasons.add(ReachValidation.Reason.ATTACKER_POSE_UNKNOWN); return result(in, reasons, notes, ReachValidation.Status.UNCERTAIN, List.of(), null, null, 0, 0, profileId); }

        // ---- scenes
        int scenes = 0, work = 0; boolean allExcluded = true, limit = false;
        EnumSet<ReachValidation.MissClass> classes = EnumSet.noneOf(ReachValidation.MissClass.class);
        double minDistance = Double.POSITIVE_INFINITY;
        ReachValidation.Witness witness = null; boolean blocksUnresolved = false;
        double range = rangeKnown ? a.entityRange() : Double.NaN;
        outer:
        for (int ei = 0; ei < eyeBoxes.size(); ei++) {
            double[] eb = eyeBoxes.get(ei); PickMath.Box eye = new PickMath.Box(eb[0], eb[1], eb[2], eb[3], eb[4], eb[5]);
            for (View v : in.views()) {
                if (scenes >= MAX_SCENES || work + 3 > MAX_WORK) { limit = true; break outer; }
                scenes++;
                double[] view = PickMath.viewVector(trig, v.pitch(), v.yaw());
                // outer exclusion (sound for every eye in the box and every admissible box)
                RayBoxBounds.OuterBound ob = RayBoxBounds.exclude(eye, view, hull, rangeKnown ? range : Double.POSITIVE_INFINITY);
                work++;
                if (ob.exclusion() == RayBoxBounds.Exclusion.ANGLE) classes.add(ReachValidation.MissClass.ANGLE);
                else if (ob.exclusion() == RayBoxBounds.Exclusion.REACH) { classes.add(ReachValidation.MissClass.REACH); minDistance = Math.min(minDistance, ob.minDistance()); }
                else { allExcluded = false; if (!Double.isNaN(ob.minDistance())) minDistance = Math.min(minDistance, ob.minDistance()); }
                // robust witness (certified eyes, classic pick, known range only)
                if (witness == null && eyeCertified.get(ei) && classicPick && rangeKnown && core != null) {
                    double entry = RayBoxBounds.robustHit(eye, view, core); work++;
                    if (!Double.isNaN(entry) && entry < range * (1 - WITNESS_MARGIN) - WITNESS_MARGIN) {
                        Boolean clear = clearOfBlocks(eye, view, entry, in.cells()); work++;
                        if (Boolean.TRUE.equals(clear)) {
                            witness = new ReachValidation.Witness(scenes, eye.minX(), eye.minY(), eye.minZ(), v.yaw(), v.pitch(), positions.minX(), positions.minY(), positions.minZ(),
                                positions.maxX(), positions.maxY(), positions.maxZ(), entry, range, true);
                        } else blocksUnresolved = true;
                    }
                }
            }
        }
        if (limit) reasons.add(scenes >= MAX_SCENES ? ReachValidation.Reason.SCENE_LIMIT : ReachValidation.Reason.WORK_LIMIT);
        if (witness != null) {
            reasons.removeIf(r -> r == ReachValidation.Reason.RANGE_UNKNOWN); // range was known for the witness
            notes.add("other entities on the ray are not evaluated (R8)");
            return result(in, EnumSet.noneOf(ReachValidation.Reason.class), notes, ReachValidation.Status.VALID, List.of(), witness, null, scenes, work, profileId);
        }
        if (blocksUnresolved) reasons.add(ReachValidation.Reason.BLOCKS_UNRESOLVED);
        // INVALID needs: every scene excluded, no limit hit, an exclusion-grade eye set, the classic pick, and — for REACH — a known range.
        boolean reachNeedsRange = classes.contains(ReachValidation.MissClass.REACH) && !rangeKnown;
        boolean exclusionComplete = allExcluded && !limit && eyeForExclusion && classicPick && !reachNeedsRange && a.scale() != null && !"spectator".equalsIgnoreCase(a.gameMode());
        if (exclusionComplete) {
            double residual = classes.contains(ReachValidation.MissClass.REACH) ? minDistance - range : Double.NaN;
            ReachValidation.Certificate cert = new ReachValidation.Certificate(scenes, new ArrayList<>(classes), minDistance == Double.POSITIVE_INFINITY ? Double.NaN : minDistance, residual);
            return result(in, EnumSet.noneOf(ReachValidation.Reason.class), notes, ReachValidation.Status.INVALID, new ArrayList<>(classes), null, cert, scenes, work, profileId);
        }
        if (reasons.isEmpty()) reasons.add(ReachValidation.Reason.BORDERLINE);
        return result(in, reasons, notes, ReachValidation.Status.UNCERTAIN, List.of(), null, null, scenes, work, profileId);
    }

    /** The admissible target position box (R4/R5): the marker-bounded window under the interpolation hull, or the whole lifetime box without proof. */
    static PickMath.Box positions(TargetInput t, EntityGeometryTable.Type type) {
        // every answered marker at once (R5 backtrack refinement): the nested box is never wider than the single-frontier one
        if (!t.bounds().isEmpty() && !t.history().isEmpty()) return TargetHull.nested(t.history(), t.lifetime(), Math.max(type.interpolationSteps(), 0), t.bounds());
        if (t.frontier().status() != ApplicationFrontier.Status.MARKER_PROVEN) {
            List<double[]> all = new ArrayList<>(t.window());
            all.add(new double[]{t.lifetime().minX(), t.lifetime().minY(), t.lifetime().minZ()}); all.add(new double[]{t.lifetime().maxX(), t.lifetime().maxY(), t.lifetime().maxZ()});
            return TargetHull.positions(all, t.lifetime(), Math.max(type.interpolationSteps(), 0), 0);
        }
        return TargetHull.positions(t.window(), t.lifetime(), type.interpolationSteps(), t.frontier().ticksSincePong());
    }

    /** {wMin, hMin, wMax, hMax} over every unknown (pose, scale, baby flag); null when not computable. Float operations as the client's. */
    static float[] targetDimensions(EntityGeometryTable.Type type, TargetInput t, EntityGeometryTable table, EnumSet<ReachValidation.Reason> reasons) {
        List<EntityGeometryTable.Dimensions> base = new ArrayList<>();
        boolean sleepingPossible = type.living() && (t.pose() == null || t.pose().equals("SLEEPING"));
        switch (type.dimensionClass()) {
            case PLAIN, PLAIN_LIVING -> base.add(type.dimensions());
            case BABY -> { if (t.baby() == null || !t.baby()) base.add(type.dimensions()); if (t.baby() == null || t.baby()) base.add(type.babyDimensions()); }
            case AVATAR -> {
                if (t.pose() == null) { reasons.add(ReachValidation.Reason.TARGET_POSE_UNKNOWN); for (String p : List.of("STANDING", "CROUCHING", "SWIMMING", "FALL_FLYING", "SPIN_ATTACK", "DYING")) base.add(avatarPose(table, p)); }
                else if (!t.pose().equals("SLEEPING")) base.add(avatarPose(table, t.pose()));
            }
            default -> { return null; }
        }
        if (t.pose() == null && type.dimensionClass() != EntityGeometryTable.DimensionClass.AVATAR && type.living()) reasons.add(ReachValidation.Reason.TARGET_POSE_UNKNOWN);
        float[] scales;
        if (!type.living()) scales = new float[]{1.0F};
        else if (t.scale() != null) scales = new float[]{(float) (double) t.scale()};
        else { reasons.add(ReachValidation.Reason.TARGET_SCALE_UNKNOWN); EntityGeometryTable.Attribute s = table.attribute("SCALE"); scales = new float[]{(float) s.min(), (float) s.max()}; }
        float wMin = Float.POSITIVE_INFINITY, hMin = Float.POSITIVE_INFINITY, wMax = 0, hMax = 0;
        for (EntityGeometryTable.Dimensions d : base) for (float s : scales) {
            float[] sd = PickMath.scale(d.width(), d.height(), d.eyeHeight(), d.fixed(), s, s);
            wMin = Math.min(wMin, sd[0]); hMin = Math.min(hMin, sd[1]); wMax = Math.max(wMax, sd[0]); hMax = Math.max(hMax, sd[1]);
        }
        if (sleepingPossible) { EntityGeometryTable.Dimensions sl = table.livingSleeping(); wMin = Math.min(wMin, sl.width()); hMin = Math.min(hMin, sl.height()); wMax = Math.max(wMax, sl.width()); hMax = Math.max(hMax, sl.height()); }
        if (wMax <= 0 && hMax <= 0) return null;
        return new float[]{wMin, hMin, wMax, hMax};
    }

    /**
     * Phase 13 (push exclusion, finding phase13-to-phase12-02): a sound box of every bounding box the observer's client can give the entity
     * described by {@code t}: the admissible position box ({@link #positions}) grown by the widest / tallest admissible dimensions
     * ({@link #targetDimensions}). Null when not computable (VARIABLE or dynamic-interpolation type, unknown lifetime, a non-exact, mounted or
     * locally displaced window): the caller must then treat the entity as possibly anywhere.
     */
    public static PickMath.Box possibleEntityBox(TargetInput t, EntityGeometryTable table) {
        if (t.lookupStatus() != EntityLookup.Status.FOUND || t.type() == null || t.window().isEmpty() || !t.windowExact() || t.mounted() || t.localDisplacementPossible()) return null;
        EntityGeometryTable.Type type = table.type(t.type());
        if (type == null || type.dimensionClass() == EntityGeometryTable.DimensionClass.VARIABLE || type.interpolationSteps() < 0) return null;
        if (t.lifetime() == null) return null;
        float[] dims = targetDimensions(type, t, table, EnumSet.noneOf(ReachValidation.Reason.class));
        if (dims == null) return null;
        PickMath.Box p = positions(t, type);
        double hw = Math.nextUp((double) dims[2] / 2.0), h = Math.nextUp((double) dims[3]);
        return new PickMath.Box(Math.nextDown(p.minX() - hw), Math.nextDown(p.minY()), Math.nextDown(p.minZ() - hw), Math.nextUp(p.maxX() + hw), Math.nextUp(p.maxY() + h), Math.nextUp(p.maxZ() + hw));
    }

    static EntityGeometryTable.Dimensions avatarPose(EntityGeometryTable table, String pose) {
        EntityGeometryTable.Dimensions d = table.avatarPose(pose);
        return d == null ? table.avatarStanding() : d;
    }

    /** The attacker's eye heights for the possible poses ({@code Avatar} table scaled by the attacker's scale, float as the client); null if a pose is unknown. */
    static float[] eyeHeights(EntityGeometryTable table, List<String> poses, Double scale, EnumSet<ReachValidation.Reason> reasons) {
        if (poses.isEmpty()) return null;
        float[] out = new float[poses.size() * (scale == null ? 2 : 1)]; int k = 0;
        for (String p : poses) {
            if (p == null) return null;
            EntityGeometryTable.Dimensions d = p.equals("SLEEPING") ? table.livingSleeping() : avatarPose(table, p);
            if (scale != null) out[k++] = PickMath.scale(d.width(), d.height(), d.eyeHeight(), d.fixed(), (float) (double) scale, (float) (double) scale)[2];
            else { EntityGeometryTable.Attribute s = table.attribute("SCALE"); out[k++] = PickMath.scale(d.width(), d.height(), d.eyeHeight(), d.fixed(), (float) s.min(), (float) s.min())[2]; out[k++] = PickMath.scale(d.width(), d.height(), d.eyeHeight(), d.fixed(), (float) s.max(), (float) s.max())[2]; }
        }
        return out;
    }

    /** The eye box of a certified candidate: exact when the previous position is certified, else the lerp pad (a few ulps). */
    static double[] eyeBox(EyeCandidate c, float eyeHeight) {
        if (c.previousKnown()) { double[] p = PickMath.eyePosition(c.xo(), c.yo(), c.zo(), c.x(), c.y(), c.z(), eyeHeight); return new double[]{p[0], p[1], p[2], p[0], p[1], p[2]}; }
        double px = eyeLerpPad(c.x()), py = eyeLerpPad(c.y()), pz = eyeLerpPad(c.z());
        return new double[]{c.x() - px, Math.nextDown((c.y() - py) + (double) eyeHeight), c.z() - pz, c.x() + px, Math.nextUp((c.y() + py) + (double) eyeHeight), c.z() + pz};
    }

    /**
     * True when no block OUTLINE can be hit before the target: every block cell the ray can cross from the eye box up to {@code entry} (plus a
     * margin) is known, and each of its outline boxes is soundly missed or met only beyond {@code entry + margin} by every eye in the box
     * ({@link RayBoxBounds#exclude}, which includes the client's clip tolerance). Null when a cell or an outline is unknown, or an outline box may
     * be met first (R7).
     */
    static Boolean clearOfBlocks(PickMath.Box eye, double[] view, double entry, CellOutlines cells) {
        if (cells == null) return null;
        double margin = 1.0E-6, len = entry + margin;
        double ex = eye.maxX() - eye.minX(), ey = eye.maxY() - eye.minY(), ez = eye.maxZ() - eye.minZ();
        double sx = eye.minX(), sy = eye.minY(), sz = eye.minZ();
        double fx = sx + view[0] * len, fy = sy + view[1] * len, fz = sz + view[2] * len;
        // conservative: every cell overlapped by the segment's swept box between consecutive sample points (step ≤ 0.25 block), widened by the eye box and margin
        int steps = Math.max(1, (int) Math.ceil(len / 0.25));
        if (steps > 64) return null;
        Set<Long> seen = new TreeSet<>();
        for (int i = 0; i < steps; i++) {
            double a0 = (double) i / steps, a1 = (double) (i + 1) / steps;
            double x0 = sx + (fx - sx) * a0, y0 = sy + (fy - sy) * a0, z0 = sz + (fz - sz) * a0, x1 = sx + (fx - sx) * a1, y1 = sy + (fy - sy) * a1, z1 = sz + (fz - sz) * a1;
            int cx0 = (int) Math.floor(Math.min(x0, x1) - margin), cx1 = (int) Math.floor(Math.max(x0, x1) + ex + margin);
            int cy0 = (int) Math.floor(Math.min(y0, y1) - margin), cy1 = (int) Math.floor(Math.max(y0, y1) + ey + margin);
            int cz0 = (int) Math.floor(Math.min(z0, z1) - margin), cz1 = (int) Math.floor(Math.max(z0, z1) + ez + margin);
            for (int x = cx0; x <= cx1; x++) for (int y = cy0; y <= cy1; y++) for (int z = cz0; z <= cz1; z++) {
                long key = ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | ((long) z & 0x3FFFFFFL);
                if (!seen.add(key)) continue;
                if (seen.size() > 512) return null;
                double[] boxes = cells.outline(x, y, z);
                if (boxes == null) return null;
                for (int b = 0; b + 5 < boxes.length; b += 6) {
                    RayBoxBounds.OuterBound ob = RayBoxBounds.exclude(eye, view, new PickMath.Box(boxes[b], boxes[b + 1], boxes[b + 2], boxes[b + 3], boxes[b + 4], boxes[b + 5]), len);
                    if (ob.exclusion() == RayBoxBounds.Exclusion.POSSIBLE) return null;
                }
            }
        }
        return Boolean.TRUE;
    }

    static double nz(Double d) { return d == null ? Double.NaN : d; }

    static ReachValidation uncertain(Input in, ReachValidation.Reason r, String note, String profileId) {
        return result(in, EnumSet.of(r), List.of(note), ReachValidation.Status.UNCERTAIN, List.of(), null, null, 0, 0, profileId);
    }

    static ReachValidation result(Input in, Set<ReachValidation.Reason> reasons, List<String> notes, ReachValidation.Status status, List<ReachValidation.MissClass> classes,
                                  ReachValidation.Witness witness, ReachValidation.Certificate cert, int scenes, int work, String profileId) {
        TargetInput t = in.target();
        List<String> n = notes.size() > 16 ? notes.subList(0, 16) : notes;
        return new ReachValidation(in.session(), in.sequence(), in.attackId(), status, classes, new ArrayList<>(new TreeSet<>(reasons)), in.eye().source(), t.frontier(), t.type(),
            t.networkId(), t.incarnation(), scenes, work, witness, cert, nz(in.attacker().entityRange()), nz(in.attacker().scale()), profileId, n);
    }
}
