# Performance and bounded resources

## Decision F1: measure capacity, cap every resource

**DECISION:** Design for experiments at 50, 100, 250, 500 and 1000 connected active players; publish only measured capacity. Apply per-session and global byte/work limits before optimization.

**WHY:** Player count alone says little about packet mix, world diversity, collision complexity or CPU saturation.

**ALTERNATIVES:** Assume asynchronous means fast; claim support from idle bots; unbounded queues that conceal overload.

**TRADEOFFS:** Caps may reduce coverage under unusual legitimate loads. Report uncertainty/coverage beside latency so apparent speed cannot be obtained by silently skipping work.

## Initial resource budget registry

**Decision F2 — global reservation first.** Remove the old 16 MiB/session reservation. All live allocations/pins are admitted against a global AC budget B; at least 20% stays unassigned for measured overhead/transients. A session's ceiling is not allocated at login. **WHY:** Per-player maxima cannot imply capacity. **ALTERNATIVES:** Allocate each player's maximum; silently shrink history. **TRADEOFFS:** Explicit limited-coverage admission when floors do not fit. All figures below are design estimates requiring retained-heap verification, not measured minima.

Two tiers: CORE_OBSERVATION floor = 2 MiB/session; QUALIFICATION target = 4 MiB/session. Floor components are timeline 512 KiB, private world/entity 768 KiB, ingress/bridge/control 256 KiB, pending outcome journal 256 KiB, and other components/metadata 256 KiB. In the target tier: timeline 1 MiB, world/entity 2 MiB, and the remaining components 1 MiB. Entity/history storage is shared within that world quota, not added again. Evidence/inventory/latency subcaps must fit the remaining arena and can cause local explicit coverage loss; the floor guarantees resource-safe observation, not every mechanic or a three-second horizon. Until measured implementations fit, these tiers cannot be advertised as supported footprints.

Reference global partition G = 256 MiB: shared world 96 MiB, shape cache 32 MiB, replay 96 MiB, other queues/bulk/worker scratch/control 32 MiB. These are partitions, not extra memory outside B. Increase G explicitly for greater shared-world diversity or workers only after recalculating admission. World coverage may be insufficient even at the target private tier.

| Resource | Count/work ceiling and byte policy | Exhaustion |
|---|---|---|
| Ingress / bridge / control | 1024 /256 /16 records; combined tier arena 256 KiB floor, fixed latches | Barrier and explicit gap; no hidden queue |
| Provisional outcome journal | 256 attempts /256 KiB /500 ms | UNCERTAIN_PIPELINE_OUTCOME, no later dependent promotion |
| Timeline | 4096 records, 512 KiB floor or 1 MiB target, three-second target | Shorter achieved horizon reported |
| World / entities | 4096 section handles, 256 summaries, 32 deep histories, 96 revisions/history; tier byte cap | Regional/entity unknown, no silent baseline recovery |
| Bulk / scratch / global infrastructure | 2 MiB single payload, 4 MiB/session exposure ceiling; total within 32 MiB infrastructure pool | Reject capture/coverage before allocation |
| Replay | Global 96 MiB:48 rolling, 32 promotion, 16 I/O; no fixed per-session buffer | Adaptive fair retention, explicit incomplete capture |
| Shared world / shape cache | 96 /32 MiB reference partitions; all references/pins accounted | Deny/evict; coverage loss versus semantic cache miss distinguished |
| Solver | 256 nodes, 4096 transfer/step evaluations, 3 inferred steps | UNCERTAIN_SEARCH_INCOMPLETE unless complete proof already obtained |
| Collision | 4096 cells/16384 boxes/query,depth 16 | Unknown if incomplete |
| Movement geometry | 65536 cumulative cell/box/interval units/event | Incomplete search cannot be INVALID |
| Block inside sampling | 512 cells/step,charged to movement total | UNCERTAIN_BLOCK_BEHAVIOR |
| Entity motion neighborhood | 32 entities/step, 2048 push/collider operations,charged to movement total | UNCERTAIN_ENTITY_NEIGHBORHOOD |
| Application uncertainty | Eight active unresolved influences; 64 diagnostic records; 256 markers | UNCERTAIN_APPLICATION_OVERFLOW; diagnostics never extend query history |
| Combat / interaction | 64 scenes, 4096 geometry units/action; 64 pending/player | Minimum dependency horizon expiry |
| Inventory / evidence | 256 pending inventory deltas; 256 evidence summaries/128 groups; per-component 256 KiB ceilings within tier arena | No extra memory reservation; missing roots/data disables dependent actions |
| Sustained work | 2 million weighted units/session/second,burst 200000; 2048/visit or 32 events | UNCERTAIN_WORK_QUOTA and operational alert |
| Platform actions | 1024 intents/1 MiB within infrastructure pool;one active/player | Reject, never wait |
| Diagnostic/log queue | 4096 records/1 MiB within infrastructure pool;fixed metric keys | Coalesce, no client-controlled labels |
| Disk replay | 5 GiB or 7 days,configuration-owned | Audit eviction/failure; skip capture-required effects |

