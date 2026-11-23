package me.nicolas.papea2ac.player;
import me.nicolas.papea2ac.model.Rotation;
import me.nicolas.papea2ac.model.Vector3;
import java.util.Objects;
/**
 * Phase 7 timing vocabulary (player-owned, LATENCY_COMPENSATION.md N1 "model outbound ordering, write outcomes, protocol
 * acknowledgements and client application uncertainty explicitly"): the ordered facts the TimingLedger retains about one
 * session. Every event carries the two clocks PLAYER_TIMELINE.md L1 keeps apart - {@code sequence}, the session's capture
 * sequence (the sole ordering key), and {@code nanos}, the capture MONOTONIC timestamp of the observation (the encoder tap for
 * outbound, the pre-process tap for inbound) - and never a wall-clock value. Player cannot import protocol types (the
 * approved DAG), so core translates Phase 1 observations into these values, exactly as it does for PlayerObservation.
 */
public sealed interface TimingEvent {
    long sequence();
    long nanos();
    int chargedBytes();
    /** The two request/response mechanisms the pinned play protocol exposes that a client answers without game-thread involvement. */
    enum MarkerKind { KEEPALIVE, PING }
    /** External motion operation as the client applies it (PhysicsEnvironment.ExternalMotion.Operation mirrored without the physics import). */
    enum Operation { SET, ADD, UNRESOLVED }
    /** Relative flags of a position correction as the client interprets them (mirrors the protocol's Relative set without importing it). */
    int RELATIVE_X = 1, RELATIVE_Y = 2, RELATIVE_Z = 4, RELATIVE_Y_ROT = 8, RELATIVE_X_ROT = 16, RELATIVE_DELTA_X = 32, RELATIVE_DELTA_Y = 64, RELATIVE_DELTA_Z = 128, RELATIVE_ROTATE_DELTA = 256;

    /** An inbound client movement claim (position and/or rotation), i.e. one client tick's transmission. */
    record MovementObserved(long sequence, long nanos, boolean hasPosition, Vector3 position, boolean hasRotation, Rotation rotation, boolean onGround) implements TimingEvent {
        public MovementObserved { check(sequence); Objects.requireNonNull(position); Objects.requireNonNull(rotation); }
        public int chargedBytes() { return 96; }
    }
    /** The client's end-of-tick packet: one per client tick while in a level (26.2 client send rule, verified per session by its presence). */
    record ClientTickEnd(long sequence, long nanos) implements TimingEvent {
        public ClientTickEnd { check(sequence); }
        public int chargedBytes() { return 32; }
    }
    /** An outbound marker (keep-alive or ping) at its wire ordinal. */
    record MarkerSent(long sequence, long nanos, MarkerKind kind, long id, long wireOrdinal) implements TimingEvent {
        public MarkerSent { check(sequence); Objects.requireNonNull(kind); if (wireOrdinal < 1) throw new IllegalArgumentException("wireOrdinal"); }
        public int chargedBytes() { return 48; }
    }
    /** The client's answer to a marker (keep-alive reply or pong). Receipt evidence only, never application (N1). */
    record MarkerAcknowledged(long sequence, long nanos, MarkerKind kind, long id) implements TimingEvent {
        public MarkerAcknowledged { check(sequence); Objects.requireNonNull(kind); }
        public int chargedBytes() { return 40; }
    }
    /** The transport's write callback for an outbound wire ordinal; success proves handoff, not client application. */
    record WriteResolved(long sequence, long nanos, long wireOrdinal, boolean success) implements TimingEvent {
        public WriteResolved { check(sequence); if (wireOrdinal < 1) throw new IllegalArgumentException("wireOrdinal"); }
        public int chargedBytes() { return 40; }
    }
    /** An outbound position correction (teleport) with the client-side interpretation fields: absolute/relative per axis, the delta movement it installs. */
    record TeleportSent(long sequence, long nanos, long teleportId, long wireOrdinal, Vector3 position, Rotation rotation, Vector3 delta, int relativeMask) implements TimingEvent {
        public TeleportSent { check(sequence); Objects.requireNonNull(position); Objects.requireNonNull(rotation); Objects.requireNonNull(delta); if (teleportId < 0 || wireOrdinal < 1 || relativeMask < 0 || relativeMask > 511) throw new IllegalArgumentException("teleport"); }
        public boolean relative(int flag) { return (relativeMask & flag) != 0; }
        public int chargedBytes() { return 112; }
    }
    /** The client's teleport-confirm control packet (sent from its game thread right after applying the correction, followed by the corrected position). */
    record TeleportConfirmed(long sequence, long nanos, long teleportId) implements TimingEvent {
        public TeleportConfirmed { check(sequence); if (teleportId < 0) throw new IllegalArgumentException("teleportId"); }
        public int chargedBytes() { return 40; }
    }
    /** An outbound external-motion influence for THIS player (velocity packet: SET; explosion knockback: ADD) at its wire ordinal. */
    record ExternalMotionSent(long sequence, long nanos, long wireOrdinal, Operation operation, Vector3 vector, String source) implements TimingEvent {
        public ExternalMotionSent { check(sequence); Objects.requireNonNull(operation); Objects.requireNonNull(vector); Objects.requireNonNull(source); if (wireOrdinal < 1 || source.length() > 64) throw new IllegalArgumentException("motion"); }
        /** Stable identity of the influence inside its session: the wire ordinal is unique per connection. */
        public String id() { return "m" + wireOrdinal; }
        public int chargedBytes() { return 96 + source.length(); }
    }
    /** A server tick boundary observed by the session. */
    record ServerTick(long sequence, long nanos, long serverTick) implements TimingEvent {
        public ServerTick { check(sequence); if (serverTick < 0) throw new IllegalArgumentException("serverTick"); }
        public int chargedBytes() { return 40; }
    }
    /** World epoch boundary (respawn / dimension change): no timing hypothesis crosses it as ordinary movement. */
    record Discontinuity(long sequence, long nanos, long newWorldEpoch) implements TimingEvent {
        public Discontinuity { check(sequence); if (newWorldEpoch < 0) throw new IllegalArgumentException("worldEpoch"); }
        public int chargedBytes() { return 40; }
    }
    /** A Phase 1 transport coverage break: everything after it is uncertain until the session is replaced. */
    record CoverageBreak(long sequence, long nanos, String reason) implements TimingEvent {
        public CoverageBreak { check(sequence); Objects.requireNonNull(reason); if (reason.length() > 64) throw new IllegalArgumentException("reason"); }
        public int chargedBytes() { return 64 + reason.length(); }
    }
    /**
     * Phase 13: the client's ATTACK on an entity. The 26.2 client sends it from {@code handleKeybinds}, before that tick's movement packet, and
     * then runs {@code Player.attack} locally, which may slow and un-sprint the attacker before the same tick's movement (attacker slowdown).
     * Whether it did depends on client facts the server does not see (attack cooldown, held-item knockback, the target's {@code hurtClient}),
     * so the resolver keeps both outcomes. The event is no evidence that the server accepted the attack.
     */
    record LocalAttack(long sequence, long nanos, int targetEntityId) implements TimingEvent {
        public LocalAttack { check(sequence); }
        public int chargedBytes() { return 40; }
    }
    private static void check(long sequence) { if (sequence < 1) throw new IllegalArgumentException("sequence"); }
}
