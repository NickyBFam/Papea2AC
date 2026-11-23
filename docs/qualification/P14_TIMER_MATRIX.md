# Phase 14 — Timer — real-cheat VPS qualification matrix

Status: RESEARCH CATALOG (2026-10-03, revision 3: folds in `phase14-to-qualification-01`; contract **version 2**,
`CONTRACT_CHANGED-6.md`). **The timer detector is not qualified.** Phase 14 is prepared in `Papea2AC-wip-phase14` (PREPARATION, not READY:
integration waits for the Phase 13 receipt). Its contract is `handoffs/PHASE14_TIMER_CONTRACT_READY.md` version 2, snapshot
`phase14-snapshot-4.tar` (merged-stack diff `phase14-snapshot-4.on-p11s2-p12s2-p13s4.tracked.diff`); snapshots 1–3 are superseded and must
not be applied. Version 2 is a rename only: every Phase 14 class `Cadence*` is now `TickCadence*` (for example `TickCadenceLedger`,
`TickCadenceRunner`, `TickCadenceValidator`, `TickCadenceProfile`), because the `Cadence` prefix is reserved for Phase 17 click analysis.
Behaviour and results are unchanged (803/803, 0 INVALID in 2 000 legitimate scenarios). The `/papea2ac cadence` command, the profile id
`paper-26.2-124-cadence` and the `cadenceCorpus` task keep their names. Evidence for the facts below is in that worktree's
`docs/phase14-artifacts/` (`client-cadence-facts.json` from the client jar, `cadence-corpus-results.json` from the offline corpus).

Shared rules: [QUALIFICATION_PROTOCOL.md](QUALIFICATION_PROTOCOL.md).

Governing specs: ROADMAP (Phase 14 = monotonic timing, batching/stall allowances, ACK context, controlled rate-manipulation fixtures),
LATENCY_COMPENSATION ("never derive timer guilt from a single burst after a pause"), TESTING TC22 (reduced tick rate, freeze, 500 ms stall,
mount/ride/dismount, camera and respawn: **no timer guilt**).

## 1. The Phase 14 model

The detector does not average a rate. It uses a bound that no vanilla client can exceed.

- **Fences.** Phase 7's 1 Hz ping markers. The client answers each ping on its game thread, in wire order, and the pong and the tick ends go
  through one FIFO. Every tick end between pong A and pong B therefore belongs to a tick the client ran after processing ping A and before
  writing pong B.
- **Window.** The real-time span of those ticks is at most `answer(B) − encoderTap(ping A)`. Delay, jitter, stalls in either direction,
  bursts and server event-loop pauses can only make the span longer. **A burst after a pause is never a timer finding, by construction**
  (TC22).
- **Bound.** `floor(span × 1.0025 / 50 ms) + 22`. The client never ticks faster than every 50 ms, whatever the server tick rate. It runs at
  most 10 ticks per frame and drops owed ticks beyond that. The 22 is 2 catch-up frames, plus 1 residual tick, plus 1 tick for millisecond
  and float rounding. The 0.25 % clock tolerance is five times the maximum NTP slew.
- **Proven ticks.** `max(tick ends, movement packets − server packets that make the client send a movement outside its tick)` between the
  two pongs.
- **Windows.** Every pair of retained anchors, from 50 ms to 600 s. Anchors are dense for the newest 48 pongs, then thinned to 2 s spacing.
- **Classification, one per pong:** VALID (every window within the bound), INVALID (one window above it, with its proof: reason
  `TICK_END_EXCESS` or `MOVEMENT_EXCESS`), or UNCERTAIN (no window yet, a coverage break, or an unsupported profile). Consecutive INVALID
  pongs form one **exclusion run**.
- **Kind.** A model-conditional DeterministicExclusion (more ticks than any vanilla schedule allows in that span), not a
  BehavioralFeatureWindow.
- **No evidence, no effect.** Phase 14 feeds no Phase 9 evidence and no Phase 10 mitigation. The evidence projection, including TC20
  grouping, is an open owner decision. Until it is made, "0 positive evidence" holds trivially and tests nothing. **The qualification outcome
  to record is the cadence classification itself: INVALID count and exclusion runs.**

### Combat-time markers (owner decision, 2026-10-03)

Combat-time markers in the Phase 7 scheduler were approved and ship with Phase 12. After integration, pings are denser than 1 Hz during
combat, so fences and windows are denser too. The Timer thread moves its model to that cadence and Phase 7's timing gates are rerun. In
this matrix, after integration:

- the offline time-to-first-finding figures in §6 (measured at 1 Hz) are a ceiling during combat; report live figures separately for
  combat and non-combat stretches;
- the T-L and T-B cells run both outside and during combat (husk pen), and the minimum margin is reported for each;
- the telemetry ping counts (sent, answered, unknown pongs) are expected to rise during combat; record them per stretch.

## 2. Client facts that set the expectations