All limits charge retained bytes including headers/alignment, pool slabs and references. Published primitive size estimates are not enough: reconcile JFR/heap dumps after churn. Shared allocation is charged once globally, while retention liabilities from forensic pins additionally reserve capacity in the replay promotion partition; this deliberately conservative reservation prevents replay from monopolizing the world store. Reclamation tokens cover all pools. A live/hung owner cannot have memory freed underneath it.

### Scale and admission (MiB, rounded upward)

For this reference partition, required B >= (256 + N * tierMiB) /0.8. B excludes Paper world heap. These are memory-planning numbers only.

| Players | Floor B (2 MiB/session) | Qualification-target B (4 MiB/session) |
|---|---|---|
| 50 | 445 MiB | 570 MiB |
| 100 | 570 MiB | 820 MiB |
| 250 | 945 MiB | 1570 MiB |
| 500 | 1570 MiB | 2820 MiB |
| 1000 | 2820 MiB | 5320 MiB |

Default B remains min(1 GiB, 25% JVM max heap). It does not admit 1000 target-tier players: startup checks configured capacity, worker envelope and tier against the equation, and either refuses that AC configuration or explicitly selects CORE_OBSERVATION with MemoryTierLimited if the operator configured that fallback. It never silently lowers a floor. No host-capacity or complete-world claim follows from fitting this equation. Reducing optional rolling replay, shape cache or retention above the floor is allowed only by explicit validated allocation policy with visible coverage impact. A lower global partition requires its own admitted configuration.

### Derived retention horizon

Qualification target H=3 seconds, assumed 1000 total journal records/second including inbound attempts/outcomes, outbound entity traffic and control, average charged size<=256 bytes. Required records 3000 and bytes 768000 fit 4096/1 MiB. At 2000 records/s, count and byte bounds each give about 2.048 seconds; report this shortfall. Floor 512 KiB at 1000/s yields about 2.048 seconds even before checkpoint overhead, and makes no three-second guarantee.

Entity target:30 relevant entities at 20 revisions/s require 60 revisions/entity for 3 seconds, leaving room in the 96 cap for bursts. At a planning 128 bytes/revision, 30 * 60 * 128 = 230400 bytes (225 KiB), plus metadata/index overhead. This fits a 2 MiB target world arena only if remaining sections/deltas also fit. More than 32 relevant histories yields explicit coverage loss. Marker diagnostics cap 256 and 10 seconds, but a query cannot use them to extend missing other inputs.

Every query reports availableHorizon=min(timeline,world revisions,entity revisions,application constraints,checkpoint dependency closure). Pending attack timeout is min(3 seconds,availableHorizon), with current pins/bytes rechecked during waiting. Outbound entity records remain losslessly sequenced; no hidden aggregation to satisfy these rates. Forensic rolling windows are opportunistic and separate from reconstruction guarantees.

### Replay allocation lifecycle

Replay service owns a global allocator in its 96 MiB partition. Rolling share q=min(256 KiB,floor(48 MiB/activeSessions)), allocated lazily; below 16 KiB record only bounded metadata and report NoRollingCapture. Promotion reserves closure/pin liability from 32 MiB, at most 8 MiB/capsule and four concurrent promotions; existing promotion reservations are protected until deadline, never starve indefinite future captures. I/O charges its 16 MiB queue by retained/uncompressed bytes; compression does not create fictitious free quota. Large closure is rejected, not partially declared complete.

