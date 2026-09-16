# Packet pipeline

## Decision P1: transport boundary and final-wire semantics

**DECISION:** Use a narrow, version-pinned Paper/Netty adapter in platform-paper behind the packet module's transport SPI. Phase 1 must prove hook placement on the exact Paper build. No broad packet library dependency is selected by this specification. Normalized immutable events are the only packet data exposed to engines.

**WHY:** Inbound callback order, outbound transformation, cancellation, bundling and write completion are correctness requirements; hiding them behind a generic asynchronous listener is unsafe.

**ALTERNATIVES:** ProtocolLib/PacketEvents behind the same SPI; event-only Bukkit integration; direct NMS in checks.

**TRADEOFFS:** A narrow direct adapter needs more version maintenance. If Phase 1 cannot prove final-wire semantics or safe lifecycle hooks, it must return for architecture review to select a tested library adapter. It must not silently guess channel internals or widen supported versions.

## Event contract

An ObservationEnvelope contains schemaVersion, sessionId, connectionEpoch, worldEpoch, captureSeq, direction, sourceKind, sourceEventId, optional causalParentSeq, monotonicCaptureNanos, optional serverTickObservation, protocolState, clientProtocol, rulesetHash, payload and coverage flags. Payload variants are explicit tagged values, not unchecked maps. No borrowed ByteBuf, packet handle, Player, World or Location crosses the boundary. Finite numeric/range validation occurs before state use. Raw yaw may wrap; position, angle and semantic limits come from the version profile, not arbitrary clamps.

Sources: inbound movement (including which position/rotation fields were actually present), attack/interact, input/action, inventory, placement/digging, acknowledgements and lifecycle; outbound position correction, velocity, attributes/effects, abilities, chunk/block changes, entity spawn/move/metadata/destroy, window state, protocol transitions and marker writes; server observations and action outcomes. Unknown packet IDs or unsupported semantics produce capability loss, not invented defaults. Login/configuration/play transitions have explicit legal state tables.

## Ordered processing

1. A channel-event-loop hook observes the accepted inbound message or final outbound representation. Channel callbacks from other threads first enter the bounded bridge described in [Threading](THREADING_MODEL.md). Never assume outbound writes originate on one thread.
2. Adapter performs bounded validation/copying into owned bytes or small immutable values. It records cancellation and transform provenance. State-changing packets cancelled before final send do not become client-visible deltas.
3. The event-loop sequencer assigns captureSeq and publishes to the connection's FIFO. The assignment and offer are one event-loop operation; no producer reserves a sequence and stalls while later producers overtake it.
4. Outbound events begin as WriteAttempt, with wire order and dependency IDs. Success/failure becomes a later WriteOutcome event. A client ACK may reach the reducer before a write callback; it is retained by ID pending resolution, not discarded. Failed or ambiguous writes invalidate related assumptions. Write success only proves handoff, not client application.
5. The actor processes observations in captureSeq order. Bulk decode may run incrementally on the actor; later events cannot commit past an unfinished earlier event. No generic executor can reorder normalization results.
6. Reducer updates model revisions, obtains immutable views, runs bounded validation, commits component/timeline updates and emits evidence plus replay records. Processing failure changes health and invalidates derived results; it cannot leave partial state usable for enforcement.
7. Platform side effects and I/O receive bounded requests. Their outcomes return as future observations with causal IDs. Neither callback mutates player state.

Logical capture order is the observed serialization of inbound/outbound/local submissions, not a claim of exact client tick order or total physical order across machines. Packet timestamps are metadata, never a reorder key. Per-direction TCP order is preserved; causally unrelated main-thread and network events may race and must retain uncertainty. Replay stores the actual serialization.

## Boundary conditions

Bundles are normalized as one envelope with ordered children sharing an atomic application group where required by the version. Fragmented TCP frames are decoded by the transport; one frame/read is not one movement/tick. Compression/encryption state must be observed at a compatible decoded boundary. Chunk copying has count/byte caps and uses a separate bounded bulk pool; a queue contains bounded handles, not unlimited retained network buffers.

Reserved control capacity supports gap, close and health messages. Queue/bulk exhaustion latches a transport health barrier before any later decision can be actionable. Do not drop only a movement packet and continue simulation as if contiguous. See [Threading](THREADING_MODEL.md) for the exact recovery protocol.

Protocol safety rejects malformed sizes before allocation. Malformed framing may disconnect under an availability/protocol policy without a cheat violation. Normal valid traffic must remain unaffected in Phase 1 observation mode. No asynchronous verdict retroactively cancels an already-delivered attack; mitigation has separate semantics.

## Phase 1 demonstrations

Show inbound order under multiple reads, outbound writes from several producers, transformation/cancellation, bundle order, failed writes, ACK-before-write-callback, reconnect, dimension transition and plugin disable. A trace must prove every relevant wire event is captured exactly once or explicitly marked missing. See [acceptance gates](PHASE_0_REVIEW.md).
