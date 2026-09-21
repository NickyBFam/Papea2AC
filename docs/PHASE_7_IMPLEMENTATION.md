# Phase 7 — Latency / Acknowledgement / External-Motion Timing

Status: **READY FOR HUMAN REVIEW** (not committed, not baselined, no Phase 8 work).
Base: Phase 6 baseline `ca6c16004247808fdb81203ecd1e041d3cfe7259`, receipt `6d5485bb564450d79944f7799fa3f52029070e3f` (HEAD, unchanged).
Pinned pair: Paper 26.2 build 124 (`.phase1-runtime/versions/26.2/paper-26.2.jar`) / vanilla 26.2 client. Toolchain Java 25 (`.phase1-tools/jdk-25.0.4.1+1`).

Phase 7 answers one question, bounded and without judgement: **for an interval between two client transmissions, which bounded set of
timing hypotheses (client-tick alignment, application tick of every outstanding external influence, relation to an outstanding position
correction) is consistent with the ordered evidence, and which of those admit a legal input history under Phase 6?** It never says which one
is true, never turns latency into a verdict, and never lets a timing uncertainty exclude the branch a real client actually took.

## SCOPE

In scope (implemented, tested, real-client qualified):

* a bounded per-session RTT model fed by two request/response pairs of the pinned play protocol (keep-alive, ping/pong), with the
  server-issued ping marker as Phase 7's own instrument (`MarkerPort`);
* the ACK inventory and semantics of the 26.2 protocol (no legacy transactions), established from the jar's bytecode and live capture;
* session-scoped marker correlation with bounded pending/tombstone state, expiry, late and duplicate answers, write failures;
* the external-motion lifecycle (server velocity SET, explosion knockback ADD, declared-unknown UNRESOLVED): registration, application-tick
  branching over the interval, wire-order application, expiry, overflow;
* the position-correction lifecycle (send, confirm, supersession, stale confirm, write failure, re-send semantics) and its timing relation
  to movement (pre-application / application point);
* movement alignment through the 26.2 client's per-tick `ServerboundClientTickEndPacket` (exact horizon), a bounded 1–3 tick fallback when
  tick ends are unavailable or coverage is degraded, and a bounded silent closure for a standing client's 20-tick reminders;
* the immutable `TimingResolution resolve(TimingRequest)` API with rich statuses and provenance, and the Phase 6 adapter
  `TimingReconstruction` that unions the legal continuations of every hypothesis (no `InputReconstructor` duplication);
* world-epoch and session-epoch isolation;
* the `/papea2ac timing <player>` diagnostic with a controlled velocity/teleport harness for qualification;
* deterministic fixtures, property tests, fuzzing, round-trip gates in the thousands, memory harness, JMH, a native ACK inventory and a
  native differential of the velocity codec, and the real-client qualification recorded in `docs/phase7-artifacts/real-client-evidence.log`.

Out of scope (Phase 8+): any judgement, any use of the hypotheses to accept/reject/setback/punish/alert, violation levels, "Speed/Fly"
vocabulary, marker answers narrowing an application frontier (`pongBoundsApplication=false`, measured only), network emulation beyond
localhost, capacity claims.

## MODULE OWNERSHIP

| Module | Phase 7 content | Why here (ARCHITECTURE.md DAG) |
|---|---|---|
| `anticheat-version` | `TimingProfile` (version-scoped facts and guarantee flags), `LowPrecisionVector` (bit-exact `LpVec3` reproduction, P7-F8) | release-gated protocol facts live with the pinned version, never hidden in code |
| `anticheat-player` | `TimingEvent` (the vocabulary, two clocks), `RttEstimator`, `MarkerLedger`, `TimingLedger` (mutable, owner-thread, published immutable `View`) | "mutable ledgers in player" (A3); player cannot see protocol types |
| `anticheat-simulation` | `TimingRequest`, `TimingBudget`, `TimingHypothesis`, `TimingResolution`, `TimingResolver` (interval/constraint enumeration), `TimingReconstruction` (Phase 6 adapter, branch union) | interval/constraint solving next to `InputReconstructor`; pure, stateless, no `System.nanoTime` |
| `anticheat-core` | `TimingObservationMapper` (Phase 1 observation → `TimingEvent`), `MarkerPort` (outbound marker port), marker scheduling and ledger feeding in `PacketEngine`, `TimingTracer` (diagnostic walk), `TimingStatus`/`TimingEngineStatus` (primitive projections) | the only module that legally sees protocol, player and simulation together |
| `anticheat-platform-paper` | `PaperMarkerPort` (writes `ClientboundPingPacket(int)` on the channel by reflection), `SELF_ENTITY` control at attach, `/papea2ac timing` command and status line, the qualification harness (`velocity x y z`, `teleport dx dy dz`) | display and transport only; no timing logic |