- The vanilla client follows the server tick rate **only below 20 Hz**. `/tick rate 40`, `/tick sprint` and any rate above 20 leave it at
  20 Hz. Frozen, it ticks every 50 ms, except during `/tick step`, when it ticks at the rate period.
- A legitimate client loses ticks whenever its frames stall, so there is **no lower bound**: a slow client is never a finding.
- A client that banks ticks must run more than 10 ticks in one frame to spend them faster than real time. Up to about 22 ticks (1.1 s) of
  bank per window fits inside the slack.
- Tick-end suppression alone is not a finding. It shows as `movementsBeyondTickEnds > 0` (diagnostic only).
- At join, the first classification is UNCERTAIN `NO_PRIOR_ANCHOR`. The first VALID needs two answered pings (about 2 s).

## 3. Setup

- Arena: the overworld platform; a 40-block running lane, a jump course, a ladder, water, ice; the Nether room for an epoch change.
- Server-side controls (log each command with its UTC time): `/tick rate <n>`, `/tick freeze`, `/tick unfreeze`, `/tick step <n>`,
  `/tick sprint <n>`.
- Trials last at least 60 s of continuous play, with a 10 s gap.
- Per-tester sheet for every trial: client FPS cap and FPS stability (frequent frame hitches hide slight timers, see T-S01).

## 4. Legitimate controls

Every tier-L cell: **0 INVALID pongs and 0 exclusion runs.** Also report the **minimum margin** (bound minus proven ticks) per trial. The
offline corpus (2 000 legitimate scenarios, 44 simulated hours, 42 million windows) had 0 INVALID and a closest margin of 12 ticks. A live
tier-L margin below 5 requires a new round before any tier-R trial.

| ID | Scenario | M/S | Latency | Expected (acceptance target) |
|---|---|---|---|---|
| T-L01 | Vanilla, standing still for 60 s (position reminders; tick ends continue) | S | N | 0 INVALID |
| T-L02 | Vanilla, continuous running, jumping, swimming, ladders and ice for 5 min | M | N | 0 INVALID |
| T-L03 | Client FPS capped at 10, then at 5 (catch-up ticks) | M | N | 0 INVALID |
| T-L04 | Client freeze 1–3 s (window drag or a heavy client action), then continue | M | N | 0 INVALID (a burst after a pause is never a finding) |
| T-L05 | `/tick rate 10`, `/tick rate 40`, `/tick sprint 200`, `/tick freeze` then `unfreeze`, `/tick step 20` | S/M | N | 0 INVALID **at every rate**; the client stays at 20 Hz for rates of 20 and above. Tick-rate signal recorded (UNOBSERVED / SETTLED / TRANSITION / OVERFLOW) |
| T-L06 | Server stall 500 ms and 2 s (documented workload) | M | N | 0 INVALID |
| T-L07 | Mount, ride, dismount a horse, boat and minecart; spectator camera; death and respawn; dimension change | M | N | 0 INVALID. Tick ends keep flowing, so none of these can raise the count; a coverage break starts a new segment |
| T-L08 | Remote tester on a high-jitter path (Wi-Fi or mobile hotspot), 10 min free play | M | N, J | 0 INVALID |
| T-L09 | Network stall then burst | M | S500, S2, S9 | 0 INVALID |
| T-L10 | 1 % packet loss (TCP retransmit bursts) | M | P1 | 0 INVALID |
| T-L11 | Lunar or other remote-tester client with its usual mods, 10 min | M | N | 0 INVALID |
| T-L12 | Long AFK (5 min) then sprint | S→M | N | 0 INVALID |
| T-L13 | Join and reconnect | S | N | First result UNCERTAIN `NO_PRIOR_ANCHOR`; first VALID after two answered pings (about 2 s) |

## 5. Blatant cheat settings (tier R)

| ID | Module / setting (descriptive) | M/S | Latency | Expected (acceptance target) |
|---|---|---|---|---|
| T-B01 | Timer 2.0× while running | M | N | Exclusion in every 60 s trial (offline median time to first INVALID about 2 s) |
| T-B02 | Timer 1.5× while running | M | N, D150 | Exclusion in every 60 s trial (offline median 2–4 s) |
| T-B03 | Timer 2.0× while standing still | S | N | Exclusion as T-B01: tick ends flow at 2× regardless of movement |
| T-B04 | Timer 0.5× (slow) | M | N | **Not a finding, by design** (no lower bound). Report `tickEnds` per second only |
| T-B05 | Blink: hold all outbound packets for 3–5 s, then release | M | N | No timer INVALID from the release burst (the span only grows). The held movement may still be a movement or teleport finding; report |

Blatant gate: detection in every 60 s trial for T-B01, T-B02 and T-B03 is expected. The protocol's 80 % floor stays as the minimum.

## 6. Subtle cheat settings (tier R) — measured, no pass threshold

