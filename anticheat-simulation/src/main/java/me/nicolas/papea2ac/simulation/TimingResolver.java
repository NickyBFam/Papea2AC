package me.nicolas.papea2ac.simulation;
import me.nicolas.papea2ac.model.Vector3;
import me.nicolas.papea2ac.physics.PhysicsEnvironment;
import me.nicolas.papea2ac.physics.Vec3d;
import me.nicolas.papea2ac.player.TimingEvent;
import me.nicolas.papea2ac.player.TimingLedger;
import me.nicolas.papea2ac.version.MovementProfile;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
/**
 * Phase 7 timing resolver (LATENCY_COMPENSATION.md N1/N2, PLAYER_TIMELINE.md L1): turns the ordered timing facts between two
 * client transmissions into a BOUNDED set of {@link TimingHypothesis}es - never one instant, never a ranked guess.
 *
 * <p>Alignment (how many client ticks the interval spans) rests on the 26.2 client's per-tick {@code ClientTickEnd} packet: the
 * number of tick ends strictly between the two transmissions is the exact horizon (TCP keeps the client's stream order; the
 * tick end of a tick follows that tick's movement packet). A session that has never shown a tick end, or whose transport
 * coverage is degraded, gets the bounded fallback (1..3 ticks) with {@code complete=false}. Silent intermediate ticks are
 * {@code WithinSendThreshold} observations (the client's own send rule); rotation-only packets give their tick's yaw.
 *
 * <p>External motion (velocity SET, explosion ADD): an influence sent at capture time {@code t} cannot have applied to a client
 * tick whose tick-end packet was captured BEFORE {@code t} (that tick ended before its packet reached the server, which is
 * before the influence left it). Every later tick in the interval is a possible application tick, and "not yet applied" stays
 * a branch (N1: expiry is unavailable evidence, never proof of non-application) until the version's application horizon
 * passes, after which the influence is retired as EXPIRED. Ordered influences apply in wire order (in-order client processing).
 * A marker answer never narrows application unless the profile claims it ({@code pongBoundsApplication}, not claimed).
 *
 * <p>Corrections: with the 26.2 client's game-thread confirm, the first position after a confirm is the correction's
 * application point (horizon 0, no physics step); transmissions before a still-unconfirmed correction are pre-application
 * ordinary movement. A world-epoch discontinuity inside the interval is never crossed.
 */
public final class TimingResolver {
    /** The client transmits a position at least every 20 ticks (MovementObservationProfile reminder): a silent run never exceeds it; the exact silent closure is bounded accordingly. */
    public static final int MAX_CLOSURE_TICKS = 20;
    /**
     * The tick ends captured between the anchor and the target. For an in-tick anchor (an ordinary movement packet) {@code ends.get(0)}
     * closes the ANCHOR's own tick (tick 0, already accounted for by the anchor state) and {@code ends.get(j)} closes intermediate tick j
     * (1 <= j < k), so the horizon k is the count. For a between-ticks anchor (a correction's confirm position, sent from the packet
     * handler) {@code ends.get(0)} already closes tick 1, so the horizon is the count plus one. {@code offset} is the index of tick 1's
     * end (1 or 0); the target's tick (k) ends after the target, unseen here.
     */
    private record Ticks(List<TimingEvent.ClientTickEnd> ends, int k, int offset) {
        /** The end of intermediate tick j (1 <= j < k). */
        TimingEvent.ClientTickEnd endOf(int j) { return ends.get(j - 1 + offset); }
    }