`ArchitectureTest.timingLayerStaysWithinThePhase7Boundary` forbids verdict vocabulary, `System.nanoTime` in the resolver/adapter, hash-order
collections in the timing sources and any `(rtt|ping) … / 2`; the Phase 6 boundary test excludes `Timing*` files explicitly. No Bukkit/NMS
types leave platform-paper; no `net.minecraft.` text exists in non-platform sources.

## PHASE BOUNDARY

Phase 7 produces **hypotheses and their provenance**, nothing else. Concretely:

* no `VALID` / `INVALID` / `CHEATING` / `SUSPICIOUS` token anywhere in the timing sources, statuses or diagnostics (architecture test + the
  tracer/resolver vocabulary tests);
* no violation level, no setback, no punishment, no alert, no `Speed`/`Fly`;
* a transmission without an explaining hypothesis is reported as **coverage / timing / model to investigate** (`NO_MODELLED_CANDIDATE`,
  `INCOMPLETE`, `SEARCH_LIMIT`, …), and the diagnostic prints exactly that sentence with "never a verdict";
* the union frontier is carried forward: when several hypotheses explain a transmission all their continuations survive; nothing picks one;
* RTT is never halved into a one-way delay and never used as truth: it is a window with provenance (`RttWindow`), reported, not applied;
* marker answers never narrow an application frontier under the pinned profile (N1).

## TIME DOMAINS

Six monotone domains, never mixed:

| Domain | Source | Used for |
|---|---|---|
| capture sequence | Phase 1 `Observation.sequence()` (one writer per session) | ordering, interval slicing, pre/post-confirm decisions |
| capture-monotonic nanos | `Observation.capturedNanos()` (`System.nanoTime` at the capture tap; outbound at the encoder tap, inbound at the pre-process tap) | RTT samples, expiry, staleness, evaluation time |
| server tick | `Observation.Tick` → `TimingEvent.ServerTick` | recorded only (never an alignment source in Phase 7) |
| client tick | `ServerboundClientTickEndPacket` → `TimingEvent.ClientTickEnd` | exact alignment horizon |
| ack ordering | marker ids (kind + id + wire ordinal), teleport ids, TCP order | correlation; wire order ⇒ application order for influences |
| world / session epoch | `Discontinuity(newWorldEpoch)`, `SessionKey.epoch()` | intervals never cross either |

`TimingEvent` carries both clocks on every record; the resolver reads only what the request hands it (`nowNanos` is the **evaluation time
of the interval**: a live consumer's now, an offline walk's target capture time — P7-F7). Wall-clock time is never used. Large and
wrapped monotonic values are covered by `TimingPropertyTest.largeMonotonicTimestampsAndOrderingAreSafe` (differences only, never absolutes).

## RTT MODEL

`RttEstimator` (player): 32-sample window; smoothed EWMA α=1/8 and jitter β=1/4 (seeded from the first sample with shifts, no division);
outlier = sample > 3·smoothed + 4·jitter + 20 ms — kept in the window (min/max/count), not folded; three consecutive outliers re-seed (a
persistent shift is the new reality); samples ≤ 0 or > 60 s are rejected and counted; `stale(now)` after 30 s without a sample;
envelope `[min, max(max, smoothed + 4·jitter)]`. `Summary` is immutable and cheap; `hasSamples()` distinguishes "no evidence" from zero.

Semantics: a sample is **encoder tap → pre-process tap**, i.e. the network round trip plus the client's network-thread handling, never a
client tick, never halved. Consumers get a window (`TimingHypothesis.RttWindow{present, low, high, stale}`) and the statuses `NO_ACK_EVIDENCE`
/ `INSUFFICIENT_RTT` name its absence; nothing in Phase 7 narrows a hypothesis with it.

`rtt-model-results.json` (synthetic): stable 20 ms → smoothed 20.0 / jitter ≈ 0; 20–60 ms jitter → smoothed 45.8 / jitter 10.1 / envelope
high 86.3; a single 5 s spike → counted as 1 outlier, smoothed still 20.0; a 20→400 ms shift → 3 outliers then re-seed to 400.0.
Live (localhost, final jar): last 0.15–0.5 ms, smoothed 0.2–0.7 ms, jitter ≈ 0.1 ms, max 30 ms bursts during joins; samples of 30–100 ms
appeared while a diagnostic report ran on the main thread and were classified as outliers or, when persistent, folded by the re-seed rule —
exactly the designed behaviour (see LOCALHOST).

## ACK SOURCES

`ack-inventory.json` (written by `NativeAckInventoryTest` from the jar):

