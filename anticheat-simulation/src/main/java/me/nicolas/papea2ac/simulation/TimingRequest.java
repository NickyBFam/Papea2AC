package me.nicolas.papea2ac.simulation;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.player.RttEstimator;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.player.TimingLedger;
import me.nicolas.papea2ac.version.TimingProfile;
import java.util.List;
import java.util.Objects;
/**
 * Immutable input of one timing resolution: explain how the client got from the ANCHOR transmission (the state the caller's
 * frontier represents - the client state right after the anchor's tick) to the TARGET transmission, using only the ordered
 * timing events strictly between them (plus the target itself), the influences the caller still carries as unresolved, the
 * corrections that may be pending, and the session's RTT evidence.
 *
 * <p>Clocks are the ledger's: {@code sequence} orders, {@code nanos} is capture-monotonic. No wall clock, no "ping".
 *
 * @param events            ordered events with anchorSequence < sequence <= target.sequence (the anchor excluded, the target included)
 * @param anchorSequence    the anchor transmission's capture sequence (0 = a rest hypothesis with no transmission)
 * @param anchorInTick      whether the anchor was sent from inside a client tick (an ordinary movement packet: its own tick end follows it) or between ticks
 *                          (a correction's confirm position, sent from the packet handler: the next tick end already closes tick 1)
 * @param anchorNanos       its capture nanos (or the caller's monotonic start)
 * @param lastSentPosition  the last position the client transmitted at or before the anchor (the send-threshold reference)
 * @param lastYaw           the yaw in effect after the anchor
 * @param target            the movement transmission to explain
 * @param pendingInfluences influences sent before the target and not yet consumed in the caller's branch (may include events older than the slice)
 * @param teleports         the ledger's pending/confirmed corrections that could concern this interval
 * @param rtt               the session's RTT evidence (may be EMPTY)
 * @param tickEndCapable    whether this session has shown the client tick-end packet at all (the alignment source is only used when the client demonstrably sends it)
 * @param coverageDegraded  Phase 1 transport health degraded for this session (packets may be missing: counts are not trustworthy)
 * @param worldEpoch        the caller's world epoch at the anchor
 * @param nowNanos          the evaluation time of the interval on the capture-monotonic clock: for a live consumer its now, for an offline walk
 *                          the target's capture nanos (influence expiry is judged against it - an influence older than the horizon at a later
 *                          report time was still live inside the interval that applied it, P7-F7)
 */
public record TimingRequest(List<TimingEvent> events, long anchorSequence, long anchorNanos, boolean anchorInTick, Vector3 lastSentPosition, float lastYaw, TimingEvent.MovementObserved target,
                            List<TimingEvent.ExternalMotionSent> pendingInfluences, List<TimingLedger.PendingTeleport> teleports, RttEstimator.Summary rtt, boolean tickEndCapable,
                            boolean coverageDegraded, long worldEpoch, long nowNanos, TimingProfile profile, TimingBudget budget) {
    public TimingRequest {
        events = List.copyOf(events); Objects.requireNonNull(lastSentPosition); Objects.requireNonNull(target); pendingInfluences = List.copyOf(pendingInfluences); teleports = List.copyOf(teleports);
        Objects.requireNonNull(rtt); Objects.requireNonNull(profile); Objects.requireNonNull(budget);
        if (anchorSequence < 0 || worldEpoch < 0) throw new IllegalArgumentException("sequence/epoch");
        if (target.sequence() <= anchorSequence) throw new IllegalArgumentException("target must follow the anchor");
        long previous = anchorSequence;
        for (TimingEvent e : events) { if (e.sequence() <= previous || e.sequence() > target.sequence()) throw new IllegalArgumentException("events must be ordered within (anchor, target]"); previous = e.sequence(); }
    }
    public TimingRequest withPendingInfluences(List<TimingEvent.ExternalMotionSent> p) { return new TimingRequest(events, anchorSequence, anchorNanos, anchorInTick, lastSentPosition, lastYaw, target, p, teleports, rtt, tickEndCapable, coverageDegraded, worldEpoch, nowNanos, profile, budget); }
    public TimingRequest withBudget(TimingBudget b) { return new TimingRequest(events, anchorSequence, anchorNanos, anchorInTick, lastSentPosition, lastYaw, target, pendingInfluences, teleports, rtt, tickEndCapable, coverageDegraded, worldEpoch, nowNanos, profile, b); }
    public TimingRequest withProfile(TimingProfile p) { return new TimingRequest(events, anchorSequence, anchorNanos, anchorInTick, lastSentPosition, lastYaw, target, pendingInfluences, teleports, rtt, tickEndCapable, coverageDegraded, worldEpoch, nowNanos, p, budget); }
}
