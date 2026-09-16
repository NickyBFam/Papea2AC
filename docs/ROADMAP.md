# Development roadmap

## Decision O1: move prerequisites forward, retain phase identities

**DECISION:** Preserve the user's phase numbers for tracking, but begin version contracts, replay schema, regression harness and resource budgets before the packet engine is considered complete. Split transport/ACK groundwork from later latency qualification. Implement each engine in observation mode before enabling actions.

**WHY:** Packet ordering cannot be tested without a recorder; simulation cannot be trusted without version/ACK semantics; resource safety cannot wait until Phase 23.

**ALTERNATIVES:** Strict numeric implementation order; implement detections first and retrofit replay/tests later.

**TRADEOFFS:** Some later phases have early foundation slices. Their later milestones remain meaningful qualification/expansion work, not duplicate implementations.

## Dependency-driven sequence

| Work order / original phase | Deliverable and exit gate |
|---|---|
| 0 | Remediation, content-addressed working-tree Review 2, resolved findings/APPROVE, human approval, verified baseline commit; no Phase 1 before these gates |
| Foundation within 1, drawing forward 21/22/23/25 | Public api/internal model/replay-schema/interaction DAG, Java pin, config canonicalization, global quota floors, replay projection contracts, numeric identity; no detections |
| 1 Packet engine | Observation matrix, correlated provisional/outcome model, boundary probe/fallback, NativeScenarioHarness transport lane, fairness/supervisor/global control, recorder cross-host tests and event-loop metrics; observe-only |
| 2 Player timeline | Processed versus certified frontiers, coverage dimensions, actual horizons, snapshot mapper, integration declarations/exemptions and bounded journals |
| 7a ACK groundwork (before world/physics enforcement) | Outbound ledgers and protocol marker/teleport capture; receipt/application guarantees experimentally documented |
| 3 World reconstruction | Lazy observer baselines/regional coverage recovery, intern dictionaries, relevant entity neighborhoods, block-property resolver, overlay storage and representation benchmarks |
| 4 Collision | Profile shape-use mapping, block/entity geometry, moving blocks/border, bounded inside/support sampling contracts and clearance service primitives |
| 5 Physics | Exact numeric operations, block physical properties, external forces/entity push, concrete and conservative abstract transfer containment, native motion harness; no exclusion before proofs |
| 6 Input reconstruction | Staged inverse/interval solver, conservative-envelope merge, discrete refinement accounting, concrete witness lane and bounded impossible-case exclusion tests |
| 7b Latency qualification | Application automata/eight-influence cap, sound branch retirement, 50/150/300 ms coverage-floor tests, history expiry |
| 9 Evidence foundation (before 8 outputs are actionable) | Root-set correlation, complete replay projections/capsules, three-scenario durability measurements, global anomaly observation; observe-only |
| 8 Movement validation | Certified-outcome integration, numeric benign/adversarial coverage gates; no success claim from all-UNCERTAIN |
| 10 Mitigation | Conditional main-thread intents, safe anchors, confirmed recovery and loop-breaker tests; separate enablement approval |
| 11 Combat engine | Observer scene reconstruction and contextual eligibility |
| 12 Reach/hitbox/ray tracing | Coherent historical geometry, pose/attribute/occlusion qualification |
| 13 Velocity/knockback | Shared simulation consumption and impulse ordering; no duplicate motion formula |
| 14 Timer | Monotonic timing, batching/stall allowances, ACK context and controlled rate-manipulation fixtures |
| 15 Rotation analysis | Bounded feature windows, legitimate sensitivity/input confounders and diagnostics |
| 16 Combat heuristics | Calibrated behavioral evidence with held-out benign/adversarial evaluation |
| 17 Click analysis | Protocol action timing separated from physical-click inference; no unsupported CPS-only guilt |
| 18 Placement/scaffold | anticheat-interaction placement/use/break/entity-action rules, face/cursor/timing, overlay reconciliation and checks-owned scaffold features |
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

Concrete ownership additions: conservative transfer=envelope primitives in Phase 5 and solver in 6; force observation in 1/7a and application in 5; entity neighborhood in 3 and push in 5; block properties 3/5; baseline/coverage 2/3; interaction contracts 1 and semantics 18; replay DTO 1 with per-domain mapper as each state arrives; numerical profile 1 and fidelity 5. No additional numbered phases are introduced. Phase 1 recorder uses synthetic/transport state only; it cannot be required to run future physics detectors. Review 2 resolves specification obligations now; scheduled empirical gates remain unfulfilled until their implementations exist.

Every phase exit requires specification, meaningful tests, independent review, changes/second review as needed, regression/performance evidence relevant to scope and human approval. The table defines no automatic promotion or deadline. A new release during development requires version requalification, not a moving dependency resolution.

## Rollout ladder

Offline fixtures -> controlled native-client test server -> observation-only realistic load -> staff-visible diagnostics -> independently reviewed limited setbacks -> broader rollout within measured coverage. Punishment automation is separately authorized policy, never the implied final step of writing a validator. Unsupported mechanics remain visible as unsupported.
