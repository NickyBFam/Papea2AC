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

Phase 0/remediation changes no production code and does not claim runtime suites passed. Document checks include dependency behavior paths and public/internal/schema boundaries, finding coverage, immutable Review 1/source hashes and archived review inputs. See [review submission](PHASE_0_REVIEW.md).

## Review remediation acceptance matrix

All tests below are specified deliverables, not tests run in Phase 0. A failure blocks the named capability/phase; no test is satisfied merely by adding its name here.

| Test | Scenario and required assertion | Delivery gate |
|---|---|---|
| TC01 | Observe inbound movement then foreign cancellation: original claim remains diagnostic; no certified frontier/anchor promotion or actionable exclusion | Phase 1 |
| TC02 | Mutation/replacement/one-to-many replacement, foreign handler insertion in both directions and insert/remove between audits: correlate final outcome or named uncovered span | Phase 1 |
| TC03 | Foreign teleport versus movement/setback in both orders, including admission race: epoch/predecessor invalidation, one terminal intent outcome, zero induced violation | Capture Phase 1; effects Phase 10 |
| TC04 | Velocity versus movement, ACK-before-write-callback and failed send: correct application alternatives, no double consumption | Phase 1/7b |
| TC05 | Creeper at 3 distances and 50/150/300 ms, TNT/end crystal/explosion, projectile/fishing/plugin impulse: captured resultant influence, no false INVALID | Phase 5/7b |
| TC06 | Crowds 5/15/30 plus 33 relevant entities, mob contact, boat/minecart/shulker colliders: VALID where qualified, named uncertainty on overflow/unknown execution side | Phase 4/5 |
| TC07 | Ice/blue ice, soul sand, honey slowdown/wall-slide, slime/bed bounce, web/berry drag, bubble up/down and fluid flow: independent concrete witnesses | Phase 5 |
| TC08 | Moving piston/slime train and moving border: temporal displacement or explicit unsupported capability, never missing-force exclusion | Phase 4/5 |
| TC09 | Mid-session activation with 300 sessions: OBSERVE_ONLY_BASELINE_MISSING shown per session; only newly covered regions become eligible | Phase 3 |
| TC10 | Evict and re-enter a region; gap/resume without resend; fake-block versus main snapshot: ordering can recover while world stays unknown; observer precedence retained | Phase 2/3 |
| TC11 | Lower search/work budgets at runtime: unresolved nodes yield UNCERTAIN_SEARCH_INCOMPLETE, applied budget recorded and replayed | Phase 6/7b |
| TC12 | Random concrete states inside abstract envelopes; every concrete successor contained; edge/rounding counterexamples; envelope intersection never LegitimateWitness; outside full envelope returns INVALID inside cap | Phase 5/6 |
| TC13 | Same capsule on Linux x86-64 and Windows ARM64 or qualified alternative pair: identical numeric/digest results; mismatched table/profile rejected | Recorder Phase 1; physics Phase 5 |
| TC14 | 50–1000 synthetic sessions, global replay pressure, concurrent promotion, slow/failing writer and shutdown: partitions never exceeded, skipped capsules explicit, leases reclaimed safely | Allocator Phase 1; closure Phase 9 |
| TC15 | Stale anchor age>2 s, distance>8 blocks, world revision changed, destination unload/obstruction: no stale teleport; resync outcome | Phase 10 |
| TC16 | Kill shard mid-event: fenced/no new assignments, terminated-writer lease reclamation and alert; hung shard never has live memory freed | Phase 1 |
| TC17 | Correlated anomaly across distinct sessions and runtime ENFORCE->OBSERVE_ONLY: global guard suspends before player loop breakers; no new effect after restriction; control/admission log replay | Control Phase 1; effects Phase 10 |
| TC18 | Config key-order/comments/default spelling equivalence -> same canonical digest; invalid floor/probe -> explicit failed or limited admission | Phase 1 |
| TC19 | Local domain export/restore/export across changed runtime IDs -> equal schema digest; missing projection/closure fails | Each state phase |
| TC20 | One root movement produces movement/combat/timer evidence -> one capped causal group; root truncation prevents extra weight | Phase 9 |
| TC21 | Within-tolerance position shift toward target -> no raw-claim-only reach witness; envelope-only eye yields no concrete witness | Phase 11/12 |
| TC22 | Reduced tick rate/freeze and 500 ms stall; mount/ride/dismount and camera/respawn changes: mode observed, stale frontier invalidated, no timer guilt | Phase 1/14 |
| TC23 | Elytra/firework, riptide, horse jump and vehicle-on-ice: every source mapped before mode qualified; otherwise visible unsupported status | Phase 5 |
| TC24 | Cost-maximizing actor next to ordinary actors: visit/quota fairness, visible per-session coverage, no cross-player resource starvation | Phase 1 infrastructure; Phase 6/24 load |
| TC25 | Sparse movement, dense combat and chunk-churn capsules: measured size/closure and >=99% durable within 400 ms under declared load; otherwise mitigation stays off | Phase 9 before 10 |

### Concrete coverage gates

Qualification corpus is fixed before tuning and held-out scenarios are independently labeled. Phase 6 pilot:100% of the analytically impossible disjoint-envelope cases must return INVALID within original limits, and 100% of sampled concrete envelope intersections without a trajectory must never return a witness. Phase 8: zero false INVALID/action on benign corpus, >=95% VALID on each supported benign scenario class; >=80% INVALID on each predeclared supported adversarial class where independent ground truth proves impossibility. Report the remaining VALID and UNCERTAIN separately. Unsupported classes cannot be removed from product coverage reporting to improve the score.

Phase 7b: repeated-knockback profiles at 50/150/300 ms require >=90% definite outcomes per declared supported profile, zero false INVALID, and explicit counts for application overflow/history loss. These are release gates, not assertions of achievement or universal detection rates. If a profile cannot meet them, keep it unqualified and report the product limitation. Timeline test:30 entities at 20 Hz plus attempts/outcomes/control drives measured retention; three-second target at the declared 1000-record/s envelope must pass or produce a qualification failure/shortfall metric.

### NativeScenarioHarness

anticheat-testing owns a reproducible harness delivered in Phase 1 for transport capture and extended in Phase 5 for motion oracles. Launch a checksum-pinned licensed native client and Paper build in an isolated test world; script input actions through an external driver or a minimal test-only input injector. Read-only instrumentation may record input sampling, relevant client state application and tick boundaries; it must not replace movement math/packet processing. Compare instrumented runs with uninstrumented behavior and document perturbation. No Intave code/fixtures or third-party cheat client is the correctness oracle.

Record scenario seed, world/config/build hashes, driver/instrumentation revision, timelines and sanitization. The phase implementer owns per-version recapture, independently reviewed by the senior reviewer. Initial planning budget:4–8 engineering hours and 1–2 automated host-hours for the smoke matrix per version; full mechanic recapture can exceed this and must report actual cost. These are estimates, not measured throughput. Failed/unavailable harness prevents claiming native qualification.

Future architecture checks include the exact DAG, constructor/call paths, absence of internal DTOs from public API, schema-only replay imports and forbidden Math transcendental/platform imports. This remediation adds specifications only, not the Phase 1 tests or harness implementation.
