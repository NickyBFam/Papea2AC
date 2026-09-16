# World model

## Decision W1: observer-specific state

**DECISION:** Maintain separate authoritative server observations and the set of states a particular client could know. Validation uses observer-specific outbound block/entity history plus versioned local prediction rules, never a worker read of the live Bukkit world.

**WHY:** The server's current block or target position is not necessarily the observer's current view.

**ALTERNATIVES:** Synchronous Bukkit queries for every check; global present-state cache; ping-shifted snapshots.

**TRADEOFFS:** Per-observer deltas and histories cost memory. Immutable shared content reduces duplication, but divergent views stay distinct.

## Data model and sources

WorldKey = dimension identity + registry/data-pack digest + world epoch. BlockStateKey = canonical namespaced type + canonical property set + ruleset digest; runtime palette IDs are not persisted as universal identifiers. ChunkSectionSnapshot contains immutable palette/data and completeness flags. SectionRevision adds observer send order, application constraints and content hash. EntityState contains entity network ID **and incarnation**, type, position/rotation, pose, dimensions, attributes, interpolation state, vehicle relation and visibility lifecycle.

Covered outbound representations plus write/application constraints create visibility deltas. Main-thread snapshots provide server-only facts, not client visibility. Fake blocks, anti-xray substitutions and per-player entity hiding require covered transformation boundaries; missing coverage means unknown regions/entities. A main-thread snapshot must not overwrite an observer's fake block view.

Query contract: `WorldView(observer, epochs, knowledgeFrontier, applicationHypothesis)` returns a read-only resolver. `blockAt`, `fluidAt`, `entityAt` return KNOWN(value, provenance), UNKNOWN(reason), or AMBIGUOUS(capped alternatives). Unknown is neither air nor solid. Queries expose whether all touched coordinates have coverage; validators propagate it.

## Revisions and prediction

Record chunk baseline before deltas; a delta without baseline is incomplete. Store unload tombstones so a stale shared section cannot reappear. Teleport, respawn, dimension, data-pack or registry changes invalidate incompatible handles. Neighbor-sensitive shapes request all required neighbor states from the same coherent hypothesis.

Client placement/break prediction is a separate, bounded overlay tied to interaction sequence and window/state acknowledgements where available. Server rejection/replacement resolves it; timeout becomes uncertainty. A predicted change never permanently mutates the authoritative baseline. Pistons, moving blocks, block entities and fluids need explicit temporal rules; unsupported dynamic behavior remains unknown for affected queries.

Entity histories come from packets **sent to the attacker**, including relative movement quantization, teleports, metadata and destroy/respawn. Do not read the target actor's most recent position as a substitute. Sharing immutable geometry is allowed; sharing mutable observer interpolation state is not.

## Storage and eviction

Private observer data uses the session tier: 768 KiB floor or 2 MiB qualification target, optional expansion only from admitted global spare bytes. Shared content has a global pool (96 MiB reference profile). Up to 4096 section handles and 256 entity summaries are ceilings, not preallocations. Deep history prioritizes at most 32 nearby entities, 96 revisions or three target seconds subject to bytes. PERFORMANCE governs all quotas. Sparse representation avoids eager volume allocation; deduplication confirms hashes against canonical bytes.

Evict obsolete revisions and far regions first. Current collision neighborhood and pending combat slices are preferred, but pinning has byte/age quotas. When quota cannot retain required coverage, cancel affected queries as UNCERTAIN and release pins. Never synchronously load chunks to satisfy validation. One pathological client must not evict all other clients' active views; account reservations and per-session fairness are required.

## Acceptance examples

Tests must distinguish absent data from air, delayed door change, cancelled placement, fake barrier, unloaded section, border-crossing collision, metadata-driven target size, entity ID reuse and dimension transition. The same canonical snapshot must resolve identically without Paper in an offline replay.

## Decision W2: regional coverage and baseline recovery

