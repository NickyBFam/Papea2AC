# Phase 0 senior architecture review

| Field | Value |
|---|---|
| Reviewer | Claude (independent senior architect / senior code reviewer) |
| Implementer | GPT 6 Astra |
| Phase | 0 — Architecture and technical specification |
| Review date | 2026-09-15 |
| Reviewed tree | `master` has **no commits**; reviewed the working-tree content of `README.md` + `docs/*.md` (22 files). Composite digest of the sorted per-file SHA-256 list: `40aabc61f7e8b01f5cd2d709c234ced1687b2dbb8329c208c68574c11bd1da58` |
| Per-file digests | `README.md c3f8ab14…`, `ARCHITECTURE.md 1b2632dc…`, `PACKET_PIPELINE.md eefe9c73…`, `THREADING_MODEL.md cb54edbe…`, `PLAYER_STATE.md e2253ece…`, `PLAYER_TIMELINE.md 0272b4d1…`, `WORLD_MODEL.md 47b857bb…`, `COLLISION_ENGINE.md 27d84df5…`, `MOVEMENT_SIMULATION.md a1163c44…`, `INPUT_RECONSTRUCTION.md c53d4dfd…`, `LATENCY_COMPENSATION.md 05bfafd2…`, `COMBAT_MODEL.md c28f0d4f…`, `EVIDENCE_MODEL.md b4fbf2f4…`, `MITIGATION.md f019a0a1…`, `REPLAY_SYSTEM.md 1c093edf…`, `TESTING.md 0960d08b…`, `PERFORMANCE.md 10788477…`, `VERSION_SUPPORT.md da608166…`, `AI_DEVELOPMENT_RULES.md 056aa266…`, `INTAVE_REFERENCE_ANALYSIS.md 75ea760c…`, `ROADMAP.md 905d5f91…`, `PHASE_0_REVIEW.md 3390cf24…` |
| Automated artifacts | None. Phase 0 changed no code, so no build, test or benchmark output exists. Every runtime statement in this review is analytical, not measured. |
| Independent verification | Mojang `version_manifest_v2.json` re-fetched: `latest.release = 26.3`, released 2026-09-15T11:23:02Z — matches VERSION_SUPPORT. Intave re-inspected at pinned revision `8f3c430bbf1e6c5daaa6850afe691091160a399a` via the GitHub API: full tree (1754 entries), `README.md`, `docs/STRUCTURE.md`, `docs/BLOCK_SYSTEM.md`, `docs/CHEATSHEET.md`, `.github/workflows/tests.yml`. Repository files `pom.xml`, `plugin.yml`, `Papea2AC.java`, `.gitignore` read directly. |

---

## EXECUTIVE SUMMARY

This is a strong Phase 0. The epistemic core of the design is correct and unusually disciplined, and several things anticheat projects routinely get wrong are right here:

- **VALID / UNCERTAIN / INVALID with a completeness certificate** (ARCHITECTURE A2, MOVEMENT_SIMULATION M2) genuinely separates "we proved this impossible" from "we ran out of budget". Budget exhaustion cannot become a negative proof. This is the most important property in the package and it is specified correctly.
- **ACK is not application** (LATENCY_COMPENSATION N1) is handled more carefully than Astra's own risk table implies: marker ACKs supply receipt/RTT only and *must not* advance a physics-application frontier until a version-specific guarantee profile exists. The residual problem is coverage cost, not false positives — see H3 and H5.
- **Observer-specific world and entity history** (WORLD_MODEL W1, COMBAT_MODEL B1) is the right model and the only one that handles Paper anti-xray and ghost blocks correctly; validating against the server's live world would false-positive on every obfuscated section.
- **Evidence causal families** (EVIDENCE_MODEL E1) correctly forbids "one simulation failure becomes Speed + Fly + NoSlow = three strikes".
- **Mitigation as expiring conditional intents with an explicit linearization boundary** (MITIGATION G1), including the honest admission that an already-started platform call cannot be undone.
- The **dependency table is genuinely acyclic**: I re-derived the topological order independently and all 16 production modules validate, with `testing` correctly test-scoped. That claim is accurate, not merely asserted.
- **Version honesty** is accurate and independently verified: MC 26.3 released today, Paper 26.3 ALPHA, stable Paper 26.2, Java 25. Refusing to claim latest-version support is correct.

The problems fall into three groups.

**First, the module graph is acyclic but not implementable as written.** Four documented behaviours have no legal path through the declared adjacency list. `core` is the composition/DI root but may not depend on `collision` or `physics`, which it must instantiate. `mitigation` and `platform-paper` must validate a setback destination's collision clearance but neither may reach the collision engine. Block placement/interaction ray validation (Phase 18) has no owning module. And `replay` must encode, decode and diff player-component, world, evidence and mitigation state while depending on none of those modules — which forces the entire serializable domain model into `api`, whose contents are governed only by a heuristic. Phase 1's *first* deliverable is this DAG plus CI boundary enforcement, so it has to be right before Phase 1 starts. (B1, B2)

**Second, several quantitative budgets contradict the qualitative guarantees they support.** The simulation budget (256 live candidates, 4096 candidate-step evaluations, 3 inferred steps) is smaller than one step of the input domain crossed with pending-update application branches, so an INVALID verdict with all completeness flags true is close to unreachable by forward enumeration — the enforcement story degenerates to permanent UNCERTAIN. The timeline ring (4096 records / 2 MiB / 10 s) cannot hold ten seconds of a busy session once outbound entity traffic counts as committed events, and entity history (64 revisions ≈ 3.2 s at 20 Hz) is shorter than the 10 s horizon that combat and marker resolution are written against, so the effective horizon is the minimum rather than the advertised one. The per-session 16 MiB reservation times the stated 1000-player target is 16 GiB against a default global budget of `min(1 GiB, 25% of heap)`. None of these is fatal; all are load-bearing numbers that currently disagree with the prose. (H1, H3, H13)

**Third, there are specification holes in exactly the mechanics that dominate false positives on production servers.** Movement-affecting block *properties* — friction, speed factor, bounce, fluid flow, inside-block drag — have no contract anywhere; only collision *shapes* do. Entity-vs-entity pushing appears in no document. The outbound observation source list omits explosion knockback, vehicle movement and passengers, camera, respawn/gamemode and server tick-rate/freeze state. Each is a guaranteed mismatch source, not a hypothetical one. (H6, H7, H8)

One finding corrupts truth rather than reducing coverage: **the packet pipeline has no hook-placement invariant and no handling of third-party *inbound* transformation or cancellation.** If another plugin cancels an inbound movement packet after Papea2AC observed it, Papea2AC's committed model and the server's state diverge permanently, and the setback machinery then acts on a state the server never had. Outbound cancellation is handled; inbound is not mentioned. (B3)

Finally, AI_DEVELOPMENT_RULES and Phase 1 gate 1 both require the review to be bound to a fixed revision. `master` has no commits and the Phase 0 documents are untracked, so that gate is currently unsatisfiable. (B4)

**Verdict: REQUEST CHANGES.** No fundamental redesign is needed — the spine is sound. The four blockers are narrow and mostly mechanical, and the HIGH findings are largely "specify the thing you already promised". Gating lists are in PHASE 1 READINESS.

**Where I disagree with Astra's own risk ranking.** The ACK-application risk (their #1) is already mitigated in the text and should be downgraded; the real ACK problem is branch-explosion coverage collapse. "17 modules may add complexity without real isolation" is rated MEDIUM, but the DAG is a blocker for the opposite reason — it is *under*-connected, not over-modularized. "Candidate pruning could omit legal trajectories" is rated HIGH, but the deeper issue is that the stated budget makes sound exhaustion arithmetically unreachable, so unsound pruning is not the binding constraint.

---

## BLOCKERS

### B1 — Module dependency graph cannot express four documented behaviours

- **ID:** B1
- **SEVERITY:** BLOCKER
- **DOCUMENT(S):** `ARCHITECTURE.md` (dependency table, Decision A1), `MITIGATION.md`, `REPLAY_SYSTEM.md`, `COMBAT_MODEL.md`, `ROADMAP.md` (phase 18)
- **PROBLEM:** The adjacency list is acyclic but has no legal path for four required behaviours.
  1. `core` is "composition/orchestration layer … dependency injection" yet its allowed dependencies omit `collision` and `physics`. A composition root must reference the concrete types it constructs. With Gradle `implementation` dependencies those types are not transitively visible through `simulation`, so `core` cannot wire a collision engine or a physics stepper. Either `core` gains the edges or `simulation` is the composition root for them — the document must say which.
  2. `MITIGATION.md` requires `SafeAnchor` to carry "collision clearance" and requires the main thread to "verify destination against current loaded world and platform restrictions" immediately before execution. `mitigation` may depend only on `api`, `player`, `evidence`; `platform-paper` has `world` but not `collision`. Neither can call the shared collision engine, so the clearance check is either re-implemented against Bukkit in `platform-paper` — which `AI_DEVELOPMENT_RULES.md` forbids ("Checks consume shared engines") — or it cannot happen.
  3. `ROADMAP.md` phase 18 and `COMBAT_MODEL.md` ("Placement uses the same eye/ray/world primitives … it does not copy combat geometry") require placement/interaction ray validation. `checks` may not depend on `world`, `collision` or `physics`, so the only module that can host it is `combat`. A module named `combat` owning block placement is an incorrect responsibility boundary that will be relitigated in Phase 18.
  4. `replay` may depend only on `api`, `protocol`, `version`, yet `REPLAY_SYSTEM.md` requires the capsule body to hold "initial coherent checkpoint with component roots and all reachable world/entity/registry data" and the runner to "compare validation status, uncertainty, evidence IDs, intent decisions and checkpoints" — `player`, `world`, `evidence` and `mitigation` types.
- **WHY IT MATTERS:** Phase 1's first deliverable is "Migrate preserved skeleton atomically to the approved Gradle DAG; verify dependency boundaries", with CI "dependency boundary/no-cycle checks". A DAG that cannot compile the documented behaviour gets fixed under schedule pressure by whoever hits it first — usually by dumping types into `api` or adding an unreviewed edge. The declared boundary then stops being a real constraint, which removes the main justification for 17 modules (Decision A1).
- **FAILURE SCENARIO:** Phase 10 implements mitigation. The developer must check that the safe anchor is not inside a block. `mitigation` cannot see `collision`, so the fastest legal fix is `platform-paper` calling Bukkit block/collision APIs on the main thread. Later, the Bukkit-derived clearance rule and the engine's versioned `ShapeKind.MOVEMENT` resolution disagree on a trapdoor. A legitimate player is repeatedly set back into a space they cannot occupy, the loop-breaker fires after three corrections, and enforcement silently disables for that session — with no evidence trail pointing at the divergence.
- **RECOMMENDED CHANGE:** Before the Gradle migration, republish the adjacency table with: (a) the composition root for `collision`/`physics` named, and the edges that follow; (b) a defined path for setback destination validation — either a `SafeAnchorValidator` port implemented in a module that may see `collision` and injected by `core`, or an explicit `mitigation → collision` edge; (c) a decision on placement/interaction geometry — rename `combat` to `interaction`, or add an `interaction` module, and give `checks` a declared path to it; (d) a decision for replay state (see B2).
- **ACCEPTANCE CRITERIA:** For each of the four items the table shows the edge, or names the port and its implementing module. A reviewer can trace `SafeAnchor` clearance, placement ray validation, `core` constructing the physics stepper, and replay checkpoint encode/decode/diff end-to-end through declared edges only, with no step requiring an undeclared import.

### B2 — `api` module contents are undefined, and replay forces the whole domain model into it

- **ID:** B2
- **SEVERITY:** BLOCKER
- **DOCUMENT(S):** `ARCHITECTURE.md` (api row, API package rules), `REPLAY_SYSTEM.md`, `PLAYER_STATE.md`, `EVIDENCE_MODEL.md`
- **PROBLEM:** `api` is specified by a package-name list (`identity`, `observation`, `state`, `geometry`, `validation`, `evidence`, `ports`) plus a heuristic: "An API package must have at least two legitimate consumers; do not turn it into a utility dump." That heuristic constrains nothing, because other documents force a very large type set across the boundary — `replay` serializing component roots, world/entity/registry data, evidence records and mitigation decisions with no dependency on those modules; `player` components holding observer view handles, world revision handles and immutable context refs; `checks` consuming simulation, combat and evidence results. Nearly every domain type will have two consumers and therefore qualify.
- **WHY IT MATTERS:** The transitive closure is essentially the whole cross-cutting domain model. If `api` becomes that, the module split provides almost no isolation: every module recompiles on every domain change. If the team instead keeps `api` small, it will add edges (`replay → player`, `replay → world`, `replay → evidence`, `replay → mitigation`) that push toward a cycle through the reducer port. This is the classic irreversible decision — every later module compiles against whatever `api` becomes in Phase 1.
- **FAILURE SCENARIO:** Phase 1 defines `api` from envelope needs only. Phase 2 adds component snapshots, Phase 3 world handles, Phase 9 evidence. By Phase 21 the replay codec needs a stable wire form for all of them, but those types were designed as in-memory projections with revision handles and interned references, not serializable values. The codec either declares a parallel schema — two models to keep in sync, with the `REPLAY_SYSTEM.md` digest contract silently depending on the translation — or `api` types acquire serialization concerns that `ARCHITECTURE.md` explicitly forbids ("`api` has no Paper, packet-library or serializer annotations").
- **RECOMMENDED CHANGE:** Replace the "two consumers" heuristic with an enumerated content policy for `api`: categories, an example type each, and an explicit exclusion list. Record a DECISION for the replay wire schema: either (i) a codec module owning its own DTOs and mapping functions, with declared dependencies for mapping, or (ii) `api` value types designed as the canonical serializable form with a documented stability and versioning rule. Option (i) is normally right because schema evolution and in-memory representation have different change rates.
- **ACCEPTANCE CRITERIA:** `ARCHITECTURE.md` contains an `api` content policy with enumerated categories and at least three named exclusions. A DECISION record states how replay obtains and compares player/world/evidence/mitigation state, and the module table reflects it. From the documents alone a reader can say which module declares each of: `EvidenceRecord`, `TimelineSlice`, `WorldView`, `SimulationResult`, `MitigationIntent`, `ChunkSectionSnapshot`, `SafeAnchor`.

### B3 — No hook-placement invariant, and third-party inbound cancellation is unhandled

- **ID:** B3
- **SEVERITY:** BLOCKER
- **DOCUMENT(S):** `PACKET_PIPELINE.md` (Decision P1, Ordered processing 1–2, Phase 1 demonstrations), `ARCHITECTURE.md` (security boundary), `PHASE_0_REVIEW.md` (Phase 1 criterion 3)
- **PROBLEM:** Two related gaps.
  1. *No placement invariant.* The spec requires observing "the accepted inbound message or final outbound representation" and asserts that "third-party packet transformations require compatible final-wire observation or affected validation is disabled with a reason" — but never states where in the Netty pipeline the handlers sit relative to the codec and to other plugins, and defines no mechanism to detect that the pipeline changed after installation. Outbound, a handler head-side of the encoder sees encrypted bytes (unusable); before the encoder it sees the packet object, but any handler another plugin installs between Papea2AC and the encoder transforms *after* Papea2AC observed. Inbound, any handler installed between Papea2AC and the server's packet handler transforms or cancels *after* Papea2AC observed. "Final-wire observation" is therefore not achievable in general, only "final relative to a stated boundary" — and there is neither a stated boundary nor an integrity check.
  2. *Inbound cancellation unhandled.* Step 2 covers cancellation outbound only ("State-changing packets cancelled before final send do not become client-visible deltas"). Nothing covers another plugin cancelling or rewriting an *inbound* movement, interaction or acknowledgement packet after Papea2AC committed it.
