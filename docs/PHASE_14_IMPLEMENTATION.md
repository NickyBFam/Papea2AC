# Phase 14 — Timer (client tick cadence)

Status: **PREPARATION — independent components implemented and qualified offline; NOT integrated, NOT VPS-qualified, NOT ready for review.**
Base: Phase 10 receipt `59caec5a0b4bde0c13dadca1807c0b0f3f039a7d` (branch `wip/phase14`, uncommitted). Canonical integration waits for the
Phase 13 receipt. Pinned pair: Paper 26.2 build 124 / vanilla 26.2 client (jar SHA-256 `40896ee9f1e2bec3c934daac7e93d41e9e3d9c2f8ae0ca366d52ffbfd1afa290`).
Toolchain Java 25 (Temurin 25.0.4.1+1).

Phase 14 answers one question: **between two points of the client's own game-thread timeline that the server can locate, did the client run
more ticks than any vanilla client could have run in the real time those points span?** The answer is a model-conditional exclusion with
an exact proof, or a witness that every window holds, or UNCERTAIN. It is never a rate estimate, a score or a probability, and in this
preparation it feeds neither evidence nor mitigation.

## SCOPE

In scope (implemented and tested offline):

- the client timing model of the pinned 26.2 client, established from the client bytecode and pinned by a test;
- a sound upper bound on client ticks between game-thread fences that is immune to delay, jitter, stalls in either direction, bursts and
  server pauses;
- a per-session cadence ledger (`player`), a pure validator (`simulation`), a mapper and per-session runner (`core`), engine wiring and a
  `/papea2ac cadence` diagnostic (`platform-paper`);
- the **tick-rate signal**, the contract Phase 15 waits on: which server ticking states (rate, frozen) the client may currently be applying,
  with the exact point where a change is proven applied;
- an independent client/network/server simulator as the offline oracle; a 2 000-scenario legitimate corpus and a timer detection-latency table;
- input to the shared VPS Timer matrix (findings to qualification).

Out of scope here: Phase 9 evidence projection and TC20 grouping (an open owner decision), any mitigation, VPS qualification, JMH and the
end-to-end engine cost, replay-capsule projection of cadence state (the ledger is rebuilt from observations, like Phase 7's).

## CLIENT TIMING MODEL (bytecode facts)

Every fact below is asserted by `ClientCadenceFactsTest` against the client jar (`java.lang.classfile`) and recorded in
`docs/phase14-artifacts/client-cadence-facts.json`. The test runs where the jar exists and is skipped elsewhere.

| Fact | Where | Consequence |
|---|---|---|
| `new DeltaTracker.Timer(20.0f, …, this::getTickTargetMillis)` | `Minecraft.<init>` | nominal 50 ms per tick |
| `getTickTargetMillis(f) = runsNormally ? max(f, TickRateManager.millisecondsPerTick()) : f` | `Minecraft` | the client **never ticks faster than 20 Hz**; a slower server rate slows it; frozen = 50 ms |
| `advanceGameTime`: `residual += Δms / mspt`; `i = (int) residual`; `residual -= i`; return `i` | `DeltaTracker.Timer` | the whole owed integer is consumed |
| `runTick`: `advanceGameTime` → `processQueuedPackets` → `runAllTasks` → `for (j < min(10, i)) tick()` | `Minecraft` | ≤ 10 ticks per frame; owed ticks beyond 10 are **dropped**; packets are processed before the frame's ticks |
| `tick()` has exactly one caller (that loop) and ends with `send(ServerboundClientTickEndPacket.INSTANCE)` | `Minecraft` | one tick end per tick |
| `handlePing`: `ensureRunningOnSameThread` then `send(new ServerboundPongPacket(id))`; `handleKeepAlive`: no thread hop | `ClientCommonPacketListenerImpl` | **the pong is a game-thread fence; the keep-alive reply is not** (P14-F1, also P13-F1) |
| `handleMovePlayer`, `handleRotatePlayer`, `handleTeleportEntity`, `handleTickingState`, `handleSetEntityMotion`, `handleExplosion` all marshal to the game thread | `ClientPacketListener` | applied in wire order, before any later pong |
| `Connection.sendPacket`: off the event loop → `eventLoop().execute(…)` | `Connection` | one FIFO per writing thread: game-thread writes keep their order |
| Movement packets are constructed only in `LocalPlayer.tick`/`sendPosition` (one per tick at most) and in `handleMovePlayer`, `handleRotatePlayer`, `handleTeleportEntity` | whole jar scan | in-tick movements ≤ ticks; every other one answers a server packet |
| `TickRateManager.setTickRate(r)`: `tickrate = max(r, 1)`, `nanosecondsPerTick = (long)(1e9 / tickrate)`; `runsNormally = !frozen \|\| frozenTicksToRun > 0` | `TickRateManager` (shared class) | period rule of the tick-rate signal; `/tick step` runs normally while frozen |
| `ServerTickRateManager.updateJoiningPlayer` sends the ticking state and step packets | server jar | the signal starts at join |

## THE BOUND

Let ping A leave the encoder tap at `p_A` and pong B reach the pre-process tap at `q_B`. Every tick end that the capture stream places
between pong A and pong B was sent by a tick the client's game thread ran after it processed ping A and before it wrote pong B (one FIFO,
TCP order). That tick therefore happened in real time inside `(p_A, q_B)`. Delay, jitter, stalls, bursts and server event-loop pauses only
move `p_A` earlier or `q_B` later relative to the client, so they can only widen the window.

