# Player state

## Decision S1: component state, immutable projections

**DECISION:** PlayerSession is an actor-owned directory of small components, not a globally mutable PlayerData object. Only its actor reducer may mutate components. Engines receive immutable projections and return transition proposals; core commits them as one event transition.

**WHY:** Ownership must be mechanically enforceable and snapshots must have coherent revisions.

**ALTERNATIVES:** Shared mutable PlayerData; locks per component; clone every field for every event.

**TRADEOFFS:** Projection schemas need maintenance. Structural sharing and immutable revision handles avoid cloning the entire world.

## Component contracts

Every component below is owned and mutated **only by the session actor**. Network/main/I/O threads submit observations, never setters. Mutation happens during the indicated ordered transition. Snapshots carry session, connection/world/validation epochs and committed sequence. Bounds below reference [Performance](PERFORMANCE.md); no field may retain an unbounded collection.

| Component | Changes on | Snapshot contents | Bound/lifetime |
|---|---|---|---|
| ConnectionState | Admission, protocol transition, disconnect, health barrier | Protocol/capabilities, epochs, health, remote pseudonym | One record; no raw credentials/address in engine state |
| MovementState | Accepted movement observation and simulation result | Claimed and supported poses separately, velocity hypotheses, last trusted anchor | Current state plus capped candidate frontier; history in Timeline |
| RotationState | Rotation-bearing observations only | Raw and normalized angles, field-presence, source sequence | Current value and bounded feature window |
| CombatState | Attack, interaction, target eligibility changes | Attack ID, immutable context refs, provisional outcomes | 64 pending attacks; overflow yields uncertain outcomes |
| CollisionState | Simulation/contact evaluation | Contact summary and query/world revision handles | Current summary; per-event scratch recycled; no eternal shape list |
| WorldState | Outbound visibility deltas and resync | Dimension/registry IDs, observer view handle, coverage map | 8 MiB private world/history budget, plus global store admission |
| VelocityState | Sent impulse, ACK/application branch, consumption/reset | Impulse ID, type, source, vector, possible application interval | 64 pending impulses; timeout invalidates affected validation |
| TeleportState | Intent, execution, outbound correction, confirmation | Teleport generation/ID, target, relative flags, state | One active correction, eight diagnostic predecessors |
| InventoryState | Window, slot/use-item changes and confirmations | Window epoch/state ID, relevant items/components, using-item alternatives | Protocol-bounded active window; 256 pending deltas/256 KiB cap |
| LatencyState | Marker write/outcome/ACK, timeout | Outstanding IDs, causal frontiers, RTT diagnostics | 256 markers and 256 RTT samples; time horizon 10 seconds |
| EntityTracker | Observer's spawn/move/metadata/destroy traffic | Entity incarnation, pose history, visibility constraints | 256 tracked candidates, 64 revisions each, within world byte budget |
| TickState | Protocol tick markers, movement grouping, server tick observation | Distinct server, logical simulation and arrival clocks | Fixed counters and bounded timer window |
| ViolationState | Deduplicated evidence and logical-time decay | Scores by finite category, correlation ledger, policy version | 128 correlation groups; 256 evidence summaries |
| Timeline | Every committed event/control transition | Checkpoints, observations and revision refs | 4096 records or 2 MiB or 10 seconds, whichever first |

Caps deliberately sacrifice coverage before memory safety. A target excluded by the entity cap is UNKNOWN, not nonexistent. Client-controlled IDs cannot create unlimited map keys. Histories expire by count, bytes and recorded time. Outstanding references count against both owning and shared-store budgets; no hidden pinning exemption.

## Commit and snapshot semantics

Read projection at sequence N-1 -> compute bounded transition using event N -> validate component invariants -> install new roots -> append commit N -> publish read-only summary. If a transition fails, quarantine the actor before another actionable commit; partial data cannot become a trusted checkpoint. Checkpoints include component revisions and view hashes, not platform references. Exact restore is specified in [Replay](REPLAY_SYSTEM.md).

Administrative snapshots are published at a bounded cadence, initially once per server tick observation, with sequence/age. Readers can report staleness; they cannot demand a synchronous actor read. Retained diagnostic snapshots have leases and byte budgets. World changes, death/respawn and reconnect are explicit epoch transitions; stale simulation candidates and safe anchors cannot cross them.