| Pair | Clientbound | Serverbound | Id | Answered from | Wire ids (PLAY) |
|---|---|---|---|---|---|
| keep-alive | `ClientboundKeepAlivePacket(long)` | `ServerboundKeepAlivePacket(long)` | long challenge | client network thread | 0x2c / 0x1c |
| ping / pong | `ClientboundPingPacket(int)` (Phase 7 marker) | `ServerboundPongPacket(int)` | int | client network thread; server `handlePong` is a no-op | 0x3d / 0x2d |
| position correction | `ClientboundPlayerPositionPacket(id, PositionMoveRotation, Set<Relative>)` | `ServerboundAcceptTeleportationPacket(int)` + PosRot | int, increments per `internalTeleport` | client game thread | 0x48 / 0x00 |
| client tick | — | `ServerboundClientTickEndPacket()` | — | once per client tick | — / 0x0d |

No legacy transaction/window-confirmation packet exists in the 26.2 play protocol. The marker is issued by core through `MarkerPort` (1 Hz
per session once the self entity is known, plus one bracket ≥ 50 ms after each outbound influence/correction, ≤ 16 pending); its id is
`epoch byte << 24 | counter`, so a late answer of a replaced session never matches the new ledger.

## ACK SEMANTICS

Established from the bytecode (`java.lang.classfile`) and pinned by the native test:

* `ServerCommonPacketListenerImpl.handlePong`: a single `return` — a pong is receipt evidence only.
* `keepConnectionAlive`: one keep-alive **per second** (`nanoTime − lastKeepAliveTx ≥ 1 s`), nanoTime-derived challenge id, a pending queue
  (`io.papermc.paper.util.KeepAlive.pendingKeepAlives`), kick when the oldest pending is older than `KEEPALIVE_LIMIT =
  Long.getLong("paper.playerconnection.keepalive", 30) s`; the vanilla `LATENCY_CHECK_INTERVAL = 15000` exists but does not drive the cadence
  (**P7-F3**: the profile first said 15 s). `handleKeepAlive` matches the head of the pending queue; Papea2AC correlates the same long id
  itself and never reads the server's latency field.
* `handleAcceptTeleportPacket`: main thread; accepted only when `id == awaitingTeleport` (a stale or superseded id is ignored); the server then
  snaps the player to `awaitingPositionFromClient`. `updateAwaitingTeleport`: an unconfirmed teleport is re-sent (same id) after 20 ticks.
