package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.EvidenceTestSupport.*;
import static me.nicolas.papea2ac.testing.PhysicsTestSupport.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import me.nicolas.papea2ac.core.ValidationEvidenceMapper;
import me.nicolas.papea2ac.evidence.*;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.InvalidCode;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.UncertainCode;
import me.nicolas.papea2ac.mitigation.*;
import me.nicolas.papea2ac.mitigation.MitigationActor.EffectMode;
import me.nicolas.papea2ac.mitigation.MitigationVocabulary.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.testing.ValidationTestSupport.Validated;
import me.nicolas.papea2ac.world.WorldObservation;

/**
 * The deterministic Phase 10 mitigation corpus (brief section 52): every case is a small stream with an EXACT expected decision (outcome + typed
 * reason) or terminal intent outcome, run through a real Phase 9 ledger, the real {@link MitigationActor} and the real collision engine. Also the
 * Phase 8/9 corpus sinks: the legitimate corpus and the impossible corpus, fed case by case through a real ledger and actor in ENFORCE.
 */
final class MitigationCorpus {
    private MitigationCorpus() {}
    static final long S = 1_000_000_000L, T0 = 100 * S;
    static final List<InvalidCode> H = List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.HORIZONTAL_EXCESS), V = List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.VERTICAL_EXCESS),
        HU = List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.HORIZONTAL_EXCESS, InvalidCode.UNEXPLAINED_DISPLACEMENT);
    static final Vector3 ANCHOR = MitigationActorTest.ANCHOR;

    /** One case's expected and actual result. */
    record Result(String name, String group, String expectedOutcome, String expectedReason, String actualOutcome, String actualReason, long targetSequence, String action, String note) {
        boolean matches() { return expectedOutcome.equals(actualOutcome) && expectedReason.equals(actualReason); }
        Map<String, Object> json() { Map<String, Object> m = new LinkedHashMap<>(); m.put("case", name); m.put("expected", expectedOutcome + " " + expectedReason); m.put("actual", actualOutcome + " " + actualReason); m.put("match", matches()); if (targetSequence >= 0) m.put("targetSequence", targetSequence); if (action != null) m.put("action", action); if (!note.isEmpty()) m.put("note", note); return m; }
    }
    private static MitigationActorTest.H0 h(EffectMode mode) { MitigationActorTest.H0 h = new MitigationActorTest.H0(); h.mode = mode; return h; }
    private static Result decision(String name, String group, String eo, String er, MitigationActorTest.H0 h, MitigationActor.Decision d, String note) {
        return new Result(name, group, eo, er, d.outcome().name(), d.reason().name(), d.target() == null ? -1 : d.target().sequence(), d.actionId() == null ? null : d.actionId().substring(0, 16), note);
    }
    private static Result terminal(String name, String eo, String er, MitigationActorTest.H0 h, String note) {
        var outs = h.actor.status().recentOutcomes(); var o = outs.isEmpty() ? null : outs.get(outs.size() - 1);
        return new Result(name, "setback", eo, er, o == null ? h.actor.state().name() : o.terminal().name(), o == null ? "-" : o.reason().name(), -1, o == null ? null : o.actionId().substring(0, 16), note);
    }
    /** Streams four groups (anchor first unless suppressed by the setup), applying {@code before4th} right before the fourth. */
    private static MitigationActor.Decision four(MitigationActorTest.H0 h, boolean anchor, Consumer<MitigationActorTest.H0> before4th, List<List<InvalidCode>> families) {
        if (anchor) h.anchor(50, T0, ANCHOR);
        MitigationActor.Decision d = null;
        for (int i = 1; i <= 4; i++) { if (i == 4) before4th.accept(h); d = h.feed(invalid(h.key, 0, 100L * i, 100L * i + 10, T0 + i * S / 10, families.get(i - 1), 0.2, 0)); }
        return d;
    }
    private static List<List<InvalidCode>> same(List<InvalidCode> f) { return List.of(f, f, f, f); }

    static List<Result> synthetic() {
        List<Result> out = new ArrayList<>(); Consumer<MitigationActorTest.H0> none = x -> { };
        // ---------------------------------------------------------------- evidence policy
        for (int n = 1; n <= 3; n++) {
            var h = h(EffectMode.ENFORCE); h.anchor(50, T0, ANCHOR); MitigationActor.Decision d = null;
            for (int i = 1; i <= n; i++) d = h.feed(h.group(i, T0 + i * S / 10));
            out.add(decision("insufficient_" + n + "_group" + (n > 1 ? "s" : ""), "policy", "NOT_ELIGIBLE", "INSUFFICIENT_EVIDENCE", h, d, n + " independent group(s): never a setback"));
        }
        { var h = h(EffectMode.OBSERVE_ONLY); out.add(decision("eligible_4_independent_same_family_observe_only", "policy", "SUPPRESSED", "MODE_OBSERVE_ONLY", h, four(h, true, none, same(H)), "policy met; OBSERVE_ONLY records the would-be target, applies nothing")); }
        { var h = h(EffectMode.ENFORCE); out.add(decision("eligible_4_independent_same_family_enforce", "policy", "PROPOSED", "POLICY_MET", h, four(h, true, none, same(H)), "the only path to an intent")); }
        { var h = h(EffectMode.ENFORCE); h.anchor(50, T0, ANCHOR); MitigationActor.Decision d = null; ValidationRecord third = null;
          for (int i = 1; i <= 3; i++) { third = h.group(i, T0 + i * S / 10); d = h.feed(third); }
          for (int k = 0; k < 3; k++) h.feed(third);
          d = h.feed(valid(h.key, 0, 310, 311, T0 + 35 * S / 100)); out.add(decision("duplicates_never_count", "policy", "NONE", "NO_EVIDENCE", h, d, "3 groups + 3 re-deliveries (DUPLICATE) + a VALID: the duplicates added nothing, the VALID reset")); }
        { var h = h(EffectMode.ENFORCE); h.anchor(50, T0, ANCHOR); h.feed(h.group(1, T0 + S / 10)); h.feed(h.group(2, T0 + 2 * S / 10)); h.feed(valid(h.key, 0, 220, 230, T0 + 25 * S / 100)); h.feed(h.group(3, T0 + 3 * S / 10));
          out.add(decision("valid_separated", "policy", "NOT_ELIGIBLE", "INSUFFICIENT_EVIDENCE", h, h.feed(h.group(4, T0 + 4 * S / 10)), "I I V I I: the VALID resets the chain")); }
        { var h = h(EffectMode.ENFORCE); h.anchor(50, T0, ANCHOR); h.feed(h.group(1, T0 + S / 10)); h.feed(h.group(2, T0 + 2 * S / 10)); h.feed(uncertain(h.key, 0, 220, 230, T0 + 25 * S / 100, List.of(UncertainCode.WORLD_INCOMPLETE), new ValidationRecord.Completeness(true, false, false, true, true, true, true, true, true, true, true, true, true))); h.feed(h.group(3, T0 + 3 * S / 10));
          out.add(decision("uncertain_separated", "policy", "NOT_ELIGIBLE", "INSUFFICIENT_EVIDENCE", h, h.feed(h.group(4, T0 + 4 * S / 10)), "I I U I I: the UNCERTAIN resets the chain")); }
        { var h = h(EffectMode.ENFORCE); h.anchor(50, T0, ANCHOR); h.feed(h.group(1, T0 + S / 10)); h.feed(h.group(2, T0 + 2 * S / 10)); h.actor.onUnanchored(T0 + 25 * S / 100); h.feed(h.group(3, T0 + 3 * S / 10));
          out.add(decision("unanchored_separated", "policy", "NOT_ELIGIBLE", "INSUFFICIENT_EVIDENCE", h, h.feed(h.group(4, T0 + 4 * S / 10)), "an unanchored START_UNAVAILABLE is an UNCERTAIN observation")); }
        { var h = h(EffectMode.ENFORCE); h.anchor(50, T0 + 33 * S, ANCHOR); h.feed(h.group(1, T0 + S)); h.feed(h.group(2, T0 + 12 * S)); h.feed(h.group(3, T0 + 23 * S));
          out.add(decision("expired_evidence", "policy", "NOT_ELIGIBLE", "EVIDENCE_EXPIRED", h, h.feed(h.group(4, T0 + 34 * S)), "the first group left the 30 s window: 3 active groups; mitigation never resurrects expired evidence")); }
        { var h = h(EffectMode.ENFORCE); out.add(decision("different_families", "policy", "NOT_ELIGIBLE", "INSUFFICIENT_EVIDENCE", h, four(h, true, none, List.of(H, H, V, H)), "H H V H: no family common to the last four")); }
        { var h = h(EffectMode.ENFORCE); out.add(decision("shared_family", "policy", "PROPOSED", "POLICY_MET", h, four(h, true, none, List.of(HU, H, HU, H)), "HORIZONTAL_EXCESS common to all four")); }
        { var h = h(EffectMode.ENFORCE); h.anchor(50, T0, ANCHOR); MitigationActor.Decision d = null;
          for (int i = 0; i < 8; i++) d = h.feed(invalid(h.key, 0, 100 + 10L * i, 110 + 10L * i, T0 + (i + 1) * S / 20, H, 0.4, 0));
          out.add(decision("touching_run_is_one_group", "policy", "NOT_ELIGIBLE", "INSUFFICIENT_EVIDENCE", h, d, "8 consecutive touching INVALIDs: one Phase 9 group")); }
        { var h = h(EffectMode.ENFORCE); h.anchor(50, T0, ANCHOR); h.feed(h.group(1, T0 + S / 10)); h.feed(h.group(2, T0 + 2 * S / 10)); h.feed(h.group(3, T0 + 3 * S / 10));
          out.add(decision("world_epoch_boundary", "policy", "NOT_ELIGIBLE", "INSUFFICIENT_EVIDENCE", h, h.feed(invalid(h.key, 1, 400, 410, T0 + 4 * S / 10, H, 0.2, 0)), "the fourth group is in a new epoch: the chain restarted there")); }
        { var h = h(EffectMode.ENFORCE); EvidenceLedger foreign = new EvidenceLedger(new SessionKey(new UUID(5, 5), 1)); MitigationActor.Decision d = null;
          for (int i = 1; i <= 4; i++) { var o = foreign.ingest(invalid(foreign.session(), 0, 100L * i, 100L * i + 10, T0 + i * S / 10, H, 0.2, 0)); d = h.actor.onEvidence(o, foreign.lastEvent(), foreign.snapshot(), false, h.cs(T0 + i * S / 10, 100L * i)); }
          out.add(decision("session_boundary", "policy", "NONE", "SESSION_MISMATCH", h, d, "another session's evidence never counts")); }
        // ---------------------------------------------------------------- safety suppressions
        record Sup(String name, String outcome, String reason, boolean anchor, Consumer<MitigationActorTest.H0> setup, String note) {}
        List<Sup> sups = List.of(
            new Sup("no_anchor", "SUPPRESSED", "NO_SAFE_ANCHOR", false, none, "no certified pre-evidence state"),
            new Sup("teleport_pending", "SUPPRESSED", "UNRESOLVED_TELEPORT", true, x -> x.teleports = 1, "a legitimate correction is still unconfirmed"),
            new Sup("velocity_pending", "SUPPRESSED", "UNRESOLVED_EXTERNAL_MOTION", true, x -> x.influences = 1, "legitimate external motion in scope"),
            new Sup("block_dispute", "SUPPRESSED", "BLOCK_DISPUTE", true, x -> x.dispute = true, "P9-F2: the geometry is disputed"),
            new Sup("entity_ambiguity", "SUPPRESSED", "ENTITY_STATE_INCOMPLETE", true, x -> x.entities = true, "P9-F5: a pusher near the player or target"),
            new Sup("transport_unhealthy", "SUPPRESSED", "TRANSPORT_UNHEALTHY", true, x -> x.healthy = false, "the health latch is not HEALTHY"),
            new Sup("unsupported_locomotion", "SUPPRESSED", "UNSUPPORTED_LOCOMOTION", true, x -> x.supported = false, "flight/creative/vehicle context"),
            new Sup("unsafe_anchor_obstructed", "SUPPRESSED", "COLLISION_OBSTRUCTED", true, x -> x.ps.set(8, (int) SURFACE, 8, BlockStateKey.of("minecraft:stone")), "block-change race: the target is no longer clear"),
            new Sup("unsafe_anchor_unknown", "SUPPRESSED", "COLLISION_UNKNOWN", true, x -> x.ps.scene.apply(new WorldObservation.ChunkUnloaded(0, 0)), "UNKNOWN is never clear"),
            new Sup("anchor_too_far", "RESYNC_REQUIRED", "ANCHOR_TOO_FAR", true, x -> x.position = new Vector3(ANCHOR.x() + 9.0, SURFACE, ANCHOR.z()), "more than 8 blocks: no long-distance correction"),
            new Sup("mode_disabled", "SUPPRESSED", "MODE_DISABLED", true, x -> x.mode = EffectMode.DISABLED, "DISABLED: no effect"));
        for (Sup s : sups) { var h = h(EffectMode.ENFORCE); out.add(decision(s.name(), "suppression", s.outcome(), s.reason(), h, four(h, s.anchor(), s.setup(), same(H)), s.note())); }
        { var h = h(EffectMode.ENFORCE); h.anchor(50, T0 - 3 * S, ANCHOR); out.add(decision("anchor_too_old", "target", "RESYNC_REQUIRED", "ANCHOR_TOO_OLD", h, four(h, false, none, same(H)), "older than 2 s: no stale correction")); }
        { var h = h(EffectMode.ENFORCE); h.anchor(30, T0 - S / 10, new Vector3(9.5, SURFACE, 8.5)); h.anchor(50, T0, ANCHOR);
          out.add(decision("most_recent_anchor_is_the_target", "target", "PROPOSED", "POLICY_MET", h, four(h, false, none, same(H)), "two admitted anchors: the most recent pre-evidence one (seq 50)")); }
        { var h = h(EffectMode.ENFORCE); h.anchor(50, T0, ANCHOR); h.feed(h.group(1, T0 + S / 10)); h.feed(h.group(2, T0 + 2 * S / 10)); h.feed(h.group(3, T0 + 3 * S / 10)); h.anchor(350, T0 + 35 * S / 100, new Vector3(9.5, SURFACE, 8.5));
          out.add(decision("anchor_after_first_group_is_ignored", "target", "PROPOSED", "POLICY_MET", h, h.feed(h.group(4, T0 + 4 * S / 10)), "an anchor certified inside the evidence chain is not a pre-evidence state: seq 50 is chosen")); }
        // ---------------------------------------------------------------- intent lifecycle (terminal outcomes)
        { var h = h(EffectMode.ENFORCE); h.eligible(); var it = h.captured.get(0); h.actor.onCaptureReceipt(it.actionId(), true, "c", T0 + 45 * S / 100); h.actor.onPlatformOutcome(it.shortId(), MitigationActor.PlatformOutcome.STARTED, null, T0 + 46 * S / 100);
          h.actor.onTeleportSent(9, ANCHOR, 0, T0 + 46 * S / 100); h.actor.onTeleportConfirmed(9, T0 + 50 * S / 100); h.feed(valid(h.key, 0, 500, 510, T0 + 55 * S / 100));
          out.add(terminal("setback_confirmed_and_recovered", "RECOVERED", "RECOVERY", h, "PROPOSED -> WAITING_CAPTURE -> QUEUED -> EXECUTING -> SENT_AWAITING_CONFIRM -> CONFIRMED -> RECOVERY -> IDLE")); }
        { var h = h(EffectMode.ENFORCE); h.eligible(); var it = h.captured.get(0); h.actor.onCaptureReceipt(it.actionId(), true, "c", T0 + 45 * S / 100); h.actor.onTeleportSent(3, new Vector3(0, 90, 0), 0, T0 + 46 * S / 100);
          out.add(terminal("teleport_race", "SUPERSEDED", "SUPERSEDED_BY_FOREIGN_TELEPORT", h, "a foreign correction before ours executes")); }
        { var h = h(EffectMode.ENFORCE); h.eligible(); var it = h.captured.get(0); h.actor.onCaptureReceipt(it.actionId(), true, "c", T0 + 45 * S / 100); h.actor.onExternalMotion(T0 + 46 * S / 100);
          out.add(terminal("velocity_race", "REJECTED", "UNRESOLVED_EXTERNAL_MOTION", h, "legitimate motion registered before application is never erased")); }
        { var h = h(EffectMode.ENFORCE); h.eligible(); h.actor.onEpochChange(1, T0 + 46 * S / 100); out.add(terminal("world_change", "SUPERSEDED", "WORLD_CHANGED", h, "the old world's correction never applies in the new one")); }
        { var h = h(EffectMode.ENFORCE); h.eligible(); h.actor.onSessionEnd(false, T0 + 46 * S / 100); out.add(terminal("session_end", "REJECTED", "SESSION_ENDED", h, "nothing of the old session affects a new one")); }
        { var h = h(EffectMode.ENFORCE); h.eligible(); h.actor.onTick(T0 + 4 * S / 10 + MitigationBounds.DEFAULT.intentDeadlineNanos() + 1); out.add(terminal("capture_deadline_miss", "EXPIRED", "CAPTURE_DEADLINE_MISS", h, "no durable receipt within 500 ms")); }
        { var h = h(EffectMode.ENFORCE); h.captureAvailable = false; h.eligible(); out.add(terminal("capture_unavailable", "REJECTED", "CAPTURE_UNAVAILABLE", h, "DURABLE_REQUIRED: no capture, no effect")); }
        { var h = h(EffectMode.ENFORCE); h.sinkOpen = false; h.eligible(); var it = h.captured.get(0); h.actor.onCaptureReceipt(it.actionId(), true, "c", T0 + 45 * S / 100); out.add(terminal("queue_saturated", "REJECTED", "QUEUE_SATURATED", h, "the main-thread queue is full: rejected without blocking")); }
        { var h = h(EffectMode.ENFORCE); h.eligible(); var it = h.captured.get(0); h.actor.onCaptureReceipt(it.actionId(), true, "c", T0 + 45 * S / 100); h.actor.onTick(it.deadlineNanos() + 1); out.add(terminal("execution_deadline", "EXPIRED", "DEADLINE_EXPIRED", h, "queued but never executed within 500 ms")); }
        { var h = h(EffectMode.ENFORCE); h.eligible(); var it = h.captured.get(0); h.actor.onCaptureReceipt(it.actionId(), true, "c", T0 + 45 * S / 100); h.actor.onPlatformOutcome(it.shortId(), MitigationActor.PlatformOutcome.REJECTED, Reason.PLATFORM_CANCELLED, T0 + 46 * S / 100);
          out.add(terminal("platform_cancelled", "RESYNC_REQUIRED", "PLATFORM_CANCELLED", h, "another plugin cancelled the teleport: a failed correction, never a fake anchor")); }
        { var h = h(EffectMode.ENFORCE); h.eligible(); var it = h.captured.get(0); h.actor.onCaptureReceipt(it.actionId(), true, "c", T0 + 45 * S / 100); h.actor.onPlatformOutcome(it.shortId(), MitigationActor.PlatformOutcome.STARTED, null, T0 + 46 * S / 100); h.actor.onTeleportSent(4, ANCHOR, 0, T0 + 46 * S / 100);
          h.actor.onTick(T0 + 46 * S / 100 + MitigationBounds.DEFAULT.confirmTimeoutNanos() + 1); out.add(terminal("confirm_timeout", "RESYNC_REQUIRED", "CONFIRM_TIMEOUT", h, "sent, never confirmed within 5 s")); }
        return out;
    }

    /** Phase 8/9 corpus sink: each case through a real ledger and actor in ENFORCE (anchors are irrelevant: eligibility precedes any target). */
    static final class Sink implements java.util.function.BiConsumer<String, List<Validated>> {
        final String name; long cases, consumed, positive, eligible, proposed, notEligible, none; final Map<String, Long> outcomes = new TreeMap<>();
        final MitigationActor.Metrics metrics = new MitigationActor.Metrics(); final PhysicsScene ps = new PhysicsScene();
        Sink(String name) { this.name = name; }
        @Override public void accept(String caseId, List<Validated> chain) {
            cases++; SessionKey key = EvidenceCorpus.Sink.session(caseId); EvidenceLedger l = new EvidenceLedger(key);
            MitigationActor a = new MitigationActor(key, MitigationBounds.DEFAULT, new AnchorAdmission(ps.scene.engine, p -> CollisionTestSupport.standing(p.x(), p.y(), p.z())), i -> { proposed++; return true; }, i -> true, (s0, n0) -> true, metrics);
            for (Validated v : chain) {
                ValidationRecord r = ValidationEvidenceMapper.record(key, v.result(), v.target().nanos()); consumed++;
                EvidenceLedger.Outcome o = l.ingest(r); if (o == EvidenceLedger.Outcome.POSITIVE_EVENT) positive++;
                var d = a.onEvidence(o, l.lastEvent().filter(e -> o == EvidenceLedger.Outcome.POSITIVE_EVENT), l.snapshot(), false,
                    new MitigationActor.CurrentState(r.nanos(), r.endSequence(), key, r.worldEpoch(), "minecraft:overworld", new Vector3(0, 0, 0), true, 0, 0, 0, true, p -> false, p -> false, ps.scene.world.currentView(), EffectMode.ENFORCE));
                outcomes.merge(d.outcome().name() + " " + d.reason().name(), 1L, Long::sum);
            }
            eligible = metrics.eligible.sum();
        }
        Map<String, Object> json() { Map<String, Object> m = new LinkedHashMap<>(); m.put("cases", cases); m.put("transitionsConsumed", consumed); m.put("positiveEvidenceEvents", positive); m.put("policyEligible", eligible); m.put("intentsProposed", proposed); m.put("setbacksApplied", 0); m.put("decisions", outcomes); return m; }
    }
    static <T> T with(Function<Sink, T> f, Sink s) { return f.apply(s); }
}