At normal load retain fair recent ranges; at high count q shrinks visibly; on pressure evict oldest unpromoted complete ranges, then reject new promotions. Detection-triggered promotion has priority over rolling retention but never bypasses the global cap. Shutdown stops promotion, drains bounded I/O within deadline, marks unfinished captures incomplete and releases leases after users finish. All policies retain zero cheating weight.

## Measurement methodology

Record CPU model/cores, OS, Java vendor/version, GC/heap flags, Paper build/hash, native client/proxy profile, world seed/layout, plugin configuration, ruleset, simulation coverage, view/simulation distances and background workload. Freeze power settings and unrelated work for comparison. Compare Paper alone, observation-only plugin and full enabled supported engines; use the same scenario trace.

Load matrix: 50/100/250/500/1000 players, each with idle control, ordinary walking/jumping, collision-heavy builds, swimming/elytra/vehicles where supported, dense combat, chunk churn, latency variation and malformed/adversarial bursts. Separate replay-only throughput from real network/server load. Bots must exercise actual packets and relevant mechanics; fixture playback alone cannot establish main-thread impact.

For each cell run 10-minute warmup, 30-minute measurement, three independent repetitions; pre-release adds a four-hour soak and repeated join/leave churn. Record unsuccessful cells and saturation, not just successful peaks. Retain raw histograms and seeds. Inject slow disk, capped CPU, paused client traffic and server tick stalls in separate fault profiles. Stop a run before unsafe host exhaustion; record the stop reason and achieved load.

Metrics: process and worker CPU/core, retained heap/session and shared heap, bytes allocated/event and MB/s, GC pause/time, p50/p95/p99/max capture-to-commit latency, separate queue wait/service time, simulation and collision latency/work, raytrace latency/work, per-shard queue depth/age, backlog bytes, uncertainty/budget abort rates, admission drops and incremental main-thread MSPT. Use monotonic timestamps; do not omit delayed/dropped requests from latency accounting. Report batch effects and coordinated omission safeguards in the load generator.

JMH candidates: normalization, immutable section lookup, contextual shape resolution, slab ray intersection, one physics step, candidate expansion and replay codec. Use representative parameter sets, forks and warmups; consume results and guard against constant folding. Initial recipe: 3 forks, 5 x 1-second warmup, 10 x 1-second measurement, allocation profiler. This measures kernels, not supported player capacity.

Event-loop measurements: utilization, AC callback time per family, copied bytes/second and bulk-copy tails. Initial qualification targets: small callback p99 <=50 microseconds, admitted bulk copy p99 <=500 microseconds, AC <=10% of loop wall time. These are measured targets, not interrupt-safe deadlines. Failure requires explicit lost coverage or adapter redesign; never retain unsafe mutable buffers. Break allocations down into normalization, solver nodes, collision scratch, journal and evidence.

Cost-maximizing adversarial load combines complex stairs/fences, maximal entity neighborhood, pending impulses and no early witness without increasing packet rate. Verify fair visits, per-session exhaustion and neighbor-session coverage. Dynamic quotas are recorded config/grant inputs and replay must reproduce them. Degrade optional forensic retention first, then expensive heuristic windows, then deterministic work to UNCERTAIN_WORK_QUOTA, then alert/isolate AC admission or configured resource disconnect. Cost is never a cheat violation.

## Proposed engineering targets, not current guarantees

Under a declared supported workload: p99 queue age <25 ms, p99 capture-to-commit <50 ms, incremental p95 main-thread MSPT <=2 ms, and no retained-heap growth after stable churn/GC. CPU must retain operational headroom; provisional goal <=70% of available worker CPU over the measurement window. Establish per-kernel latency/allocation baselines after implementation; avoid invented nanosecond promises now.

Acceptance additionally requires zero unmarked gaps, zero unsafe Bukkit access, no automated effects from incomplete validation and no resource-bound violations. A fast run dominated by UNCERTAIN is a failed coverage claim. Publish player-hours, adverse-event/false-positive counts and confidence intervals. Zero observed false positives is not proof of zero risk; for independent trials, explain assumptions before using statistical upper bounds. Correlated per-player events require appropriate grouping.
