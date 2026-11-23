package me.nicolas.papea2ac.testing;
import me.nicolas.papea2ac.checks.rotation.RotationKinematics;
import me.nicolas.papea2ac.model.Rotation;
import me.nicolas.papea2ac.player.RotationHistory;
import me.nicolas.papea2ac.version.RotationProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
/**
 * Phase 15 rotation corpora. Every stream is produced from a recorded seed, so a failing case replays exactly.
 *
 * <p>The legitimate generator is a model of the native 26.2 client derived from its bytecode (see {@code RotationProfile}): per frame it
 * accumulates cursor counts, applies {@code MouseHandler.turnPlayer} (raw, smooth-camera or scoping path, optional inversion) and
 * {@code Entity.turn}, and per tick emits the raw float rotation only when the float delta against the last sent rotation is non-zero
 * ({@code LocalPlayer.sendPosition}). Human motion itself is synthetic (random-walk angular velocity with pauses and flicks); it exercises the
 * arithmetic, not a claim about real player behavior. Real-player and real cheat-client streams come from VPS qualification.
 */
final class RotationCorpus {
    private RotationCorpus() {}

    static RotationKinematics kinematics() {
        var p = RotationProfile.NATIVE_26_2;
        return new RotationKinematics(p.profileId(), p.sensitivityScale(), p.sensitivityOffset(), p.unscopedMultiplier(), p.perCountScale(),
                p.pitchMin(), p.pitchMax(), p.sensitivityMin(), p.sensitivityMax());
    }

    /** One rotation-bearing observation, or a segment break. */
    record Event(long sequence, Rotation rotation, RotationHistory.Break breakReason) {
        static Event rotation(long seq, float yaw, float pitch) { return new Event(seq, new Rotation(yaw, pitch), null); }
        static Event breakAt(RotationHistory.Break reason) { return new Event(-1, null, reason); }
    }

    record Stream(String name, long seed, double sensitivity, List<Event> events) {}

    /** Options of the simulated native client. */
    record ClientOptions(double sensitivity, boolean smoothCamera, boolean scoping, boolean fractionalInput, boolean invertX, boolean invertY,
                         int maxFramesPerTick, float startYaw, float startPitch, double pitchBias) {
        static ClientOptions raw(double sensitivity) { return new ClientOptions(sensitivity, false, false, false, false, false, 20, 0f, 0f, 0); }
        ClientOptions withSmooth() { return new ClientOptions(sensitivity, true, scoping, fractionalInput, invertX, invertY, maxFramesPerTick, startYaw, startPitch, pitchBias); }
        ClientOptions withScoping() { return new ClientOptions(sensitivity, smoothCamera, true, fractionalInput, invertX, invertY, maxFramesPerTick, startYaw, startPitch, pitchBias); }
        ClientOptions withFractional() { return new ClientOptions(sensitivity, smoothCamera, scoping, true, invertX, invertY, maxFramesPerTick, startYaw, startPitch, pitchBias); }
        ClientOptions withInvert() { return new ClientOptions(sensitivity, smoothCamera, scoping, fractionalInput, true, true, maxFramesPerTick, startYaw, startPitch, pitchBias); }
        ClientOptions withStart(float yaw, float pitch) { return new ClientOptions(sensitivity, smoothCamera, scoping, fractionalInput, invertX, invertY, maxFramesPerTick, yaw, pitch, pitchBias); }
        ClientOptions withPitchBias(double bias) { return new ClientOptions(sensitivity, smoothCamera, scoping, fractionalInput, invertX, invertY, maxFramesPerTick, startYaw, startPitch, bias); }
        ClientOptions withFrames(int frames) { return new ClientOptions(sensitivity, smoothCamera, scoping, fractionalInput, invertX, invertY, frames, startYaw, startPitch, pitchBias); }
        ClientOptions withSensitivity(double s) { return new ClientOptions(s, smoothCamera, scoping, fractionalInput, invertX, invertY, maxFramesPerTick, startYaw, startPitch, pitchBias); }
    }

    /** {@code net.minecraft.util.SmoothDouble}, transcribed from its bytecode (lerp(0.5, last, delta) with sign test). */
    static final class SmoothDouble {
        private double target, remaining, lastAmount;
        double next(double delta, double time) {
            target += delta;
            double d = target - remaining;
            double lerped = lastAmount + 0.5d * (d - lastAmount);
            double sign = Math.signum(d);
            if (sign * d > sign * lastAmount) d = lerped;
            lastAmount = lerped;
            remaining += d * time;
            return d * time;
        }
        void reset() { target = 0; remaining = 0; lastAmount = 0; }
    }

