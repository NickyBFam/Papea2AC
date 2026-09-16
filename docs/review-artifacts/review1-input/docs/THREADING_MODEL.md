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

The actor commits one event transition at a time. Expensive bulk decoding can yield a resumable continuation; no later event is processed until it completes or the actor invalidates coverage. Simulation work is bounded by operations, not an unrecorded elapsed-time race. A shard runs at most 32 small events per actor visit or yields at a safe continuation boundary. The 1 ms scheduling watchdog is diagnostic; if emergency wall-time cancellation is necessary, record a BudgetAbort input and return UNCERTAIN.

## Backpressure and overload

Initial budgets are centralized in [Performance](PERFORMANCE.md). Count and byte bounds apply at ingress, bridge, bulk pool, shared store, replay, actor and platform effect queues. Per-connection quotas and global admission reservations are both required: multiplying per-player limits is not a sufficient global bound.

On failed ingress offer: the sequencer increments transportGeneration and atomically marks the connection unhealthy, recording firstMissingSeq in an out-of-band fixed-size latch. It stops accepting validation continuity for that generation. A reserved control mailbox carries Gap; if even that is unavailable the latch still prevents new effect admission. The actor checks the latch before each event commit; platform action execution acquires the generation-checked admission described in [Mitigation](MITIGATION.md). In-flight evidence from an older generation may be retained diagnostically but cannot admit new actions. Failure of a main-thread bridge offer uses the same generation barrier even before a wire sequence can be assigned; reason is LostServerObservation.

Recovery transitions HEALTHY -> GAP/OVERLOADED -> RESYNC_PENDING -> HEALTHY(new validation epoch). Clear queued dependent work, discard pending effects, rebuild a coherent baseline through captured outbound resynchronization and required acknowledgements, then record Resume at a sequenced boundary. Actual connection epoch changes only on reconnect; validation epoch changes on recovery. A timeout or repeated overload may disconnect for resource protection under explicit configuration; it does not ban or accuse the player. No silent packet sampling, infinite buffers or blanket high-ping exemptions.

Replay/storage overload may drop an entire capture with an explicit incomplete marker; if the current enforcement policy requires a complete replay, actionable evidence becomes unavailable. Metrics/log queues coalesce by bounded keys. Saturated main-thread action queues reject intents with outcomes, never block the shard.

## Exceptions, disconnect and shutdown

Malformed client input returns a typed rejection. An engine exception records bounded context, marks the affected actor/ruleset unhealthy, and quarantines enforcement until resync or disconnect. Continue processing other actors. An unexpected shard failure quarantines all assigned actors; restarting a thread does not recover trust in their partially updated state. JVM-fatal conditions are not swallowed.

Disconnect closes ingress, tombstones the session, cancels pending intents by epoch, releases world/replay references and publishes a final summary. Late callbacks with old epochs are ignored but counted. Reconnection never reuses mutable components or ACK IDs from the previous session.

Disable first atomically prevents new effects and admissions. Main thread detaches platform listeners and requests event-loop hook removal without waiting. Workers drain control/close messages under a five-second background deadline; I/O flush has a bounded byte/deadline allowance. After deadline, abandon incomplete capture with a marker and release references. No unbounded join or disk flush in onDisable. Completion callbacks must not invoke a disabled plugin. Hot reload is unsupported; lifecycle tests must still prove hook removal and no leaked plugin classloader references.

Paper documents that world access is generally unsafe asynchronously and that synchronous work affects server ticks. This design confines such access to the platform main thread. [Paper scheduling reference](https://docs.papermc.io/paper/dev/scheduler/).
