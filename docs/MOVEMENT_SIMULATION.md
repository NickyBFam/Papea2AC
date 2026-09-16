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

Result completeness includes server outcome/causal-prefix coverage, input-domain, world/property/entity/force coverage, application-set coverage, step-range coverage and exclusion-proof provenance. INVALID requires all relevant flags true and a complete disjointness certificate or a fully exhausted exact residual domain. A concrete witness can establish VALID early. Neither result may promote a certified frontier before the packet outcome resolves. A matching observation does not eliminate other future-relevant states unless sound constraints exclude them.

## Step specification

The selected ruleset defines exact operation order for input normalization, pose/use/sprint, attributes/effects, ExternalMotionInfluence, BlockBehaviorQuery samples, entity push/colliders, collision/step-up, drag/gravity and end-of-step effects. This list is not a universal Minecraft order. Phase 5 must document exact order/casts, per-mode force paths and abstract transfer containment before enabling any exclusion profile. A shape-only world is incomplete physics. Unmodeled force, missing neighbors or unknown behavior cannot be defaulted to zero/ordinary friction.

Observed packet coordinates are authoritative client claims; reconstructed velocity is inferred. Preserve both separately. Packets that omit position or rotation retain field-presence semantics. Multi-step possibilities are enabled only where version/protocol observations permit them; arrival delay does not by itself imply skipped simulation steps. Vehicle, elytra, swimming, flying and walking are distinct capability-gated modes with tested transitions. Unsupported modes are locally UNCERTAIN and visible in coverage metrics.

## Decision M2: staged constraint solver with bounded refinement

**DECISION:** Use a concrete witness lane and a separate sound interval/constraint exclusion lane. Retain the limits of 256 live refinement nodes, 4096 step/abstract-transfer evaluations and at most three inferred steps; do not raise them to fund Cartesian enumeration. Input acceleration sets and pending application intervals are propagated symbolically where possible. Discrete topology is split only when refinement can help.

**WHY:** Adversarial input and world ambiguity must not cause exponential resource growth.

**ALTERNATIVES:** Flat forward enumeration; claim inverse motion is constant-time everywhere; beam search with invalid-on-miss; unrestricted exhaustive search.

**TRADEOFFS:** Legal cases outside the represented horizon lose validation coverage. Measure saturation and refine rules instead of increasing caps without evidence.

### Solver stages and actual exclusion condition

1. Verify outcome/epoch, capability and causal input coverage. Form observation set O from exact present fields and version-established quantization/numerical intervals. Do not inflate O with an arbitrary speed/reach tolerance.
2. Try the client-reported input first as an untrusted witness proposal, then a bounded stable list of likely inputs. Run the exact concrete profile and require coherent world/force/contact history. A verified trajectory in O establishes VALID, not honesty.
3. Build abstract initial frontier A and input/force domains. Propagate every admissible step count through sound abstract transfer T#. If O is disjoint from the union of all propagated observation sets, emit an exclusion certificate without enumerating concrete input combinations.
4. Where free-flight/known-contact equations are affine for a fixed mode, invert the observed interval to constrain required acceleration. For `d = v + a + j` in a profile where that particular order is proven, constrain `a` by outward-rounded `O - V - J`; intersect with the admissible acceleration set. This is an illustrative equation, not the target Minecraft step equation. Clamp, rounding, normalization, bounce and collision are not globally invertible: use interval preimages over all cases or retain them. Dividing by an interval containing zero must not remove solutions.
5. Refine surviving mode/contact/jump branches with branch-and-bound, split at known discontinuities and resolve exact collision where possible. Keep unsplit cells as conservative supersets. Each eliminated node carries a checkable cause and its coverage of the parent domain. Terminate when a concrete witness exists, all covering nodes have been soundly excluded, or resources expire.

Certificate contains initial-state/input/applicability domain hashes, rules/numeric profile, each transfer/partition proof ID, union coverage, disjointness intervals or exact eliminated leaves, and budget used. INVALID is permitted only if the covering forest has **no remaining feasible or unexamined node**, or a covering outer envelope for the entire remaining domain is disjoint from O. Resource exhaustion with any intersecting/uncovered node yields UNCERTAIN_SEARCH_INCOMPLETE. An empty container caused by dropping nodes is not an empty feasible set.

### Qualification domains and branch accounting

