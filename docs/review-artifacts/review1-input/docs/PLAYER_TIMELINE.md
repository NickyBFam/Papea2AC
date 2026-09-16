# Player timeline

## Decision L1: event order and causal partial order

**DECISION:** Preserve captureSeq as the deterministic processing order and separately retain causal/application constraints. Server ticks, estimated client ticks and arrival times are different fields. An event is never reassigned to a tick merely to make simulation match.

**WHY:** Serialization is observable; exact client application time often is not.

**ALTERNATIVES:** Sort by timestamps; assume one movement packet per tick; subtract ping from server history.

**TRADEOFFS:** A partial order creates several admissible state views. Bounding them safely requires UNCERTAIN when coverage cannot be completed.

## Record and indexing

TimelineRecord contains captureSeq, committedSeq, eventKind, monotonic timestamp delta, optional server tick, logical movement-step range, wire ordinal where applicable, causal parent IDs, component revision roots, knowledge frontier, health generation and uncertainty reasons. Session/connection/world/validation epochs scope every index. Sequence is a 64-bit counter; wrap is handled as an explicit new session before reuse, not signed comparison tricks.

Fixed-capacity ring stores normalized inputs and periodic checkpoints, with per-kind indexes for attacks, ACKs, teleports, impulses and world transitions. A checkpoint is taken initially and every 128 committed records or at a lifecycle boundary, subject to byte budget. A complete replay range begins at a checkpoint; eviction of its required prefix also evicts dependent ranges or marks them incomplete.

Query: `(session, epochs, sequenceRange, requiredCapabilities)` -> immutable TimelineSlice plus coverage intervals. Missing older/newer records return HISTORY_MISSING. No fallback to present-day player/entity state. Earliest/latest possible application positions derive from constraints, not from choosing whichever historic position makes an attack pass.

## Example: uncertain update application

| Sequence | Observation | Consequence |
|---|---|---|
| 100 | Outbound impulse V, wire ordinal 40 | Record possible future velocity application |
| 101 | Outbound marker M after V | Link M to preceding wire frontier |
| 102 | Inbound movement A | Evaluate eligible before/after-application branches |
| 103 | Inbound ACK M | Resolve only the guarantees of M's version profile |
| 104 | Inbound movement B | Use updated knowledge, retaining allowed tick-boundary ambiguity |

ACK M is not permission to mutate the committed interpretation of A silently. A deferred validation may resolve later against its frozen slice and emit a Resolution record referring to the original evidence ID. It cannot double-count an earlier provisional observation. If resolution expires or prerequisite data is evicted, final status remains UNCERTAIN.

World epoch increments on dimension/respawn baseline change. Validation epoch increments on continuity loss/resync; connection epoch increments on reconnect. Network ACK IDs may be reused by a protocol only when disambiguated by epoch and outstanding ledger; ambiguity blocks promotion.

The timeline cannot repair uncaptured outbound events. Gap records terminate the continuous segment. Recover only through a coherent baseline, required acknowledgements and explicit Resume, never by starting a new ring at the next ordinary movement.
