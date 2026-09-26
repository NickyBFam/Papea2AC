package me.nicolas.papea2ac.evidence;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.BoundKind;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.Category;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.Classification;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.InvalidCode;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.Rejection;
import me.nicolas.papea2ac.model.SessionKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
/**
 * One immutable piece of positive physical-impossibility evidence (EVIDENCE_MODEL kind {@code DeterministicExclusion}): a completed Phase 8
 * INVALID transition with its complete exhaustion proof intact. Nothing else can become one - the only constructor path is
 * {@link #from(ValidationRecord)}, which enforces the gate structurally (classification INVALID, proof present and exhaustive with zero
 * candidates remaining, every one of the 13 completeness dimensions true, no uncertainty reason, no legal candidate, no witness,
 * {@code NO_LEGAL_INPUT} present, finite residuals). A VALID, an UNCERTAIN, a search-limited, an unsupported-mechanic, an internal-error or
 * a malformed record yields {@link Optional#empty()} together with the typed {@link Rejection} from {@link #check(ValidationRecord)}.
 *
 * <p>The event {@link #id()} is a SHA-256 digest of the record's identity and proof content (session, world epoch, sequence range,
 * reasons, proof counts and profiles, residual numbers): the same Phase 8 result always yields the same id, whichever path delivers it, so
 * repeated delivery is idempotent; the monotonic capture time is carried but not part of the identity. The magnitude fields are the
 * Phase 8 residual EXCESSES of the exhausted set (never a nearest-candidate distance): {@code horizontalExcess} in blocks per transition
 * beyond the legal horizontal extent, {@code verticalExcess} in blocks beyond the legal rise interval (positive above, negative below),
 * with the bound kind they came from. One event means one unexplained transition; it is not an enforcement decision.
 */
