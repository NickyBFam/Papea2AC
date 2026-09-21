package me.nicolas.papea2ac.simulation;
import me.nicolas.papea2ac.player.TimingEvent;
import java.util.List;
import java.util.Objects;
import java.util.Set;
/**
 * One bounded, provenance-carrying account of WHEN things happened between the anchor and the target transmission: how many
 * client ticks elapsed and what each transmitted (the observation list Phase 6 consumes), which influences were applied
 * before which tick (the Phase 6 external-motion branch), which stay pending afterwards, how the interval relates to a
 * position correction, which fences produced the account, and the RTT evidence in effect. A hypothesis is never a verdict
 * and never ranked: Phase 6 reconstructs legal inputs under each one, and consumers keep the union.
 *
 * @param horizon            client ticks from the anchor's tick to the target's tick (0 only for a teleport application point)
 * @param ticks              per-tick observations (silent intermediate ticks as WithinSendThreshold, the target exact)
 * @param branch             the external-motion application scenario per tick (Phase 6 branch)
 * @param appliedInfluences  influence ids applied inside this interval under this hypothesis
 * @param pendingInfluences  influence ids still unresolved after the target under this hypothesis
 * @param expiredInfluences  influence ids retired as EXPIRED in this resolution (evidence unavailable, not "not applied")
 * @param teleport           the interval's relation to a correction
 * @param alignment          how the tick horizon was established
 * @param fences             fences consulted (tick-end sequences, marker answers, confirms) - bounded text, provenance only
 * @param preTicks           for a TELEPORT application point: client ticks that elapsed before the correction was applied (the pre-application state comes from them)
 * @param closureTicks       leading silent client ticks not in {@code ticks}: the adapter covers them with the exact silent closure (single silent ticks iterated to a superset fixed point)
 */
public record TimingHypothesis(String id, int horizon, List<TickObservation> ticks, ReconstructionRequest.ExternalMotionBranch branch, Set<String> appliedInfluences, Set<String> pendingInfluences,
                               Set<String> expiredInfluences, TeleportRelation teleport, TimingEvent.TeleportSent correction, Alignment alignment, List<String> fences, RttWindow rtt, int preTicks, int closureTicks) {
    public enum TeleportRelation { NONE, PRE_APPLICATION, APPLICATION_POINT }
    public enum Alignment { TICK_END_EXACT, BOUNDED_FALLBACK, TELEPORT_CONFIRM }
    /** The round-trip envelope in effect (nanos), or absent. RTT sizes horizons and is reported; it never selects a branch. */
    public record RttWindow(boolean present, long lowNanos, long highNanos, boolean stale) { public static final RttWindow ABSENT = new RttWindow(false, 0, 0, true); }
    public TimingHypothesis {
        Objects.requireNonNull(id); ticks = List.copyOf(ticks); Objects.requireNonNull(branch); appliedInfluences = Set.copyOf(appliedInfluences); pendingInfluences = Set.copyOf(pendingInfluences);
        expiredInfluences = Set.copyOf(expiredInfluences); Objects.requireNonNull(teleport); Objects.requireNonNull(alignment); fences = List.copyOf(fences); Objects.requireNonNull(rtt);
        if (horizon < 0 || preTicks < 0 || closureTicks < 0) throw new IllegalArgumentException("horizon");
        if (teleport == TeleportRelation.APPLICATION_POINT ? (horizon != 0 || correction == null) : (horizon != ticks.size() || horizon < 1)) throw new IllegalArgumentException("ticks/horizon");
    }
    /** Two hypotheses are equivalent when they lead to the same physics question: same tick observations, same per-tick motions, same correction relation and pending set. */
    public boolean equivalentTo(TimingHypothesis o) {
        return horizon == o.horizon && ticks.equals(o.ticks) && branch.motionsPerTick().equals(o.branch.motionsPerTick()) && teleport == o.teleport && Objects.equals(correction, o.correction) && pendingInfluences.equals(o.pendingInfluences) && preTicks == o.preTicks && closureTicks == o.closureTicks;
    }
    public String describe() {
        StringBuilder sb = new StringBuilder(id).append(": ").append(teleport == TeleportRelation.APPLICATION_POINT ? "teleport application (pre-ticks " + preTicks + ")" : (closureTicks > 0 ? closureTicks + " silent closure ticks + " : "") + horizon + " tick(s) [" + alignment + "]");
        if (!appliedInfluences.isEmpty()) { sb.append(" applied="); for (int t = 0; t < branch.motionsPerTick().size(); t++) if (!branch.motionsPerTick().get(t).isEmpty()) { sb.append("t").append(t + 1).append(':'); for (var m : branch.motionsPerTick().get(t)) sb.append(m.sourceId()).append('/').append(m.operation()).append(' '); } }
        if (!pendingInfluences.isEmpty()) sb.append(" pending=").append(pendingInfluences);
        if (!expiredInfluences.isEmpty()) sb.append(" expired=").append(expiredInfluences);
        if (teleport == TeleportRelation.PRE_APPLICATION) sb.append(" (correction pending, pre-application)");
        return sb.toString();
    }
}