    /** The simulated native client: frame turning plus per-tick send. */
    static final class Client {
        final RotationKinematics k; ClientOptions o; final SplittableRandom rnd;
        float yaw, pitch, yawLast, pitchLast; boolean sentOnce;
        final SmoothDouble sx = new SmoothDouble(), sy = new SmoothDouble();
        double vx, vy; // synthetic intended angular velocity, degrees per tick
        Client(RotationKinematics k, ClientOptions o, long seed) {
            this.k = k; this.o = o; this.rnd = new SplittableRandom(seed); yaw = o.startYaw(); pitch = o.startPitch();
        }
        /** Runs one client tick; returns the emitted rotation or null when nothing changed. */
        float[] tick() {
            // random-walk intended motion with pauses and occasional flicks
            double r = rnd.nextDouble();
            if (r < 0.08) { vx = 0; vy = 0; }
            else if (r < 0.11) { vx = (rnd.nextDouble() - 0.5) * 120; vy = (rnd.nextDouble() - 0.5) * 40; }
            else { vx = vx * 0.7 + (rnd.nextDouble() - 0.5) * 6; vy = vy * 0.7 + (rnd.nextDouble() - 0.5) * 2 + o.pitchBias(); }
            int frames = 1 + rnd.nextInt(o.maxFramesPerTick());
            double mult = k.multiplier(o.sensitivity(), o.scoping());
            double perCount = mult * k.perCountScale();
            for (int f = 0; f < frames; f++) {
                double cx = vx / frames / perCount + (rnd.nextDouble() - 0.5);
                double cy = vy / frames / perCount + (rnd.nextDouble() - 0.5);
                double dx = o.fractionalInput() ? cx : Math.rint(cx);
                double dy = o.fractionalInput() ? cy : Math.rint(cy);
                if (dx == 0 && dy == 0 && !o.smoothCamera()) continue;
                double tx, ty;
                if (o.smoothCamera()) { double t = 1.0 / frames; tx = sx.next(dx * mult, t * mult); ty = sy.next(dy * mult, t * mult); }
                else { sx.reset(); sy.reset(); tx = dx * mult; ty = dy * mult; }
                if (o.invertX()) tx = -tx;
                if (o.invertY()) ty = -ty;
                // Entity.turn(tx, ty)
                float stepPitch = (float) ty * k.perCountScale();
                float stepYaw = (float) tx * k.perCountScale();
                pitch = k.turnPitch(pitch, stepPitch);
                yaw = k.turnYaw(yaw, stepYaw);
            }
            boolean changed = !sentOnce || (double) (yaw - yawLast) != 0 || (double) (pitch - pitchLast) != 0;
            if (!changed) return null;
            yawLast = yaw; pitchLast = pitch; sentOnce = true;
            return new float[]{yaw, pitch};
        }
    }

    static Stream legitimate(String name, long seed, ClientOptions options, int ticks) {
        var k = kinematics();
        var c = new Client(k, options, seed);
        List<Event> events = new ArrayList<>();
        long seq = 1;
        for (int t = 0; t < ticks; t++) {
            float[] out = c.tick();
            if (out != null) events.add(Event.rotation(seq, out[0], out[1]));
            seq += 1 + c.rnd.nextInt(3); // other packets interleave
        }
        return new Stream(name, seed, options.sensitivity(), events);
    }

    /** Legitimate stream whose sensitivity changes halfway (the options screen is client-only; the server sees only the new unit). */
    static Stream sensitivityChange(long seed, double before, double after, int ticks) {
        var k = kinematics();
        var c = new Client(k, ClientOptions.raw(before), seed);
        List<Event> events = new ArrayList<>();
        long seq = 1;
        for (int t = 0; t < ticks; t++) {
            if (t == ticks / 2) c.o = c.o.withSensitivity(after);
            float[] out = c.tick();
            if (out != null) events.add(Event.rotation(seq, out[0], out[1]));
            seq += 1;
        }
        return new Stream("sensitivity-change", seed, before, events);
    }

