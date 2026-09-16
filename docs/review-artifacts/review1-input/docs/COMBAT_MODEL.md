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
2. Obtain bounded coherent attacker-eye, target-box and world hypotheses from causal history.
3. Reconstruct eligible rays from eye position and rotation under the numeric profile. Query target intersection and version-specific blocking/occlusion shapes.
4. Compare intersection parameter against permitted interaction reach from the version/game-mode/attribute profile. Distinguish reach miss, angular miss, occlusion and invalid target.
5. Return VALID with a legitimate witness, INVALID only if all admissible scenes are covered and fail, otherwise UNCERTAIN with specific coverage reasons.

An enclosing swept box can conservatively disprove a hit if even the superset is unreachable. Hitting that superset does not establish an actual legitimate interpolated position; it may only establish UNCERTAIN until a concrete witness exists. This distinction also applies to rotation and eye-position envelopes.

## Bounds and outputs

At most 64 coherent scene hypotheses and 4096 total geometry work units per attack, sharing bounded world query limits. Up to 64 unresolved attacks per player may wait for relevant acknowledgements, for at most the retained history horizon. Exhaustion/expiry returns UNCERTAIN. Pending work cannot pin world history beyond its memory reservation.

CombatValidation contains status, target incarnation, minimum/maximum residual intersection information, selected witness or exclusion certificate, scene IDs, numeric tolerances, relevant attribute values and uncertainty. Evidence contains enough geometry and history references to replay, not only a reach number.

The engine performs geometry and deterministic protocol eligibility. Rotation, click and KillAura heuristics consume bounded features separately and cannot label a legal ray impossible. Placement uses the same eye/ray/world primitives with block outline and placement rules; it does not copy combat geometry.

## Enforcement boundary

An asynchronous result usually arrives after Paper has handled an attack. Initial policy is record/alert only; no claim of retroactive cancellation or damage rollback. Any future synchronous cancellation facility requires its own reviewed bounded decision path and exact packet-to-action correlation. Future mitigations may constrain subsequent actions through main-thread policy, with expiry and epoch checks.

Fixtures include observer lag, target teleport, entity spawn/destroy, interpolation crossing a wall, overlapping targets, eye-height change, attribute-driven reach, equal-boundary hits, reordered marker callback and missing target history. Measure false outcomes per attack and per player-hour, including UNCERTAIN coverage, not just successful detections.