In that interval the client can run at most:

    maxTicks(span) = floor(span × (1 + 2 500 ppm) / 50 ms) + 22

- **50 ms** is the minimum tick period. A rate above 20 never speeds the client up, so the bound needs no tick-rate state (TC22 holds by
  construction for `/tick rate`, `/tick sprint`, `/tick freeze` and `/tick step`).
- **22**: frame k, which processes ping A, runs ≤ 10 catch-up ticks credited from before `p_A`. Frame k+1 runs ≤ 10 more, credited from frame
  k's start, which may also precede `p_A`. Every later frame starts after the fence and is credited only with time inside the window, plus a
  sub-tick residual (< 1). One more tick covers `Util.getMillis` truncation and float accumulation (a few hundredths of a tick over 600 s at
  any plausible frame rate).
- **2 500 ppm**: how much faster the client's monotonic clock may run than the server's. That is five times the maximum Linux NTP slew of
  500 ppm. It is a reviewed tolerance, not a measurement.

Proven ticks in the window = `max(tick ends, movement packets − induced)`, where *induced* counts the outbound packets that make the client
send a movement outside its tick (corrections, rotation corrections and every entity teleport, which over-counts) whose wire position
lies between ping A and ping B. A window whose proven ticks exceed `maxTicks` is an exclusion. `TickCadenceValidator` evaluates every retained
anchor pair ending at each new pong, with exact `long` arithmetic and no per-window allocation.

Assumptions the bound rests on (all client facts above, plus): the server's taps use one monotonic clock; TCP preserves order per
direction; the client is the pinned vanilla 26.2 client (a modded client that changes the frame loop is outside the profile). Classification
is UNCERTAIN, never INVALID, when the profile is unsupported, when no earlier anchor exists, or when a window exceeds 600 s.

## ARCHITECTURE (approved DAG, no new edges)

