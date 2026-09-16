# Phase 0 senior architecture review #2

| Field | Value |
|---|---|
| Reviewer | Claude (independent senior architect / senior code reviewer) |
| Implementer | GPT 6 Astra |
| Phase | 0 — Architecture remediation following Review #1 (REQUEST CHANGES) |
| Review date | 2026-09-15 |
| Reviewed surface | Working tree, bound to the content manifest `docs/review-artifacts/review2-manifest.json`, composite SHA-256 `1a1b987df59ef9fd411528296c2150f2b4fbbb3417ec36cddd286f1c65f64155` (31 files). `master` still has no commits, by the approved sequence. |
| Review #1 | `docs/PHASE_0_SENIOR_REVIEW.md` — **preserved, verified below** |
| Independent verification performed | Recomputed all 31 manifest hashes against raw bytes; recomputed the composite digest; recomputed the composite of the archived `review1-input` snapshot; regenerated the change diff from scratch and compared byte-for-byte; re-parsed the 20-module adjacency table and ran my own DFS cycle detection and reachability closure; re-verified every arithmetic claim in PERFORMANCE and MOVEMENT_SIMULATION; swept all specifications for stale pre-remediation text; link/fence checked every document; re-inspected the Intave pinned revision for the M13/L4 claims; confirmed git index and worktree state. |
| Automated artifacts | None from the project — there is still no implementation. All checks above are my own; I did not rely on `validate_phase0.py` for any conclusion. |

---

## EXECUTIVE SUMMARY

The remediation is substantive, honest, and in several places technically better than what Review #1 asked for. All four blockers are closed. All fourteen HIGH findings have real specification answers, six of them by a remedy stronger than the one I proposed. I found no new blocker and no new high finding.

**Preservation confirmed.** `PHASE_0_SENIOR_REVIEW.md` is 811 lines / 139,108 bytes — an exact match for what I recorded at the close of Review #1 — with both of my post-write edits present, both pre-edit strings absent, all 30 section headings, all 31 finding IDs in order, and the verdict line intact. Independently, the archived `review1-input/` snapshot hashes to `40aabc61f7e8b01f5cd2d709c234ced1687b2dbb8329c208c68574c11bd1da58`, which is exactly the composite I computed myself during Review #1. The diff baseline is therefore genuine, not reconstructed. I also regenerated `review2-changes.diff` from scratch: 221,770 bytes, byte-identical to the supplied artifact. The change set is 4 new documents, 22 revised, and the four skeleton files untouched.

**The three structural problems from Review #1 are gone.**

The DAG is now implementable, not merely acyclic. I re-parsed the table and ran my own cycle detection: 20 modules, no cycles, row order topological. All four previously impossible paths now have direct declared edges — `core → collision`/`physics` for composition, `mitigation → world`/`collision` for setback clearance behind a `model` port, `checks → interaction` for placement, and every state owner → `replay-schema` with `replay` depending on nothing else. The explicit "Dependencies use Gradle `implementation`… No transitive visibility is assumed" sentence closes the exact loophole I raised. The public surface went the opposite direction from what I feared: `api` is now six named types with zero dependencies, engines are structurally forbidden from importing it, and there is an explicit exclusion list plus a new-type rule that replaces the useless two-consumer heuristic. `model` did not become the dump — it is bounded by an enumerated category table and an explicit prohibition on absorbing algorithms, codecs or engine results.

The budget/guarantee contradictions are resolved with arithmetic I checked line by line. The old 16 MiB/session reservation is removed and replaced by global-first admission with a stated floor (2 MiB) and target (4 MiB) tier, a published scale equation `B >= (256 + N*tier)/0.8`, and a table I recomputed for all ten cells — every value correct. Retention now derives from one 3-second target at a declared 1000 records/s envelope, with markers demoted to diagnostics that explicitly cannot extend coverage; the 2.048-second shortfall at 2000 records/s is published rather than hidden. The solver budget now matches a stated pilot domain: ≤4 outer branches/step, 85 nodes through three steps, inside the unchanged 256/4096 limits.

The missing mechanics are specified. `BlockBehaviorQuery` is a separate contract from `ShapeQuery` and covers friction, speed/jump factor, bounce, inside drag, fluid flow/columns, climb/support and special forces, with a 512-cell inside-sampling cap and a no-default rule. `NearbyMotionEntities` bounds entity push to 32 entities and 2048 operations over a swept envelope, with `UNCERTAIN_ENTITY_NEIGHBORHOOD` on overflow. The observation matrix now enumerates explosion motion, vehicle move/steer/passengers, camera, respawn/game-event/health, tick-rate/freeze/step, held-item, swing and client tick-end, with `MissingObservationFamily` disabling dependent capability at startup.

**Three remediation answers are better than my recommendations, and I am recording that explicitly.** On packet truth, Astra observed that a periodic topology audit cannot prove a handler was not inserted and removed between checks, and therefore refuses to certify on periodic checks alone — a hole my own B3 fix would have left open. On numerics, Astra rejected my StrictMath fallback with "reproducibility of the wrong arithmetic is not vanilla fidelity" and ordered the priorities correctly: fidelity governs concrete stepping, outward containment governs exclusion, exact reproducibility governs verified replay. On shard failure, Astra distinguished a terminated writer from a hung one and refuses to free memory under a live thread — my Review #1 wording would have invited a use-after-free.

**Astra also corrected two overreaches of mine, correctly.** My 144-input figure was a nominal upper bound treating jump/sprint/sneak/use as independent, not a verified target-profile domain, and my "inversion is O(1)-ish" claim does not survive clamps, normalization, bounce and collision clipping. The revised solver inverts only over provably affine sub-domains and uses interval preimages elsewhere. Separately, my blanket assertion that entity push "is client-predicted" is not safe across versions and mechanisms; the spec now qualifies server-only, predicted and mixed contributions and deduplicates captured resultants.

**What remains open is correctly gated, not hidden.** The largest residual risk is a product risk the remediation states plainly: if no correlated authoritative outcome provider can be qualified at the Paper dispatch boundary, the adapter ships capture-only and can never certify — therefore never enforce. That is disclosed in P1, in the Phase 1 criteria, and at the top of the remediation's own risk table. It is the right disclosure and the right gate.

I raise one NEW MEDIUM (the source of replay capsule prehistory is ambiguous, and its scaling interacts with the durability gate) and one NEW LOW (one port payload type has no named declaration owner). Neither blocks Phase 1.

**Verdict: APPROVE.** B1–B4 resolved; no unresolved HIGH prevents Phase 1; no new blocker; the architecture is internally implementable; the packet truth model is sound at specification level; incomplete simulation cannot certify INVALID on any path I could find. Phase 1 may begin **after** human approval and the baseline commit — not now.

---

## REVIEW SCOPE

I read `PHASE_0_SENIOR_REVIEW.md` and `PHASE_0_REMEDIATION.md` in full, then every document the remediation touched: all 22 revised specifications and the four new files (`CONFIGURATION.md`, `INTERACTION_MODEL.md`, `NUMERICAL_POLICY.md`, `PHASE_0_REMEDIATION.md`). I inspected `review-artifacts/` including both utility scripts, and re-derived every structural claim myself rather than executing Astra's validator.

I did not re-open settled Review #1 decisions, and I did not propose alternative architectures where the remediation's answer is sound. Where Astra's remedy differs from my recommendation and is technically better, I record it as SUPERSEDED and adopt theirs.

Out of scope, unchanged from Review #1: no implementation exists, so no runtime, native-client, numeric, heap, durability, load or compilation evidence was requested or accepted. I verified that gates exist and are measurable later.

---

## BLOCKER VERIFICATION

### B1

- **ID:** B1 — module dependency graph could not express four documented behaviours
- **ORIGINAL PROBLEM:** `core` was the composition/DI root but could not depend on `collision`/`physics`; `mitigation`/`platform-paper` had to validate setback collision clearance with no path to the collision engine; placement/interaction ray validation had no owning module; `replay` had to encode, decode and diff player/world/evidence/mitigation state while depending on none of them.
- **ORIGINAL ACCEPTANCE CRITERIA:** For each of the four items the table shows the edge, or names the port and its implementing module; a reviewer can trace all four end-to-end through declared edges only, with no step requiring an undeclared import.
- **ASTRA REMEDIATION:** ACCEPT. Added `model`, `replay-schema`, `interaction` (20 modules, 19 production). `core` gains direct edges to every implementation it constructs. `mitigation` gains `world` and `collision` and implements a `model` `SafeAnchorValidationPort`. `checks` gains `interaction`. Each state owner gains `replay-schema` and a local `ReplayMapper`; `replay` depends on `replay-schema` alone.
- **INDEPENDENT VERIFICATION:** I re-parsed the adjacency table and ran my own DFS three-colour cycle detection independent of the declared row order: 20 modules, **zero cycles**, and the row order is itself a valid topological order. Direct-edge assertions I confirmed programmatically: `core→collision` ✓, `core→physics` ✓, `core→interaction` ✓, `core→replay` ✓, `mitigation→collision` ✓, `mitigation→world` ✓, `checks→interaction` ✓, `replay` deps == `[replay-schema]` ✓. Reachability closure of `replay` is exactly `{replay-schema}` — it cannot reach any implementation module even transitively.

  Path 1 (composition): `platform-paper → core → collision`/`physics` constructors. ARCHITECTURE now states "Dependencies use Gradle `implementation`; signatures exposed across module boundaries require the consumer's explicit direct dependencies. No transitive visibility is assumed. Reflection/service locators cannot bypass the adjacency list." That is the precise sentence the original defect needed.

  Path 2 (clearance): confirmed in three places. ARCHITECTURE: "core → mitigation's SafeAnchorValidator → collision, injected as a model port into the platform executor… Destination clearance uses a bounded immutable server-current view constructed on the main thread, then the same pure collision engine, not copied Bukkit geometry rules." MITIGATION restates it; COLLISION_ENGINE adds the enforcement side — "SafeAnchorValidator in mitigation calls this same pure engine against a bounded server-current destination view captured on the main thread; checks cannot call it or reimplement clearance." `platform-paper` deliberately has no `collision` edge, so the port is the only route. ✓

  Path 3 (interaction): `checks → interaction → world`/`collision`; `core` applies the returned `ProposedOverlayDelta` through `world`. No `interaction ↔ combat` edge exists; both reuse collision's `RayQuery`, and both documents explicitly forbid duplicated geometry. ✓

  Path 4 (replay projection): domain state → owner's `ReplayMapper` → `replay-schema` DTO → `replay` codec, with restore dispatched by `core` back to the owners via `ReplayDriverPort`. ✓

  Regressions I checked for and did not find: no cycles; no public-API pollution (no engine module imports `api` — verified by closure, the set of engines with `api` in their transitive reach is empty); no platform leakage (only `platform-paper` may import Bukkit/Paper/NMS/Netty, and TESTING lists a CI check for forbidden platform imports); no production→testing edge. The new implementation-to-implementation edges are `mitigation→world`/`collision` and `checks→interaction`, both of which exist precisely to force shared-engine reuse rather than defeat it. `checks` still has no path to `world`, `collision`, `physics`, `mitigation` or `platform-paper` — the structural guarantee that a check cannot grow its own physics survives intact.
- **STATUS:** **VERIFIED RESOLVED**
- **REMAINING ACTION:** None. Compile-time enforcement is correctly scheduled as a Phase 1 CI check.

### B2

- **ID:** B2 — `api` contents undefined; replay forced the domain model into it
- **ORIGINAL PROBLEM:** `api` was governed only by "at least two legitimate consumers", which constrains nothing; replay's requirements forced the whole cross-cutting domain model into it.
- **ORIGINAL ACCEPTANCE CRITERIA:** An enumerated content policy with categories and at least three named exclusions; a DECISION on how replay obtains and compares domain state; and a documents-only answer to "which module declares `EvidenceRecord`, `TimelineSlice`, `WorldView`, `SimulationResult`, `MitigationIntent`, `ChunkSectionSnapshot`, `SafeAnchor`".
- **ASTRA REMEDIATION:** ACCEPT. Decision A3 splits four surfaces: public `api` (six named integration types), internal `model`, archival `replay-schema`, module-local implementation. New-type rule replaces the heuristic. Explicit exclusion list. Separate versioning promises per surface.
- **INDEPENDENT VERIFICATION:** The four-way separation the prompt asks about is real and structurally enforced, not just described. `api` = `PapeaService`, `PublicPlayerId`, `CoverageSummary`, `EvidenceSummary`, `ExpiringExemptionRequest`, `DeclaredEffectNotice`; it depends on nothing and "never depends on model or replay-schema"; only `core` and `platform-paper` may depend on it. I confirmed by closure that no engine can reach `api`.

  All seven of my named types have unambiguous owners in the A3 category table: `EvidenceRecord` → evidence-local; `TimelineSlice` → player-local read projection; `WorldView` and `ChunkSectionSnapshot` → world; `SimulationResult` (and Input/Candidate/Error/Uncertainty) → simulation-local; `MitigationIntent` → mitigation-local; `SafeAnchor` → model. Explicit `api` exclusions are listed: `SimulationResult`, `EvidenceRecord`, `MitigationIntent`, `WorldView`, `TimelineSlice`, internal packet events, replay records — seven, more than the three I asked for.

  The new-type rule is decidable without the old heuristic: supported external operation → `api`; archival representation → `replay-schema`; internal primitive/immutable value or documented cross-boundary port → `model`; engine-specific result/query/mutable state → owning module; "Two consumers alone never qualify." I swept every specification and confirmed the two-consumer rule is gone entirely.

  My concern that `model` would inherit the god-module problem does not materialise. Its scope is bounded by the category table (identities, geometry values, `SafeAnchor`, coverage summaries, `RevisionId`, `SafeAnchorValidationPort`/`ClearanceResult`, `PlatformEffectRequest`) plus an explicit prohibition: "Model cannot absorb algorithms, codecs, framework types or every engine result."

  The replay decision is option (i) from my recommendation — a separate schema module with per-owner mappers — which is the right choice given that archival and in-memory representations evolve at different rates. R2 states the pipeline and the round-trip obligation, and TESTING TC19 covers export/restore/export digest equality across changed runtime IDs.
