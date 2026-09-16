# Phase 0 architecture remediation

PHASE 0 REMEDIATION STATUS:
READY FOR SENIOR ARCHITECTURE REVIEW #2

## REVIEW SUMMARY

Review #1 is [PHASE_0_SENIOR_REVIEW.md](PHASE_0_SENIOR_REVIEW.md), verdict REQUEST CHANGES. It was read in full before edits and preserved byte-for-byte (SHA-256 86F18BCB1F00D7C8AAF29BB9A80A5261F658476B2B1D9F333D130FA3AE182341). The revised package is a working-tree submission, not an approval or committed baseline. The original 22 specification files are archived under review-artifacts/review1-input for exact comparison.

This report disposes all 49 numbered findings: B1–B4, H1–H14, M1–M13, L1–L6 and RT1–RT12. U1–U6 additionally track the six unnumbered red-team notes. Repeated narrative observations are addressed by their associated findings and the extra lifecycle/coverage/first-witness rules, not ignored because they lack a second ID.

### What is complete versus pending

- **Completed now:** architecture decisions, contracts, ownership/DAG, per-finding dispositions, test specifications, roadmap/gates, preserved comparison inputs and static document review.
- **Later implementation:** all Java/modules/Gradle migration, transport outcome provider, solver/envelope, capture harness, API/config loader and runtime controls. None was implemented here.
- **Unmeasured:** native application/physics semantics, table fidelity, cross-host bit equality, concrete solver coverage, heap/tier fit, event-loop cost, global-load capacity and capsule size/durability.
- **Pending gates:** independent Review 2 APPROVE, human approval and baseline commit before Phase 1; each listed runtime test before its owning phase/capability qualifies. Documentation is not execution evidence.

“Resolved” below means the specification defect has an explicit reviewed-proposal answer. It does not claim that the reviewer's future runtime acceptance criteria have already passed. PARTIALLY ACCEPT records both the accepted problem and the technical reason for choosing a different or narrower remedy.

## FINDING DISPOSITIONS

### B1

- **FINDING ID:** B1
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Acyclicity did not provide legal constructor and service paths.
- **DOCUMENTS CHANGED:** [ARCHITECTURE](ARCHITECTURE.md), [MITIGATION](MITIGATION.md), [REPLAY_SYSTEM](REPLAY_SYSTEM.md), [INTERACTION_MODEL](INTERACTION_MODEL.md).
- **ARCHITECTURAL CHANGE:** Add model/replay-schema/interaction; core directly constructs collision and physics; mitigation implements the model clearance port using collision; local mappers feed schema-only replay.
- **ACCEPTANCE CRITERIA:** All four original behavior paths must use declared direct edges.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** The adjacency table and four traced paths now state each constructor/port/mapper owner. Static DAG/path validation applies now; compiled boundary tests await Phase 1.

### B2

- **FINDING ID:** B2
- **DISPOSITION:** ACCEPT
- **RATIONALE:** The two-consumer heuristic did not define a public API.
- **DOCUMENTS CHANGED:** [ARCHITECTURE](ARCHITECTURE.md), [PLAYER_STATE](PLAYER_STATE.md), [EVIDENCE_MODEL](EVIDENCE_MODEL.md), [REPLAY_SYSTEM](REPLAY_SYSTEM.md), [CONFIGURATION](CONFIGURATION.md).
- **ARCHITECTURAL CHANGE:** Public api is deliberate integration contracts only; internal values belong to model, engine types to their owners, archival DTOs to replay-schema.
- **ACCEPTANCE CRITERIA:** Every named type must have a declaration owner; api must explicitly exclude internal state.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** A3 enumerates all requested categories including EvidenceRecord, TimelineSlice, WorldView, SimulationResult, MitigationIntent, ChunkSectionSnapshot and SafeAnchor. DTO round-trip tests remain scheduled.

### B3