    /** Legitimate stream with a server teleport mid-way: the client adopts the server rotation (not a mouse turn). */
    static Stream teleportMidway(long seed, double sensitivity, int ticks) {
        var k = kinematics();
        var c = new Client(k, ClientOptions.raw(sensitivity), seed);
        List<Event> events = new ArrayList<>();
        long seq = 1;
        for (int t = 0; t < ticks; t++) {
            if (t == ticks / 2) {
                events.add(Event.breakAt(RotationHistory.Break.TELEPORT_ACCEPTED));
                c.yaw = 123.456f; c.pitch = -12.5f; // server-chosen rotation
            }
            float[] out = c.tick();
            if (out != null) events.add(Event.rotation(seq, out[0], out[1]));
            seq += 1;
        }
        return new Stream("teleport-midway", seed, sensitivity, events);
    }

    // ---------------------------------------------------------------- adversarial (synthetic automation shapes)

    enum Adversary {
        /** Smooth aim assist: each tick turns a fixed fraction of the remaining angle to a moving target (arbitrary floats). */
        SMOOTH_AIM_ASSIST,
        /** GCD-aware aim assist: the same, rounded to whole multiples of the player's real unit (a known quantization bypass). */
        GCD_AWARE_AIM_ASSIST,
        /** Snap aim: rotation jumps straight to the target, then holds still. */
        SNAP_AIM,
        /** Spin: a constant large yaw step every tick. */
        SPIN,
        /** Pitch outside the native clamp range ("derp"). */
        PITCH_OUT_OF_RANGE,
        /** Uniform random rotation noise. */
        RANDOM_NOISE,
        /** Aim assist that changes only yaw (pitch held exactly). */
        YAW_ONLY_ASSIST
    }

    static Stream adversarial(Adversary kind, long seed, double sensitivity, int ticks) {
        var k = kinematics();
        var rnd = new SplittableRandom(seed);
        float unit = k.unit(sensitivity, false);
        List<Event> events = new ArrayList<>();
        float yaw = 10f, pitch = 5f;
        double targetYaw = 40, targetPitch = 0, tvYaw = 3, tvPitch = 0.5;
        long seq = 1;
        int hold = 0;
        for (int t = 0; t < ticks; t++) {
            targetYaw += tvYaw + (rnd.nextDouble() - 0.5) * 2; targetPitch = Math.clamp(targetPitch + tvPitch * (rnd.nextDouble() - 0.5), -60, 60);
            if (rnd.nextDouble() < 0.05) tvYaw = (rnd.nextDouble() - 0.5) * 12;
            float ny = yaw, np = pitch;
            switch (kind) {
                case SMOOTH_AIM_ASSIST -> { ny = (float) (yaw + (targetYaw - yaw) * 0.35); np = (float) (pitch + (targetPitch - pitch) * 0.35); }
                case GCD_AWARE_AIM_ASSIST -> {
                    double wy = (targetYaw - yaw) * 0.35, wp = (targetPitch - pitch) * 0.35;
                    ny = yaw + (float) Math.rint(wy / unit) * unit; np = k.turnPitch(pitch, (float) Math.rint(wp / unit) * unit);
                }
                case SNAP_AIM -> {
                    if (hold-- <= 0) { ny = (float) targetYaw; np = (float) targetPitch; hold = 4 + rnd.nextInt(6); }
                }
                case SPIN -> { ny = yaw + 45f; np = pitch; }
                case PITCH_OUT_OF_RANGE -> { ny = yaw + (float) Math.rint((rnd.nextDouble() - 0.5) * 20) * unit; np = 90f + 1f + rnd.nextInt(90); }
                case RANDOM_NOISE -> { ny = yaw + (float) ((rnd.nextDouble() - 0.5) * 20); np = Math.clamp(pitch + (float) ((rnd.nextDouble() - 0.5) * 10), -90f, 90f); }
                case YAW_ONLY_ASSIST -> { ny = (float) (yaw + (targetYaw - yaw) * 0.35); np = pitch; }
            }
            if (ny - yaw != 0 || np - pitch != 0) events.add(Event.rotation(seq, ny, np));
            yaw = ny; pitch = np;
            seq += 1;
        }
        return new Stream("adversarial-" + kind, seed, sensitivity, events);
    }

    /** Replays a stream into a fresh bounded history, calling {@code onObserve} after every observation. */
    static void replay(Stream s, RotationHistory h, Runnable onObserve) {
        for (Event e : s.events()) {
            if (e.breakReason() != null) h.breakSegment(e.breakReason());
            else { h.observe(e.sequence(), e.rotation()); onObserve.run(); }
        }
    }
}
