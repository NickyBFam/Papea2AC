# Input reconstruction

## Decision I1: inputs are hypotheses

**DECISION:** Reconstruct a set of possible legitimate inputs; never claim that inferred keys were actually pressed. Client-reported input, if present in the selected protocol, is a claim constrained against movement and legal state transitions.

**WHY:** Several inputs or application timings can produce the same movement, and adversarial clients can lie about input fields.

**ALTERNATIVES:** Derive one key vector from displacement; enumerate every combination forever; trust input packets absolutely.

**TRADEOFFS:** Multiple plausible states persist. This reduces some detection coverage but avoids manufactured precision.

## Reconstruction domain

InputDomain describes forward/strafe possibilities, jump, sprint, crouch, item use, rotation intervals, locomotion mode and allowed step-count range. The target profile specifies discrete/continuous domains and normalization behavior; do not assume legacy keyboard-only nine-direction input is complete for every supported client. Initial support is the native vanilla Java client; controller/modded/translated input semantics are unverified capabilities until fixtures establish them.

Inputs are constrained by observed actions, window/use-item states, attributes, pose transitions, collision environment and causal ACK intervals. Reported rotation is matched at its actual packet position in the timeline. A later rotation cannot be moved backwards to legitimize an attack. Missing rotation produces a constrained interval/state from the prior observation, not a random guessed aim.

Pipeline: determine covered mode/step span -> derive domains -> try reported input as the first concrete witness -> constrain required acceleration from observed intervals -> propagate covering reachable sets -> refine discrete discontinuities -> exact collision/witness or complete exclusion. MOVEMENT_SIMULATION M2/M3 defines the proof obligations. Client input does not restrict the exclusion domain unless independently established: a lie that produces reachable motion is not detectable at this layer. Learnable ordering may rank candidates but never remove admissible classes. Application alternatives remain interval/automaton constraints instead of a Boolean Cartesian product.

## Bounded inference and confidence

Use the simulation engine's candidate/operation budget, not an independent additional budget that multiplies work. Split input ambiguity from missing-world and timing ambiguity in results. Reconstructed input confidence is diagnostic; it cannot turn an incomplete search into INVALID. Maintain stable branch identifiers and all relevant consumed-impulse/ACK choices.

Regression cases: opposing key cancellation, diagonal normalization, sprint transition on landing, jump with effect change, sneak on edge, use-item slowdown entering/exiting a window, rotation-only packet, delayed input packet, mode switch, equivalent position with different residual velocity, absent fields, and candidate exhaustion. For every pruned branch class, supply an independent reason it cannot explain the observation or mark search incomplete.
