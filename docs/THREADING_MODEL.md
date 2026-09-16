# Threading model

## Decision T1: one logical writer per session

**DECISION:** A connection has one event-loop sequencer and a player actor owned by a fixed worker shard for its lifetime. A shard is one dedicated worker thread, with round-robin actor mailboxes. No per-packet executor task, per-player operating-system thread or live actor migration in the first release.

**WHY:** Fixed ownership makes ordering, mutation and replay auditable while bounding scheduler overhead.

**ALTERNATIVES:** Main-thread simulation; concurrent maps with locks; work-stealing actors with handoff tokens.

**TRADEOFFS:** A hot shard can reduce local throughput. Fair work quotas and admission limits precede actor migration; shard count is fixed at startup and benchmarked.

## Ownership table

| Context | May mutate | Must not do |
|---|---|---|
| Channel event loop | Connection ingress counters, wire sequence, bounded bridge, transport health latch | Simulation, world API calls, disk/network service I/O, waiting for actor |
| Player actor/shard | All player components, observer world history, timeline, violations | Bukkit access, blocking I/O, another player's state |
| Paper main thread | Actual game world/entities, lifecycle, platform action executor state | Mutate actor state, await worker result, write replay files |
| I/O executor | Owned replay buffers, storage queue, file lifecycle | Access live player components; decide violations |
| Admin/metrics readers | Their own copies/aggregates | Retain mutable player/world references |

Immutable ruleset tables are safely published at startup. Shared world content is immutable and reference-counted through a bounded store with thread-safe bookkeeping, not globally mutable player views. Resource admission/health latches are transport infrastructure, not gameplay components, and may use atomics. Mutable PlayerData is never exposed.

## Producer bridges and publication

Main-thread observations enter a bounded per-connection MPSC bridge. Producers offer first, then use a coalesced atomic drain-scheduled flag to schedule at most one event-loop drain task per connection. Drain races use clear-then-recheck so no wakeup is lost. Do not post one unbounded event-loop task per observation. Platform commands include command ID, source sequence and server tick; no wall-clock sorting is permitted.

Adapter observes inbound and outbound hooks on the channel event loop. Bridge messages get captureSeq only while draining there. Outbound messages and ACK markers requiring relative wire placement are executed as one ordered event-loop operation; bridge arrival alone does not establish packet application order. FIFO publication uses release/acquire synchronization. Actor consumers never see partially initialized values. Main-thread action requests use a separate bounded queue, drained without waiting. Main-thread outcomes follow the bridge above.

The actor commits one journal event at a time. Expensive decoding can yield; normalization never overtakes earlier captures. Outcome-pending attempts do not block consuming later outcome records: only the certified frontier waits (Packet P2). Simulation uses recorded operation budgets. A shard visit yields after 32 small events or 2048 weighted work units, whichever first, with resumable physics/geometry boundaries. One primitive box/cell/interval operation costs one unit; a concrete step also charges its inner operations. The 1 ms watchdog records emergency BudgetAbort rather than silently changing a result.

## Backpressure and overload

Initial budgets are centralized in [Performance](PERFORMANCE.md). Count and byte bounds apply at ingress, bridge, bulk pool, shared store, replay, actor and platform effect queues. Per-connection quotas and global admission reservations are both required: multiplying per-player limits is not a sufficient global bound.

On failed ingress offer: the sequencer increments transportGeneration and atomically marks the connection unhealthy, recording firstMissingSeq in an out-of-band fixed-size latch. It stops accepting validation continuity for that generation. A reserved control mailbox carries Gap; if even that is unavailable the latch still prevents new effect admission. The actor checks the latch before each event commit; platform action execution acquires the generation-checked admission described in [Mitigation](MITIGATION.md). In-flight evidence from an older generation may be retained diagnostically but cannot admit new actions. Failure of a main-thread bridge offer uses the same generation barrier even before a wire sequence can be assigned; reason is LostServerObservation.

