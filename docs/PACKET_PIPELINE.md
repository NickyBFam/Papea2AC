# Packet pipeline

## Decision P1: bounded observation and authoritative outcome coverage

**DECISION:** Use a narrow Paper adapter behind the packet SPI, with distinct network observation and server-dispatch outcome ports. Observation alone never certifies acceptance. The initial direct Netty hook supplies capture; an enforcement-qualified profile additionally requires a verified correlated outcome provider at the selected Paper packet-dispatch/application boundary. No library is assumed to provide this automatically. Phase 1 may deliver an explicitly observe-only profile if no such provider exists.

**WHY:** Inbound callback order, outbound transformation, cancellation, bundling and write completion are correctness requirements; hiding them behind a generic asynchronous listener is unsafe.

**ALTERNATIVES:** ProtocolLib/PacketEvents behind the same SPI; event-only Bukkit integration; direct NMS in checks.

**TRADEOFFS:** Post-dispatch coverage is harder than ordering callbacks. A server event alone cannot establish packet-to-outcome correspondence. Unsupported profiles remain observe-only; no permanently certified frontier is advanced by assuming absence of cancellation. Adapter replacement or deeper instrumentation requires a reviewed profile change.

## Event contract

An ObservationEnvelope contains schemaVersion, sessionId, connectionEpoch, worldEpoch, captureSeq, direction, sourceKind, sourceEventId, optional causalParentSeq, monotonicCaptureNanos, optional serverTickObservation, protocolState, clientProtocol, rulesetHash, payload and coverage flags. Payload variants are explicit tagged values, not unchecked maps. No borrowed ByteBuf, packet handle, Player, World or Location crosses the boundary. Finite numeric/range validation occurs before state use. Raw yaw may wrap; position, angle and semantic limits come from the version profile, not arbitrary clamps.

Envelope and normalized packet variants belong to protocol using model identities; archival forms belong to replay-schema. Each inbound attempt adds attemptId, original payload digest, observed semantic digest, ingressOrdinal and boundaryGeneration. ServerOutcome references that ID, final semantic payload/digest, processingOrdinal, server revision and APPLIED / TRANSFORMED_APPLIED / REJECTED / FAILED / UNKNOWN. `APPLIED` means dispatch completed according to the profile, not that the client was honest. No borrowed ByteBuf, Player or mutable packet object may escape.

## Observation matrix (semantic families, mapped to exact target packet IDs in Phase 1)

| Direction | Mandatory family | State/capability fed |
|---|---|---|
| In | Position/rotation/ground movement variants | Claimed/provisional movement and rotation; field-presence retained |
| In | Input, sprint/crouch/action, client tick-end | Reconstruction and TickState; availability version-gated |
| In | Attack, entity interaction, swing/animation | Combat/interaction; physical clicks not inferred one-to-one |
| In | Use item, use-on-block, target face/cursor, dig/placement sequence | Interaction semantics, prediction overlay |
| In | Held-item change, inventory/window actions | Attributes, use-item/slowdown, inventory |
| In | Vehicle move, steer/paddle, mount controls | Mode and vehicle motion; loss invalidates prior walking frontier |
| In | Marker/keepalive response, teleport confirm | Receipt/application constraints and dedicated teleport ledger |
| In | Login/configuration/play lifecycle | Session/protocol epochs and registries |
| Out | Position correction, vehicle correction/move | Teleport and vehicle epochs |
| Out | Entity velocity, explosion motion payload | ExternalMotionInfluence; explosion vector captured independently of source mob |
| Out | Attributes/effects/equipment/abilities | Physics, input/interaction reach and mode |
| Out | Spawn/move/metadata/destroy, passengers/mount | Observer history, push/colliders and control relationships |
| Out | Set camera, respawn, game event/gamemode, set health/death | Controlled entity, lifecycle and locomotion; no silent mode carry-over |
| Out | Chunk baseline/unload, section/block/block-entity/event, moving border | Observer world, dynamic blocks, physical properties |
| Out | Window/slot/held-item state, action acknowledgements | Inventory/use/placement reconciliation |
| Out | Tick-rate, tick-freeze/step state and configuration changes | Timer and inferred-step semantics |
| Out | Markers, protocol transitions, bundles | Wire frontier, epochs and atomicity profile |
| Server outcome | Correlated dispatch acceptance/rejection/transformation, failure and authoritative revision | Certification, not raw capture |
| Server observation | Tick/stall/rate state, plugin effects, lifecycle, platform action results | Server-only knowledge and reconciliation |

