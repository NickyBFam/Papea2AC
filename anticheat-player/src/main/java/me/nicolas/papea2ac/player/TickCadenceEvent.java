package me.nicolas.papea2ac.player;
import java.util.Objects;
/**
 * Phase 14 cadence vocabulary (player-owned; player cannot import protocol types, so core translates Phase 1 observations into these
 * values exactly as it does for {@link TimingEvent}). The facts the {@link TickCadenceLedger} needs to bound how many client ticks happened
 * between two game-thread fences: the client's tick ends and movement transmissions, the server's ping markers and the client's pongs
 * (the fences), write failures of those markers, the outbound packets whose handling makes the client send an extra movement packet
 * outside its tick, and the server's tick-rate state (diagnostic: the client never ticks faster than 20 Hz whatever it is).
 *
 * <p>Every event carries the session capture sequence (the sole ordering key) and the capture-monotonic nanos of its observation (encoder
 * tap for outbound, pre-process tap for inbound); never a wall-clock value.
 */
public sealed interface TickCadenceEvent {
    long sequence();
    long nanos();
    /** Outbound packets whose client handler sends one movement packet outside the client tick ({@code ClientPacketListener}: the
     *  correction's PosRot, the rotation correction's Rot, the PosRot of a teleport of the player's removed vehicle). */
    enum Induced { CORRECTION, ROTATION, ENTITY_TELEPORT }

    /** The client's end-of-tick packet. */
    record TickEnd(long sequence, long nanos) implements TickCadenceEvent { public TickEnd { check(sequence); } }
    /** An inbound player movement packet (any position/rotation/status variant). */
    record Movement(long sequence, long nanos) implements TickCadenceEvent { public Movement { check(sequence); } }
    /** An outbound ping marker (whoever issued it) at its wire ordinal. */
    record PingSent(long sequence, long nanos, long id, long wireOrdinal) implements TickCadenceEvent {
        public PingSent { check(sequence); if (wireOrdinal < 1) throw new IllegalArgumentException("wireOrdinal"); }
    }
    /** The client's pong. */
    record PingAnswered(long sequence, long nanos, long id) implements TickCadenceEvent { public PingAnswered { check(sequence); } }
    /** A failed outbound write: if it was a ping, the client never received it. */
    record WriteFailed(long sequence, long nanos, long wireOrdinal) implements TickCadenceEvent {
        public WriteFailed { check(sequence); if (wireOrdinal < 1) throw new IllegalArgumentException("wireOrdinal"); }
    }
    /** An outbound packet that may make the client send one movement packet outside its tick. */
    record InducedSent(long sequence, long nanos, long wireOrdinal, Induced kind) implements TickCadenceEvent {
        public InducedSent { check(sequence); Objects.requireNonNull(kind); if (wireOrdinal < 1) throw new IllegalArgumentException("wireOrdinal"); }
    }
    /** The server's ticking state (rate, frozen) at its wire ordinal. It can only slow a client down (never relevant to the upper bound);
     *  the ledger tracks when the client has provably applied it (the tick-rate signal). */
    record TickRate(long sequence, long nanos, long wireOrdinal, float rate, boolean frozen) implements TickCadenceEvent {
        public TickRate { check(sequence); if (wireOrdinal < 1 || !Float.isFinite(rate) || rate <= 0) throw new IllegalArgumentException("rate"); }
    }
    /** A transport coverage break (loss class): anchors before it are never paired with anchors after it. */
    record CoverageBreak(long sequence, long nanos, String reason) implements TickCadenceEvent {
        public CoverageBreak { check(sequence); Objects.requireNonNull(reason); if (reason.length() > 64) throw new IllegalArgumentException("reason"); }
    }
    private static void check(long sequence) { if (sequence < 1) throw new IllegalArgumentException("sequence"); }
}
