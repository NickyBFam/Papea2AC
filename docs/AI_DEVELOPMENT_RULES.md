# AI development and review rules

## Decision D1: independent approval at each phase

**DECISION:** Each phase has one implementer and a different senior reviewer. GPT 6 Astra authored Phase 0; Claude independently reviews architecture and red-team risks. Neither approves its own work. A human approves merge after required review and tests.

**WHY:** Architecture, security and false-positive assumptions need an independent challenge.

**ALTERNATIVES:** Self-approval; implement several phases before review; merge on green tests alone.

**TRADEOFFS:** Additional review time is intentional. A draft ready for review is not an approved implementation baseline.

Workflow: specification -> implementation -> automated tests -> senior review -> request changes -> fixes -> second review -> regression/performance tests -> human approval -> merge. Phase 0 substitutes document validation for implementation tests. Phase 1 MUST NOT start until Phase 0 has senior approval and the human progression/merge gate is satisfied.

## Decision D2: working-tree review versus committed baseline

**DECISION:** Review 2 may approve a fixed content manifest without a Git HEAD. Only after senior APPROVE and human approval is a Phase 0 baseline commit created and verified before Phase 1. **WHY:** A content hash binds bytes even in an unborn repository; it does not create history or approval. **ALTERNATIVES:** Require an impossible pre-existing commit; commit the user's staged content before their approval. **TRADEOFFS:** A manifest plus archived input snapshot is needed for reproducible working-tree comparison.

Sequence: finish remediation -> generate exact per-file manifest -> reviewer verifies bytes and archived Review 1 diff -> APPROVE against that digest -> human approval -> stage only approved files -> baseline commit -> verify committed blobs match approved manifest -> record actual revision in a separate baseline receipt -> Phase 1 allowed. Any changed specification byte after APPROVE requires a fresh manifest and review of that change. Baseline receipt is a later process artifact referencing the real commit; a commit cannot contain its own final SHA. No commit or approval is fabricated in this remediation.

Review 1 at PHASE_0_SENIOR_REVIEW.md is immutable. docs/review-artifacts/review1-input preserves all 22 old spec files; Review 2 manifest defines the current review surface. The manifest excludes itself, later reviewer artifacts and the immutable comparison archive to avoid recursive hashes; their exclusion is explicit, not an opportunity to exclude normative files. Review 2 uses the current snapshot/manifest and the archived bytes for diff; hash-only abbreviated Review 1 labels are not the only comparison evidence. A changed review artifact requires a new named review, not overwriting Review 1.

Phase 0 acceptance resolves architecture defects at specification level. Runtime evidence explicitly assigned to later phases is not falsely marked passed; it remains a capability/phase gate. No senior reviewer approval is inferred from the implementer's consistency checks.

## Every implementation submission

State scope, phase ID, governing decisions, before/after behavior, dependency changes, state/ordering invariants, version assumptions, uncertainty behavior, resource budgets, fixture provenance, tests actually run and limitations. Distinguish measured results from targets. Preserve unrelated user changes; never silently delete useful work. All project files stay under the requested repository.

No Bukkit/NMS import outside the platform boundary; no arbitrary PlayerData mutation; no blocking I/O on main or packet/actor threads; no punishment from validators. A new source of uncertainty must propagate to consumers and replay. A new collection needs count, byte, lifetime and eviction rules. A new protocol/physics assumption needs native-version evidence. Checks consume shared engines.

## Required senior review template

Identify reviewer, implementer, phase, exact reviewed file/tree digest or commit, review date and automated artifacts. For each category record findings or explicitly state none found; silence is not approval:

- BLOCKERS
- HIGH PRIORITY
- MEDIUM PRIORITY
- ARCHITECTURE
- CORRECTNESS
- THREAD SAFETY
- PACKET ORDERING
- VERSION CORRECTNESS
- FALSE-POSITIVE RISKS
- PERFORMANCE
- MEMORY / ALLOCATION
- SECURITY
- TEST COVERAGE
- MAINTAINABILITY

Each finding includes ID, severity, location, concrete failure scenario, required fix and validation. Track responses and residual risk; second review verifies fixes on the changed revision. The review must finish with exactly **APPROVE** or **REQUEST CHANGES**. Open blockers/high-priority correctness or safety issues require REQUEST CHANGES. Phase status elsewhere cannot override that result.

## Intave and independent development

Use only publicly accessible reference information for conceptual comparison. Do not copy implementation, constants, private algorithms, fixtures or licensed assets into Papea2AC. Do not ask an AI to translate or paraphrase restricted source into equivalent code. Record sources and distinguish claims, direct observations and inferences. Independent implementations require their own specification and tests derived from legitimate native-game observations; reference similarity is not correctness evidence.

## Phase discipline

Do not create detection scaffolds merely because Phase 0 seems complete. Build/module migration belongs to the approved Phase 1 foundation step. Review artifacts remain under docs; source and Gradle changes must be scoped to later phases. Changes to architectural ownership, DAG, ACK guarantees, numeric behavior or mitigation policy reopen the relevant review decision. ML/cloud research remains optional and late, with no silent upload of gameplay samples.