- **WHY IT MATTERS:** Every other failure mode here degrades coverage (UNCERTAIN). This one degrades truth: Papea2AC's committed model diverges from the server's and stays diverged, so the supported-state frontier, the safe anchor and the acknowledgement ledger are all computed against a state the server never had. It is the only such divergence path in the design, which is why it is a blocker despite being cheap to specify.
- **FAILURE SCENARIO:** A server runs a region plugin that cancels inbound movement packets for players inside a protected area during an event. Papea2AC observes movement M, commits it, advances the supported frontier and updates the safe anchor. The server never applies M and sends no correction, because from its perspective nothing happened. The next 40 packets are validated against a frontier ahead of the server. When cancellation stops, the client's next packet is consistent with the server but several blocks from Papea2AC's frontier. Simulation finds no candidate and all completeness flags are true — world known, inputs enumerable, no budget abort — so the engine emits `DeterministicExclusion`: a fully certified false positive, followed by a setback to an anchor that is itself wrong.
- **RECOMMENDED CHANGE:** Add to `PACKET_PIPELINE.md`: (a) a named observation boundary per direction, stated as an invariant (for example "inbound: immediately upstream of the server packet handler; outbound: immediately upstream of the encoder"), with the explicit admission that this is final *relative to that boundary*, not final-wire; (b) a pipeline-integrity rule — record the handler-name list at install, re-verify on a bounded schedule and on protocol-state transitions, and treat any unknown handler inside the boundary as capability loss with a named reason such as `ForeignHandlerInsideBoundary`; (c) an inbound-cancellation rule symmetric to the outbound one — if the adapter cannot observe whether the server consumed an inbound packet, dependent validation is disabled for the affected span; (d) two additions to the Phase 1 demonstration list — a foreign handler installed inside the boundary in each direction, and a foreign handler cancelling an inbound movement packet.
- **ACCEPTANCE CRITERIA:** The documents state the exact boundary per direction. A Phase 1 test installs a foreign handler inside the boundary in each direction and proves the integrity check fires and the right capability is dropped. A Phase 1 test cancels an inbound movement packet from a foreign handler and proves Papea2AC either observes the cancellation or marks the span uncovered — and in no case emits `DeterministicExclusion` for the resulting divergence.

### B4 — Review cannot be bound to a revision; `master` has no commits

- **ID:** B4
- **SEVERITY:** BLOCKER (process; mechanical to fix)
- **DOCUMENT(S):** `AI_DEVELOPMENT_RULES.md` (required review template), `PHASE_0_REVIEW.md` (Phase 1 criterion 1), repository git state
- **PROBLEM:** Both documents require recording "the exact reviewed file/tree digest or commit", and Phase 1 criterion 1 requires the gate to be "recorded against a fixed revision/digest". `master` has no commits; `README.md` and `docs/` are untracked; only the four pre-existing skeleton files are staged. There is no revision to bind to, and reviewed content can change without a trace.
- **WHY IT MATTERS:** The project's whole quality model is "implementer and reviewer are different agents, and approval is bound to a revision". Without a commit, approval binds to nothing. This review records content digests instead, which is a weaker substitute that depends on nobody touching the files.
- **FAILURE SCENARIO:** Phase 0 is approved. A Phase 1 implementer reasonably edits the `MOVEMENT_SIMULATION.md` budgets while implementing. Phase 2's reviewer reads the edited document and believes those numbers were reviewed. No diff exists to show otherwise.
- **RECOMMENDED CHANGE:** Commit the Phase 0 documents — and the pre-existing staged skeleton files, unchanged — on `master` before requesting the human approval gate, and record that commit SHA in `PHASE_0_REVIEW.md`. Responses to this review land as further commits so the fix diff is reviewable.
- **ACCEPTANCE CRITERIA:** `git rev-parse HEAD` returns a commit containing exactly the reviewed documents; the review record cites it; the second review verifies fixes against a diff rather than re-read prose.

---

## HIGH PRIORITY

### H1 — Stated simulation budget makes a complete INVALID arithmetically unreachable

- **ID:** H1
- **SEVERITY:** HIGH
- **DOCUMENT(S):** `MOVEMENT_SIMULATION.md` (Decision M2), `INPUT_RECONSTRUCTION.md`, `PERFORMANCE.md` (Simulation row), `ARCHITECTURE.md` (Decision A2)
- **PROBLEM:** INVALID requires "all relevant flags true and no candidate matching the documented observation intervals", where the flags include input-domain coverage, application-branch coverage and step-range coverage. The budget is 256 live candidates, 4096 candidate-step evaluations per movement event and at most 3 inferred steps. A single step's nominal input domain is roughly forward×strafe (9) × jump (2) × sprint (2) × sneak (2) × use-item (2) = 144, before rotation intervals and locomotion-mode alternatives. Crossed with application branches for pending updates — the ledger allows 64 impulses and 256 markers — a handful of unresolved updates alone multiplies by 2^k. One step of full coverage is therefore already near or past 4096; two steps is out of reach by orders of magnitude, and three is hopeless. Forward generate-and-test cannot produce a complete negative certificate within this budget.
- **WHY IT MATTERS:** The architecture's central promise is that INVALID means "every admissible explanation excluded with complete relevant knowledge". If that is unreachable in practice, the system converges to permanent UNCERTAIN, no deterministic exclusion ever gets emitted, and the only remaining detection is behavioural heuristics — which `EVIDENCE_MODEL.md` correctly refuses to let punish on their own. The project would then ship an anticheat that is epistemically impeccable and operationally inert. Worse, because the failure is silent (UNCERTAIN is a legitimate output), it would not be discovered until the Phase 8 coverage report.
- **FAILURE SCENARIO:** Phase 8 runs its "benign/adversarial coverage report". Benign traffic is mostly VALID, because a single witness suffices and the client-claimed input is tried first. Adversarial traffic is ~100% UNCERTAIN with `budget exhaustion` as the reason, because proving a cheat's movement impossible requires exhaustion. The report shows high VALID rates and zero false positives, and it is read as success. The engine detects nothing.
- **RECOMMENDED CHANGE:** Add a DECISION record for how exclusion is *computed*, not merely bounded. Forward enumeration is sound for finding witnesses (VALID), but exclusion needs an inversion/constraint formulation: given observed displacement, prior state frontier and observed rotation, solve for the required per-axis acceleration contribution and test membership in the admissible set, using interval arithmetic over the uncertain terms. That is O(1)-ish per step and is complete by construction for the deterministic part of the step function, with the enumerated part reduced to the genuinely discrete choices (mode, jump, impulse-application ordering). State explicitly which parts of the step function are invertible and which must remain enumerated, and set budgets from the enumerated remainder. Until that exists, the roadmap should say that INVALID is not expected to be reachable and no enforcement is possible.
- **ACCEPTANCE CRITERIA:** A DECISION record states the exclusion algorithm class and, for the target ruleset, the number of genuinely enumerated branches per step with justification. A Phase 6 test constructs a movement that is provably impossible and shows the engine returns INVALID with all completeness flags true inside the budget. A saturation metric reports the fraction of events reaching INVALID versus budget-exhausted UNCERTAIN, and the Phase 8 exit gate requires a non-trivial INVALID rate on adversarial fixtures — not just a low false-positive rate.

### H2 — The "sound conservative abstraction / envelope" that soundness depends on is never defined