- **STATUS:** **VERIFIED RESOLVED**
- **REMAINING ACTION:** None blocking. See NEW-L1 for one residual type-owner gap and a drift note on the "cross-boundary port" branch of the rule.

### B3

- **ID:** B3 — no hook-placement invariant; third-party inbound cancellation unhandled
- **ORIGINAL PROBLEM:** No stated observation boundary, no detection of runtime pipeline mutation, and no rule for another plugin cancelling or rewriting an inbound packet after Papea2AC committed it — the one path in the design that corrupts truth rather than reducing coverage.
- **ORIGINAL ACCEPTANCE CRITERIA:** Exact boundary stated per direction; a Phase 1 test proving a foreign handler inside the boundary triggers the integrity check and drops the right capability; a Phase 1 test cancelling an inbound movement proving Papea2AC either observes the cancellation or marks the span uncovered, and in no case emits `DeterministicExclusion`.
- **ASTRA REMEDIATION:** ACCEPT. Decision P1 splits network observation from server-dispatch outcome; Decision P2 introduces journal-first, certify-after-outcome. Hook placement and `AdapterBoundaryProbe` are specified. TC01/TC02 added.
- **INDEPENDENT VERIFICATION:** I traced the four conceptual tests the review brief demands.

  *Test 1 — downstream cancellation.* Lifecycle is `CAPTURED → PREPROCESS_OBSERVED → OUTCOME_PENDING → APPLIED / TRANSFORMED_APPLIED / REJECTED / FAILED / UNKNOWN`. The state model keeps `processedCaptureSeq` separate from `certifiedIngressOrdinal`; certification "walks the resolved dependency prefix". `REJECTED` "discards its speculative effects and invalidates descendants that depended on them; the claim remains diagnostic". PLAYER_STATE's `MovementState` now carries "Client claim, provisional path, accepted server projection and certified supported set **separately**" with "no anchor promotion without outcome". EVIDENCE_MODEL adds a second gate: "Provisional or rejected inbound transitions cannot generate actionable deterministic evidence, regardless of solver completeness." P cannot advance the certified frontier. ✓

  Critically, the follow-on hole is closed too: "After rejection/transformation, resume certification only with a correlated server baseline and reconciled client frontier, **not by skipping the record and trusting its successor**." That is the failure I would have looked for first.

  *Test 2 — mutation.* `TRANSFORMED_APPLIED` "recomputes from the final payload, records original and final digests and does not equate modified server coordinates with the client's original prediction". Authority is explicitly assigned: the final accepted payload drives the server projection; the original observation is retained as the distinct client claim; neither is promoted by object identity. Beyond my ask, one-to-many replacement is handled — "Exact mutable object identity is insufficient for one-to-many replacements: provider supplies explicit replacement children and causal IDs or marks UNKNOWN." ✓

  *Test 3 — unobservable outcome / timeout.* "FAILED/UNKNOWN, mismatched IDs, contradictory late outcomes and uncertified predecessors force `UNCERTAIN_PIPELINE_OUTCOME` and dependent resynchronization." Journal bound is 256 attempts / 256 KiB / 500 ms, and "overflow/expiry **discards provisional certification eligibility** for the span" — expiry makes the attempt ineligible, it never promotes. "Late outcomes never resurrect expired evidence." The brief's specific worry — timeout silently becoming certification — is explicitly refuted. ✓

  *Ordering, memory, lifecycle.* Outcomes re-enter the same sequencer and receipt order is never changed; "Waiting for an outcome never blocks consuming the outcome itself" and "Pending server outcomes do not block journal consumption; they block only dependent frontier promotion" — the deadlock I would have expected from a naive outcome-wait is explicitly avoided. Unresolved provisional entries are bounded by count, bytes and time. On disconnect the session is tombstoned, pending intents cancelled by epoch and references released; late callbacks with old epochs are "ignored but counted"; on disable, admission stops first and incomplete captures are abandoned with a marker. A provisional entry therefore dies with its session and can never certify afterwards.

  *Hook placement.* Both boundaries are now named — inbound "immediately before the configured server packet-dispatch handler after decoding and covered library transforms", outbound "immediately on the packet-object input side of the encoder after all declared transforms" — with the honest qualifier "These are final only relative to the named, verified boundaries." `AdapterBoundaryProbe` checks codec/dispatch identities, handler order/classes, classloader signatures, protocol state, transformer versions and mapping fingerprints, at install, at every protocol transition, before each relevant callback, plus a one-second idle audit, with `ForeignHandlerInsideBoundary` as the named reason.

  **This is where the remediation exceeds my finding.** My B3 fix would have accepted periodic integrity checking as sufficient. Astra does not: "A periodic snapshot cannot prove that a handler was not inserted and removed between checks; only a mediated integrity/outcome provider covering that span permits certification. Any unobservable pipeline change leaves outcome coverage UNKNOWN regardless of a later matching fingerprint. No initial profile may advertise certification based on periodic checks alone." That closes a transient-handler hole I left open.

  The Phase 1 demonstration list now includes foreign handlers in both segments, inbound cancellation showing no certified-frontier or anchor advance, mutation/replacement reconciliation, and insertion-plus-removal between audits. TC01/TC02 carry the assertions. Phase 1 criterion 3 states the consequence plainly: if no authoritative outcome provider can be qualified, ship capture-only OBSERVE_ONLY with no certification.
- **STATUS:** **SUPERSEDED BY A STRONGER DESIGN**
- **REMAINING ACTION:** None for architecture. The residual is a disclosed implementation gate: qualify an outcome provider or remain observation-only.

### B4

- **ID:** B4 — review could not be bound to a revision; `master` has no commits
- **ORIGINAL PROBLEM:** Both the AI rules and Phase 1 criterion 1 required approval bound to a fixed revision, which was unsatisfiable in an unborn repository.
- **ORIGINAL ACCEPTANCE CRITERIA:** `git rev-parse HEAD` returns a commit containing the reviewed documents; the review record cites it; the second review verifies fixes against a diff.
- **ASTRA REMEDIATION:** PARTIALLY ACCEPT. Decision D2 substitutes a content manifest plus an archived input snapshot for the pre-approval commit, and moves the real commit after senior and human approval. Astra states explicitly that my literal criterion is deliberately not satisfied and fabricates no commit.
- **INDEPENDENT VERIFICATION:** The counter-argument is correct on both points: a SHA-256 manifest binds exact bytes without needing history, and committing the user's pre-staged files before human approval would be a worse outcome than the problem it solves. The purpose of B4 — approval binds to exact content, and later edits are detectable — is achieved.

  The sequence in D2 matches the brief's expected conceptual sequence: working-tree architecture complete → manifest → Review #2 verifies bytes and diff → APPROVE against that digest → human approval → stage only approved files → baseline commit → verify committed blobs equal approved bytes → record the actual revision in a separate receipt → Phase 1 gate opens. The separate receipt is necessary and correctly reasoned: "a commit cannot contain its own final SHA". D2 also closes the drift path: "Any changed specification byte after APPROVE requires a fresh manifest and review of that change."

  I verified the mechanism works rather than taking it on trust: all 31 manifest entries match raw bytes and sizes; the declared composite reproduces exactly; every normative Markdown file is covered with none omitted; Review #1 is included in the manifest rather than excluded; the declared exclusions (the artifacts directory itself, future reviews, the baseline receipt) are non-normative and avoid recursive hashing. `git rev-parse --verify HEAD` fails as claimed, the staged set is still exactly the four original skeleton files, and the unstaged tracked diff is empty.

  Phase 1 criterion 1 is rewritten to be satisfiable and now says "No HEAD exists yet; a manifest is not a commit." My grep confirms the impossible phrasing is gone from every specification.
- **STATUS:** **SUPERSEDED BY A STRONGER DESIGN** — I accept the substitution and withdraw the literal criterion.
- **REMAINING ACTION:** Human approval, then the baseline commit, then blob verification against this manifest and the receipt. Those are gates, not review findings.

---

## HIGH FINDING VERIFICATION

### H1

- **ID:** H1 — stated simulation budget made a complete INVALID arithmetically unreachable
- **ORIGINAL PROBLEM:** INVALID required full input-domain, application-branch and step-range coverage, but the budget (256 candidates, 4096 candidate-step evaluations, 3 steps) was smaller than one step of the nominal input domain crossed with pending-update branches, so the system would converge to permanent UNCERTAIN while reporting zero false positives as success.
- **ORIGINAL ACCEPTANCE CRITERIA:** A DECISION naming the exclusion algorithm class with per-step enumerated branch counts and justification; a Phase 6 test showing a provably impossible movement returns INVALID with all flags true inside budget; a saturation metric; and a Phase 8 gate requiring a non-trivial INVALID rate on adversarial fixtures, not just a low false-positive rate.
- **ASTRA REMEDIATION:** PARTIALLY ACCEPT. Decision M2 replaces flat enumeration with a five-stage staged solver: witness lane, sound interval reachability, inverse constraints over valid operation domains, branch-and-bound discrete refinement, exact collision resolution. Budgets unchanged. Pilot domain and branch accounting published. My 144 figure and my O(1) inversion claim are both challenged.
- **INDEPENDENT VERIFICATION:** Both challenges are correct and I accept them. 144 was `9 × 2^4` treating jump/sprint/sneak/use as independent; in the real protocol these are constrained and partly observable, so it is a nominal upper bound, not a verified target-profile domain. And inversion is genuinely not constant-time through clamp, normalization, bounce and collision clipping. M2's formulation — invert only where "free-flight/known-contact equations are affine for a fixed mode", and use interval preimages over all cases otherwise, with "Dividing by an interval containing zero must not remove solutions" — is the technically correct statement of what I was reaching for.

  The budget contradiction is now resolved by making the exclusion domain explicit rather than by raising caps. For the declared pilot (verified one-step walking/free-flight/known flat contact, known mode/rotation/effects, complete neighborhood, bounded force domain): mode=1, contact ≤2, jump ≤2, acceleration/impulse interval case=1 → ≤4 outer transfer branches per step; three steps → 1+4+16+64 = 85 outer nodes. I checked that arithmetic; it is right, and 85 fits comfortably inside the unchanged 256-node / 4096-evaluation limits. Outside the pilot, "the solver has no fixed worst-case success guarantee; return UNCERTAIN when the capped covering forest still intersects O."

  I then audited every path to INVALID across the whole corpus. The exclusion condition is stated once, precisely: "INVALID is permitted only if the covering forest has **no remaining feasible or unexamined node**, or a covering outer envelope for the entire remaining domain is disjoint from O. Resource exhaustion with any intersecting/uncovered node yields `UNCERTAIN_SEARCH_INCOMPLETE`. An empty container caused by dropping nodes is not an empty feasible set." Unsplit cells are retained as conservative supersets, so they count as unexamined. Each eliminated node carries a checkable cause and its parent coverage. The brief's five-way mapping holds without exception: witness → VALID; sound exclusion of the complete admissible domain → INVALID; budget exhaustion → UNCERTAIN; no candidate after incomplete search → UNCERTAIN; unknown relevant physical input → UNCERTAIN. I could not construct a path where budget exhaustion, candidate truncation, branch pruning, coverage loss or an unknown external force reaches INVALID.

  The safety posture is stronger than I asked for: "until then INVALID is not enabled even for the pilot" — no certified exclusion may be emitted at all before Phase 5/6 publishes target-profile case counts and proves each transfer.

  My acceptance criteria are met: algorithm class declared with per-step branch counts ✓; TC12 requires "outside full envelope returns INVALID inside cap" plus an in-envelope impossible point requiring refinement ✓; saturation is a reported metric and `UNCERTAIN_SEARCH_INCOMPLETE` is a named reason ✓; and the Phase 8 gate is now numeric — "zero false INVALID/action on benign corpus, >=95% VALID on each supported benign scenario class; >=80% INVALID on each predeclared supported adversarial class where independent ground truth proves impossibility", with "Unsupported classes cannot be removed from product coverage reporting to improve the score." That last clause closes the gaming path I was worried about.
