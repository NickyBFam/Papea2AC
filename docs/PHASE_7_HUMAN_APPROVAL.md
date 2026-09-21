# Phase 7 human approval record

Date: 2026-09-21.

The human project owner reviewed `docs/PHASE_7_IMPLEMENTATION.md` (implementation report, RTT model, ACK inventory and
semantics, velocity/explosion/teleport lifecycles, movement alignment and silent closure, the Phase 6 adapter and branch union,
the no-false-timing-exclusion gates, JMH/memory qualification, Paper smoke, real-client timing qualification, the P7-F1..P7-F9
forward-correction record) together with its verification artifacts under `docs/phase7-artifacts/`, performed the real-client
timing qualification personally (in-game `Emojiado`: velocity standing/walking, teleport and post-teleport movement, three
successive velocities, knockback, explosion, disconnect/reconnect), and authorized creation of the Phase 7 implementation
baseline. This record is written by the implementation engineer at the owner's instruction; the engineer does not
self-approve. No independent Senior Code Review is claimed - the current project workflow does not require one.

```
PHASE:
Phase 7 — Latency / Acknowledgement / External-Motion Timing

STATUS:
HUMAN APPROVED FOR BASELINE

IMPLEMENTATION ENGINEER:
Claude

PARENT:
6d5485bb564450d79944f7799fa3f52029070e3f

PHASE 6 IMPLEMENTATION BASELINE:
ca6c16004247808fdb81203ecd1e041d3cfe7259

PHASE 5 IMPLEMENTATION BASELINE:
03a538c4496df92df16b7e0ce503bb61750847ae

PHASE 4 IMPLEMENTATION BASELINE:
1685e96519f2eaec3f5b28c12d687e8118809b64

PHASE 3 IMPLEMENTATION BASELINE:
92159ec5ef8524c5953ecaa22f663c1f556d52ac

PHASE 2 IMPLEMENTATION BASELINE:
f24824f9f5e009b835195f9d5b7ef7d5cb4ceb24

PHASE 1 IMPLEMENTATION BASELINE:
35c44f9bf7694363d284c646e881351d887cf0bf

BUILD:
PASS

TESTS:
test 598
nativeTest 137
unique 735
0 failed

PHASE 1:
127/127
0 gaps
CAPTURE-ONLY; authoritative downstream correlation UNSUPPORTED; schema 2 current / 1 readable
(the Phase 7 ping marker is diagnostic traffic the server ignores - never authoritative correlation)

PHASE 2 REGRESSION:
NONE (PlayerObservationMapper.map(Observation) preserved; the P7-F8 velocity representation is a forward change that no
frozen Phase 2 artifact contains)

PHASE 3 REGRESSION:
NONE (ObserverWorld: UNKNOWN != AIR, UNLOADED != EVICTED, epoch isolation)

PHASE 4 REGRESSION:
NONE (collision fixtures and shape coverage unchanged)

PHASE 5 REGRESSION:
NONE (physics native differential 88/88, 3105/3105 ticks bit-exact, drift 0.0; movement property coverage 1196/1196;
P6-F1 client-vector preprocessing present)

PHASE 6 REGRESSION:
NONE WITH ONE DOCUMENTED FORWARD CORRECTION (P7-F2 widens the AbstractTransfer landing envelope only; no legal set
shrinks; frozen Phase 6 artifacts unchanged)

RTT MODEL:
PASS (32-sample window, EWMA 1/8, jitter 1/4, outlier > 3*smoothed + 4*jitter + 20 ms, re-seed after 3 consecutive
outliers, reject <= 0 or > 60 s, stale 30 s; never a single ping integer, never halved)

ACK INVENTORY:
PASS (jar-derived: keep-alive, ping/pong marker, player_position / accept_teleportation, client_tick_end, nine relative
flags; no legacy transaction packet)

ACK SEMANTICS:
PASS (handlePong no-op, keep-alive 1 Hz / 30 s limit - P7-F3, confirm only when id == awaitingTeleport, re-send after
20 ticks, tick end on the main thread; marker answers are RTT/order evidence only, pongBoundsApplication NOT CLAIMED)

ACK STATE BOUNDS:
PASS (32 pending markers, 64 tombstones, 10 s expiry, <= 16 issued/pending, 8 corrections; late/duplicate/failed-write
answers counted, never RTT samples)

VELOCITY TIMING:
PASS (1500 tick-end-exact cases / 11 558 transmissions + 200 fallback cases retained; live standing, walking, knockback)

MULTIPLE VELOCITIES:
PASS (wire-order branches preserved; live three successive velocities each at its own tick)

EXPLOSION:
PASS (ADD/SET kept as separate ordered influences; live explosion impulse then damage knockback)

TELEPORT:
PASS (500/500 application points, 470 pre-application transmissions, 0 truth lost; confirmation is ordering evidence,
not a wall-clock application time)

TELEPORT SUPERSESSION:
PASS (an old confirmation never resolves a newer teleport; stale confirms counted)

MOVEMENT ALIGNMENT:
PASS (TICK_END_EXACT on the pinned client; bounded 1-3 fallback; UNSUPPORTED_HORIZON / TIMING_GAP beyond, never
truncated to three ticks and called complete)

SILENT TICKS:
PASS (exact bounded closure <= 20 ticks; influences inside a closure are boundary-branched with complete=false)

TIMING HYPOTHESES:
PASS (provenance ids, deterministic describe(), semantic-equivalence dedup, severity-ordered statuses, RttWindow never
applied as truth)

PHASE 6 INTEGRATION:
PASS (no InputReconstructor duplication; shared reconstruction per identical observations; <= 8 branches per request)

BRANCH UNION:
PASS (union of legal continuations; no residual/RTT/earliest/latest/"most likely" selection)

NO-FALSE-TIMING-EXCLUSION:
PASS (truth excluded 0)

TIMING ROUND-TRIP:
26733/26733

VELOCITY:
1700/1700

TELEPORT:
500/500

FUZZ:
1981/1981 (13 626 transmissions, 0 truth lost)

SEARCH BOUNDS:
PASS (TimingBudget 3 / 16 / 8 / 64 / 16 / 16384 / 64, Phase 6 SearchBudget, closure <= 20 ticks, SEARCH_LIMIT carries
complete=false, no beam search)

MEMORY:
PASS (TimingLedger ~1.4 KB empty / ~6.3 KB ring bound / ~10.4 KB caps full; RttEstimator ~360 B; ring 2048 records /
256 KiB / 3 s; armed capture <= 4096 events, <= 4 traces; no permanent UUID timing-history cache)

PERFORMANCE:
PASS (timing-only and end-to-end costs kept separate: RTT update 2.8 ns / 0 B, ACK resolve 30.9 ns / 152 B, 1 / 2 / 4
branches 281 ns / 947 ns / 1.43 us timing-only vs 27 / 52 / 73 us end-to-end, teleport 115 ns vs 0.8 us, 2-tick gap
~469 us / 1 MB, 3-tick gap ~1.75 ms / 5.9 MB, worst bounded ~13.3 us vs ~1.53 ms / 6.8 MB; Phase 6 gap enumeration is
the hotspot, not the timing layer; no player-capacity claim)

PAPER SMOKE:
PASS (final jar 5ee0324cac4d9314..., clean stop, 0 exceptions)

REAL CLIENT:
PASS (velocity standing, velocity walking, damage knockback, multiple successive velocities, explosion impulse, teleport,
post-teleport movement, reconnect/session replacement; actual behaviour inside the hypothesis union in every test; the
teleport-into-wall attempt exposed client push-out outside the Phase 5/6 model and was recorded as
NO_MODELLED_CANDIDATE, not a timing verdict)

NETWORK-CONDITION QUALIFICATION:
EXTERNAL QUALIFICATION REQUIRED — NOT A LOCAL BLOCKER (live qualification was localhost; synthetic deterministic
RTT/jitter testing PASS)

SESSION REPLACEMENT:
PASS (new SessionKey: fresh TimingLedger, no inherited RTT / pending markers / influences / teleport state; old trace
CLOSED)

DIAGNOSTIC BOUNDS:
PASS (/papea2ac timing: 4 traces, 600 steps, 120 report lines, 4096 captured events; no verdict language)

P7-F1..P7-F9:
DOCUMENTED / FIXED / VERIFIED / REQUALIFIED
(P7-F1 self-entity velocity attribution; P7-F2 Phase 6 landing envelope forward correction; P7-F3 keep-alive cadence;
P7-F4 loss-class-only timing coverage breaks; P7-F5 bounded armed capture; P7-F6 tracked-write outcomes only;
P7-F7 interval-close evaluation time; P7-F8 LpVec3 low-precision velocity vector; P7-F9 tracer folding / step cap /
CLOSED note)

PHASE 0 DRIFT:
NONE

PHASE 0–6 HISTORICAL BASELINES:
UNCHANGED

PHASE 8:
NOT STARTED
```
