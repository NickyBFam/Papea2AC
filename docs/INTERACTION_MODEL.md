# Placement and interaction engine

## Decision ACT1

**DECISION:** anticheat-interaction owns placement, block use/break and non-attack entity interaction rules; combat owns attacks. Both reuse collision/world. Checks owns scaffold behavior features only. **WHY:** Block placement is not combat, and geometry must not be copied. **ALTERNATIVES:** Put placement in combat or checks. **TRADEOFFS:** A small new engine module and explicit world-overlay contract.

ActionContext includes attempt/outcome IDs, certified attacker pose/rotation provenance, target block/entity incarnation, face, cursor/hit vector, hand/item/window state, action sequence, timing, observer world/application view and profile. Rules include replaceability, block-against-face placement, outline ray, permitted reach/rotation relationship and server permissions. World owns prediction-overlay storage; interaction returns a ProposedOverlayDelta tied to the action, and core asks world to apply/confirm/reject it only at the appropriate outcome/application boundary. A rejected placement cannot permanently mutate the baseline.

Return a concrete legitimate action witness, fully covered exclusion, or UNCERTAIN. Use combat's documented eye-source policy but not combat implementation classes; share model provenance values and collision ray primitives. At most 64 scene alternatives/4096 geometry units/action. Bounded interaction feature windows feed checks for scaffold analysis; behavioral suspicion cannot change geometry or world truth. ReplayMapper is local to interaction and writes ActionSnapshotDto in replay-schema.

Phase 1 captures action families, Phase 3 builds overlay storage, Phase 11 establishes reusable eye/ray context, Phase 18 implements interaction semantics and scaffold consumers. Phase 19 expands inventory-dependent transitions. Phase 0 only defines ownership/contracts. Tests cover cursor/face mismatch, replacement, cancellation, item swap, fake blocks, action ACK delay and unsupported geometry.