- **STATUS:** **SUPERSEDED BY A STRONGER DESIGN** (mechanism replaced; empirical usefulness correctly gated to Phase 5/6/8)
- **REMAINING ACTION:** None for Phase 1. Phase 5/6 must publish actual target-profile case counts and per-operator transfer proofs before any exclusion profile is enabled — already a stated gate.

### H2

- **ID:** H2 — "sound conservative envelope" named but never defined
- **ORIGINAL PROBLEM:** Three load-bearing guarantees delegated to an abstraction with no representation, no soundness obligation and no roadmap owner.
- **ORIGINAL ACCEPTANCE CRITERIA:** A DECISION defining the abstraction and stating containment as a checkable obligation; a property test asserting the envelope's step contains the set of stepped concrete states; a test asserting no envelope-only match produces a `LegitimateWitness`.
- **ASTRA REMEDIATION:** ACCEPT. Decision M3 defines `AbstractState` as a tagged union of outward-rounded boxes plus discrete sets and an application automaton, with an explicit gamma containment obligation.
- **INDEPENDENT VERIFICATION:** Every element the brief enumerates is present. *Represents:* finite union of tagged boxes — outward-rounded position/velocity intervals, discrete mode/pose/contact/jump/use sets, attribute/effect intervals, application automaton retaining impulse IDs, replace/add order and earliest/latest step; coverage and numeric profile are "attached prerequisites, not interval fields allowed to disappear". *Inputs/outputs:* `T#(A)` over admissible inputs and worlds. *Soundness:* `step(gamma(A), admissibleInputs, admissibleWorlds) subset gamma(T#(A))` — stated as a formula, which is exactly what I asked for. *When used:* exclusion, branch pruning, supported-frontier continuation. *May prove:* exclusion only. *Cannot prove:* "cannot establish a `LegitimateWitness`, reconstruct actual keys, resolve a client application tick, or justify an eye point merely by containing it. `O intersects gamma(A)` is possible, not proven valid." *Version-specific:* primitives owned by physics/version, composed by simulation; "Every numeric-profile/version change invalidates old proof IDs." *Testing:* TC12 plus adversarial edge/rounding cases, with the honest caveat that "Sampling is a regression check, not a mathematical proof: per-operator containment argument plus independent review is required." *Roadmap owner:* Phase 5 operators/proofs, Phase 6 solver/merge/refinement.

  The critical asymmetry the brief demands is explicit and correctly directed: "Losing correlations widens the set and creates false negatives/UNCERTAIN, never permission to shrink it", and "For a region whose collision response cannot be bounded tightly, union every relevant contact case or return top/UNKNOWN, never assume free space." Widening is always allowed; narrowing never is. Merging is "tagged union or interval hull preserving discrete obligations" — no representative-candidate merge, which was my specific failure scenario.

  One further clause ties M3 back to B3 and answers my Review #1 continuation concern: "A trusted frontier narrowed by an observation is the intersection with that observation **only after outcome certification**, retaining all histories consistent with it. One matching witness must not discard the remainder."
- **STATUS:** **VERIFIED RESOLVED**
- **REMAINING ACTION:** None. Containment proofs are correctly scheduled with an independent-review requirement.

### H3

- **ID:** H3 — retention horizons mutually inconsistent
- **ORIGINAL PROBLEM:** Timeline (4096 records / 10 s), entity history (64 revisions ≈ 3.2 s), markers (10 s) and combat pending attacks were specified independently; the effective horizon was the minimum, and it collapsed fastest in dense combat.
- **ORIGINAL ACCEPTANCE CRITERIA:** A derivation from a target horizon and assumed event rate to each cap with stated rates; a 30-entity 20 Hz test asserting the achieved horizon meets target or reports the shortfall; no document claiming a horizon its inputs cannot supply.
- **ASTRA REMEDIATION:** ACCEPT. One 3-second qualification target; explicit 1000 records/s envelope; per-query achieved horizons; markers demoted to diagnostics.
- **INDEPENDENT VERIFICATION:** The derivation is published and I recomputed all of it. H=3 s at 1000 records/s, ≤256 bytes charged → 3000 records ≤ 4096 ✓ and 768,000 bytes ≤ 1 MiB ✓. At 2000 records/s both bounds independently give 2.048 s — I confirmed `4096/2000 = 2.048` and `1048576/(2000×256) = 2.048` — and the shortfall is required to be reported rather than concealed. The 512 KiB floor at 1000/s yields 2.048 s and "makes no three-second guarantee". Entity target: 30 relevant entities at 20 revisions/s need 60 revisions each for 3 s, leaving headroom under the 96 cap; `30 × 60 × 128 = 230400` bytes = 225 KiB ✓.

  The minimum-dependency rule I demanded is now normative in three places. PERFORMANCE: "Every query reports `availableHorizon=min(timeline, world revisions, entity revisions, application constraints, checkpoint dependency closure)`." LATENCY: "actual query availability equal to the minimum dependency horizon"; "Marker diagnostics may retain ten seconds/256 markers, **but do not extend world/entity/timeline coverage**." COMBAT B2: "Pending attacks expire at the minimum of achieved timeline, entity, world and application coverage, with a three-second target at most, never marker diagnostic retention." The 10 s / 3.2 s mismatch is gone because 10 s is now explicitly diagnostic-only.

  My swept check for stale 10-second coverage claims found only the two legitimate diagnostic references and the unrelated 10-second anomaly guard window. TIMELINE adds the anti-gaming clause: "The immutable journal includes every captured outbound entity event and server outcome, not a lossy tick aggregate", and PERFORMANCE repeats "no hidden aggregation to satisfy these rates". The 30-entity test is specified in TESTING's coverage gates.
- **STATUS:** **VERIFIED RESOLVED**
- **REMAINING ACTION:** None.

### H4

- **ID:** H4 — observer world baselines unrecoverable; eviction, gap recovery and mid-session enable all produce permanent UNKNOWN
- **ORIGINAL PROBLEM:** The observer world is built only from outbound packets; the server never resends known chunks, so three paths lose a baseline irrecoverably, and `RESYNC_PENDING → HEALTHY` could not actually restore world coverage.
- **ORIGINAL ACCEPTANCE CRITERIA:** State the unrecoverability property explicitly; choose a bootstrap policy for pre-existing sessions and post-gap recovery with the coverage state named; publish measured or explicitly justified comparison of the two representations at distance 10/16 in Phase 3.
- **ASTRA REMEDIATION:** PARTIALLY ACCEPT. Decision W2: `OBSERVE_ONLY_BASELINE_MISSING` for pre-existing sessions; lazy regional reconstruction; independent coverage vector; divergence arithmetic supplied; Phase 3 benchmarks both models. Astra narrows my "permanent" to conditional, since a later full baseline can restore a region.
- **INDEPENDENT VERIFICATION:** The narrowing is correct — "permanently" was too strong, since crossing into a region that is re-sent does restore it. Everything else is met.

  The coverage vector the brief asks for is explicit and separated: `OrderingHealth`, `PipelineOutcomeCoverage`, `WorldCoverage(regions/revisions)`, `EntityCoverage(spatial neighborhood/incarnations)`, `ApplicationCoverage`, `PhysicsCapabilityCoverage`, with "Queries require the conjunction of their dependencies." THREADING states the consequence I asked for in the exact terms I used: "Ordering health transitions HEALTHY → GAP/OVERLOADED → RESYNC_PENDING → HEALTHY … WorldCoverage, EntityCoverage, PipelineOutcomeCoverage and ApplicationCoverage are separate dimensions and **are not reset to complete by Resume**." TIMELINE repeats it: "Resume restores ordering only."

  The brief's mid-session test: a pre-existing session enters `OBSERVE_ONLY_BASELINE_MISSING`; the system cannot declare complete observer knowledge; only newly observed complete baselines restore regions — "A delta/current server snapshot cannot restore an evicted observer baseline." Eviction and gap recovery follow the same rule. What is required before complete movement exclusion can resume is stated: a newly observed complete baseline (or qualified equivalent) plus a reconciled supported frontier plus the conjunction of dependent coverage dimensions.

  The divergence comparison is supplied with arithmetic I verified: `(2×10+1)² = 441` and `(2×16+1)² = 1089` columns; at 24 sections/column and 4 KiB/section, `441×24×4 KiB = 41.34 MiB` and `1089×24×4 KiB = 102.09 MiB`; at 1 % divergence, ~0.41/1.02 MiB. All correct, and all labelled "arithmetic estimates, not measurements". Phase 3 benchmarks both representations on fully captured data including event-loop copy cost and worst-case divergence.

  The reason for rejecting my divergence-overlay preference is better than my proposal: "absent fake-block divergence cannot be proven after mid-session activation." My model assumed divergence capture could be complete; it cannot be for a session that predates the plugin. RT11's observer precedence over any main-thread snapshot is preserved.
- **STATUS:** **VERIFIED RESOLVED** (with my "permanent" correctly narrowed to conditional)
- **REMAINING ACTION:** None. Phase 3 representation benchmark is a scheduled gate.

### H5

- **ID:** H5 — pending-update application branching unbounded
- **ORIGINAL PROBLEM:** With application unresolvable, every physics-affecting update stayed ambiguous, nothing bounded how many were unresolved at once, and ~8 concurrent updates saturated the 256-candidate frontier — collapsing coverage in exactly the dense-combat, high-latency case the model exists for.
- **ORIGINAL ACCEPTANCE CRITERIA:** A stated cap on concurrently unresolved physics-affecting updates with a named overflow reason; a Phase 7b test at 50/150/300 ms reporting the fraction of definite outcomes against a declared minimum.
- **ASTRA REMEDIATION:** ACCEPT. Decision N2 caps eight simultaneously unresolved physics-affecting updates per supported motion domain; 64 diagnostic records retained; M3 application automaton replaces Boolean enumeration; sound branch retirement defined.
- **INDEPENDENT VERIFICATION:** Cap ✓, named overflow `UNCERTAIN_APPLICATION_OVERFLOW` ✓ with "visible coverage loss with zero violation weight" ✓. The separation of ledger capacity from branch budget is stated as the decision rationale: "Ledger capacity is not a feasible branch budget."

  Branch retirement — which I suggested loosely — is specified soundly: "A later certified observation can eliminate an application branch only if a sound covering exclusion proves that branch cannot explain it; **failure to find a witness is insufficient**. Such elimination is inference conditional on the movement model, recorded with proof IDs, never a claimed ACK guarantee. Contradiction invalidates the frontier." That is the correct form; my version would have permitted witness-absence retirement, which is unsound.

  Also correct: "A simple sum of max impulses is a safe outer bound only when a proof covers replacement/drag/bounce and signs; otherwise widen or mark incomplete" — the naive outer bound is not assumed safe. And "No generic velocity-application guarantee is certified by Phase 0."

  The Phase 7b gate is numeric: ">=90% definite outcomes per declared supported profile, zero false INVALID, and explicit counts for application overflow/history loss" at 50/150/300 ms, with "If a profile cannot meet them, keep it unqualified and report the product limitation." TC04/TC05 carry the scenarios.

  Astra also adopted my Review #1 re-ranking: "ACK receipt/application confusion is downgraded to a guarded implementation obligation; the high architectural risk is exclusion precision under sustained unresolved state." The risk table in PHASE_0_REVIEW reflects this.
- **STATUS:** **VERIFIED RESOLVED**
- **REMAINING ACTION:** None.

### H6

- **ID:** H6 — no contract for movement-affecting block properties
- **ORIGINAL PROBLEM:** `collision` owned shapes and `physics` owned step rules, but nothing owned friction, speed factor, bounce, fluid flow, inside-block drag or column forces — so the simulator would apply defaults on ice, soul sand, honey, cobwebs, bubble columns, slime and beds.
- **ORIGINAL ACCEPTANCE CRITERIA:** A named contract with coverage semantics and a work bound; the step specification citing it as an input; a property fixture list naming at least ice/blue-ice friction, soul sand, honey slowdown and wall-slide, slime and bed bounce, cobweb and berry drag, bubble column up/down, fluid flow.
- **ASTRA REMEDIATION:** ACCEPT. Decision W3: world owns `BlockBehaviorQuery`, version provides pure definitions, physics applies them, collision keeps geometry.
- **INDEPENDENT VERIFICATION:** The contract exists with the right shape: `BlockBehaviorQuery(runtime state ID, coherent revision, entity context, sample kind)` → `KNOWN`/`AMBIGUOUS`/`UNKNOWN` plus typed friction, speed/jump factor, bounce, inside drag, fluid flow/column force, climb/support and special acceleration responses. Coverage semantics match `blockAt` ✓. Work bound is separate and explicit: "inside sampling is separately bounded to 512 cells/step and charged to total work"; PERFORMANCE has the row with `UNCERTAIN_BLOCK_BEHAVIOR`. The no-default rule is stated twice — "Unknown properties are not normal friction or zero flow" and, in MOVEMENT_SIMULATION, "Unmodeled force, missing neighbors or unknown behavior cannot be defaulted to zero/ordinary friction."

  The separation the brief asks about is enforced from the collision side, which is where it matters: "MovementQuery never calculates friction/inside drag itself: physics queries BlockBehaviorQuery using the independently specified sampling rule." The step specification lists `BlockBehaviorQuery` samples as an ordered input, and adds "A shape-only world is incomplete physics."

  Fixture list: COLLISION names "Slime bounce, honey wall-slide, ice friction, soul sand slowdown, bed bounce, web/berry drag, bubble columns and fluid flow need property fixtures **in addition to** shape fixtures"; TC07 covers "Ice/blue ice, soul sand, honey slowdown/wall-slide, slime/bed bounce, web/berry drag, bubble up/down and fluid flow: independent concrete witnesses". Climbability and support behaviour are in the property set; VERSION_SUPPORT's capability table requires fluids/climbing before enforcement. Version abstraction covers them: "A profile declares … BlockBehaviorQuery definitions".
