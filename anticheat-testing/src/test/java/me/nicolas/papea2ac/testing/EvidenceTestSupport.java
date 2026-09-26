package me.nicolas.papea2ac.testing;
import java.util.List;
import java.util.UUID;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.BoundKind;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.Classification;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.InvalidCode;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary.UncertainCode;
import me.nicolas.papea2ac.evidence.ValidationRecord;
import me.nicolas.papea2ac.model.SessionKey;
/** Synthetic Phase 8 result projections for the Phase 9 tests: a complete INVALID with an exhaustive proof, a VALID witness, an UNCERTAIN with its reasons, and corrupted variants. Every field is explicit so that each test states what it feeds. */
final class EvidenceTestSupport {
    private EvidenceTestSupport() {}
    static final SessionKey SESSION = new SessionKey(new UUID(9, 1), 1);
    static final long TICK = 50_000_000L;
    static final ValidationRecord.Provenance PROV = new ValidationRecord.Provenance("paper-26.2-124-timing", "paper-26.2-124-movement", "paper-26.2-124-movement-observation", "paper-26.2-124-collision", 7, 0, false);
    static ValidationRecord.ProofSummary proof(int hypotheses) { return new ValidationRecord.ProofSummary(true, hypotheses, hypotheses, 144L * hypotheses, 144 * hypotheses, 100 * hypotheses, 44 * hypotheses, 0, "paper-26.2-124-movement-observation", "paper-26.2-124-movement", "paper-26.2-124-collision", "paper-26.2-124-timing", 7, List.of("h1")); }
    /** A complete INVALID transition (start..end sequence, at nanos) with the given typed reasons and proven excesses. */
    static ValidationRecord invalid(SessionKey s, long epoch, long start, long end, long nanos, List<InvalidCode> reasons, double hExcess, double vExcess) {
        ValidationRecord.ResidualSummary res = new ValidationRecord.ResidualSummary(true, 0.5 + hExcess, vExcess, 0.3274, 0.42, 0.0, BoundKind.ENVELOPE, hExcess, vExcess);
        return new ValidationRecord(s, epoch, start, end, nanos, Classification.INVALID, ValidationRecord.Completeness.ALL, List.of(), reasons, proof(1), res, new ValidationRecord.WorkSummary(1, 1, 0, 0, 1, 144), PROV, 0);
    }
    static ValidationRecord invalid(long start, long end, long nanos) { return invalid(SESSION, 0, start, end, nanos, List.of(InvalidCode.NO_LEGAL_INPUT, InvalidCode.HORIZONTAL_EXCESS), 0.1726, 0.0); }
    static ValidationRecord valid(SessionKey s, long epoch, long start, long end, long nanos) {
        return new ValidationRecord(s, epoch, start, end, nanos, Classification.VALID, ValidationRecord.Completeness.ALL, List.of(), List.of(), ValidationRecord.ProofSummary.NONE, ValidationRecord.ResidualSummary.NONE, new ValidationRecord.WorkSummary(1, 1, 1, 2, 1, 144), PROV, 1);
    }
    static ValidationRecord valid(long start, long end, long nanos) { return valid(SESSION, 0, start, end, nanos); }
    static ValidationRecord uncertain(SessionKey s, long epoch, long start, long end, long nanos, List<UncertainCode> reasons, ValidationRecord.Completeness completeness) {
        return new ValidationRecord(s, epoch, start, end, nanos, Classification.UNCERTAIN, completeness, reasons, List.of(), ValidationRecord.ProofSummary.NONE, new ValidationRecord.ResidualSummary(true, 0.15, 0, 0, 0, 0, BoundKind.NONE, 0, 0), new ValidationRecord.WorkSummary(1, 0, 0, 0, 1, 144), PROV, 0);
    }
    static ValidationRecord uncertain(long start, long end, long nanos, UncertainCode reason) {
        ValidationRecord.Completeness c = switch (reason) {
            case SEARCH_LIMIT, QUERY_LIMIT -> new ValidationRecord.Completeness(true, false, true, true, true, true, true, true, true, true, false, true, true);
            case WORLD_INCOMPLETE, CONTENT_NOT_RETAINED -> new ValidationRecord.Completeness(true, false, false, true, true, true, true, true, true, true, true, true, true);
            case CLIMBABLE_CONTACT, UNSUPPORTED_FLUID, UNSUPPORTED_LOCOMOTION, UNSUPPORTED_CONTEXT -> new ValidationRecord.Completeness(true, true, true, true, true, true, true, true, true, false, true, true, true);
            case EXTERNAL_MOTION_UNRESOLVED, EXTERNAL_MOTION_EXPIRED, EXTERNAL_MOTION_OVERFLOW -> new ValidationRecord.Completeness(true, true, true, true, true, true, true, true, false, true, true, true, true);
            case ENTITY_NEIGHBORHOOD_UNKNOWN -> new ValidationRecord.Completeness(true, false, true, true, true, true, false, true, true, true, true, true, true);
            case FRONTIER_INCOMPLETE -> new ValidationRecord.Completeness(true, true, true, true, true, true, true, true, true, true, true, true, false);
            default -> new ValidationRecord.Completeness(false, true, true, true, true, true, true, true, true, true, true, true, true);
        };
        return uncertain(SESSION, 0, start, end, nanos, List.of(reason), c);
    }
}