**DECISION:** Pre-existing sessions enter OBSERVE_ONLY_BASELINE_MISSING. Lazily reconstruct newly observed outbound baselines with region/entity coverage epochs. Gap/eviction marks dependent coverage UNKNOWN. Queue recovery never restores missing world data. No automatic chunk retracking is assumed. **WHY:** Already-known chunks may never be resent. **ALTERNATIVES:** Qualified forced resend; shared server snapshot plus a complete divergence overlay. **TRADEOFFS:** Some regions can stay unknown for the rest of the session; publish that limitation. Reconnect normally allows fresh capture but is not silently forced.

Coverage vector: OrderingHealth, PipelineOutcomeCoverage, WorldCoverage(regions/revisions), EntityCoverage(spatial neighborhood/incarnations), ApplicationCoverage and PhysicsCapabilityCoverage. Queries require the conjunction of their dependencies. Existing sessions can acquire covered regions incrementally, but local certification also needs a reconciled supported frontier. A delta/current server snapshot cannot restore an evicted observer baseline. Only a newly observed complete baseline or qualified equivalent can do that. Fake-block precedence remains intact. A resync request may be refused/no-op; only observed outcomes count.

Divergence comparison (arithmetic estimates, not measurements): at view distance 10/16, (2d+1)^2 gives 441/1089 columns. Assuming 24 sections/column at 4 KiB/section, full materialization is 41.34/102.09 MiB per observer before sharing. At 1% divergence an overlay is roughly.41/1.02 MiB plus visibility/history/shared baseline; 100% divergence removes that saving. These are illustrative sizes, not evidence that a current snapshot establishes missing history. Choose captured lazy reconstruction because absent fake-block divergence cannot be proven after mid-session activation. Phase 3 benchmarks both representations on fully captured data at distance 10/16, including memory, event-loop copy cost and worst-case divergence. Bootstrap changes require a new reviewed visibility guarantee.

## Decision W3: physical properties and entity neighborhood

**DECISION:** World owns BlockBehaviorQuery and NearbyMotionEntities; version provides pure definitions, physics applies them, collision owns geometry. **WHY:** Occupancy and physical response differ. **ALTERNATIVES:** Infer friction from shape; simulate all entities. **TRADEOFFS:** Incomplete property/neighborhood coverage must remain explicit.

BlockBehaviorQuery(runtime state ID, coherent revision, entity context, sample kind) returns KNOWN/AMBIGUOUS/UNKNOWN plus typed friction, speed/jump factor, bounce, inside drag, fluid flow/column force, climb/support and special acceleration responses. Sampling of support faces, fluid volume and inside-body cells is version-defined; inside sampling is separately bounded to 512 cells/step and charged to total work. Omitted required cells mean incomplete behavior. Moving border and piston displacement have temporal profiles. Unknown properties are not normal friction or zero flow.

NearbyMotionEntities queries a spatial index over the conservative swept player envelope expanded by the profile's maximum push/contact radius. Entries contain incarnation, type, pose/dimensions, eligible team/collision rules, vehicle relationships and position intervals. Resolve at most 32 relevant entities/step with 2048 push/collider operations charged to movement work. Overflow, missing spawn history, uncovered visibility or unbounded region yields UNCERTAIN_ENTITY_NEIGHBORHOOD. Omission from a capped list never proves absence. Include players, mobs, boats, minecarts and other version-defined push/solid entities, including shulkers where applicable.

Do not assume all pushing is client-predicted: profiles distinguish server-only, predicted and mixed effects. Server-only contributions use captured resultant velocity/corrections; predicted contributions use observer history. Common source IDs prevent double application. Unknown execution side means incomplete physics. Qualification covers 5/15/30-entity crowds and overflow; no global entity simulation is required.

BlockStateId is an interned integer for hot lookups. RegistryDictionary maps to canonical namespaced/properties/ruleset state and is recorded in replay. IDs may vary by run; canonical digests translate them. Missing mappings yield UNKNOWN. World's local ReplayMapper exports canonical data and restores only after dictionary/closure validation.
