# World model

## Decision W1: observer-specific state

**DECISION:** Maintain separate authoritative server observations and the set of states a particular client could know. Validation uses observer-specific outbound block/entity history plus versioned local prediction rules, never a worker read of the live Bukkit world.

**WHY:** The server's current block or target position is not necessarily the observer's current view.

**ALTERNATIVES:** Synchronous Bukkit queries for every check; global present-state cache; ping-shifted snapshots.

**TRADEOFFS:** Per-observer deltas and histories cost memory. Immutable shared content reduces duplication, but divergent views stay distinct.

## Data model and sources

WorldKey = dimension identity + registry/data-pack digest + world epoch. BlockStateKey = canonical namespaced type + canonical property set + ruleset digest; runtime palette IDs are not persisted as universal identifiers. ChunkSectionSnapshot contains immutable palette/data and completeness flags. SectionRevision adds observer send order, application constraints and content hash. EntityState contains entity network ID **and incarnation**, type, position/rotation, pose, dimensions, attributes, interpolation state, vehicle relation and visibility lifecycle.

Outbound chunk load/unload, block/section changes and entity tracking packets create visibility deltas. Main-thread snapshots provide server-only facts and initial capture assistance; they do not establish client visibility. Fake blocks, anti-xray substitutions and per-player entity hiding must be observed after transformation. If final-wire representations cannot be captured, related regions/entities become unknown. A main-thread world snapshot must not overwrite an observer's fake block view.

Query contract: `WorldView(observer, epochs, knowledgeFrontier, applicationHypothesis)` returns a read-only resolver. `blockAt`, `fluidAt`, `entityAt` return KNOWN(value, provenance), UNKNOWN(reason), or AMBIGUOUS(capped alternatives). Unknown is neither air nor solid. Queries expose whether all touched coordinates have coverage; validators propagate it.

## Revisions and prediction

Record chunk baseline before deltas; a delta without baseline is incomplete. Store unload tombstones so a stale shared section cannot reappear. Teleport, respawn, dimension, data-pack or registry changes invalidate incompatible handles. Neighbor-sensitive shapes request all required neighbor states from the same coherent hypothesis.

Client placement/break prediction is a separate, bounded overlay tied to interaction sequence and window/state acknowledgements where available. Server rejection/replacement resolves it; timeout becomes uncertainty. A predicted change never permanently mutates the authoritative baseline. Pistons, moving blocks, block entities and fluids need explicit temporal rules; unsupported dynamic behavior remains unknown for affected queries.

Entity histories come from packets **sent to the attacker**, including relative movement quantization, teleports, metadata and destroy/respawn. Do not read the target actor's most recent position as a substitute. Sharing immutable geometry is allowed; sharing mutable observer interpolation state is not.

## Storage and eviction

Per-player private overlay/entity history has an 8 MiB cap. A global immutable content store begins at 512 MiB, with per-player reference admission and a bounded 4096 section-reference cap per observer. Sparse air/unknown representations avoid eager volume allocation. Content hash equality must be confirmed by canonical bytes before deduplication.

Evict obsolete revisions and far regions first. Current collision neighborhood and pending combat slices are preferred, but pinning has byte/age quotas. When quota cannot retain required coverage, cancel affected queries as UNCERTAIN and release pins. Never synchronously load chunks to satisfy validation. One pathological client must not evict all other clients' active views; account reservations and per-session fairness are required.

## Acceptance examples

Tests must distinguish absent data from air, delayed door change, cancelled placement, fake barrier, unloaded section, border-crossing collision, metadata-driven target size, entity ID reuse and dimension transition. The same canonical snapshot must resolve identically without Paper in an offline replay.
