# Intave reference analysis

Research date: September 15, 2026 (America/New_York). Repository revision resolved through GitHub API: **8f3c430bbf1e6c5daaa6850afe691091160a399a**. This is a conceptual comparison, not an implementation audit, performance benchmark or endorsement. Papea2AC contains no copied Intave implementation or fixtures.

## Evidence and limits

Public repository README, architecture/block/cheatsheet documentation, repository tree, CI workflow and physics issue template were inspected. Public website pages about physics, attack ray tracing and heuristics were also read. Repository documentation at the pinned revision was cross-checked where used. Source filenames show organization and test topics; names alone do not prove algorithms, correctness or coverage. Private/cloud internals and exact current application-timing algorithms were not established.

The repository describes Intave as source-available and identifies Polyform Perimeter restrictions. Public visibility is not permission to reuse implementation. Our scope is high-level comparison and independently designed contracts; no legal conclusion about permissible derivative work is claimed. [Repository README](https://github.com/intave/intave/blob/8f3c430bbf1e6c5daaa6850afe691091160a399a/README.md).

| ID | Source | What it supports |
|---|---|---|
| I1 | [Pinned README](https://github.com/intave/intave/blob/8f3c430bbf1e6c5daaa6850afe691091160a399a/README.md) | Product-level simulation, recorded regression, delayed-ACK and optional cloud claims |
| I2 | [Structure](https://github.com/intave/intave/blob/8f3c430bbf1e6c5daaa6850afe691091160a399a/docs/STRUCTURE.md) | Checks, active/passive modules and legacy service organization |
| I3 | [Cheatsheet](https://github.com/intave/intave/blob/8f3c430bbf1e6c5daaa6850afe691091160a399a/docs/CHEATSHEET.md) | Per-user block access, distance caveat, main-thread transaction guidance |
| I4 | [Block system](https://github.com/intave/intave/blob/8f3c430bbf1e6c5daaa6850afe691091160a399a/docs/BLOCK_SYSTEM.md) | Material/variant model, shape kinds, cache/patch interaction |
| I5 | [Self-test CI](https://github.com/intave/intave/blob/8f3c430bbf1e6c5daaa6850afe691091160a399a/.github/workflows/tests.yml) | Configured server-version test matrix, not evidence each run passed |
| I6 | [Physics issue template](https://github.com/intave/intave/blob/8f3c430bbf1e6c5daaa6850afe691091160a399a/.github/ISSUE_TEMPLATE/1-physics_bug.md) | Reproduction/dump and optional movement recording workflow |
| I7 | [Physics documentation](https://docs.intave.ac/mechanics/checks-09-physics.html) | Simulation philosophy and published mitigation discussion, including historical caveats |
| I8 | [AttackRaytrace](https://docs.intave.ac/mechanics/checks-02-attackraytrace.html) | Observer-relative entity position and client ray reconstruction |
| I9 | [Heuristics](https://docs.intave.ac/mechanics/checks-06-heuristics.html) | Behavioral detection limitations; undisclosed analytical details |
| I10 | [Pinned repository tree](https://github.com/intave/intave/tree/8f3c430bbf1e6c5daaa6850afe691091160a399a/src) | Presence of simulation, feedback, collision, replay-related and test organization |

## Architectural comparisons

### Shared simulation, checks and module boundaries

**INTAVE APPROACH — documented:** I2 locates detection in check classes with supporting packages, and distinguishes ordered active modules, passive services and older high-order services. This is richer than isolated speed/fly thresholds; package-level organization does not establish a compile-time dependency DAG.

**PAPEA2AC PROPOSED APPROACH — DECISION:** Keep shared engines but put physics, candidate orchestration, world, collision and evidence in separately constrained modules. Core alone composes them; checks receive engine results.

**WHY:** Compile-time boundaries can make forbidden platform dependencies and accidental physics duplication fail a build. Uniform lifecycle ownership is easier to review than several loading styles.

**ALTERNATIVES:** Follow a package-only architecture; put simulation under a Physics check.

**TRADEOFFS:** More APIs/build configuration and possible over-modularization. This is a proposed maintainability improvement, not proof that Intave's modules currently fail.

### Movement simulation and uncertainty

**INTAVE APPROACH — documented claim:** I7 favors simulated legal motion over layered static constraints and describes many environmental inputs. Its page includes older support caveats and numerical accuracy statements; these are not independently validated current guarantees.

**PAPEA2AC PROPOSED APPROACH — DECISION:** Adopt legitimate-state search, with explicit SimulationInput/Candidate/Result/Error/Uncertainty, per-rule coverage and a completeness certificate for INVALID. Preserve supported next-state alternatives across observations.

**WHY:** A missed candidate caused by a work cap is different from proving all legal trajectories impossible. Evidence must expose that distinction.

**ALTERNATIVES:** Single prediction plus tolerance; always choose the closest branch; unlimited branching.

**TRADEOFFS:** More UNCERTAIN outcomes under hard cases. The public material reviewed does not establish whether current Intave already implements equivalent completeness guarantees; do not claim their absence.

### Delayed acknowledgements and threading

**INTAVE APPROACH — documented:** I1 describes late-ACK compensation. I3 advises main-thread transactions and per-user block access. These sources do not prove the exact current ACK application boundary, callback ownership or full threading model.

**PAPEA2AC PROPOSED APPROACH — DECISION:** Separate wire order, write result, receipt frontier and application interval. One actor owns latency/gameplay state; platform callbacks submit sequenced immutable observations. A version-specific experiment must establish any application guarantee.

**WHY:** ACK arrival need not identify the client tick that applied a change. Two clocks and an unproven ping estimate cannot replace causal constraints.

**ALTERNATIVES:** Main-thread mutable feedback callbacks; fixed ping allowance; high-ping exemption.

**TRADEOFFS:** Ledger/history cost and adversarial ambiguity. Papea2AC intends to make guarantees auditable rather than transplant an undocumented algorithm. Main-thread work still executes where the platform requires it.

### Blocks, geometry and caching

**INTAVE APPROACH — documented:** I4 describes Bukkit Material types, variant indexes, different shape uses and patches after caches. It notes repeated resolution and possible re-entry through block access. I3 warns about distant access. These identify areas to investigate, not measured bottlenecks.

**PAPEA2AC PROPOSED APPROACH — DECISION:** Use platform-neutral canonical state keys, explicit unknown coverage, immutable observer revisions and shape-kind/context cache keys. Bound recursive context resolution and fail uncertain on exhausted dependencies.

**WHY:** Replay needs stable keys independent of runtime registry indexes. Contextual geometry must not be silently reused across different poses, neighbors or worlds.

**ALTERNATIVES:** Cache by material only; global server-current block cache; per-query platform resolution.

**TRADEOFFS:** Canonicalization and more precise keys cost CPU/memory. Benchmark static hits, contextual misses, neighbor resolution and world churn separately. Intended improvement is predictable behavior and platform isolation, not an unmeasured speed claim.

### Combat and historical ray tracing

**INTAVE APPROACH — documented:** I8 reconstructs target position from the observer's perspective before checking the client's ray. Published distance/accuracy examples are product documentation, not a universal current-version reach specification or verified benchmark.

**PAPEA2AC PROPOSED APPROACH — DECISION:** Adopt observer-relative history; make pose, eye height, interpolation, entity incarnation, interaction attributes, occlusion and causal scene coherence explicit inputs.

**WHY:** A fixed distance or arbitrary union of historic boxes can misclassify legitimate attacks or admit impossible combinations. Modern attribute/ruleset support must be version-tested.

**ALTERNATIVES:** Current target location; blanket RTT rewind; any historic target position.

**TRADEOFFS:** Bounded coherent scenes require more computation and may remain unresolved. Exact Intave scene selection was not audited, so no comparative bypass claim is made.

### Deterministic validation and heuristics

**INTAVE APPROACH — documented:** I9 acknowledges that some automated combat can follow legal protocol behavior and leaves analytical details undisclosed. I1 separately describes simulation, heuristics and optional cloud detection. This distinction is useful; it does not reveal proprietary classifiers.

**PAPEA2AC PROPOSED APPROACH — DECISION:** Separate deterministic exclusions from behavioral feature windows and calibrated suspicion. Keep optional ML at the end of the roadmap; no cloud dependency for core correctness or silent sample export.

**WHY:** Legal motion or legal aim is not proof of a human, while statistical suspicion is not a proof of physically impossible behavior.

**ALTERNATIVES:** Mix every feature into one violation score; make cloud availability part of core validation.

**TRADEOFFS:** Some sophisticated automation remains undetected initially. Transparent evidence/provenance helps reviewers audit false positives without pretending all behavior is decidable.

## Regression and false-positive workflow

I5 configures Gradle tasks named testPaper for 20 versions from 1.8.8 through 26.2. The pinned libs tree contains mostly Spigot-named server artifacts, a Paper artifact and an alternative 1.8.8 artifact; the task name is not proof that every run uses Paper. Exact per-task runtime provenance requires inspecting its configuration/output. I6 requests reproduction/dumps and optional recordings; I10 shows test organization, not pass rates or oracle independence. [Bundled artifact tree](https://github.com/intave/intave/tree/8f3c430bbf1e6c5daaa6850afe691091160a399a/libs).

**DECISION:** Adopt the principle that every confirmed false positive becomes a retained scenario. Papea2AC's fixtures additionally specify expected uncertainty, work limits, causal ordering, storage failure and action prohibition, and must identify an independent oracle.

**WHY:** A regression should protect both correctness and the ability to explain why an action was or was not taken.

**ALTERNATIVES:** Test only final position residual; record packets without world/ruleset closure.

**TRADEOFFS:** More fixture metadata and lifecycle maintenance. This is Papea2AC's explicit test contract, not a claim Intave lacks such tests.

I7's staged mitigation discussion usefully separates detection sensitivity from player impact. Papea2AC will measure false INVALID, false actions, unresolved coverage and per-player correlated incidents separately. Published rates cannot be transferred between servers, eras or definitions, and successive filter probabilities cannot be multiplied without justified dependence assumptions.

## Performance hypotheses to test

| Hypothesis, not measured Intave defect | Papea2AC experiment / intended guardrail |
|---|---|
| Repeated contextual shape resolution can dominate dense collision cases (I4) | Profile context lookup/cache misses; bounded keys and recursion; prove cache/no-cache equality |
| Multiple movement/application alternatives increase work | Record candidate-step histograms and cap saturation; completeness prevents cap-driven false INVALID |
| Long-latency observer state increases retained history | Measure bytes by overlay/revision/pin; finite horizon with explicit missing coverage |
| Runtime platform types can complicate offline test setup (I4) | Run core replay without Bukkit; canonical registry fixtures |
| Serialized platform callbacks could add queue delay (I3) | Measure bridge age and main-thread MSPT; do not assume a main-thread instruction describes all Intave processing |

No claim that Papea2AC is faster, more accurate or more scalable is justified before equivalent version/workload benchmarks. Do not run comparative tests on different coverage settings and call the result an engine speedup.

## Keep, avoid and unresolved questions

Keep conceptually: shared legal-motion simulation; observer-relative worlds; dedicated geometry; late-ACK awareness; reproducible bug scenarios; separate behavior analysis.

Avoid adopting without justification: legacy multi-version breadth, framework-dependent domain identities, opaque callback mutation, cache keys missing context, undocumented confidence thresholds, any accuracy claim without a reproducible denominator, and cloud coupling for deterministic correctness. These are Papea2AC design exclusions, not an assertion that Intave exhibits every anti-pattern.

Review questions remain: Can the outcome adapter provide certification rather than capture alone? Are constraint envelopes precise enough under budgets? Do observer histories and globally pooled captures fit real workloads? These require experiments. ACK receipt/application separation is already explicit; its principal remaining architectural cost is unresolved application-state growth.

## Review 2 additions: five omitted observations and decisions

The pinned GitHub tree was re-fetched during remediation; observations below concern organization only, not copied or audited algorithms.

| Public observation / source | Papea2AC decision and reason | Alternative / tradeoff |
|---|---|---|
| Separate block/physics entries for material responses and block/inside/EntityMovement | Adopt separate BlockBehaviorQuery in world with version definitions and physics application; shape coverage is insufficient | Putting responses in collision couples occupancy and behavior; extra contract needs coverage tests |
| world/permission and access/player/event contain integration/permission surfaces | Expose scoped expiring declarations/exemptions and read-only coverage through public api | No integration channel leaves custom movement unexplained; authorized plugins can intentionally reduce coverage |
| trustfactor and access/player/trust contain trust concepts | Reject reputation-scaled physics tolerances or proof. Use explicit audited scope exemptions instead | Trust-adjusted sensitivity could improve operator convenience but obscures deterministic meaning |
| MovingWorldBorder and piston/slime/shulker/geyser movement entries | Model kinematic displacement and temporal geometry separately from velocity impulses | Treating all world-induced motion as velocity loses timing/contact semantics |
| ThreeTickSimulationSearch and frequency/branch tests exist | Keep stable likely-witness ordering, but use staged constraints for exclusion | Names do not prove three steps universally suffice or Intave solves the proposed caps; no empirical coverage conclusion follows |

Sources at the pinned revision: [block mechanics](https://github.com/intave/intave/tree/8f3c430bbf1e6c5daaa6850afe691091160a399a/src/main/java/de/jpx3/intave/block), [integration surface](https://github.com/intave/intave/tree/8f3c430bbf1e6c5daaa6850afe691091160a399a/src/main/java/de/jpx3/intave/access/player), [trust organization](https://github.com/intave/intave/tree/8f3c430bbf1e6c5daaa6850afe691091160a399a/src/main/java/de/jpx3/intave/trustfactor), [world border](https://github.com/intave/intave/tree/8f3c430bbf1e6c5daaa6850afe691091160a399a/src/main/java/de/jpx3/intave/world/border), [search organization](https://github.com/intave/intave/tree/8f3c430bbf1e6c5daaa6850afe691091160a399a/src/main/java/de/jpx3/intave/check/movement/physics/search).

Canonical archival block identities do not require expensive canonical strings in hot loops: Papea2AC now uses runtime interning plus a replay dictionary. Per-run variant IDs are not inherently defective; not recording their meaning would be. Likewise, a directory/test name is not independent justification for our three-step cap or a claim of bounded exhaustive exclusion. These qualifications partially disagree with the review's stronger comparative inference while accepting the missing research topics.