Initial exclusion pilot is explicitly limited to verified one-step walking/free-flight/known flat contact with known mode/rotation/effects, complete neighborhood and bounded force domain. Continuous acceleration is handled as intervals; nominal forward/strafe/use-state alternatives form an admissible set, not 144 independent step simulations. For this pilot: mode=1; contact case<=2; jump case<=2; acceleration/impulse interval case=1 gives at most 4 outer transfer branches/step. Trying all nine discrete direction proposals is optional witness refinement, <=36 one-step proposals; known flags reduce it further. Up to three steps need at most 1+4+16+64=85 outer nodes before additional refinements, under the existing limits, **only if** the target profile validates these domain assumptions. Outside that pilot, the solver has no fixed worst-case success guarantee; return UNCERTAIN when the capped covering forest still intersects O.

The review's 144-state calculation demonstrates a plausible explosion, not a verified universal independent domain: sprint/sneak/use combinations are constrained and protocol-dependent. Nor is inverse solving O(1) through discontinuous collision. Phase 5/6 must publish actual target-profile case counts and prove each transfer; until then INVALID is not enabled even for the pilot. Required tests include a displacement outside the full outer reachable set returning INVALID within budget, an in-envelope impossible point requiring refinement, and unsupported/missing-force cases returning UNCERTAIN. Coverage gates are in TESTING; benign witness rates cannot mask zero adversarial exclusions.

## Decision M3: conservative envelope contract

**DECISION:** AbstractState is a finite union of tagged boxes: outward-rounded intervals for position and velocity, discrete sets for mode/pose/contact/jump/use state, attribute/effect intervals, and an application automaton retaining impulse IDs, replace/add order and earliest/latest possible step. World/property/entity coverage and numeric profile are attached prerequisites, not interval fields allowed to disappear.

**WHY:** Merging must retain every concrete possibility. **ALTERNATIVES:** Merge nearby candidates into a representative; a speed-only bound; enumerate every pending update. **TRADEOFFS:** Losing correlations widens the set and creates false negatives/UNCERTAIN, never permission to shrink it.

Let gamma(A) be represented concrete states. Required containment is `step(gamma(A), admissibleInputs, admissibleWorlds) subset gamma(T#(A))`. Abstract merge is tagged union or interval hull preserving discrete obligations. Arithmetic bounds use the numerical policy; nonlinear/quantized operations evaluate all endpoint/extremum/discontinuity cases or widen to a safe top interval. For a region whose collision response cannot be bounded tightly, union every relevant contact case or return top/UNKNOWN, never assume free space. Unknown legitimate unbounded force sets force coverage false even if a finite cached envelope exists.

Use envelopes for exclusion, branch pruning, and supported-frontier continuation. They cannot establish a LegitimateWitness, reconstruct actual keys, resolve a client application tick, or justify an eye point merely by containing it. `O intersects gamma(A)` is possible, not proven valid. A trusted frontier narrowed by an observation is the intersection with that observation **only after outcome certification**, retaining all histories consistent with it. One matching witness must not discard the remainder.

Version implements primitive concrete/abstract operation definitions in physics/version; simulation composes them. Phase 5 delivers transfer definitions/containment evidence, Phase 6 delivers solver/merge and counterexample shrinking. Every numeric-profile/version change invalidates old proof IDs. Tests pair independently captured native trajectories with random concrete states sampled inside envelopes, assert successor containment, adversarial edge-contact/rounding cases and no envelope-only witness. Sampling is a regression check, not a mathematical proof: per-operator containment argument plus independent review is required.

Geometry work also draws from one cumulative 65536-unit movement-event budget across all candidates and steps, with a visited cell or tested box each consuming one unit. Per-query limits are additional ceilings, never fresh unlimited allowances for each branch. Resumable work may yield the shard at safe boundaries without resetting the event budget. Exhausting aggregate geometry work has the same UNCERTAIN semantics as exhausting candidate steps.

## Continuation and failure safety

A matching position alone cannot choose an arbitrary future velocity. Retain distinct supported hypotheses or M3's covering abstraction. If neither fits, mark the frontier unavailable; never keep a best representative and completeness=true. INVALID, rejected or outcome-unknown observations do not overwrite certified state or anchors. Keep claims/provisional results diagnostically and require reconciled recovery.

Impulses are one-shot per branch and respect replace/add ordering. Zero displacement does not prove ignored knockback when collision could absorb it. Velocity validation consumes simulation provenance, not a duplicated physics formula. Every transition is reproducible from the normalized input and ruleset, including uncertainty and operation-budget aborts.