- **ID:** H2
- **SEVERITY:** HIGH
- **DOCUMENT(S):** `MOVEMENT_SIMULATION.md` (Decision M2, Continuation and failure safety), `COMBAT_MODEL.md` (superset paragraph), `ROADMAP.md`
- **PROBLEM:** Three separate load-bearing guarantees delegate to an abstraction that is named but never specified: "Equivalent states may merge only when their future-relevant state, causal obligations and uncertainty sets are identical **or represented by a sound conservative abstraction**"; "Retain distinct supported motion hypotheses **or a sound envelope**"; "Prune only with proven incompatible constraints **or conservative reachable envelopes**". No document defines what an envelope is, how soundness is established, or how an envelope-derived exclusion is proven conservative. `ROADMAP.md` assigns it to no phase.
- **WHY IT MATTERS:** Without an envelope, the candidate frontier must be enumerated, which is exactly what H1 shows is infeasible. With an unsound envelope, exclusions are wrong and false positives carry full completeness certificates — the worst possible failure mode, because the evidence model will present them as deterministic proof. The abstraction is simultaneously the only escape from the budget problem and the largest correctness risk, and it is currently a placeholder.
- **FAILURE SCENARIO:** A Phase 6 implementer, blocked by the candidate cap, introduces "merge candidates whose position differs by less than 1e-7 and whose velocity differs by less than 1e-7". Two hypotheses that are numerically close but diverge after two more ticks (one on ground, one one-tick-airborne with residual downward velocity) merge. The surviving representative cannot reach the next observation. `completeness = true` because merging was declared "conservative". A legitimate player gets a certified exclusion on a stair edge.
- **RECOMMENDED CHANGE:** Either (a) specify the envelope — its representation (per-axis intervals plus discrete mode/contact tags is the usual choice), the monotonicity obligation (the envelope's image under one physics step must contain the image of every concrete state it represents), and the rule that an envelope may only establish INVALID, never VALID, while a concrete witness may only establish VALID; or (b) remove the envelope escape hatches and accept the coverage consequence. Add an explicit roadmap deliverable owning it.
- **ACCEPTANCE CRITERIA:** A DECISION record defines the abstraction and states the containment property as a checkable obligation. A property-based test asserts, on random concrete states, that stepping the envelope contains the set of stepped concrete states. A test asserts that no envelope-only match ever produces a `LegitimateWitness` evidence record.

### H3 — Retention horizons are mutually inconsistent; the effective horizon is the minimum, not the advertised 10 s

- **ID:** H3
- **SEVERITY:** HIGH
- **DOCUMENT(S):** `PLAYER_STATE.md` (component bounds), `PLAYER_TIMELINE.md`, `PERFORMANCE.md` (budget registry), `COMBAT_MODEL.md` (pending attacks), `LATENCY_COMPENSATION.md` (10 s horizon)
- **PROBLEM:** Several horizons are stated independently and do not agree.
  - Timeline: "4096 records or 2 MiB or 10 seconds, whichever first", where Timeline "Changes on: Every committed event/control transition". Committed events include outbound entity spawn/move/metadata/destroy for up to 256 tracked entities. Twenty nearby moving entities at 20 Hz is ~400 records/s before the player's own 20/s movement, acknowledgements, block changes and inventory traffic. At ~500 records/s the ring holds about 8 seconds; in dense combat at ~2000/s it holds about 2. The 2 MiB cap also implies ≤512 bytes per record including object overhead, which is tight for a record carrying component revision roots, causal parent IDs, knowledge frontier and uncertainty reasons.
  - Entity history: "256 tracked candidates, 64 revisions each". At 20 Hz that is 3.2 seconds per entity.
  - Markers and latency: "at most ten seconds and 256 markers".
  - Combat: "Up to 64 unresolved attacks per player may wait for relevant acknowledgements, for at most the retained history horizon."
  Combat resolution is therefore bounded by entity history (~3.2 s), not by the 10 s horizon the latency model is written against, and both are bounded by the timeline ring, which collapses fastest precisely in dense combat.
- **WHY IT MATTERS:** These are the numbers that decide whether a laggy player's attack can be validated at all. As written, the system advertises a 10-second reconstruction horizon and delivers roughly 3 seconds for combat and possibly under 2 seconds for anything needing the timeline, exactly in the scenario the feature exists for. Because expiry returns UNCERTAIN, this shows up as coverage loss, not as an error — so it will not be noticed without a coverage denominator.
- **FAILURE SCENARIO:** A 250 ms player attacks during a 30-player fight. The attack needs the target's interpolation endpoints at the attacker's observed frontier. Entity traffic has pushed the timeline ring past the attack's record, and the target's 64 retained revisions cover the last 3.2 s at 20 Hz but the relevant revision was evicted by a burst of relative-move packets. The attack resolves UNCERTAIN with `HISTORY_MISSING`. Repeat for the whole fight: combat validation is unavailable exactly during combat.
- **RECOMMENDED CHANGE:** Publish one horizon table that derives every retention bound from a single declared *target reconstruction horizon* and a declared worst-case event rate, and make the inconsistencies explicit where they remain. Specifically: state whether outbound entity traffic produces timeline records or is held only in `EntityTracker` with the timeline holding an aggregate reference; express entity history in *time* (with a revision cap as a secondary guard) rather than in revisions; and make combat's pending-attack timeout the minimum of the horizons it actually depends on, reported as such. Add a per-session metric for achieved horizon versus target.
- **ACCEPTANCE CRITERIA:** `PERFORMANCE.md` contains a derivation from target horizon and assumed event rate to each cap, with the assumed rates stated. A test drives a synthetic 30-entity combat scenario at 20 Hz and asserts the achieved reconstruction horizon meets the declared target, or that the shortfall is reported through the coverage metric. No document states a 10-second horizon for a query whose inputs retain less.

### H4 — Observer world baselines cannot be rebuilt: eviction, gap recovery and mid-session enable all produce permanent UNKNOWN

- **ID:** H4
- **SEVERITY:** HIGH
- **DOCUMENT(S):** `WORLD_MODEL.md` (Data model and sources, Storage and eviction), `THREADING_MODEL.md` (Recovery transitions), `PACKET_PIPELINE.md`
- **PROBLEM:** The observer world is reconstructed exclusively from outbound packets: "Record chunk baseline before deltas; a delta without baseline is incomplete", "Main-thread world snapshots … do not establish client visibility", "A main-thread world snapshot must not overwrite an observer's fake block view". The server does not resend chunks the client already has, so a baseline lost for any reason is unrecoverable for the remainder of the session. Three paths lose it: (1) eviction — "Evict obsolete revisions and far regions first" with 4096 section handles per observer, so a player who ranges away and returns has no baseline for their origin; (2) gap recovery — `HEALTHY → GAP/OVERLOADED → RESYNC_PENDING → HEALTHY` claims to "rebuild a coherent baseline through captured outbound resynchronization", but there is no outbound resynchronization for world content, so world coverage cannot actually return to HEALTHY; (3) plugin enable with players already online — no chunk packets will ever arrive for the chunks they are standing in.
- **WHY IT MATTERS:** Backpressure recovery is one of the three answers this review was asked to check, and as specified the recovery is only partial: sequencing and acknowledgement ledgers can be rebuilt, world coverage cannot. That is a materially weaker guarantee than the document implies. The mid-session-enable case means any restart-free deploy (or any `/reload`-adjacent operation) yields a server-full of sessions whose movement validation is permanently UNKNOWN-bounded until each player reconnects.
- **FAILURE SCENARIO:** A server with 300 players enables the plugin during a live session. Every online player's collision neighbourhood is UNKNOWN. Because `blockAt` "Unknown is neither air nor solid" and validators propagate coverage, every movement event returns UNCERTAIN. Operators see the plugin running, alerting nothing, with no error — the failure is indistinguishable from "no cheaters online". Separately, a routine ingress overload on one connection latches a gap; after resync, that player's world coverage never returns, and their movement stays UNCERTAIN for hours.
- **RECOMMENDED CHANGE:** Three things. (a) State explicitly, in `WORLD_MODEL.md` and in the `THREADING_MODEL.md` recovery section, that world coverage is not recoverable within a session by outbound observation alone, and that `RESYNC_PENDING → HEALTHY` restores sequencing but not world coverage. (b) Define the bootstrap/rebaseline mechanism or declare its absence: options are a platform-mediated chunk re-send (Paper can untrack and retrack chunks for a player), marking such sessions `OBSERVE_ONLY` until reconnect, or a main-thread authoritative snapshot combined with a bounded per-observer *divergence* overlay. (c) Evaluate the divergence model against the current full-reconstruction model before Phase 3 — divergence (server-authoritative shared snapshot + per-observer visibility set + unconfirmed changes + captured fake-block sections) makes eviction recoverable and turns per-observer memory from O(view volume) into O(divergence), at the cost of requiring divergence capture to be provably complete. I am not asking for a redesign on principle; I am asking for the comparison to be made with numbers, because the current model's cost and its unrecoverability are both unquantified.
- **ACCEPTANCE CRITERIA:** The documents state the unrecoverability property explicitly. A DECISION record chooses a bootstrap policy for sessions that predate plugin enable and for post-gap recovery, with the resulting coverage state named. Phase 3 publishes measured per-observer world bytes and event-loop copy cost at view distance 10 and 16 for both models, or a written justification for not comparing.

### H5 — Pending-update application branching has no bound, so coverage collapses under exactly the conditions the model exists for

- **ID:** H5
- **SEVERITY:** HIGH
- **DOCUMENT(S):** `LATENCY_COMPENSATION.md`, `MOVEMENT_SIMULATION.md`, `PLAYER_STATE.md` (VelocityState, LatencyState)
- **PROBLEM:** Because marker ACKs "must not advance a physics-application frontier" until a version-specific guarantee profile exists, every outbound update that could affect physics stays in a before/after application ambiguity until independently resolved. The ledger permits 64 pending impulses and 256 markers. Each simultaneously-unresolved update contributes an application branch per step. Nothing bounds the number of *simultaneously unresolved* updates, and the candidate cap is 256 — so roughly eight concurrently unresolved updates saturate the frontier by themselves, before any input branching.
- **WHY IT MATTERS:** Dense combat plus 150–250 ms latency produces exactly this: knockback impulses arriving faster than they resolve. The very scenario the latency model was designed for is the scenario where it runs out of representational budget. And because saturation returns UNCERTAIN, it is invisible unless measured.
- **FAILURE SCENARIO:** A player in a 10-player fight takes knockback roughly every 500 ms at 200 ms RTT. Three to five impulses are unresolved at any time; each movement event branches over their application orderings, and the interaction with rotation intervals and mode alternatives saturates 256 candidates within one or two steps. Movement validation for that player is UNCERTAIN for the duration of the fight. Anyone wanting to evade movement validation only has to stay in combat.
- **RECOMMENDED CHANGE:** Bound the unresolved set explicitly rather than implicitly: state a maximum number of concurrently unresolved physics-affecting updates, and specify what happens on overflow (declare the affected span uncovered with a named reason, rather than silently saturating). Then specify the forcing mechanisms that shrink the set — teleport confirmation is already dedicated; identify whether any protocol response in the pinned version can bracket velocity application, and if none can, say so and accept that impulse-adjacent movement is uncovered until the guarantee profile exists. This is the concrete place where the envelope from H2 pays for itself: an interval over "impulse applied at step *i* for *i* in [a,b]" replaces enumeration.
- **ACCEPTANCE CRITERIA:** A stated cap on concurrently unresolved physics-affecting updates with a named overflow reason. A Phase 7b test drives repeated knockback at 50/150/300 ms simulated RTT and reports the fraction of movement events reaching VALID or INVALID rather than UNCERTAIN, with a declared minimum for the qualification gate.

### H6 — Movement-affecting block properties have no contract anywhere

- **ID:** H6
- **SEVERITY:** HIGH
- **DOCUMENT(S):** `COLLISION_ENGINE.md` (Decision C1), `MOVEMENT_SIMULATION.md` (Step specification), `WORLD_MODEL.md`, `ARCHITECTURE.md` (module responsibilities)
- **PROBLEM:** `collision` owns "Shape queries, movement clipping, ray geometry" and `physics` owns "Pure one-step movement rules". Neither owns the non-geometric, movement-affecting properties of blocks: surface friction (ice, blue ice, packed ice, slime), horizontal speed factor (soul sand, honey, blue ice), bounce (slime, beds), fluid flow vectors and push strength, inside-block drag (cobweb, powder snow, sweet berry bush), vertical column forces (bubble columns), climbable behaviour and step/jump modifiers. `COLLISION_ENGINE.md` answers "can the body occupy this space"; nothing answers "what does this block do to the body". The fixture list covers shape cases well (slabs, stairs, fences, doors, snow layers, scaffolding, powder snow, climbables, honey/slime, pistons, world border) but shape coverage for honey is not the same thing as honey's slowdown and wall-slide.
- **WHY IT MATTERS:** Review 6 asked specifically whether collision shape and movement-affecting properties are conflated. They are not conflated — the properties are simply absent. Every one of these is a per-tick multiplier or additive force in the real step function, so a simulator without them is wrong on ice, soul sand, honey, cobwebs, berry bushes, bubble columns, slime and beds. Since these are common blocks, the result is either mass UNCERTAIN or, if the simulator believes it has complete knowledge, certified false positives. This is also the one place where the Intave reference is directly instructive: its `block/physics` package is separate from its shape system for exactly this reason.
- **FAILURE SCENARIO:** A player sprints across an ice bridge. The simulator applies default 0.6 friction instead of 0.98. Observed displacement exceeds every candidate. World coverage is complete, inputs are enumerable, no budget abort fired — so completeness flags are true and the engine emits a deterministic exclusion. Ice bridges are common; this is a mass false positive on the first server that has one.
- **RECOMMENDED CHANGE:** Add a `BlockBehaviorQuery` (or equivalent) contract alongside `ShapeQuery`, owned by the same module that owns shapes, keyed by canonical block state plus context, returning a typed property set with the same KNOWN / UNKNOWN / AMBIGUOUS coverage semantics as `blockAt`. Specify separately the *sampling rule* for inside-block effects — the set of block positions intersecting the entity AABB, with its own bounded work budget, distinct from the swept-volume broad phase used for collision. Extend the collision/physics fixture list to include friction, speed factor, bounce, fluid flow, inside-block drag and column forces as named cases.
- **ACCEPTANCE CRITERIA:** A named contract exists with coverage semantics and a work bound. `MOVEMENT_SIMULATION.md`'s step specification cites it as an input. The fixture list names at least: ice/blue-ice friction, soul sand speed factor, honey slowdown and wall-slide, slime and bed bounce, cobweb and berry-bush drag, bubble column up and down, and fluid flow push.

### H7 — Entity-vs-entity collision and pushing are absent from the entire specification

- **ID:** H7
- **SEVERITY:** HIGH
- **DOCUMENT(S):** `MOVEMENT_SIMULATION.md`, `COLLISION_ENGINE.md`, `WORLD_MODEL.md`, `PERFORMANCE.md`
- **PROBLEM:** Collision is specified against blocks and the world border only. No document mentions entity push, entity-vs-entity collision, or entities as colliders (boats and shulkers have real collision in vanilla). The step specification lists "collision/step-up resolution" without saying it includes entity interaction, and no budget covers enumerating nearby entities for push resolution.
- **WHY IT MATTERS:** Vanilla applies entity push every tick, and it is client-predicted, so it appears in the observed movement. In any crowded space — spawn, hub, a fight, a mob farm, a queue — a player's displacement includes push contributions the simulator does not model. Since the push depends on entities the *client* knew about, it also needs the observer entity history, which exists but is only wired for combat. The effect is systematic mismatch in the busiest areas of any server.
- **FAILURE SCENARIO:** Fifteen players stand in a spawn plaza. Push accumulates several hundredths of a block per tick per player. Every player's movement is slightly unexplainable. If the engine treats knowledge as complete, it certifies exclusions for everyone simultaneously — the classic mass false positive. If it does not, spawn is permanently UNCERTAIN and a cheater only needs to stand near other players.
- **RECOMMENDED CHANGE:** Decide explicitly and record it: either entity push is modelled (using the observer entity history, with a stated candidate-entity bound and work budget, and an UNKNOWN result when nearby entity coverage is incomplete), or it is not modelled and any movement event with a nearby-entity count above a stated threshold is uncovered by construction with a named reason. The second option is defensible for an initial release but must be stated, because it defines an evasion surface. Also state whether boats, shulkers and other solid entities participate in `MovementQuery`.
- **ACCEPTANCE CRITERIA:** A DECISION record covering entity push and entity colliders, with the coverage reason named when unmodelled. If modelled, `PERFORMANCE.md` gains a budget row. Fixtures include a multi-player push scenario and a boat-as-collider scenario, asserting no INVALID with complete-knowledge flags.

### H8 — Outbound and inbound observation source lists omit movement-critical packets

- **ID:** H8
- **SEVERITY:** HIGH
- **DOCUMENT(S):** `PACKET_PIPELINE.md` (Sources), `PLAYER_STATE.md` (VelocityState, TickState), `MOVEMENT_SIMULATION.md`
- **PROBLEM:** The outbound source list is "position correction, velocity, attributes/effects, abilities, chunk/block changes, entity spawn/move/metadata/destroy, window state, protocol transitions and marker writes". It omits at least: **explosion** (which carries the player's knockback in its own packet, not as an entity-velocity packet), **vehicle move / set passengers / mount** (vehicle position is corrected on a separate path), **set camera** (spectator target changes what the client controls), **respawn and game-event** (gamemode change, respawn screen), **set health / death**, and **server tick-rate and tick-freeze state** (modern servers can change tick rate or freeze ticks, which invalidates every timing assumption). The inbound list omits **held-item change** (affects attributes and interaction range), **swing/animation** (needed by click analysis), **vehicle move and steer/paddle**, and the **per-tick client tick-end marker** that `PLAYER_STATE.md`'s TickState already assumes exists.
- **WHY IT MATTERS:** Explosion knockback is the clearest case: if the impulse arrives in a packet Papea2AC does not observe, the player's motion after a TNT, creeper or end crystal is unexplainable with *complete* knowledge — the ledger has no pending impulse to branch over, so nothing marks the span uncertain. That is a certified false positive on an extremely common event, and it is caused by an omission in a list, not by a modelling error. Tick-rate changes are the second clearest: a server running at a reduced tick rate makes every timer and multi-step assumption wrong in a way that looks exactly like a client-side timer cheat.
- **FAILURE SCENARIO:** A player fights near a creeper. The creeper explodes; the server sends an explosion packet containing the player's knockback. Papea2AC never sees it. The next movement shows a large displacement with no pending impulse in the ledger. World is known, inputs enumerable, no budget abort — deterministic exclusion, setback, and the player is rubber-banded into the blast.
- **RECOMMENDED CHANGE:** Replace the prose source lists with an explicit, enumerated per-direction packet/observation matrix in `PACKET_PIPELINE.md`, each entry tagged with the state it feeds and whether it is mandatory for a given capability. Add a rule that any packet class the version profile marks as physics-affecting and the adapter does not observe causes an explicit capability loss at startup, not a silent omission. Explicitly include explosion knockback in the impulse ledger's source list in `PLAYER_STATE.md`.
- **ACCEPTANCE CRITERIA:** The matrix exists and names explosion, vehicle move, set passengers, set camera, respawn, game event, set health, tick-rate/freeze state, held-item change, swing, steer/paddle and client tick-end. A Phase 1 test asserts that for the pinned protocol, every packet the version profile marks physics-affecting has an adapter entry, and that a deliberately removed entry causes startup capability loss rather than silent absence.

### H9 — Budget starvation is an unexamined evasion channel, and shard scheduling has no fairness rule

- **ID:** H9
- **SEVERITY:** HIGH
- **DOCUMENT(S):** `THREADING_MODEL.md` (Decision T1, actor visit rules), `PERFORMANCE.md`, `MOVEMENT_SIMULATION.md` (budgets)
- **PROBLEM:** Budgets are per-event constants and exhaustion yields UNCERTAIN. Work is bounded per event but not per *player per second*, and a shard is "one dedicated worker thread, with round-robin actor mailboxes" with no per-actor CPU quota, only "at most 32 small events per actor visit". Two consequences are unaddressed. (a) A player can deliberately increase their own validation cost — complex geometry, maximal pending impulses, maximal tracked entities, movement patterns that defeat early witnesses — to push their own events to budget exhaustion and therefore to UNCERTAIN. (b) A single expensive actor head-of-line blocks every other actor on its shard, and nothing states how shard count relates to expected player count or what degradation is applied under sustained saturation.
- **WHY IT MATTERS:** "Exhaustion means UNCERTAIN" is the correct safety property, but it also means *cost is a control surface for the attacker*. Any evasion that works by making validation expensive is free and undetectable, because the resulting UNCERTAIN is indistinguishable from legitimate ambiguity. Separately, if the response to load is to reduce budgets, verdicts become load-dependent — which the replay design handles correctly by recording the budget, but which no policy governs.
- **FAILURE SCENARIO:** A cheat client keeps the player inside a dense stair/slab/fence build and maintains a deep pending-impulse ledger by attacking a mob. Every movement event exhausts the aggregate geometry budget. All of that player's movement is UNCERTAIN, permanently and legitimately. Meanwhile their shard's other 120 players see queue age grow, so a second population also degrades.
- **RECOMMENDED CHANGE:** (a) Add a per-session sustained work quota alongside the per-event caps, and treat a session that persistently exhausts budget as an operational signal with its own evidence kind — not a cheating violation, but a visible, alertable condition (this pairs with M9). (b) State the shard sizing rule, the per-actor per-visit work quota, and the degradation ladder under sustained saturation, including whether budgets may be lowered dynamically and, if so, that the applied budget is recorded in both evidence and replay (M2 already requires the latter — make it explicit that dynamic reduction is the reason). (c) Add an adversarial load profile to the performance matrix that maximises validation cost rather than packet rate.
- **ACCEPTANCE CRITERIA:** `PERFORMANCE.md` gains a sustained per-session work quota row and a degradation ladder. A metric reports per-session budget-exhaustion rate. The load matrix includes a cost-maximising adversarial profile. A test shows that lowering budgets under load changes the recorded budget in evidence and that replay reproduces the original verdict from the recorded budget.

### H10 — Shard failure leaves reservations and shared-store pins unreclaimed, and quarantined actors have no recovery path

- **ID:** H10
- **SEVERITY:** HIGH
- **DOCUMENT(S):** `THREADING_MODEL.md` (Exceptions, disconnect and shutdown), `WORLD_MODEL.md` (pins and admission), `PERFORMANCE.md`
- **PROBLEM:** "An unexpected shard failure quarantines all assigned actors; restarting a thread does not recover trust in their partially updated state." Nothing states who then releases those actors' memory reservations, world-store pins, replay staging buffers and pending intents — the owning actor is the only writer and it is gone. Nothing states whether new connections continue to be assigned to the failed shard by round-robin, whether quarantined players are ever validated again in that session, or what operators see.
- **WHY IT MATTERS:** The shared world content store is a fixed 512 MiB global budget with per-player reference admission; unreleased pins are a permanent global leak that degrades every other session, and the failure is silent because quarantine is a legitimate state. Combined with round-robin assignment, a failed shard can keep collecting new sessions that never get validated.
- **FAILURE SCENARIO:** An unhandled exception escapes a shard worker on a Tuesday. Its 120 actors quarantine, holding pins. Global world-store admission tightens; other sessions start being denied pins and returning UNCERTAIN for world queries. Over hours the anticheat quietly stops validating most of the server, with no error beyond one logged exception.
- **RECOMMENDED CHANGE:** Specify shard-failure semantics: a supervisor that (a) reclaims all reservations and pins held by actors of a failed shard through a dedicated reclamation path that does not require the dead writer, (b) decides whether quarantined sessions are disconnected, re-bootstrapped with a new validation epoch, or left observe-only until reconnect, (c) removes the failed shard from the assignment rotation, and (d) raises an operational alert. Add shard death to the Phase 1 lifecycle test list.
- **ACCEPTANCE CRITERIA:** A documented shard-failure state machine with a named reclamation owner. A fault-injection test kills a shard thread mid-event and asserts: all reservations and pins released, no new sessions assigned to it, quarantined sessions reach a defined terminal state, and an alert is emitted.

### H11 — No server-wide anomaly guard and no runtime enforcement kill switch

- **ID:** H11
- **SEVERITY:** HIGH
- **DOCUMENT(S):** `MITIGATION.md`, `EVIDENCE_MODEL.md`, `VERSION_SUPPORT.md`, `THREADING_MODEL.md` (hot reload unsupported)
- **PROBLEM:** All safety mechanisms are per-session: one active correction per player, a two-second cooldown, three failures disables setbacks for *that session*. There is no global guard — nothing notices that 40% of online players produced deterministic exclusions in the same 10-second window, which is the signature of a server-side event (tick stall, plugin conflict, world change, an engine bug) rather than 40% of the server cheating simultaneously. Separately, `VERSION_SUPPORT.md` allows observation-only "by explicit configuration" but `THREADING_MODEL.md` says "Hot reload is unsupported", and no document describes a runtime path to disable enforcement without restarting.
- **WHY IT MATTERS:** Per-session limits bound the damage to one player; correlated failures are the ones that damage a server's reputation, and they are also the ones most likely to be caused by the anticheat itself. An operator watching a live mass-rubber-band event needs a switch that works in seconds; telling them to restart the server is not an acceptable answer for a plugin whose failure mode is teleporting players around.
- **FAILURE SCENARIO:** A plugin update starts cancelling inbound movement for a region (see B3), or a world-generation task stalls the main thread for 4 seconds. Within one window, dozens of players produce certified exclusions and setbacks. Each session's loop-breaker eventually trips after three corrections, so every affected player is rubber-banded up to three times before enforcement disables individually. The operator has no single control and no restart-free remedy.
- **RECOMMENDED CHANGE:** Add (a) a global correlated-anomaly guard: if the rate of actionable decisions across distinct sessions in a rolling window exceeds a configured threshold, suspend all automated effects server-wide, emit a distinct operational event, and require explicit re-enable; and (b) a runtime enforcement mode control (`ENFORCE` / `OBSERVE_ONLY` / `DISABLED`) reachable by a permissioned command, applied through the existing sequenced-message path so it is ordered and recorded in replay, with an explicit statement of how quickly it takes effect for already-admitted intents.
- **ACCEPTANCE CRITERIA:** Both mechanisms are specified with their thresholds and their interaction with the effect-admission generation. A test injects a synthetic correlated fault across many sessions and asserts that automated effects suspend server-wide before the per-session loop-breakers individually trip. A test flips enforcement mode at runtime and asserts that no new effects are admitted afterwards and that the transition appears in the replay stream.

### H12 — Numeric profile does not cover transcendental functions, so vanilla fidelity and cross-host replay determinism are both unspecified

- **ID:** H12
- **SEVERITY:** HIGH
- **DOCUMENT(S):** `COLLISION_ENGINE.md` (Numerical and contextual rules), `MOVEMENT_SIMULATION.md` (Step specification), `REPLAY_SYSTEM.md` (determinism), `VERSION_SUPPORT.md` (numeric/quantization rules)
- **PROBLEM:** The documents require preserving "IEEE float/double bits where relevant" and recording a numeric profile, and correctly require "Physics dictates float/double conversions and movement clipping order". They say nothing about transcendental functions. Converting a yaw into a movement basis needs sine and cosine. In Java, `Math.sin`/`Math.cos` are permitted to differ between implementations and platforms within their accuracy bound, while `StrictMath` is reproducible; and Minecraft itself does not use either for this — it uses its own precomputed table, so bit-exact vanilla agreement requires reproducing that table's semantics rather than calling a library function at all. Basic arithmetic and `Math.sqrt` are exactly specified, so this is specifically about the trig (and any other transcendental) path.
- **WHY IT MATTERS:** Two distinct failures. (1) Fidelity: a simulator using a library trig function does not reproduce the client, so residuals appear at the least significant bits and grow over multi-step searches — the tolerance needed to absorb that is unprincipled, which `COLLISION_ENGINE.md` explicitly forbids ("Tolerances must name their source"). (2) Determinism: `REPLAY_SYSTEM.md` requires "Equal outputs/checkpoints on repeated runs" and "cross-process determinism". A capsule produced on one host and replayed on another can diverge if trig differs, which turns regression tests into flaky tests and makes a false-positive capsule non-reproducible on the developer's machine — the exact scenario replay exists to serve.
- **FAILURE SCENARIO:** A false positive is captured on a production Linux/x86-64 host. A developer replays it on Windows/aarch64. The last-bit difference in the yaw basis changes one collision boundary outcome two steps in; the replay produces VALID. The bug is recorded as unreproducible and the fixture is dropped.
- **RECOMMENDED CHANGE:** Extend the numeric profile to enumerate every non-elementary function used in physics, geometry and combat, and for each state the mandated implementation and its provenance: the reproduced client table, `StrictMath`, or an explicitly justified library call. Forbid `Math` transcendental calls in `physics`, `collision`, `simulation` and `combat` by CI rule, the same way Bukkit imports are forbidden outside the platform boundary. Add cross-platform determinism to the replay test obligations rather than only cross-process.
- **ACCEPTANCE CRITERIA:** The numeric profile names each transcendental function and its mandated implementation. A CI check fails on `Math.sin`/`Math.cos`/`Math.pow`/`Math.atan2` and similar in the engine modules. A replay determinism test runs the same capsule on at least two distinct OS/architecture combinations and asserts identical digests.

### H13 — Memory budgets contradict the stated 1000-player target; no minimum per-session footprint is defined

- **ID:** H13
- **SEVERITY:** HIGH
- **DOCUMENT(S):** `PERFORMANCE.md` (Decision F1, budget registry, memory estimate), `PLAYER_STATE.md`, `WORLD_MODEL.md`
- **PROBLEM:** The per-session reservation is "A player-level 16 MiB hard reservation encompasses all private items below", where the private items include timeline 2 MiB, replay staging 2 MiB and private world/entity history 8 MiB. The global default is "min(1 GiB, 25% of JVM max heap)" with 20% reserved for accounting error. The design target is "experiments at 50, 100, 250, 500 and 1000 connected active players". At 1000 players the per-session reservations alone imply up to 16 GiB, and even a minimum viable configuration is ~12 MiB per session (timeline + replay staging + world history) ≈ 12 GiB, before the 512 MiB shared store and 128 MiB shape cache. The document's escape hatch — "This default is not a 1000-player configuration" — is honest but leaves the actual requirement unstated, and "startup lowers compatible quotas or refuses configuration if they cannot fit" means a 1000-player server on a 1 GiB budget would be silently reduced to about 1 MiB per session, below the minimum any component needs.
- **WHY IT MATTERS:** This is the difference between "an anticheat you can run" and "an anticheat that needs 16 GiB of heap on top of Paper's world heap". It is decided by the budget registry written in Phase 1, and discovering it at Phase 24 means re-deriving retention, world modelling and replay staging after everything is built. It also interacts with H3 and H4: the cheapest way to fit the budget is to shrink retention, which silently shrinks the reconstruction horizon.
- **FAILURE SCENARIO:** Phase 24 runs the 500-player cell on a 4 GiB anticheat budget. Startup reduces per-session quotas to ~8 MiB. World history is squeezed first, so per-observer section handles drop and eviction becomes aggressive; per H4 eviction is unrecoverable, so world coverage degrades permanently for long-lived sessions. The run reports acceptable latency and heap and a high UNCERTAIN rate, and the UNCERTAIN rate is attributed to "load" rather than to a budget-driven design limit.
- **RECOMMENDED CHANGE:** Publish a minimum viable per-session footprint (the floor below which a session must be refused or marked observe-only rather than silently degraded), and a scale table mapping each target player count to the required anticheat budget under stated retention assumptions. State which quotas may be reduced at startup and which are floors. If 1000 players is not achievable at a plausible heap, say so now and make the supported envelope a design input rather than a Phase 24 discovery.
- **ACCEPTANCE CRITERIA:** `PERFORMANCE.md` contains a minimum per-session footprint, a list of reducible versus floor quotas, and a player-count-to-budget table with its assumptions. Startup refuses or downgrades explicitly, with a logged reason, when the configured budget cannot meet the floor for the configured player capacity.

### H14 — Combat does not state which attacker eye position is authoritative

- **ID:** H14
- **SEVERITY:** HIGH
- **DOCUMENT(S):** `COMBAT_MODEL.md` (Attack context, Validation pipeline step 2), `PLAYER_STATE.md` (MovementState: "Claimed and supported poses separately")
- **PROBLEM:** `MovementState` deliberately keeps the client-claimed pose and the engine-supported pose frontier apart, which is right. `COMBAT_MODEL.md` then says to "Obtain bounded coherent attacker-eye, target-box and world hypotheses from causal history" without saying which of the two sources supplies the attacker eye, or what happens when the claimed position lies outside the supported frontier.
- **WHY IT MATTERS:** This choice decides both the false-positive and the bypass profile of every reach check. Using the claimed position trusts the client for the most security-relevant input in combat, so a position spoof becomes reach; using only the supported frontier makes reach UNCERTAIN whenever movement is UNCERTAIN, which — given H5 — is most of a fight; and if the claimed position is outside the supported frontier, the "attack from an impossible position" case has no defined handling at all. Left unspecified, two implementers in Phase 11 and Phase 12 will choose differently.
- **FAILURE SCENARIO:** Phase 12 uses the claimed position because it is precise and always available. A cheat sends positions shifted 0.4 blocks toward its target each attack tick, staying within movement tolerance per tick. Reach validates every time against the shifted eye. Reach is fully bypassed by a movement lie that movement validation treats as within tolerance.
- **RECOMMENDED CHANGE:** State the rule: the attacker eye set is derived from the supported frontier, intersected with the claimed pose when the claim is inside it; if the claim lies outside the supported frontier, that is a movement-domain fact and combat returns UNCERTAIN with a named reason rather than validating against either. Record in `CombatValidation` which source produced the witness, so evidence shows whether a pass depended on a client claim.
- **ACCEPTANCE CRITERIA:** `COMBAT_MODEL.md` states the derivation rule and the outside-frontier case. `CombatValidation` includes the eye-source provenance field. A fixture drives a within-tolerance position shift toward the target and asserts that reach does not validate purely on the claimed eye.

---

## MEDIUM PRIORITY

### M1 — Configuration has no owner, schema, or versioning specification

- **ID:** M1 · **SEVERITY:** MEDIUM · **DOCUMENT(S):** `ARCHITECTURE.md`, `EVIDENCE_MODEL.md`, `REPLAY_SYSTEM.md`, `PERFORMANCE.md`
- **PROBLEM:** `configId` appears in `EvidenceRecord`, config digests appear in the replay capsule header, "Commands/configuration updates re-enter the sequence as messages", and budgets are "configured" — but no module owns configuration, no document defines its schema, validation, defaults, reload semantics or version identity.
- **WHY IT MATTERS:** Config is a replay input; if it is not canonicalised and hashed the same way every time, the capsule digest contract is unsound. It is also the surface operators actually touch, and the natural home for the H11 kill switch and the H13 budget floors.
- **FAILURE SCENARIO:** Phase 1 writes a YAML loader with defaults scattered across modules. A capsule records a digest of the raw file. Whitespace or key-order changes alter the digest, and previously-valid fixtures are reported as configuration mismatches.
- **RECOMMENDED CHANGE:** Add a configuration section (owner module, canonical normalised form, digest rule, validation-on-load with refusal rather than silent defaults, and the change path through the sequenced-message mechanism).
- **ACCEPTANCE CRITERIA:** A named owner, a canonicalisation rule, and a test showing two textually different but semantically identical configurations produce the same digest.

### M2 — No plugin-facing integration API, so legitimate server-side movement effects have no declaration channel

- **ID:** M2 · **SEVERITY:** MEDIUM · **DOCUMENT(S):** `ARCHITECTURE.md`, `MITIGATION.md`, `VERSION_SUPPORT.md`
- **PROBLEM:** Papea2AC is specified with no API for other plugins. Server-side effects are inferred purely from outbound packets. That works for velocity and teleports, but not for declaring intent — "this player is in a minigame with custom movement", "exempt this region", "I am about to launch this player", "this is a staff member in vanish".
- **WHY IT MATTERS:** Every production anticheat needs this, and the reference implementation has it (`AsyncIntave*PermissionEvent`, `TrustFactorResolver`). Without it, servers with legitimate movement plugins have no remedy except disabling the anticheat. It also affects the DAG: an API module and its event/port surface should be planned, not bolted on.
- **RECOMMENDED CHANGE:** Add a roadmap deliverable and a short specification for an integration surface: exemption scopes with expiry, declared-effect registration that feeds the impulse ledger, and query access to coverage and validation status. Deliberately decide whether a trust-factor concept is adopted or rejected, and record the reasoning either way — silence is not a decision.
- **ACCEPTANCE CRITERIA:** A DECISION record on the integration surface and on trust factors, with the module that owns it named.

### M3 — Evidence correlation is defined by example, not by rule, and only within one engine

- **ID:** M3 · **SEVERITY:** MEDIUM · **DOCUMENT(S):** `EVIDENCE_MODEL.md`
- **PROBLEM:** The one concrete rule given is that movement, velocity and derived ground mismatches from the same simulation are one causal family. Nothing defines the general rule, and nothing covers cross-engine correlation — a single false position claim can produce a movement exclusion, a reach exclusion and a timer anomaly, which are three engines with one root cause.
- **RECOMMENDED CHANGE:** Require every evidence record to carry the set of root observation sequence IDs it derives from, and define the correlation rule as overlap of root sets (plus the declared causal key), rather than by category naming.
- **ACCEPTANCE CRITERIA:** The schema includes the root-observation set; a test shows that a single spoofed movement producing movement, combat and timer evidence yields one correlation group.

### M4 — Setback anchors have no age or distance bound, and the no-chunk-load rule conflicts with the teleport API

- **ID:** M4 · **SEVERITY:** MEDIUM · **DOCUMENT(S):** `MITIGATION.md`
- **PROBLEM:** `SafeAnchor` carries an age field but no maximum; there is no maximum setback distance; and "Do not synchronously load a chunk" sits alongside a requirement to teleport, where the ordinary platform teleport will load the destination chunk itself. Side effects of teleporting (fall distance, vehicle dismount, elytra state) are unaddressed.
- **RECOMMENDED CHANGE:** State a maximum anchor age and maximum correction distance, with the overflow action being resync rather than a long-distance teleport; specify the asynchronous teleport path and what happens if its completion is delayed or fails; enumerate the teleport side effects that must be preserved or explicitly accepted.
- **ACCEPTANCE CRITERIA:** Numeric bounds stated with rationale; a test covering an anchor older than the bound and one beyond the distance bound, both resulting in resync rather than teleport.

### M5 — Durable-capture-before-effect may make mitigation effectively unreachable

- **ID:** M5 · **SEVERITY:** MEDIUM · **DOCUMENT(S):** `EVIDENCE_MODEL.md`, `REPLAY_SYSTEM.md`, `MITIGATION.md`
- **PROBLEM:** Automated effects require a complete capsule with a durable receipt; capsules require world/blob closure; replay staging is 2 MiB per session and prehistory up to 10 seconds; closure that exceeds the reservation means "reject capture or shorten to a later coherent checkpoint". Combined with a 500 ms intent deadline, the realistic outcome is that most actionable evidence expires before its capsule is durable.
- **RECOMMENDED CHANGE:** Size a representative capsule before adopting this as the default policy. If it cannot fit the deadline, define the intermediate option explicitly — act on a pinned in-memory capsule with durability completed asynchronously and reconciled afterwards — rather than discovering the coupling in Phase 10.
- **ACCEPTANCE CRITERIA:** Measured capsule sizes and durability latency for three representative scenarios, and a stated policy consistent with the intent deadline.

### M6 — Paper build support policy is unstated (exact pin versus feature detection)

- **ID:** M6 · **SEVERITY:** MEDIUM · **DOCUMENT(S):** `VERSION_SUPPORT.md`, `PACKET_PIPELINE.md`
- **PROBLEM:** "Unsupported server builds fail enforcement initialization" without defining what makes a build supported. Paper publishes builds continuously; pinning exact builds means the plugin refuses to enforce on almost every server, while accepting any build of a version means the transport adapter's internal assumptions are unverified.
- **RECOMMENDED CHANGE:** Define support as a feature-detection contract — a startup probe that verifies the specific internals the adapter depends on (hook points, pipeline names, mapping identities) — with a known-good build list as a fast path and an explicit degraded mode when detection fails.
- **ACCEPTANCE CRITERIA:** A named startup probe with its checks enumerated, and a test that simulates a failed probe and asserts observe-only rather than enforcement.

### M7 — Canonical block state keys must not be the hot-path lookup key

- **ID:** M7 · **SEVERITY:** MEDIUM · **DOCUMENT(S):** `WORLD_MODEL.md`, `COLLISION_ENGINE.md`, `PERFORMANCE.md`
- **PROBLEM:** `BlockStateKey` is "canonical namespaced type + canonical property set + ruleset digest", which is correct for replay stability but expensive as a per-query key in the innermost collision loop.
- **RECOMMENDED CHANGE:** Specify a two-level identity: an interned integer id for runtime lookups plus the canonical key as its stable projection, with the id-to-canonical mapping table recorded in the replay capsule so the ids need not be stable across runs. (This is the correct reading of the reference project's warning that its variant index is random per run — the fix is to record the mapping, not to avoid interning.)
- **ACCEPTANCE CRITERIA:** The two-level identity is specified, and the capsule header includes the mapping table.

### M8 — No automated native-client capture harness is specified, yet the entire oracle story depends on it

- **ID:** M8 · **SEVERITY:** MEDIUM · **DOCUMENT(S):** `TESTING.md`, `VERSION_SUPPORT.md`, `LATENCY_COMPENSATION.md` (required experiments)
- **PROBLEM:** Correctness rests on "version-pinned native-client/Paper integration captures" and "Native-client capture must cover [eleven scenarios]". No document says how those captures are produced repeatably — manual sessions, an instrumented client, a scripted harness — or who re-runs them on every version bump.
- **WHY IT MATTERS:** This is the most likely piece of the plan to be silently skipped, and it is the only independent oracle. Without automation, the fixture corpus stays small and the upgrade checklist becomes unaffordable.
- **RECOMMENDED CHANGE:** Specify the capture harness as a deliverable with its own roadmap slot: how the client is driven, what instrumentation is permitted, how captures are sanitised and stored, and what the per-version re-capture cost is.
- **ACCEPTANCE CRITERIA:** A named harness deliverable and an estimated per-version capture cost.

### M9 — Per-session coverage collapse is invisible to operators

- **ID:** M9 · **SEVERITY:** MEDIUM · **DOCUMENT(S):** `PERFORMANCE.md`, `EVIDENCE_MODEL.md`, `ROADMAP.md`
- **PROBLEM:** Uncertainty and coverage are reported as aggregate metrics. There is no per-session coverage signal, so a player whose validation is entirely UNCERTAIN looks exactly like a player who is fine.
- **RECOMMENDED CHANGE:** Add a per-session coverage summary to the published administrative snapshot, and a non-punitive operational signal for sessions whose coverage is persistently below a threshold. This must never carry cheating weight — it is an availability and investigation signal (and it pairs with H9).
- **ACCEPTANCE CRITERIA:** Per-session coverage appears in the published snapshot with an explicit statement that it carries zero violation weight.

### M10 — Netty event-loop cost is neither budgeted nor measured

- **ID:** M10 · **SEVERITY:** MEDIUM · **DOCUMENT(S):** `PACKET_PIPELINE.md`, `PERFORMANCE.md`
- **PROBLEM:** The adapter performs bounded validation and copying on the channel event loop, including chunk payload copies of up to 2 MiB. The metric list covers worker CPU, main-thread MSPT, queue age and allocation, but not event-loop utilisation or per-packet event-loop time — yet that is shared with Paper's own networking and directly affects every player on that loop.
- **RECOMMENDED CHANGE:** Add a per-packet event-loop time budget and event-loop utilisation to the metric set; treat chunk copy volume as a first-class measured quantity (it is the largest single copy cost in the design and is multiplied by player count).
- **ACCEPTANCE CRITERIA:** Event-loop utilisation and per-packet event-loop time appear in the metric list and in the Phase 1 observe-only benchmark report.

### M11 — A distinct "combat occlusion" shape kind may not correspond to anything in the game

- **ID:** M11 · **SEVERITY:** MEDIUM · **DOCUMENT(S):** `COLLISION_ENGINE.md`
- **PROBLEM:** Three shape kinds are named: movement collision, interaction outline and combat occlusion. The game distinguishes collision, outline/interaction and visual shapes; the client's attack pick ray uses the outline path. A third kind invented for combat risks encoding a rule the client does not have.
- **RECOMMENDED CHANGE:** Make the shape-kind set a property of the version profile, verified against captured behaviour, rather than a fixed architectural triple. If combat occlusion resolves to the outline shape on the target version, say so and keep the alias for clarity.
- **ACCEPTANCE CRITERIA:** Shape kinds are declared per version profile with provenance, and a fixture verifies combat occlusion against captured client behaviour rather than assumption.

### M12 — Placement and interaction validation has no named owner (companion to B1.3)

- **ID:** M12 · **SEVERITY:** MEDIUM · **DOCUMENT(S):** `COMBAT_MODEL.md`, `ROADMAP.md` (phases 18, 19)
- **PROBLEM:** Phase 18 requires world prediction and reconciliation with placement rules, and `WORLD_MODEL.md` defines the client prediction overlay, but no module is assigned ownership of placement semantics (block-against-face rules, replaceable blocks, sequence acknowledgement).
- **RECOMMENDED CHANGE:** Assign it when resolving B1.3, and state the relationship between the prediction overlay owner (`world`) and the placement rule owner.
- **ACCEPTANCE CRITERIA:** A module owns placement semantics and the document says so.

### M13 — Intave analysis omits several directly relevant reference observations

- **ID:** M13 · **SEVERITY:** MEDIUM · **DOCUMENT(S):** `INTAVE_REFERENCE_ANALYSIS.md`
- **PROBLEM:** The analysis is accurate and well-hedged but incomplete in ways that would have caught gaps in this very package. Details in the INTAVE COMPARISON section below.
- **RECOMMENDED CHANGE:** Add the five omitted observations listed there, each as an observation with its own source and an explicit Papea2AC position.
- **ACCEPTANCE CRITERIA:** Each omission is addressed with a stated Papea2AC decision, including a deliberate rejection where that is the answer.

---

## LOW PRIORITY

- **L1 — Skeleton drift.** `pom.xml` targets Java 21 and `spigot-api 1.21.11`; `plugin.yml` declares `api-version: '1.21'` and `authors: [ Emojiado ]`. The target is Java 25 and Paper 26.x. `ARCHITECTURE.md` already plans the migration; add `api-version`, the Java toolchain and the author/identity fields to the explicit migration checklist so identity is preserved deliberately rather than by accident. *Acceptance:* the migration checklist names each field and its post-migration value.
- **L2 — Wrapper JAR ignore rule.** `.gitignore` contains a bare `*.jar`, which will exclude `gradle/wrapper/gradle-wrapper.jar`. `ARCHITECTURE.md` notes this; make it a concrete checklist item (`!gradle/wrapper/gradle-wrapper.jar`) since a missing wrapper JAR silently breaks reproducible builds for everyone else. *Acceptance:* the negation rule is present after migration and a clean clone builds.
- **L3 — Mermaid diagram node labels are not module names.** `ARCHITECTURE.md`'s diagram uses `player / timeline`, `packet -> protocol` and `evidence -> violation` as node labels, which do not correspond to modules in the governing table. The table is declared authoritative, but implementers read diagrams. *Acceptance:* node labels match module names exactly.
- **L4 — "Paper self-tests" imprecision in the Intave analysis.** The pinned `tests.yml` runs a Gradle task named `testPaper<version>` across twenty versions from 1.8.8 to 26.2, but the bundled server artifacts are mostly Spigot jars, with one Paper jar and one alternative 1.8.8 server. Describing all twenty as "Paper self-tests" adopts the task name as a fact. In a document whose whole thesis is not overclaiming from filenames, this is worth tightening. *Acceptance:* the sentence describes the task matrix and notes the artifact mix.
- **L5 — README scope sentence.** The README says "Module directories, Gradle files, production interfaces and tests described below are designs for later approved phases" but nothing is described below that point. Harmless, but it reads as a truncated section. *Acceptance:* sentence corrected or the referenced content added.
- **L6 — Evidence retention defaults deserve a named owner.** "Retain local replay files seven days or 5 GiB" is stated as an operational default with an appropriate disclaimer; it should be part of the configuration schema from M1 rather than a prose constant. *Acceptance:* retention appears in the configuration schema.

---

## MODULE ARCHITECTURE

**Verified independently.** I re-derived the topological order from the adjacency table: `api → version → protocol → packet → player → world → collision → physics → simulation → combat → evidence → checks → mitigation → replay → core → platform-paper`, with `testing` test-scoped. Every module's declared dependencies appear strictly earlier. The graph is acyclic, and Astra's claim is accurate rather than asserted. `testing` is correctly excluded from production dependencies, and the platform isolation rule (only `platform-paper` may import Bukkit/Paper/CraftBukkit/NMS/Netty) is correctly stated and enforceable in CI.

**What is right beyond the DAG:** `checks` is deliberately denied `world`, `collision` and `physics`. That is a genuinely good structural decision — it makes "a check reimplements its own physics" a compile error rather than a code-review finding, which is the failure mode that produces inconsistent anticheats. `evidence` depends only on `api` and `mitigation` depends on `evidence`, so the direction of the evidence/mitigation relationship is correct: detection cannot reach punishment. `replay` not importing `core`, with `core` supplying a reducer through a port, is the right inversion.

**What is wrong:** B1 (four behaviours with no legal path) and B2 (`api` contents undefined, replay forcing the domain model into it). Both are concentrated in the parts of the graph that Phase 1 will encode first.

**On the "17 modules" question the risk table raises:** the count is not the problem, and I would not reduce it. Modules with thin contracts (`version`, `evidence`) exist to enforce a direction, which is a legitimate reason. The real cost is B2: if `api` absorbs the domain model, the other sixteen boundaries stop meaning much. Fix `api` and the granularity is justified; leave it undefined and the modularity is decorative.

**Build system note.** Every document specifies Gradle; the repository is Maven with a Spigot dependency. `ARCHITECTURE.md` plans an atomic migration and the README flags the skeleton as non-target. That is handled. I note it only because `VERSION_SUPPORT.md` requires a Gradle wrapper "selected from a release supporting execution on Java 25 and checksum-pinned" — a concrete constraint that should be verified at migration time, not assumed.

---

## THREAD SAFETY

**Trace of a packet, as specified.** Channel event loop observes → bounded validation/copy into owned bytes → sequencer assigns `captureSeq` and offers to the connection FIFO as one event-loop operation → actor on its fixed shard consumes in `captureSeq` order → reducer reads projection at N−1, computes a bounded transition, validates invariants, installs new roots, appends commit N → evidence and replay records emitted → platform effects go out as bounded requests whose outcomes return as future observations with causal IDs.

**Ownership is unambiguous.** `PlayerSession` is an actor-owned directory of components; only the session actor mutates them; other threads submit observations, never setters; mutable `PlayerData` is never exposed. The ownership table assigns each context its mutable set and its prohibitions. This answers "who owns PlayerData" and "can two threads mutate it" cleanly, which is more than most designs manage.

**Ordering is guaranteed at the right point.** Sequence assignment and FIFO offer are one event-loop operation, which specifically prevents the reserve-then-stall hole. Release/acquire publication is stated. The main-thread bridge uses offer-then-coalesced-drain-flag with clear-then-recheck, which is the correct lost-wakeup-free pattern.

**Races the design handles.** Disconnect versus processing (tombstone, epoch-scoped cancellation, late callbacks ignored but counted). Teleport versus movement (dedicated teleport ledger, pre/post hypotheses, generic ACK explicitly cannot confirm a teleport). Velocity versus movement (impulse ledger with application intervals). World update versus movement (observer knowledge frontier). Entity update versus attack (observer entity history plus incarnation). Shutdown versus queued work (disable prevents admission first, bounded drain deadline, no unbounded join). Effect admission versus gap invalidation (single atomic generation transition with an explicitly acknowledged linearization boundary). This is a good list and it is close to complete.

**Races the design does not handle:** shard failure reclamation and quarantine recovery (H10); sustained per-actor work fairness and head-of-line blocking (H9).

**One structural observation.** Because the AC observes rather than proxies, it cannot apply TCP backpressure — Paper will process packets regardless of whether Papea2AC kept up. This is correct behaviour, but it means "OVERLOADED" is a coverage-loss state, not a flow-control state. `THREADING_MODEL.md` should say so, because "backpressure" implies a control the system does not have.

---

## PACKET ORDERING

Well specified overall. Per-direction TCP order preserved; timestamps explicitly not a reorder key; logical capture order honestly described as "the observed serialization … not a claim of exact client tick order"; bundles normalised as one envelope with ordered children; fragmented frames correctly decoupled from movement ticks; `WriteAttempt`/`WriteOutcome` split with ACK-before-write-callback retained by ID rather than discarded. Step 5's rule that "later events cannot commit past an unfinished earlier event" closes the incremental-decode hole. The refusal to "drop only a movement packet and continue simulation as if contiguous" is exactly right.

Gaps: B3 (no placement invariant, inbound third-party cancellation unhandled) and H8 (incomplete source enumeration). Both are in the pipeline document and both are cheap to close.

One detail worth pinning during Phase 1: bundle atomicity. "Bundles are normalized as one envelope with ordered children sharing an atomic application group where required by the version" leaves "where required" to be determined. Since bundle semantics decide whether two entity movements were visible to the client simultaneously — which feeds combat scene coherence — the determination should be an explicit version-profile entry with a fixture, not an implementation judgement.

---

## ACKNOWLEDGEMENT MODEL

This is the strongest document in the package and it already answers Review 3's question correctly.

The four states Review 3 asks for are present and distinguished: SENT (`WriteAttempt`), DELIVERED/ACKNOWLEDGED (`ACK_OBSERVED → RECEIPT_RESOLVED`), LIKELY APPLIED (`APPLICATION_CONSTRAINED`, gated behind a guarantee profile), and OBSERVED THROUGH SUBSEQUENT BEHAVIOUR (before/after application branches over adjacent movements). The decisive sentence is: "Until established against the target vanilla client, marker ACKs supply receipt/RTT evidence only and must not advance a physics-application frontier." That is the correct default and it forecloses the specific false-positive class Astra listed as risk 1.

Also correct: teleport confirmation has a dedicated ledger and generic ACKs cannot substitute; write success proves handoff only; forged or accelerated ACKs are treated as protocol observations, not clocks; ACK suppression leads to unavailable coverage or a resource-policy disconnect, never to fabricated movement violations; ID reuse requires no outstanding ambiguity or a new marker generation; contradictory constraints produce a diagnostic and UNCERTAIN rather than an arbitrary pick.

The residual risk is not correctness but cost: with application unresolvable, every physics-affecting update stays ambiguous, and nothing bounds how many are ambiguous at once (H5). I would downgrade Astra's risk 1 from "blocker for dependent enforcement" on false-positive grounds and re-file it as a coverage-feasibility risk.

One addition worth making: the ledger should record, per update, whether a *later* observation is logically incompatible with one of the branches — a movement that is only explicable if the impulse was already applied retires the "not yet applied" branch. The document gestures at this ("Evaluate eligible before/after-application branches", "Use updated knowledge") but never states branch retirement as a mechanism, and it is the main way the unresolved set shrinks without a guarantee profile.

---

## PLAYER STATE

Decision S1 is right, and the component table is the most operationally useful artefact in the package: every component has a single writer, a named mutation point, defined snapshot contents, and count/byte/lifetime bounds. "Caps deliberately sacrifice coverage before memory safety" and "A target excluded by the entity cap is UNKNOWN, not nonexistent" are exactly the right defaults. "Client-controlled IDs cannot create unlimited map keys" closes a real attack. Separating claimed and supported poses in `MovementState` is the correct primitive — H14 is only about which one combat consumes.

The commit protocol (read at N−1, compute, validate invariants, install roots, append commit, publish read-only summary) with quarantine on failure is sound: partial data cannot become a trusted checkpoint. Administrative snapshots being published at a bounded cadence, with readers unable to demand a synchronous actor read, correctly prevents the admin path from becoming a lock.

Issues: H3 (Timeline's record-count bound versus its time bound), H13 (16 MiB per session versus global budget at scale), and the absence of per-session coverage in the published summary (M9).

One smaller point: `CombatState` allows 64 pending attacks and "overflow yields uncertain outcomes", while `ViolationState` keeps 128 correlation groups and 256 evidence summaries. In a sustained fight, attack-resolution overflow and evidence-group overflow are reached in a correlated way, so the aggregate behaviour under overflow should be stated once rather than inferred from three separate rows.

---

## PLAYER TIMELINE

Decision L1 is correct and unusually careful: `captureSeq` as the deterministic processing order with causal constraints retained separately; server ticks, estimated client ticks and arrival times as distinct fields; "An event is never reassigned to a tick merely to make simulation match"; "Earliest/latest possible application positions derive from constraints, not from choosing whichever historic position makes an attack pass". That last sentence rules out the single most common anticheat cheat-in-the-implementation, where the engine picks whichever history makes the verdict come out convenient.

Epoch discipline (world epoch on dimension/respawn, validation epoch on resync, connection epoch on reconnect) is clean, and 64-bit sequence wrap handled as an explicit new session rather than signed-comparison tricks is the right call. `HISTORY_MISSING` with "No fallback to present-day player/entity state" is correct. Checkpoint-anchored replay ranges with dependent-range eviction is right.

The problem is capacity, not semantics: H3. The document also does not say whether outbound entity traffic produces timeline records, which is the single fact that determines whether the ring holds two seconds or ten.

---

## WORLD MODEL

Decision W1 is correct, and I want to record why, because it is the decision most likely to be second-guessed on cost grounds: validating against the server's live world would produce false positives on every Paper anti-xray section, since the client collides with obfuscated blocks the server considers air. Observer-specific state is not merely more rigorous, it is required for correctness on a common server configuration.

Also right: KNOWN / UNKNOWN / AMBIGUOUS with "Unknown is neither air nor solid"; unload tombstones preventing stale shared sections from reappearing; canonical `BlockStateKey` rather than runtime palette IDs; entity histories built from packets sent to the attacker with "Do not read the target actor's most recent position as a substitute"; sharing immutable geometry but never mutable observer interpolation state; "Never synchronously load chunks to satisfy validation"; and per-session fairness so one pathological client cannot evict everyone else's views.

The gap is H4: baselines are unrecoverable, and three separate paths lose them. The related cost question — decoding and copying chunk payloads per observer on the event loop — is unbudgeted (M10) and unmeasured, and it is the largest single throughput risk in the design.

`WORLD_MODEL.md`'s acceptance examples are good (absent versus air, delayed door, cancelled placement, fake barrier, unloaded section, border crossing, metadata-driven size, entity ID reuse, dimension transition). Add one: a section evicted and re-entered, asserting the coverage result is explicitly UNKNOWN rather than silently stale.

---

## COLLISION ENGINE

The shape side is strong. Separating geometry primitives from versioned contextual shape resolution, requesting an explicit `ShapeKind` per use, including neighbour signature and pose in context keys, refusing to cache context-sensitive shapes under static keys, bounding recursive neighbour resolution with cycle detection returning UNKNOWN rather than an empty shape, and requiring "Cache eviction changes performance only, never query results" — these are the right rules, and the last one is a property most implementations violate.

`MovementQuery` is well shaped: clipped displacement, contacts and normals, ground-support provenance, swept coverage and uncertainty, with "Do not invent a universal XYZ ordering" and client-claimed ground compared against collision-derived support rather than trusted. `RayQuery`'s explicit parallel-slab handling, NaN/infinity rejection, origin-inside-shape and equal-distance tie rules are the details that usually bite later.

"Tolerances must name their source … No global epsilon expansion that compounds on each tick" and "Contact tolerance is separate from a violation threshold" are the two sentences that most anticheats need and lack.

**Review 6's specific question — are collision shape and movement-affecting properties conflated?** No. They are separated correctly for shapes, and the properties are simply missing (H6). The fixture list is also incomplete in the same direction: it names shape cases thoroughly but omits friction, speed factor, bounce, inside-block drag and column forces. Also missing: entities as colliders (H7).

M11 (whether "combat occlusion" is a real third shape kind or an alias for the outline shape) belongs here too.

---

## PHYSICS SIMULATION

Decision M1 is the right frame: ask whether an observed movement has any legitimate explanation, not whether it exceeds a threshold. The five contract types are well chosen, and `SimulationError` carrying per-axis residual intervals and threshold provenance rather than a single norm is a detail that pays for itself during every false-positive investigation.

The completeness model is the best part of the package: completeness spans input-domain, world, application-branch, step-range and pruning justification; INVALID requires all relevant flags true; "A witness can establish VALID before exhausting remaining candidates" is the correct asymmetry; "Approximate merging, top-K pruning or heuristic branch dropping sets completeness false"; "If a merged superset fails to reach the observation, its conservativeness must be proven before using that exclusion". Budget values and branch-order version being part of replay is what makes budget-dependent verdicts reproducible.

Continuation safety is also right, and `MOVEMENT_SIMULATION.md`'s "INVALID observations do not automatically overwrite the trusted baseline … continuing from an impossible trusted state would cause cascaded false positives" is the single most important false-positive-prevention sentence in the document. So is "Zero displacement does not prove ignored knockback when collision could absorb it".

The problems: H1 (the budget cannot fund complete exclusion by enumeration), H2 (the abstraction that would fix it is undefined), H6 and H7 (missing physics inputs). Also, the step specification honestly says it "names responsibilities, not an asserted universal Minecraft order" and defers exact ordering and float casts to Phase 5 with independent fixtures — that is the correct posture, and it should stay that way rather than being filled in from memory.

Review 7's mandatory distinction — true invalid versus search incomplete — **is** present and correctly specified. My finding is that the budget makes the first branch nearly unreachable, not that the distinction is missing.

---

## INPUT RECONSTRUCTION

Decision I1 is right: inputs are hypotheses, never claims about which keys were pressed; client-reported input is a constrained claim, not truth. "Reported rotation is matched at its actual packet position in the timeline. A later rotation cannot be moved backwards to legitimize an attack" is exactly the rule that prevents the engine from cheating in the defendant's favour. Refusing to assume legacy nine-direction keyboard input is complete for every client is correct for the modern protocol. Reusing the simulation budget rather than adding a second multiplying budget is right. "Reconstructed input confidence is diagnostic; it cannot turn an incomplete search into INVALID" is right. The regression case list is good and specific.

One thing the document should make explicit because it is the main performance lever available: when the protocol carries a client input claim, that claim is the *first witness candidate to try*. Since VALID needs only one legitimate explanation, using the claimed input to establish VALID is sound even though the claim is untrusted — a lie that produces a legally reachable state is not a detectable lie at this layer. The document already permits this implicitly but never says it, and it is the difference between cheap common-case validation and enumerating 144 branches for every walking player.

The sentence "For every pruned branch class, supply an independent reason it cannot explain the observation or mark search incomplete" is the right obligation, and it is exactly what H2 must be specified enough to satisfy.

---

## LATENCY COMPENSATION

Covered in ACKNOWLEDGEMENT MODEL. Review 9's specific prohibition — no "high ping → exempt" — is honoured explicitly: "High ping still permits validation where state is known; uncertainty is restricted to dependent world/impulse/combat queries" and "No silent packet sampling, infinite buffers or blanket high-ping exemptions". RTT is confined to diagnostics and horizon sizing, with `RTT/2` explicitly rejected as a history index. Separate capture-monotonic, Paper tick and inferred client step clocks, with wall time "for human labels only", is correct. "TCP head-of-line delay and application lag should not be reported as packet loss merely from a missing response" is a nice piece of precision.

The required-experiments list is the right list. The gaps are H5 (unresolved-set bound) and H3 (the 10 s horizon is not actually available to every consumer that cites it).

---

## MOVEMENT VALIDATION

Decision A2's semantics are correct and I would not change them: VALID means a supported legitimate explanation was found, not proof of honest intent; INVALID requires every admissible explanation excluded with complete relevant knowledge; UNCERTAIN cannot accrue punitive weight; resource and network anomalies are measured separately and "never fabricated cheating evidence".

Review 10 asks whether UNCERTAIN is preferred when coverage is incomplete. It is, by construction, in every relevant document. This is the part of the architecture I would defend against pressure to loosen.

The practical risk is the inverse of the usual one: not that INVALID is too easy, but that it is unreachable (H1/H2), and that the resulting all-UNCERTAIN outcome will be read as success because false positives will be zero. `PERFORMANCE.md` already anticipates this — "A fast run dominated by UNCERTAIN is a failed coverage claim" — and `TESTING.md` requires that "An all-UNCERTAIN implementation must not pass the full suite". Those two sentences should be promoted into the Phase 8 exit gate as a numeric threshold, not left as principles.

---

## COMBAT MODEL

Decision B1 is right and rejects the correct alternatives (fixed centre distance, RTT/2 rewind, any-position-in-a-window). The key safety rule is stated precisely: "Arbitrarily favorable combinations of unrelated historic states would create bypasses, so hypotheses must share causal constraints" — combined with "Metadata, movement and spawn visibility cannot be mixed from incompatible frontiers", this closes the scene-shopping bypass that most historical-rewind implementations have.

Also right: entity incarnation so ID reuse after destroy never links to an earlier entity; attack packets that carry no rotation using preceding eligible rotation with "Do not borrow later aim"; distinguishing reach miss, angular miss, occlusion and invalid target rather than collapsing them; the superset rule — an enclosing swept box can *disprove* but hitting it cannot *prove*, so a superset hit yields UNCERTAIN until a concrete witness exists. That asymmetry is correct and is the same shape as the envelope rule H2 needs.

The enforcement boundary is honest: asynchronous results arrive after Paper handled the attack, initial policy is record/alert only, no claim of retroactive cancellation or damage rollback. Requiring "Evidence contains enough geometry and history references to replay, not only a reach number" is right, as is measuring false outcomes per attack and per player-hour including UNCERTAIN coverage.

Gaps: H14 (which attacker eye is authoritative) and H3 (entity history is shorter than the horizon combat is written against).

---

## EVIDENCE MODEL

Decision E1 is correct, and the specific double-counting question in Review 13 is answered: "Movement mismatch, velocity mismatch and derived ground mismatch from the same simulation are one causal family; they do not contribute three independent strikes." Also correct: resolution of provisional evidence replaces its contribution exactly once; world-gap and resource events carry zero cheating weight; statistical windows need minimum samples, a calibration population, confounder handling and held-out validation; "A score is not a probability unless calibrated as such"; a fixed category registry so client strings never become map keys; observe-only initial policy including for deterministic exclusions; punishment as an administrative, permission-gated, audited action outside engine logic.

The evidence kinds are well chosen — separating `DeterministicExclusion` from `BehavioralFeatureWindow` from `ResourceHealthEvent` from `ProtocolSafetyEvent` means an operator can always tell what kind of claim they are looking at.

Gaps: M3 (correlation defined by example and only within one engine) and H11 (no cross-session correlated-anomaly guard — the correlation model is per-session only, and the dangerous correlations are across sessions).

---

## MITIGATION

Decision G1 is right, and Review 14's stale-setback scenario is directly addressed: `SafeAnchor` carries a world revision, collision clearance, trusted velocity context and age; "Latest claimed position is not automatically safe"; block changes, unload or mode changes invalidate the anchor and force resync rather than "choose an arbitrary nearby coordinate". The intent carries all epochs plus the transport health generation, with a main-thread recheck immediately before the platform call.

The admission/invalidation linearization is described honestly, including the case that cannot be won: "cannot undo an effect whose platform call has begun. This is an explicit linearization boundary, not a guarantee of rollback." That honesty is worth more than a false guarantee.

Feedback-loop prevention is good: own teleports tagged with action IDs, dependent validation uncertain during recovery, "Do not award violation weight for motion caused by the anticheat itself", one correction per two-second cooldown, three failures disabling setbacks for the session, loop breaker does not ban, resynchronisation framed as recovery rather than punishment. Deferring damage rollback and retroactive cancellation as needing their own transaction semantics is the right scope decision.

Gaps: B1.2 (no engine path for clearance validation), M4 (no anchor age or distance bound, teleport API conflict, unaddressed teleport side effects), H11 (no global guard above the per-session loop breaker).

---

## REPLAY SYSTEM

Decision R1 — schema and round-trip harness before detections — is the right sequencing call and the one most projects get wrong. The capsule structure (header with build, protocol profile, ruleset/registry/config digests, numeric profile, branch-order version, limits; body with coherent checkpoint, sequence-ordered normalized inputs, relative monotonic times, write outcomes, application constraints, gap/health/budget aborts, action outcomes, storage receipts; footer with counts, checksums, covered range, complete/incomplete flag and expected output hashes) is complete enough to be implementable.

The determinism rules are right: exclude unstable object IDs, wall time, thread scheduling and iteration order; preserve float bits; record seeds for anything randomized. Recording `BudgetAbort` as an *input* is the key trick that makes budget-dependent verdicts reproducible, and it is correctly specified. The linked-continuation design resolves the circular "an action must be recorded before it occurs" problem cleanly. Treating input files as untrusted with pre-allocation limits, no Java deserialization, path-traversal rejection and decompression-bomb limits is right. "Do not rewrite fixtures to conceal a failed detector" is the right cultural rule to write down.

Gaps: B1.4 and B2 (module and type ownership), H12 (transcendental determinism across hosts), M5 (durable-capture coupling versus the 500 ms intent deadline).

One point Review 15 asks about that is already handled well: replay must not create server lag. Serialization and compression happen on the I/O executor, the queue is bounded at 64 MiB globally, overload drops an entire capture with an explicit marker rather than partially, and shutdown abandons incomplete captures under a bounded deadline rather than flushing synchronously. That is the right set of choices.

---

## PERFORMANCE

Decision F1 is correct in spirit — cap every resource, publish only measured capacity — and the methodology section is better than most production projects have: full environment recording, a comparison baseline of Paper alone versus observation-only versus fully enabled, a load matrix with named scenario classes, warmup/measurement/repetition structure, a pre-release soak, fault profiles, coordinated-omission safeguards, and a requirement to record unsuccessful cells and saturation rather than only successful peaks. The refusal to invent nanosecond promises is right, and the JMH recipe is reasonable.

The acceptance rules are the strongest part: "A fast run dominated by UNCERTAIN is a failed coverage claim", "Zero observed false positives is not proof of zero risk", and the requirement to group correlated per-player events rather than treating them as independent trials.

Gaps: H13 (budget versus scale target), H9 (no sustained per-session work quota, no degradation ladder, no cost-maximising adversarial profile), M10 (event-loop cost not budgeted or measured).

What must be measured before any performance claim is accepted, in priority order: (1) event-loop time per packet class, especially chunk payload copy, at realistic view distances; (2) retained bytes per session by component after churn and GC, against the estimate equation; (3) achieved reconstruction horizon versus the target horizon under dense entity traffic; (4) candidate-step and geometry-unit histograms with saturation rate; (5) fraction of movement and combat events reaching VALID or INVALID rather than UNCERTAIN, per scenario — this is the denominator that makes every other number meaningful.

---

## MEMORY / ALLOCATION

The budget registry is the right artefact and the accounting rule — counts *and* retained bytes including object overhead, reconciled against measured retained heap with allocator slack — is correctly stated. Per-session and global limits both being required, with the explicit note that "multiplying per-player limits is not a sufficient global bound", is right. Sparse air/unknown representation, content-hash equality confirmed by canonical bytes before dedup, charging pins against both owning and shared budgets with "no hidden pinning exemption", and requiring idle cleanup and disconnect to release everything are all correct.

Estimated scale, using the stated caps:

| Players | Per-session reservations | Shared store + shape cache | Total anticheat heap |
|---|---|---|---|
| 100 | up to 1.6 GiB (≈1.2 GiB at the component floor) | 0.64 GiB | ≈2.2 GiB |
| 250 | up to 4 GiB (≈3 GiB floor) | 0.64 GiB | ≈3.6–4.6 GiB |
| 500 | up to 8 GiB (≈6 GiB floor) | 0.64 GiB | ≈6.6–8.6 GiB |
| 1000 | up to 16 GiB (≈12 GiB floor) | 0.64 GiB | ≈12.6–16.6 GiB |

These are the document's own caps multiplied out, not measurements, and caps are not typical usage. But the default global budget is `min(1 GiB, 25% heap)`, so at every listed target the default is below even the component floor. That is H13, and it is the finding most likely to force design change if left until Phase 24.

Allocation hot paths that need per-event allocation budgets, not just retention budgets: envelope normalization, collision scratch geometry (already actor-local and recycled — good), candidate expansion, timeline record creation, and evidence construction. `PERFORMANCE.md` measures "bytes allocated/event" globally; a per-path breakdown is what actually enables optimisation.

---

## BACKPRESSURE

Review 17 requires an explicit answer, and the design gives one. When packets arrive faster than validation can process them: the ingress offer fails; the sequencer increments `transportGeneration` and atomically marks the connection unhealthy, recording `firstMissingSeq` in an out-of-band fixed-size latch; a reserved control mailbox carries `Gap`, and if even that is unavailable the latch still prevents new effect admission; the actor checks the latch before each event commit; platform effect execution requires generation-checked admission; in-flight evidence from an older generation is retained diagnostically but cannot admit new actions. Recovery is `HEALTHY → GAP/OVERLOADED → RESYNC_PENDING → HEALTHY(new validation epoch)`. Main-thread bridge offer failure uses the same barrier with reason `LostServerObservation`. Replay overload drops an entire capture with an explicit incomplete marker. Main-thread action queue saturation rejects intents with outcomes rather than blocking the shard.

Against Review 17's prohibitions: no silent dropping of critical packets (gap is explicit and latched); no reordering (sequence assignment and offer are one operation); no unbounded queue growth (count and byte bounds at every stage); no indefinite Netty blocking (the event loop never waits for the actor); no `PlayerData` corruption (single writer plus commit-or-quarantine). This is a genuinely good answer and I do not request changes to its structure.

Three additions are required. (1) State that Papea2AC cannot apply flow control — it observes rather than proxies, so the server processes packets regardless, and OVERLOADED is therefore a coverage-loss state, not a throttle. (2) Acknowledge that recovery is partial: sequencing recovers, world coverage does not, because no outbound world resynchronization exists (H4). (3) Add the sustained-cost dimension — the current model handles packet *rate* overload but not validation *cost* overload, which is the attacker-controllable one (H9).

---

## VERSION SUPPORT

Verified independently and accurate. Mojang's manifest returns `latest.release = 26.3` with the release timestamp the document cites; Paper 26.3 builds are ALPHA with 26.2 as the stable download; Paper requires Java 25 for 26.1+. Decision V1 — target the latest stable Minecraft with a stable matching Paper build, use 26.2 as a provisional development baseline with a separate 26.3 research lane, and refuse to certify either as latest-version support — is the correct call on a release-day transition, and the explicit instruction to recheck channels before the Phase 1 migration is right.

Separation is correct: `VersionProfile` is pure (protocol schema, registries, numeric rules, locomotion ordering, shapes, dimensions, poses, interpolation, input domains, attributes, reach, acknowledgement guarantees), while `platform-paper` holds mappings, hooks and API differences, with "Transport details do not enter physics". Per-rule support states (VERIFIED / OBSERVE_ONLY / UNSUPPORTED) with provenance and test coverage is exactly the right granularity, and "Unknown registry states or custom data-pack behavior produce unavailable capabilities, not a nearest-version fallback" prevents the classic silent-wrong-ruleset failure. Refusing to assert any protocol integer, ACK tick guarantee or operation order from research alone is the right discipline. The upgrade checklist is complete.

Gaps: M6 (what makes a Paper *build* supported) and H14's cousin in deployment terms — the scope statement declares ViaVersion, Geyser, Folia, proxies and modded movement unverified. That is the correct engineering position, but it is also a statement that the initial supported envelope excludes most public servers. It should be stated as a product constraint with a plan, not only as a list of unverified items, because "detect identifiable translation paths and degrade" will be the common path rather than the exception. Also worth adding to the version-sensitive list explicitly: server tick rate and tick freeze state (see H8), which no current document treats as a version/runtime variable.

---

## INTAVE COMPARISON

I independently re-inspected the reference at the pinned revision (tree, README, `STRUCTURE.md`, `BLOCK_SYSTEM.md`, `CHEATSHEET.md`, `tests.yml`).

**Accuracy: good.** The pinned SHA resolves. Every claim I checked is supported by its cited source and is appropriately hedged. The README does describe three pillars (movement simulation, heuristics, cloud ML), does claim multi-tick simulation between consecutive movements, does describe late-acknowledgement compensation, does gate cloud detection behind a paid plan, does state 1.8–26.2 support, and is Polyform Perimeter licensed. `BLOCK_SYSTEM.md` does describe Material-based types, random per-run variant indexes, multiple shape use-cases and post-cache patches with a re-entrancy warning. `CHEATSHEET.md` does warn about distant block access and does advise main-thread transactions. `STRUCTURE.md` does describe checks, dynamic active modules, static passive modules and legacy high-order services. The CI matrix does run twenty versions from 1.8.8 to 26.2. I found no unsupported superiority claim, no copied implementation, and no inference presented as observation. The repeated "this is not an assertion that Intave lacks X" qualifiers are correct discipline, occasionally to the point of being hard to read.

**Where the comparison was too literal:** nowhere serious. The one place worth watching is the three-shape-kind model (M11) — the reference's "collision / outline / visual" distinction is real, but Papea2AC's third kind is "combat occlusion", which is a different concept and may not correspond to a distinct shape in the game.

**Useful ideas the analysis missed** — each visible in the pinned public tree, each relevant to a gap in this package:

1. **A separate block-physics layer.** `block/physics/` contains `BlockProperties`, `MaterialMagic`, and per-material physics for bubble columns, fluids, honey, powder snow, slime, soul sand, webs, berry bushes and beds, plus `block/inside/EntityMovement`, entirely separate from the shape system. This is direct external corroboration that shapes and movement-affecting properties need distinct contracts — H6. The analysis discusses the block system only as a shape/cache story.
2. **A permission and integration surface.** `world/permission/*` and `access/player/event/AsyncIntave*PermissionEvent` show a plugin-facing channel for declaring what is legitimate. Papea2AC has none (M2).
3. **A trust-factor concept.** `trustfactor/` and `access/player/trust/*`. Papea2AC's implicit position is that per-player trust is a form of exemption to be avoided. That may well be right, but it should be an explicit rejected alternative rather than an omission.
4. **Moving world border and block-driven entity movement.** `world/border/MovingWorldBorder` and `block/tick/piston/PistonSlimeMovement`, `ShulkerBoxPhysics`, `SulfurGeyserPhysics` treat "the world moves the player" as first-class. Papea2AC mentions pistons under world temporal rules but has no locomotion mode or impulse source for being moved by blocks.
5. **Three-tick search with frequency-ordered branches.** `check/movement/physics/search/` contains `ThreeTickSimulationSearchTest`, `TickSearchTest`, `BranchFrequencySorterTest` and `ThreeTickBranchFrequencyTest`, and the branch package enumerates branchers per input dimension. This independently corroborates Papea2AC's three-step cap and its "cheap likely witnesses first" ordering, which is worth recording as external support for a number that currently reads as arbitrary. It also sharpens H1: a comparable engine reaches three ticks with *dimension-specific branchers*, not a flat 4096-evaluation budget.

**Where Papea2AC's approach is better justified than the reference's, on the public evidence:** compile-time module boundaries versus package convention (assuming B2 is fixed); replay-stable canonical block identity versus per-run random variant indexes; explicit completeness certificates and an UNCERTAIN class; and evidence provenance with causal families. None of these is a claim that the reference is defective — only that Papea2AC's choices are defensible on their own reasoning.

**Where the reference is ahead and Papea2AC should not pretend otherwise:** a twenty-version CI matrix running real servers; a shipped operator surface (analytics, metrics, reports, diagnostics) rather than one deferred to phase 26; movement-recording-based physics regression already in place; and a deliberate integration API. Papea2AC's answer to all of these is "later, with better rigour", which is a legitimate plan but is not yet evidence of anything.

---

## TEST COVERAGE

Decision Q1 is right, and the core insight — "A simulator can be consistently wrong and still pass tests derived from its own formulas" — is the thing most anticheat test suites get wrong. "Expected physics values must cite an independent source or controlled measurement; never derive both expected and actual through the same simulator" is the correct rule.

The layer table is well constructed, with real oracles per layer rather than "unit tests exist". Concurrency using deterministic scheduler tests plus jcstress-style invariants with "exactly one actor writer" as an asserted property is right. The fault-injection list (omit an outbound observation, fail a write, exhaust capture pins, race reconnect with an intent, throw during a component transition, terminate a shard, change registry, cancel a platform teleport) is genuinely adversarial, and the required result — "quarantine/uncertainty with bounded cleanup, not a fabricated violation" — is the right assertion. The false-positive workflow (reproduce, minimize while preserving failure, add a fixture that fails for the demonstrated reason, fix the shared engine, independent review, permanent retention) is correct, as is forbidding fixture deletion or loosening without a reviewed explanation.

**Boundaries that are missing:**

1. **Block-property fixtures** (H6): friction, speed factor, bounce, inside-block drag, column forces. The collision fixture list covers shapes only.
2. **Entity-push fixtures** (H7): nothing exercises crowded movement.
3. **Cross-platform replay determinism** (H12): the requirement says cross-process, not cross-host; the trig issue only manifests across architectures.
4. **Foreign packet handler tests** (B3): third-party handler installed inside the observation boundary, and third-party inbound cancellation.
5. **Correlated multi-session fault tests** (H11): a server-wide fault must be shown to suspend effects globally before individual loop-breakers trip.
6. **Shard death** (H10): `TESTING.md` lists "terminate a shard" under fault injection, but the assertion needs to include reservation and pin reclamation and the assignment-rotation change, which no document currently specifies.
7. **A capture harness** (M8): the native-client capture obligations have no stated production mechanism.
8. **Coverage-floor gates**: `TESTING.md` says "An all-UNCERTAIN implementation must not pass the full suite" and requires "minimum supported-scenario coverage in qualified fixtures", but no numeric floor exists. Given H1, this is the gate most likely to be quietly satisfied by a system that validates nothing.

Testability without a running server is well handled: engines are platform-independent by module rule, the offline replay runner lives in `testing` and supplies recorded clocks and outcomes rather than Bukkit, and "The same canonical snapshot must resolve identically without Paper in an offline replay" is an explicit obligation. Physics, collision, simulation, evidence and timeline are all independently testable by construction — which is the answer Review 20 asks for.

---

## RED TEAM FINDINGS

### RT1 — Explosion knockback (HIGH)

- **SCENARIO:** A player fights near a creeper, TNT or end crystal and is launched.
- **EXPECTED LEGITIMATE BEHAVIOUR:** Large displacement explained by explosion knockback delivered in the explosion packet.
- **WHY PAPEA2AC MAY MISINTERPRET IT:** The outbound source list does not include explosion, so no impulse enters the ledger. World is known, inputs enumerable, no budget abort — completeness flags are true and the engine emits a certified deterministic exclusion.
- **ARCHITECTURAL FIX:** H8 — explicit packet/observation matrix including explosion knockback, feeding the impulse ledger; startup capability loss for any unobserved physics-affecting packet class.
- **TEST:** Fixture: creeper detonation at three distances and three latencies, asserting no INVALID and a matched impulse in the ledger.

### RT2 — Entity push in crowded areas (HIGH)

- **SCENARIO:** Fifteen players in a spawn plaza or a fight.
- **EXPECTED LEGITIMATE BEHAVIOUR:** Per-tick push contributions appear in observed movement; the client predicts them.
- **WHY:** Entity collision and push appear in no document (H7). Either mass certified exclusions or permanent UNCERTAIN in crowds, the latter being a free evasion surface.
- **ARCHITECTURAL FIX:** H7 — a recorded decision to model push from observer entity history with a bound, or to declare crowded events uncovered with a named reason.
- **TEST:** Multi-player push fixture with 5, 15 and 30 nearby entities; assert no INVALID, and assert the declared coverage outcome matches the decision.

### RT3 — Ice, soul sand, honey, cobwebs, bubble columns (HIGH)

- **SCENARIO:** Sprinting an ice bridge; walking soul sand; sliding a honey wall; falling through a web; riding a bubble column.
- **EXPECTED LEGITIMATE BEHAVIOUR:** Per-tick friction, speed factor, drag and column forces.
- **WHY:** No contract for movement-affecting block properties (H6). The simulator applies defaults and every candidate misses, with completeness flags true.
- **ARCHITECTURAL FIX:** H6 — a `BlockBehaviorQuery` contract with coverage semantics, plus an inside-block sampling rule with its own budget.
- **TEST:** One fixture per property class, each asserting a VALID witness, using independently captured native-client movement as the oracle.

### RT4 — Third-party plugin cancels inbound movement (BLOCKER-level)

- **SCENARIO:** A region or minigame plugin cancels inbound movement packets.
- **EXPECTED LEGITIMATE BEHAVIOUR:** The server does not apply the movement; the player stays where the server thinks they are.
- **WHY:** B3 — Papea2AC commits the movement and advances its frontier and safe anchor; the divergence is permanent and produces certified exclusions plus setbacks to wrong anchors.
- **ARCHITECTURAL FIX:** B3 — observation boundary invariant, pipeline integrity check, inbound cancellation rule.
- **TEST:** Integration test with a foreign handler cancelling inbound movement, asserting no `DeterministicExclusion` and a named capability loss.

### RT5 — Plugin enabled while players are online (HIGH)

- **SCENARIO:** Plugin loaded mid-session, or after a plugin-manager reload, with 300 players connected.
- **EXPECTED LEGITIMATE BEHAVIOUR:** Normal play continues.
- **WHY:** H4 — the observer world has no baseline because chunks are never resent, so every collision query is UNKNOWN and everything is UNCERTAIN, indistinguishable from a quiet server.
- **ARCHITECTURAL FIX:** H4 — a defined bootstrap policy: platform-mediated re-track, explicit `OBSERVE_ONLY` until reconnect, or a divergence model seeded from a main-thread snapshot.
- **TEST:** Enable the plugin with simulated online sessions; assert the documented coverage state is entered and reported, not silently degraded.

### RT6 — Server tick stall or reduced tick rate (HIGH)

- **SCENARIO:** A 500 ms main-thread stall from world generation or another plugin; or an operator running the server at a reduced tick rate.
- **EXPECTED LEGITIMATE BEHAVIOUR:** The client keeps ticking and sending at its own rate; packets bunch on resumption, or the whole server runs slower.
- **WHY:** Stalls are handled ("Never derive timer guilt from a single burst after a pause", server-stall information in timer consumers), but tick-rate and tick-freeze state are not in any observation list (H8), so a reduced tick rate looks exactly like a client-side timer manipulation.
- **ARCHITECTURAL FIX:** H8 — observe tick-rate and freeze state and make them a version-profile input to timer and multi-step reasoning.
- **TEST:** Fault profile with a 500 ms stall and a separate profile with reduced tick rate; assert no timer evidence and no INVALID in either.

### RT7 — Knockback at high latency (HIGH)

- **SCENARIO:** Sustained melee at 200 ms RTT.
- **EXPECTED LEGITIMATE BEHAVIOUR:** Movement reflects impulses applied at client-side times the server cannot pin down.
- **WHY:** H5 — several concurrently unresolved impulses saturate the 256-candidate frontier; movement is UNCERTAIN for the whole fight, which is both a coverage hole and an evasion surface.
- **ARCHITECTURAL FIX:** H5 plus H2 — bound the unresolved set explicitly and replace enumeration of application timing with an interval abstraction.
- **TEST:** Latency-sweep fixture at 50/150/300 ms with repeated knockback, reporting the fraction of events reaching a definite verdict against a declared minimum.

### RT8 — Elytra, riptide and firework boosts (MEDIUM)

- **SCENARIO:** Elytra flight with firework boosts; riptide trident launch in rain or water.
- **EXPECTED LEGITIMATE BEHAVIOUR:** Large impulses with mode transitions.
- **WHY:** Elytra is named as a capability-gated mode but appears in only two documents, and riptide and firework boosts appear nowhere. If the mode is unsupported, the result is UNCERTAIN (acceptable); if it is claimed supported without those impulse sources, exclusions become possible.
- **ARCHITECTURAL FIX:** Tie every impulse source to the mode capability matrix in `VERSION_SUPPORT.md`; a mode may not be VERIFIED until each of its impulse sources has an observation path and a fixture.
- **TEST:** Elytra with firework boost and riptide launch fixtures, each asserting either VALID or an explicitly unsupported-mode UNCERTAIN.

### RT9 — Vehicles: boats on ice, minecarts, mounts (MEDIUM)

- **SCENARIO:** Boat on blue ice, minecart on powered rail, horse jump.
- **EXPECTED LEGITIMATE BEHAVIOUR:** Vehicle physics drives the player; the client sends vehicle movement on a separate path.
- **WHY:** Vehicle is listed as a capability-gated mode, but vehicle move and set-passengers packets are not in the observation list (H8), so entering and leaving a vehicle may not be observable at all — meaning the mode transition itself is missed rather than merely the physics.
- **ARCHITECTURAL FIX:** H8, plus an explicit statement that an unobservable mode transition invalidates the supported frontier rather than continuing under the previous mode.
- **TEST:** Mount, ride and dismount fixtures for boat, minecart and horse, asserting the mode transition is detected and the frontier is reset rather than carried over.

### RT10 — Pistons and slime-block trains (MEDIUM)

- **SCENARIO:** A piston pushes the block a player stands on; a slime-block flying machine carries them.
- **EXPECTED LEGITIMATE BEHAVIOUR:** The world moves the player.
- **WHY:** `WORLD_MODEL.md` requires explicit temporal rules for pistons and moving blocks and defaults unsupported dynamic behaviour to unknown, which is safe. But no locomotion mode or impulse source covers "moved by a block", so this is an unsupported-by-omission case rather than an explicitly declared one — and the difference matters because unsupported must be *visible* in the capability matrix.
- **ARCHITECTURAL FIX:** Add block-driven displacement to the mode/impulse taxonomy with an explicit support state, even if that state is UNSUPPORTED initially.
- **TEST:** Piston push and slime-train fixtures asserting an explicit unsupported-coverage result, not an exclusion.

### RT11 — Anti-xray ghost blocks (MEDIUM — currently handled, keep it that way)

- **SCENARIO:** Paper anti-xray obfuscates hidden sections; the client collides with blocks the server considers air.
- **EXPECTED LEGITIMATE BEHAVIOUR:** The client's movement matches the client's view.
- **WHY:** Papea2AC gets this right today by validating against the observer view. The risk is regression: any future optimisation that seeds the observer world from a main-thread snapshot without preserving the fake-block overlay reintroduces it. `WORLD_MODEL.md` already forbids exactly that ("A main-thread world snapshot must not overwrite an observer's fake block view") — this must survive the H4 bootstrap work.
- **ARCHITECTURAL FIX:** Keep the overlay precedence rule as an invariant of whatever bootstrap mechanism H4 produces.
- **TEST:** Anti-xray fixture with movement against an obfuscated boundary, asserting the observer view wins over any snapshot-derived baseline.

### RT12 — Server or plugin teleports, portals and respawn (MEDIUM)

- **SCENARIO:** `/tp`, a portal transition, respawn, or a plugin launching a player from a pad.
- **EXPECTED LEGITIMATE BEHAVIOUR:** Server-side position change with a correction packet, or a server-applied velocity.
- **WHY:** Teleports are handled through `TeleportState` and outbound corrections. Respawn and game-event packets are not in the observation list (H8), and the interaction between a foreign teleport and an in-flight Papea2AC setback intent is only partially covered ("cancelled teleport by another plugin" appears in the test list but the ordering semantics are not stated).
- **ARCHITECTURAL FIX:** H8 for the packets; state the precedence rule when a foreign teleport lands between intent admission and execution.
- **TEST:** Race a foreign teleport against an admitted setback intent in both orders; assert exactly one terminal outcome per intent and no violation weight for the resulting motion.

### MEDIUM and LOW red-team cases noted without full expansion

- **Packet loss and jitter:** handled by the gap and uncertainty machinery; the risk is coverage, not false positives. Needs measurement, not design change.
- **Attribute and potion changes mid-flight:** covered by the attribute/effect observation path, with the usual application-timing ambiguity; the risk is H5 again.
- **Gamemode change to creative or spectator:** flight abilities change; abilities packets are observed but game-event and set-camera are not (H8).
- **Edge collisions and grazing contacts:** `COLLISION_ENGINE.md` requires deterministic tie rules and named tolerance provenance, which is the right treatment; needs fixtures, not design change.
- **Version translation (ViaVersion):** declared unsupported; the real risk is that it is the common case on public servers, which is a product-scope question rather than a correctness bug.
- **Legitimate movement plugins (launch pads, grappling hooks, jetpacks):** velocity-based ones are observable as impulses; ones that manipulate movement without a packet are not, and there is no declaration channel (M2).

---

## PHASE 1 READINESS

**Must be addressed before Phase 1 begins.** These all constrain artefacts Phase 1 produces first — the module graph, the `api` surface, the transport adapter, the quota registry and the replay digest contract — and are expensive to change afterwards:

- **B1** — module dependency graph cannot express four documented behaviours.
- **B2** — `api` module contents undefined; replay forces the domain model into it.
- **B3** — no hook-placement invariant; third-party inbound cancellation unhandled.
- **B4** — commit the Phase 0 tree so the review and the gate can bind to a revision.
- **H13** — publish a minimum per-session memory footprint and a player-count-to-budget table; Phase 1 writes the quota registry.
- **H12** — extend the numeric profile to transcendental functions and add a CI rule; Phase 1 writes the replay digest contract and asserts cross-run determinism.
- **H8** — replace prose observation source lists with an enumerated per-direction matrix; Phase 1 gate 4 builds exactly this.

**Must be addressed before Phase 1 exits:**

- **H9** — sustained per-session work quota, shard fairness rule, degradation ladder, cost-maximising adversarial load profile.
- **H10** — shard failure state machine with reclamation ownership, plus its lifecycle test.
- **H11** — global correlated-anomaly guard and runtime enforcement mode control.
- **M1** — configuration owner, canonical form and digest rule (the replay header depends on it).
- **M6** — Paper build support policy as feature detection.
- **M10** — event-loop cost budget and metric.

**Must be addressed before the affected subsystem is implemented:**

- **H1, H2** — before Phase 5/6: the exclusion algorithm class, and the conservative abstraction with its containment obligation.
- **H3** — before Phase 2/3: one derived horizon table.
- **H4** — before Phase 3: unrecoverability stated, bootstrap policy chosen, divergence model compared with numbers.
- **H5** — before Phase 7b: bound on concurrently unresolved physics-affecting updates.
- **H6, H7** — before Phase 4/5: block-behaviour contract and entity-push decision.
- **H14** — before Phase 11/12: attacker eye derivation rule.
- **M2–M5, M7–M9, M11–M13** — track against their roadmap phases.

**What Phase 1 may proceed with once the blockers close.** The packet engine, the actor and shard infrastructure, the envelope schema, the quota framework and the minimal replay recorder are all well enough specified to build, and the Phase 1 acceptance criteria in `PHASE_0_REVIEW.md` are good — they require demonstration rather than assertion, they forbid detections, and criterion 3's instruction to stop and return for review rather than guess at transport semantics is the right escape hatch. Add the B3 demonstrations and the H8 matrix check to that list and it is a sound gate.

**What I am explicitly not asking for.** No module renaming beyond what B1.3 requires. No change to the VALID/UNCERTAIN/INVALID semantics, the evidence model, the mitigation intent model or the observer world principle — these are the parts of the design worth defending. No change to the budget *mechanism*, only to the specific numbers that contradict the prose. And no adoption of reference-project structure for its own sake; the five omitted observations in the Intave section are inputs to decisions, not instructions.

---

## FINAL VERDICT

This package is well above the standard I expected for a Phase 0. The reasoning about knowledge, uncertainty and proof is correct, and it is correct in the specific ways that separate an anticheat that can be operated from one that cannot. The dependency graph is genuinely acyclic, the version research is genuinely verified, the acknowledgement model already answers its own headline risk, and the backpressure answer is real rather than gestural. I am not requesting changes because the design is wrong in principle.

I am requesting changes because four things Phase 1 will build first are specified in a way that cannot be implemented as written — the module graph, the `api` surface, the packet observation boundary and the revision anchor — and because a set of load-bearing numbers currently contradicts the guarantees they are supposed to support. The most serious of these, and the one I would ask the Lead Architect to treat as the real headline, is H1: as budgeted, a complete INVALID is arithmetically out of reach by forward enumeration, which means the architecture's central promise resolves to permanent UNCERTAIN, silently and with a clean false-positive record. That needs an answer before Phase 5, and the answer is a different exclusion algorithm, not a bigger budget.

Blocking set for Phase 1 start: **B1, B2, B3, B4, H8, H12, H13.** Before Phase 1 exit: **H9, H10, H11, M1, M6, M10.** Before their subsystems: **H1, H2, H3, H4, H5, H6, H7, H14.**

PHASE 0 REVIEW: REQUEST CHANGES
