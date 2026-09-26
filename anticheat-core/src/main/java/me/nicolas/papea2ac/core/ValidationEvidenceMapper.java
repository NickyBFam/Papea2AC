package me.nicolas.papea2ac.core;
import me.nicolas.papea2ac.evidence.EvidenceVocabulary;
import me.nicolas.papea2ac.evidence.ValidationRecord;
import me.nicolas.papea2ac.model.SessionKey;
import me.nicolas.papea2ac.simulation.MovementValidationResult;
import java.util.ArrayList;
import java.util.List;
/**
 * Core's name-for-name projection of a Phase 8 {@link MovementValidationResult} into the evidence-local {@link ValidationRecord}
 * (approved DAG: evidence depends on model only, so it never sees the simulation types; core composes both). Nothing is interpreted here:
 * the classification, the 13 completeness dimensions, the reasons, the proof counts, the residual and the provenance are copied by name.
 * A reason name the evidence registry does not know makes the mapping fail (an {@link IllegalArgumentException}); the caller then
 * records the failure and produces no evidence - an unknown vocabulary is never guessed into a category.
 */
public final class ValidationEvidenceMapper {
    private ValidationEvidenceMapper() {}
    public static ValidationRecord record(SessionKey session, MovementValidationResult r, long nanos) {
        var c = r.completeness();
        ValidationRecord.Completeness comp = new ValidationRecord.Completeness(c.timing(), c.reconstruction(), c.world(), c.attributes(), c.effects(), c.equipment(), c.entities(), c.border(), c.externalMotion(), c.mechanicSupported(), c.search(), c.observation(), c.frontier());
        List<EvidenceVocabulary.UncertainCode> unc = new ArrayList<>(); for (var u : r.uncertainReasons()) unc.add(EvidenceVocabulary.UncertainCode.valueOf(u.name()));
        List<EvidenceVocabulary.InvalidCode> inv = new ArrayList<>(); for (var i : r.invalidReasons()) inv.add(EvidenceVocabulary.InvalidCode.valueOf(i.name()));
        var p = r.proof();
        ValidationRecord.ProofSummary proof = p == MovementValidationResult.Proof.NONE ? ValidationRecord.ProofSummary.NONE
            : new ValidationRecord.ProofSummary(true, p.hypothesesExhausted(), p.reconstructions(), p.evaluations(), p.rawEnumerated(), p.excludedExact(), p.excludedEnvelope(), p.candidatesRemaining(), p.observationProfile(), p.movementProfile(), p.collisionProfile(), p.timingProfile(), p.worldRevision(), p.hypothesisIds());
        var s = r.residual();
        ValidationRecord.ResidualSummary res = !s.present() ? ValidationRecord.ResidualSummary.NONE
            : new ValidationRecord.ResidualSummary(true, s.observedHorizontal(), s.observedRise(), s.maxLegalHorizontal(), s.maxLegalRise(), s.minLegalRise(), EvidenceVocabulary.BoundKind.valueOf(s.bound().name()), s.horizontalExcess(), s.verticalExcess());
        ValidationRecord.WorkSummary work = new ValidationRecord.WorkSummary(r.counts().timingHypotheses(), r.counts().completeTimingHypotheses(), r.counts().explainingHypotheses(), r.counts().legalCandidates(), r.counts().reconstructions(), r.work().evaluations());
        int influences = 0; boolean correctionApplication = false; for (var w : r.witnesses()) { influences = Math.max(influences, w.appliedInfluences().size()); correctionApplication |= w.teleportApplication(); }
        var pv = r.provenance();
        ValidationRecord.Provenance prov = new ValidationRecord.Provenance(pv.timingProfile(), pv.movementProfile(), pv.observationProfile(), pv.collisionProfile(), pv.worldRevision(), influences, correctionApplication);
        return new ValidationRecord(session, r.worldEpoch(), r.startSequence(), r.endSequence(), nanos, EvidenceVocabulary.Classification.valueOf(r.classification().name()), comp, unc, inv, proof, res, work, prov, r.witnesses().size());
    }
}