| Module | Phase 14 content |
|---|---|
| `anticheat-version` | `TickCadenceProfile` (`paper-26.2-124-cadence`: 50 ms, 10/frame, slack 22, 2 500 ppm, 600 s; `maxTicks`, `clientTickPeriod` → `TickPeriod`) |
| `anticheat-player` | `TickCadenceEvent` (TickEnd, Movement, PingSent, PingAnswered, WriteFailed, InducedSent, TickRate, CoverageBreak); `TickCadenceLedger` (pending pings, anchors, tick-rate signal, counters; owner-thread; immutable `View`) |
| `anticheat-simulation` | `TickCadenceValidator` (pure, stateless), `TickCadenceResult` (VALID / UNCERTAIN / INVALID, `Window` proof) |
| `anticheat-core` | `TickCadenceObservationMapper`, `TickCadenceRunner` (per session, shard-owned), `TickCadenceStatus`, `TickCadenceEngineStatus`; `PacketEngine.Session.cadence` |
| `anticheat-platform-paper` | `/papea2ac cadence <player> [view \| recent \| windows]` (prints the engine's words) |

`ArchitectureTest.cadenceStaysWithinThePhase14Boundary` enforces several rules. No enforcement, score or brand vocabulary appears; there is
no clock read, platform type, reflection, hash-order collection or ping halving. Only the validator builds a `TickCadenceResult`. The runner
imports neither evidence nor mitigation. The frozen Phase 7/8 engines (`TimingLedger`, `TimingResolver`, `MovementValidator`) do not
know the cadence types. The ledger imports only `model` and `player`. The Phase 6 simulation scan excludes `TickCadence*` (they have their own
boundary).

## PHASE 7 INTEGRATION

- **Same markers, no new traffic.** The fences are Phase 7's own 1 Hz ping markers plus the bracket marker after each influence or correction.
  The cadence ledger correlates pings and pongs itself (64 pending, no time expiry), so the frozen `TimingLedger`/`MarkerLedger` is untouched.
  A late pong is still a valid fence.
- **Every outbound ping counts, whoever sent it.** A pong resolves the oldest outstanding ping with its id. A plugin ping with a colliding
  id can only make a window start earlier.
- **Keep-alive is never a fence.** Its reply is written on the network thread.
- **Combat-time markers (the target cadence; user-approved, built by Phase 12 as D12-2, filed as phase12-to-phase14-01).** After every
  inbound attack, one marker per shard pass at least 50 ms apart for 3 s, at most 16 unanswered; the 1 Hz periodic and bracket markers are
  unchanged outside that window. Up to 20 pongs a second only add anchors: windows get shorter RTT slack, tick-rate changes settle sooner and
  a timer that runs while fighting is excluded sooner. Retention is time-thinned (dense newest 48, then ≥ 2 s apart, ≤ 352, ≤ 600 s), so
  memory is the same for both cadences. The validator costs about 1.2 µs per pong at full retention. The simulator models this cadence
  (combat episodes, 50 ms spacing, the 16-pending cap approximated from the path's base round trip).
- **Coverage breaks.** Each loss-class break that Phase 7 applies (`timingBreak`, one per health generation) also starts a new cadence
  segment. No window crosses it, and the tick-rate signal returns to UNOBSERVED.
- **P14-F1 (= P13-F1).** Phase 7's inventory says pongs are answered on the network thread. They are answered on the game thread. Phase 13
  filed the documentation finding (`phase13-to-coordinator-01`). Phase 14 builds on the corrected fact and pins it in its own test.

## TICK-RATE SIGNAL (the Phase 15 contract)

`TickCadenceLedger.TickRateSignal(status, candidates, complete, changeSequence, settledSequence)`, read via `TickCadenceRunner.tickRateSignal()`.
The client applies `ClientboundTickingStatePacket` on its game thread in wire order, so a change is **SETTLED** at the first pong whose ping
left after it on the wire. Until then it is **TRANSITION**, with the previous state followed by every unsettled change, in application
order. **UNOBSERVED** (none captured yet, or after a coverage break) and **OVERFLOW** (> 8 unsettled changes) are incomplete: assume no
rate. `TickCadenceProfile.clientTickPeriod(rate, frozen)` gives the period: `max(50 ms, (long)(1e9 / max(rate, 1)))` while running, and
`[50 ms, that]` when frozen (a `/tick step` runs normally). The published contract is `Papea2AC-coordination/handoffs/PHASE14_TIMER_CONTRACT_READY.md`.

## LAG / JITTER UNCERTAINTY

There is no allowance to tune. The window itself carries the uncertainty: each window's span includes the round trip at its start and any
stall inside it.

| Condition | Effect on the window | Offline evidence |
|---|---|---|
| Base delay, jitter (either direction) | span ≥ client time + RTT at the start | legitimate corpus: 0–400 ms base, 0–300 ms jitter |
| Server→client stall (P11-F10's 9.1 s) | ping A delayed: the client answers late, span includes the stall | `stallsInBothDirectionsAndServerPausesOnlyWidenTheWindows` |
| Client→server stall then burst | tick ends and pong B arrive together after the stall: `q_B` late | same, plus corpus |
| Server event-loop pause (GC) | captures and encoder taps wait: `p_A` before, `q_B` after | corpus: up to 2 × 2 s per scenario |
| Client freeze, low FPS, hitches | the client drops owed ticks (cap): fewer ticks, never more | `theVanillaFrameCapAndAFrozenProcessNeverProduceAnExcess` |
| Server tick rate / freeze / step / sprint | never above 20 Hz | `reducedRaisedOrFrozenServerTickRatesNeverProduceAnExcess`, corpus |
| Clock drift | within 2 500 ppm | corpus: ±500 ppm |

## OFFLINE QUALIFICATION

The oracle is `TickCadenceSimulator`, an independent discrete-event model transcribed from the bytecode facts, not from the validator. It
models the client frame loop with the float `DeltaTracker` arithmetic, the drifting client clock, the 10-tick cap, game-thread packet
processing at frame start, pongs, induced movements, ticking state, one write FIFO, both network directions in order with delay, jitter and
stalls, and server taps with event-loop pauses. It also has cheat knobs: a timer factor, ignoring the cap, suppressing tick ends, holding
pongs, and answering pings on the network thread.

`docs/phase14-artifacts/cadence-corpus-results.json` (task `:anticheat-testing:cadenceCorpus`, deterministic):

- **Legitimate:** 2 000 scenarios (657 of them with combat-time marker episodes), 44 simulated hours, about 2.7 M client ticks, 1.06 M
  anchors. **0 INVALID** (0 of 657 under combat-time markers). Closest margin 12 ticks.
- **Detection latency after the timer starts**, median of 10 seeds (all 10 detected in every cell):

| Path | 1.02× | 1.05× | 1.1× | 1.25× | 1.5× | 2× | 3× | 5× |
|---|---|---|---|---|---|---|---|---|
| LAN | 64.6 s | 25.2 s | 11.6 s | 4.7 s | 2.7 s | 1.6 s | 1.2 s | 0.7 s |
| 80 ms | 70.7 s | 25.7 s | 12.7 s | 5.6 s | 2.8 s | 1.7 s | 1.3 s | 0.8 s |
| 250 ms jittery | 91.0 s | 34.9 s | 15.2 s | 6.3 s | 4.0 s | 2.1 s | 1.6 s | 1.1 s |
| 9 s stalls | 77.8 s | 29.6 s | 12.7 s | 5.7 s | 2.8 s | 1.7 s | 1.3 s | 0.9 s |
| 80 ms, combat-time markers | 100.1 s | 24.9 s | 12.4 s | 4.9 s | 2.4 s | 1.3 s | 0.7 s | 0.4 s |

- **Frame hitches hide slight timers:** 1.05× detected in 10/10 runs at hitch probabilities of 0, 0.05 % and 0.2 % per frame, and in 0/10 at
  1 %, because a client that drops more ticks than the cheat adds is never ahead of real time. This is the bound working as designed, not
  a defect.
- **Tick-end suppression + 1.5× timer:** excluded through the movement count (`MOVEMENT_EXCESS`). Suppression alone is VALID.
- **Catch-up beyond the cap ("balance") after a 2 s freeze:** excluded. The same freeze on a vanilla client is not, and a 0.5 s bank is
  inside the slack.
- **Held pongs (3 s) or network-thread pongs with a 1.5× timer:** still excluded.
- **Cost** (one host, descriptive): validator 1.2 µs and 216 B per pong at full retention (347 anchors); ledger 33 ns and 232 B per tick end;
  about 32.8 KB retained per full ledger.

Tests (`:anticheat-testing:test`, 803 total, 0 failures): `TickCadenceValidatorTest` (7), `TickCadenceLedgerTest` (11), `TickCadenceRunnerTest` (3),
`TickCadenceSimulationTest` (9: 240 legitimate seeds, 60 legitimate combat-cadence seeds, the cheat cases), `ClientCadenceFactsTest` (5), `ArchitectureTest` (15, one new).

## VPS QUALIFICATION PLAN

The shared matrix is `Papea2AC-coordination/qualification/P14_TIMER_MATRIX.md`, owned by the qualification thread. Phase 14's input is
`findings/phase14-to-qualification-01-timer-matrix-contract-answers.md`. It covers the model, the answers to the WAITING cells, the
corrected expectations (the client never follows a rate above 20, 2× standing still is excluded, a bank under about 22 ticks is invisible)
and the telemetry. The round runs only on the exact integrated Phase 14 candidate jar, with its SHA-256 recorded, after integration. Tier L
comes first, including every TC22 condition, then the latency cells, then tier R. Each trial records `/papea2ac cadence <p>`,
`… windows` and `… recent`, the minimum margin per trial, and every `/tick` command with its time. Mitigation effects must be 0.

## KNOWN LIMITATIONS

1. **Lag banking up to the slack.** A cheat can spend about 22 banked ticks (about 1.1 s) per window faster than real time without
   exceeding the bound.
2. **Slight timers under unstable FPS** (see the hitch table). The bound is about ticks executed, not intent.
3. **Pong suppression.** A client that never answers pings produces no anchors and stays UNCERTAIN. That is visible as
   `pingsSent ≫ pingsAnswered`, but it is not a finding. Whether a missing answer stream should become a protocol finding is a Phase 20 / owner
   decision.
4. **Profile gate.** `profileSupported` is `true` for every session. A client-protocol check against the pinned profile belongs to
   integration.
5. **Entity teleports are over-counted as induced** (all entities, not only the player's removed vehicle). This only weakens the
   movement estimator. After Phase 11's teleport/sync split (P11-F2), only true teleports need counting.
6. **Vehicle movement packets** (`ServerboundMoveVehiclePacket`) are not used as tick evidence.
7. **Mid-session activation.** The tick-rate signal stays UNOBSERVED until a ticking-state packet arrives (Paper sends one at join).
8. **No evidence, no mitigation, no replay projection** in this preparation.

## OPEN DECISIONS (owner)

1. **Evidence.** Should a cadence INVALID become Phase 9 positive evidence (observe-only, DeterministicExclusion, TC20 grouping with
   movement findings from the same transmissions)? Default in this preparation: no, which matches Phase 11's precedent.
2. Whether pong suppression or tick-end suppression is a protocol finding (Phase 20 scope by default).

## INTEGRATION NOTES (after the Phase 13 receipt)

The delta adds 17 new files and touches 3 tracked files additively (`PacketEngine.java`, `Papea2AC.java`, `ArchitectureTest.java`, plus a
`cadenceCorpus` task in `anticheat-testing/build.gradle.kts`). Merge it with the Phase 11–13 hunks in those files. Re-run the full suite,
`nativeTest`, the corpus and a Paper smoke test on the integrated jar. Replace the broad entity-teleport induced count with Phase 11's
teleport-only payload. Gate `profileSupported` on the client protocol.

## FORWARD DEFECTS

| Id | Found by | Defect | Status |
|---|---|---|---|
| P14-F1 | client bytecode | Phase 7 documents the pong as a network-thread answer; it is a game-thread answer | documented (Phase 13 owns the wording fix as P13-F1); Phase 14 relies on the corrected fact and pins it in `ClientCadenceFactsTest` |

Development-time harness corrections (not product defects): the simulator first modelled a tick-end-suppressing client with two movement
packets per tick, and two test expectations were mis-derived (dense-anchor index, packet decoder classes in the movement-sender scan).