- **STATUS:** **VERIFIED RESOLVED**
- **REMAINING ACTION:** None. Native property qualification is a Phase 5 gate.

### H7

- **ID:** H7 — entity-vs-entity collision and pushing absent from the entire specification
- **ORIGINAL PROBLEM:** Collision was specified against blocks and the world border only; nothing covered entity push or entities as colliders, producing systematic mismatch in crowds.
- **ORIGINAL ACCEPTANCE CRITERIA:** A DECISION covering entity push and entity colliders with the coverage reason named when unmodelled; a PERFORMANCE budget row if modelled; crowd and boat-as-collider fixtures asserting no INVALID with complete-knowledge flags.
- **ASTRA REMEDIATION:** PARTIALLY ACCEPT. `NearbyMotionEntities` over a bounded relevant neighborhood; server-only/predicted/mixed qualification; resultant deduplication. Astra rejects my blanket "client-predicted" characterization.
- **INDEPENDENT VERIFICATION:** The rejection is correct — whether a given push is predicted, server-only or mixed is a version and mechanism property, and asserting prediction universally would bake in an unverified assumption. "Do not assume all pushing is client-predicted: profiles distinguish server-only, predicted and mixed effects… Unknown execution side means incomplete physics."

  The bounded-neighborhood strategy the brief asks about is exactly right and does not require global tracking: the query is "a spatial index over the conservative swept player envelope expanded by the profile's maximum push/contact radius", resolving "at most 32 relevant entities/step with 2048 push/collider operations charged to movement work". PERFORMANCE carries the row. Unknown state degrades completeness without exception: "Overflow, missing spawn history, uncovered visibility or unbounded region yields `UNCERTAIN_ENTITY_NEIGHBORHOOD`. **Omission from a capped list never proves absence.**"

  Coverage of the entity classes the brief lists: "Include players, mobs, boats, minecarts and other version-defined push/solid entities, including shulkers where applicable." Entities as colliders are wired into movement geometry: COLLISION states "Body collision includes profile-defined solid entity boxes from world's bounded neighborhood, with provenance separate from entity push. Missing collider history invalidates completeness." Double-application is prevented: "Common source IDs prevent double application"; "Server-only contributions use captured resultant velocity/corrections; predicted contributions use observer history."

  TC06 covers crowds of 5/15/30 plus a 33-entity overflow case, mob contact, and boat/minecart/shulker colliders, asserting VALID where qualified and named uncertainty on overflow or unknown execution side.
- **STATUS:** **VERIFIED RESOLVED** (with my over-generalization correctly narrowed)
- **REMAINING ACTION:** None. Execution-side semantics are a Phase 5 version experiment.

### H8

- **ID:** H8 — observation source lists omitted movement-critical packets
- **ORIGINAL PROBLEM:** No explosion knockback, vehicle move/passengers, camera, respawn/game-event/health, tick-rate/freeze, held-item, swing or client tick-end. Explosion alone guarantees a certified false positive on a very common event.
- **ORIGINAL ACCEPTANCE CRITERIA:** An enumerated per-direction matrix naming those families; a Phase 1 test asserting every physics-affecting family has an adapter entry and that a removed entry causes startup capability loss rather than silent absence.
- **ASTRA REMEDIATION:** ACCEPT. Enumerated semantic observation matrix with startup inventory checking.
- **INDEPENDENT VERIFICATION:** I checked the matrix against my list item by item. Explosion motion payload ✓ — and better than I asked, "explosion vector captured independently of source mob", which prevents attributing it to an entity-velocity path. Vehicle move/steer/paddle/mount controls inbound and vehicle correction/move outbound ✓. Passengers/mount ✓. Set camera ✓. Respawn, game event/gamemode, set health/death ✓. Tick-rate, tick-freeze/step state ✓. Held-item change ✓. Swing/animation ✓. Client tick-end ✓. Beyond my list: target face/cursor for interaction, moving border, block-entity/event, action acknowledgements.

  The enforcement rule is exactly my acceptance criterion: "Every target-profile packet family is explicitly classified affecting/irrelevant/unsupported. Adapter coverage is checked against that inventory at startup; absence of any affecting entry disables dependent capability with `MissingObservationFamily`." Honest scoping is retained: "The table is a semantic requirements list, not a claim of exact packet names/IDs for all versions." Mode-specific sources are handled: "Firework/riptide/projectile/fishing effects are resolved from version-specific event/entity/item/velocity paths; missing any required path disables that mode."

  Downstream wiring is correct: PLAYER_STATE's `ExternalMotionInfluence` ledger lists explosion/TNT/creeper/end crystal as `VelocityDelta`/`VelocityReplace` sources with "Explosion block changes are recorded separately from its motion component"; VERSION_SUPPORT's capability table requires "external entity/explosion/projectile/fishing/plugin velocity" before enforcement; TC02 and TC22 carry the startup-inventory and tick-state assertions. Vehicle transitions now invalidate rather than carry over: "Unsupported transition observation invalidates the old frontier rather than retaining walking mode."
- **STATUS:** **VERIFIED RESOLVED**
- **REMAINING ACTION:** None. Exact packet IDs are Phase 1 profile work, correctly scoped.

### H9

- **ID:** H9 — budget starvation as an evasion channel; no shard fairness rule
- **ORIGINAL PROBLEM:** Budgets were per-event constants with no sustained per-session quota, so a player could make their own validation expensive and push their own verdicts to UNCERTAIN; and one expensive actor head-of-line blocked its shard with no sizing or degradation policy.
- **ORIGINAL ACCEPTANCE CRITERIA:** A sustained per-session work quota row and a degradation ladder; a per-session budget-exhaustion metric; a cost-maximising adversarial load profile; and a test showing that lowering budgets under load records the applied budget in evidence and that replay reproduces the verdict from it.
- **ASTRA REMEDIATION:** ACCEPT. Weighted sustained quota, burst and visit caps, worker sizing rule, visible degradation ladder.
- **INDEPENDENT VERIFICATION:** Sustained quota row exists: "2 million weighted units/session/second, burst 200000; 2048/visit or 32 events" → `UNCERTAIN_WORK_QUOTA` and operational alert ✓. Weighting is defined ("One primitive box/cell/interval operation costs one unit; a concrete step also charges its inner operations").

  Degradation ladder is explicit and ordered: "Degrade optional forensic retention first, then expensive heuristic windows, then deterministic work to `UNCERTAIN_WORK_QUOTA`, then alert/isolate AC admission or configured resource disconnect." THREADING adds the non-negotiable: "It never reduces certainty thresholds or silently lowers compulsory floors." And "Cost is never a cheat violation."

  Reproducibility under dynamic budgets is my exact criterion and is met: "Dynamic quotas are recorded config/grant inputs and replay must reproduce them"; "recorded monotonic credits and grants make replay independent of host scheduling"; TC11 asserts that lowering budgets at runtime yields `UNCERTAIN_SEARCH_INCOMPLETE` with the applied budget recorded and replayed.

  Shard sizing is now stated rather than left open: `W = max(1, min(8, availableProcessors-2))`, target ≤128 certified sessions/shard, and "If W*128 is below configured capacity, excess sessions are observation-only unless a benchmarked profile explicitly permits more." That is internally consistent with the 1000-player memory row (8×128 = 1024). Fairness is asserted — "Cost-maximizing clients cannot consume another actor's visit allowance" — and TC24 tests a cost-maximizing actor beside ordinary actors for visit/quota fairness and neighbour-session coverage.

  The adversarial profile is specified in the load matrix: "combines complex stairs/fences, maximal entity neighborhood, pending impulses and no early witness **without increasing packet rate**" — which is precisely the starvation vector I described. Per-session work-exhaustion fraction is in `CoverageSummary`.
- **STATUS:** **VERIFIED RESOLVED**
- **REMAINING ACTION:** None.

### H10

- **ID:** H10 — shard failure left reservations and pins unreclaimed; quarantined actors had no recovery path
- **ORIGINAL PROBLEM:** No owner for reclaiming a failed shard's memory and world-store pins, no rule removing it from assignment rotation, no defined terminal state for its sessions, no alert.
- **ORIGINAL ACCEPTANCE CRITERIA:** A documented shard-failure state machine with a named reclamation owner; a fault-injection test asserting reservations and pins released, no new assignments, a defined terminal session state and an alert.
- **ASTRA REMEDIATION:** PARTIALLY ACCEPT. Core supervisor with a lease ledger, `RUNNING → FAILED_FENCED → DRAINING_LEASES → RETIRED`, `OBSERVE_ONLY_UNTIL_RECONNECT` sessions. Astra refuses to reclaim from a hung-but-live thread.
- **INDEPENDENT VERIFICATION:** The refusal is correct and my Review #1 wording was unsafe — freeing memory still reachable by a live thread is a use-after-free regardless of fencing. The spec now distinguishes the two cases precisely: "After the worker is **proven terminated** and outstanding I/O lease users acknowledge release, the supervisor idempotently releases all reservations/pins and drops actor roots. It never frees memory still accessed by a live or hung thread. A hung worker stays fenced, reservations stay charged and admission shrinks; alert/restart is required."

  The key design element that makes reclamation possible without the dead writer is stated: "Allocation/pin tokens are registered in a supervisor-owned lease ledger **before publication to an actor**, so cleanup does not require the failed writer." That is the right mechanism.

  All four of my criteria are met: state machine and owner ✓; reclamation after proven termination ✓; "removes it from admission rotation" and "New sessions use remaining healthy shards or are refused AC admission with a reason" ✓; terminal session state `OBSERVE_ONLY_UNTIL_RECONNECT` with "their old mutable actors are never migrated/reused" and "Late callbacks are rejected by generation" ✓; alert ✓. TC16 tests both arms: "Kill shard mid-event: fenced/no new assignments, terminated-writer lease reclamation and alert; hung shard never has live memory freed."
- **STATUS:** **SUPERSEDED BY A STRONGER DESIGN**
- **REMAINING ACTION:** None.

### H11

- **ID:** H11 — no server-wide anomaly guard and no runtime enforcement kill switch
- **ORIGINAL PROBLEM:** All safety was per-session; nothing noticed correlated mass exclusions, which is the signature of a server-side fault rather than mass cheating; and there was no restart-free way to disable enforcement.
- **ORIGINAL ACCEPTANCE CRITERIA:** Both mechanisms specified with thresholds and their interaction with effect-admission generation; a test showing a correlated fault suspends effects server-wide before per-session loop breakers trip; a test showing a runtime mode flip admits no new effects and appears in replay.
- **ASTRA REMEDIATION:** ACCEPT. Decision G2: main-thread global admission arbiter owning runtime mode and a global generation, with two anomaly gates.
- **INDEPENDENT VERIFICATION:** Modes are `DISABLED` / `OBSERVE_ONLY` (default) / `ENFORCE` (qualified and human-enabled) ✓. Thresholds are concrete: a 10-second window suspending at distinct actionable sessions `>= max(3, ceil(0.20*N))` where N is eligible sessions at window start, plus a burst gate at 3 distinct sessions in 1 second regardless of N — labelled "reviewable configuration, not measured optimal settings". Counting is at the right point: "Count proposed actionable decisions before capture/queue delay and before execution, not only completed setbacks", which is what makes it fire before the per-session breakers.

  Interaction with admission generation is specified: "Main-thread admission serializes global checks with effect starts; per-session transport invalidation still uses the atomic race rule… Already-started platform calls cannot be undone, but no new effect starts after the global restrictive transition." Re-enable "requires explicit command and current healthy coverage/profiles; no automatic timer reset" ✓. `GlobalAnomalySuspend` carries no cheating weight ✓, and EVIDENCE routes cross-session anomalies here rather than to player guilt.

  Runtime control is restart-free and correctly scoped: "Permissioned restriction immediately updates global mode/generation at main-thread command execution, before notifying actors"; "Runtime mode may only become more restrictive immediately"; "This runtime control is supported even though plugin hot reload is not." Honest limitation: "A stalled main thread cannot execute either effects or the command; report control latency rather than promise a wall-clock response during a stall."

  My tests are specified: "Explicit correlated fault tests must show suspension before individual three-failure loop breakers fire", and TC17 covers the correlated anomaly, the `ENFORCE → OBSERVE_ONLY` transition, no new effect after restriction, and control/admission log replay.