public record EvidenceEvent(String id, SessionKey session, long worldEpoch, long startSequence, long endSequence, long nanos, Set<Category> categories, List<InvalidCode> invalidReasons,
                            Magnitude magnitude, ValidationRecord.ProofSummary proof, ValidationRecord.Provenance provenance, int timingHypotheses, int reconstructions) {
    /** Proven excess outside the legal set, with the bound it was measured against. */
    public record Magnitude(BoundKind bound, double horizontalExcess, double verticalExcess, boolean collisionPenetration, double observedHorizontal, double observedRise) {
        public Magnitude { Objects.requireNonNull(bound); for (double d : new double[] { horizontalExcess, verticalExcess, observedHorizontal, observedRise }) if (!Double.isFinite(d)) throw new IllegalArgumentException("magnitude"); }
    }
    public EvidenceEvent {
        Objects.requireNonNull(id); Objects.requireNonNull(session); categories = Set.copyOf(categories); invalidReasons = List.copyOf(invalidReasons); Objects.requireNonNull(magnitude); Objects.requireNonNull(proof); Objects.requireNonNull(provenance);
        if (id.length() != 64 || categories.isEmpty() || !invalidReasons.contains(InvalidCode.NO_LEGAL_INPUT) || !proof.present() || proof.hypothesesExhausted() <= 0 || proof.candidatesRemaining() != 0) throw new IllegalArgumentException("not a complete exclusion");
    }

    /** The typed gate: why this record is not (or is) positive evidence. */
    public static Optional<Rejection> check(ValidationRecord r) {
        Objects.requireNonNull(r);
        if (r.classification() != Classification.INVALID) return Optional.of(Rejection.NOT_INVALID);
        if (!r.proof().present()) return Optional.of(Rejection.PROOF_MISSING);
        if (r.proof().hypothesesExhausted() <= 0 || r.proof().rawEnumerated() <= 0) return Optional.of(Rejection.PROOF_NOT_EXHAUSTIVE);
        if (r.proof().candidatesRemaining() != 0) return Optional.of(Rejection.CANDIDATES_REMAINING);
        if (!r.completeness().all()) return Optional.of(Rejection.COMPLETENESS_MISSING);
        if (!r.uncertainReasons().isEmpty()) return Optional.of(Rejection.UNCERTAINTY_PRESENT);
        if (r.work().legalCandidates() != 0 || r.work().explainingHypotheses() != 0) return Optional.of(Rejection.LEGAL_CANDIDATE_PRESENT);
        if (r.witnesses() != 0) return Optional.of(Rejection.WITNESS_PRESENT);
        if (!r.invalidReasons().contains(InvalidCode.NO_LEGAL_INPUT)) return Optional.of(Rejection.NO_LEGAL_INPUT_MISSING);
        if (r.work().timingHypotheses() < r.proof().hypothesesExhausted() || r.work().timingHypotheses() != r.work().completeTimingHypotheses()) return Optional.of(Rejection.PROOF_NOT_EXHAUSTIVE);
        return Optional.empty();
    }
    /** The only way to obtain positive evidence: empty unless the record passes {@link #check}. */
    public static Optional<EvidenceEvent> from(ValidationRecord r) {
        if (check(r).isPresent()) return Optional.empty();
        Set<Category> cats = categories(r.invalidReasons());
        ValidationRecord.ResidualSummary res = r.residual();
        Magnitude mag = new Magnitude(res.present() ? res.bound() : BoundKind.NONE, res.present() ? res.horizontalExcess() : 0, res.present() ? res.verticalExcess() : 0, r.invalidReasons().contains(InvalidCode.COLLISION_PENETRATION), res.present() ? res.observedHorizontal() : 0, res.present() ? res.observedRise() : 0);
        return Optional.of(new EvidenceEvent(digest(r), r.session(), r.worldEpoch(), r.startSequence(), r.endSequence(), r.nanos(), cats, r.invalidReasons(), mag, r.proof(), r.provenance(), r.work().timingHypotheses(), r.work().reconstructions()));
    }
    /** Typed families from the typed Phase 8 reasons: never a rename, never a guess. */
    static Set<Category> categories(List<InvalidCode> reasons) {
        EnumSet<Category> out = EnumSet.noneOf(Category.class);
        for (InvalidCode c : reasons) switch (c) {
            case HORIZONTAL_EXCESS -> out.add(Category.HORIZONTAL_EXCESS);
            case VERTICAL_EXCESS -> out.add(Category.VERTICAL_EXCESS);
            case IMPOSSIBLE_ASCENT -> out.add(Category.IMPOSSIBLE_ASCENT);
            case IMPOSSIBLE_DESCENT -> out.add(Category.IMPOSSIBLE_DESCENT);
            case COLLISION_PENETRATION -> out.add(Category.COLLISION_PENETRATION);
            case UNEXPLAINED_DISPLACEMENT -> out.add(Category.UNEXPLAINED_DISPLACEMENT);
            case NO_LEGAL_INPUT -> { }
        }
        if (out.isEmpty()) out.add(Category.GENERAL_MOVEMENT_IMPOSSIBILITY);
        return out;
    }
    /** Deterministic identity: SHA-256 over a canonical text of the identity and proof content (no time, no object identity, no map order). */
    public static String digest(ValidationRecord r) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("papea2ac-evidence-v1|").append(r.session().player()).append('|').append(r.session().epoch()).append('|').append(r.worldEpoch()).append('|').append(r.startSequence()).append('|').append(r.endSequence()).append('|').append(r.classification());
        List<String> inv = new ArrayList<>(); for (InvalidCode c : r.invalidReasons()) inv.add(c.name()); inv.sort(null); sb.append('|').append(String.join(",", inv));
        ValidationRecord.ProofSummary p = r.proof();
        sb.append('|').append(p.hypothesesExhausted()).append(',').append(p.reconstructions()).append(',').append(p.evaluations()).append(',').append(p.rawEnumerated()).append(',').append(p.excludedExact()).append(',').append(p.excludedEnvelope()).append(',').append(p.candidatesRemaining());
        sb.append('|').append(p.observationProfile()).append(',').append(p.movementProfile()).append(',').append(p.collisionProfile()).append(',').append(p.timingProfile()).append(',').append(p.worldRevision()).append('|').append(String.join(",", p.hypothesisIds()));
        ValidationRecord.ResidualSummary s = r.residual();
        sb.append('|').append(s.present()).append(',').append(s.bound()).append(',').append(Double.toString(s.observedHorizontal())).append(',').append(Double.toString(s.observedRise())).append(',').append(Double.toString(s.maxLegalHorizontal())).append(',').append(Double.toString(s.maxLegalRise())).append(',').append(Double.toString(s.minLegalRise())).append(',').append(Double.toString(s.horizontalExcess())).append(',').append(Double.toString(s.verticalExcess()));
        return sha256(sb.toString());
    }
    static String sha256(String text) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64); for (byte b : h) out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return out.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
    /** Bounded, neutral description (categories, reasons, excesses, proof counts); never a verdict about the player. */
    public String describe() {
        List<String> cats = new ArrayList<>(); for (Category c : Category.values()) if (categories.contains(c)) cats.add(c.name());
        return "evidence " + id.substring(0, 12) + " seq=" + startSequence + "->" + endSequence + " epoch=" + worldEpoch + " " + cats + " reasons=" + invalidReasons
            + String.format(Locale.ROOT, " excess h=%.4f v=%.4f (%s)%s", magnitude.horizontalExcess(), magnitude.verticalExcess(), magnitude.bound(), magnitude.collisionPenetration() ? " penetration" : "")
            + " proof: hyp=" + proof.hypothesesExhausted() + " enum=" + proof.rawEnumerated() + " exact=" + proof.excludedExact() + " env=" + proof.excludedEnvelope() + " remaining=" + proof.candidatesRemaining();
    }
}