| ID | Module / setting | M/S | Latency | What to measure (offline expectation) |
|---|---|---|---|---|
| T-S01 | Timer 1.05× continuous | M | N | Time to first INVALID (offline median about 25–35 s). Record FPS stability: with a 1 % frame-hitch chance, 1.05× was detected in 0 of 10 offline runs, because the client drops more ticks than the cheat adds |
| T-S02 | Timer 1.08–1.10× continuous | M | N, J | Time to first INVALID (offline median about 12–15 s at 1.1×); FPS stability |
| T-S03 | Timer 1.2× only on ground, or only while sprinting | M | N | Time to first INVALID; exclusion runs |
| T-S04 | "Balance" / "smart" timer: bank ticks while idle, spend them in bursts | M | N | **Record the bank length per trial.** Up to about 22 ticks (1.1 s) per window is invisible; larger banks are expected to be excluded (offline: a 2 s bank spent at once was excluded; the same 2 s freeze on a vanilla client was not) |
| T-S05 | "Tick base" style combat timer (short speed-ups before attacks) | M | N | As T-S04, with bank length and correlation to combat contexts |
| T-S06 | Fake lag (variable outbound hold of 100–500 ms) combined with Timer 1.05× | M | N | Time to first INVALID; holding only lengthens spans, so the rate is still bounded |
| T-S07 | Tick-end suppression or duplication, if the client offers it | M | N | Suppression alone: `movementsBeyondTickEnds > 0`, diagnostic, not a finding. Suppression combined with a timer: expected exclusion through `MOVEMENT_EXCESS` |

## 7. Latency-sensitive cells

Run T-L02, T-L08, T-L09, T-B02, T-S01, T-S02 and T-S04 under N, D150, D300, J, A, S500, S2, S9 and P1. Legitimate cells: 0 INVALID and the
minimum margin reported per profile. The offline detection medians held over LAN, 80 ms, jittery 250 ms and 9 s stall paths; live results
are compared with them. If the live data shows the bound is wrong, the fix is a new jar and a new round, never tuning on these trials.

## 8. False-positive scenarios to target deliberately

| ID | Why it is risky |
|---|---|
| T-FP01 | Burst after a pause: client freeze, server stall, network stall (excluded by construction; still tested) |
| T-FP02 | Server tick-rate changes, freeze, step and sprint |
| T-FP03 | Low or unstable client FPS (catch-up ticks, up to 10 per frame) |
| T-FP04 | TCP retransmits and Wi-Fi bunching |
| T-FP05 | Mode changes: mount, spectator camera, respawn, dimension change |
| T-FP06 | Long sessions: client and server clock drift (0.25 % tolerance) |
| T-FP07 | Join and reconnect: UNCERTAIN `NO_PRIOR_ANCHOR` until two pings are answered |
| T-FP08 | Server packets that make the client send a movement outside its tick (counted as `induced`) |

## 9. Acceptance targets

- Tier L: **0 INVALID pongs and 0 exclusion runs**; at least 10 player-hours in total and 30 min per latency profile; minimum margin
  reported per trial (≥ 5 required before tier R).
- Tier R blatant: exclusion in every T-B01, T-B02 and T-B03 trial.
- Tier R subtle: time to first INVALID, exclusion runs, bank length and FPS stability reported. No gate.
- Evidence and effects: Phase 14 feeds none. Record the cadence classification. 0 mitigation effects, as always.

## 10. Telemetry to capture

Available on the Phase 14 jar:

- `/papea2ac cadence <player>` (view): `tickEnds`, `movements`, `induced`, pings sent and answered, unknown pongs, anchors (thinned and
  expired), breaks, the tick-rate signal and its candidate states, VALID / UNCERTAIN / INVALID counts, exclusion runs, the largest excess with
  its window, and engine-wide counters.
- `/papea2ac cadence <player> recent`: exclusion runs, first uncertain results, coverage breaks.
- `/papea2ac cadence <player> windows`: the newest 32 classifications, each with its tightest window (span, proven ticks, bound, excess,
  apparent speed). Poll it often enough to keep up: every 10 s at 1 pong per second.
- There is no running balance. The per-window equivalent is the margin (bound minus proven ticks).

Per trial: the common commands; `/papea2ac cadence <p>` and `… windows` every 10 s; `papea2ac timing <p> view` every 10 s; the operator's
`/tick` and injected-stall log with UTC times; for tier R, the bank length (T-S04, T-S05) and the screen recording.

## 11. Run order

1. Offline, done by Phase 14: the cadence corpus (`cadence-corpus-results.json`) and the client facts.
2. VPS only on the **exact integrated jar** that contains Phase 14, which exists only after the Phase 13 receipt, and only after its offline
   qualification is complete: tier L (every TC22 condition) first, the margin check, then latency cells, then tier R.
3. Any defect: new jar, rerun the affected cells plus the full tier-L set.