- **STATUS:** **VERIFIED RESOLVED**
- **REMAINING ACTION:** None.

### H12

- **ID:** H12 — numeric profile did not cover transcendental functions
- **ORIGINAL PROBLEM:** Vanilla fidelity and cross-host replay determinism both depended on trig behaviour that no document addressed.
- **ORIGINAL ACCEPTANCE CRITERIA:** The profile names each transcendental function and its mandated implementation; a CI check fails on direct `Math` transcendental calls in engine modules; a replay determinism test on at least two OS/architecture combinations.
- **ASTRA REMEDIATION:** PARTIALLY ACCEPT. New `NUMERICAL_POLICY.md` with version-owned `NumericOps`. Astra rejects both a universal StrictMath replacement and a universal table assertion.
- **INDEPENDENT VERIFICATION:** The rejection is correct, and it is the more important half of the finding. My recommendation offered StrictMath as an acceptable option; NUM1's rebuttal — "Reproducibility of the wrong arithmetic is not vanilla fidelity" — is right, and the priority ordering it establishes is the correct resolution of the tension the brief asks me to check: "native behavior fidelity governs concrete simulation; proven outward containment governs exclusion bounds; exact reproducibility governs verified replay/digests; display formatting may round and is excluded from decisions." Mathematical reproducibility is not allowed to displace fidelity.

  Function enumeration is complete for the classes that matter: sin/cos for movement basis and pick rays (reproduce the qualified client algorithm/table, with table bits generated independently and hashed — and explicitly "no Intave tables copied", which respects the no-copy rule); atan2/asin/acos/tan (profile-specified, unsupported until independently qualified); pow/exp/log/log10/cbrt/hypot/hyperbolic (disallowed in core physical transitions unless individually listed with a proven client-compatible implementation; StrictMath permitted only for deterministic diagnostics); sqrt/floor/ceil/rounding/casts/remainder/signed zero (explicit IEEE semantics, no reassociation or FMA substitution unless the profile specifies it); and interval bounds (outward rounding at every primitive and cast, including extrema and table-index discontinuities).

  CI check ✓, and broader than I asked: "CI forbids direct Math.sin/cos/tan/atan2/pow/exp/log and other transcendental calls in collision/physics/simulation/combat/interaction (**including static imports and wrapper bypasses**); reviewed NumericOps implementations are the only path." Cross-host test ✓: TC13 runs the same capsule on Linux x86-64 and Windows ARM64 (or a documented alternative pair) asserting bitwise output/checkpoint equality, with mismatched table/profile rejected.

  The determinism level claimed is precisely scoped rather than overclaimed: bitwise reproducibility only on conformance-qualified hosts, with `INCOMPATIBLE_NUMERIC_ENVIRONMENT` refusing verified replay elsewhere, and "Bit-identical cross-host replay is a qualification requirement, not an achievement." Unavailable faithful algorithms yield `NUMERIC_PROFILE_UNVERIFIED` rather than "a widened unprincipled tolerance". One subtlety I would have missed is handled: "recorded intermediates cannot replace recomputation in verification mode. A playback-only view that uses them must say it is not independent replay verification."
- **STATUS:** **SUPERSEDED BY A STRONGER DESIGN**
- **REMAINING ACTION:** None. Table/algorithm qualification is a Phase 5 gate; recorder conformance is Phase 1.

### H13

- **ID:** H13 — memory budgets contradicted the stated 1000-player target
- **ORIGINAL PROBLEM:** 16 MiB/session against a `min(1 GiB, 25% heap)` default meant the default was below even the component floor at every target player count, with no stated minimum footprint.
- **ORIGINAL ACCEPTANCE CRITERIA:** A minimum per-session footprint; a list of reducible versus floor quotas; a player-count-to-budget table with assumptions; and explicit refusal or downgrade with a logged reason when the budget cannot meet the floor.
- **ASTRA REMEDIATION:** PARTIALLY ACCEPT. Decision F2 removes the per-session reservation entirely in favour of global-first admission with two tiers and a published scale equation. Astra notes that independent component maxima were never measured minima.
- **INDEPENDENT VERIFICATION:** The old 16 MiB reservation is explicitly removed — I swept for it and found only the removal statement plus unrelated "16 KiB"/"16 MiB I/O" uses.

  Floor stated ✓: `CORE_OBSERVATION` = 2 MiB/session, composed of timeline 512 KiB + world/entity 768 KiB + ingress/bridge/control 256 KiB + journal 256 KiB + other 256 KiB — I checked, that sums to exactly 2048 KiB. Target tier 4 MiB = timeline 1 MiB + world/entity 2 MiB + remaining 1 MiB ✓. Reducible-versus-floor is stated: "Reducing optional rolling replay, shape cache or retention above the floor is allowed only by explicit validated allocation policy with visible coverage impact"; "It never silently lowers a floor."

  Scale equation `B >= (256 + N*tierMiB)/0.8` with a reference partition `G = 256 MiB` (shared world 96 + shape cache 32 + replay 96 + other 32 — verified to sum to 256). I recomputed all ten table cells: 445/570/945/1570/2820 at the floor tier and 570/820/1570/2820/5320 at the target tier — every value correct. Assumptions are stated, including "B excludes Paper world heap."

  Refusal behaviour is explicit: "Default B remains min(1 GiB, 25% JVM max heap). It does not admit 1000 target-tier players: startup checks configured capacity, worker envelope and tier against the equation, and either refuses that AC configuration or explicitly selects `CORE_OBSERVATION` with `MemoryTierLimited` if the operator configured that fallback." And honest scope limits: "the floor guarantees resource-safe observation, not every mechanic or a three-second horizon"; "World coverage may be insufficient even at the target private tier"; "Until measured implementations fit, these tiers cannot be advertised as supported footprints."
- **STATUS:** **VERIFIED RESOLVED**
- **REMAINING ACTION:** None. Retained-heap verification is a scheduled gate (TC14, Phase 24).

### H14

- **ID:** H14 — combat did not state which attacker eye position is authoritative
- **ORIGINAL PROBLEM:** `MovementState` separated claimed from supported poses, but combat never said which supplied the attacker eye, nor what happens when the claim lies outside the supported frontier.
- **ORIGINAL ACCEPTANCE CRITERIA:** A stated derivation rule and outside-frontier case; an `eyeSource` provenance field in `CombatValidation`; a within-tolerance position-shift fixture asserting reach does not validate on the claimed eye alone.
- **ASTRA REMEDIATION:** PARTIALLY ACCEPT. Decision B2 requires a concrete certified trajectory. Astra rejects my "intersection of claimed and supported" as insufficient, because an envelope point is not a demonstrated reachable state.
- **INDEPENDENT VERIFICATION:** The rejection is correct and strictly stronger than my recommendation. An abstract envelope over-approximates; a point inside it may not be reachable by any concrete trajectory, so intersecting the claim with the envelope could legitimise an unreachable eye. B2 instead requires "concrete, certified supported trajectories consistent with the claim's profile-defined observation interval. A tolerance envelope alone is not a concrete eye witness."

  Outside-frontier case ✓ and it also avoids double-counting: "If the claim lies outside the supported outer frontier, return `UNCERTAIN_ATTACKER_FRONTIER_CONFLICT` and route the root observation to movement; do not create a second punitive reach exclusion." Envelope-only case ✓: "If it intersects only an abstract envelope, combat can exclude all eyes only through sound geometry over the complete set, but cannot establish VALID from an arbitrary envelope point." Outcome dependence ✓: "Outcome-pending/rejected movement yields `UNCERTAIN_PIPELINE_OUTCOME`." Pose coherence ✓: "Eye height/pose belongs to the same trajectory and profile as its position."

  Provenance field ✓: `eyeSource ∈ {CERTIFIED_CONCRETE, ABSTRACT_EXCLUSION_ONLY, UNAVAILABLE}` with supporting trajectory/proof IDs, claim interval, originating attempt/outcome and causal roots. Fixture ✓: "A within-tolerance position-shift fixture must show no raw-claim-only VALID"; TC21 also asserts an envelope-only eye yields no concrete witness.

  The honesty clause is correct and I endorse it: "This does not promise to detect a lie observationally identical to legitimate movement; reachable motion is not proof of honest intent." My Review #1 failure scenario assumed a per-tick shift within movement tolerance would be catchable at the reach layer; if the shifted trajectory is genuinely within the legitimate reachable set, it is not detectable at any deterministic layer, and promising otherwise would be a false guarantee.
- **STATUS:** **SUPERSEDED BY A STRONGER DESIGN**
- **REMAINING ACTION:** None.

---

## MEDIUM/LOW VERIFICATION TABLE

