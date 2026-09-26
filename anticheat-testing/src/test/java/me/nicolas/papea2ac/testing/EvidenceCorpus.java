package me.nicolas.papea2ac.testing;
import static me.nicolas.papea2ac.testing.ValidationTestSupport.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import me.nicolas.papea2ac.core.ValidationEvidenceMapper;
import me.nicolas.papea2ac.evidence.EvidenceEvent;
import me.nicolas.papea2ac.evidence.EvidenceLedger;
import me.nicolas.papea2ac.evidence.EvidenceLedger.Outcome;
import me.nicolas.papea2ac.evidence.EvidenceSnapshot;
import me.nicolas.papea2ac.evidence.ValidationRecord;
import me.nicolas.papea2ac.model.SessionKey;
/**
 * Phase 9 corpora: every Phase 8 corpus chain (legitimate fuzz / velocity / teleport / native, and the impossible mutations) is projected
 * through core's mapper and ingested into one evidence ledger per case (one case = one session), exactly as the live runner does. The
 * sink counts consumed records by classification, positive events, duplicates, proof rejections and events per family, and can re-deliver
 * every record a second time for the duplicate gate.
 */
final class EvidenceCorpus {
    private EvidenceCorpus() {}
    static final class Sink implements java.util.function.BiConsumer<String, List<Validated>> {
        final String name; long consumed, valid, uncertain, invalid, events, duplicates, rejected, outOfOrder, cases; final Map<String, Integer> eventsByCategory = new TreeMap<>(), rejections = new TreeMap<>(), gaps = new TreeMap<>(); final List<String> eventCases = new ArrayList<>();
        final List<List<ValidationRecord>> retained = new ArrayList<>(); final boolean retain; final List<String> digests = new ArrayList<>(); double maxH, maxV; long duplicatePassEvents, duplicatePassDuplicates;
        Sink(String name, boolean retain) { this.name = name; this.retain = retain; }
        static SessionKey session(String caseId) { return new SessionKey(UUID.nameUUIDFromBytes(("papea2ac-p9-" + caseId).getBytes(StandardCharsets.UTF_8)), 1); }
        @Override public void accept(String caseId, List<Validated> chain) {
            cases++; SessionKey key = session(caseId); EvidenceLedger l = new EvidenceLedger(key); List<ValidationRecord> recs = new ArrayList<>();
            for (Validated v : chain) {
                ValidationRecord r = ValidationEvidenceMapper.record(key, v.result(), v.target().nanos()); recs.add(r); consumed++;
                switch (r.classification()) { case VALID -> valid++; case UNCERTAIN -> { uncertain++; for (var g : r.uncertainReasons()) gaps.merge(g.name(), 1, Integer::sum); } case INVALID -> invalid++; }
                Outcome o = l.ingest(r);
                switch (o) {
                    case POSITIVE_EVENT -> { events++; EvidenceEvent e = l.lastEvent().orElseThrow(); for (var c : e.categories()) eventsByCategory.merge(c.name(), 1, Integer::sum); maxH = Math.max(maxH, e.magnitude().horizontalExcess()); maxV = Math.max(maxV, Math.abs(e.magnitude().verticalExcess())); if (eventCases.size() < 8) eventCases.add(caseId + "@" + r.endSequence()); if (digests.size() < 64) digests.add(e.id()); }
                    case DUPLICATE -> duplicates++;
                    case PROOF_REJECTED -> { rejected++; rejections.merge(l.lastRejection().map(Enum::name).orElse("?"), 1, Integer::sum); }
                    case OUT_OF_ORDER -> outOfOrder++;
                    default -> { }
                }
            }
            if (retain) retained.add(recs);
        }
        /** Re-delivers every retained record a second time, right after its first delivery, into fresh ledgers: every event must come back as DUPLICATE and the event count must equal the single-delivery count. */
        void duplicatePass() {
            for (List<ValidationRecord> recs : retained) {
                EvidenceLedger m = new EvidenceLedger(recs.get(0).session());
                for (ValidationRecord r : recs) { if (m.ingest(r) == Outcome.POSITIVE_EVENT) duplicatePassEvents++; Outcome o = m.ingest(r); if (o == Outcome.POSITIVE_EVENT) duplicatePassEvents++; else if (o == Outcome.DUPLICATE) duplicatePassDuplicates++; }
            }
        }
        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>(); m.put("cases", cases); m.put("transitionsConsumed", consumed); m.put("VALID", valid); m.put("UNCERTAIN", uncertain); m.put("INVALID", invalid); m.put("positiveEvidenceEvents", events); m.put("duplicates", duplicates); m.put("proofRejected", rejected); m.put("outOfOrder", outOfOrder);
            m.put("eventsByCategory", eventsByCategory); m.put("rejections", rejections); m.put("gapReasons", gaps); m.put("maxHorizontalExcess", maxH); m.put("maxVerticalExcess", maxV); m.put("firstEventCases", eventCases); return m;
        }
    }
    static String sha256(String text) {
        try { byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); StringBuilder out = new StringBuilder(); for (byte b : h) out.append(String.format("%02x", b)); return out.toString(); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    /** A snapshot's headline as JSON (fixtures). */
    static Map<String, Object> snapshotJson(EvidenceSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activeEvents", s.activeEvents()); m.put("totalEvents", s.totalEvents()); m.put("expiredEvents", s.expiredEvents()); m.put("evictedEvents", s.evictedEvents()); m.put("currentRun", s.currentRun()); m.put("longestRun", s.longestRun());
        m.put("valid", s.validObserved()); m.put("uncertain", s.uncertainObserved()); m.put("invalid", s.invalidObserved()); m.put("proofRejected", s.proofRejected()); m.put("duplicates", s.duplicates()); m.put("outOfOrder", s.outOfOrder());
        m.put("activeGroups", s.activeGroups()); m.put("totalGroups", s.totalGroups()); m.put("correlationIncomplete", s.correlationIncomplete()); m.put("epochSegments", s.epochSegments()); m.put("worldEpoch", s.worldEpoch());
        List<String> cats = new ArrayList<>(); for (var c : s.activeByCategory()) cats.add(c.category() + "=" + c.count()); m.put("activeByCategory", cats);
        List<String> gaps = new ArrayList<>(); for (var g : s.gapReasons()) gaps.add(g.reason() + "=" + g.count()); m.put("gapReasons", gaps);
        List<String> ids = new ArrayList<>(); for (String id : s.supportingIds()) ids.add(id.substring(0, 16)); m.put("supportingIdPrefixes", ids);
        m.put("newestEventAgeNanos", s.newestEventAgeNanos()); m.put("oldestActiveAgeNanos", s.oldestActiveAgeNanos()); m.put("lastClassification", s.lastClassification().name());
        return m;
    }
}
