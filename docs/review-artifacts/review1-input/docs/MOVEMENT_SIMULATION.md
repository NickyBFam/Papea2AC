# Movement simulation

## Decision M1: search legitimate trajectories

**DECISION:** Pure physics advances one versioned step; simulation orchestrates admissible inputs, state-application branches and step counts. Movement validation asks whether an observed movement has any legitimate explanation under the recorded knowledge, rather than comparing to a fixed maximum speed.

**WHY:** Input, collision, attributes, impulses and uncertain application timing jointly determine reachable states.

**ALTERNATIVES:** Per-cheat thresholds; a single best prediction; unrestricted exhaustive search.

**TRADEOFFS:** Search is expensive. Bounded incomplete search must return UNCERTAIN; it cannot become a negative proof.

## Contract types

| Type | Required content |
|---|---|
| SimulationInput | Prior supported-state frontier; observed fields; admissible step/input domains; rotation constraints; world view(s); abilities/attributes/effects; external impulse ledger; collision/numeric profile; deterministic work budget; ruleset ID |
| SimulationCandidate | State/velocity/pose after each step; input sequence; causal application choices; consumed impulse IDs; contacts; uncertainties; stable branch ID |
| SimulationResult | VALID/UNCERTAIN/INVALID; matching candidates/frontier; completeness certificate; error summaries; work counts; referenced world/timeline revisions |
| SimulationError | Observed-minus-predicted vector, per-axis residual intervals, contact/pose mismatch, threshold provenance; not just one norm |
| SimulationUncertainty | Typed reason and affected range: world missing, input ambiguity, application boundary, unsupported rule, history gap, budget exhaustion, numeric profile unverified |

Result completeness includes input-domain coverage, world coverage, application-branch coverage, step-range coverage and pruning justification. INVALID requires all relevant flags true and no candidate matching the documented observation intervals. A witness can establish VALID before exhausting remaining candidates. An ambiguous matched frontier may remain usable only if all relevant future alternatives are represented.

## Step specification

The selected ruleset defines exact operation order: input normalization, pose/use/sprint constraints, acceleration and attribute/effect application, external impulses, fluid/environment forces, collision/step-up resolution, ground response, drag/gravity and end-of-step effects. This list names responsibilities, not an asserted universal Minecraft order. Phase 5 must document the exact target-version ordering and float casts with independent fixtures before enabling a mode.

Observed packet coordinates are authoritative client claims; reconstructed velocity is inferred. Preserve both separately. Packets that omit position or rotation retain field-presence semantics. Multi-step possibilities are enabled only where version/protocol observations permit them; arrival delay does not by itself imply skipped simulation steps. Vehicle, elytra, swimming, flying and walking are distinct capability-gated modes with tested transitions. Unsupported modes are locally UNCERTAIN and visible in coverage metrics.

## Decision M2: bounded sound search

**DECISION:** Use deterministic branch ordering and work-unit limits. Prune only with proven incompatible constraints or conservative reachable envelopes. Initial cap: 256 live candidates, 4096 candidate-step evaluations per movement event and at most three inferred steps. These are resource limits, not claims that three steps cover every legal case.

**WHY:** Adversarial input and world ambiguity must not cause exponential resource growth.

**ALTERNATIVES:** Beam search that declares misses invalid; elapsed-time-only budgets; unlimited search.

**TRADEOFFS:** Legal cases outside the represented horizon lose validation coverage. Measure saturation and refine rules instead of increasing caps without evidence.

Enumerate cheap, likely witnesses first using a stable profile-defined order. Equivalent states may merge only when their future-relevant state, causal obligations and uncertainty sets are identical or represented by a sound conservative abstraction. Approximate merging, top-K pruning or heuristic branch dropping sets completeness false. If a merged superset fails to reach the observation, its conservativeness must be proven before using that exclusion. Budget values and branch-order version are part of replay.

Geometry work also draws from one cumulative 65536-unit movement-event budget across all candidates and steps, with a visited cell or tested box each consuming one unit. Per-query limits are additional ceilings, never fresh unlimited allowances for each branch. Resumable work may yield the shard at safe boundaries without resetting the event budget. Exhausting aggregate geometry work has the same UNCERTAIN semantics as exhausting candidate steps.

## Continuation and failure safety

A matching position alone cannot choose an arbitrary future velocity. Retain distinct supported motion hypotheses or a sound envelope; overflowing that frontier invalidates negative proofs. INVALID observations do not automatically overwrite the trusted baseline. Keep the client-claimed state for diagnostics and request a reviewed recovery/mitigation policy; continuing from an impossible trusted state would cause cascaded false positives.

Impulses are one-shot per branch and respect replace/add ordering. Zero displacement does not prove ignored knockback when collision could absorb it. Velocity validation consumes simulation provenance, not a duplicated physics formula. Every transition is reproducible from the normalized input and ruleset, including uncertainty and operation-budget aborts.
