# Performance and bounded resources

## Decision F1: measure capacity, cap every resource

**DECISION:** Design for experiments at 50, 100, 250, 500 and 1000 connected active players; publish only measured capacity. Apply per-session and global byte/work limits before optimization.

**WHY:** Player count alone says little about packet mix, world diversity, collision complexity or CPU saturation.

**ALTERNATIVES:** Assume asynchronous means fast; claim support from idle bots; unbounded queues that conceal overload.

**TRADEOFFS:** Caps may reduce coverage under unusual legitimate loads. Report uncertainty/coverage beside latency so apparent speed cannot be obtained by silently skipping work.

## Initial resource budget registry

All values are reviewable starting limits, not benchmark results. Counts **and retained bytes including object overhead** must be bounded; implementation must reconcile estimated charges with measured retained heap and include allocator slack. A player-level 16 MiB hard reservation encompasses all private items below; individual maxima are not promises that all can fill simultaneously. Reject or degrade before exceeding the aggregate cap.

| Resource | Initial limit | Exhaustion policy |
|---|---|---|
| Player ingress | 1024 envelopes / 1 MiB including referenced small payloads | Health barrier, explicit gap, resync |
| Main-to-network bridge | 256 messages / 256 KiB per session | Lost-observation barrier, no unbounded event-loop tasks |
| Control lane | 16 small records plus fixed-size health/close latches | Latch still blocks actions; disconnect if recovery impossible |
| Bulk packet owned payload | 2 MiB per payload, 8 MiB/session, 64 MiB global pool; charged outside private arena but inside global quota | Unknown affected state/resync, no partial trustworthy decode |
| Timeline | 4096 records / 2 MiB / 10 s | Evict coherent ranges; missing coverage is explicit |
| Replay staging | 2 MiB/session | Incomplete capture, skip capture-required effects |
| Private world/entity history | 8 MiB/session; 4096 section handles, 256 entity histories, 64 revisions/entity | Evict within fairness; unknown beyond coverage |
| Shared immutable world content | 512 MiB global, all pins charged | Deny new pins; cancel dependent validation/capture |
| Simulation | 256 frontier candidates, 4096 candidate-step operations/event, 3 inferred steps | UNCERTAIN unless a complete legitimate witness already exists |
| Collision | 4096 visited cells and 16384 tested boxes/query; nesting depth 16 | UNKNOWN/BUDGET_EXHAUSTED |
| Aggregate movement geometry | 65536 total visited-cell/tested-box units across all candidates and steps per movement event | UNCERTAIN on incomplete search; yielding never resets budget |
| Combat | 64 scenes and 4096 total geometry operations/attack; 64 pending attacks/player | UNCERTAIN, bounded resolution timeout |
| Latency/impulses | 256 markers/10 s; 64 impulses; 256 RTT samples | Expire with uncertainty, no cheating weight |
| Inventory | One bounded active window; 256 pending deltas / 256 KiB | Invalidate inventory-dependent validation |
| Evidence | 256 summaries, 128 causal groups / 256 KiB | Bounded aggregation; capture before any required effect |
| Shape cache | 128 MiB global | Evict; identical semantics without cache |
| Replay I/O queue | 64 MiB global including admitted pinned payload accounting | Reject capture, emit operational diagnostic |
| Main-thread actions | 1024 intents / 1 MiB global, one active correction/session | Reject with outcome; never block worker |
| Logs/metrics | Fixed metric names, 4096 queued diagnostic records / 4 MiB | Coalesce/rate-limit; never client-controlled metric labels |
| Disk captures | 5 GiB or 7 days | Evict oldest permitted captures; failure disables required effects |

All global stores/queues and session admissions draw from a configured total anticheat memory budget. Default proposal: min(1 GiB, 25% of JVM max heap), with 20% of that budget reserved for accounting error/transient overhead. Fixed global maxima are ceilings, not eagerly allocated buffers; startup lowers compatible quotas or refuses configuration if they cannot fit. This default is not a 1000-player configuration. Load experiments explicitly size heap/admission limits and report rejected sessions. Budget estimates exclude Paper world heap, which must be measured separately.

Memory estimate: plugin retained heap <= admitted session reservations + admitted bulk bytes + shared world/cache charges + queues + bounded worker scratch + safety margin. Shared content is charged once globally; per-player references still have their own overhead charges. Use heap dumps/JFR to verify the equation after churn and GC. Idle cleanup, disconnect, incomplete capture and pinned-view eviction must release all reservations.

## Measurement methodology

Record CPU model/cores, OS, Java vendor/version, GC/heap flags, Paper build/hash, native client/proxy profile, world seed/layout, plugin configuration, ruleset, simulation coverage, view/simulation distances and background workload. Freeze power settings and unrelated work for comparison. Compare Paper alone, observation-only plugin and full enabled supported engines; use the same scenario trace.

Load matrix: 50/100/250/500/1000 players, each with idle control, ordinary walking/jumping, collision-heavy builds, swimming/elytra/vehicles where supported, dense combat, chunk churn, latency variation and malformed/adversarial bursts. Separate replay-only throughput from real network/server load. Bots must exercise actual packets and relevant mechanics; fixture playback alone cannot establish main-thread impact.

For each cell run 10-minute warmup, 30-minute measurement, three independent repetitions; pre-release adds a four-hour soak and repeated join/leave churn. Record unsuccessful cells and saturation, not just successful peaks. Retain raw histograms and seeds. Inject slow disk, capped CPU, paused client traffic and server tick stalls in separate fault profiles. Stop a run before unsafe host exhaustion; record the stop reason and achieved load.

Metrics: process and worker CPU/core, retained heap/session and shared heap, bytes allocated/event and MB/s, GC pause/time, p50/p95/p99/max capture-to-commit latency, separate queue wait/service time, simulation and collision latency/work, raytrace latency/work, per-shard queue depth/age, backlog bytes, uncertainty/budget abort rates, admission drops and incremental main-thread MSPT. Use monotonic timestamps; do not omit delayed/dropped requests from latency accounting. Report batch effects and coordinated omission safeguards in the load generator.

JMH candidates: normalization, immutable section lookup, contextual shape resolution, slab ray intersection, one physics step, candidate expansion and replay codec. Use representative parameter sets, forks and warmups; consume results and guard against constant folding. Initial recipe: 3 forks, 5 x 1-second warmup, 10 x 1-second measurement, allocation profiler. This measures kernels, not supported player capacity.

## Proposed engineering targets, not current guarantees

Under a declared supported workload: p99 queue age <25 ms, p99 capture-to-commit <50 ms, incremental p95 main-thread MSPT <=2 ms, and no retained-heap growth after stable churn/GC. CPU must retain operational headroom; provisional goal <=70% of available worker CPU over the measurement window. Establish per-kernel latency/allocation baselines after implementation; avoid invented nanosecond promises now.

Acceptance additionally requires zero unmarked gaps, zero unsafe Bukkit access, no automated effects from incomplete validation and no resource-bound violations. A fast run dominated by UNCERTAIN is a failed coverage claim. Publish player-hours, adverse-event/false-positive counts and confidence intervals. Zero observed false positives is not proof of zero risk; for independent trials, explain assumptions before using statistical upper bounds. Correlated per-player events require appropriate grouping.