Every target-profile packet family is explicitly classified affecting/irrelevant/unsupported. Adapter coverage is checked against that inventory at startup; absence of any affecting entry disables dependent capability with MissingObservationFamily. The table is a semantic requirements list, not a claim of exact packet names/IDs for all versions. Firework/riptide/projectile/fishing effects are resolved from version-specific event/entity/item/velocity paths; missing any required path disables that mode. Bundles have a profile-defined grouping/application entry and dedicated fixtures. Unknown classes never silently use defaults.

## Hook placement and integrity

Netty inbound flows toward the server handler; outbound traverses handlers in the opposite direction. Inbound pre-process tap is immediately before the configured server packet-dispatch handler after decoding and covered library transforms. Outbound semantic tap is immediately on the packet-object input side of the encoder after all declared transforms in outbound traversal order. The provider must correlate this representation with serialized bytes/write success; if a serializer or later handler can change semantics beyond coverage, mark FinalRepresentationUncovered. Neither tap alone observes future main-thread cancellation. These are final only relative to the named, verified boundaries. [Netty pipeline contract](https://netty.io/4.1/api/io/netty/channel/ChannelPipeline.html).

AdapterBoundaryProbe checks decoder/encoder/dispatch identities, handler order/classes and classloader signatures, protocol state, known transformer/provider versions, cancellation/mutation coverage and mapping fingerprints. Record boundaryGeneration and topology fingerprint. Check at installation, each protocol transition, and before accepting each relevant callback; a one-second audit additionally detects idle changes. An unknown handler inside either protected segment yields ForeignHandlerInsideBoundary. A periodic snapshot cannot prove that a handler was not inserted and removed between checks; only a mediated integrity/outcome provider covering that span permits certification. Any unobservable pipeline change leaves outcome coverage UNKNOWN regardless of a later matching fingerprint. No initial profile may advertise certification based on periodic checks alone.

## Decision P2: journal first, certify after correlated outcome

**DECISION:** Process all captured events into an immutable journal and bounded provisional transitions; promote an inbound transition only after its authoritative outcome and all predecessor dependencies resolve. **WHY:** Downstream cancellation must not become trusted state. **ALTERNATIVES:** Eager commit with rollback of emitted punishments; listener priority as proof of server acceptance. **TRADEOFFS:** Additional latency/journal memory and potentially observation-only operation where outcome instrumentation is unavailable.

State flow: CAPTURED -> PREPROCESS_OBSERVED -> OUTCOME_PENDING -> APPLIED / TRANSFORMED_APPLIED / REJECTED / FAILED / UNKNOWN. Outcomes are normalized into the same sequencer as later events; receipt order is never changed. Maintain separate processedCaptureSeq and certifiedIngressOrdinal. Waiting for an outcome never blocks consuming the outcome itself. Certification walks the resolved dependency prefix in ingress/processing order, recording a FrontierPromotion at the outcome's capture sequence; it does not rewrite old records.

The client-claimed/provisional path is distinct from the accepted server projection and certified supported frontier. A raw valid-looking movement may be evaluated provisionally but cannot update a safe anchor, produce actionable exclusion or certify combat/ACK effects. REJECTED discards its speculative effects and invalidates descendants that depended on them; the claim remains diagnostic because a client may already have predicted motion. TRANSFORMED_APPLIED recomputes from the final payload, records original and final digests and does not equate modified server coordinates with the client's original prediction. FAILED/UNKNOWN, mismatched IDs, contradictory late outcomes and uncertified predecessors force UNCERTAIN_PIPELINE_OUTCOME and dependent resynchronization. An ACK rejected downstream cannot advance a certified ledger.

After rejection/transformation, resume certification only with a correlated server baseline and reconciled client frontier, not by skipping the record and trusting its successor. Journal capacity: 256 pending attempts or 256 KiB, timeout 500 ms; overflow/expiry discards provisional certification eligibility for the span. Longer main-thread stalls produce coverage loss, not violations. Late outcomes never resurrect expired evidence. Exact mutable object identity is insufficient for one-to-many replacements: provider supplies explicit replacement children and causal IDs or marks UNKNOWN.

## Ordered processing

1. Capture at the stated pre-process/encoder boundaries, without assuming acceptance. Non-event-loop observations enter the bounded bridge. Never assume outbound writes originate on one thread.
2. Adapter performs bounded validation/copying into owned bytes or small immutable values. It records cancellation and transform provenance. State-changing packets cancelled before final send do not become client-visible deltas.
3. The event-loop sequencer assigns captureSeq and publishes to the connection's FIFO. The assignment and offer are one event-loop operation; no producer reserves a sequence and stalls while later producers overtake it.
4. Outbound events begin as WriteAttempt, with wire order and dependency IDs. Success/failure becomes a later WriteOutcome event. A client ACK may reach the reducer before a write callback; it is retained by ID pending resolution, not discarded. Failed or ambiguous writes invalidate related assumptions. Write success only proves handoff, not client application.
5. The actor processes journal events in captureSeq order. Bulk decode may yield, but later journal records cannot overtake its normalization. Pending server outcomes do not block journal consumption; they block only dependent frontier promotion.
6. Reducer commits journal/diagnostic changes, then promotes only eligible resolved transitions under P2. Certified model revisions, evidence eligibility and replay explicitly identify the promoting outcome. Failure quarantines affected certification.
7. Platform side effects and I/O receive bounded requests. Their outcomes return as future observations with causal IDs. Neither callback mutates player state.

Logical capture order is the observed serialization of inbound/outbound/local submissions, not a claim of exact client tick order or total physical order across machines. Packet timestamps are metadata, never a reorder key. Per-direction TCP order is preserved; causally unrelated main-thread and network events may race and must retain uncertainty. Replay stores the actual serialization.

## Boundary conditions

Bundles are normalized as one envelope with ordered children sharing an atomic application group where required by the version. Fragmented TCP frames are decoded by the transport; one frame/read is not one movement/tick. Compression/encryption state must be observed at a compatible decoded boundary. Chunk copying has count/byte caps and uses a separate bounded bulk pool; a queue contains bounded handles, not unlimited retained network buffers.

Reserved control capacity supports gap, close and health messages. Queue/bulk exhaustion latches a transport health barrier before any later decision can be actionable. Do not drop only a movement packet and continue simulation as if contiguous. See [Threading](THREADING_MODEL.md) for the exact recovery protocol.

Protocol safety rejects malformed sizes before allocation. Malformed framing may disconnect under an availability/protocol policy without a cheat violation. Normal valid traffic must remain unaffected in Phase 1 observation mode. No asynchronous verdict retroactively cancels an already-delivered attack; mitigation has separate semantics.

## Phase 1 demonstrations

Show inbound order, cross-thread writes, failed writes, bundles, ACK-before-write-callback, reconnect and disable. Inject foreign handlers inside both protected segments; cancel an inbound movement after capture and show no certified-frontier or anchor advance; mutate/replace it and show final-payload reconciliation; throw downstream; delay/duplicate/drop outcomes; insert and remove a handler between periodic audits. Verify named uncovered status if the provider cannot prove the outcome. A passed callback or normal handler return is not APPLIED without the profile's server-dispatch completion semantics. Observe-only capture is a legitimate Phase 1 deliverable, not permission to enable detection later.
