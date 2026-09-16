# Phase 0 review submission and acceptance gates

**PHASE 0 STATUS: READY FOR SENIOR ARCHITECTURE REVIEW**

Author/implementer: GPT 6 Astra. Required independent senior architecture and red-team reviewer: Claude. This is a submission status, not APPROVE. No Phase 1 implementation, build migration, detection code, commit or merge was performed.

## Deliverable coverage

| Requirement | Specification |
|---|---|
| 1 Final repository/modules; 2 dependency graph | [Architecture](ARCHITECTURE.md) |
| 3 Thread ownership | [Threading](THREADING_MODEL.md) |
| 4 Player state | [Player state](PLAYER_STATE.md) |
| 5 Packet pipeline | [Packet pipeline](PACKET_PIPELINE.md) |
| 6 Timeline | [Timeline](PLAYER_TIMELINE.md) |
| 7 World | [World model](WORLD_MODEL.md) |
| 8 Collision | [Collision](COLLISION_ENGINE.md) |
| 9 Physics/simulation | [Movement simulation](MOVEMENT_SIMULATION.md) |
| 10 Input reconstruction | [Input reconstruction](INPUT_RECONSTRUCTION.md) |
| 11 Network/latency | [Latency](LATENCY_COMPENSATION.md) |
| 12 Combat | [Combat](COMBAT_MODEL.md) |
| 13 Evidence | [Evidence](EVIDENCE_MODEL.md) |
| 14 Mitigation | [Mitigation](MITIGATION.md) |
| 15 Replay | [Replay](REPLAY_SYSTEM.md) |
| 16 Testing | [Testing](TESTING.md) |
| 17 Performance | [Performance](PERFORMANCE.md) |
| 18 Version abstraction | [Version support](VERSION_SUPPORT.md) |
| 19 Intave research | [Reference analysis](INTAVE_REFERENCE_ANALYSIS.md) |
| 20 Updated roadmap | [Roadmap](ROADMAP.md) |
| 21 Phase 0 criteria; 22 Phase 1 criteria | This document |
| Independent AI workflow | [AI rules](AI_DEVELOPMENT_RULES.md) |

## Phase 0 acceptance criteria

- All 19 requested documents plus roadmap/review index exist and use DECISION / WHY / ALTERNATIVES / TRADEOFFS for major choices.
- Existing staged source/configuration is inventoried and preserved; future migration is justified and explicitly deferred.
- Seventeen module boundaries, exhaustive direct dependencies and a platform boundary are defined without production dependency cycles.
- Ordering is defined at actual capture/offer, with outbound/main-thread race limits, write outcomes, epoch handling and overload barriers. Effect admission has an explicit linearization boundary; already-started platform effects cannot be retroactively undone.
- Each state component has one writer, transition points, snapshot semantics and count/byte/lifetime bounds.
- World, ACK, simulation and combat models propagate missing knowledge; resource exhaustion cannot produce a negative proof.
- Evidence correlation, mitigation expiry/rechecks, durable capture and replay continuation avoid circular prerequisites.
- All roadmap phases are retained or explicitly split/moved with reasons; review and human gates remain intact.
- Intave observations have sources and limits; proposed improvements are not falsely presented as measured competitor defects.
- Latest Minecraft/Paper mismatch is documented; no unsupported production/version/performance claim is made.
- Independent senior review resolves blockers/high-priority issues and ends APPROVE; human approval follows before progression. **Pending.**

Document existence is not architecture approval. Runtime properties in this package are obligations to test later, not claims already proven.

## Phase 1 acceptance criteria

