# Latency and acknowledgement compensation

## Decision N1: causal state intervals, not ping rewind

**DECISION:** Model outbound ordering, write outcomes, protocol acknowledgements and client application uncertainty explicitly. RTT is a diagnostic and horizon-sizing input; RTT/2 is not an authoritative one-way delay or a target-history index.

**WHY:** Asymmetric delay, tick boundaries and server scheduling invalidate a single ping-shifted world.

**ALTERNATIVES:** Fixed grace period; disable above a ping threshold; infer application immediately on send or ACK receipt.

**TRADEOFFS:** More branches and possible loss of coverage at finite history limits. No finite-memory system can retain arbitrarily delayed state; it must say when it cannot decide.

## Ledger and guarantees

MarkerRecord contains epoch, unique ID, wire ordinal, preceding update range, write status, monotonic send time, ACK sequence/time, expiry and guarantee profile. Markers use a supported play-state request/response mechanism selected from the pinned protocol, initially a ping/pong-style candidate subject to the Phase 1/7 guarantee tests. Do not reuse legacy inventory transaction assumptions in a modern protocol. Teleport confirmation has its own dedicated ledger and semantics; generic ACKs do not confirm teleport execution.

No generic ACK by itself proves client physics has applied all preceding updates. A guarantee profile separately describes network receipt/handler ordering, application scheduling, relevant client tick boundaries and permitted ambiguous steps. Until established against the target vanilla client, marker ACKs supply receipt/RTT evidence only and must not advance a physics-application frontier. Exact protocol ID, mappings and these semantics are release-gated artifacts, not assumptions hidden in code.

For each outbound update maintain earliestPossibleApplication, latestPossibleApplication if proven, and a causal dependency set. Bracket updates with ordered markers only where the version's guarantees justify it. Delayed ACKs can resolve receipt while leaving before/after application hypotheses for adjacent movements. Intersect constraints; contradictory constraints produce a protocol/model diagnostic and UNCERTAIN, not an arbitrarily selected state.

## ACK handling state machine

CREATED -> WRITE_PENDING -> SENT -> ACK_OBSERVED -> RECEIPT_RESOLVED -> APPLICATION_CONSTRAINED -> RETIRED. WRITE_FAILED and EXPIRED are terminal uncertainty paths. ACK-before-write-callback is retained until the write outcome is known. Duplicates are idempotent; unknown IDs do not advance any frontier. IDs are scoped to session, protocol state and marker generation. Wrap/reuse requires no outstanding ambiguity; otherwise open a new marker generation. Bound outstanding markers and ACK tombstones.

A client can forge or accelerate acknowledgements. They are protocol observations, not trusted clocks or proof of an honest client. Validate ID/order consistency and legal response state; early ACKs cannot manufacture proof of world application. Deliberate ACK suppression eventually causes unavailable coverage or resource-policy disconnection, never fabricated movement violations. TCP head-of-line delay and application lag should not be reported as packet loss merely from a missing response.

## Clocks, high latency and retention

Maintain separate capture monotonic time, Paper tick observations and inferred client simulation step ranges. Timer consumers use recorded monotonic deltas and batching allowances, with server-stall and capture-gap information. Never derive timer guilt from a single burst after a pause. Wall time is for human labels only.

Target reconstruction horizon is three seconds in the qualification tier, with actual query availability equal to the minimum dependency horizon from PERFORMANCE. Marker diagnostics may retain ten seconds/256 markers, but do not extend world/entity/timeline coverage. Pending validation expires at its minimum available horizon; report achieved seconds per consumer. High ping permits validation where covered; history loss disables only dependent negative proofs.

## Decision N2: bound application uncertainty, do not enumerate a Boolean product

**DECISION:** Keep at most 64 diagnostic influence records, but only eight simultaneously unresolved physics-affecting updates per supported motion domain. Use the M3 application automaton with ordered replace/add operations and step intervals. **WHY:** Ledger capacity is not a feasible branch budget. **ALTERNATIVES:** Enumerate 2^k states; silently forget old impulses. **TRADEOFFS:** More than eight active unresolved influences produces UNCERTAIN_APPLICATION_OVERFLOW until reconciled, which is visible coverage loss with zero violation weight.

No generic velocity-application guarantee is certified by Phase 0. A proved target-specific response may tighten application intervals; otherwise they stay open. A later certified observation can eliminate an application branch only if a sound covering exclusion proves that branch cannot explain it; failure to find a witness is insufficient. Such elimination is inference conditional on the movement model, recorded with proof IDs, never a claimed ACK guarantee. Contradiction invalidates the frontier. Teleport baseline reconciliation can reset old obligations only when the profile proves their pre/post semantics.

Influence interval transfer must retain replacement order, one-shot consumption and possible application steps. A simple sum of max impulses is a safe outer bound only when a proof covers replacement/drag/bounce and signs; otherwise widen or mark incomplete. Phase 7b tests repeated knockback at 50/150/300 ms with explicit coverage floors in TESTING. ACK receipt/application confusion is downgraded to a guarded implementation obligation; the high architectural risk is exclusion precision under sustained unresolved state.

## Required experiments

Native-client capture must cover update then marker in the same flush, update in a bundle, delayed ACK across multiple server/client ticks, ACK before write callback, failed write, duplicate/unexpected ACK, reconnect ID reuse, teleport confirmation, server stalls, asymmetric delay and proxy transforms. Record wire and application evidence with independent instrumentation. A passing network-order test alone cannot certify client game-thread application order.

Intave's README describes compensation for late acknowledgements. Papea2AC adopts the problem statement, not an assumed implementation of that claim. See [reference analysis](INTAVE_REFERENCE_ANALYSIS.md).