    public TimingResolution resolve(TimingRequest req) {
        TimingBudget budget = req.budget();
        List<String> notes = new ArrayList<>();
        List<TimingEvent.ClientTickEnd> tickEnds = new ArrayList<>(); List<TimingEvent.MovementObserved> rotationOnly = new ArrayList<>();
        List<TimingEvent.ExternalMotionSent> sentHere = new ArrayList<>(); List<TimingEvent.MarkerAcknowledged> answers = new ArrayList<>(); List<TimingEvent.MarkerSent> markers = new ArrayList<>();
        int confirmCount = 0; boolean discontinuityFlag = false, coverageBreakFlag = false;
        List<TimingEvent.AttackerSlowdownPossible> attacks = new ArrayList<>();
        for (TimingEvent e : req.events()) {
            switch (e) {
                case TimingEvent.ClientTickEnd t -> { if (t.sequence() < req.target().sequence()) tickEnds.add(t); }
                case TimingEvent.MovementObserved m -> { if (m.sequence() < req.target().sequence() && !m.hasPosition() && m.hasRotation()) rotationOnly.add(m); }
                case TimingEvent.ExternalMotionSent s -> { if (s.sequence() < req.target().sequence()) sentHere.add(s); }
                case TimingEvent.MarkerAcknowledged a -> answers.add(a);
                case TimingEvent.MarkerSent s -> markers.add(s);
                case TimingEvent.TeleportConfirmed c -> confirmCount++;
                case TimingEvent.Discontinuity d -> discontinuityFlag = true;
                case TimingEvent.CoverageBreak b -> coverageBreakFlag = true;
                case TimingEvent.AttackerSlowdownPossible a -> { if (a.sequence() < req.target().sequence()) attacks.add(a); } // the runner's gated events; raw LocalAttack packets are never branched
                default -> { }
            }
        }
        final int confirms = confirmCount; final boolean discontinuity = discontinuityFlag, coverageBreak = coverageBreakFlag;
        TimingHypothesis.RttWindow rtt = req.rtt().hasSamples() ? new TimingHypothesis.RttWindow(true, req.rtt().envelopeLowNanos(), req.rtt().envelopeHighNanos(), req.rtt().stale(req.nowNanos())) : TimingHypothesis.RttWindow.ABSENT;
        TimingResolution.Provenance prov = new TimingResolution.Provenance(req.anchorSequence(), req.target().sequence(), req.worldEpoch(), req.profile().id());
        java.util.function.Function<Integer, TimingResolution.Evidence> evidence = expired -> new TimingResolution.Evidence(tickEnds.size(), rotationOnly.size(), answers.size(), confirms, req.tickEndCapable(), rtt.present(), rtt.stale(), rtt.lowNanos(), rtt.highNanos(), 0, expired);
        if (discontinuity) return new TimingResolution(TimingResolution.Status.WORLD_DISCONTINUITY, List.of(), true, evidence.apply(0), new TimingResolution.Work(0, 0, 0, 0, 0), prov, List.of("a world-epoch boundary lies inside the interval: no ordinary-movement hypothesis crosses it"));
        if (!rtt.present()) notes.add("no round-trip evidence in this session yet (markers/keep-alives unanswered): horizons are not RTT-sized, hypotheses are still enumerated");
        else if (rtt.stale()) notes.add("round-trip evidence is stale (" + req.rtt().ageNanos(req.nowNanos()) / 1_000_000 + " ms old)");

        // ---- corrections
        TimingLedger.PendingTeleport applied = null; boolean preApplication = false;
        for (TimingLedger.PendingTeleport t : req.teleports()) {
            if (t.confirmed() && t.confirmedSequence() > req.anchorSequence() && t.confirmedSequence() < req.target().sequence()) { if (applied == null || t.confirmedSequence() > applied.confirmedSequence()) applied = t; }
            // pending AT THE TARGET: sent before it and not confirmed before it (a later confirm or supersession does not change what the client knew then)
            else if (!t.writeFailed() && t.sent().sequence() < req.target().sequence() && (!t.confirmed() || t.confirmedSequence() > req.target().sequence())) preApplication = true;
        }
        int offset = req.anchorInTick() ? 1 : 0;
        Ticks ticks = new Ticks(tickEnds, tickEnds.size() + 1 - offset, offset);
        if (applied != null && req.profile().teleportConfirmIsGameThreadFence()) {
            int endsBeforeConfirm = 0; for (TimingEvent.ClientTickEnd t : tickEnds) if (t.sequence() < applied.confirmedSequence()) endsBeforeConfirm++;
            int preTicks = Math.max(0, endsBeforeConfirm - offset); // for an in-tick anchor the first end closes the anchor's own tick
            TimingEvent.TeleportSent c = applied.sent();
            boolean relative = (c.relativeMask() & (TimingEvent.RELATIVE_X | TimingEvent.RELATIVE_Y | TimingEvent.RELATIVE_Z | TimingEvent.RELATIVE_DELTA_X | TimingEvent.RELATIVE_DELTA_Y | TimingEvent.RELATIVE_DELTA_Z | TimingEvent.RELATIVE_ROTATE_DELTA)) != 0;
            if (preTicks > budget.maxHorizon() + MAX_CLOSURE_TICKS) return new TimingResolution(TimingResolution.Status.TELEPORT_UNRESOLVED, List.of(), false, evidence.apply(0), new TimingResolution.Work(1, 0, 0, 0, tickEnds.size() + 1), prov, List.of("correction " + c.teleportId() + " applied after " + preTicks + " pre-application ticks (beyond the silent closure): the pre-application state cannot be reconstructed"));
            if (relative && preTicks > 0) notes.add("relative correction " + c.teleportId() + " after " + preTicks + " pre-application tick(s): the pre-application states come from the exact silent closure");
            if (!req.target().hasPosition()) return new TimingResolution(TimingResolution.Status.TELEPORT_UNRESOLVED, List.of(), false, evidence.apply(0), new TimingResolution.Work(1, 0, 0, 0, tickEnds.size() + 1), prov, List.of("the transmission after confirm " + c.teleportId() + " carries no position"));
            Set<String> pending = new LinkedHashSet<>(); for (TimingEvent.ExternalMotionSent e : req.pendingInfluences()) pending.add(e.id()); for (TimingEvent.ExternalMotionSent e : sentHere) pending.add(e.id());
            TimingHypothesis h = new TimingHypothesis("h0-teleport-" + c.teleportId(), 0, List.of(), ReconstructionRequest.ExternalMotionBranch.NONE, Set.of(), pending, Set.of(), TimingHypothesis.TeleportRelation.APPLICATION_POINT, c, TimingHypothesis.Alignment.TELEPORT_CONFIRM,
                List.of("confirm#" + c.teleportId() + "@" + applied.confirmedSequence(), "preTickEnds=" + preTicks), rtt, preTicks, 0);
            notes.add("correction " + c.teleportId() + " confirmed at sequence " + applied.confirmedSequence() + ": the target is its application point" + (pending.isEmpty() ? "" : "; influences " + pending + " remain pending across it"));
            // P11-F27: an own attack before a relative correction may have slowed the pre-application motion the correction rebases on: not modelled there
            final long confirmedAt = applied.confirmedSequence();
            boolean attackRebased = relative && attacks.stream().anyMatch(a -> a.sequence() < confirmedAt);
            if (attackRebased) notes.add("a possible attacker slowdown precedes relative correction " + c.teleportId() + ": the slowed pre-application motion is not modelled, complete=false");
            return new TimingResolution(TimingResolution.Status.RESOLVED_UNIQUE, List.of(h), !attackRebased, evidence.apply(0), new TimingResolution.Work(1, 1, 1, 0, tickEnds.size() + 1), prov, notes);
        }
        if (preApplication) notes.add("a correction is pending (sent, not yet confirmed): this transmission is pre-application client movement");

        // ---- alignment (closureTicks > 0: the interval spans more ticks than the horizon; the leading ticks are silent and are covered by the adapter's exact silent closure)
        List<Integer> alignments = new ArrayList<>(); TimingHypothesis.Alignment source; boolean complete = true; TimingResolution.Status gapStatus = null; int closureTicks = 0;
        if (req.coverageDegraded()) { for (int k = 1; k <= budget.maxHorizon(); k++) alignments.add(k); source = TimingHypothesis.Alignment.BOUNDED_FALLBACK; complete = false; gapStatus = TimingResolution.Status.TIMING_GAP; notes.add("transport coverage degraded: tick-end counts are not trustworthy, bounded fallback alignment"); }
        else if (coverageBreak) { for (int k = 1; k <= budget.maxHorizon(); k++) alignments.add(k); source = TimingHypothesis.Alignment.BOUNDED_FALLBACK; complete = false; gapStatus = TimingResolution.Status.TIMING_GAP; notes.add("coverage break inside the interval: bounded fallback alignment"); }
        else if (!req.tickEndCapable()) { for (int k = 1; k <= budget.maxHorizon(); k++) alignments.add(k); source = TimingHypothesis.Alignment.BOUNDED_FALLBACK; complete = false; gapStatus = TimingResolution.Status.TIMING_GAP; notes.add("this session has shown no client tick-end packet: bounded fallback alignment (1.." + budget.maxHorizon() + " ticks)"); }
        else if (tickEnds.isEmpty() && offset == 1) { for (int k = 1; k <= budget.maxHorizon(); k++) alignments.add(k); source = TimingHypothesis.Alignment.BOUNDED_FALLBACK; complete = false; gapStatus = TimingResolution.Status.TIMING_GAP; notes.add("no client tick end between two transmissions (a same-tick pair outside a correction, or a missed packet): bounded fallback alignment"); }
        else if (ticks.k() > budget.maxHorizon() + MAX_CLOSURE_TICKS) return new TimingResolution(TimingResolution.Status.UNSUPPORTED_HORIZON, List.of(), false, evidence.apply(0), new TimingResolution.Work(1, 0, 0, 0, tickEnds.size()), prov, List.of(ticks.k() + " client ticks elapsed (tick ends counted) - beyond the supported horizon of " + budget.maxHorizon() + " plus the " + MAX_CLOSURE_TICKS + "-tick silent closure (the client's position reminder bounds silence at 20 ticks); not truncated, not called complete"));
        else if (ticks.k() > budget.maxHorizon()) { closureTicks = ticks.k() - budget.maxHorizon(); alignments.add(budget.maxHorizon()); source = TimingHypothesis.Alignment.TICK_END_EXACT; notes.add(ticks.k() + " client ticks elapsed: the first " + closureTicks + " are silent (no transmission) and are covered by the exact silent closure, the last " + budget.maxHorizon() + " by the observation list"); }
        else { alignments.add(ticks.k()); source = TimingHypothesis.Alignment.TICK_END_EXACT; }

        // ---- influences
        List<TimingEvent.ExternalMotionSent> influences = new ArrayList<>(req.pendingInfluences());
        for (TimingEvent.ExternalMotionSent s : sentHere) if (influences.stream().noneMatch(p -> p.wireOrdinal() == s.wireOrdinal())) influences.add(s);
        influences.sort((a, b) -> Long.compare(a.wireOrdinal(), b.wireOrdinal()));
        if (influences.size() > budget.maxInfluences()) return new TimingResolution(TimingResolution.Status.UNSUPPORTED, List.of(), false, evidence.apply(0), new TimingResolution.Work(alignments.size(), 0, 0, 0, tickEnds.size()), prov, List.of(influences.size() + " simultaneously unresolved influences exceed the " + budget.maxInfluences() + " cap (UNCERTAIN_APPLICATION_OVERFLOW)"));
        Set<String> expired = new LinkedHashSet<>(); List<TimingEvent.ExternalMotionSent> live = new ArrayList<>(); boolean unresolvedVector = false;
        for (TimingEvent.ExternalMotionSent e : influences) {
            if (req.nowNanos() - e.nanos() > req.profile().applicationHorizonNanos()) { expired.add(e.id()); continue; }
            if (e.operation() == TimingEvent.Operation.UNRESOLVED) unresolvedVector = true;
            live.add(e);
        }
        if (!expired.isEmpty()) notes.add("influences " + expired + " passed the application horizon unresolved: retired as EXPIRED (evidence unavailable, not non-application)");
        if (closureTicks > 0) {
            boolean rotationInClosure = false; long closureEnd = ticks.endOf(closureTicks).sequence(); for (TimingEvent.MovementObserved m : rotationOnly) if (m.sequence() < closureEnd) rotationInClosure = true;
            if (!live.isEmpty() || rotationInClosure) { complete = false; notes.add("silent closure with " + (live.isEmpty() ? "" : "pending influences ") + (rotationInClosure ? "rotation changes " : "") + "inside it: application/yaw inside the closure is not enumerated, complete=false"); }
        }

        // ---- enumerate
        List<TimingHypothesis> out = new ArrayList<>(); int combinations = 0, dedup = 0, fences = tickEnds.size() + answers.size(); boolean searchLimit = false;
        for (int k : alignments) {
            Ticks segment = closureTicks == 0 ? ticks : new Ticks(ticks.ends().subList(closureTicks - 1 + offset, ticks.ends().size()), k, 1); // the segment's anchor is the last silent tick (in-tick): its end is the first of the sub-list
            List<TickObservation> obs = tickObservations(req, segment, rotationOnly, k, source == TimingHypothesis.Alignment.TICK_END_EXACT);
            int[] earliest = new int[live.size()];
            for (int i = 0; i < live.size(); i++) earliest[i] = earliestTick(live.get(i), segment, k, source == TimingHypothesis.Alignment.TICK_END_EXACT);
            List<int[]> assignments = new ArrayList<>();
            enumerate(new int[live.size()], 0, earliest, k, 1, assignments, budget.maxHypotheses() + 1);
            for (int[] a : assignments) {
                combinations++;
                if (out.size() >= budget.maxHypotheses()) { searchLimit = true; break; }
                List<List<PhysicsEnvironment.ExternalMotion>> perTick = new ArrayList<>(); for (int t = 0; t < k; t++) perTick.add(new ArrayList<>());
                Set<String> appliedIds = new LinkedHashSet<>(), pendingIds = new LinkedHashSet<>(); StringBuilder id = new StringBuilder("h").append(k);
                for (int i = 0; i < live.size(); i++) {
                    TimingEvent.ExternalMotionSent e = live.get(i);
                    if (a[i] == 0) { pendingIds.add(e.id()); id.append('-').append(e.id()).append("@later"); }
                    else { perTick.get(a[i] - 1).add(motion(e)); appliedIds.add(e.id()); id.append('-').append(e.id()).append('@').append(a[i]); }
                }
                List<String> fenceText = new ArrayList<>(); if (!tickEnds.isEmpty()) fenceText.add("tickEnds@" + tickEnds.get(0).sequence() + ".." + tickEnds.get(tickEnds.size() - 1).sequence()); if (!answers.isEmpty()) fenceText.add("markerAnswers=" + answers.size());
                TimingHypothesis h = new TimingHypothesis(id.toString(), k, obs, new ReconstructionRequest.ExternalMotionBranch(id.toString(), perTick), appliedIds, pendingIds, expired,
                    preApplication ? TimingHypothesis.TeleportRelation.PRE_APPLICATION : TimingHypothesis.TeleportRelation.NONE, null, source, fenceText, rtt, 0, closureTicks);
                boolean duplicate = false; for (TimingHypothesis o : out) if (o.equivalentTo(h)) { duplicate = true; break; }
                if (duplicate) dedup++; else out.add(h);
            }
            if (searchLimit) break;
        }
        // ---- P11-F27: the client's own attacker slowdown (P13-F2). The runner admits a slowdown only where vanilla allows one (an ATTACK on a tracked
        // hurtClient-capable entity within reach, or a kinetic-weapon use beside one) and says how many hits one event can mean (1 without a knockback
        // source, otherwise unbounded). An event belongs to the tick whose movement follows it (after the tick's packet drain, before LocalPlayer.tick).
        // Per tick every hit count 0..n is a concrete branch, applied after that tick's drained influences (without a knockback source the tick's hits
        // count as one: the sprint bonus applies once per tick). Beyond the hypothesis budget the largest counts are folded into one option "k or more
        // hits", which is EXACT whenever k hits already put the horizontal motion under the client's zeroing threshold (every larger count is then the
        // same state; with the budget's 16 hypotheses k reaches 15, and 0.6^15 times the largest velocity packet is under it) and otherwise bounded by
        // the sound envelope; at the last resort "any number of hits, or none". The account stays COMPLETE, so the number of attack packets cannot turn
        // an impossible movement into UNCERTAIN (QA-09).
        if (!attacks.isEmpty() && !out.isEmpty()) {
            boolean exact = source == TimingHypothesis.Alignment.TICK_END_EXACT; int k = out.get(0).horizon(); long[] hits = new long[k]; boolean[] stacks = new boolean[k]; boolean unplaced = false;
            for (TimingEvent.AttackerSlowdownPossible a : attacks) {
                if (!exact) { unplaced = true; continue; }
                int abs = ticks.k(); for (int j = 1; j < ticks.k(); j++) if (ticks.endOf(j).sequence() > a.sequence()) { abs = j; break; }
                if (abs <= closureTicks) unplaced = true; else { int t = abs - closureTicks - 1; hits[t] = Math.min(Integer.MAX_VALUE, hits[t] + a.maxHits()); if (a.stacks()) stacks[t] = true; }
            }
            for (int t = 0; t < k; t++) if (!stacks[t]) hits[t] = Math.min(hits[t], 1); // no knockback source: at most one slowdown per tick
            // per tick: concrete counts 0..K[t] plus, when range[t], one envelope option ">= K[t] + 1 hits"; K[t] == -1: only "any number of hits, or none"
            int[] K = new int[k]; boolean[] range = new boolean[k];
            long total = 0; boolean anyStacks = false; for (TimingEvent.AttackerSlowdownPossible a : attacks) { total = Math.min(Integer.MAX_VALUE, total + a.maxHits()); anyStacks |= a.stacks(); }
            for (int t = 0; t < k; t++) {
                long n = unplaced ? (anyStacks ? total : Math.min(total, 1)) : hits[t]; // an unplaceable event may belong to any tick
                K[t] = (int) Math.min(n, MAX_ATTACK_VARIANTS); range[t] = n > K[t];
            }
            if (unplaced) notes.add("a possible attacker slowdown lies inside the silent closure or the alignment is not exact: every tick carries its hit counts");
            long cap = Math.max(1, budget.maxHypotheses() / Math.max(1, out.size()));
            while (options(K, range) > cap) {
                int w = 0; for (int t = 1; t < k; t++) if (optionCount(K[t], range[t]) > optionCount(K[w], range[w])) w = t;
                if (K[w] >= 1) { K[w]--; range[w] = true; } else { K[w] = -1; range[w] = true; } // fold the largest count into the envelope; at last "any hits, or none"
            }
            long variants = options(K, range);
            boolean any = false; for (int t = 0; t < k; t++) if (K[t] != 0 || range[t]) { any = true; break; }
            if (any) {
                List<int[]> combos = new ArrayList<>(); combine(K, range, 0, new int[k], combos);
                List<TimingHypothesis> expanded = new ArrayList<>();
                for (TimingHypothesis h : out) for (int[] c : combos) {
                    List<List<PhysicsEnvironment.ExternalMotion>> perTick = new ArrayList<>(); StringBuilder id = new StringBuilder(h.id());
                    for (int t = 0; t < k; t++) {
                        List<PhysicsEnvironment.ExternalMotion> m = new ArrayList<>(h.branch().motionsPerTick().get(t));
                        if (K[t] == -1) { m.add(PhysicsEnvironment.ExternalMotion.attackerSlowdownRange(0, 1.0, "atk" + (t + 1) + ".any")); id.append("-atk").append(t + 1).append("any"); }
                        else if (c[t] > K[t]) { m.add(PhysicsEnvironment.ExternalMotion.attackerSlowdownRange(K[t] + 1, rangeFactor(K[t] + 1), "atk" + (t + 1) + ".ge" + (K[t] + 1))); id.append("-atk").append(t + 1).append("ge").append(K[t] + 1); }
                        else { for (int x = 0; x < c[t]; x++) m.add(PhysicsEnvironment.ExternalMotion.attackerSlowdown("atk" + (t + 1) + "." + x)); if (K[t] > 0 || range[t]) id.append("-atk").append(t + 1).append('x').append(c[t]); }
                        perTick.add(m);
                    }
                    expanded.add(new TimingHypothesis(id.toString(), h.horizon(), h.ticks(), new ReconstructionRequest.ExternalMotionBranch(id.toString(), perTick), h.appliedInfluences(), h.pendingInfluences(), h.expiredInfluences(),
                        h.teleport(), h.correction(), h.alignment(), h.fences(), h.rtt(), h.preTicks(), h.closureTicks()));
                }
                notes.add(attacks.size() + " possible attacker slowdown(s): " + variants + " variant(s) per timing account" + (variants < combosUnbounded(hits) ? " (larger counts folded into the envelope)" : ""));
                out = expanded;
            }
        }
        if (!live.isEmpty() && answers.isEmpty() && markers.isEmpty()) notes.add("no marker traffic inside the interval: application bounded by tick-end capture times only (NO_ACK_EVIDENCE for narrowing; none was needed for soundness)");
        if (!live.isEmpty() && !req.profile().pongBoundsApplication() && !answers.isEmpty()) notes.add("marker answers present but the profile does not claim pongBoundsApplication: they narrow nothing (receipt evidence only, N1)");
        TimingResolution.Evidence ev = new TimingResolution.Evidence(tickEnds.size(), rotationOnly.size(), answers.size(), confirms, req.tickEndCapable(), rtt.present(), rtt.stale(), rtt.lowNanos(), rtt.highNanos(), live.size(), expired.size());
        TimingResolution.Work work = new TimingResolution.Work(alignments.size(), combinations, out.size(), dedup, fences);
        TimingResolution.Status status;
        if (searchLimit) { status = TimingResolution.Status.SEARCH_LIMIT; complete = false; notes.add("hypothesis cap " + budget.maxHypotheses() + " reached: enumeration stopped, complete=false"); }
        else if (gapStatus != null) status = gapStatus;
        else if (unresolvedVector) { status = TimingResolution.Status.EXTERNAL_MOTION_UNRESOLVED; notes.add("an influence with an unresolved vector/operation is in scope: physics under it cannot be exact"); }
        else if (!expired.isEmpty()) { status = TimingResolution.Status.EXPIRED; complete = false; }
        else status = out.size() == 1 ? TimingResolution.Status.RESOLVED_UNIQUE : TimingResolution.Status.RESOLVED_AMBIGUOUS;
        if (out.isEmpty()) status = TimingResolution.Status.UNSUPPORTED;
        return new TimingResolution(status, out, complete, ev, work, prov, notes);
    }