1. Phase 0 independent review/human gate is recorded against a fixed revision/digest, with no unresolved blockers. Recheck native Minecraft/Paper release channels and pin development artifacts/toolchain; mark any provisional lane explicitly.
2. Migrate preserved skeleton atomically to the approved Gradle DAG; verify dependency boundaries, reproducible build, empty plugin load/unload and plugin identity. Keep all files/caches/build output under the repository. No detections, violations, setbacks or punishment are enabled.
3. Demonstrate safe channel hook lifecycle and observation after relevant transformation/cancellation; document known plugin/proxy incompatibilities. If direct adapter cannot satisfy this, stop adapter work for reviewed replacement instead of guessing.
4. Immutable envelopes cover mandatory movement, action, outbound state, ACK/teleport and protocol lifecycle events; large payloads have admission/ownership rules. Unit tests cover field presence, malformed sizes/numerics, bundles, unknown capability and protocol transitions.
5. Capture sequences preserve per-direction order, have no reservation/publication holes and retain causal metadata. Test cross-thread outbound writes, main-thread bridge races, ACK-before-write-callback, failure/cancellation and connection close. Do not claim full physics application semantics merely from these tests.
6. Bounded actor/bridge/I/O admission and reserved health latches prevent new effect admission after observation loss. Test both sides of the effect-admission/invalidation race and recorded recovery for an already-admitted effect. Fault tests prove no silent gap, unbounded allocation, buffer leaks, blocking main-thread/network work or unsafe Bukkit access.
7. Minimal record/restore replays normalized inputs and clock metadata with identical sequence digests in separate runs; corrupted/missing data is rejected or explicitly incomplete. Fixtures are original and sanitized.
8. Observe-only packet-load benchmarks report queue latency, CPU, allocations, heap and Paper MSPT with raw metadata. Run 50/100-player development profiles and deliberate saturation; larger load qualification remains Phase 24. Failed targets are reported, not declared supported.
9. Lifecycle tests cover disconnect/reconnect, failed startup, exceptions, full queues and disable. No hooks, retained buffers or stale action callbacks survive cleanup. No actor state mutation occurs outside its owner.
10. Independent senior review, fix verification, relevant regression/performance evidence and human approval are complete before Phase 2. Native ACK/application proof may continue in Phase 7a, but remains a hard gate before dependent enforcement.

## Top architectural risks for Claude to review

| Priority | Risk / concrete challenge | Evidence required to close implementation risk |
|---|---|---|
| Blocker for dependent enforcement | ACK receipt might be mistaken for game-thread application; forged/late ACKs and bundled updates | Native-client causal experiment and version-specific guarantee table |
| High | Direct transport hook may miss transformations or reorder cross-thread writes; reserved health latch race | Exact Paper hook analysis, wire trace and concurrency/failure tests |
| High | Candidate pruning/merging or frontier truncation could silently omit legal trajectories | Soundness argument, completeness tests, cap-exhaustion regression and coverage metrics |
| High | Observer history/interpolation could combine incompatible states or lose coverage under caps | Coherent scene fixtures, entity incarnation tests, admitted-memory accounting |
| High | Actor result becomes stale before main-thread action; resync/setback loop | Epoch/generation checks, blocked-destination and late-confirm tests |
| High | Capture durability adds delay; dependency closure or action continuation can be incomplete | Slow/failing storage tests, expiry metrics, complete-capsule/continuation validation |
| High | Latest-Minecraft target changes during Paper release transition | Stable build pin, ruleset provenance and complete requalification |
| Medium | Seventeen modules/API contract growth may add complexity without real isolation | DAG/build boundary enforcement and review of shared API types |
| Medium | Global/private/pinned budgets may be double-counted or undercount JVM overhead | Heap/JFR analysis, adversarial admission/churn tests and measured allocator slack |
| Medium | Benign validation becomes mostly UNCERTAIN while benchmarks appear fast | Required capability/coverage denominators beside latency and false-positive statistics |

## Review handoff

Claude should begin with Architecture, Threading, Latency, Movement Simulation, Replay and this risk table, then inspect the remaining contracts and Intave evidence. Review each required category from AI_DEVELOPMENT_RULES.md. Record exact reviewed revision/digest, findings and final APPROVE or REQUEST CHANGES in a new review artifact under docs. No review has been requested through an external messaging service or represented as completed here.

## Phase 0 validation record

Document checks found all 19 required specifications, plus Roadmap, this review submission and the root README (22 Markdown files total). Each required specification contains the four decision labels. Local Markdown link targets resolve. The exhaustive dependency table was parsed and topologically traversed: all 16 production modules are acyclic; testing remains test-only. Existing `.gitignore`, POM, JavaPlugin source and plugin.yml SHA-256 hashes match the pre-edit inventory. Git's original four staged additions remain unchanged; only README and docs were added, unstaged. No build or runtime tests were run because this phase changed no implementation. These checks support submission completeness, not independent architecture approval.
