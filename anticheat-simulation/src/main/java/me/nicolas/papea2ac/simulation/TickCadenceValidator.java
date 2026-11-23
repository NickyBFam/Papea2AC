package me.nicolas.papea2ac.simulation;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.player.TickCadenceLedger;
import me.nicolas.papea2ac.version.TickCadenceProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
/**
 * Phase 14 cadence validation: a pure, stateless bound on the client's tick count between game-thread fences.
 *
 * <p>For the newest anchor B and every retained earlier anchor A of the same segment, the window is the stream interval between pong A and
 * pong B. Its ticks were executed after the client's game thread processed ping A (which left the encoder tap at {@code A.pingNanos}) and
 * before it wrote pong B (which reached the pre-process tap at {@code B.answerNanos}), so their real-time span is at most
 * {@code B.answerNanos - A.pingNanos} whatever the network did in between: delay, jitter, stalls in either direction and server-side pauses
 * only widen it. The profile turns that span into the most ticks a vanilla client can execute ({@link TickCadenceProfile#maxTicks}). The ticks
 * the stream proves were executed are at least the tick ends between the pongs, and at least the movement packets between them minus the
 * outbound packets that make the client send a movement outside its tick and that it processed in the window (those whose wire position
 * lies between ping A and ping B: the client handles its packets in wire order on the same game thread). A window whose proven ticks exceed
 * its bound is an exclusion; when every window holds, the target is VALID with the tightest margin as its witness.
 *
 * <p>Nothing here reads a clock, keeps state between calls, sorts by score or prunes a window: every retained anchor is evaluated, in order.
 */
public final class TickCadenceValidator {
    public TickCadenceResult evaluate(SessionKey session, List<TickCadenceLedger.Anchor> anchors, TickCadenceProfile profile, boolean profileSupported) {
        Objects.requireNonNull(session); Objects.requireNonNull(anchors); Objects.requireNonNull(profile);
        if (anchors.isEmpty()) throw new IllegalArgumentException("no target anchor");
        TickCadenceLedger.Anchor target = anchors.get(anchors.size() - 1);
        int evaluated = 0, skipped = 0, worstIndex = -1; long beyond = 0, worstExcess = Long.MIN_VALUE;
        for (int i = 0; i < anchors.size() - 1; i++) {
            TickCadenceLedger.Anchor from = anchors.get(i);
            if (from.segment() != target.segment() || from.answerSequence() >= target.answerSequence()) { skipped++; continue; }
            long max = profile.maxTicks(target.answerNanos() - from.pingNanos());
            if (max < 0) { skipped++; continue; }
            long tickEnds = target.tickEnds() - from.tickEnds(), movementTicks = movementTicks(from, target);
            beyond = Math.max(beyond, movementTicks - tickEnds);
            long excess = Math.max(tickEnds, movementTicks) - max;
            evaluated++;
            if (excess > worstExcess) { worstExcess = excess; worstIndex = i; } // the first window reaching the largest excess
        }
        TickCadenceResult.Window worst = worstIndex < 0 ? null : window(anchors.get(worstIndex), target, profile);
        List<TickCadenceResult.UncertainCode> uncertain = new ArrayList<>(2);
        if (!profileSupported) uncertain.add(TickCadenceResult.UncertainCode.PROFILE_UNSUPPORTED);
        if (evaluated == 0) uncertain.add(skipped == 0 ? TickCadenceResult.UncertainCode.NO_PRIOR_ANCHOR : TickCadenceResult.UncertainCode.WINDOW_OUT_OF_RANGE);
        if (!uncertain.isEmpty()) return new TickCadenceResult(session, target.segment(), target.answerSequence(), target.answerNanos(), TickCadenceResult.Classification.UNCERTAIN, uncertain, List.of(), worst, evaluated, skipped, beyond, profile.id());
        if (worst.excess() > 0) {
            TickCadenceResult.InvalidCode code = worst.source() == TickCadenceResult.Source.MOVEMENT ? TickCadenceResult.InvalidCode.MOVEMENT_EXCESS : TickCadenceResult.InvalidCode.TICK_END_EXCESS;
            return new TickCadenceResult(session, target.segment(), target.answerSequence(), target.answerNanos(), TickCadenceResult.Classification.INVALID, List.of(), List.of(code), worst, evaluated, skipped, beyond, profile.id());
        }
        return new TickCadenceResult(session, target.segment(), target.answerSequence(), target.answerNanos(), TickCadenceResult.Classification.VALID, List.of(), List.of(), worst, evaluated, skipped, beyond, profile.id());
    }
    /** Movement packets between the pongs minus the packets the client processed between the two pings that make it send one outside its tick
     *  (wire order); 0 when the target's ping precedes the earlier ping on the wire (no vanilla client answers out of order). */
    private static long movementTicks(TickCadenceLedger.Anchor from, TickCadenceLedger.Anchor target) {
        if (target.pingWireOrdinal() <= from.pingWireOrdinal()) return 0;
        return Math.max(0, (target.movements() - from.movements()) - (target.inducedBeforePing() - from.inducedBeforePing()));
    }
    /** The proof record of one window (built only for the reported window: the loop above allocates nothing per window). */
    private static TickCadenceResult.Window window(TickCadenceLedger.Anchor from, TickCadenceLedger.Anchor target, TickCadenceProfile profile) {
        long span = target.answerNanos() - from.pingNanos(), max = profile.maxTicks(span);
        long tickEnds = target.tickEnds() - from.tickEnds(), movements = target.movements() - from.movements();
        long induced = target.pingWireOrdinal() > from.pingWireOrdinal() ? target.inducedBeforePing() - from.inducedBeforePing() : 0, movementTicks = movementTicks(from, target);
        long executed = Math.max(tickEnds, movementTicks);
        return new TickCadenceResult.Window(from.pingSequence(), from.answerSequence(), target.answerSequence(), span, tickEnds, movements, induced, executed,
            movementTicks > tickEnds ? TickCadenceResult.Source.MOVEMENT : TickCadenceResult.Source.TICK_END, max, executed - max);
    }
}