- **FINDING ID:** B3
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Pre-process observation cannot establish downstream server acceptance; topology audits alone miss transient changes.
- **DOCUMENTS CHANGED:** [PACKET_PIPELINE](PACKET_PIPELINE.md), [THREADING_MODEL](THREADING_MODEL.md), [PLAYER_STATE](PLAYER_STATE.md), [PLAYER_TIMELINE](PLAYER_TIMELINE.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Journal attempts provisionally; only correlated outcomes and resolved dependencies promote certification; unsupported outcome provider means uncertified observe-only.
- **ACCEPTANCE CRITERIA:** Cancellation/mutation after observation cannot advance a false certified frontier; unknown boundary/outcome must drop coverage.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** P2 defines prefix promotion, rejection/transformation reconciliation, pending timeout and fallback. TC01/TC02 specify downstream and transient-handler tests; no runtime provider is claimed qualified.

### B4

- **FINDING ID:** B4
- **DISPOSITION:** PARTIALLY ACCEPT
- **RATIONALE:** A fixed review artifact is needed, but SHA-256 does bind exact bytes without HEAD. Committing before human approval conflicts with the requested sequence and risks committing pre-staged user files prematurely.
- **DOCUMENTS CHANGED:** [AI_DEVELOPMENT_RULES](AI_DEVELOPMENT_RULES.md), [PHASE_0_REVIEW](PHASE_0_REVIEW.md), [README](../README.md).
- **ARCHITECTURAL CHANGE:** Use an exact manifest and archived old inputs for working-tree Review 2; then senior approval, human approval, baseline commit, committed-blob verification and separate receipt.
- **ACCEPTANCE CRITERIA:** Replace the original commit-now criterion with a satisfiable content-bound review and a real committed baseline before Phase 1.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** D2 defines the sequence; archive/manifest/diff make review reproducible. The literal old criterion git rev-parse HEAD succeeding is deliberately NOT satisfied now. No commit is fabricated.

### H1

- **FINDING ID:** H1
- **DISPOSITION:** PARTIALLY ACCEPT
- **RATIONALE:** Flat enumeration is infeasible in plausible cases. However 144 independent inputs is not a verified target domain, and inverse solving is not generally constant-time through collision/clamps.
- **DOCUMENTS CHANGED:** [MOVEMENT_SIMULATION](MOVEMENT_SIMULATION.md), [INPUT_RECONSTRUCTION](INPUT_RECONSTRUCTION.md), [PERFORMANCE](PERFORMANCE.md), [TESTING](TESTING.md), [ROADMAP](ROADMAP.md).
- **ARCHITECTURAL CHANGE:** Separate concrete witnesses from interval reachability, inverse constraints and bounded discrete refinement; preserve covering nodes.
- **ACCEPTANCE CRITERIA:** Declare exclusion proof and bounded pilot branch accounting; demonstrate impossible-case INVALID and useful adversarial coverage later.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** M2 gives conditional pilot accounting (at most 4 outer cases/step, 85 tree nodes through 3 steps), exact proof conditions and TC11/TC12 plus numeric coverage gates. Target-case qualification and runtime rates remain unproven Phase 5/6/8 obligations.

### H2

- **FINDING ID:** H2
- **DISPOSITION:** ACCEPT
- **RATIONALE:** An unnamed conservative envelope was insufficient to justify merging or exclusion.
- **DOCUMENTS CHANGED:** [MOVEMENT_SIMULATION](MOVEMENT_SIMULATION.md), [NUMERICAL_POLICY](NUMERICAL_POLICY.md), [TESTING](TESTING.md), [ROADMAP](ROADMAP.md).
- **ARCHITECTURAL CHANGE:** Define tagged interval unions, application automaton, gamma containment, outward arithmetic and top/unknown fallback.
- **ACCEPTANCE CRITERIA:** State containment obligation; no abstract-only LegitimateWitness; assign delivery phase.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** M3 defines concrete meaning, transfer/merge rules and limitations. Phase 5 owns operators/proofs; 6 composition/refinement; TC12 checks containment and no envelope-only witness. Proof review is pending.

### H3

- **FINDING ID:** H3
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Query history is the minimum of its dependencies; marker diagnostic retention cannot promise combat coverage.
- **DOCUMENTS CHANGED:** [PERFORMANCE](PERFORMANCE.md), [PLAYER_STATE](PLAYER_STATE.md), [PLAYER_TIMELINE](PLAYER_TIMELINE.md), [LATENCY_COMPENSATION](LATENCY_COMPENSATION.md), [COMBAT_MODEL](COMBAT_MODEL.md).
- **ARCHITECTURAL CHANGE:** Use one three-second qualification target with explicit rates/bytes and per-query achieved horizons.
- **ACCEPTANCE CRITERIA:** Derive count/byte/time bounds and report 30-entity workload shortfalls.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** PERFORMANCE derives 1000 records/s, 4096/1 MiB timeline and 96-revision entity caps, plus high-rate/floor-tier shortfalls. TC10 and retention qualification remain future tests.

### H4

- **FINDING ID:** H4
- **DISPOSITION:** PARTIALLY ACCEPT
- **RATIONALE:** Lost baselines are not automatically recoverable; a future full baseline can restore a region, so permanent unrecoverability is conditional. A current shared snapshot cannot prove absent fake-block divergence.
- **DOCUMENTS CHANGED:** [WORLD_MODEL](WORLD_MODEL.md), [THREADING_MODEL](THREADING_MODEL.md), [PLAYER_STATE](PLAYER_STATE.md), [PLAYER_TIMELINE](PLAYER_TIMELINE.md), [PERFORMANCE](PERFORMANCE.md).
- **ARCHITECTURAL CHANGE:** Choose lazy captured regional recovery and explicit mid-session baseline-missing state; preserve observer precedence; compare divergence only on fully captured data.
- **ACCEPTANCE CRITERIA:** Choose bootstrap/eviction policy, state its limitations and evaluate model costs.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** W2 defines separate coverage epochs and conservative fallback; distance 10/16 arithmetic is supplied. Real memory/copy comparisons are explicitly pending Phase 3, not presented as measurements.

### H5

- **FINDING ID:** H5
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Diagnostic ledger size did not bound simultaneously unresolved motion state.
- **DOCUMENTS CHANGED:** [LATENCY_COMPENSATION](LATENCY_COMPENSATION.md), [PLAYER_STATE](PLAYER_STATE.md), [MOVEMENT_SIMULATION](MOVEMENT_SIMULATION.md), [PERFORMANCE](PERFORMANCE.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Cap eight active unresolved influences; keep 64 diagnostic records; use ordered application intervals/automata and sound branch retirement.
- **ACCEPTANCE CRITERIA:** Named overflow plus repeated-knockback coverage gate at 50/150/300 ms.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** N2 names UNCERTAIN_APPLICATION_OVERFLOW and retirement proofs; TC04/TC05 and Phase 7b >=90% definite gate are specified. No velocity-application ACK guarantee is invented.

### H6

- **FINDING ID:** H6
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Shape geometry alone cannot supply friction, bounce or inside/fluid response.
- **DOCUMENTS CHANGED:** [WORLD_MODEL](WORLD_MODEL.md), [COLLISION_ENGINE](COLLISION_ENGINE.md), [MOVEMENT_SIMULATION](MOVEMENT_SIMULATION.md), [VERSION_SUPPORT](VERSION_SUPPORT.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** World owns versioned BlockBehaviorQuery; physics applies response; inside/support/fluid sampling is explicit and bounded.
- **ACCEPTANCE CRITERIA:** Contract, unknown propagation, work cap and complete property fixture list.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** W3 and collision/physics input contracts cover requested properties; 512-cell inside cap and TC07 exist. Native property qualification remains Phase 5.

### H7

- **FINDING ID:** H7
- **DISPOSITION:** PARTIALLY ACCEPT
- **RATIONALE:** Relevant pushing and solid entities were missing. The review's blanket client-predicted characterization cannot safely apply to every version/entity mechanism.
- **DOCUMENTS CHANGED:** [WORLD_MODEL](WORLD_MODEL.md), [PLAYER_STATE](PLAYER_STATE.md), [COLLISION_ENGINE](COLLISION_ENGINE.md), [MOVEMENT_SIMULATION](MOVEMENT_SIMULATION.md), [PERFORMANCE](PERFORMANCE.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Bound a relevant observer neighborhood and qualify server-only/predicted/mixed mechanisms; deduplicate captured resultant impulses.
- **ACCEPTANCE CRITERIA:** Model or explicitly uncover push/colliders, bound work and add crowd/boat cases.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** W3 specifies 32 entities/2048 operations, spatial coverage and unknown reasons. TC06 covers 5/15/30 and overflow. Exact execution-side semantics remain a version experiment.

### H8

- **FINDING ID:** H8
- **DISPOSITION:** ACCEPT
- **RATIONALE:** The source list omitted mandatory movement/lifecycle families.
- **DOCUMENTS CHANGED:** [PACKET_PIPELINE](PACKET_PIPELINE.md), [PLAYER_STATE](PLAYER_STATE.md), [VERSION_SUPPORT](VERSION_SUPPORT.md), [TESTING](TESTING.md), [PHASE_0_REVIEW](PHASE_0_REVIEW.md).
- **ARCHITECTURAL CHANGE:** Enumerated per-direction semantic inventory includes explosion, vehicles/passengers/camera, lifecycle/health, tick state, held item/swing/control/tick-end.
- **ACCEPTANCE CRITERIA:** Missing affecting family must disable capability; all named families need mappings.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** Packet matrix and TC02/TC22 startup inventory assertions are present. Exact packet IDs and optional/version-absent mappings remain Phase 1 profile work.

### H9

- **FINDING ID:** H9
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Per-event caps did not prevent sustained cost starvation.
- **DOCUMENTS CHANGED:** [THREADING_MODEL](THREADING_MODEL.md), [PERFORMANCE](PERFORMANCE.md), [CONFIGURATION](CONFIGURATION.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Weighted sustained quota, burst/visit caps, admission sizing and visible degradation ladder.
- **ACCEPTANCE CRITERIA:** Fairness/work-exhaustion metrics and cost-maximizing load with reproducible budget changes.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** T1/fairness and PERFORMANCE specify 2M units/s, 2048/visit and record grants/config. TC11/TC24 await implementation.

### H10

- **FINDING ID:** H10
- **DISPOSITION:** PARTIALLY ACCEPT
- **RATIONALE:** A failed writer needs external reclamation, but reclaiming memory from a merely hung live thread would be unsafe.
- **DOCUMENTS CHANGED:** [THREADING_MODEL](THREADING_MODEL.md), [PERFORMANCE](PERFORMANCE.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Core supervisor fences failed shards, removes admission and releases registered leases after termination/users finish; hung leases stay charged.
- **ACCEPTANCE CRITERIA:** Terminated shard resources reclaimed, no new assignment, defined session state and alert.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** Failure state machine chooses observe-only-until-reconnect and distinguishes hung versus dead ownership. TC16 specifies both; runtime reclamation remains Phase 1.

### H11

- **FINDING ID:** H11
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Per-session loop breakers do not bound correlated server-wide effects.
- **DOCUMENTS CHANGED:** [MITIGATION](MITIGATION.md), [THREADING_MODEL](THREADING_MODEL.md), [EVIDENCE_MODEL](EVIDENCE_MODEL.md), [CONFIGURATION](CONFIGURATION.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Main-thread global admission arbiter, mode/generation control and distinct-session anomaly suspension.
- **ACCEPTANCE CRITERIA:** Thresholds, no new effects after restriction, correlated-fault test before individual loop breakers.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** G2 supplies 10-second 20%/minimum 3 and one-second 3-session gates, explicit re-enable and already-started effect boundary. TC17 remains scheduled.

### H12

- **FINDING ID:** H12
- **DISPOSITION:** PARTIALLY ACCEPT
- **RATIONALE:** Numeric fidelity and portability were underdefined. A universal StrictMath replacement or universal Minecraft table assertion would itself be unverified.
- **DOCUMENTS CHANGED:** [NUMERICAL_POLICY](NUMERICAL_POLICY.md), [VERSION_SUPPORT](VERSION_SUPPORT.md), [COLLISION_ENGINE](COLLISION_ENGINE.md), [MOVEMENT_SIMULATION](MOVEMENT_SIMULATION.md), [REPLAY_SYSTEM](REPLAY_SYSTEM.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Version-owned NumericOps, faithful qualified tables/algorithms, outward abstract rounding, host conformance and strict replay refusal on mismatch.
- **ACCEPTANCE CRITERIA:** Enumerate functions, forbid unreviewed host calls, add cross-host digest qualification.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** NUM1 sets boundaries and TC13; exact client table/algorithms and bit-identical cross-host results remain gated, not claimed tested.

### H13

- **FINDING ID:** H13
- **DISPOSITION:** PARTIALLY ACCEPT
- **RATIONALE:** The previous 16 MiB reservation was incoherent at target scale. However independent component maxima were not measured minimum requirements.
- **DOCUMENTS CHANGED:** [PERFORMANCE](PERFORMANCE.md), [PLAYER_STATE](PLAYER_STATE.md), [WORLD_MODEL](WORLD_MODEL.md), [REPLAY_SYSTEM](REPLAY_SYSTEM.md), [CONFIGURATION](CONFIGURATION.md).
- **ARCHITECTURAL CHANGE:** Global-first admission, estimated 2/4 MiB tiers, fixed 256 MiB reference partition and adaptive globally capped replay.
- **ACCEPTANCE CRITERIA:** Publish floor/reducible quotas and scale equation; no silent under-floor admission.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** F2 provides 50–1000 scale table and fallback policy. Tier fit/shared-world coverage are explicitly unmeasured; TC14/TC18 and load/heap qualification remain gates.

### H14

- **FINDING ID:** H14
- **DISPOSITION:** PARTIALLY ACCEPT
- **RATIONALE:** Raw claimed eye is unsafe as an independent witness. Mere intersection with an abstract supported envelope is also insufficient.
- **DOCUMENTS CHANGED:** [COMBAT_MODEL](COMBAT_MODEL.md), [PLAYER_STATE](PLAYER_STATE.md), [EVIDENCE_MODEL](EVIDENCE_MODEL.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Require certified concrete trajectory for a valid eye; complete outer set may only exclude; conflict becomes movement-root uncertainty.
- **ACCEPTANCE CRITERIA:** Eye source provenance and no raw-claim-only reach pass.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** Combat B2 and TC21 define this rule. Cannot promise to detect an observationally legitimate within-tolerance lie; no such false guarantee is made.

### M1

- **FINDING ID:** M1
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Configuration hashes need a semantic owner and canonical form.
- **DOCUMENTS CHANGED:** [CONFIGURATION](CONFIGURATION.md), [ARCHITECTURE](ARCHITECTURE.md), [REPLAY_SYSTEM](REPLAY_SYSTEM.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Core schema/default/validation owner; model snapshot; schema DTO; canonical semantic JSON hash.
- **ACCEPTANCE CRITERIA:** Equivalent configuration must hash equally; invalid config cannot enable enforcement.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** CFG1 defines units, defaults, key/set ordering and change paths; TC18 awaits Phase 1.

### M2

- **FINDING ID:** M2
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Legitimate integrations need a bounded declaration/query surface.
- **DOCUMENTS CHANGED:** [ARCHITECTURE](ARCHITECTURE.md), [CONFIGURATION](CONFIGURATION.md), [PLAYER_STATE](PLAYER_STATE.md), [ROADMAP](ROADMAP.md).
- **ARCHITECTURAL CHANGE:** Public scoped expiring exemptions/notices and coverage queries; no reputation-scaled proof/tolerance.
- **ACCEPTANCE CRITERIA:** Name API owner and decide trust-factor policy.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** A3 explicitly rejects trust multipliers, correlates declarations to actual effects and schedules Phase 1 queries/Phase 2 notices. Implementation remains later.

### M3

- **FINDING ID:** M3
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Category examples did not specify cross-engine correlation.
- **DOCUMENTS CHANGED:** [EVIDENCE_MODEL](EVIDENCE_MODEL.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Bounded primitive root sets, overlap/key union and transitive closure with one capped group contribution.
- **ACCEPTANCE CRITERIA:** One movement root producing movement/combat/timer findings must count once.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** Evidence rule and TC20 are explicit; missing/truncated roots cannot earn additional weight.

### M4

- **FINDING ID:** M4
- **DISPOSITION:** PARTIALLY ACCEPT
- **RATIONALE:** Age/distance and teleport side effects needed limits. Generic teleportAsync does not by itself guarantee stale-state rechecks or no loading.
- **DOCUMENTS CHANGED:** [MITIGATION](MITIGATION.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** 2-second/8-block anchors, on-foot scope, generation precedence and qualified loaded-destination execution or rejection.
- **ACCEPTANCE CRITERIA:** Old/distant/stale anchors resync; no blocking waits or unsafe destination changes.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** G1 states limits and refuses an unproven no-sync-load path; TC03/TC15 qualify execution later. Async preparation alone cannot authorize an effect.

### M5

- **FINDING ID:** M5
- **DISPOSITION:** PARTIALLY ACCEPT
- **RATIONALE:** The capture deadline may make effects unavailable, but changing auditability without measurements is unjustified.
- **DOCUMENTS CHANGED:** [EVIDENCE_MODEL](EVIDENCE_MODEL.md), [REPLAY_SYSTEM](REPLAY_SYSTEM.md), [MITIGATION](MITIGATION.md), [TESTING](TESTING.md), [ROADMAP](ROADMAP.md).
- **ARCHITECTURAL CHANGE:** Retain DURABLE_REQUIRED and gate effect enablement on three measured capsule profiles; skip expiry explicitly.
- **ACCEPTANCE CRITERIA:** Measure sizes/durable latency and adopt a deadline-consistent policy before effects.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** Policy and >=99% within 400 ms gate are specified. Measurements are NOT available in Phase 0; this empirical criterion remains pending Phase 9, blocking Phase 10 effects rather than Review 2 submission.

### M6

- **FINDING ID:** M6
- **DISPOSITION:** PARTIALLY ACCEPT
- **RATIONALE:** Feature detection is needed, but a few successful reflection probes cannot prove behavioral compatibility.
- **DOCUMENTS CHANGED:** [VERSION_SUPPORT](VERSION_SUPPORT.md), [PACKET_PIPELINE](PACKET_PIPELINE.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Qualified compatibility-class fingerprints plus live boundary/outcome/numeric probe; known build list is provenance, not bypass.
- **ACCEPTANCE CRITERIA:** Failed probe -> observe-only; support class defined.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** VERSION_SUPPORT and AdapterBoundaryProbe enumerate checks; TC02/TC18 test failure. No unknown build is certified solely from loading successfully.

### M7

- **FINDING ID:** M7
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Canonical archival keys need not be hot-path string keys.
- **DOCUMENTS CHANGED:** [WORLD_MODEL](WORLD_MODEL.md), [COLLISION_ENGINE](COLLISION_ENGINE.md), [REPLAY_SYSTEM](REPLAY_SYSTEM.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Runtime integer interning plus canonical RegistryDictionary in capsules.
- **ACCEPTANCE CRITERIA:** IDs may vary across runs while restored canonical digest stays equal.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** World lookup and schema dictionary rules plus TC19 define this; codec implementation awaits its phase.

### M8

- **FINDING ID:** M8
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Native oracles needed an owned repeatable capture mechanism.
- **DOCUMENTS CHANGED:** [TESTING](TESTING.md), [ROADMAP](ROADMAP.md).
- **ARCHITECTURAL CHANGE:** NativeScenarioHarness in testing, external/test-only input drive and read-only instrumented oracle with perturbation checks.
- **ACCEPTANCE CRITERIA:** Named deliverable, permitted instrumentation and per-version effort estimate.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** Harness is staged Phase 1/5 with estimated 4–8 engineer hours and 1–2 host-hours smoke recapture. Actual capture cost and harness do not yet exist.

### M9

- **FINDING ID:** M9
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Aggregate uncertainty hides individual coverage collapse.
- **DOCUMENTS CHANGED:** [PLAYER_STATE](PLAYER_STATE.md), [EVIDENCE_MODEL](EVIDENCE_MODEL.md), [PERFORMANCE](PERFORMANCE.md), [CONFIGURATION](CONFIGURATION.md).
- **ARCHITECTURAL CHANGE:** Per-session coverage dimensions/horizons/counters; 30-second threshold and zero-weight operational alert.
- **ACCEPTANCE CRITERIA:** Coverage summary visible and never cheating weight.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** PLAYER_STATE defines <50% definite over 30 seconds/minimum 100 observations; TC24 tests publication/fairness later.

### M10

- **FINDING ID:** M10
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Shared network-loop copy cost must be budgeted and measured.
- **DOCUMENTS CHANGED:** [PERFORMANCE](PERFORMANCE.md), [PACKET_PIPELINE](PACKET_PIPELINE.md), [PHASE_0_REVIEW](PHASE_0_REVIEW.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Per-family callback/utilization/copy metrics and provisional tail targets.
- **ACCEPTANCE CRITERIA:** Include event-loop metrics in Phase 1 report.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** PERFORMANCE names 50/500 microsecond p99 and 10% utilization targets, not guarantees; Phase 1 report gate now requires measurements.

### M11

- **FINDING ID:** M11
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Combat occlusion may be an alias, not an independent game shape family.
- **DOCUMENTS CHANGED:** [COLLISION_ENGINE](COLLISION_ENGINE.md), [VERSION_SUPPORT](VERSION_SUPPORT.md), [COMBAT_MODEL](COMBAT_MODEL.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Version-profile shape-use mapping with captured pick/outline behavior.
- **ACCEPTANCE CRITERIA:** No invented universal triple; verify target mapping.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** Collision C1 and TC qualification require provenance; exact target mapping remains unverified.

### M12

- **FINDING ID:** M12
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Placement semantics needed an owner distinct from prediction storage.
- **DOCUMENTS CHANGED:** [ARCHITECTURE](ARCHITECTURE.md), [INTERACTION_MODEL](INTERACTION_MODEL.md), [WORLD_MODEL](WORLD_MODEL.md), [ROADMAP](ROADMAP.md).
- **ARCHITECTURAL CHANGE:** Interaction owns placement/use/break/non-attack entity rules; world owns overlay; core applies outcome-correlated proposals.
- **ACCEPTANCE CRITERIA:** Trace placement, geometry and overlay reconciliation legally.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** ACT1 and declared interaction/world/collision edges provide the path; Phase 18 implements semantics.

### M13

- **FINDING ID:** M13
- **DISPOSITION:** PARTIALLY ACCEPT
- **RATIONALE:** Five omitted organizational observations are relevant, but filenames do not validate three-tick sufficiency, concrete algorithm behavior or comparative performance.
- **DOCUMENTS CHANGED:** [INTAVE_REFERENCE_ANALYSIS](INTAVE_REFERENCE_ANALYSIS.md), [ARCHITECTURE](ARCHITECTURE.md), [WORLD_MODEL](WORLD_MODEL.md).
- **ARCHITECTURAL CHANGE:** Add five sourced positions, explicit trust rejection and corrected runtime interning comparison.
- **ACCEPTANCE CRITERIA:** Address each omission with a decision and source.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** Pinned tree was re-fetched; additions cover properties/integration/trust/moving world/search. Stronger inference from filenames is explicitly rejected.

### L1

- **FINDING ID:** L1
- **DISPOSITION:** PARTIALLY ACCEPT
- **RATIONALE:** Identity/toolchain fields must be explicit; exact future API-version should not be invented before selecting the target build.
- **DOCUMENTS CHANGED:** [ARCHITECTURE](ARCHITECTURE.md), [VERSION_SUPPORT](VERSION_SUPPORT.md).
- **ARCHITECTURAL CHANGE:** List preserved identity/version/main/author, Java 25 and target-qualified api-version selection.
- **ACCEPTANCE CRITERIA:** Checklist fields and actual migration values verified later.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** Known identity fields have explicit values; API-version selection is an explicit Phase 1 pin gate, not a guessed 26.x literal. Skeleton unchanged.

### L2

- **FINDING ID:** L2
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Wrapper JAR exclusion can break clean clones.
- **DOCUMENTS CHANGED:** [ARCHITECTURE](ARCHITECTURE.md), [PHASE_0_REVIEW](PHASE_0_REVIEW.md).
- **ARCHITECTURAL CHANGE:** Require !gradle/wrapper/gradle-wrapper.jar after broad JAR rule during migration.
- **ACCEPTANCE CRITERIA:** Negation and clean-clone build after migration.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** Concrete checklist now exists; .gitignore intentionally unchanged because no migration/build is authorized now.

### L3

- **FINDING ID:** L3
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Diagram labels should match declared modules.
- **DOCUMENTS CHANGED:** [ARCHITECTURE](ARCHITECTURE.md).
- **ARCHITECTURAL CHANGE:** Replace combined labels with table names and add new boundaries.
- **ACCEPTANCE CRITERIA:** Every diagram node label maps to one module.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** Revised Mermaid nodes use exact table names; static check validates them.

### L4

- **FINDING ID:** L4
- **DISPOSITION:** ACCEPT
- **RATIONALE:** A task name is not a verified server distribution.
- **DOCUMENTS CHANGED:** [INTAVE_REFERENCE_ANALYSIS](INTAVE_REFERENCE_ANALYSIS.md).
- **ARCHITECTURAL CHANGE:** Describe testPaper task matrix and observed bundled Spigot/Paper/alternative artifact mix.
- **ACCEPTANCE CRITERIA:** Avoid asserting all runtime servers are Paper.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** Pinned libs tree was inspected and wording corrected; no per-task runtime claim is inferred.

### L5

- **FINDING ID:** L5
- **DISPOSITION:** ACCEPT
- **RATIONALE:** The README pointed to nonexistent content below it.
- **DOCUMENTS CHANGED:** [README](../README.md).
- **ARCHITECTURAL CHANGE:** Point to specifications/remediation and remove below wording.
- **ACCEPTANCE CRITERIA:** README scope sentence accurate.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** Sentence corrected; no runtime behavior involved.

### L6

- **FINDING ID:** L6
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Retention needs configuration ownership.
- **DOCUMENTS CHANGED:** [CONFIGURATION](CONFIGURATION.md), [EVIDENCE_MODEL](EVIDENCE_MODEL.md), [REPLAY_SYSTEM](REPLAY_SYSTEM.md).
- **ARCHITECTURAL CHANGE:** Core-owned schema contains 7-day/5 GiB defaults.
- **ACCEPTANCE CRITERIA:** Named schema owner for retention.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** CFG1 includes retention fields/defaults and digest rules; loader is later work.

### RT1

- **FINDING ID:** RT1
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Explosion motion requires an explicit scenario disposition in addition to H8/H6. Exact native behavior still needs version qualification.
- **DOCUMENTS CHANGED:** [PLAYER_STATE](PLAYER_STATE.md), [PACKET_PIPELINE](PACKET_PIPELINE.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** ExternalMotionInfluence captures explosion motion separately from blocks and source mob.
- **ACCEPTANCE CRITERIA:** Scenario must produce a concrete qualified legitimate result or explicit uncertainty, never false complete exclusion; execute TC05.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** The linked contracts and TC05 specify the scenario and assertions. This is a design correction/preservation, not a completed runtime regression.

### RT2

- **FINDING ID:** RT2
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Crowded pushing requires an explicit scenario disposition in addition to H7. Exact native behavior still needs version qualification.
- **DOCUMENTS CHANGED:** [WORLD_MODEL](WORLD_MODEL.md), [PLAYER_STATE](PLAYER_STATE.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Side-aware neighborhood includes push/colliders with 32-entity cap and explicit overflow.
- **ACCEPTANCE CRITERIA:** Scenario must produce a concrete qualified legitimate result or explicit uncertainty, never false complete exclusion; execute TC06.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** The linked contracts and TC06 specify the scenario and assertions. This is a design correction/preservation, not a completed runtime regression.

### RT3

- **FINDING ID:** RT3
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Block physical effects requires an explicit scenario disposition in addition to H6. Exact native behavior still needs version qualification.
- **DOCUMENTS CHANGED:** [WORLD_MODEL](WORLD_MODEL.md), [COLLISION_ENGINE](COLLISION_ENGINE.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** BlockBehaviorQuery includes friction/speed/inside/fluid responses; no default on unknown.
- **ACCEPTANCE CRITERIA:** Scenario must produce a concrete qualified legitimate result or explicit uncertainty, never false complete exclusion; execute TC07.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** The linked contracts and TC07 specify the scenario and assertions. This is a design correction/preservation, not a completed runtime regression.

### RT4

- **FINDING ID:** RT4
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Downstream cancellation requires an explicit scenario disposition in addition to B3. Exact native behavior still needs version qualification.
- **DOCUMENTS CHANGED:** [PACKET_PIPELINE](PACKET_PIPELINE.md), [PLAYER_STATE](PLAYER_STATE.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** P2 forbids cancelled/unknown attempts from certified promotion.
- **ACCEPTANCE CRITERIA:** Scenario must produce a concrete qualified legitimate result or explicit uncertainty, never false complete exclusion; execute TC01/TC02.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** The linked contracts and TC01/TC02 specify the scenario and assertions. This is a design correction/preservation, not a completed runtime regression.

### RT5

- **FINDING ID:** RT5
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Online activation requires an explicit scenario disposition in addition to H4. Exact native behavior still needs version qualification.
- **DOCUMENTS CHANGED:** [WORLD_MODEL](WORLD_MODEL.md), [THREADING_MODEL](THREADING_MODEL.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Baseline-missing observation mode and regional lazy coverage are explicit.
- **ACCEPTANCE CRITERIA:** Scenario must produce a concrete qualified legitimate result or explicit uncertainty, never false complete exclusion; execute TC09/TC10.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** The linked contracts and TC09/TC10 specify the scenario and assertions. This is a design correction/preservation, not a completed runtime regression.

### RT6

- **FINDING ID:** RT6
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Stall/tick control requires an explicit scenario disposition in addition to H8. Exact native behavior still needs version qualification.
- **DOCUMENTS CHANGED:** [PACKET_PIPELINE](PACKET_PIPELINE.md), [VERSION_SUPPORT](VERSION_SUPPORT.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Tick-rate/freeze/step and server stall inputs invalidate unsupported timer/motion assumptions.
- **ACCEPTANCE CRITERIA:** Scenario must produce a concrete qualified legitimate result or explicit uncertainty, never false complete exclusion; execute TC22.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** The linked contracts and TC22 specify the scenario and assertions. This is a design correction/preservation, not a completed runtime regression.

### RT7

- **FINDING ID:** RT7
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Delayed knockback requires an explicit scenario disposition in addition to H5/H1. Exact native behavior still needs version qualification.
- **DOCUMENTS CHANGED:** [LATENCY_COMPENSATION](LATENCY_COMPENSATION.md), [MOVEMENT_SIMULATION](MOVEMENT_SIMULATION.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Application intervals and eight-active bound feed sound solver with visible coverage floors.
- **ACCEPTANCE CRITERIA:** Scenario must produce a concrete qualified legitimate result or explicit uncertainty, never false complete exclusion; execute TC04/TC05 and Phase 7b.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** The linked contracts and TC04/TC05 and Phase 7b specify the scenario and assertions. This is a design correction/preservation, not a completed runtime regression.

### RT8

- **FINDING ID:** RT8
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Elytra/riptide/firework requires an explicit scenario disposition in addition to H8. Exact native behavior still needs version qualification.
- **DOCUMENTS CHANGED:** [PLAYER_STATE](PLAYER_STATE.md), [VERSION_SUPPORT](VERSION_SUPPORT.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** ModeAcceleration requires qualified source paths or explicit unsupported coverage.
- **ACCEPTANCE CRITERIA:** Scenario must produce a concrete qualified legitimate result or explicit uncertainty, never false complete exclusion; execute TC23.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** The linked contracts and TC23 specify the scenario and assertions. This is a design correction/preservation, not a completed runtime regression.

### RT9

- **FINDING ID:** RT9
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Vehicles requires an explicit scenario disposition in addition to H8/H7. Exact native behavior still needs version qualification.
- **DOCUMENTS CHANGED:** [PACKET_PIPELINE](PACKET_PIPELINE.md), [WORLD_MODEL](WORLD_MODEL.md), [VERSION_SUPPORT](VERSION_SUPPORT.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Vehicle controls/passengers/correction are mandatory; unobserved transition cannot retain walking frontier.
- **ACCEPTANCE CRITERIA:** Scenario must produce a concrete qualified legitimate result or explicit uncertainty, never false complete exclusion; execute TC06/TC22/TC23.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** The linked contracts and TC06/TC22/TC23 specify the scenario and assertions. This is a design correction/preservation, not a completed runtime regression.

### RT10

- **FINDING ID:** RT10
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Pistons/slime train requires an explicit scenario disposition in addition to H6. Exact native behavior still needs version qualification.
- **DOCUMENTS CHANGED:** [PLAYER_STATE](PLAYER_STATE.md), [WORLD_MODEL](WORLD_MODEL.md), [VERSION_SUPPORT](VERSION_SUPPORT.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** KinematicDisplacement couples temporal geometry and movement, not an invented impulse.
- **ACCEPTANCE CRITERIA:** Scenario must produce a concrete qualified legitimate result or explicit uncertainty, never false complete exclusion; execute TC08.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** The linked contracts and TC08 specify the scenario and assertions. This is a design correction/preservation, not a completed runtime regression.

### RT11

- **FINDING ID:** RT11
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Fake blocks requires an explicit scenario disposition in addition to H4. Exact native behavior still needs version qualification.
- **DOCUMENTS CHANGED:** [WORLD_MODEL](WORLD_MODEL.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Preserve observer precedence; reject unknown bootstrap instead of overlaying server truth.
- **ACCEPTANCE CRITERIA:** Scenario must produce a concrete qualified legitimate result or explicit uncertainty, never false complete exclusion; execute TC10.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** The linked contracts and TC10 specify the scenario and assertions. This is a design correction/preservation, not a completed runtime regression.

### RT12

- **FINDING ID:** RT12
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Foreign teleport/respawn requires an explicit scenario disposition in addition to H8/M4. Exact native behavior still needs version qualification.
- **DOCUMENTS CHANGED:** [MITIGATION](MITIGATION.md), [PACKET_PIPELINE](PACKET_PIPELINE.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Foreign generation supersedes pending intent; already-started effects reconcile with later foreign action.
- **ACCEPTANCE CRITERIA:** Scenario must produce a concrete qualified legitimate result or explicit uncertainty, never false complete exclusion; execute TC03/TC15.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** The linked contracts and TC03/TC15 specify the scenario and assertions. This is a design correction/preservation, not a completed runtime regression.

### U1

- **FINDING ID:** U1
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Local tracking ID for the unnumbered red-team note: Packet delay/jitter. No original review ID is being invented.
- **DOCUMENTS CHANGED:** [LATENCY_COMPENSATION](LATENCY_COMPENSATION.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Retain causal coverage and explicit gaps; do not mislabel TCP head-of-line delay as packet loss.
- **ACCEPTANCE CRITERIA:** Retain explicit scope/uncertainty and qualify through TC04/TC22.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** Normative contracts now cover the note. TC04/TC22 remains scheduled; no measurements are claimed.

### U2

- **FINDING ID:** U2
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Local tracking ID for the unnumbered red-team note: Mid-flight attributes/effects. No original review ID is being invented.
- **DOCUMENTS CHANGED:** [LATENCY_COMPENSATION](LATENCY_COMPENSATION.md), [PACKET_PIPELINE](PACKET_PIPELINE.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Treat attribute/effect application as active unresolved state in the same bounded domain.
- **ACCEPTANCE CRITERIA:** Retain explicit scope/uncertainty and qualify through Phase 7b.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** Normative contracts now cover the note. Phase 7b remains scheduled; no measurements are claimed.

### U3

- **FINDING ID:** U3
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Local tracking ID for the unnumbered red-team note: Creative/spectator changes. No original review ID is being invented.
- **DOCUMENTS CHANGED:** [PACKET_PIPELINE](PACKET_PIPELINE.md), [VERSION_SUPPORT](VERSION_SUPPORT.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Observe gamemode/abilities/camera and invalidate old unsupported modes.
- **ACCEPTANCE CRITERIA:** Retain explicit scope/uncertainty and qualify through TC22.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** Normative contracts now cover the note. TC22 remains scheduled; no measurements are claimed.

### U4

- **FINDING ID:** U4
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Local tracking ID for the unnumbered red-team note: Grazing/edge collisions. No original review ID is being invented.
- **DOCUMENTS CHANGED:** [COLLISION_ENGINE](COLLISION_ENGINE.md), [NUMERICAL_POLICY](NUMERICAL_POLICY.md), [TESTING](TESTING.md).
- **ARCHITECTURAL CHANGE:** Preserve tie/tolerance provenance and add cross-host boundary cases.
- **ACCEPTANCE CRITERIA:** Retain explicit scope/uncertainty and qualify through TC12/TC13.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** Normative contracts now cover the note. TC12/TC13 remains scheduled; no measurements are claimed.

### U5

- **FINDING ID:** U5
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Local tracking ID for the unnumbered red-team note: Version translation scope. No original review ID is being invented.
- **DOCUMENTS CHANGED:** [VERSION_SUPPORT](VERSION_SUPPORT.md), [ROADMAP](ROADMAP.md).
- **ARCHITECTURAL CHANGE:** Explicit native-only product envelope; Phase 25 prioritizes qualified translations by measured demand.
- **ACCEPTANCE CRITERIA:** Retain explicit scope/uncertainty and qualify through Phase 25 profile gates.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** Normative contracts now cover the note. Phase 25 profile gates remains scheduled; no measurements are claimed.

### U6

- **FINDING ID:** U6
- **DISPOSITION:** ACCEPT
- **RATIONALE:** Local tracking ID for the unnumbered red-team note: Custom movement plugins. No original review ID is being invented.
- **DOCUMENTS CHANGED:** [ARCHITECTURE](ARCHITECTURE.md), [CONFIGURATION](CONFIGURATION.md), [PLAYER_STATE](PLAYER_STATE.md).
- **ARCHITECTURAL CHANGE:** Bounded authorized declarations/exemptions with expiry; unknown custom physics never defaults to vanilla.
- **ACCEPTANCE CRITERIA:** Retain explicit scope/uncertainty and qualify through Phase 2 API tests.
- **HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED:** Normative contracts now cover the note. Phase 2 API tests remains scheduled; no measurements are claimed.

## BLOCKERS RESOLVED

B1: legal composition/clearance/interaction/projection paths now exist. B2: public api is explicitly separated from internal model and replay-schema. B3: provisional observation cannot certify rejected or outcome-unknown state; unavailable outcome integration stays observe-only. B4: content-bound working-tree review and post-approval committed baseline are separate gates. RT4 is the corresponding B3 scenario.

## BLOCKERS REMAINING

No **unresolved architectural decision** remains in B1–B4. Independent approval is pending. A production authoritative-outcome provider is NOT proven and the initial capture-only fallback cannot enforce; this is an explicit implementation/qualification gate, not a hidden promise of transport support. No baseline commit exists, by the chosen approval sequence. Neither condition is represented as complete.

## HIGH PRIORITY FINDINGS RESOLVED

H1–H14 have specification-level answers: staged exclusion/envelope; minimum dependency horizons; regional bootstrap; bounded application uncertainty; physical properties/entities/observation inventory; sustained fairness; safe shard reclamation; global controls; numerical profile; global memory; and certified eye provenance. RT1–RT7 are covered explicitly. H1/H4/H7/H10/H12/H13/H14 have narrowed or alternative remedies explained in their dispositions.

## HIGH PRIORITY FINDINGS REMAINING

Empirical qualification remains pending, notably H1/H2/H5 solver usefulness/containment, H4 world-model measurements, H7 execution-side pushing, H12 faithful cross-host numerical results, H13 real retained memory and H14 joint eye/movement witnesses. H8 packet inventory and H9–H11 controls must be implemented/tested. These are scheduled gates, not unresolved architecture defects or claimed passing tests. If later evidence invalidates a contract or floor, reopen the decision rather than weaken completeness.

## MEDIUM/LOW FINDINGS

All M1–M13 and L1–L6 have dispositions, including M5's unmeasured durability gate and L1's deferred exact API-version pin. No Java/build/ignore change was made to satisfy a future-phase criterion prematurely. All RT findings and unnumbered notes are tracked separately. No finding was silently dismissed.

## MODULE DAG CHANGES

Final 20 modules: original 17 plus model, replay-schema and interaction; 19 production nodes and test-only testing. [ARCHITECTURE](ARCHITECTURE.md) contains the exhaustive direct dependency graph and exact-name summary diagram. No production dependency on testing, no engine dependency on public api, no replay dependency on implementations.

| Required behavior | Legal direct path |
|---|---|
| Build physics/collision | platform-paper -> core -> physics/collision constructors |
| Safe anchor clearance | core constructs mitigation validator -> world/collision; passes model SafeAnchorValidationPort to platform-paper |
| Placement/action semantics | checks -> interaction -> world/collision; core applies returned overlay proposal through world |
| Record/restore/diff domain state | owning module -> replay-schema DTO; core -> replay; core -> owner restore; replay codec/diff -> replay-schema only |
| Public integration | platform-paper/core -> api; core maps to model messages; engines do not import api |
| Configuration/replay policy | core owns schema and model snapshot, maps to replay-schema ConfigDto |
| Native harness/offline verification | testing test configurations -> declared production modules; no reverse production edge |

The model contains immutable shared primitives and narrow service ports, not a parallel implementation dump. SimulationResult, EvidenceRecord, WorldView, TimelineSlice and MitigationIntent retain named local owners. Each mapper has explicit schema dependency. This addresses implementability at the design level; compile checks belong to Phase 1.

## PACKET COMMIT MODEL

P2 separates immutable journal progress from certification. For an inbound attempt A, let C be the existing certified frontier. Capture(A) stores a claim/provisional transition and leaves C unchanged. Only a correlated APPLIED outcome plus resolved predecessors and reconciled knowledge can promote. REJECTED(A) discards A's provisional effects and invalidates dependent speculation; UNKNOWN/timeout is likewise ineligible. Thus cancellation after observation cannot advance C via A. TRANSFORMED_APPLIED recomputes from final contents and reconciles the distinct client claim; it never promotes the original payload by identity alone.

Later outcome records can be consumed while certification waits, avoiding an outcome-queue deadlock. Normal callback return/periodic handler checks are insufficient to establish server application. Uncovered late cancellation/mutation yields UNCERTAIN_PIPELINE_OUTCOME. This is an architectural invariant/proof by allowed transitions, not a demonstrated Paper hook.

## SIMULATION SOLVER CHANGES

The solver first seeks cheap concrete witnesses, then propagates sound outer reachability, applies inverse constraints only over valid operation domains, refines discrete cases and resolves precise collision where useful. Unknown contact/force domains widen or invalidate coverage. NO CONCRETE CANDIDATE FOUND is not INVALID. SEARCH BUDGET EXHAUSTED is not INVALID. Only a covering disjointness proof or exhaustive sound elimination of the entire admissible domain can exclude.

The conditional pilot branch accounting avoids claiming every modern mode is solved: known mode, two contact/two jump outer cases, interval acceleration/application set; detailed target counts and proofs remain Phase 5/6 gates. Budget limits stay unchanged. Numeric adversarial coverage thresholds ensure zero false positives cannot mask an inert all-UNCERTAIN validator.

## CONSERVATIVE ENVELOPE

M3 defines a tagged union of outward-rounded state intervals and a causal application automaton. Concrete successors must be contained by abstract successors. Merge uses union/hull without losing discrete obligations. Unknown topology cannot silently become free motion. Envelope overlap is not a witness; only complete disjointness can exclude. Phase 5 owns operator proofs and Phase 6 owns solver/merge/refinement, with independent review plus regression containment tests.

## EXTERNAL FORCE MODEL

ExternalMotionInfluence distinguishes delta/replace velocity, mode acceleration, contact push, kinematic displacement and declared unknown motion. Explosion/TNT/creeper/end-crystal motion is captured independently of ordinary velocity packets; projectiles/fishing/plugin velocity use the captured resultant effect rather than a second guessed impulse. Firework/riptide and piston/border behavior have dedicated mode/temporal paths. Missing source/side/operation information blocks completeness.

## ENTITY PUSHING

World supplies a covered relevant spatial neighborhood, bounded to 32 active influences and 2048 operations per step. Physics qualifies predicted versus server-only contributions and deduplicates resultant packets. Boats/minecarts/mobs/players and applicable solid types participate through version rules. Missing/truncated entity coverage is uncertain, not proof of empty space. Qualification includes ordinary and overflowing crowds.

## BLOCK PHYSICAL PROPERTIES

BlockBehaviorQuery is separate from ShapeQuery and supplies friction, speed/jump response, bounce, inside drag, fluid flow/columns, climb/support and special forces with coverage. World resolves coherent samples, version defines rules, physics applies them. Inside sampling has its own 512-cell step cap and shares total work accounting. Property fixtures are distinct from shape fixtures.

## WORLD COVERAGE RECOVERY

Ordering Resume changes ordering health only. WorldCoverage, EntityCoverage, PipelineOutcomeCoverage and ApplicationCoverage remain independent. Mid-session and lost regions use lazy captured baselines; a server-current snapshot never fabricates observer visibility or overwrites fake blocks. Some regions may remain unknown until a new baseline/reconnect. Distance 10/16 representation comparisons are explicitly estimated arithmetic with future benchmarks, not measured performance.

## NUMERICAL DETERMINISM

NUM1 prioritizes vanilla fidelity for concrete stepping, sound outward containment for exclusion and exact verified reproducibility for compatible replay. NumericOps owns faithful profile-specific algorithms/tables; StrictMath may serve specified deterministic diagnostic functions but is not a universal client replacement. Runtime metadata/table hashes/conformance vectors distinguish environments. Bit-identical cross-host replay is a qualification requirement, not an achievement. Incompatible hosts refuse verified replay; recorded intermediates are diagnostic/playback only.

## REPLAY MEMORY MODEL

Global admission prevents normal B from allocating 16 MiB for every session. The reference replay partition is 96 MiB total (48 rolling, 32 promotion, 16 I/O), with adaptive lazy shares and globally charged pins/closures. Session tiers and shared partitions satisfy B >= (256 MiB + N*tierMiB)/0.8. At 1000 players the estimated qualification budget is 5320 MiB, not the 1 GiB default; this still does not prove sufficient world coverage or CPU.

Rolling data yields to admitted captures; oversize closures/promotions are refused explicitly. DURABLE_REQUIRED remains the initial effect policy. Slow/failing storage skips effects and records availability loss. Shutdown stops admission and releases only leases no longer used. Hung-worker memory remains charged, never freed unsafely. No load/heap/persistence measurements have been fabricated.

## ROADMAP CHANGES

Existing numbered phases are retained. Phase 1 owns module/schema/config/quota/boundary/outcome foundation, capture harness smoke lane and runtime health controls without detections. Phase 2 owns certification/coverage timelines and integration declarations. Phase 3 owns region baselines, relevant entities and world/property lookup. Phase 4 owns geometry/sample contracts; 5 concrete/abstract physics, external forces, pushes, physical properties and numeric fidelity; 6 constraint solver; 7b bounded application qualification; 9 projections/correlation/capsule measurements; 10 qualified effects; 18 interaction semantics. Detailed table: [ROADMAP](ROADMAP.md).

## TESTING CHANGES

TC01–TC25 cover every requested scenario plus shard death/hang, config semantics, global control, root correlation, eye provenance and capsule feasibility. [TESTING](TESTING.md) states delivery phases and concrete coverage floors. Static checks in this remediation check documents and invariants; no runtime test exists yet, and none is reported passed.

## REMAINING RISKS

1. Outcome instrumentation may keep real-world integration capture-only; no guarantee a generic plugin library supplies it.
2. Covering envelopes may be too loose near discontinuities or under many pending effects; prove usefulness without unsound pruning.
3. Regional observer coverage can remain unavailable after mid-session activation/eviction, and shared-world diversity may require more memory than the reference pool.
4. Numeric fidelity, entity execution-side semantics and target version changes require real native captures.
5. Durable closure may miss the effect deadline; effects remain disabled if the measurement gate fails.
6. Global resource/guard thresholds and estimated memory floors need stress validation; they are not production capacity claims.
7. Review 2, human approval and a real baseline commit are outstanding. Phase 1 remains blocked now.

## REVIEW #2 CHECKLIST

- [ ] Verify the [Review 2 content manifest](review-artifacts/review2-manifest.json), current bytes and [change comparison](review-artifacts/review2-changes.diff).
- [ ] Confirm Review 1 hash and original skeleton hashes; archive is comparative evidence, not current normative docs.
- [ ] Evaluate all 55 dispositions, including 13 partial accepts and unfulfilled empirical acceptance criteria.
- [ ] Trace all legal construction/clearance/placement/projection paths and public exclusions.
- [ ] Challenge outcome-pending promotion and transient-handler fallback.
- [ ] Review covering-set exclusion/containment and the narrow pilot domain; no witness/exhaustion shortcut.
- [ ] Verify force/property/entity completeness and independent coverage recovery.
- [ ] Review numerical qualification, memory accounting, durability and runtime controls.
- [ ] Review phase ownership and distinguish synthetic Phase 1 tests from later native physics.
- [ ] Record APPROVE or REQUEST CHANGES against the exact manifest; human approval and baseline commit remain separate.

## SELF-REVIEW AND STATIC VALIDATION

The 17 requested checks are mapped as follows: DAG/path implementability and composition (Architecture table/traces); public/internal boundary (A3); downstream truth (Packet P2); exhaustion/no-witness safety and envelope scheduling (M2/M3/Roadmap); explosions/external forces (State S2); entity push (World W3); physical properties (World W3/Collision); independent coverage (World W2/Threading); global replay (Performance F2/Replay R2); numerical policy (NUM1); placement owner (ACT1); schema projection (R2); realistic Phase 1 criteria (review gates); no implementation (Git/source hash inspection).

Static validation completed on the revised working tree:

- PASS: all 49 numbered Review #1 findings and six supplementary notes have exactly one disposition with all seven required fields.
- PASS: the exhaustive 20-module adjacency table is topologically ordered, with no unknown provider or cycle; required composition, clearance, interaction and local replay-projection edges exist. The public API, internal model and archival schema are dependency leaves. Manual contract tracing is documented above; compilation remains a Phase 1 check.
- PASS: current local Markdown file links resolve and fenced blocks balance.
- PASS: Review #1 and the four original skeleton files match their recorded full SHA-256 hashes. All 22 archived inputs match Review #1's recorded hash prefixes, and all 22 current specifications differ from their archived originals.
- PASS: global memory partitions, admission tables and timeline/entity retention arithmetic reconcile. This verifies arithmetic, not real heap usage or capacity.
- PASS: `git diff --check` and `git diff --cached --check`; the tracked working-tree diff is empty and the index still contains only the four original additions. `git rev-parse --verify HEAD` fails as expected for the unborn repository. No Java, plugin descriptor, build or ignore file was changed.
- REVIEWED: cancellation/transformation prefix transitions, unknown coverage propagation, full-domain exclusion conditions and the legal behavior paths described in this report. These are architectural arguments, not executed runtime proofs.

The reproducible [static validator](review-artifacts/validate_phase0.py) and [package generator](review-artifacts/build_review_package.py) are review utilities only, not product implementation or runtime tests. The [change artifact](review-artifacts/review2-changes.diff) compares all current specifications with the preserved originals and includes new specifications. Git's ordinary unstaged diff cannot display these untracked documents. The [manifest](review-artifacts/review2-manifest.json) binds the 31 current review files, including the unchanged skeleton and Review #1; its declared exclusions avoid recursive hashing. Re-run the generator only before approval, then verify all entries against raw bytes.

No runtime, native-client, numerical, heap, durability, load or Java compilation test was performed. Independent senior approval, human approval and a verified baseline commit remain outstanding. This self-review cannot confer independent senior approval.