| ID | Orig. severity | Astra disposition | Review #2 status | Notes |
|---|---|---|---|---|
| M1 | MEDIUM | ACCEPT | VERIFIED RESOLVED | CFG1: core owns schema/defaults/validation; canonical form fully specified (expand defaults, normalize units, ordinal key sort, ordered arrays preserved, sets sorted, canonical JSON/LF, NaN rejected); "Comments/key order/formatting do not change the digest"; invalid config cannot enable enforcement. TC18. |
| M2 | MEDIUM | ACCEPT | VERIFIED RESOLVED | `api` owns scoped expiring exemptions (≤32/player, 60 s default max) and `DeclaredEffectNotice` correlated to captured outcomes; "Unknown behavior is uncovered, not silently legitimized". Trust factors explicitly **rejected**: "No reputation/trust multiplier alters proof, reach or physics tolerance." Phase 1 queries / Phase 2 declarations. |
| M3 | MEDIUM | ACCEPT | VERIFIED RESOLVED | Root-set rule replaces category examples: union on `rootObservationIds` overlap or identical causal key, transitive closure, one capped weight (max member, not sum), "Different engines do not establish independence". 64-root cap with `CorrelationIncomplete` forbidding extra weight rather than assuming disjointness. TC20. |
| M4 | MEDIUM | PARTIAL | SUPERSEDED | 2-second anchor age, 8-block distance, on-foot-only scope, foreign-generation precedence. My `teleportAsync` suggestion correctly refused: "general teleportAsync may load asynchronously but is not a substitute for a fresh final precondition check" — requires a profile-proven no-sync-load path or rejection. Fall-distance/velocity side effects captured. TC15. |
| M5 | MEDIUM | PARTIAL | VERIFIED RESOLVED (gate deferred, correctly) | `DURABLE_REQUIRED` retained; three named scenarios (sparse movement, dense combat, chunk churn); gate ">=99% complete durable captures within 400 ms" against the 500 ms deadline; "If it fails, automated mitigation stays disabled." Measurement is Phase 9, blocking Phase 10 — not Review #2. See NEW-M1 for a gate-precision point. |
| M6 | MEDIUM | PARTIAL | VERIFIED RESOLVED | Support is now `AdapterBoundaryProbe` + qualified profile, not build-name matching; known-good hashes are "provenance but do not bypass live pipeline checks"; unknown builds matching every fingerprint may use that compatibility class; any mismatch → OBSERVE_ONLY. "Successful reflection alone does not prove client semantics." |
| M7 | MEDIUM | ACCEPT | VERIFIED RESOLVED | `BlockStateId` interned integer for hot lookups + canonical `RegistryDictionary` recorded in the capsule; "IDs may vary by run; canonical digests translate them." TC19 asserts export/restore/export digest equality across changed runtime IDs. |
| M8 | MEDIUM | ACCEPT | VERIFIED RESOLVED | `NativeScenarioHarness` owned by anticheat-testing, Phase 1 transport lane + Phase 5 motion oracles; checksum-pinned licensed client, external driver or minimal test-only injector, read-only instrumentation with perturbation comparison; per-version recapture owner named; 4–8 engineering hours + 1–2 host-hours estimate, labelled an estimate. |
| M9 | MEDIUM | ACCEPT | VERIFIED RESOLVED | `CoverageSummary` per session: dimensions, achieved horizons, VALID/INVALID/UNCERTAIN by capability, work-exhaustion fraction, memory tier, 30-second window. `CoverageDegraded` below 50 % definite over 30 s (min 100 observations), zero violation weight. |
| M10 | MEDIUM | ACCEPT | VERIFIED RESOLVED | Event-loop utilization, per-family callback time, copied bytes/s and bulk-copy tails added; targets p99 ≤50 µs small callback, ≤500 µs admitted bulk copy, ≤10 % loop wall time, explicitly "measured targets, not interrupt-safe deadlines". Phase 1 criterion 8 now requires the measurements. |
| M11 | MEDIUM | ACCEPT | VERIFIED RESOLVED | C1 reframed: "combat occlusion is a use that may alias the client's outline/pick path, not an invented third geometry family"; shape-use mapping is a version-profile entry with a required fixture proving target-client behaviour. |
| M12 | MEDIUM | ACCEPT | VERIFIED RESOLVED | `anticheat-interaction` owns placement/use/break/non-attack entity semantics; world owns overlay storage; core applies `ProposedOverlayDelta` at the outcome boundary; checks keeps scaffold behaviour features only. Folded into B1 path 3. |
| M13 | MEDIUM | PARTIAL | VERIFIED RESOLVED | All five omissions added with pinned sources: `block/physics` split, permission/integration surface, trustfactor (rejected with reasoning), MovingWorldBorder + piston/slime/shulker/geyser, ThreeTickSimulationSearch/branch-frequency. Astra's pushback on inferring three-step sufficiency from filenames is correct; so is the correction that a per-run variant ID is not defective if its meaning is recorded — I overstated that comparison in Review #1. |
| L1 | LOW | PARTIAL | VERIFIED RESOLVED | Migration checklist lists `name`, `authors`, `main`, version, Java 25; api-version deliberately left as a Phase 1 pin gate ("expected 26.x, not a guessed literal") rather than invented. Correct restraint. |
| L2 | LOW | ACCEPT | VERIFIED RESOLVED | `!gradle/wrapper/gradle-wrapper.jar` after `*.jar` plus clean-clone build in the checklist; `.gitignore` correctly left unchanged since no migration is authorized. |
| L3 | LOW | ACCEPT | VERIFIED RESOLVED | I checked every Mermaid node: all labels are exact single module names from the table. New boundaries (`interaction`, `replay-schema`, `model`) and the `core→physics`/`collision`/`interaction` and `mitigation→collision` edges are shown. |
| L4 | LOW | ACCEPT | VERIFIED RESOLVED | Corrected to "Gradle tasks named testPaper for 20 versions… mostly Spigot-named server artifacts, a Paper artifact and an alternative 1.8.8 artifact; the task name is not proof that every run uses Paper." Matches what I re-observed in the pinned `libs/` tree. |
| L5 | LOW | ACCEPT | VERIFIED RESOLVED | README scope sentence rewritten; no dangling "below" reference. |
| L6 | LOW | ACCEPT | VERIFIED RESOLVED | 7-day / 5 GiB retention now a CONFIGURATION field with defaults and digest coverage, referenced (not redefined) by EVIDENCE and PERFORMANCE. |
| RT1 | HIGH (red team) | ACCEPT | VERIFIED RESOLVED | Explosion motion in the observation matrix and as a distinct `ExternalMotionInfluence` source, separate from block changes and source mob. TC05. |
| RT2 | HIGH | ACCEPT | VERIFIED RESOLVED | `NearbyMotionEntities`, 32-entity cap, explicit overflow. TC06 (5/15/30 plus 33). |
| RT3 | HIGH | ACCEPT | VERIFIED RESOLVED | `BlockBehaviorQuery` with no-default rule. TC07. |
| RT4 | BLOCKER-level | ACCEPT | SUPERSEDED | P2 plus boundary probe plus the transient-handler refusal. TC01/TC02. |
| RT5 | HIGH | ACCEPT | VERIFIED RESOLVED | `OBSERVE_ONLY_BASELINE_MISSING`; only newly covered regions become eligible. TC09/TC10 (TC09 uses 300 sessions, my scenario's scale). |
| RT6 | HIGH | ACCEPT | VERIFIED RESOLVED | Tick-rate/freeze/step observed as a state input; stall handling retained. TC22. |
| RT7 | HIGH | ACCEPT | VERIFIED RESOLVED | Eight-influence cap + application automaton + sound retirement + Phase 7b ≥90 % floor. TC04/TC05. |
| RT8 | MEDIUM | ACCEPT | VERIFIED RESOLVED | `ModeAcceleration` requires qualified source paths; "Modes cannot be VERIFIED until all required motion sources have capture/application/fixture coverage." TC23. |
| RT9 | MEDIUM | ACCEPT | VERIFIED RESOLVED | Vehicle move/steer/passengers mandatory; "Unsupported transition observation invalidates the old frontier rather than retaining walking mode" — the mode-transition-missed case I raised. TC06/TC22/TC23. |
| RT10 | MEDIUM | ACCEPT | VERIFIED RESOLVED | `KinematicDisplacement` as a distinct mechanism with temporal geometry, explicitly "not equivalent to velocity". TC08. |
| RT11 | MEDIUM | ACCEPT | VERIFIED RESOLVED | Observer precedence preserved through the W2 bootstrap work; "reject unknown bootstrap instead of overlaying server truth". TC10. |
| RT12 | MEDIUM | ACCEPT | VERIFIED RESOLVED | Foreign generation supersedes a pending intent before execution; after an already-started effect the foreign action wins and reconciles a new baseline; both recorded with zero violation weight. TC03/TC15. |
| U1–U6 | — | ACCEPT ×6 | VERIFIED TRACKED | Astra assigned local IDs to my six unnumbered red-team notes (jitter/loss, mid-flight attributes/effects, creative-spectator, grazing/edge collisions, translation scope, custom movement plugins) rather than silently dropping them. Each has a contract change and a scheduled gate. Correct handling; local IDs are clearly marked as not original review IDs. |

Disposition arithmetic checks out: 49 numbered findings (B4+H14+M13+L6+RT12) plus U1–U6 = 55 dispositions; I counted 13 partial accepts (B4, H1, H4, H7, H10, H12, H13, H14, M4, M5, M6, M13, L1) and 42 accepts, matching the report. **No Medium/Low finding requires escalation.**

---

## FINAL MODULE DAG VERIFICATION

Verified independently by re-parsing the table and running my own DFS cycle detection and reachability closure — I did not rely on the supplied validator.

- **20 modules** (19 production + test-only `testing`). **Zero cycles.** Table row order is itself topological.
- **No unknown providers**; every declared dependency resolves to a declared module.
- **Leaves:** `api`, `model`, `replay-schema` all have zero dependencies — the three stability surfaces are structurally independent of everything else.
- **`replay` reachability closure = `{replay-schema}` exactly.** It cannot reach any implementation module directly or transitively.
- **No engine imports `api`:** the set of non-`core`/`platform-paper`/`testing` modules with `api` in their transitive reach is empty.
- **`checks` isolation preserved:** no edge to `world`, `collision`, `physics`, `mitigation` or `platform-paper`. A check still cannot grow its own geometry without a build failure.
- **`platform-paper` has no `collision` edge**, forcing clearance through the `model` port; it does have `world`, which it needs to build the destination view.
- **No production→`testing` edge.**
- **Eleven modules carry a `replay-schema` edge** (protocol, player, world, simulation, combat, interaction, evidence, mitigation, replay, core, testing) — matching the per-owner mapper design.

The four behaviour paths are traceable end-to-end through declared edges only. The three added modules each earn their existence: `model` removes the public-API pressure, `replay-schema` removes the replay-versus-implementation coupling, `interaction` gives placement an owner. This is not gratuitous modularization — each was forced by a specific Review #1 defect.

Implementability caveats, both minor and both recorded as NEW-L1: the `model`-declared clearance port's region payload type has no named owner, and the new-type rule's "cross-boundary port with a documented owner" branch is the one clause loose enough to permit drift over time. Neither prevents Phase 1; the Phase 1 boundary tests will surface the first immediately.

---

## PUBLIC API BOUNDARY

The four surfaces the brief asks about are cleanly separated and the separation is enforced by the graph, not just described:

- **Public integration contracts** — `api`: `PapeaService`, `PublicPlayerId`, `CoverageSummary`, `EvidenceSummary`, `ExpiringExemptionRequest`, `DeclaredEffectNotice`. Zero dependencies; semantic-version compatibility promise; only `core` (implementing the facade) and `platform-paper` may depend on it.
- **Internal domain model** — `model` for shared immutable identities, geometry values, coverage summaries and narrow ports; engine-specific types stay in their owning modules. No third-party compatibility promise.
- **Replay schema** — `replay-schema`, independently versioned with its own decode/migration compatibility.
- **Module-local implementation** — everything else, reachable only through declared edges.

Placement of the brief's specific concepts: player identifiers → `api.PublicPlayerId` publicly, `model` SessionId/epochs internally; position/vector/AABB/`SafeAnchor` → `model`; simulation results → simulation-local; collision results → collision-local, with only a small `model` `ClearanceResult` crossing via the port; evidence and violations → evidence-local, redacted `EvidenceSummary` separately mapped for `api`; replay records → `replay-schema`; world revisions → `model` `RevisionId` with `WorldView`/`ChunkSectionSnapshot` owned by world; network state and coverage → `model` immutable summaries with mutable ledgers in player; packet observations → protocol, "never exported via api"; mitigation commands → `MitigationIntent` in mitigation, with a narrow `model` `PlatformEffectRequest` as the execution port payload.

A future engineer can decide where a new type belongs from the rule alone: external operation → `api`; archival → `replay-schema`; internal value or documented port → `model`; engine result/query/mutable state → owner. "Two consumers alone never qualify" is stated, and I confirmed the old heuristic appears nowhere in the corpus. `api` is not a dumping ground — it shrank from an implicit everything to six named types with seven explicit exclusions.

---

## PACKET COMMIT MODEL

Sound at specification level. The certified frontier can only advance through `APPLIED`/`TRANSFORMED_APPLIED` with all predecessor dependencies resolved; `REJECTED` discards speculative effects and invalidates descendants; `FAILED`/`UNKNOWN`/timeout/ID-mismatch/contradictory-late-outcome all force `UNCERTAIN_PIPELINE_OUTCOME`. A cancelled packet remains in the journal as a diagnostic claim but "no certified promotion references it as applied", and certification cannot be resumed by skipping the record and trusting its successor. Mutation reconciles from the final payload with both digests retained and never promotes the original by identity; one-to-many replacement requires explicit replacement children or `UNKNOWN`. Journal bounds are 256 attempts / 256 KiB / 500 ms, with expiry removing certification eligibility rather than granting it. Outcome consumption never blocks, so there is no outcome-queue deadlock. Provisional entries die with their session on disconnect and with the plugin on disable; late callbacks are rejected by epoch.

Defence in depth is present at three layers: packet (P2), state (`MovementState` keeps claim, provisional, accepted projection and certified set separate, with "no anchor promotion without outcome"), and evidence ("Provisional or rejected inbound transitions cannot generate actionable deterministic evidence, regardless of solver completeness").

The disclosed cost is real and correctly surfaced rather than buried: certification requires a correlated authoritative outcome provider at the Paper dispatch boundary, no library is assumed to supply one, and if none can be qualified the product ships capture-only with no enforcement path. That is the top entry in the project's own risk table and in Phase 1 criterion 3.

---

## SIMULATION COMPLETENESS

Verified against the brief's required mapping. Witness → VALID. Sound exclusion of the complete admissible domain → INVALID. Budget exhaustion → `UNCERTAIN_SEARCH_INCOMPLETE`. No candidate after incomplete search → UNCERTAIN ("An empty container caused by dropping nodes is not an empty feasible set"). Unknown relevant physical input → UNCERTAIN (force/world/property/entity coverage flags, `UNCERTAIN_EXTERNAL_FORCE`, `UNCERTAIN_ENTITY_NEIGHBORHOOD`, `UNCERTAIN_BLOCK_BEHAVIOR`, `NUMERIC_PROFILE_UNVERIFIED`, `DeclaredUnknownMotion` "cannot support INVALID").

I audited every INVALID assertion in the corpus. The exclusion condition is stated once and precisely, and every other mention defers to it or restates the prohibition. I could not construct a path where budget exhaustion, candidate truncation, branch pruning, coverage loss or an unknown external force yields INVALID. Unsplit cells remain in the covering forest as conservative supersets, so truncation blocks exclusion rather than enabling it. Certification dependency is additional: "Neither result may promote a certified frontier before the packet outcome resolves."

The strongest safety property is temporal: INVALID is not enabled at all — "not… even for the pilot" — until Phase 5/6 publishes target-profile case counts and per-operator transfer proofs under independent review.

---

## CONSERVATIVE ENVELOPE

Contract complete against all nine elements the brief lists. The over-approximation property is explicit and correctly directed: widening produces UNCERTAIN and false negatives and is always permitted; narrowing is never permitted; unbounded collision response unions all contact cases or returns top/UNKNOWN rather than assuming free space; unknown legitimate unbounded force sets coverage false "even if a finite cached envelope exists". Envelopes may exclude and prune and continue a frontier; they may not establish a witness, reconstruct keys, resolve an application tick or justify an eye point by containment. `O intersects gamma(A)` is explicitly "possible, not proven valid". Containment is a checkable formula with a sampling regression (TC12) plus a required per-operator argument and independent review, with sampling correctly disclaimed as non-proof. Phase 5 owns operators, Phase 6 owns solver/merge/refinement, and profile changes invalidate proof IDs.

---

## EXTERNAL FORCES

The `ExternalMotionInfluence` taxonomy (Decision S2) covers every source the brief enumerates: entity knockback, explosion/TNT/creeper/end crystal, projectiles, fishing rod, plugin velocity (`VelocityDelta`/`VelocityReplace`); firework and riptide (`ModeAcceleration`); pistons, moving support and moving border (`KinematicDisplacement`); players/mobs/boats/minecarts (`ContactPush`); and unqualified custom plugin movement (`DeclaredUnknownMotion`).

The critical invariant holds: "Unknown side/rule/source completeness yields `UNCERTAIN_EXTERNAL_FORCE`", `DeclaredUnknownMotion` "cannot support INVALID", and MOVEMENT_SIMULATION's completeness certificate includes force coverage — so it propagates into the exclusion condition rather than sitting in an unread field. Double-application is prevented ("A projectile/fishing event that already caused a captured velocity must not add a second guessed impulse"; "Common source IDs prevent double application"), and label-based inference is forbidden ("never infer vector solely from source label"). Explosion block changes are recorded separately from explosion motion. Each mechanism carries source/correlation ID, execution side, causal wire/server revision, vector or bounded state, operation, application interval, mode and coverage.

---

## ENTITY PUSHING

Owner and model are explicit (W3: world supplies `NearbyMotionEntities`, version defines rules, physics applies, collision owns geometry). The bounded relevant-neighborhood strategy is exactly as the brief hopes: a spatial index over the conservative swept player envelope expanded by the profile's maximum push/contact radius, capped at 32 entities and 2048 operations per step charged against movement work — **no global entity simulation is required**, and the spec says so. Players, mobs, boats, minecarts and other version-defined push/solid entities including shulkers participate. Unknown relevant entity state degrades completeness without exception, and "Omission from a capped list never proves absence." Solid-entity boxes also enter `MovementQuery` with provenance kept separate from push. Crowd qualification covers 5/15/30 plus overflow.

---

## BLOCK PHYSICAL PROPERTIES

Collision geometry and physical response are separate contracts with separate owners, separate caches, separate work budgets and separate fixtures. `BlockBehaviorQuery` supplies friction, speed/jump factor, bounce, inside drag, fluid flow and column force, climb and support behaviour, and special acceleration, each with KNOWN/AMBIGUOUS/UNKNOWN coverage. Sampling of support faces, fluid volume and inside-body cells is version-defined and capped at 512 cells/step. `MovementQuery` is explicitly forbidden from computing friction or inside drag. Version abstraction covers the properties: a profile declares `BlockBehaviorQuery` definitions, and the capability table gates walking/contact on friction/drag/bounce plus relevant pushes, with everything starting UNVERIFIED.

---

## WORLD/ENTITY COVERAGE

Six independent dimensions — `OrderingHealth`, `PipelineOutcomeCoverage`, `WorldCoverage`, `EntityCoverage`, `ApplicationCoverage`, `PhysicsCapabilityCoverage` — with queries requiring the conjunction of their dependencies, and `availableHorizon` computed as the minimum across timeline, world revisions, entity revisions, application constraints and checkpoint closure.

All three brief scenarios verified. *Mid-session enable:* `OBSERVE_ONLY_BASELINE_MISSING`; complete observer knowledge cannot be declared; only newly observed baselines make regions eligible (TC09, 300 sessions). *Eviction:* "A delta/current server snapshot cannot restore an evicted observer baseline"; re-entry without resend leaves the region unknown (TC10). *Gap recovery and resynchronization:* ordering health may return to HEALTHY while the other dimensions do not — stated in THREADING, WORLD_MODEL and TIMELINE independently; "A resync request may be refused/no-op; only observed outcomes count."

Conditions required before complete movement exclusion can resume: a newly observed complete baseline or qualified equivalent for the region, a reconciled supported frontier, certified pipeline outcomes for the dependency prefix, and the conjunction of dependent coverage dimensions true. Fake-block precedence survives all of it.

---

## NUMERICAL DETERMINISM

Policy addresses every item the brief lists. The determinism promised is precisely scoped: bitwise reproducibility of verified replay **only on conformance-qualified hosts**, with explicit refusal (`INCOMPATIBLE_NUMERIC_ENVIRONMENT`) otherwise, and the cross-host property labelled a qualification requirement rather than an achievement. Capsule metadata carries profile/version, client and runtime provenance, table bytes and digests, algorithm IDs, Java vendor/version, OS/architecture, conformance-vector digest, math-library profile and rounding rules; OS equality is not required where conformance proves identical semantics.

Crucially for the brief's last question: reproducibility is **not** allowed to displace vanilla fidelity. The priority order is fidelity for concrete stepping, outward containment for exclusion, exact reproducibility for verified replay, with formatting excluded from decisions — and a blanket StrictMath substitution is explicitly rejected as "reproducibility of the wrong arithmetic". Tolerances still must name their source, and an unavailable faithful algorithm yields `NUMERIC_PROFILE_UNVERIFIED` rather than a widened epsilon.

---

## REPLAY ARCHITECTURE

The boundary the brief asks for exists exactly: live domain state → owner's `ReplayMapper` → `replay-schema` DTO → codec/persistence, with restore validated and dispatched back through `core`'s `ReplayDriverPort`. No live implementation object is serialized; `core` "does not flatten private objects with reflection"; "Diff never assumes implementation object equality". Schema stability is independent of both `api` and the internal model, with its own versioned decode/migration compatibility, explicit migration, and `RegistryDictionary` plus numeric metadata in the header so restored canonical digests survive changed runtime intern IDs (TC19). Untrusted-input hardening (byte/nesting/count limits, closure validation before allocation, no Java deserialization, path-traversal and decompression-bomb rejection) is retained from the original design.

---

## REPLAY MEMORY

Structurally bounded; the 16 MiB × player-count failure mode is impossible by construction because there is **no per-session replay buffer at all**. Replay lives in a fixed global 96 MiB partition: 48 MiB adaptive rolling, 32 MiB promotion/pin liability, 16 MiB I/O.

The brief's 1000-player test: rolling share `q = min(256 KiB, floor(48 MiB / activeSessions))` gives ~48 KiB per session at N=1000, so total rolling stays at 48 MiB regardless of N; below a 16 KiB share the system records bounded metadata only and reports `NoRollingCapture` (reached around N≈3072). Promotion is capped at 8 MiB/capsule with at most four concurrent promotions — exactly 32 MiB worst case. I/O is charged by retained/uncompressed bytes, with "compression does not create fictitious free quota" closing the obvious accounting loophole. The architecture remains bounded at every N I tested.

Priority, eviction and degradation are specified (detection-triggered promotion outranks rolling retention but never bypasses the global cap; existing promotion reservations are protected until deadline; pressure evicts oldest unpromoted complete ranges then rejects new promotions; oversize closures are rejected, never partially declared complete). Persistence and shutdown are handled (bounded drain, unfinished captures marked incomplete, leases released only after users finish, hung-worker memory stays charged). No capacity numbers are claimed. See NEW-M1 for the one precision gap.

---

## INTERACTION ENGINE

Ownership is coherent and complete for the brief's list: placement, block use/break and non-attack entity interaction belong to `anticheat-interaction`; attacks stay in combat; scaffold behaviour features stay in checks. `ActionContext` covers attempt/outcome IDs, certified attacker pose/rotation provenance, target block/entity incarnation, face, cursor/hit vector, hand/item/window state, action sequence and timing, observer world/application view and profile — every item the brief enumerates including cursor/hit vector, target face, rotation relationship and action timing.

Dependency direction is correct against every module the brief names: `interaction → world`, `→ collision`, `→ version`, `→ player`, `→ model`, `→ replay-schema`; consumed by `checks`; no edge to or from `combat`; no edge to `protocol` (action families arrive through the normalized path) and none to `evidence` (findings flow via checks). World keeps overlay storage; interaction returns a `ProposedOverlayDelta` that core asks world to apply/confirm/reject only at the outcome boundary, so "A rejected placement cannot permanently mutate the baseline."

No duplicated ray engine: both interaction and combat call collision's `RayQuery`, and interaction is told to "Use combat's documented eye-source policy but not combat implementation classes; share model provenance values and collision ray primitives." Bounds match combat's (64 scene alternatives, 4096 geometry units per action).

---

## BACKPRESSURE

The overload strategy satisfies every prohibition. No unbounded queue growth — count and byte bounds at ingress, bridge, bulk pool, shared store, replay, actor, journal and effect queues. No silent critical loss — failed ingress latches a transport generation with `firstMissingSeq` and a reserved control lane, and "Do not drop only a movement packet and continue simulation as if contiguous." No reordering — sequence assignment and FIFO offer are one event-loop operation. No Netty blocking — the event loop may never wait for the actor, and saturated main-thread queues reject with outcomes rather than blocking the shard. No certified-state corruption — P2 plus the generation barrier plus commit-or-quarantine.

Work quotas, degradation modes, coverage reduction, recovery and observability are all present (H9, H11, M9). Overload produces reduced coverage and UNCERTAIN, never false INVALID: "Longer main-thread stalls produce coverage loss, not violations."

Both clarifications I asked for in Review #1 are now stated in the document rather than left implicit: "Papea2AC observes rather than proxies: overload is coverage loss, not TCP backpressure; Paper may continue processing traffic", and Resume restores ordering only, with the other coverage dimensions not reset.

---

## OPERATIONAL SAFETY

All six items the brief lists are architectural contracts in the specifications, not remediation-report prose. Sustained work quotas — PERFORMANCE budget row plus THREADING fairness section plus CONFIGURATION fields. Failed-shard cleanup — THREADING lifecycle state machine with the supervisor lease ledger. Per-session coverage reporting — `CoverageSummary` in PLAYER_STATE with the `CoverageDegraded` threshold. Server-wide suspension — MITIGATION G2 with thresholds and the re-enable rule. Durable capture prerequisite — EVIDENCE and REPLAY R2, `DURABLE_REQUIRED` as "the only initial automated-effect policy", with `CaptureDeadlineMiss` counted on failure. Measurement gate for setbacks — the ≥99 %-within-400 ms Phase 9 gate.

The specific invariant the brief asks me to confirm holds: if the durability gate is not met, effects stay off. EVIDENCE: "If it fails, automated mitigation stays disabled; a new policy needs review." REPLAY: "Failure keeps effects disabled, not automatically switched to memory-only capture." No timing numbers are required before implementation; the gate exists, names its scenarios and its threshold, and is measurable in Phase 9.

---

## VERSION SUPPORT

Re-verified the release-day facts independently in Review #1 and they remain accurately stated. Support is now determined by `AdapterBoundaryProbe` plus a qualified ruleset, with known-good build hashes as provenance that cannot bypass live checks, and unknown builds admitted only into a matching qualified compatibility class — otherwise explicit OBSERVE_ONLY. "Successful reflection alone does not prove client semantics", and a test must remove a required hook or family and show capability loss. The capability table (walking/contact, fluids/climbing, elytra+firework, riptide, boats/minecarts/mounts, block-driven movement, moving border, external forces) starts entirely UNVERIFIED, and "Modes cannot be VERIFIED until all required motion sources have capture/application/fixture coverage."

The product-scope honesty I asked for in U5 is present: "Initial product envelope is deliberately native Paper/vanilla Java only, potentially narrower than many public servers need. Phase 25 prioritizes measured demand for translation/proxy profiles; no timetable or broad compatibility claim is made."

---

## TESTING/REGRESSION COVERAGE

TC01–TC25 cover every scenario I listed, with delivery gates attached and the standing caveat "no test is satisfied merely by adding its name here." All eight test boundaries Review #1 identified as missing are closed: block properties (TC07), entity push (TC06), cross-platform replay determinism (TC13), foreign packet handlers (TC02), correlated multi-session fault (TC17), shard death including the hung/terminated distinction (TC16), the native capture harness, and numeric coverage floors.

The coverage floors are the part I care most about, because they are what prevents an inert all-UNCERTAIN validator from reading as success. They are now numeric and anti-gameable: Phase 6 pilot requires 100 % INVALID on analytically impossible disjoint-envelope cases within original limits and 100 % no-witness on sampled envelope intersections lacking a trajectory; Phase 8 requires zero false INVALID on benign, ≥95 % VALID per supported benign class and ≥80 % INVALID per predeclared supported adversarial class with independent ground truth; Phase 7b requires ≥90 % definite outcomes at 50/150/300 ms with zero false INVALID. "Qualification corpus is fixed before tuning and held-out scenarios are independently labeled", and "Unsupported classes cannot be removed from product coverage reporting to improve the score."

Fixture provenance discipline is unchanged and still correct: independent oracles, no deriving expected and actual from the same simulator, no fixture deletion or loosening without reviewed explanation, no Intave fixtures or cheat client as oracle.

---

## STATIC CONSISTENCY

- **DAG:** re-derived; acyclic; row order topological; all behaviour-path edges present. No cycle, unknown provider, api pollution, platform leakage or production→testing edge.
- **Links and fences:** every local Markdown link across README and all 25 documents resolves; all code fences balance. Zero broken.
- **Manifest:** all 31 entries match raw bytes and sizes; composite reproduces; every normative file covered; Review #1 included.
- **Change artifact:** I regenerated `review2-changes.diff` independently — 221,770 bytes, byte-identical. 4 new documents, 22 revised, all four skeleton files unchanged, index untouched, no HEAD.
- **Stale text sweep:** I searched specifically for pre-remediation statements. The old 16 MiB/session reservation appears only in its own removal sentence. The two-consumer API rule is gone entirely. The 512 MiB shared store and 128 MiB shape cache are gone (now 96/32). "256 live candidates / 4096 candidate-step evaluations" is superseded by "256 nodes, 4096 transfer/step evaluations". The 10-second coverage horizon survives only as explicitly diagnostic marker retention that "do not extend world/entity/timeline coverage". The impossible `git rev-parse HEAD` criterion is gone from all specifications. The old 17-module count appears only in the A1 sentence explaining the delta to 20. **No contradictory stale text found.**
- **Terminology and state names:** consistent across documents — `CAPTURED`/`PREPROCESS_OBSERVED`/`OUTCOME_PENDING`/`APPLIED`/`TRANSFORMED_APPLIED`/`REJECTED`/`FAILED`/`UNKNOWN`; `OBSERVE_ONLY_BASELINE_MISSING`; `OBSERVE_ONLY_UNTIL_RECONNECT`; `RUNNING`/`FAILED_FENCED`/`DRAINING_LEASES`/`RETIRED`; `DISABLED`/`OBSERVE_ONLY`/`ENFORCE`; and the `UNCERTAIN_*` reason family. Budget numbers agree across PERFORMANCE, PLAYER_STATE, WORLD_MODEL, MOVEMENT_SIMULATION, COMBAT_MODEL and INTERACTION_MODEL everywhere I cross-checked.
- **Arithmetic:** I recomputed the global partition (96+32+96+32=256), the replay partition (48+32+16=96), the floor tier composition (512+768+256+256+256=2048 KiB), all ten scale-table cells, the retention derivations (3000 records / 768,000 bytes; 2.048 s at both bounds at 2000/s; 2.048 s for the 512 KiB floor), the entity estimate (30×60×128=230,400 B=225 KiB), the view-distance columns (441/1089) and volumes (41.34/102.09 MiB), and the solver pilot node count (1+4+16+64=85). **Every figure is correct.**
- **Roadmap ownership:** each new contract has a named phase, and the "Concrete ownership additions" paragraph maps envelope/solver, force observation/application, entity neighborhood/push, block properties, baseline/coverage, interaction, replay DTO/mappers and numerical profile/fidelity to specific phases without introducing new phase numbers.
- **Phase gates:** Phase 0 criteria updated to 20 modules and four behaviour paths; Phase 1 criteria rewritten so criterion 1 is satisfiable and criteria 3/4/7/8/9 carry the B3/H8/H12/M10/H10/H11/M1 obligations. The risk table is re-ranked as I recommended — ACK-as-application downgraded to a guarded implementation risk, all-UNCERTAIN elevated to High, outcome-provider availability promoted to the top implementation gate.
- **One wording observation, not a finding:** COMBAT_MODEL's "Bounds and outputs" section still says pending attacks may wait "for at most the retained history horizon", which is looser than the normative rule stated twice elsewhere (B2 and the PERFORMANCE exhaustion column both specify the minimum-dependency horizon). These are not contradictory — under the revised model the retained history horizon *is* that minimum — but the older phrasing could be tightened when the document is next touched.

---

## NEW FINDINGS

### NEW-M1 — source of replay capsule prehistory is ambiguous, and its scaling interacts with the durability gate

- **SEVERITY:** NEW MEDIUM
- **DOCUMENT(S):** `REPLAY_SYSTEM.md` (capture lifecycle, R2), `PERFORMANCE.md` (replay allocation lifecycle), `EVIDENCE_MODEL.md`, `TESTING.md` (TC25)
- **PROBLEM:** Two rings can supply a capsule's prehistory and the documents do not say which does. The per-session timeline ring is N-independent (512 KiB floor / 1 MiB target, three-second target). The replay rolling share is N-dependent: `q = min(256 KiB, floor(48 MiB/activeSessions))`, which is 256 KiB up to about 192 sessions but roughly 48 KiB at 1000 sessions — about 0.19 seconds at the declared 1000 records/s, 256 bytes/record envelope. REPLAY says "The timeline ring is distinct but globally accounted" without stating which one the capsule draws from. Separately, TC25's gate (">=99% complete durable captures within 400 ms under the declared qualification load") does not name the session count at which it must hold, while one of the two candidate sources degrades linearly with that count.
- **WHY IT MATTERS:** If prehistory comes from the rolling share, capsule completeness — and therefore `DURABLE_REQUIRED` effect eligibility — silently degrades with player count, and a gate satisfied at 50 sessions would not hold at 500. If it comes from the timeline ring, the property is N-independent and the rolling share is purely forensic. Both are defensible designs; the ambiguity is the problem, because it determines whether the Phase 9 measurement generalises. This is a gate-precision issue in a Phase 9/10 subsystem, not a Phase 1 obstacle.
- **RECOMMENDED CHANGE:** State which ring supplies capsule prehistory (and if both, the precedence). Add the session count(s) to the TC25 gate, or state that the gate must hold at the maximum configured certified-session capacity for the tier under test. TC14 already exercises 50–1000 synthetic sessions under global replay pressure, so the measurement path exists.
- **ACCEPTANCE CRITERIA:** REPLAY_SYSTEM names the prehistory source and its N-dependence; TC25 names the session count(s) at which the durability threshold must hold; if the source is N-dependent, PERFORMANCE states the session count above which a complete capsule can no longer be expected.

### NEW-L1 — clearance port payload type has no named declaration owner

- **SEVERITY:** NEW LOW
- **DOCUMENT(S):** `ARCHITECTURE.md` (A3 category table, path 2), `MITIGATION.md`, `COLLISION_ENGINE.md`
- **PROBLEM:** The setback clearance path passes "a bounded immutable destination region" (MITIGATION) / "a bounded server-current destination view captured on the main thread" (COLLISION) from `platform-paper` through the `model`-declared `SafeAnchorValidationPort` into mitigation's validator and then into collision. The return type is named (`model` `ClearanceResult`); the input type is not, and it is not in the A3 category table. Since `model` is a dependency-free leaf, the port signature cannot name a `world`-declared type such as `WorldView` or `ChunkSectionSnapshot` without creating a cycle. At least one legal resolution exists inside the current DAG — declare a small immutable region value in `model`, built by a `world` factory (world → model is allowed) and consumed by collision — so this is a missing declaration, not a design flaw. Relatedly, the new-type rule's `model` branch, "internal primitive/immutable value **or cross-boundary port with a documented owner**", is the one clause open enough to let `model` accrete ports over time.
- **WHY IT MATTERS:** An implementer who resolves it the wrong way (declaring the port in `model` with a world-typed parameter) hits a compile error and may be tempted to move world types into `model`, which would begin eroding B2's separation. The failure is loud and Phase 1's boundary test catches it immediately, so the risk is small.
- **RECOMMENDED CHANGE:** Add the destination-region type to the A3 category table with its declaring module, and name it in MITIGATION's clearance sentence. When `model` gains its second or third port, restate the ports it is allowed to hold as an enumerated list rather than a rule.
- **ACCEPTANCE CRITERIA:** The A3 table names the clearance input type and its owner; the Phase 1 architecture check asserts that `model` declares no type requiring a dependency.

**No new blocker and no new high finding.** I looked specifically for contradictions introduced by the remediation, paths that cannot be implemented as specified, surviving false-positive routes, and violated safety or performance invariants, and found none beyond the two above.

---

## PHASE 1 READINESS

Phase 0 architecture is ready. The specifications answer the questions Phase 1 will ask first — the module graph and its boundary rules, the `api`/`model`/`replay-schema` split, the observation boundary and provisional/outcome model, the observation matrix, the quota registry and admission equation, the configuration canonical form, the numeric identity and CI restriction, and the supervisor/global-control machinery. Phase 1's acceptance criteria now carry the Review #1 obligations as concrete demonstrations rather than aspirations.

Nothing from Review #1 remains as a Phase 1 precondition. My Review #1 gating list is fully discharged: B1, B2, B3, B4, H8, H12 and H13 were all required before Phase 1 begins, and all seven are resolved. H9, H10 and H11 were required before Phase 1 exits and are now specified with Phase 1 tests (TC16, TC17, TC24) attached. H1, H2, H3, H4, H5, H6, H7 and H14 were gated to their subsystems and remain correctly gated there with named phases and numeric exit criteria.

Tracked into later phases: NEW-M1 (Phase 9, before Phase 10 effects), NEW-L1 (Phase 1 architecture check), and the empirical obligations the remediation lists honestly — solver usefulness and containment proofs (Phase 5/6), world-model representation measurements (Phase 3), entity execution-side semantics (Phase 5), cross-host numeric conformance (Phase 1 recorder, Phase 5 physics), retained-heap and tier fit (Phase 24), capsule size and durability (Phase 9), and the outcome-provider qualification that governs whether enforcement is possible at all.

The largest residual risk is not an architecture defect and cannot be closed by more specification: if no correlated authoritative outcome provider can be qualified at the Paper dispatch boundary, the product is permanently observation-only. Phase 1 criterion 3 handles this correctly by requiring the fallback to be shipped and documented rather than worked around.

**This approval satisfies the senior architecture gate only.** Phase 1 must not begin now. The human project owner must approve, a Phase 0 baseline commit must then be created from exactly the bytes bound by manifest composite `1a1b987df59ef9fd411528296c2150f2b4fbbb3417ec36cddd286f1c65f64155`, its committed blobs must be verified against that manifest, and the actual revision must be recorded in the separate baseline receipt. Any specification byte changed after this approval requires a fresh manifest and review of that change.

---

## FINAL VERDICT

The remediation did what a remediation should do: it fixed the defects, it argued back where my findings were overstated, and it was right both times it did so. The module graph is implementable rather than merely acyclic. The public boundary is smaller and sharper than before. The packet model now makes downstream cancellation a coverage problem instead of a truth problem, and it refuses to certify on periodic topology checks — a hole I had left open. Exclusion moved from an arithmetically impossible enumeration to a staged constraint solver whose budget matches a declared pilot domain, with INVALID disabled entirely until the transfer proofs are reviewed. Memory is globally admitted with a published equation I recomputed and a floor the system refuses to silently breach. The mechanics that dominate false positives — explosions, entity push, block physical properties, vehicles, tick control — now have owners, contracts, budgets and fixtures.

Everything that cannot be settled without an implementation is scheduled with a named phase, a named test and, where it matters, a numeric threshold. Nothing is claimed as measured that was not measured, and the one risk that could make the whole product observation-only is stated at the top of the project's own risk table rather than buried.

Two findings remain, both minor and both scheduled. Neither blocks Phase 1.

PHASE 0 REVIEW #2: APPROVE

- **B1–B4 STATUS:** All resolved. B1 VERIFIED RESOLVED; B2 VERIFIED RESOLVED; B3 SUPERSEDED BY A STRONGER DESIGN; B4 SUPERSEDED BY A STRONGER DESIGN.
- **H1–H14 STATUS:** All resolved. VERIFIED RESOLVED — H2, H3, H4, H5, H6, H7, H8, H9, H11, H13. SUPERSEDED BY A STRONGER DESIGN — H1, H10, H12, H14. None NOT RESOLVED; none PARTIALLY RESOLVED.
- **NEW BLOCKERS:** None.
- **NEW HIGH FINDINGS:** None.
- **MEDIUM/LOW REMAINING:** NEW-M1 (capsule prehistory source and TC25 gate scale — Phase 9, before Phase 10 effects) and NEW-L1 (clearance port payload type owner — Phase 1 architecture check). All 13 original MEDIUM and 6 LOW findings, all 12 RT findings and all 6 unnumbered notes verified resolved or correctly tracked; none required escalation.
- **MODULE DAG STATUS:** Verified independently. 20 modules, 19 production, zero cycles, row order topological, all four behaviour paths traceable through declared edges, no api pollution, no platform leakage, `checks` isolation intact.
- **PACKET COMMIT MODEL STATUS:** Sound at specification level. Cancelled, mutated, failed, unknown and timed-out attempts cannot advance the certified frontier; certification cannot resume by skipping a record; no outcome-queue deadlock; provisional state bounded and released on disconnect and disable.
- **SIMULATION COMPLETENESS STATUS:** Sound. No path from budget exhaustion, truncation, pruning, coverage loss or unknown external force reaches INVALID. INVALID is additionally disabled entirely until Phase 5/6 transfer proofs pass independent review.
- **REPLAY MEMORY STATUS:** Structurally bounded. No per-session replay buffer exists; fixed 96 MiB global partition with N-adaptive rolling share, capped promotion reservations and retained-byte I/O accounting. Bounded at 1000 players and beyond.
- **PHASE 0 ARCHITECTURE STATUS:** APPROVED at the senior architecture gate, bound to manifest composite `1a1b987df59ef9fd411528296c2150f2b4fbbb3417ec36cddd286f1c65f64155`.
- **HUMAN APPROVAL STATUS:** Outstanding. Required before any commit or Phase 1 work.
- **GIT BASELINE STATUS:** No commit exists; `master` unborn by the approved sequence. Baseline commit, committed-blob verification against this manifest, and the separate revision receipt are all outstanding.
- **PHASE 1 GATE STATUS:** CLOSED. Opens only after human approval, the baseline commit and its recorded verified revision. Phase 1 has not been started and must not be started now.