Ordering health transitions HEALTHY -> GAP/OVERLOADED -> RESYNC_PENDING -> HEALTHY(new validation epoch) only after sequence/lifecycle continuity is re-established. WorldCoverage, EntityCoverage, PipelineOutcomeCoverage and ApplicationCoverage are separate dimensions and are not reset to complete by Resume. Missing observer baselines remain unknown until newly observed, as specified in WORLD_MODEL. Clear dependent provisional work and pending effects; rebuild only demonstrable state. Actual connection epoch changes only on reconnect. Papea2AC observes rather than proxies: overload is coverage loss, not TCP backpressure; Paper may continue processing traffic. No silent sampling, infinite buffers or blanket ping exemption.

Replay/storage overload may drop an entire capture with an explicit incomplete marker; if the current enforcement policy requires a complete replay, actionable evidence becomes unavailable. Metrics/log queues coalesce by bounded keys. Saturated main-thread action queues reject intents with outcomes, never block the shard.

## Exceptions, disconnect and shutdown

Malformed input returns a typed rejection. An engine exception quarantines the affected actor/ruleset; continue other actors. Shard lifecycle is RUNNING -> FAILED_FENCED -> DRAINING_LEASES -> RETIRED. Core's supervisor atomically fences its generation, removes it from admission rotation, invalidates effects and emits an alert. Allocation/pin tokens are registered in a supervisor-owned lease ledger before publication to an actor, so cleanup does not require the failed writer. After the worker is proven terminated and outstanding I/O lease users acknowledge release, the supervisor idempotently releases all reservations/pins and drops actor roots. It never frees memory still accessed by a live or hung thread. A hung worker stays fenced, reservations stay charged and admission shrinks; alert/restart is required. No unsafe forced thread stop or concurrent cleanup of live state.

Failed-shard sessions become explicit OBSERVE_ONLY_UNTIL_RECONNECT; their old mutable actors are never migrated/reused. Late callbacks are rejected by generation. New sessions use remaining healthy shards or are refused AC admission with a reason. Fault tests kill a cooperatively terminated worker mid-transition, verify lease return/no new assignments and alert; a separate hung-worker test verifies bounded charged retention rather than use-after-free. JVM-fatal failures are not swallowed.

## Sustained fairness and control

Initial worker count W=max(1,min(8,availableProcessors-2)); administrative override requires load evidence. Balanced admission aims for at most 128 certified sessions/shard, not a capacity guarantee. If W*128 is below configured capacity, excess sessions are observation-only unless a benchmarked profile explicitly permits more. Each session has 2 million weighted work units/second, burst 200000, in addition to per-event/visit bounds; recorded monotonic credits and grants make replay independent of host scheduling. Budget pressure first evicts optional forensic history, then stops expensive heuristic windows, then marks exhausted deterministic work UNCERTAIN_WORK_QUOTA and alerts on sustained loss. It never reduces certainty thresholds or silently lowers compulsory floors. Quota changes are sequenced config inputs. Cost-maximizing clients cannot consume another actor's visit allowance.

Global enforcement controls are handled by the platform main-thread admission arbiter described in MITIGATION. Core supervises configuration/health; actor notifications preserve causal logs. Emergency mode restriction takes effect for admission before per-session acknowledgements, without waiting for a stalled actor. This runtime control is supported even though plugin hot reload is not.

Disconnect closes ingress, tombstones the session, cancels pending intents by epoch, releases world/replay references and publishes a final summary. Late callbacks with old epochs are ignored but counted. Reconnection never reuses mutable components or ACK IDs from the previous session.

Disable first atomically prevents new effects and admissions. Main thread detaches platform listeners and requests event-loop hook removal without waiting. Workers drain control/close messages under a five-second background deadline; I/O flush has a bounded byte/deadline allowance. After deadline, abandon incomplete capture with a marker and release references. No unbounded join or disk flush in onDisable. Completion callbacks must not invoke a disabled plugin. Hot reload is unsupported; lifecycle tests must still prove hook removal and no leaked plugin classloader references.

Paper documents that world access is generally unsafe asynchronously and that synchronous work affects server ticks. This design confines such access to the platform main thread. [Paper scheduling reference](https://docs.papermc.io/paper/dev/scheduler/).
