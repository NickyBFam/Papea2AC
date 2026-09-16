# Development roadmap

## Decision O1: move prerequisites forward, retain phase identities

**DECISION:** Preserve the user's phase numbers for tracking, but begin version contracts, replay schema, regression harness and resource budgets before the packet engine is considered complete. Split transport/ACK groundwork from later latency qualification. Implement each engine in observation mode before enabling actions.

**WHY:** Packet ordering cannot be tested without a recorder; simulation cannot be trusted without version/ACK semantics; resource safety cannot wait until Phase 23.

**ALTERNATIVES:** Strict numeric implementation order; implement detections first and retrofit replay/tests later.

**TRADEOFFS:** Some later phases have early foundation slices. Their later milestones remain meaningful qualification/expansion work, not duplicate implementations.

## Dependency-driven sequence

| Work order / original phase | Deliverable and exit gate |
|---|---|
| 0 | This specification, independent Claude architecture/red-team review, resolved findings and human approval |
| Foundation within 1, drawing forward 21/22/23/25 | Reviewed Gradle/module migration, Java/version pin, immutable events, quotas, minimal recorder/round-trip harness; no detections |
| 1 Packet engine | Proven transport hooks, exact ordered normalized capture, lifecycle/backpressure/failed-write tests, observe-only integration |
| 2 Player timeline | Actor components, epochs, causal slices, checkpoint restore, ownership/concurrency tests |
| 7a ACK groundwork (before world/physics enforcement) | Outbound ledgers and protocol marker/teleport capture; receipt/application guarantees experimentally documented |
| 3 World reconstruction | Observer baselines/deltas, entity histories, unknown coverage, memory/pinning bounds |
| 4 Collision | Versioned contextual shapes, clipping/rays and independent fixtures |
| 5 Physics | Pure per-mode step rules; vanilla differential fixtures and numeric profile |
| 6 Input reconstruction | Admissible domains, deterministic search and sound pruning/completeness tests |
| 7b Latency qualification | Integrated delayed-application branches, jitter/stall/proxy scenarios and horizon exhaustion tests |
| 9 Evidence foundation (before 8 outputs are actionable) | Typed provenance, correlation/deduplication, initial observe-only policy and replay capsules |
| 8 Movement validation | Witness/exclusion/uncertainty integration; no per-check physics; benign/adversarial coverage report |
| 10 Mitigation | Conditional main-thread intents, safe anchors, confirmed recovery and loop-breaker tests; separate enablement approval |
| 11 Combat engine | Observer scene reconstruction and contextual eligibility |
| 12 Reach/hitbox/ray tracing | Coherent historical geometry, pose/attribute/occlusion qualification |
| 13 Velocity/knockback | Shared simulation consumption and impulse ordering; no duplicate motion formula |
| 14 Timer | Monotonic timing, batching/stall allowances, ACK context and controlled rate-manipulation fixtures |
| 15 Rotation analysis | Bounded feature windows, legitimate sensitivity/input confounders and diagnostics |
| 16 Combat heuristics | Calibrated behavioral evidence with held-out benign/adversarial evaluation |
| 17 Click analysis | Protocol action timing separated from physical-click inference; no unsupported CPS-only guilt |
| 18 Placement/scaffold | Shared ray/outline, world prediction/reconciliation and behavior evidence |
| 19 Inventory validation | Window/state epochs, item-use effects, legal transitions and plugin compatibility |
| 20 Protocol validation expansion | Full semantic validators; malformed size/state safety already required in Phase 1 |
| 21 Replay expansion | Staff capture/inspect tools and linked action continuations; foundational recorder already exists |
| 22 Regression qualification | Full fixture corpus, minimization tooling and release matrix; tests have run every phase |
| 23 Performance refinement | Profile-guided layout/cache/queue improvements within established semantics/budgets |
| 24 Load/performance qualification | Full 50–1000-player matrix, churn/soak/fault campaigns; publish measured support envelope |
| 25 Version qualification/expansion | Latest stable pair requalification and any separately justified extra native/proxy profiles |
| 26 Staff/admin tooling | Permissioned review, capability/coverage dashboards, audit exports and explicit policy controls |
| 27 Experimental behavior/ML | Optional isolated research, provenance/consent/export policy, no authority over deterministic truth |

Minimal read-only health/coverage/queue diagnostics begin in Phase 1 because operators need to recognize degraded processing. Phase 26 adds full workflows; it is not the first time failures become visible.

Every phase exit requires specification, meaningful tests, independent review, changes/second review as needed, regression/performance evidence relevant to scope and human approval. The table defines no automatic promotion or deadline. A new release during development requires version requalification, not a moving dependency resolution.

## Rollout ladder

Offline fixtures -> controlled native-client test server -> observation-only realistic load -> staff-visible diagnostics -> independently reviewed limited setbacks -> broader rollout within measured coverage. Punishment automation is separately authorized policy, never the implied final step of writing a validator. Unsupported mechanics remain visible as unsupported.