* `handleClientTickEnd`: main thread, fires Paper's `ClientTickEndEvent`.
* Client side (verified live): the correction is applied on the game thread and followed by the confirm and a PosRot at the applied position
  in the same handler (the profile's `teleportConfirmIsGameThreadFence`); the same tick's regular position packet follows before any tick
  end; tick ends arrive once per client tick (20/s observed continuously).

Phase 7 consumption rules: `MarkerAcknowledged` resolves a pending marker in order → `RESOLVED` (sample) / `RESOLVED_WRITE_UNKNOWN` (sample,
write outcome not yet seen) / `DUPLICATE` / `LATE_OR_UNKNOWN` (counted, never a sample); a failed write retires the marker (its answer can
never be valid); expiry after 10 s moves the marker to a bounded tombstone list (64) so a late answer is recognised as late, not unknown;
Phase 1's `EXPIRED`/`OUTCOME_UNKNOWN` journal outcomes are **not** packet loss and do not touch timing coverage (P7-F4, brief 56/57).

## TIMING WINDOWS

For an interval (anchor transmission → target transmission) the resolver produces: the alignment horizon (below), the earliest application
tick of every outstanding influence = the first intermediate tick whose end was captured **after** the influence left the server (TCP order,
same connection), the correction relation, and `Evidence{clientTickEnds, markersAnswered, rtt window, coverageDegraded}`. It never
translates RTT into ticks; the window is the set of enumerated ticks, and `later` (still pending after the target) is always a branch until
expiry.

## VELOCITY LIFECYCLE

1. **Registration** — an outbound `ClientboundSetEntityMotionPacket` whose entity id equals the session's own (`ControlChange("SELF_ENTITY")`
   from the platform at attach; **P7-F1**: another entity's motion is not this player's influence; an unknown self id attributes nothing and
   is counted) becomes `ExternalMotionSent(wire, SET, vector, "velocity")` with id `m<wire>`. The vector is what the **client decodes**:
   the packet uses the 26.2 low-precision codec `LpVec3` (15 data bits per component over an integer scale `ceil(max|c|)`), so a server
   `(0.3, 0.5, 0.2)` reaches the client as `(0.3000061, 0.5000305, 0.2000244)` (**P7-F8**, found live; `LowPrecisionVector` reproduces the
   codec bit for bit — 200 000 random vectors against the jar's encoder/decoder).
2. **Pending** — the influence is in every later request's `pendingInfluences` until applied or expired; the ledger counts it.
3. **Branching** — for each interval the resolver enumerates apply@j for every admissible tick j ≥ earliest, plus `later`; several pending
   influences apply in wire order (the product is bounded by the budget; > 8 outstanding → `UNSUPPORTED` overflow, never a guess).
4. **Application** — Phase 6 receives the branch as `ExternalMotionBranch` (SET replaces, ADD adds before the tick's movement) and evaluates
   the legal inputs under it; the union frontier keeps each surviving node with its own remaining pending set.
5. **Expiry** — after `applicationHorizonNanos` (3 s) relative to the interval's evaluation time the influence is `EXPIRED`: evidence
   unavailable, explicitly **not** "not applied" (`TimingPropertyTest.timeoutsNeverCreateCertainty`).
6. **Supersession / composition** — a later SET replaces the client's velocity whatever the earlier one did (live: three velocities 300 ms
   apart, each applied at its own tick, none lost or double-consumed); several `setVelocity` calls in one server tick coalesce into one packet
   (`hurtMarked`), which is what the client and the ledger both see.

## EXPLOSION TIMING

`ClientboundExplodePacket` with a player knockback → `ExternalMotionSent(ADD, vector, "explosion")`; the vector uses the full-precision codec
(not quantised). Live (creeper, radius 3, 1.5 blocks away): the ADD applied at tick 3 of the interval, and the explosion's own damage knockback
arrived one tick later as a velocity SET — both attributed, 26 transmissions explained.

## TELEPORT LIFECYCLE

`TeleportSent(id, wire, position, rotation, delta, relativeMask)` → `PendingTeleport`; `TeleportConfirmed(id)` confirms the matching pending
correction; confirming a later one first marks the older **superseded**; a confirm for an unknown/superseded id is **stale** (counted,
resolves nothing); a failed write is recorded (the client never received it). Confirmed corrections stay listed (bounded, 8) because an
earlier interval may still need them. Timing: a target transmitted before the confirm in capture order is **PRE_APPLICATION** (ordinary
movement under the outstanding correction); the first position after the confirm is the **APPLICATION_POINT** (horizon 0, alignment
`TELEPORT_CONFIRM`, no physics bridges the discontinuity; the post state takes position/rotation from the correction with relative flags
resolved against the pre state, velocity from the delta, on-ground branched both ways); movement after it is ordinary again with
`anchorInTick=false` (the confirm's PosRot is sent between ticks, so the next horizon is tick-end count + 1). An unconfirmed correction past
its window → `TELEPORT_UNRESOLVED`. Verified live: 4 corrections (join + 3 harness teleports), every application point resolved, the same
tick's regular packet and rotation-only packets explained, walking immediately after a teleport explained (75–96 transmissions).

## MOVEMENT ALIGNMENT

* **TICK_END_EXACT** — horizon = number of client tick ends strictly inside the interval (for an in-tick anchor) or count + 1 (anchor between
  ticks, i.e. a correction's confirm); rotation-only packets give their tick its yaw; this is the normal case on the pinned client (every
  live interval resolved this way).
* **BOUNDED_FALLBACK** — no tick-end capability, zero tick ends, or loss-class coverage break: horizons 1..3 enumerated, `TIMING_GAP` when
  two transmissions arrive without a tick end between them, `complete=false` throughout.
* **TELEPORT_CONFIRM** — the application point (above).
* Beyond 3 ticks the interval is either a **silent closure** (below) or `UNSUPPORTED_HORIZON` (never a narrower guess).

## SILENT TICKS

A standing client transmits nothing for up to 20 ticks (send threshold / 20-tick reminder). Leading silent ticks are covered by the exact
single-tick Phase 6 closure: every frontier state is stepped with all legal inputs under the send-threshold constraint until a superset fixed
point (bounded by `MAX_CLOSURE_TICKS = 20` and the budget); the last ≤ 3 ticks are the observation list. Influences that could apply inside
the closure are branched at the closure boundary only: the resolver marks such intervals `complete=false` and says so ("application/yaw
inside the closure is not enumerated") — the live velocity-on-a-standing-player case still resolved because the influence's earliest tick
fell inside the enumerated tail; a velocity that lands mid-closure is a documented limitation (below). Incomplete Phase 6 states (unknown
entity neighbourhood, unobserved chunks) cannot close: `SEARCH_LIMIT`/`INCOMPLETE`, honest, observed live when wildlife was within 8 blocks.

## TIMING HYPOTHESES

`TimingHypothesis(id, horizon, ticks, branch, appliedInfluences, pendingInfluences, expiredInfluences, teleport, correction, alignment,
fences, rtt, preTicks, closureTicks)`; ids carry provenance (`h3`, `h3-m14950@3`, `h1-m84151@later`, `h0-teleport-4`, …); `describe()` is what the
diagnostic prints; `equivalentTo` drives deduplication (same horizon, ticks, motions per tick, correction relation, pending set, closure).
`TimingResolution(status, hypotheses, evidence, work, provenance, notes)` is immutable and severity-ordered
(`SESSION_DISCONTINUITY < WORLD_DISCONTINUITY < TELEPORT_UNRESOLVED < UNSUPPORTED < UNSUPPORTED_HORIZON < SEARCH_LIMIT < TIMING_GAP <
EXTERNAL_MOTION_UNRESOLVED < EXPIRED < NO_ACK_EVIDENCE < INSUFFICIENT_RTT < RESOLVED_AMBIGUOUS < RESOLVED_UNIQUE`).

## PHASE 6 INTEGRATION

`TimingReconstruction.run(template, frontier, env, perRequest)`: for each resolved hypothesis it groups frontier nodes by pending set,
shares one `InputReconstructor` call between hypotheses with identical tick observations (their influence branches ride as
`ExternalMotionBranch`es, chunked ≤ `ReconstructionRequest.MAX_BRANCHES = 8`), collects each branch's EXACT matching states as new nodes
carrying their remaining pending influences, and unions them (`(state, pending)` dedup). Teleport application points are constructed, not
simulated. `Result{status, resolutions, steps, next, complete, work, notes}` reports per-hypothesis Phase 6 results; `explaining()` are the
steps with ≥ 1 legal history; `targetInTick()` tells the caller how to anchor the next interval. Phase 6's own solver, budget, input domain
and observation profile are untouched, with one physics-envelope fix found through this integration (**P7-F2**: `AbstractTransfer`'s landing
case unions the unchanged position when the collided move may be negligible under `Entity.move`'s `MOVE_EPSILON_SQR`; all Phase 6 pinned
fixtures identical).

## BRANCH DEDUPLICATION

Equivalent hypotheses are merged before reconstruction (`TimingResolverTest.equivalentHypothesesDeduplicateAndTheSameTraceResolvesIdentically`);
identical `(state, pending)` nodes are merged in the frontier; hypotheses with identical tick observations share one Phase 6 request; the
diagnostic folds consecutive uniquely-resolved rest re-sends into one line (P7-F9). Ordering is deterministic everywhere (lists, insertion-ordered
maps; `TimingPropertyTest.theSameTraceResolvesIdentically`, `TimingFixtureTest`).

## SEARCH BOUNDS

`TimingBudget.DEFAULT = (maxHorizon 3, maxHypotheses 16, maxInfluences 8, maxFencesConsulted 64, maxReconstructions 16, maxTotalEvaluations
16 384, maxFrontierNodes 64)` plus Phase 6's own `SearchBudget.DEFAULT` per request and `MAX_CLOSURE_TICKS = 20`. Exhaustion is reported
(`SEARCH_LIMIT`, `complete=false`) and never pruned by score — there is no beam search, no heuristic ordering that drops branches
(`TimingPropertyTest.searchLimitsNeverClaimCompleteAndStayBounded`, fuzz `maxHypotheses = 9` observed, evaluations bounded per case).

## SESSION ISOLATION

Everything is keyed by `SessionKey(player, epoch)`: a reconnect creates a new `TimingLedger`, a new marker-id epoch byte, a new estimator;
`apply` rejects events for another key, closed ledgers and non-monotonic sequences; the tracer flags a closed session. Live: after
disconnect/reconnect the new session showed 0 influences, the join correction only, fresh RTT samples, 0 pending markers,
`lateOrUnknownAcks = 0`; `TimingMarkerSchedulingTest.aLatePongOfAReplacedSessionIsLateOrUnknownAndNeverASampleOfTheNewOne` covers the late
pong of the replaced session.

## WORLD EPOCH SAFETY

`Discontinuity(newWorldEpoch)` (respawn/dimension change) ends every interval: a request whose events span it resolves `WORLD_DISCONTINUITY`
(`TimingResolverTest.aWorldDiscontinuityInsideTheIntervalIsNeverCrossed`, fixture `world_discontinuity`); the request carries the world epoch
its frontier belongs to, and the Phase 6 environment is built from the arm-time world view (as in Phase 6).

## CAPTURE-ONLY LIMITATIONS

Unchanged from Phase 1–6: the pipeline observes, it never blocks, rewrites or injects gameplay packets. The Phase 7 marker is the one new
outbound write and it is diagnostic traffic the server ignores (`handlePong` no-op); it passes the ordinary outbound capture, and a port
failure is counted, never retried in a loop. The permanent correlation limitation of real sessions (`PIPELINE_UNCOVERED` from the client's
brand payload; `EXPIRED`/`OUTCOME_UNKNOWN` outcomes) is an operational-latency-unrelated fact: it keeps the timeline's records uncertain as
before and does **not** degrade timing alignment (P7-F4). Coverage breaks for timing come only from loss-class latch reasons (the world's
filter, P3-F3), one per health generation.

## REPLAY

`TimingFixtures` (19 cases: stable/jittered/outlier RTT walks, velocity immediate/1-tick/2-tick late, explosion, multiple velocities, a silent
client receiving a velocity, teleport immediate/delayed confirm, supersession, the 20-tick reminder closure, rotation-only ticks, the
no-tick-end fallback, a world discontinuity, unresolved motion, influence overflow, an expired influence) are synthetic ordered traces
walked through `TimingResolver + TimingReconstruction`; `timing-fixtures/timing-fixtures.json` pins statuses, hypothesis ids, truth retention
and a digest per case; `TimingFixtureTest` replays them and fails on any difference. The Phase 2 timeline replay mapper is unchanged (timing
events are not part of the capsule schema; the ledger is rebuilt from observations).

## MEMORY

`memory-results.json` (forced GC + used-heap diff, 3 trials, median; estimates, not capacity): empty `TimingLedger` 1.4 KB, ledger at the ring
bound 6.3 KB, ledger with marker/teleport caps full 10.4 KB, `RttEstimator` 360 B. Per-resolution garbage (thread allocation counters):
timing only 2.3–29 KB, timing + Phase 6 + Phase 5 0.15–18 MB depending on the family (the closure families dominate: Phase 6 hotspot, not
Phase 7). Retained if a consumer kept 100 results: 130 KB (timing only) / 244 KB (end to end). Per session retained: one ledger (ring 2048
records / 256 KiB / 3 s, 32 pending markers + 64 tombstones, 8 corrections, 32 RTT samples) plus, **only while a trace is armed**, the
diagnostic capture of ≤ 4096 non-tick events (P7-F5; cleared on cancel; ≤ 4 traces). Metrics are `LongAdder`s with fixed labels; nothing is
keyed by UUID beyond the session map.

## PERFORMANCE

`benchmarks.json` — JMH 1.37, Temurin 25, 3 forks, 5×1 s warm-up, 10×1 s measurement, `-prof gc`, one host, descriptive:

| Kernel | ns/op | alloc B/op | | Kernel | ns/op | alloc B/op |
|---|---:|---:|---|---|---:|---:|
| rttSampleUpdate | 2.8 | 0 | | timingOnlyOneBranch | 281 | 2 104 |
| ackRegisterAndResolve | 30.9 | 152 | | timingOnlyTwoBranch | 947 | 4 736 |
| ledgerVelocityRegister | 91 | 480 | | timingOnlyFourBranch | 1 430 | 7 853 |
| ledgerTeleportRegisterAndConfirm | 198 | 1 272 | | timingOnlyTeleport | 115 | 952 |
| ledgerTickEnd | 62 | 424 | | timingOnlyGap2 / Gap3 | 370 / 344 | 2 208 / 2 368 |
| | | | | timingOnlyWorstBounded | 13 345 | 57 048 |

Timing + Phase 6 + Phase 5 end to end: one branch 27 µs (86 KB), velocity two branches 52 µs (180 KB), velocity four branches 73 µs
(277 KB), teleport application point 0.8 µs (2.9 KB), 2-tick gap 469 µs (1.0 MB), 3-tick gap 1.75 ms (5.9 MB), worst bounded 1.53 ms
(6.8 MB); 30 measurements per kernel. The timing layer is about 1 % of the end-to-end cost; the rest is Phase 6's enumeration over multi-tick
gaps (a known Phase 6 hotspot). One host, no capacity claim.

## REAL CLIENT QUALIFICATION

Owner session (vanilla 26.2 client `Emojiado`, local Paper 26.2-124 on 127.0.0.1:25576, both on one host). Full log:
`docs/phase7-artifacts/real-client-evidence.log` (RCON blocks, secret-scanned; the file is git-ignored by `*.log` and must be force-added at
baseline time like Phase 6's). The session ran through five jars because it found defects (P7-F3…P7-F9 below); the **final jar
`5ee0324cac4d9314`** repeated every test that depends on timing, ledger, resolver or model code:

| Test | Jar | Server event(s) | Observed | Branches | Actual behaviour included | Status |
|---|---|---|---|---|---|---|
| baseline: tick ends, keep-alive 1 Hz + ping markers, RTT | all | — | 20 tick ends/s, every marker answered (e.g. 63/63, 1655/1655), RTT 0.15–0.7 ms smoothed, `coverageBreaks=0` | — | — | PASS |
| F2 velocity while standing | final | `setVelocity(0.3,0.5,0.2)` → LP `(0.3000061,0.5000305,0.2000244)` | jump + flight + landing, 25 transmissions | 2 (apply@3 / later) | YES (apply@3) | QUALIFYING |
| T4b / F6 velocity while walking | bffa77b7 / final | same vector mid-walk | 128 / 183 transmissions | 2 at the interval (apply@1 / later) | YES (apply@1) | QUALIFYING |
| T5c teleport while standing | bffa77b7 | Bukkit teleport (+5,0,+3) | confirm 4 seq later, PosRot = application point, same-tick regular packet, rot-only | 1 (application point) | YES | QUALIFYING |
| T6 / F3b teleport then immediate walk | bffa77b7 / final | teleport, W held after | 96 / 75 transmissions | 1 + walking hypotheses | YES | QUALIFYING |
| T7 three `setVelocity` in one server tick | bffa77b7 | coalesced by the server into one packet (last vector) | one influence, 27 transmissions | 2 | YES (apply@3) | QUALIFYING (server coalescing recorded) |
| T7b / F4b three velocities 300 ms apart | bffa77b7 / final | three SETs | 51 / 52 transmissions | 2 per interval | YES: applied@step1/tick3, step13/tick1, step24/tick1 | QUALIFYING (none lost / double-consumed) |
| T8b knockback (`/damage … at`) | bffa77b7 | knockback velocity SET `(0.3999878,0.3608008,0)` | 23 transmissions | 2 | YES (apply@1) | QUALIFYING |
| T9c explosion (ignited creeper, radius 3) | bffa77b7 | ExplodePacket ADD `(-0.5095560,0.5503205,0)` then damage knockback SET | 26 transmissions | 2 + 2 | YES (ADD apply@3, SET apply@1 next tick) | QUALIFYING |
| T10 / F5 disconnect → reconnect | bffa77b7 / final | — | session 2: fresh ledger, join correction only, 0 influences, 0 pending, `lateOrUnknownAcks=0`; old trace flagged CLOSED | — | — | PASS |
| Paper smoke | final | clean `stop` | `Disabling Papea2AC`, all dimensions saved, 0 exceptions/errors in the run log | — | — | PASS |

Also recorded, honestly: F3 (first attempt) teleported the player into a wall — the client's in-block push-out (0.1/tick, `Player.moveTowardsClosestSpace`)
is outside the Phase 5 model, so the step after the application point was `NO_MODELLED_CANDIDATE` (not a verdict; known limitation); two
attempts armed while wildlife was within 8 blocks (`entitiesNearby=UNKNOWN`) could not close the silent interval (`SEARCH_LIMIT`, Phase 6
incompleteness); the world's difficulty (Peaceful) removed the first creeper; a ghast fireball never detonated (no gravity). The knockback
and explosion tests ran in survival with Resistance V and `mobGriefing=false`, restored afterwards.

## KNOWN LIMITATIONS

1. **Localhost is not the internet.** RTT envelopes, jitter and outlier behaviour under real latency are covered by deterministic synthetic
   tests only; the live session validates protocol ordering, ACK behaviour, application sequencing and integration. Samples of 30–100 ms
   observed live were host contention (diagnostic reports on the main thread, joins), not network.
2. **Silent closure with influences inside it** is `complete=false`: an influence whose earliest tick lies inside the closure (a standing
   client that receives a velocity more than 3 ticks before its next transmission) is branched at the closure boundary only. On the pinned
   client the first post-application transmission follows within the enumerated tail (every live case resolved), but a generic client could
   fall to `NO_MODELLED_CANDIDATE` here — a coverage statement, never a verdict.
3. **Post-correction states the model does not cover**: a correction into a block (client push-out), or into an unobserved/entity-unknown
   neighbourhood, yields `NO_MODELLED_CANDIDATE` / `SEARCH_LIMIT` (Phase 5/6 scope).
4. **`pongBoundsApplication` is not claimed**: marker answers never narrow an application frontier; the diagnostic only reports that they
   were present.
5. **Sneak / item-use ordering** relative to the tick end is not modelled beyond Phase 6's input domain; rotation-only alignments are limited
   to 3 pending rotations per interval.
6. **Diagnostic bounds**: 4 traces, 600 steps, 120 lines, 4096 captured events per armed trace; the report walks the whole capture on the
   command thread (a long walk is a visible main-thread cost — diagnostic use only).
7. **Server coalescing**: several `setVelocity` calls in one server tick reach the client as one packet; Phase 7 sees what the client sees.

## DEFERRED TO PHASE 8+

Any judgement on the hypothesis set; consumption of RTT windows by a decision layer; marker-bounded application frontiers (would need a
qualified client fact, N1); network-condition qualification with real latency/jitter injection; closure branching of influences inside
silent intervals if a consumer needs completeness there; the in-block push-out kernel (Phase 5 scope decision).

## FORWARD DEFECTS (P7-F<n>)

| Id | Found by | Defect | Fix | Regression |
|---|---|---|---|---|
| P7-F1 | real client | every velocity packet was attributed to the player | attribute only `entityId == self` (`SELF_ENTITY` control at attach) | `TimingObservationMapperTest`, `TimingMarkerSchedulingTest`, `NativeAckInventoryTest` |
| P7-F2 | teleport round trip | Phase 6 landing envelope excluded a negligible collided move (`MOVE_EPSILON_SQR`) | union the unchanged position | `AbstractTransferTest.aNegligibleCollidedMoveOnLandingIsInsideTheEnvelope` |
| P7-F3 | bytecode | keep-alive cadence documented as 15 s; Paper sends one per second, limit 30 s | `TimingProfile.PAPER_26_2` + docs | `NativeAckInventoryTest` (bytecode) |
| P7-F4 | real client | benign `PIPELINE_UNCOVERED`/correlation latch reasons broke timing coverage → every real session fell to the bounded fallback | loss-class reasons only, one break per health generation | `TimingCoverageTest` |
| P7-F5 | real client | the diagnostic published the last 512 ring events (3 s horizon): influence and first transmissions gone before a human could report | bounded 4096-event capture while armed | `TimingLedgerTest` |
| P7-F6 | real client | every outbound write outcome became a ledger event (hundreds/s): ring churn, capture full in ~12 s | record write outcomes only for tracked markers/corrections | `TimingLedgerTest` |
| P7-F7 | real client | influence expiry judged against the report clock: a 6 s old velocity was `EXPIRED` for the interval that applied it | evaluation time = the interval's close (`TimingRequest.nowNanos` semantics) | `TimingTracerTest.aVelocityFlight…` (synthetic clock far in the past) |
| P7-F8 | real client | the velocity vector was the server's doubles; the client applies the `LpVec3`-quantised vector → the true branch had no candidate | `LowPrecisionVector` (version) used by both mappers | `NativeLowPrecisionVectorTest` (200 000 vectors bit-exact + the packet's own codec), `TimingObservationMapperTest` |
| P7-F9 | real client | tracer: 128-step cap eaten by rest re-sends; closed session not flagged | fold rest re-sends, `MAX_STEPS = 600`, CLOSED note | `TimingTracerTest.restResendsAreFoldedAndAClosedSessionIsFlagged` |

No forward defect touched Phase 0 documents or frozen baselines.

## ACCEPTANCE MATRIX

| Criterion | Result |
|---|---|
| Hard phase boundary (no verdict vocabulary, VL, setback, punish, alert) | PASS (architecture + vocabulary tests, diagnostic wording) |
| Owner module per DAG | PASS |
| Monotonic time domains separated | PASS |
| RTT model bounded, never truth, never halved | PASS |
| ACK inventory of the 26.2 protocol, no legacy transactions | PASS (`ack-inventory.json`) |
| ACK semantics documented and implemented | PASS |
| Session-safe correlation, bounded ACK state | PASS |
| Velocity lifecycle; multiple velocities; SET/ADD/UNRESOLVED | PASS |
| Explosion timing | PASS (synthetic + live creeper) |
| Teleport timing, confirmation, supersession, stale confirm | PASS |
| World-epoch discontinuity | PASS |
| Movement alignment 1–3 ticks exact/fallback, unsupported beyond | PASS |
| Silent ticks / threshold-suppressed movement | PASS (closure; limitation 2 documented) |
| Jitter, TCP no reordering | PASS |
| `TimingResolution resolve(TimingRequest)` immutable API, rich statuses, provenance | PASS |
| Phase 6 integration without duplication; branch union; no false exclusion through timing | PASS (velocity 1700 cases / 13 107 transmissions, teleport 500/500, fuzz 1981 cases / 13 626 transmissions, 0 truth lost) |
| Hypothesis dedup; strict search bounds; no beam search | PASS |
| Memory bounds; bounded metrics | PASS |
| Session replacement; world epoch replacement tests | PASS |
| CAPTURE-ONLY preserved | PASS |
| Property tests; fuzz; 40-item core matrix | PASS (matrix mapping in `verification.json`) |
| Deterministic replay fixtures | PASS (19 cases pinned) |
| `/papea2ac timing <player>` diagnostic | PASS |
| JMH for the listed kernels; timing-only vs end-to-end cost | PASS (`benchmarks.json`) |
| Native / process testing where possible | PASS (`NativeAckInventoryTest`, `NativeLowPrecisionVectorTest`, live capture through `PaperCapture`) |
| Real-client qualification (velocity, teleport, knockback, explosion, multiple velocities, movement around teleport/velocity, reconnect) | PASS (final jar; see table) |
| Network-condition qualification (latency/jitter injection) | EXTERNAL QUALIFICATION REQUIRED (deterministic synthetic timing tests only; localhost caveat) |
| Phase 1–6 regression preserved | PASS (see `verification.json`) |
| Phase 8 verdict logic | NOT APPLICABLE BY SPEC (none exists) |
