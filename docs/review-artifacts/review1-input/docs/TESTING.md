# Testing and regression strategy

## Decision Q1: independent oracles and permanent regressions

**DECISION:** Pair pure-engine tests with version-pinned native-client/Paper integration captures. Every confirmed false positive becomes a replay regression. Tests must assert legitimacy/coverage, not merely reproduce implementation arithmetic.

**WHY:** A simulator can be consistently wrong and still pass tests derived from its own formulas.

**ALTERNATIVES:** Manual server testing only; generated expected values from the implementation under test; a cheat-client demo as the sole success criterion.

**TRADEOFFS:** Native-client capture is slower and harder to automate. Fast deterministic replay makes the expensive independent evidence reusable.

## Test layers

| Layer | Representative cases | Oracle/pass obligation |
|---|---|---|
| Unit | Reducers, IDs/epochs, ledger transitions, deduplication, policy decay | Explicit state invariants and transition tables |
| Protocol | Field presence, bundles, legal states, malformed lengths/numerics, failed writes | Pinned native protocol captures and adapter contract |
| Concurrency | Producer races, lost wakeup, queue saturation, publication, shutdown | Deterministic scheduler tests plus stress/jcstress-style invariants; exactly one actor writer |
| Collision | Partial blocks, contextual neighbors, moving shapes, grazing rays | Independently specified geometry and vanilla scenarios |
| Physics | All supported movement modes and transitions, attributes/effects/impulses | Native-client captures with known environment/input; exact profile arithmetic where established |
| Integration | Plugin load/unload, packet-hook location, cancellations, transforms, main-thread effects | Exact Paper build and native-client observation with instrumentation |
| Latency | Delay/asymmetry/jitter, ACK boundary, server stall, write callback races | Recorded causality; no false INVALID from delayed knowledge |
| Combat | Observer target history, pose, interpolation, reach attributes, occlusion | Independently controlled attacker/target scenarios and replay geometry |
| Replay | Capture/restore, format migration, corruption, missing blob, cross-process determinism | Equal outputs/checkpoints on repeated runs; explicit failure for incomplete inputs |
| Property-based | Epoch isolation, conservative pruning, cache equivalence, translation invariance | Fixed seed recorded on failure; minimized reproducible counterexample |
| Security | Decompression bomb, oversized metadata, map-key flooding, crafted ACKs, slow disk | Bound work/memory, no unsafe effect, no arbitrary deserialization |
| Performance | Pure kernels, mixed-load server, churn and overload | Methodology and proposed budgets in [Performance](PERFORMANCE.md) |

Inject faults at the boundaries: omit an outbound observation, fail a write, exhaust capture pins, race a reconnect with an intent, throw during a component transition, terminate a shard, change registry, and cancel a platform teleport. The result must be quarantine/uncertainty with bounded cleanup, not a fabricated violation.

## Fixture schema and provenance

Each fixture has ID, scenario summary, originating issue, capture/build/ruleset hashes, setup instructions, allowed client modifications/instrumentation, input and world provenance, expected validation statuses and uncertainty, expected action prohibition/allowance, known unsupported features and license/provenance statement. Keep credentials, chat and identifying network data out. Expected physics values must cite an independent source or controlled measurement; never derive both expected and actual through the same simulator.

A regression for legitimate behavior should usually assert no INVALID and no mitigation, plus expected coverage where the engine claims support. An all-UNCERTAIN implementation must not pass the full suite: require VALID witnesses and minimum supported-scenario coverage in qualified fixtures. Adversarial fixtures assert exclusions where complete, and measure bypasses rather than assuming them absent.

## False-positive workflow

Discover -> capture sanitized scenario and environment -> reproduce in the old build -> minimize while preserving failure -> add fixture that fails for the demonstrated reason -> fix the shared engine or policy -> independent review -> run regression and relevant performance suites -> retain fixture in every later build. If reproduction is incomplete, record an unresolved case and keep the affected capability conservative; do not label it fixed from a threshold change alone.

The reviewer verifies the old failure, independent expected behavior, new success and non-regression of neighboring/adversarial cases. Never approve a fix by deleting or loosening a fixture without an explicit reviewed explanation.

## CI and qualification gates

Every change: compile on pinned Java, dependency boundary/no-cycle checks, unit/protocol/replay regression tests, deterministic fixture hashes and appropriate touched-engine tests. Integration changes additionally run native Paper tests. Nightly or pre-release: full version/capability matrix, fuzz/property seeds, load/soak, churn and fault injection. Performance-sensitive changes run representative JMH/load comparisons on the same host with confidence intervals; noisy results require reruns, not arbitrary pass claims.

Clean generated artifacts live under repository build directories. Future build tools must redirect project caches/temp directories under the repository as requested. Native test server assets are downloaded with verified hashes and documented acceptance/provenance; no Intave fixtures or implementation are imported.

Phase 0 has no production code changes and does not claim these suites passed. Its checks are document coverage, internal consistency, references, dependency DAG and preservation of existing files. See [review submission](PHASE_0_REVIEW.md).
