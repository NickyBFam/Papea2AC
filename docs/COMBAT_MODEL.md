# Combat and historical ray tracing

## Decision B1: reconstruct the attacker's observable scene

**DECISION:** Validate attacks against coherent historical views the attacker could have seen, with version-specific eye height, pose, target interpolation, dimensions, interaction attributes and world occlusion rules. Do not use a fixed center distance threshold.

**WHY:** Network visibility and client rendering/interpolation differ from the target's server position.

**ALTERNATIVES:** `distance > 3.x`; RTT/2 rewind; use all positions within a broad time window.

**TRADEOFFS:** Multiple constrained scenes cost work. Arbitrarily favorable combinations of unrelated historic states would create bypasses, so hypotheses must share causal constraints.

## Attack context

AttackContext records attack sequence, attacker session/epochs and movement/rotation provenance, target ID/incarnation, game mode and interaction attributes, relevant inventory/equipment, observer world/entity history, ACK frontiers, ruleset and coverage. An attack packet need not contain rotation: use preceding eligible rotation observations, preserving protocol-specific same-step ambiguity where proven. Do not borrow later aim.

TargetHistory belongs to the observer WorldView. Reconstruct interpolation endpoints/progress permitted by outbound entity movements, teleports and metadata; use target dimensions at the same hypothesis. Metadata, movement and spawn visibility cannot be mixed from incompatible frontiers. Entity ID reuse after destroy never links to an earlier incarnation. Vehicle passengers, sleeping/swimming/crouching, dimension changes and desynchronization are capability-gated scenarios.

## Validation pipeline

1. Resolve target identity, visibility and attack semantics at the attack sequence. Untracked or evicted targets are UNKNOWN.
2. Obtain coherent attacker eyes only through the certified supported-frontier rule below, plus observer target/world history; raw claimed position is not an independent eye source.
3. Reconstruct eligible rays from eye position and rotation under the numeric profile. Query target intersection and version-specific blocking/occlusion shapes.
4. Compare intersection parameter against permitted interaction reach from the version/game-mode/attribute profile. Distinguish reach miss, angular miss, occlusion and invalid target.
5. Return VALID with a legitimate witness, INVALID only if all admissible scenes are covered and fail, otherwise UNCERTAIN with specific coverage reasons.

An enclosing swept box can conservatively disprove a hit if even the superset is unreachable. Hitting that superset does not establish an actual legitimate interpolated position; it may only establish UNCERTAIN until a concrete witness exists. This distinction also applies to rotation and eye-position envelopes.

## Bounds and outputs

At most 64 coherent scene hypotheses and 4096 total geometry work units per attack, sharing bounded world query limits. Up to 64 unresolved attacks per player may wait for relevant acknowledgements, for at most the retained history horizon. Exhaustion/expiry returns UNCERTAIN. Pending work cannot pin world history beyond its memory reservation.

CombatValidation contains status, target incarnation, minimum/maximum residual intersection information, selected witness or exclusion certificate, scene IDs, numeric tolerances, relevant attribute values and uncertainty. Evidence contains enough geometry and history references to replay, not only a reach number.

The engine performs attack geometry and protocol eligibility. Rotation/click/KillAura consumers cannot label a legal ray impossible. Placement and non-attack interaction belong to anticheat-interaction, sharing model provenance and world/collision primitives, not duplicated geometry. Combat-local ReplayMapper exports CombatSnapshotDto in replay-schema. Numeric operations follow NUMERICAL_POLICY; occlusion shape use is version-qualified.

## Decision B2: attacker-eye provenance

**DECISION:** Derive eyes from concrete, certified supported trajectories consistent with the claim's profile-defined observation interval. A tolerance envelope alone is not a concrete eye witness. **WHY:** A client-supplied shifted position must not bypass movement certification. **ALTERNATIVES:** Use claimed eye directly; use arbitrary point inside outer envelope. **TRADEOFFS:** Combat may remain uncertain when movement has no concrete supported trajectory.

If the claim lies outside the supported outer frontier, return UNCERTAIN_ATTACKER_FRONTIER_CONFLICT and route the root observation to movement; do not create a second punitive reach exclusion. If it intersects only an abstract envelope, combat can exclude all eyes only through sound geometry over the complete set, but cannot establish VALID from an arbitrary envelope point. A concrete joint movement/attack witness is required for VALID. Outcome-pending/rejected movement yields UNCERTAIN_PIPELINE_OUTCOME. Eye height/pose belongs to the same trajectory and profile as its position.

CombatValidation records eyeSource (CERTIFIED_CONCRETE, ABSTRACT_EXCLUSION_ONLY or UNAVAILABLE), supporting trajectory/proof IDs, claim interval, originating attempt/outcome and causal roots. A within-tolerance position-shift fixture must show no raw-claim-only VALID. This does not promise to detect a lie observationally identical to legitimate movement; reachable motion is not proof of honest intent. Pending attacks expire at the minimum of achieved timeline, entity, world and application coverage, with a three-second target at most, never marker diagnostic retention.

## Enforcement boundary

An asynchronous result usually arrives after Paper has handled an attack. Initial policy is record/alert only; no claim of retroactive cancellation or damage rollback. Any future synchronous cancellation facility requires its own reviewed bounded decision path and exact packet-to-action correlation. Future mitigations may constrain subsequent actions through main-thread policy, with expiry and epoch checks.

Fixtures include observer lag, target teleport, entity spawn/destroy, interpolation crossing a wall, overlapping targets, eye-height change, attribute-driven reach, equal-boundary hits, reordered marker callback and missing target history. Measure false outcomes per attack and per player-hour, including UNCERTAIN coverage, not just successful detections.
