# Evidence and violations

## Decision E1: typed evidence before policy

**DECISION:** Validation emits immutable evidence; a separate actor-owned violation policy decides whether evidence supports any mitigation intent. Deterministic exclusion and statistical suspicion have different evidence types.

**WHY:** Repeated correlated symptoms do not independently prove cheating. Detection code must not punish directly.

**ALTERNATIVES:** Checks execute commands; one floating violation score for every anomaly; confidence numbers treated as probabilities without calibration.

**TRADEOFFS:** More schemas and reviewable policies; less immediate automation until calibration exists.

## Evidence schema

EvidenceRecord fields: schemaVersion, evidenceId, session pseudonym, connection/world/validation epochs, originating sequence/range, engine/ruleset/config/build IDs, category, validation status, completeness dimensions, uncertainty reasons, observed claim, supported witness or exclusion summary, residual/tolerance units and provenance, causal correlation key, referenced timeline/world/shape revisions, resource usage, replay manifest/hash and capture completeness, created logical time, and supersedes/resolves ID where applicable.

EvidenceRecord/ViolationDecision are evidence-local; its ReplayMapper exports archival DTOs, while core separately redacts public EvidenceSummary. Add certified outcome IDs, solver proof/coverage IDs, eye provenance where relevant, effective budgets, and rootObservationIds. Roots include the primitive observation claims/updates that caused the finding across engines, not every shared static registry/checkpoint dependency.

Kinds: DeterministicExclusion, LegitimateWitness, UnresolvedValidation, BehavioralFeatureWindow, ResourceHealthEvent and ProtocolSafetyEvent. Do not serialize a Java exception or whole player object into arbitrary-size evidence. Payload sections have size limits, stable tags and redacted diagnostic fields. Human-facing explanations identify the observation, relevant state and reason a conclusion is limited.

## Violation policy

Actor receives EvidenceRecord -> deduplicates evidenceId -> groups by shared cause/window -> rejects uncertain/incomplete/incompatible-policy records for punitive accumulation -> applies a versioned category policy -> emits a ViolationDecision. Store contributor IDs, group caps, decay clock, policy revision and intended action class. Replay uses recorded logical/monotonic deltas for decay; it never reads the machine clock.

Movement mismatch, velocity mismatch and derived ground mismatch from the same simulation are one causal family; they do not contribute three independent strikes. Resolution of provisional evidence replaces its contribution exactly once. World-gap/resource events have zero cheating weight. Statistical windows need minimum sample counts, calibration population, confounder handling and held-out validation. A score is not a probability unless calibrated as such.

General rule: within a session/epoch and a recorded 30-second correlation window, union evidence groups whose rootObservationIds overlap or whose explicit causal key is identical; take transitive closure. Groups contribute at most one policy-capped weight (maximum eligible member, not sum). Different engines do not establish independence. Maximum 64 root IDs/record; truncated or evicted roots set CorrelationIncomplete and prohibit additional punitive weight rather than pretending disjointness. A single spoofed movement giving movement/reach/timer findings must form one group. Cross-session aggregate anomalies go to the global admission guard in MITIGATION, not shared player guilt.

Initial policies are observe-only, including deterministic exclusions. Enabling any automated setback requires engine correctness, replay completeness and human-approved category thresholds on representative benign and adversarial data. Heuristics/ML cannot automatically ban in the initial production policy. Ban/punishment is an administrative action outside engine logic, permission-gated and audited; it must not be a disguised side effect of alerting.

## Bounded storage and capture

Keep 256 evidence summaries and 128 correlation groups per session, under a 256 KiB evidence budget. Expired groups contribute according to a deterministic capped aggregate, not a list of all past events. There is a fixed category registry; arbitrary client strings never become category/map keys.

Important actionable evidence requires a complete globally admitted replay capsule. DURABLE_REQUIRED remains the only initial automated-effect policy; no in-memory-only effect mode is silently introduced. If closure/persistence cannot meet the 500 ms deadline, skip the effect and count CaptureDeadlineMiss. Phase 9 must measure sparse movement, dense combat and chunk-churn capsule sizes/durable latency before Phase 10 may enable mitigation. Required gate: >=99% complete durable captures within 400 ms under the declared qualification load. If it fails, automated mitigation stays disabled; a new policy needs review. I/O receipts return through sequencing and never mutate evidence state.

Retain local replay files seven days or 5 GiB total by default, whichever limit is reached first; eviction and access are audited. These are operational defaults for review, not legal retention advice. No raw IP, authentication token, chat content or unrelated inventory text is necessary for physics evidence. Export is explicit, permission-checked and sanitized.

Retention and policy defaults are owned by CONFIGURATION, not scattered constants. Per-session low coverage and sustained budget exhaustion produce zero-weight operational events. Provisional or rejected inbound transitions cannot generate actionable deterministic evidence, regardless of solver completeness.