    /**
     * The per-tick observations for a k-tick account: intermediate ticks are silent (the client's send threshold around the last
     * transmitted position), each tick's yaw is the last rotation-only packet that preceded its tick end (exact alignment) or the
     * anchor yaw (fallback: a rotation-only packet's tick is unknown, so only the target tick takes it), the target comes last.
     */
    static List<TickObservation> tickObservations(TimingRequest req, Ticks ticks, List<TimingEvent.MovementObserved> rotationOnly, int k, boolean exact) {
        List<TickObservation> out = new ArrayList<>(k);
        Vector3 p = req.lastSentPosition(); float yaw = req.lastYaw(); int r = 0;
        for (int t = 1; t < k; t++) {
            long end = exact ? ticks.endOf(t).sequence() : req.anchorSequence() + t;
            if (exact) while (r < rotationOnly.size() && rotationOnly.get(r).sequence() < end) { yaw = rotationOnly.get(r).rotation().yaw(); r++; }
            out.add(TickObservation.silent(p.x(), p.y(), p.z(), yaw, end));
        }
        while (r < rotationOnly.size()) { yaw = rotationOnly.get(r).rotation().yaw(); r++; }
        TimingEvent.MovementObserved m = req.target();
        if (m.hasRotation()) yaw = m.rotation().yaw();
        if (m.hasPosition()) out.add(TickObservation.exact(m.position().x(), m.position().y(), m.position().z(), yaw, m.onGround() ? TickObservation.GroundClaim.TRUE : TickObservation.GroundClaim.FALSE, m.sequence()));
        else out.add(TickObservation.silent(p.x(), p.y(), p.z(), yaw, m.sequence()));
        return out;
    }
    /** The first tick (1-based) the influence may have applied to: an intermediate tick whose end was captured before the influence left the server is excluded (it ended before the influence existed); the target's tick is always possible. */
    static int earliestTick(TimingEvent.ExternalMotionSent e, Ticks ticks, int k, boolean exact) {
        if (!exact) return 1;
        for (int j = 1; j < k; j++) if (ticks.endOf(j).nanos() > e.nanos()) return j;
        return k;
    }
    /** P11-F27: the most concrete hit counts one tick enumerates before the rest is folded ("k or more"); the hypothesis budget usually binds first. */
    public static final int MAX_ATTACK_VARIANTS = 64;
    private static long optionCount(int k, boolean range) { return k < 0 ? 1 : k + 1 + (range ? 1 : 0); }
    private static long options(int[] K, boolean[] range) { long v = 1; for (int t = 0; t < K.length; t++) { v *= optionCount(K[t], range[t]); if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE; } return v; }
    private static long combosUnbounded(long[] hits) { long v = 1; for (long n : hits) { v *= (n + 1); if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE; } return v; }
    /** Every per-tick option: a concrete count 0..K[t], or K[t] + 1 standing for the envelope option ({@code range}); K[t] == -1: the single "any" option. */
    private static void combine(int[] K, boolean[] range, int t, int[] c, List<int[]> out) {
        if (t == K.length) { out.add(c.clone()); return; }
        if (K[t] < 0) { c[t] = 0; combine(K, range, t + 1, c, out); return; }
        for (int m = 0; m <= K[t] + (range[t] ? 1 : 0); m++) { c[t] = m; combine(K, range, t + 1, c, out); }
    }
    /** The largest horizontal factor of "n or more hits": 0.6^n, widened outward so the client's repeated double multiplication always lies inside. */
    static double rangeFactor(int n) { double f = 1.0; for (int i = 0; i < n; i++) f *= MovementProfile.ATTACKER_SLOWDOWN_HORIZONTAL; return Math.min(1.0, f * (1.0 + 1.0E-9)); }
    /** Ordered assignment: influence i applies at tick a[i] in [earliest[i]..k] or 0 = not yet; wire order implies a[i] <= a[i+1] with 0 (later) after everything. */
    private static void enumerate(int[] a, int i, int[] earliest, int k, int minTick, List<int[]> out, int cap) {
        if (out.size() >= cap) return;
        if (i == a.length) { out.add(a.clone()); return; }
        for (int t = Math.max(minTick, earliest[i]); t <= k; t++) { a[i] = t; enumerate(a, i + 1, earliest, k, t, out, cap); }
        a[i] = 0; // not yet applied: every later influence is then not yet applied either (in-order processing)
        for (int j = i; j < a.length; j++) a[j] = 0;
        if (out.size() < cap) out.add(a.clone());
    }
    private static PhysicsEnvironment.ExternalMotion motion(TimingEvent.ExternalMotionSent e) {
        return switch (e.operation()) {
            case SET -> new PhysicsEnvironment.ExternalMotion(PhysicsEnvironment.ExternalMotion.Operation.SET, new Vec3d(e.vector().x(), e.vector().y(), e.vector().z()), e.id());
            case ADD -> new PhysicsEnvironment.ExternalMotion(PhysicsEnvironment.ExternalMotion.Operation.ADD, new Vec3d(e.vector().x(), e.vector().y(), e.vector().z()), e.id());
            case UNRESOLVED -> PhysicsEnvironment.ExternalMotion.unresolved(e.id());
        };
    }
}
