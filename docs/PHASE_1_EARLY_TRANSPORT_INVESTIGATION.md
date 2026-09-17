# Phase 1 early-transport attachment investigation

Date: 2026-09-16. Sections 1-10 below are the original investigation-only pass: no production code
was changed in that pass. A second, later pass on the same date implemented the recommended
mechanism; its results are recorded in **Implementation results** at the end of this document.
Nothing is committed either way. Neither pass moves Phase 1 to READY by itself.

## Scope

This document answers one question precisely: can Papea2AC observe the 11 mandatory packets that are
currently unreachable because of *when* the capture tap attaches, and if so, by what mechanism, at what
cost, and within which governance path. It deliberately does not touch P1-H2, P1-H3, or P1-M1.

## 1. The 11 structurally blocked packets

All verified directly against `docs/phase1-artifacts/packet-coverage.tsv` and the pinned
`net.minecraft`/`io.papermc.paper` classes in `.phase1-runtime/versions/26.2/paper-26.2.jar`
(inspected with `javap -c`, not assumed from names).

| # | State | Direction | Native class | Required semantic (per PACKET_PIPELINE.md Observation Matrix) | Occurs before current injection? | Can it occur after? | Damage if missed |
|---|---|---|---|---|---|---|---|
| 1 | HANDSHAKING | SERVERBOUND | `net.minecraft.network.protocol.handshake.ClientIntentionPacket` | Protocol/version negotiation (claimed `clientProtocol`, intent: status/login/transfer) | Always | Never (handshake happens exactly once, first) | **Identity + normalization**: `clientProtocol` is permanently unknown (`-1`) for the whole connection; every later version-gated normalization decision (e.g. movement field availability) runs blind |
| 2 | LOGIN | CLIENTBOUND | `net.minecraft.network.protocol.login.ClientboundHelloPacket` | Session/protocol epoch (encryption-request marker) | Always | Never (sent once, only if online-mode) | **Coverage only** (no player-identifying content beyond a nonce/public key already discarded by design) |
| 3 | LOGIN | CLIENTBOUND | `net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket` | Session/protocol epoch (compression threshold) | Always (if compression enabled) | Never | **Coverage only**; compression state itself is transport plumbing, not gameplay-relevant |
| 4 | LOGIN | CLIENTBOUND | `net.minecraft.network.protocol.login.ClientboundCustomQueryPacket` | Session/protocol epoch (plugin-channel login query) | Sometimes (plugin-dependent) | Never | **Coverage only**; payload is arbitrary plugin bytes, already out of scope for the identical `custom_payload` family elsewhere in this file |
| 5 | LOGIN | CLIENTBOUND | `net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket` | **Identity + protocol state transition**: this is the packet that carries the server's accepted `GameProfile` (UUID + name) to the client and signals LOGIN→CONFIGURATION | Always | Never | **Identity + sequencing**: this is the single clearest state-transition marker in the whole family; missing it means Papea2AC has no direct observation of *when* the server committed to a profile |
| 6 | LOGIN | CLIENTBOUND | `net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket` | Session/protocol epoch (rejection reason) | Always, *only* on the rejection path | N/A (connection never reaches injection today if rejected before PLAY) | **Coverage only** for accepted connections; **entire session invisible** for every connection Papea2AC currently never even creates a `Session` for (all rejected logins) |
| 7 | LOGIN | SERVERBOUND | `net.minecraft.network.protocol.login.ServerboundHelloPacket` | **Identity**: carries the client's claimed username and (in this profile) requested UUID | Always | Never | **Identity**: this is the packet that produces `ServerLoginPacketListenerImpl.requestedUuid`/`authenticatedProfile` server-side (verified in bytecode, see §2); Papea2AC currently has no observation of the claim vs. the eventually-accepted profile |
| 8 | LOGIN | SERVERBOUND | `net.minecraft.network.protocol.login.ServerboundKeyPacket` | Session/protocol epoch (encryption response) | Always (online-mode) | Never | **Coverage only** |
| 9 | LOGIN | SERVERBOUND | `net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket` | Session/protocol epoch | Sometimes | Never | **Coverage only**, same reasoning as #4 |
| 10 | LOGIN | SERVERBOUND | `net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket` | **Protocol state transition**: client's ack of LOGIN→CONFIGURATION | Always | Never | **Sequencing + normalization**: this is the client-side mirror of #5; without it Papea2AC cannot bound *when* CONFIGURATION-state semantics become valid to interpret for this connection |
| 11 | PLAY | CLIENTBOUND | `net.minecraft.network.protocol.game.ClientboundLoginPacket` (the "Login (play)" / join-game packet) | **Identity + later interpretation**: dimension, game mode, hashed seed, view/simulation distance, `sendRecipeBook` etc. — the baseline the player timeline is built from | Always | Never (only sent once per connection; later dimension changes use `Respawn`, not this packet — verified against the coverage table's existing separate `respawn` row) | **Later interpretation**: every later relative/incremental packet (chunk radius changes, respawns) is defined relative to this baseline, which is currently never observed |

Two packets (#5 `login_finished` and #10 `login_acknowledged`) are qualitatively different from the
other nine: they are the actual **protocol-state-transition markers**, not incidental traffic. All 11
share the same root cause (see §2), but they do not all carry the same *cost* of being missed — this
table deliberately does not "group them vaguely as login packets."

## 2. Actual pinned connection lifecycle (verified by `javap -c`, not inferred)

```
TCP accept
  -> ServerConnectionListener.startTcpServerListener(): ServerBootstrap.childHandler(new ServerConnectionListener$1(this))
  -> ServerConnectionListener$1.initChannel(Channel):                                   [runs on the channel's own Netty event-loop thread]
       1. channel.config().setOption(TCP_NODELAY, true)
       2. pipeline.addFirst(FlushConsolidationHandler)                                  (unless disabled)
       3. pipeline.addLast("timeout", ReadTimeoutHandler(30))
       4. pipeline.addLast("legacy_query", LegacyQueryHandler)                          (only if repliesToStatus())
       5. Connection.configureSerialization(pipeline, SERVERBOUND, false, null):
            pipeline.addLast("splitter", frameDecoder)
            pipeline.addLast(FlowControlHandler)
            pipeline.addLast("decoder", PacketDecoder(INITIAL_PROTOCOL))                 <- REAL decoder, live immediately
            pipeline.addLast("prepender", frameEncoder)
            pipeline.addLast("outbound_config", UnconfiguredPipelineHandler.Outbound)    <- PLACEHOLDER, not yet "encoder"
       6. [HAProxy handlers inserted after "timeout" if proxy protocol enabled]
       7. new Connection(SERVERBOUND)  (or RateKickingConnection if rate-limited)        <- the Connection object itself
       8. ServerConnectionListener.pending.add(connection)
       9. connection.configurePacketHandler(pipeline):
            pipeline.addLast("hackfix", Connection$2)
            pipeline.addLast("packet_handler", connection)                              <- stable name, live immediately
      10. connection.setListenerForServerboundHandshake(new ServerHandshakePacketListenerImpl(server, connection))
      11. ChannelInitializeListenerHolder.callListeners(channel)                        <- Paper's own extension point, LAST statement
  -> channel becomes active, starts reading
  -> first inbound message decoded by "decoder" -> dispatched to ServerHandshakePacketListenerImpl -> ClientIntentionPacket handled -> protocol state set to STATUS or LOGIN
  -> [if LOGIN] ServerHandshakePacketListenerImpl installs ServerLoginPacketListenerImpl as the new listener
  -> ServerboundHelloPacket handled -> ServerLoginPacketListenerImpl.requestedUuid / authenticatedProfile populated (verified: both are public fields on this class, set inside handleHello's continuation)
  -> [online-mode] ServerboundKeyPacket, encryption enabled
  -> server sends ClientboundLoginFinishedPacket (profile now committed) -> outbound placeholder is upgraded to a real, protocol-aware encoder at or before this point (the encoder cannot remain "unconfigured" once a real packet must be serialized in a concrete protocol)
  -> client sends ServerboundLoginAcknowledgedPacket -> protocol state becomes CONFIGURATION
  -> CONFIGURATION packet exchange (registry sync, known packs, tags, finish_configuration both directions)
  -> protocol state becomes PLAY; server sends ClientboundLoginPacket (join game)
  -> Paper constructs the ServerPlayer / Bukkit Player object
  -> Bukkit fires PlayerJoinEvent                                                        <- CURRENT Papea2AC.attach() call site
```

**Current attachment point:** `Papea2AC.java` registers `@EventHandler join(PlayerJoinEvent)`, which
calls `PaperCapture.attach(player.getUniqueId(), channel)`. By the time this fires, HANDSHAKING, LOGIN,
and CONFIGURATION have already fully completed for this connection, and the CLIENTBOUND `login` (join
game) packet has already been sent. This matches, and now precisely explains, what the prior session's
coverage table already asserted ("installation after login misses this protocol phase") — that
statement was correct but imprecise; the miss extends through CONFIGURATION and the initial PLAY packet,
not just LOGIN.

**Earliest observable point that actually exists in the pinned server:** the last statement of
`ServerConnectionListener$1.initChannel`, i.e. `ChannelInitializeListenerHolder.callListeners(channel)`.
At that point `"packet_handler"` and the real `"decoder"` already exist in the pipeline with their final
names; the outbound side does not yet have a handler named `"encoder"` (see §3).

## 3. Candidate attachment strategies

### A. `io.papermc.paper.network.ChannelInitializeListenerHolder` (recommended)

Confirmed present in the pinned jar: `io.papermc.paper.network.ChannelInitializeListener` (single method
`afterInitChannel(Channel)`) and `ChannelInitializeListenerHolder` (`addListener(Key, ChannelInitializeListener)`,
`removeListener`, `callListeners`). This is a **first-party Paper API**, not NMS reflection, not a new
dependency, and it is the exact mechanism the pinned server itself already invokes at connection-accept
time. It requires no new library — `net.kyori.adventure.key.Key` is already a transitive Paper-API
dependency the plugin already has on its classpath.

Registering a listener in `onEnable()` (once, not per-connection) and installing our existing
inbound/outbound handler pair inside `afterInitChannel` is the natural analogue of what
`PaperCapture.attach()` already does — just triggered by a different, earlier event, and without a
player UUID available yet (see §5).

- Inbound tap (`addBefore("packet_handler", ...)`): **no change needed**. `"packet_handler"` is already
  correctly named at this point, and `"decoder"` is already the real, live `PacketDecoder`. All 5 inbound
  entries in the table (rows 1, 2→no wait, rows are mixed; concretely: `intention`, `hello` SB, `key`,
  `custom_query_answer`, `login_acknowledged`) become observable with zero change to how the tap is
  positioned, only to when it is installed.
- Outbound tap (`addAfter("encoder", ...)`): **not directly usable yet**. At `afterInitChannel` time the
  handler in that slot is named `"outbound_config"` (an `UnconfiguredPipelineHandler.Outbound`
  placeholder), not `"encoder"`. Both names cannot be relied on simultaneously the way the current code
  relies on `"encoder"` alone. See the safety/gap analysis below.

### B. PacketEvents/ProtocolLib-style injection

Rejected outright for this task: introducing either library is a new dependency, explicitly disallowed
by this task ("Do NOT add a new dependency"), and PACKET_PIPELINE.md's Decision P1 already considered and
rejected "ProtocolLib/PacketEvents behind the same SPI" as an alternative at Phase 0, for reasons
(post-dispatch coverage, adapter trust) that are orthogonal to the timing question this investigation is
about. Not revisited.

### C. Raw `ServerBootstrap`/`EventLoopGroup` replacement

Rejected: this would mean replacing or wrapping Paper's own `ServerConnectionListener`, which is not a
supported extension point, would break on any Paper internal refactor, and is strictly worse than
option A, which Paper already built for this exact purpose. Not investigated further.

### D. Netty pipeline observation via a periodic scan

Rejected: cannot retroactively observe packets already processed before the scan runs; does not solve
the "before injection" problem at all, only helps detect drift after the fact (which
`AdapterBoundaryProbe` already does for a different purpose).

**Recommended path: A.** It is the only option that is (a) dependency-free, (b) first-party Paper API,
(c) verified by bytecode to fire before any protocol traffic is processed, and (d) structurally similar
enough to the existing `PaperCapture.attach()` design that it extends the current architecture rather
than replacing it.

## 4. The unresolved gap: the outbound placeholder-to-encoder upgrade

This is the one piece of real, non-trivial complexity this investigation surfaces, and the reason this
is not a trivial "just attach earlier" change.

At `afterInitChannel` time, the clientbound serialization path is intentionally left unconfigured
(`"outbound_config"`, an `UnconfiguredPipelineHandler.Outbound`) because the server does not yet know
which protocol the client will use for anything it sends. Sending a *real* packet the client must decode
correctly first requires this placeholder to be upgraded to a concrete, protocol-aware `PacketEncoder`
under some name — which, by elimination against the currently-observed pinned code, must happen at or
before the server sends `ClientboundHelloPacket`/`ClientboundLoginFinishedPacket` (the first clientbound
packets of the connection). Whether that upgrade is a `pipeline.replace(oldName, "encoder", newHandler)`
(same position, new name) or something else was not traced further in this pass — doing so exactly is
necessary before implementation, not before this report, and is exactly the kind of detail a "reviewed
profile change" review should require evidence for.

Two consequences follow:

1. **6 of the 11 blocked packets are clientbound** (`hello`, `login_compression`, `custom_query`,
   `login_finished`, `login_disconnect`, and the PLAY `login`/join-game packet). All 6 require solving
   this placeholder-upgrade problem, not just registering the listener earlier.
2. The **5 inbound entries do not** have this problem and are tractable with no additional design work
   beyond §5's identity model.

Concretely, closing the outbound half requires the boundary-integrity model
(`AdapterBoundaryProbe`, PACKET_PIPELINE.md's "Hook placement and integrity" section) to be extended to
distinguish a *known, expected* placeholder-to-real-handler upgrade from an actual foreign-handler
insertion — today it is only designed to detect insertion/removal between periodic checks, not a
same-position name/implementation swap. This is new design work, not a bug fix, and PACKET_PIPELINE.md's
own Decision P1 already anticipates needing review for it: *"Adapter replacement or deeper instrumentation
requires a reviewed profile change."* Attaching earlier for the outbound direction is deeper
instrumentation by that document's own definition.

## 5. Pre-player session identity

No Phase 0 document defines a connection-level identity distinct from the player-keyed `SessionKey`
(`me.nicolas.papea2ac.model.SessionKey`, keyed by `UUID player, long epoch`) that
`PacketEngine.connect(UUID, ...)` already requires. This is a genuine gap for early attachment, not an
oversight in this investigation — Phase 0 was written assuming attachment happens once identity exists.

Verified from the pinned server: a UUID-shaped value (`ServerLoginPacketListenerImpl.requestedUuid`)
first exists only *after* packet #7 (`ServerboundHelloPacket`) is processed — i.e., **after** the very
first packet in our own target list. There is no usable identity at all during HANDSHAKING or before
`ServerboundHelloPacket` is read.

Proposed (design-only, not implemented, does not change any public API): a connection is identified from
`afterInitChannel` by a locally-minted, monotonically increasing `ConnectionEpoch` (same pattern already
used internally by `PacketEngine`'s `epochs` counter for player sessions — see
`anticheat-core/.../PacketEngine.java`), paired with the raw `Channel` reference as the lookup key,
*before* any Minecraft/player identity exists. This is an internal engine concept, not a new public API,
analogous to how `SessionKey` already pairs a UUID with an epoch.

Lifecycle:

```
CONNECTION accepted        -> ConnectionEpoch minted, keyed by Channel; no UUID yet
  -> HANDSHAKE               (still ConnectionEpoch-keyed)
  -> LOGIN                   (still ConnectionEpoch-keyed until ServerboundHelloPacket observed)
      ServerboundHelloPacket -> requested UUID becomes known -> record it against the SAME ConnectionEpoch (no new session; identity attaches to the existing epoch)
  -> CONFIGURATION            (ConnectionEpoch-keyed, UUID now known but not yet a PacketEngine.Session)
  -> PLAY transition          (ClientboundLoginPacket observed under the ConnectionEpoch)
  -> PLAYER SESSION           at this point (or at PlayerJoinEvent, whichever the reviewed design picks)
                              the ConnectionEpoch is bridged into a real PacketEngine.Session keyed by
                              (UUID, epoch), inheriting the ConnectionEpoch's already-captured ordinal
                              range so sequencing stays continuous rather than restarting at 0
```

Ordering stays continuous because both the pre-player and post-player halves are captured by the *same*
event-loop thread for a given channel and can share one FIFO ordinal counter; only the *lookup key*
changes at the bridge point, not the sequence itself. This is a materially new internal concept
(`ConnectionEpoch`/pre-player identity) that Phase 0's `SessionKey` model does not currently have — it is
additive (a new internal concept sitting *before* `SessionKey` in the pipeline), not a change to
`SessionKey` itself, but it is real enough that it should be named explicitly in any reviewed profile
change rather than implemented silently.

## 6. Required-at-early-hook vs. legitimately reclassifiable — per entry

None of the 11 are reclassified in this pass. Per the task's explicit rule, difficulty is not grounds for
reclassification, and PACKET_PIPELINE.md's Observation Matrix explicitly lists "Login/configuration/play
lifecycle" as a mandatory family for exactly the session/protocol-epoch reason these packets exist. All
11 remain **REQUIRED AT EARLY HOOK** and mandatory:

| Packet | Verdict | Citation |
|---|---|---|
| intention | REQUIRED AT EARLY HOOK | PACKET_PIPELINE.md Observation Matrix, "Login/configuration/play lifecycle" row (protocol/version negotiation) |
| hello (CB), login_compression, custom_query, login_disconnect | REQUIRED AT EARLY HOOK | same row (session/protocol epochs) |
| login_finished | REQUIRED AT EARLY HOOK | same row; also the explicit protocol-state-transition marker |
| hello (SB), key, custom_query_answer | REQUIRED AT EARLY HOOK | same row (session identity / protocol epochs) |
| login_acknowledged | REQUIRED AT EARLY HOOK | same row; client-side mirror of the transition marker |
| PLAY `login` (join game) | REQUIRED AT EARLY HOOK | same row, plus the "Out ... Spawn/move/... Observer history" row it seeds |

Two of the earlier session's already-closed `custom_payload`/`cookie_*` entries could tempt a "these are
similar login-adjacent packets, reclassify too" move — that temptation is explicitly rejected here: those
were reclassified because an *identical shared native class* was **already** ruled out of scope elsewhere
in the same file, not because they are hard to reach. None of the remaining 11 has that precedent.

## 7. Impact on the capture-only conclusion

Kept strictly separate, as instructed:

- **A. Can we observe these packets earlier?** Yes for 5 of 11 immediately (inbound); yes for the
  remaining 6 (outbound) after the placeholder-upgrade problem in §4 is solved. This is purely a
  *capture-timing* question.
- **B. Can we establish authoritative downstream application outcome?** Not investigated further here
  because nothing about `ChannelInitializeListenerHolder` changes the answer: it fires at connection
  accept, entirely on the capture side of the pipeline, and exposes no correlated server-dispatch-outcome
  signal of any kind. The prior investigation's conclusion (`docs/phase1-artifacts/transport-correlation-investigation.md`)
  — that the selected Paper/Netty integration exposes no correlated authoritative application outcome —
  is untouched by this work. Nothing here proves or disproves it either way; it simply was not
  re-examined, because an earlier attach point cannot itself supply what was missing (a dispatch-outcome
  channel), so there was no new evidence to weigh.

**Conclusion: PRODUCTION CERTIFICATION MODE remains CAPTURE-ONLY.** This is not weakened or
strengthened by this investigation.

## 8. Security / compatibility analysis (candidate A only)

| Dimension | Assessment |
|---|---|
| Paper version coupling | `io.papermc.paper.network.ChannelInitializeListener{,Holder}` is Paper-API-shaped (`io.papermc.paper` package, not `net.minecraft`), suggesting a semi-stable, versioned surface, but it is not part of the classic `org.bukkit`/`io.papermc.paper.plugin` public API surface documented for third-party plugins in general use; its presence must be re-verified at every pinned-version bump exactly like every other pinned accessor this project already checks at startup |
| NMS coupling | Low for the registration call itself (pure Paper API); **new and higher** for the outbound side specifically, because closing §4 requires depending on the literal string `"outbound_config"` and the fact that it gets upgraded in place — that detail is not part of any published Paper API and was only recovered by reading this pinned version's bytecode |
| Netty coupling | Same as today: relies on `ChannelPipeline` handler names and Netty's addBefore/addAfter semantics, which the project already depends on |
| Handler ordering | Unaffected for inbound. For outbound, ordering is only correct once the real encoder exists in the same pipeline position the placeholder occupied; this must be verified, not assumed, before implementation |
| Third-party plugin interaction | `ChannelInitializeListenerHolder` is a shared, keyed registry — other plugins using the same mechanism do not conflict as long as each uses a distinct `Key` (already true of Papea2AC's own existing handler names `papea2ac-in-<epoch>`/`papea2ac-out-<epoch>`) |
| Reload behavior | Must deregister via `removeListener` on plugin disable, mirroring the existing `removeHooks` cleanup, or a reload would double-register |
| Disconnect cleanup | New failure surface: connections that disconnect during HANDSHAKING/LOGIN/CONFIGURATION never fire `PlayerJoinEvent` and must be cleaned up via `channelInactive`/`handlerRemoved` on the early tap itself, exactly as the existing inbound handler already does — but now this is the *only* cleanup signal for connections that never reach PLAY, since there is no player object to key eventual cleanup off of |
| Server shutdown | No new concern beyond what already exists: `PacketEngine.close()`'s non-blocking shutdown model already handles arbitrary in-flight sessions |
| Failure containment | A pre-player connection that fails must not throw out of `afterInitChannel` (runs on the Netty I/O thread, synchronously, inside Paper's own `initChannel` call) — must catch and self-heal exactly like the existing `attach()` wraps its pipeline work in `try/catch` |
| Future Minecraft update cost | Higher than today for the outbound half specifically, because of the `"outbound_config"` coupling; unchanged for the inbound half, which only depends on already-pinned names (`"packet_handler"`, `"decoder"`) |

## 9. Prototype

**NOT PERFORMED.** Reasoning: the mechanism itself (`ChannelInitializeListenerHolder.callListeners`
firing before any protocol traffic, with `"packet_handler"`/`"decoder"` already live and `"encoder"` not
yet) was verified statically against the pinned server's actual bytecode (§2, §3), which is the
verification this task asked for ("Verify against the pinned implementation. Do not infer this from API
names."). Building a working end-to-end prototype that proves HANDSHAKING → LOGIN → CONFIGURATION → PLAY
observation and clean disconnect-before-PLAY requires either a full running pinned Paper server plus a
real or raw-socket Minecraft client driving it through all four protocol states, or reproducing
`ServerConnectionListener`'s real childHandler chain in a unit test — both are materially more than the
"small isolated proof-of-concept" this task authorizes, and faking either would violate "do not fake the
result." This is marked **EXTERNAL QUALIFICATION REQUIRED**, alongside the existing native-client and
second-host qualification items, not because the mechanism is in doubt, but because *dynamically*
proving it (as opposed to statically verifying it) needs a live connection this environment does not have
available in a form this task's guardrails permit standing up.

## 10. Architecture gate

**IMPLEMENTATION PATH EXISTS WITHIN APPROVED ARCHITECTURE.**

Reasoning: `ChannelInitializeListenerHolder` is a legitimate, dependency-free, first-party Paper
extension point that requires no change to any Phase 0 document's stated model — it is a different
*trigger* for the same kind of pipeline-adjacent tap the architecture already describes, not a different
kind of boundary. The one genuinely new piece (§4's outbound placeholder upgrade, and §5's pre-player
`ConnectionEpoch` identity) is real, additive design work, but PACKET_PIPELINE.md's own Decision P1
already names the exact governance path for it: *"Adapter replacement or deeper instrumentation requires
a reviewed profile change."* This is deeper instrumentation. It does not require rewriting Decision P1,
Decision P2, the `SessionKey`/`Session` model, or any other Phase 0 document — it requires exercising the
review process those documents already specify before implementation proceeds. That review has not
happened in this pass and must happen before any code is written, consistent with "Do NOT self-approve."

## Summary of what changed and did not change

- **Changed:** nothing in the working tree except this document.
- **Learned:** a viable, dependency-free early-attach mechanism exists and was verified against pinned
  bytecode; it fully solves the inbound half of the 11-entry gap with no new risk; it only partially
  solves the outbound half pending a specific, now-scoped design item; it does not touch, and cannot by
  itself resolve, the separate authoritative-outcome-correlation question that keeps production
  CAPTURE-ONLY.

## Implementation results (second pass, same date)

This section records what was actually built, immediately following the investigation above, in a
separate implementation pass. All 11 entries listed in §1 were closed; the outbound blocker in §4 was
solved (not worked around) using the anchor this document identified. No Phase 0 document was
changed. No commit was made.

### Actual early attachment implementation

`io.papermc.paper.network.ChannelInitializeListener`/`ChannelInitializeListenerHolder` are **not**
part of the compile-time `paper-api` artifact this module builds against — verified by listing the
pinned `paper-api-26.2.build.124-stable.jar`'s contents directly: it has zero entries under
`io/papermc/paper/network/` and zero `net/kyori/adventure/key/Key` entries (both classes exist only in
the running server jar). This was not assumed going in; it was discovered while implementing and is
the reason the mechanism is wired reflectively rather than by direct import, exactly like every
NMS-facing accessor already in this codebase (`PaperCapture.registerEarlyHook()` builds a
`java.lang.reflect.Proxy` implementing `ChannelInitializeListener` and calls
`ChannelInitializeListenerHolder.addListener(Key, listener)` through reflection, mirroring the
existing `PacketVisitor` proxy pattern in `PaperPacketInventory.inspect()`). This does not change the
architecture-impact conclusion in §10 — it changes *how* the boundary is reached, not *what* it is.

`Papea2AC.onEnable()` calls `capture.registerEarlyHook()` once, after the engine is constructed;
`onDisable()` calls `capture.unregisterEarlyHook()`. A failed registration (method/class missing on a
future server build) logs a warning and the plugin degrades to the pre-existing `PlayerJoinEvent`-only
attachment — it does not disable the plugin or throw.

### Actual inbound anchor

Unchanged from the recommendation: `pipeline.addBefore("packet_handler", name, handler)`, installed
inside `afterInitChannel`. `"packet_handler"` is live under that exact name at that point (verified
in §2's bytecode trace and now also by the native pipeline test below).

### Actual outbound anchor/mechanism

`pipeline.addAfter("hackfix", name, handler)`. `"hackfix"` is a stable, unconditionally-added handler
(`Connection.configurePacketHandler`) that sits immediately encoder-side of `"packet_handler"`
regardless of whether the outbound codec slot next to it is still named `"outbound_config"`
(pre-negotiation placeholder) or has been swapped in place for the real `"encoder"`. Anchoring on
`"hackfix"` never needs to know which of those two is currently there, so it needs no name-swap
detection, no `AdapterBoundaryProbe` extension, and no dependency on the fragile, undocumented
`"outbound_config"` literal at all — a materially simpler resolution than the "extend the boundary
model" path §4 anticipated might be necessary. `NativeEarlyTransportHookTest.realChannelInitializeListenerHolderInstallsBeforeAnyProtocolByte`
asserts this position explicitly (`assertEquals(names.indexOf("hackfix"), outIndex-1, ...)`) against a
pipeline shaped like the pinned server's own pre-negotiation state (no `"encoder"` present at all).

### Pre-player identity model

Implemented as designed in §5, as a new internal type pair (`anticheat-model`):

- `TransportSessionId(long value)` - minted from a `PaperCapture`-local `AtomicLong`, never derived
  from anything client-supplied.
- `TransportPhase` - `ACCEPTED, HANDSHAKING, LOGIN, CONFIGURATION, PLAY, CLOSING, CLOSED`, with
  `canAdvanceTo` rejecting every transition not in that exact forward chain (`CLOSED` is terminal;
  every other state can additionally go straight to `CLOSING`). Covered by `TransportPhaseTest`.

A new package-private `PreLoginTransport` (platform-paper only, not leaked into any
platform-independent module) holds, per connection: its `TransportSessionId`, the raw `Channel`, the
current `TransportPhase`, a bounded journal (see below), `claimedIdentity` (`volatile UUID`, set only
from an observed `ServerboundHelloPacket`/`LoginHello`, read by nothing that trusts it), and
`establishedIdentity`/`boundSession` (set only inside `bind`).

**No existing public API changed.** `PacketEngine.connect(UUID, ...)`, `SessionKey`, and `Session` are
byte-for-byte unchanged — the investigation's concern that continuity might require making `SessionKey`
mutable turned out to be avoidable. Continuity is achieved by *replay*, not by rekeying: everything
captured pre-player is journaled as plain `(direction, ordinal, capturedNanos, PacketPayload)` tuples
using the *same* per-connection ordinal counters the live path uses (the inbound/outbound handler
instances are installed once, at `afterInitChannel`, and live for the whole connection — their
`ordinal` fields are never reset), then replayed through `session.ingress.capture(...)` — the exact
method the live path calls — immediately after the real session is created and before any new traffic
reaches it. `ConnectionIngress.capture` assigns its own internal sequence number by simple
`++sequence`, so replaying N buffered entries and then continuing with live ones produces a naturally
unbroken 1..M sequence with no changes to `ConnectionIngress`/`TruthActor` at all.

### Client-claimed vs. server-established identity binding point

`ServerLoginPacketListenerImpl.requestedUuid`/`authenticatedProfile` (public fields, verified by
`javap`) become non-null immediately after the server processes `ServerboundHelloPacket` — i.e. during
LOGIN, well before CONFIGURATION or PLAY. Bukkit's `AsyncPlayerPreLoginEvent` (confirmed present in
the pinned `paper-api` jar, with a `getUniqueId()` accessor) fires at essentially that same point and
is the earliest generally-available signal of the **server-established** identity — cryptographically
verified in online mode, server-computed in offline mode either way it is the server's decision, not
the client's claim.

This implementation pass does **not** move the bind point to `AsyncPlayerPreLoginEvent`. `bind()` is
still called from `PlayerJoinEvent`, as before, using `Player.getUniqueId()` — which by that point *is*
the same server-established identity, just confirmed later than strictly necessary. Moving the bind
point earlier would require safely correlating an event that fires on a Bukkit-managed background
thread pool back to the correct `Channel`/`PreLoginTransport` (via `getConnection()`, a Paper API type
not exercised in this pass), which is additional, separable work with its own thread-safety surface —
deliberately deferred rather than rushed into the same change as the hook itself. This is the one part
of §5's design not implemented this pass; it is a strict improvement opportunity, not a defect, since
the current bind point is still strictly later than (a superset of) the true establishment point, never
earlier.

Distinct fields end to end, never conflated: `PreLoginTransport.claimedIdentity` (client claim, never
read by anything that trusts it) vs. the `player` parameter `bind(UUID player, Channel channel)`
receives (server-established, from `PlayerJoinEvent`) vs. `session.key.player()` (the actual trusted
session identity — set to the `bind` parameter, never to the claim). Test
`handshakeAndLoginInboundAreJournaledPrePlayerThenReplayedContinuously` asserts this directly: it binds
with a UUID different from the one claimed in the journaled `ServerboundHelloPacket` and asserts the
resulting session is keyed on the bind UUID, not the claim.

### Lifecycle state machine

Phase advances happen only from already-normalized facts (never raw packet inspection), inside
`PaperCapture.classify`: a `Handshake` payload advances `ACCEPTED -> HANDSHAKING` (and records the
claimed `clientProtocol`); `LoginHello` advances `-> LOGIN` (and records, never trusts, the claim); a
`Lifecycle(LOGIN_ACKNOWLEDGED)` advances `-> CONFIGURATION`; `bind()` advances `-> PLAY`. Every call
goes through `TransportPhase.canAdvanceTo`, so an out-of-order or duplicate signal is silently rejected
(the phase simply does not move) rather than corrupting state — this is deliberately fail-safe, not
fail-loud, matching the project's existing "degrade coverage, never fabricate certification" posture.
`channelInactive`/`handlerRemoved` drive `CLOSING -> CLOSED` unconditionally and clear the journal.

### Sequencing model

Covered above (replay through the same `ingress.capture` call the live path uses). Verified
dynamically: `handshakeAndLoginInboundAreJournaledPrePlayerThenReplayedContinuously` captures 3
pre-player events, binds, asserts `processedSequence() == 3`, then captures one more live event and
asserts `processedSequence() == 4` — i.e. continued, not reset.

### Cleanup model

`closeTransport` (called from the early handler's `channelInactive`) is reachable with **no player,
UUID, or `Session` in existence at all** — it removes the `PreLoginTransport` from the connection map,
advances it to `CLOSING` then `CLOSED`, and clears its journal; only if a `Session` had already been
bound does it additionally invalidate health and close that session. Verified by
`disconnectBeforeBindNeverInvolvesAPlayerOrUuidAndReportsNoFailure`, which disconnects mid-LOGIN with
no `bind()` ever called and asserts zero engine sessions and zero reported failures.

The pre-player journal is bounded exactly like the existing certified journal (256 entries / 256 KiB):
once full, further pre-player packets are still forwarded downstream untouched (nothing is ever
blocked or disconnected because of this bound) but simply stop being recorded, and the connection is
marked `truncated()` — an honest "incomplete prefix" outcome, the same posture the existing replay
recorder already uses, rather than unbounded growth or a fabricated later "catch-up". Verified by
`preLoginJournalOverflowMarksIncompleteInsteadOfGrowingWithoutBoundOrRejectingTraffic` (300 packets
sent pre-bind, exactly 256 replayed).

### Thread ownership

Unchanged in kind from the existing model: `afterInitChannel` runs on the accepted channel's own Netty
event-loop thread (verified in §2: it is the literal last statement of `initChannel`, which Netty
guarantees runs there), so pipeline installation needs no synchronization. Both early handlers run only
on that same per-channel thread for the lifetime of the connection (Netty's normal single-threaded
pipeline guarantee), so the pre-player journal's plain (non-atomic) list needs no locking either.
`PreLoginTransport.boundSession` is the one field read from a different context (a scheduler/main-thread
caller invoking `bind`) and is `volatile` for that reason; `bind`'s own pipeline mutation (replay +
arming) is itself dispatched onto `channel.eventLoop().execute(...)`, exactly like the pre-existing
`attach` path, so the "arm" write and all later handler reads happen on the same thread and no second
concurrent owner of session truth is introduced.

### Failure behavior

`afterInitChannel`, both early handlers, and `bind` each catch broadly (`ReflectiveOperationException`,
`RuntimeException`) and degrade rather than propagate: a normalization failure increments the existing
`MALFORMED`/`FAILURE` metrics and invalidates health *if* a session already exists, or is simply not
journaled if not; a pipeline-installation failure reports through the existing
`engine.reportFailure` path without touching other connections. No new certification path was added
anywhere in this change — nothing here can turn "packet observed" into "packet accepted"; the only
outcome-relevant object touched is the pre-existing `HealthLatch`, invalidated (degraded), never
upgraded.

### Compatibility risks

As assessed in §8, with one materially better outcome than anticipated: the outbound anchor no longer
depends on the undocumented `"outbound_config"` literal at all (see above), which was the single
highest version-coupling risk identified in the original investigation. Remaining risk is unchanged:
`ChannelInitializeListenerHolder`'s continued existence and `Key`/`afterInitChannel` signature on
future pinned versions, exactly as `PaperPacketInventory`'s startup fingerprint already exists to catch
for the numeric-ID surface. `registerEarlyHook()` failing closed (falls back to join-time attach,
logs a warning, does not disable the plugin) is the containment for that risk.

### Dynamic/local proof

`NativeEarlyTransportHookTest` (nativeTest, 5 tests) exercises the **real, pinned**
`ChannelInitializeListenerHolder.callListeners(Channel)` — not a mock, not a re-implementation — against
a hand-built `EmbeddedChannel` shaped like the pinned server's pipeline at accept time (verified shape,
no `"encoder"` present). It proves: the real holder accepts and later fires our registration; the
inbound/outbound anchors land at the exact verified positions; a `ClientIntentionPacket` (HANDSHAKING)
and `ServerboundHelloPacket`/`ClientboundHelloPacket` (LOGIN, both directions) are captured pre-player;
`bind()` replays them with continuous sequencing and keys the session on the bind-supplied identity, not
the claim; two unrelated connections never share state; a pre-player disconnect cleans up with zero
sessions and zero reported failures; a 300-packet pre-player flood is capped at the 256-entry bound.
`NativeEarlyTransportSemanticTest` (8 tests) separately proves native-object normalization for all 11
previously-blocked packet types.

Beyond nativeTest, the rebuilt plugin was loaded on the actual pinned Paper 26.2 server for a real
startup/shutdown cycle (`java -jar paper.jar --nogui`, `stop` via console): `[Papea2AC] Enabling
Papea2AC` and `[Papea2AC] Disabling Papea2AC` both completed with no warning about early-hook
registration failing and no exception anywhere in the log, meaning `registerEarlyHook()`'s reflective
`Proxy`/`addListener` call succeeded against the real server jar, not just against nativeTest's copy of
it. No player ever connected during this run.

### External qualification still required

- **Real Minecraft client** (or a raw-socket handshake) actually connecting through the installed early
  hook end-to-end. Everything above is real (real pinned bytecode, real `ChannelInitializeListenerHolder`,
  real running server), but no live TCP handshake from an external client was performed. This is the
  same native-client qualification item already open from prior sessions, not a new one.
- **Second OS/architecture** conformance, unchanged, already open.
- Moving the identity-binding point to `AsyncPlayerPreLoginEvent` (see above) is an *optional* future
  improvement, not a qualification blocker — the current bind point is already correct, just later than
  strictly necessary.
