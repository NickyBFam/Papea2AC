# Phase 0 review submission and acceptance gates

**PHASE 0 REMEDIATION STATUS: READY FOR SENIOR ARCHITECTURE REVIEW #2**

Author: GPT6 Astra. Review 1 verdict: REQUEST CHANGES, preserved in PHASE_0_SENIOR_REVIEW.md. Review 2 is pending. Start with [finding dispositions](PHASE_0_REMEDIATION.md). This status is not approval; no Phase 1/build migration/detection/commit/merge was performed.

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
- Twenty modules (19 production plus test-only testing), exhaustive direct dependencies and four concrete behavior paths are defined. Public api, internal model and replay-schema have separate ownership.
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

1. Review 2 APPROVE binds to the working-tree content manifest; human approval precedes the baseline commit. Verify committed blobs equal approved bytes and record the actual revision in a separate receipt before Phase 1. No HEAD exists yet; a manifest is not a commit. Recheck version channels/pin artifacts, marking provisional lanes explicitly.
2. Migrate preserved skeleton atomically to the approved Gradle DAG; verify dependency boundaries, reproducible build, empty plugin load/unload and plugin identity. Keep all files/caches/build output under the repository. No detections, violations, setbacks or punishment are enabled.
3. Demonstrate AdapterBoundaryProbe and Packet P2 provisional/outcome semantics using cancellation, mutation/replacement and inserted foreign handlers. If no production authoritative outcome provider can be qualified, ship capture-only OBSERVE_ONLY with no certification and document the enforcement gate. No assumption of acceptance from listener priority, normal return or periodic topology checks.
4. Implement the enumerated observation matrix including explosion, vehicles/passengers/camera, lifecycle/health, tick-rate/freeze, held item/swing/control/tick-end and version mapping. Missing affecting entry must drop capability. Tests cover bundles/field presence/numerics/protocol transitions; later physics remains unimplemented.
5. Capture sequences preserve per-direction order, have no reservation/publication holes and retain causal metadata. Test cross-thread outbound writes, main-thread bridge races, ACK-before-write-callback, failure/cancellation and connection close. Do not claim full physics application semantics merely from these tests.
6. Bounded actor/bridge/I/O admission and reserved health latches prevent new effect admission after observation loss. Test both sides of the effect-admission/invalidation race and recorded recovery for an already-admitted effect. Fault tests prove no silent gap, unbounded allocation, buffer leaks, blocking main-thread/network work or unsafe Bukkit access.
7. Minimal replay-schema projection/restore uses synthetic/transport state and cross-host numeric-conformance/digest tests. Do not require future physics to exist. Unknown schema/missing closure is rejected; global replay quota/adaptive allocation is tested.
8. Observe-only 50/100-player profiles report event-loop time/utilization/copy volume, queue latency, CPU, per-path allocation, heap, achieved horizons, admission tiers and MSPT. Exercise cost quotas synthetically and saturation; report failed targets. Later solver/load work remains Phase 6/24.
9. Lifecycle tests include terminated/hung shard distinction, supervisor lease cleanup and admission removal, global runtime mode/guard machinery with synthetic decisions, configuration canonicalization, disconnect/disable and full queues. No unsafe actor mutation or unbounded main-thread waiting.
10. Independent senior review, fix verification, relevant regression/performance evidence and human approval are complete before Phase 2. Native ACK/application proof may continue in Phase 7a, but remains a hard gate before dependent enforcement.

## Top architectural risks for Claude to review

| Priority | Risk / concrete challenge | Evidence required to close implementation risk |
|---|---|---|
| High implementation gate | Correlated authoritative packet outcomes may be unavailable in ordinary plugin integration | Enforced observe-only fallback; qualify outcome provider before any certification |
| High | Direct transport hook may miss transformations or reorder cross-thread writes; reserved health latch race | Exact Paper hook analysis, wire trace and concurrency/failure tests |
| High | Sound staged exclusion may still lack precision under application/contact ambiguity | Per-operator containment, pilot case counts and numeric adversarial coverage gates |
| High | Observer history/interpolation could combine incompatible states or lose coverage under caps | Coherent scene fixtures, entity incarnation tests, admitted-memory accounting |
| High | Actor result becomes stale before main-thread action; resync/setback loop | Epoch/generation checks, blocked-destination and late-confirm tests |
| High | Capture durability adds delay; dependency closure or action continuation can be incomplete | Slow/failing storage tests, expiry metrics, complete-capsule/continuation validation |
| High | Latest-Minecraft target changes during Paper release transition | Stable build pin, ruleset provenance and complete requalification |
| Medium | Twenty-module split and mapper schemas may drift | DAG behavior-path checks, explicit public exclusions and round-trip projections |
| Medium | Global/private/pinned budgets may be double-counted or undercount JVM overhead | Heap/JFR analysis, adversarial admission/churn tests and measured allocator slack |
| High | All-UNCERTAIN adversarial or dense-combat behavior can appear safe but detect nothing | Independent fixed coverage gates per scenario and per-session reporting |
| Guarded implementation risk | ACK receipt is accidentally treated as application | Existing prohibition retained; separate application-set growth is the high coverage risk |

## Review handoff

Claude should begin with Architecture, Threading, Latency, Movement Simulation, Replay and this risk table, then inspect the remaining contracts and Intave evidence. Review each required category from AI_DEVELOPMENT_RULES.md. Record exact reviewed revision/digest, findings and final APPROVE or REQUEST CHANGES in a new review artifact under docs. No review has been requested through an external messaging service or represented as completed here.

## Phase 0 validation record

Review 1-era 22-file validation is historical and preserved with the archived inputs. Remediation validation results and the new content manifest are recorded in PHASE_0_REMEDIATION.md. Three new supporting specifications cover configuration, interaction and numerical policy. Source/config skeleton and Review 1 hashes must remain unchanged. This is document validation only; no runtime tests or benchmarks are represented as executed.
