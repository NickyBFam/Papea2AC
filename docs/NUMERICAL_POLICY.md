# Numerical fidelity and replay determinism

## Decision NUM1

**DECISION:** Qualified concrete physics reproduces the selected client's numerical algorithm, including casts, lookup/indexing and operation order. All deterministic operations pass through a version-owned NumericOps contract. Cross-host replay requires identical profile/table/algorithm hashes and conformance vectors. Do not globally replace client algorithms with StrictMath.

**WHY:** Reproducibility of the wrong arithmetic is not vanilla fidelity. **ALTERNATIVES:** Host Math everywhere; blanket StrictMath; hide errors in a global epsilon. **TRADEOFFS:** Profiles need independently obtained provenance and cross-host qualification. A mismatched host cannot issue a verified replay verdict.

| Function/use | Required policy |
|---|---|
| sin/cos for movement basis and pick rays | Reproduce qualified client lookup/algorithm, indexing, input conversion and output casts. If table-based, serialize/hash table bits generated independently from permitted version observations/specification; no Intave tables copied. No runtime host Math generation of presumed canonical entries |
| atan2/asin/acos/tan where client behavior matters | Profile-specified fixed algorithm/table; unsupported until independently qualified. Do not assume the client uses Java library semantics |
| pow/exp/log/log10/cbrt/hypot and hyperbolic functions | Disallowed in core physical transitions unless individually listed with a proven client-compatible fixed implementation. Pure deterministic diagnostics/heuristics may use StrictMath wrappers with versioned semantics |
| sqrt, floor, ceil, rounding, float casts, remainder, signed zero | Explicit Java/IEEE semantics and client-specific integer conversions; preserve exact order and signed-zero bits where observable. No reassociation or fused multiply-add substitution unless the profile specifies it |
| Interval bounds | Outward rounding at every primitive/cast, including extrema and table-index discontinuities. Use nextDown/nextUp or stronger proven bounds; infinities mean an unbounded conservative result, never rejection of an unknown legitimate state |

StrictMath specifies reproducible semantics for listed mathematical functions; that guarantee alone does not establish Minecraft equivalence. [Java 25 StrictMath specification](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/StrictMath.html).

No universal current-client table contents/length are asserted by this Phase 0 design. Phase 1 creates profile identity, wrapper restrictions and cross-host replay conformance tests for the recorder. Phase 5 supplies independently qualified concrete/abstract numeric operations for physical modes. A new function requires a profile entry, provenance and fixtures before exclusion is allowed. CI forbids direct Math.sin/cos/tan/atan2/pow/exp/log and other transcendental calls in collision/physics/simulation/combat/interaction (including static imports and wrapper bypasses); reviewed NumericOps implementations are the only path.

Priority by boundary: native behavior fidelity governs concrete simulation; proven outward containment governs exclusion bounds; exact reproducibility governs verified replay/digests; display formatting may round and is excluded from decisions. An unavailable faithful algorithm yields NUMERIC_PROFILE_UNVERIFIED, not a widened unprincipled tolerance. A faithful profile that cannot reproduce on the current host yields INCOMPATIBLE_NUMERIC_ENVIRONMENT and refuses verified replay; re-run on a qualified environment. Neither requirement is silently sacrificed.

Capsule metadata: profile/version, client/runtime provenance, table bytes/digests, algorithm IDs, Java vendor/version, OS/architecture, conformance-vector digest, math-library profile and rounding/serialization rules. OS equality is not required if conformance qualification proves identical semantics. Tests run the same capsule on Linux x86-64 and Windows ARM64 (or a documented second OS/architecture pair), with bitwise output/checkpoint equality. Record optional intermediate yaw-basis/contact values only for diagnostics; recorded intermediates cannot replace recomputation in verification mode. A playback-only view that uses them must say it is not independent replay verification.

Containment testing includes signed zero, near-table-index boundaries, grazing collision, subnormal/large finite values, multi-step error propagation, and nonlinear interval extrema. Test sampling supplements a per-operator containment argument; it is not proof of all inputs.
