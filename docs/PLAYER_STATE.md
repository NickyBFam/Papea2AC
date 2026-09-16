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
| MovementState | Capture/provisional result, correlated server outcome, certified promotion | Client claim, provisional path, accepted server projection and certified supported set separately | 256 pending attempts/256 KiB/500 ms; 256 solver nodes; no anchor promotion without outcome |
| RotationState | Rotation-bearing observations only | Raw and normalized angles, field-presence, source sequence | Current value and bounded feature window |
| CombatState | Attack, interaction, target eligibility changes | Attack ID, immutable context refs, provisional outcomes | 64 pending attacks; overflow yields uncertain outcomes |
| CollisionState | Simulation/contact evaluation | Contact summary and query/world revision handles | Current summary; per-event scratch recycled; no eternal shape list |
| WorldState | Covered outbound deltas, baseline acquisition or eviction | Region/entity coverage epochs separately from ordering health | 768 KiB floor /2 MiB qualified target shared with EntityTracker; PERFORMANCE governs |
| VelocityState / ExternalMotionState | Captured force outcome, application inference, consumption/reset | Influence ID, mechanism, source/side, vector/interval and operation order | 64 diagnostic records; eight active unresolved influences; overflow uncertain |
| TeleportState | Intent, execution, outbound correction, confirmation | Teleport generation/ID, target, relative flags, state | One active correction, eight diagnostic predecessors |
| InventoryState | Window, slot/use-item changes and confirmations | Window epoch/state ID, relevant items/components, using-item alternatives | Protocol-bounded active window; 256 pending deltas/256 KiB cap |
| LatencyState | Marker write/outcome/accepted ACK, timeout | Outstanding IDs, causal/application constraints, RTT | 256 markers/samples; ten-second diagnostics only; query horizon is minimum dependency |
| EntityTracker | Observer spawn/move/metadata/destroy/passengers | Incarnation, push/collider eligibility, historical pose and spatial coverage | 256 summaries; 32 deep histories, 96 revisions,three-second target under world bytes |
| TickState | Protocol tick markers, movement grouping, server tick observation | Distinct server, logical simulation and arrival clocks | Fixed counters and bounded timer window |
| ViolationState | Deduplicated evidence and logical-time decay | Scores by finite category, correlation ledger, policy version | 128 correlation groups; 256 evidence summaries |
| Timeline | Every processed capture/outcome/control record | Checkpoints, certification promotions and coverage refs | 4096 records /512 KiB floor or 1 MiB target /three target seconds, whichever first |

Caps deliberately sacrifice coverage before memory safety. A target excluded by the entity cap is UNKNOWN, not nonexistent. Client-controlled IDs cannot create unlimited map keys. Histories expire by count, bytes and recorded time. Outstanding references count against both owning and shared-store budgets; no hidden pinning exemption.

## Commit and snapshot semantics

Process capture N -> update journal/provisional or outcome state -> validate invariants -> install roots -> append processing commit N -> conditionally promote resolved certified ingress prefix -> publish. Processing commit is not certification of the packet claim. Packet P2 governs rejection/transformation and dependent invalidation. No partially updated state is a trusted checkpoint. Player-local snapshot/restore mappers use replay-schema DTOs; internal IDs/geometry use model, not public api.

Administrative snapshots are published at a bounded cadence, initially once per server tick observation, with sequence/age. Readers can report staleness; they cannot demand a synchronous actor read. Retained diagnostic snapshots have leases and byte budgets. World changes, death/respawn and reconnect are explicit epoch transitions; stale simulation candidates and safe anchors cannot cross them.

CoverageSummary includes each coverage dimension, achieved query horizons, VALID/INVALID/UNCERTAIN counts by fixed capability, work-exhaustion fraction and effective memory tier over a recorded 30-second window. Below 50% definite supported movement results for 30 seconds (minimum 100 observations) emits CoverageDegraded, zero violation weight. Pending attack/evidence/correlation overflow shares one DependencyRetentionLost event: cancel dependent provisional results, preserve a bounded diagnostic counter and forbid punitive inference from missing roots.

## Decision S2: external motion taxonomy

**DECISION:** ExternalMotionInfluence is an internal model value; player owns its ledger, version defines operation/application semantics, physics applies it and protocol/world supply sources. **WHY:** Explosion is only one source of external motion. **ALTERNATIVES:** Entity-velocity packet only; special-case creepers. **TRADEOFFS:** Mode-specific observation paths must be qualified; unknown influences lower completeness.

| Mechanism | Sources | Application rule |
|---|---|---|
| VelocityDelta / VelocityReplace | Entity knockback, explosion/TNT/creeper/end crystal, projectile, fishing rod, plugin velocity | Capture final communicated result; version decides add/replace/order, never infer vector solely from source label |
| ModeAcceleration | Firework boost, riptide and supported special movement | Recorded mode/item/entity/effect inputs drive mode-specific transfer, not arbitrary impulse addition |
| KinematicDisplacement | Piston/moving blocks, moving support/border | Coupled temporal collision/displacement; not equivalent to velocity |
| ContactPush | Players, mobs, boats, minecarts, other eligible entities | Side-aware neighborhood profile; deduplicate server-result packets |
| DeclaredUnknownMotion | Custom plugin movement without a qualified packet/rule path | Explicit unsupported scope/expiry; cannot support INVALID |

Every influence includes source/correlation ID, execution side, causal wire/server revision, vector or bounded state, operation, possible application interval, mode and coverage. Explosion block changes are recorded separately from its motion component. A projectile/fishing event that already caused a captured velocity must not add a second guessed impulse. Unknown side/rule/source completeness yields UNCERTAIN_EXTERNAL_FORCE. Regression fixtures cover each mechanism and interleavings with movement/teleports.
