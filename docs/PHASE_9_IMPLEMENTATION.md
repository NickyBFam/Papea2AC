# Phase 9 — Evidence Engine

Status: **READY FOR HUMAN REVIEW** (not committed, not baselined, no Phase 10 work).
The engine itself passes every gate; the live legitimate-course gate found upstream defects during this qualification: P9-F1 (sprint modifier),
P9-F2 (block-state disagreement), P9-F3 (sneak input timing and the resting-sneak anchor), P9-F4 (frozen ticks before the client has loaded the level) and
P9-F5 (animals/mobs push the local player on the 26.2 client, and bundled spawns never reached the tracker) are root-caused and fixed. The final-jar live requalification
(VPS, two real players, jar `297134a6…`) passed: 22 183 transitions, 0 INVALID, 0 positive evidence.
Base: Phase 8 baseline `0333d13ee8346c3909122405a680c71377c48744`, receipt `3cc327df6edd8b7edf8fcb352e2f21e17c7cd579` (HEAD, unchanged).
Pinned pair: Paper 26.2 build 124 (`.phase1-runtime/versions/26.2/paper-26.2.jar`) / vanilla 26.2 client. Toolchain Java 25.

Phase 9 preserves what completed Phase 8 results prove over time, and nothing else. A Phase 8 `INVALID` with its complete exhaustion proof
intact becomes exactly one immutable **EvidenceEvent** (EVIDENCE_MODEL kind *DeterministicExclusion*); `VALID` and `UNCERTAIN` add coverage and
gap information and never positive evidence; a session-bounded **EvidenceLedger** deduplicates, groups by shared cause, windows, segments by
world epoch and bounds everything; an immutable **EvidenceSnapshot** is the Phase 10 input. Phase 9 answers "how many complete unexplained
transitions, over what span, independent or duplicated, which physical dimensions, how large the proven excesses, with what coverage between
them, how recent, supported by which proofs". It never answers "should we act" — Phase 10 owns that; one INVALID is one unexplained transition.

## SCOPE

In scope (implemented, tested, real-client qualified):

* `anticheat-evidence`: the fixed vocabulary registries (`EvidenceVocabulary`: classification, invalid codes, uncertain codes, categories,
  bound kinds, rejections — name for name the Phase 8 vocabulary), the validation projection `ValidationRecord`, the proof-gated
  `EvidenceEvent` with its deterministic digest, the session `EvidenceLedger`, the `EvidenceBounds` and the immutable `EvidenceSnapshot`;
* `anticheat-core`: `ValidationEvidenceMapper` (name-for-name projection of `MovementValidationResult`), `EvidenceRunner` (per session: a LIVE
  ledger fed once per completed live result and a SIDE ledger fed by the Phase 8 controlled injection, failure containment, bounded rings,
  a scheduled side re-delivery for the idempotency demonstration), `EvidenceStatus` / `EvidenceEngineStatus`, `PacketEngine` composition;
* `anticheat-platform-paper`: `/papea2ac evidence <player> [view | recent | side | reingest]` and the `evidence …` status line (display only);
* tests: event gate (every corrupted-proof variant), ledger semantics (the six ordering patterns, dedup, out-of-order, groups, window, epoch,
  session, bounds, neutrality), property/metamorphic tests over seeded streams, the Phase 8 corpora through evidence (legitimate
  32 314 → 0 events; impossible 3 290 → 3 290 events; every record twice → still 3 290), runner and engine integration, architecture
  boundary, JMH, memory harness, deterministic fixtures, real-client qualification (`docs/phase9-artifacts/real-client-evidence.log`).

Out of scope (Phase 10+): mitigation intents, setbacks, teleports, alerts as enforcement, kicks, bans, punishments, policy thresholds, any
"cheater" boolean, calibrated probabilities; also the replay-capsule durability measurements (TESTING TC25) and the global anomaly
observation that the roadmap lists for the evidence foundation — recorded under DEFERRED, both are preconditions Phase 10 must meet before
any mitigation is enabled.

## MODULE OWNERSHIP

| Role | Module | Content |
|---|---|---|
| EVIDENCE OWNER | `anticheat-evidence` | vocabulary, `ValidationRecord`, `EvidenceEvent`, `EvidenceLedger`, `EvidenceBounds`, `EvidenceSnapshot` |
| INPUT | `anticheat-simulation` (Phase 8 result, unchanged) via `anticheat-core` | `ValidationEvidenceMapper` projects `MovementValidationResult` → `ValidationRecord` |
| OUTPUT | `anticheat-core` → `anticheat-platform-paper` | `EvidenceStatus` (primitives/strings) for display; `EvidenceSnapshot` is the Phase 10 boundary |
| DEPENDENCY DIRECTION | evidence → model (build also allows replay-schema); core → evidence + simulation; platform → core | no cycle; evidence never sees simulation, physics, player, world, core, Bukkit/NMS/Netty (architecture test) |

## PHASE BOUNDARY

`ArchitectureTest.evidenceStaysWithinThePhase9Boundary`: no setback / teleport / kick / ban / punish / alert / violationLevel / strike /
cheater / probability / confidence / recommend / decay / threshold vocabulary in any Phase 9 file; no wall clock, reflection, hash-order
collection or platform type; the evidence module imports only `model` (and `evidence` itself); `EvidenceEvent.from` is the only factory and
is guarded by the proof gate; the snapshot has no verdict field; core and the platform never build evidence by hand; the platform never
imports the evidence module; `MovementValidator` does not know Phase 9. Production code contains no Phase 10 action (searched).

## EVIDENCE EVENT

`EvidenceEvent(id, session, worldEpoch, startSequence, endSequence, nanos, categories, invalidReasons, magnitude, proof, provenance,
timingHypotheses, reconstructions)`: the SessionKey, world epoch, sequence range and monotonic capture time of the transition; the typed
families and the raw Phase 8 invalid reasons (preserved verbatim: `NO_LEGAL_INPUT` never becomes "Speed"); the magnitude (bound kind,
horizontal and vertical proven excess, collision-penetration flag, observed displacement); the proof summary (hypotheses exhausted,
reconstructions, evaluations, raw enumerated, excluded exact / by envelope, candidates remaining = 0, the four profile ids, world revision,
≤ 32 hypothesis ids); the provenance (profiles, world revision, applied influences, correction application). No candidate graph.

## PROOF REQUIREMENT

`EvidenceEvent.check(record)` returns a typed `Rejection` unless: classification INVALID; proof present; `hypothesesExhausted > 0` and
`rawEnumerated > 0`; `candidatesRemaining == 0`; all 13 completeness dimensions true; no uncertainty reason; `legalCandidates == 0` and
`explainingHypotheses == 0`; no witness; `NO_LEGAL_INPUT` present; every timing hypothesis complete. `from(record)` is `Optional.empty()` on
any rejection and the record constructor of `EvidenceEvent` re-checks the essentials. `EvidenceEventTest` proves each variant individually,
including each of the 13 dimensions; the enum value alone is never trusted.

## EVIDENCE FAMILIES

`Category {HORIZONTAL_EXCESS, VERTICAL_EXCESS, IMPOSSIBLE_ASCENT, IMPOSSIBLE_DESCENT, COLLISION_PENETRATION, UNEXPLAINED_DISPLACEMENT,
GENERAL_MOVEMENT_IMPOSSIBILITY}` derived one-to-one from the typed Phase 8 invalid reasons; `NO_LEGAL_INPUT` alone is
`GENERAL_MOVEMENT_IMPOSSIBILITY`. The impossible corpus produced HORIZONTAL_EXCESS 605, VERTICAL_EXCESS 1 420, IMPOSSIBLE_ASCENT 1 176,
IMPOSSIBLE_DESCENT 244, COLLISION_PENETRATION 77, UNEXPLAINED_DISPLACEMENT 1 259 (an event may carry several families).

## DEDUPLICATION

Identity = SHA-256 over `session (player, epoch) | world epoch | start | end | classification | sorted invalid reasons | proof counts |
profiles, world revision, hypothesis ids | residual numbers` (no time, no object identity, no map order). The ledger keeps a bounded ring of
known ids (512 ≥ the 256 events): a re-delivered result is `DUPLICATE` (counted, nothing changes, the run is not extended). A late
re-delivery after later records is `OUT_OF_ORDER` (ignored). One Phase 8 INVALID already represents the exhausted union, so branch
deduplication is inherent: one event per transition proof, never per timing/input branch.

## SESSION ISOLATION

One `EvidenceRunner` (two ledgers) per session; a reconnect creates a new SessionKey and a fresh runner (engine test); a record of another
session is `SESSION_MISMATCH`; the digest includes the session, so identical transitions of different sessions never collide. Old
session evidence is dropped with the session (no cross-session storage in this phase; EVIDENCE_MODEL's cross-session anomalies belong to
the global admission guard, not to player guilt).

## WORLD-EPOCH SEGMENTATION

A record whose world epoch differs from the ledger's current one segments the state before it is considered: the correlation groups and the
consecutive-INVALID run restart, events of earlier epochs leave the active view (they stay in the bounded history and the totals),
`epochSegments++`. No run and no group ever spans an epoch boundary (ledger test, property test).

## VALID OBSERVATIONS

`LEGITIMATE_WITNESS`: `validObserved++`, `validSinceLastInvalid++`, the consecutive-INVALID run ends. Nothing is erased: a VALID after an
INVALID leaves the event in history and in the active window (aging alone expires it). No "negative points".

## UNCERTAIN OBSERVATIONS

`UNRESOLVED`: `uncertainObserved++`, `uncertainSinceLastInvalid++`, the run ends, the reason class is recorded as a coverage gap (`gapReasons`,
fixed registry, a bounded recent-gap ring). No positive evidence — including `CLIMBABLE_CONTACT` (P8-F2), `SEARCH_LIMIT` (P8-F5),
`WORLD_INCOMPLETE`, every unsupported mechanic and `INTERNAL_ERROR`. Two INVALID runs separated by an UNCERTAIN are two runs and, unless
their observation ranges touch, two groups: uncertainty never bridges.

## INVALID OBSERVATIONS

`invalidObserved++`; the proof gate decides: `POSITIVE_EVENT` (new id: event remembered, run extended, group joined or opened),
`DUPLICATE`, or `PROOF_REJECTED` (a rejected INVALID counts as a gap and ends the run; its uncertainty reasons, if any, join the gap
counters). Magnitudes come only from the accepted event's residual excesses (the exhausted set), never from a nearest candidate of an
incomplete search.

## WINDOW MODEL

The active window is the last `windowNanos` = 30 s (EVIDENCE_MODEL's correlation window) of monotonic time before the latest ingested record
(`latestNanos`), optionally aged forward by the runner at tick ends (`snapshot(asOfNanos)`, never earlier than the latest ingest). An event is
active iff it belongs to the current world epoch and `now − nanos ≤ windowNanos`. Correlation groups are active while their last member is
in the window. The monotonic times are the Phase 8 capture times of the target observations; no wall clock is ever read; ordering is by
sequence.

## AGE / EXPIRY

`newestEventAgeNanos` / `oldestActiveAgeNanos` in the snapshot; an event leaves the active view deterministically when its age exceeds the
window (or its epoch is segmented away) and is counted `expiredEvents`; it stays in the bounded history and in every total. Expired never
means the Phase 8 result was wrong.

## DECAY

NOT APPLICABLE BY SPEC for this stage: EVIDENCE_MODEL assigns the decay clock to the violation-policy actor and defines no function; none is
invented. Evidence is auditable from events and windows.

## CONTINUITY

Explicit: `currentRun` counts consecutive complete INVALID events with nothing else between them; a VALID, an UNCERTAIN, a rejected INVALID or
a world-epoch change ends it (a DUPLICATE neither extends nor ends it); `longestRun` is the maximum seen. The six brief patterns (ledger test,
`window-results.json`): `III` → run 3, 1 group; `IVI` → run 1, longest 1, 2 groups; `IUI` → run 1, 2 groups, gap SEARCH_LIMIT; `VIV` → run
0, longest 1, validSinceLastInvalid 1; `UIU` → run 0, uncertainSinceLastInvalid 1; `IIVVI` → run 1, longest 2, 2 groups (2 + 1 members).
VALID and UNCERTAIN only end the run and increment coverage counters; they do not age, segment or erase evidence.

## MAGNITUDE

Typed, never summed across kinds: horizontal excess (blocks/transition beyond the legal horizontal extent), vertical excess (blocks beyond
the legal rise interval, signed), collision penetration (flag), observed displacement — each with the Phase 8 bound kind (`ENVELOPE` /
`SIMULATED_COMPLETE` / `NONE`). A group's magnitude is its maximum member (EVIDENCE_MODEL: one causal family contributes at most its maximum
eligible member, never a sum); per-family summaries carry the maximum over the active window. No normalisation across kinds is attempted.

## SNAPSHOT MODEL

`EvidenceSnapshot`: session, world epoch, latest time, window; active events and per-family counts, total / evicted / expired events,
first/last active sequence, current and longest run; VALID / UNCERTAIN / INVALID / proof-rejected / duplicate / out-of-order counts,
VALID and UNCERTAIN since the last INVALID, gap reasons; active / total / evicted groups, `correlationIncomplete`, ≤ 16 group summaries;
per-family magnitude summaries; newest and oldest active age; epoch segments; ≤ 16 supporting event ids; last classification. No boolean
about the player, no level, no probability, no recommendation (architecture test scans the record). `describe()` gives ≤ 24 neutral lines.

## BOUNDS

`EvidenceBounds.DEFAULT`: 256 events (ring, oldest evicted and counted), 128 correlation groups (oldest evicted, `correlationIncomplete`
set — never disjointness by omission), 512 dedup ids, 30 s window, 16 supporting ids, 32 recent gap records; the runner's recent rings are 16
lines. Full bounded state measured at ≈185 KiB (< the 256 KiB per-session evidence budget).

## MEMORY

`memory-results.json`: empty ledger 760 B; typical active state (200 transitions, 20 events) 11 160 B; full bounded 188 816 B; one event
392 B; snapshot empty / typical / full 208 / 1 600 / 3 736 B; 100 retained typical snapshots 160 480 B; `EvidenceRunner` empty 3 128 B;
allocation per ingest: VALID 304 B, UNCERTAIN 568 B, new INVALID event 4 574 B (digest + event + group), duplicate 3 728 B (the digest);
snapshot 5–22 KB. No UUID-keyed global history.

## PERFORMANCE

`benchmarks.json` — JMH 1.37, Temurin 25.0.4.1+1, 3 forks, 5×1 s warm-up, 10×1 s measurement, `-prof gc`, one host (descriptive kernels,
never a capacity claim), re-measured on the final code (after P9-F1…F5):

| kernel | ns/op (mean ± 99.9 % CI) | alloc B/op |
|---|---:|---:|
| `validIngest` | 26.6 ± 0.6 | 128 |
| `uncertainIngest` | 42.3 ± 0.2 | 208 |
| `invalidIngest` | 1,612.6 ± 158.5 | 3,781 |
| `duplicateInvalidIngest` | 1,113.2 ± 38.7 | 3,304 |
| `snapshotEmpty` | 69.4 ± 3.2 | 477 |
| `snapshotOneEvent` | 283.4 ± 4.9 | 1,493 |
| `snapshotManyEvents` | 2,965.5 ± 93.4 | 4,971 |
| `snapshotFullBounded` | 7,403.6 ± 79.4 | 7,016 |
| `windowExpirySnapshot` | 1,473.3 ± 28.1 | 3,784 |
| `worldEpochSegment` | 19.4 ± 4.1 | 128 |
| `sessionReplacement` | 187.7 ± 2.8 | 1,304 |
| `mixedStream100` | 20,261.9 ± 248.1 | 51,432 |
| `fullBoundedIngest` | 1,719.9 ± 42.3 | 3,760 |
| `endToEndValidTransition` | 62,088.8 ± 634.7 | 128,968 |
| `endToEndInvalidTransition` | 46,197.7 ± 5,428.8 | 114,430 |

Evidence-only ingest of a VALID/UNCERTAIN costs tens of nanoseconds and 128–208 B; a fresh INVALID event (digest + event + grouping) ≈ 1.6 µs
and 3.8 KB; a duplicate ≈ 1.1 µs (the digest is recomputed, nothing stored). The bounded state does not change the ingest cost
(`fullBoundedIngest` ≈ `invalidIngest`); a full-bounded snapshot is 7.4 µs. The evidence-only kernels are unchanged by the forward fixes.
End to end (Phase 8 validation + projection + ingest) is dominated by Phase 8, of which evidence is < 2 µs.

**Phase 8 kernels after the forward corrections** (`validation-benchmarks.json`, same setup, written into the Phase 9 artifacts so the frozen
Phase 8 `benchmarks.json` stays untouched; "Phase 8" is the value recorded in `PHASE_8_IMPLEMENTATION.md`):

| kernel | mean ± 99.9 % CI | alloc B/op | Phase 8 |
|---|---:|---:|---:|
| `classifyValidSimple` | 0.55 ± 0.00 µs | 1,384 | — |
| `classifyInvalidHorizontal` | 1.19 ± 0.02 µs | 1,856 | — |
| `classifyWorstBounded` | 1.07 ± 0.08 µs | 1,424 | — |
| `endToEndValidSimple` | 60.05 ± 5.66 µs | 125,848 | ≈ 51 µs |
| `endToEndValidVelocity` | 93.31 ± 0.61 µs | 210,497 | ≈ 82 µs |
| `endToEndValidAmbiguous` | 117.12 ± 0.89 µs | 231,945 | — |
| `endToEndTwoBranch` | 92.76 ± 5.74 µs | 211,179 | ≈ 50 µs |
| `endToEndFourBranch` | 130.01 ± 7.46 µs | 307,425 | ≈ 60 µs |
| `endToEndGap2` | 510.42 ± 9.95 µs | 1,032,256 | ≈ 282 µs |
| `endToEndGap3` | 2,691.65 ± 19.20 µs | 6,219,064 | ≈ 1,380 µs |
| `endToEndInvalidHorizontal` | 46.52 ± 0.36 µs | 109,192 | ≈ 40 µs |
| `endToEndInvalidVertical` | 46.18 ± 0.45 µs | 109,291 | ≈ 40 µs |
| `endToEndUncertainWorldIncomplete` | 265.90 ± 29.57 µs | 722,994 | ≈ 298 µs |
| `endToEndUncertainSearchLimit` | 3.40 ± 0.32 µs | 10,733 | ≈ 1.8 µs |
| `endToEndWorstBounded` | 2,118.81 ± 197.60 µs | 7,001,270 | ≈ 2,500 µs |

The classifier is unchanged (sub-µs to ≈ 1.2 µs). The end-to-end validation paths grew: single-tick VALID ≈ +18 %, the 2/4-branch and
2-tick cases ≈ 1.8–2.2×, and the 3-tick gap ≈ 2×. The cause is P9-F3: the sneak key of a tick no longer changes that tick's movement,
only the pose it ends in, so every reconstruction now carries both end poses (a standing and a crouching continuation) where it previously
carried one, and the next tick is transferred from each. That is the exact client semantics, not an overhead to tune away; the search
bounds (256 nodes / 4 096 evaluations / 3 ticks) are unchanged and the worst bounded case stays ≈ 2.1 ms. Pose continuations that
provably behave identically could be merged later as an optimisation.

Evidence never re-runs physics, timing or reconstruction: it consumes the Phase 8 result once. The runner ages the published status at tick
ends (two snapshots per tick end per session, ≈ 1–13 µs each depending on state).

## REPLAY

`EvidenceFixtureMain` replays pinned record streams into fresh ledgers and pins event ids and snapshot digests
(`evidence-fixtures/evidence-fixtures.json`); the property tests prove that the same stream yields an equal snapshot and equal events, and
that re-inserting the same event into the same ledger is idempotent while replaying it into an isolated ledger recreates it.

## DIAGNOSTICS

`/papea2ac evidence <player> view` (the live snapshot lines), `recent` (bounded ingest lines), `side` (the side-chain snapshot and lines —
the qualification instrument), `reingest` (schedules the re-delivery of the last side-chain record on the shard: expect DUPLICATE);
`/papea2ac status` line `evidence consumed=… witness=… unresolved=… positiveEvents=… duplicates=… proofRejected=… outOfOrder=…
sessionMismatch=… internalErrors=… side{…} sessions=…` (fixed labels, no UUID). Nothing displayed is a ban, kick, setback, cheater or level.

## THREADING AND FAILURE CONTAINMENT

The runner is owned by the session's shard thread (the ledgers assert ownership); the platform reads a volatile immutable
`EvidenceStatus`; the re-delivery request is a flag consumed on the shard at the next tick end. Any exception in the projection or the
ledger is counted (`internalErrors`), recorded as a diagnostic line and produces no evidence; it never reaches the validation path.

## REAL-CLIENT QUALIFICATION

Vanilla 26.2 client, owner `Emojiado`, Paper 26.2 build 124, plugin jar
`14a4964110c7d60e567f5ce448594bec8e26a2c94b0ee957de7b508f599e3b51` (the final Phase 9 jar, carrying the P9-F1 fix). Evidence log:
`real-client-evidence.log`; server log `real-client-server.log` (the `live INVALID` sink lines are the Phase 8 P8-F7 diagnostic).
The first qualification attempt ran on jar `488111bc…` and is what found P9-F1 (1 false INVALID → 1 positive event in ~1 050 transitions);
it was abandoned and the whole course repeated on the fixed jar. Numbers below are from the fixed jar only.

| # | round | transitions | VALID | UNCERTAIN | INVALID | positive events | result |
|---|---|---:|---:|---:|---:|---:|---|
| R1 | sprint→strafe, sprint→sneak, sprint-jump→walk (the P9-F1 pattern) | 1 681 | 1 681 | 0 | 0 | 0 | PASS |
| R2 | ice / slime / honey / soul-sand strip, ladder up+down, shield walk | 718 | 718 | 0 | 0 | 0 | PASS (ladder ticks VALID with `CLIMBABLE_CONTACT` incompleteness) |
| R3 | water (the UNCERTAIN case) | 505 | 504 | 1 | 0 | 0 | PASS — recorded as a gap `UNSUPPORTED_FLUID`, no event |
| R4 | sprint+attack, sprint→backwards, sprint into a wall, stairs/slab | 714 | 712 | 0 | 1 | 1 | **FAIL — P9-F2** |
| B‑A | sprint+attack ×9 | 436 | 436 | 0 | 0 | 0 | clean |
| B‑B | sprint→walk backwards ×6 | 958 | 958 | 0 | 0 | 0 | clean |
| B‑C | sprint into a wall ×5 | 823 | 823 | 0 | 0 | 0 | clean |
| B‑D | stairs/slab + sprint off the edge ×6 | 277 | 276 | 0 | 1 | 1 | **FAIL — P9-F2** |
| B‑D1 | stairs only ×8 | 687 | 687 | 0 | 0 | 0 | clean |
| B‑D2 | sprint off the edge ×8 | 720 | 720 | 0 | 0 | 0 | clean |
| B‑D3 | slab only ×10 | 755 | 755 | 0 | 0 | 0 | clean |
| B‑E | sneak-walk along a ledge | 707 | 707 | 0 | 0 | 0 | clean |
| FP | free play, mixed (packet recording on) | ~790 | — | 0 | 1 | 1 | **FAIL — P9-F2** |
| | **total on the final jar** | **~9 800** | | **1** | **3** | **3** | |

Controlled side-chain injections (the live chain and the timeline untouched throughout, verified after each):

| injection | side ledger | live ledger |
|---|---|---|
| `+0.5 x`, 1 tick | 1 INVALID → 1 POSITIVE_EVENT `22666b3b9394` [HORIZONTAL_EXCESS] excess h 0.1726, proof 864 enumerated / 832 excluded / 0 remaining | unchanged (0 INVALID, 0 events) |
| `evidence reingest` (re-delivery of the same record) | observed INVALID 2, **duplicates 1**, still 1 event, same digest | unchanged |
| `+0.8 y`, then `+0.9 x +0.4 z` | 3 events in 3 groups: [HORIZONTAL_EXCESS] ×2, [VERTICAL_EXCESS, IMPOSSIBLE_ASCENT] ×1; `run=3 longest=3` | unchanged |

Diagnostics exercised live: `/papea2ac evidence <player>` (status, `view`, `side`, `reingest`), the 30 s window (the P9-F1 event aged out of the
active set and was reported `(expired)` with `active=0/1`), world epoch 0 throughout, one session (`session=1`), no `proofRejected`, no
`outOfOrder`, no internal errors. Paper smoke: clean start, clean `stop` (all dimensions saved, worker/IO pools terminated), **0 exceptions**
in the server log across the whole session (`grep -c "Exception|ERROR|SEVERE" → 0`).

**Gate result.** The evidence engine met every behavioural expectation in every round: nothing but a complete INVALID with an intact proof
created an event, the UNCERTAIN became a gap, duplicates deduplicated, injections grouped and never touched the live ledger. The
*qualification gate* "a legitimate course produces 0 positive evidence" is nevertheless **NOT MET**: three complete INVALIDs on a legitimate
player (P9-F2, below) produced three positive events. Each event is a faithful consequence of a Phase 8 result, not an evidence-engine fault.

### Final requalification — VPS, two real players, final jar `297134a6…`

After P9-F3 the owner course passed locally; the requalification then moved to a VPS (Paper 26.2 build 124, Java 25, online mode, RTT
≈ 60–250 ms with real jitter) so that a second, remote player could play. Players: `iDTR` (Lunar Client) and the owner `Emojiado` (vanilla),
survival, difficulty Peaceful and then Normal (hostile mobs, hunger, deaths, respawns and one cross-dimension teleport). Every session had the
one-shot forensic capture armed. The runs on intermediate jars found P9-F5 and P9-F4 and are kept as history:

| jar | transitions | INVALID | outcome |
|---|---:|---:|---|
| `1497c44e…` (P9-F3 fix) | ~5 000 | 1 | capture 7 → P9-F5 (animal push, P8-F3 premise) |
| `a3fc9a58…` (P9-F5 classification) | ~2 400 | 2 | capture 8 → P9-F5 second cause (bundled spawns never reached the tracker) |
| `c909ec19…` (P9-F5 complete) | 8 265 | 1 | capture 9 (a death) → P9-F4 (frozen ticks before the client has loaded) |
| **`297134a6…` (final)** | **22 183** | **0** | **PASS** |

Final jar, every session (`real-client-evidence-vps.log`, `real-client-server-vps-jar297134a6.log`):

| session | transitions | VALID | UNCERTAIN | INVALID | positive events |
|---|---:|---:|---:|---:|---:|
| Emojiado #1 | 15 049 | 14 999 | 50 | 0 | 0 |
| iDTR #2 (worldEpoch 0 → 2: a death and a cross-dimension teleport) | 7 134 | 7 109 | 25 | 0 | 0 |
| **total** | **22 183** | **22 108** | **75** | **0** | **0** |

The evidence engine agrees: `consumed=22183 witness=22108 unresolved=75 positiveEvents=0 duplicates=0 proofRejected=0 outOfOrder=0
internalErrors=0`; the run log holds 0 `live INVALID` lines. UNCERTAIN gap reasons (one transition can carry several): `WORLD_INCOMPLETE` 40
(disputed or unretained cells), `ENTITY_NEIGHBORHOOD_UNKNOWN` 27 (P9-F5 working: mobs and the other player within 4 blocks), `SEARCH_LIMIT` 18, `FRONTIER_INCOMPLETE` 8,
`UNSUPPORTED_FLUID` 5, `TIMING_INCOMPLETE` 1, `CONTENT_NOT_RETAINED` 1, `CLIMBABLE_CONTACT` 1. Transmissions that arrive while no chain is
anchored (≈ 66 000 over the run, mostly while standing on partially retained terrain far from spawn, where a rest fixed point cannot be proven)
are `UNCERTAIN [START_UNAVAILABLE]` and are not counted as transitions. Live probe of the P9-F5 bundle fix: a summoned pig next to the player
raised `relevantEntities` 29 → 30 and removing it restored 29 (the previous jar stayed at 0/0).

Controlled checks on the final jar (side chain; the live chains and the timeline untouched, verified after each):

| check | side ledger | live ledgers |
|---|---|---|
| E1 `inject 0.5 0 0 1` (Emojiado, anchored by correction 6) | 1 INVALID → 1 POSITIVE_EVENT `139df203c905` [HORIZONTAL_EXCESS] excess h 0.1726, proof 864 enumerated / 1 216 excluded / 0 remaining | unchanged (0 INVALID, 0 events) |
| E2 `evidence reingest` | observed INVALID 2, **duplicates 1**, still 1 event, same digest | unchanged |

Paper smoke on the final jar: clean start, clean stop after ≈ 14 h (`Disabling Papea2AC`, all dimensions saved), **0 exceptions**
(`grep -c "Exception|ERROR|SEVERE"` on the unfiltered log → 0). The repository copies of the VPS server logs drop the RCON housekeeping
lines (the progress bossbar and connection threads) and redact player IP addresses; the evidence log is complete. No RCON secret appears in any
artifact (scanned). The VPS was cleaned up afterwards (firewall rule and service removed).

**Final gate result.** Met on the exact final jar: 22 183 live transitions from two real players over a real network, **0 false INVALID,
0 positive evidence**; controlled INVALID → exactly one event, re-delivery → DUPLICATE.

## FALSE-EVIDENCE GATE

`false-evidence-results.json`: the whole Phase 8 legitimate corpus (4 269 cases, 32 314 transitions: 32 312 VALID, 2 UNCERTAIN
[SEARCH_LIMIT], 0 INVALID) through the mapper and one ledger per case → **positive evidence events 0, duplicates 0, proof rejections 0**.
`evidence-corpus-results.json`: the impossible corpus (3 290 complete INVALIDs) → 3 290 events, one per transition, 0 rejections;
`dedup-results.json`: every record delivered twice → 3 290 events, 3 290 DUPLICATE. `EvidenceCorpusTest` enforces both gates in the suite.

## KNOWN LIMITATIONS

1. Evidence is per session only; nothing survives a reconnect (by design for this phase; cross-session aggregation is the global admission
   guard's business in MITIGATION, never player guilt).
2. The correlation key is the observation range: transitions whose ranges touch within 30 s of the same epoch form one family. Root-set
   correlation across engines (movement + combat + timer of one root, TESTING TC20) waits for those engines; the group model already caps a
   family at its maximum member.
3. No decay, no level, no strength number: Phase 10 must derive any policy from the snapshot's typed components.
4. Magnitudes are the Phase 8 residual excesses; an event with bound `NONE` (no sound extent) carries zero excess and only its families.
5. The dedup ring is bounded (512 ids): a transition evicted from the ring and re-delivered much later would count again — only after ≥ 512
   newer events in the same session, and always visible in the counters.
6. Replay-capsule durability (TC25) and the global anomaly observation are not part of this task (see DEFERRED).
7. P9-F1 leaves the sprint *command* unchecked by design: a client that sends START_SPRINTING while strafing is granted the modifier by the
   server and moves 30 % faster in every direction until it next starts sprinting for real — vanilla-consistent motion, hence VALID to the
   movement validator. Plausibility of sprint commands (forward impulse, food, blindness) is a separate future check.

## DEFERRED TO PHASE 10+

Interpretation of the snapshot (policy, thresholds, stages), mitigation intents and their state machine, alerts, any operational action;
the replay-capsule durability qualification (TC25: sparse movement / dense combat / chunk churn, ≥ 99 % durable within 400 ms) and the
global anomaly observation, both preconditions before any automated mitigation; cross-engine root-set correlation once combat/timer evidence
exists; an archival `replay-schema` DTO for evidence events.

## FORWARD DEFECTS (P9-F<n>)

### P9-F1 — the server-synced sprint modifier made a sprint-stop tick INVALID (originating component: Phase 5 attribute model)

**Discovery.** Phase 9 live qualification, round 1 (walk, sprint, jumps, sprint-jump, diagonal, slab, stairs) on the legitimate owner: one
complete `INVALID [NO_LEGAL_INPUT, UNEXPLAINED_DISPLACEMENT]` at seq 235287→235357 (completeness ALL, 144/144 excluded exactly), which the
evidence engine correctly turned into exactly one positive event (`f9d6bd27691c`, family UNEXPLAINED_DISPLACEMENT) — a false positive at
the evidence layer produced faithfully from a false INVALID underneath. The engine was not at fault; the gate "legitimate course → 0 positive
evidence" was.

**Failure.** Observed h = 0.2141, rise 0, one tick, on the flat lane away from every climbable. Nearest excluded candidates: `-A----` off by
(−2.738e−02, 0, −1.070e−02), outside **0.02940**; `-A-C--` off by (5.568e−02, 0, 2.175e−02), outside **0.05978**. Both numbers are exact
arithmetic of one mechanism: 0.02940 = 0.3 × 0.098 (one sprint modifier's worth of the walking impulse) and 0.05978 = 0.098 − 0.098 × 1.3 × 0.3
(the same modifier under the sneak factor). The client strafed at plain walking speed; every model candidate was 30 % faster.

**Root cause (verified against the Paper 26.2 build 124 jar with javap).** `LivingEntity.setSprinting` removes/adds the transient
`minecraft:sprinting` MOVEMENT_SPEED modifier (`ADD_MULTIPLIED_TOTAL 0.3`); `ServerEntity.sendDirtyEntityData` syncs dirty attributes with
`ClientboundUpdateAttributesPacket` to the tracking players **and the player itself** (`sendToTrackingPlayersAndSelf`), so while the server
believes the player sprints, the snapshot the client last applied carries the modifier. The client (`ClientPacketListener.handleUpdateAttributes`)
replaces its modifier set with the snapshot's and afterwards touches that modifier only at its own sprint transitions. On the tick a sprinting
player stops sprinting but keeps moving (`!hasForwardImpulse` → `setSprinting(false)`: sprint → strafe, sprint → sneak, sprint → backwards, a
sprint hit), the client runs at 0.098 while the STOP echo is still in flight. `PhysicsSimulator.Attrs` used the snapshot's modifier list as the
client's and only ever **added** the modifier for sprinting candidates: under a carrying snapshot every candidate resolved to 0.1274.

**Re-attribution of P8-F2.** The Phase 8 pre-baseline decision pass reproduced two live false INVALIDs next to the ladder (seq 22037, nearest
outside **0.02940** — the same constant) and classified them as an unmodelled climbable interaction, gated by `CLIMBABLE_CONTACT`. That gate is
still a correct conservative statement about ladders, but the two observations were this defect: a sprint stop beside the ladder. The Phase 8
documents and artifacts are frozen and unchanged; this paragraph is the record.

**Fix (minimal forward fix; Phase 5 `anticheat-physics`, Phase 6 `anticheat-simulation`, Phase 7 ledger, Phase 8 runner).**
The presence of the client's sprint modifier is state, not a snapshot field. With flag S and presence P the tick rule is
`(S, P) := (R, R) if R != S else unchanged` (R = the tick's sprint input), which leaves three behaviour classes plus two "flag unknown" unions,
now carried by `PhysicsState.sprintModifier` (`PhysicsState.SprintModifier`):
`FOLLOWS` (S == P: present iff the input sprints; the vanilla steady state and the pre-fix behaviour, absorbing), `STUCK_ON` (S off, P on: a
carrying snapshot reached a client that had stopped — present for every input until it next starts sprinting), `STUCK_OFF` (S on, P off:
a bare snapshot reached a sprinting client — absent until it next stops), `MAYBE_STUCK_ON` = {FOLLOWS, STUCK_ON}, `MAYBE_STUCK_OFF` =
{FOLLOWS, STUCK_OFF}. `Attrs` drops the snapshot's own copy of the modifier and re-adds it iff present (list order untouched when it stays).
A MAYBE state is one state while the presence cannot matter (idle, or airborne — the speed attribute is read only on the ground with a horizontal
input) or its members agree; `InputReconstructor` splits it into its two members exactly on a ground input on which they disagree
(`PhysicsState.sprintModifierCases`; both evaluations are counted). An idle tick keeps a MAYBE class (one state for both sprint-key values,
a sound superset of the exact union), a moving tick resolves it exactly. `AbstractState.Box` carries the class set as a bit mask and
`AbstractTransfer` transfers each member to its own successor class with the same rule (boxes equal but for the class set merge), so the
envelope lane stays sound and, for the only class the Phase 5–8 fixtures visit, unchanged. `MovementContextLedger.AttributeValue` records the
sequence of the update that applied it: `MovementValidationRunner` re-synchronises the frontier whenever a new MOVEMENT_SPEED snapshot was
applied (`SprintModifier.afterSnapshot`: the presence becomes the snapshot's; the flag becomes the snapshot's when the update came with a
sprint change and stays otherwise — one MAYBE class, never a guess), anchors a rest under a carrying snapshot as `MAYBE_STUCK_ON` (a resting
client is not sprinting; its stop may be unsynced) and a correction as the MAYBE class of what the snapshot carries (the flag is unknown after
a teleport). `PhysicsState.toString()` keeps the Phase 5 record form and appends the class only when it is not FOLLOWS: the Phase 6–8 fixture
digests hash these strings and every history they pin stays in FOLLOWS.

**What the fix does not claim.** A vanilla client that receives a carrying snapshot without sprinting itself really is 30 % faster in every
direction until it next starts sprinting (`STUCK_ON`): the server grants the modifier to any client that sends START_SPRINTING and never checks
a forward impulse. Movement physics therefore admits that motion; whether the sprint *command* was plausible is a separate check for a later
phase, never a physics INVALID. Entity-data flag flips arrive in the same `sendDirtyEntityData` flush as the attribute they belong to, which is
why the resynchronisation handles them; an attribute update sent for another reason (effect, equipment) keeps the flag, which is why the result
is a MAYBE class.

**Regression.** `SprintModifierTest` (4): the exact live residuals 0.02940 / 0.05978 reproduced from the pre-fix reading and the stop tick equal
to a plain walking tick; the class state machine (FOLLOWS absorbing, STUCK_ON/OFF until the next transition, MAYBE kept on idle and settled on
moving ticks, undecided — never a prediction — when an unsplit MAYBE meets a disagreeing ground input); abstract-lane soundness for every class,
snapshot and input including the MAYBE unions; the validator-level sprint → strafe chain under a carrying snapshot VALID (8/8) with the STUCK_ON
reading INVALID and the MAYBE reading VALID and settled. `MovementValidationRunnerTest.aSprintStopUnderASyncedSprintModifierIsValidAndASnapshotResyncsTheFrontier`:
the live sequence through the runner (rest anchor, START echo snapshot, 6 sprint ticks, 3 strafe ticks, STOP echo snapshot, 2 more) → 0
INVALID, all VALID, no frontier growth. Phase 5 native differential unchanged (88/88 scenarios, 3105/3105 ticks, drift 0.0, 0 false
exclusions); Phase 2–8 fixture families regenerate byte-identical (the Phase 8 corpus result files differ only in JSON key order and were
restored); legitimate corpus 32 314 → 0 INVALID; impossible corpus 3 290 → 3 290; 680 unit + 137 native tests green.

**Requalification.** See REAL-CLIENT QUALIFICATION: the final jar `14a4964110c7d60e567f5ce448594bec8e26a2c94b0ee957de7b508f599e3b51` carries this
fix; the legitimate rounds were repeated on it with explicit sprint → strafe / sprint → sneak / sprint-jump → walk transitions.

**Historical baseline.** The Phase 8 baseline commit and receipt are unchanged; the Phase 8 jar remains the jar that was qualified for Phase 8
with this defect latent (P8-F2 gated by CLIMBABLE_CONTACT, otherwise 1 false INVALID in ~2 000 live transitions at sprint stops that keep moving).

### P9-F2 — false INVALIDs from a block state the client and this observer disagree about — FIXED (origin: Phase 3/8 world knowledge)

**Status: root-caused, fixed, reproduced offline, regression added, requalified for this class.**

**How it was found.** A bounded one-shot forensic capture was added (`ForensicCapture`, armed with `/papea2ac validate <name> forensic arm`,
frozen at the first live INVALID, never overwritten, diagnostic only): the frontier start states in raw bits, anchor provenance, the last eight
chain transitions with their continuation states, the context ledger with per-attribute applying sequences, the observation and slice, and — the
decisive part — the FULL 144-tuple raw candidate table simulated directly from every frontier node, plus a re-reconstruction with the M3 envelope
prefilter disabled. Three captures were taken live; two are archived (`p9f2-forensic-capture-1.log`, `p9f2-forensic-capture-3.log`).

**Root cause.** Capture 1: the client rose exactly 0.1875 in one tick while every model candidate was pinned in x with `hColl=true`. 0.1875 = 3/16
is the collision height of a closed bottom-half trapdoor; the block at (-3, 67, 20) is `minecraft:oak_trapdoor[facing=east,half=bottom]`. The
model's shape table is correct (shape 53 = `[0,0,0, 1,0.1875,1]`), but the model held the **open** state, whose shape is
`[0,0,0, 0.1875,1,1]` — a full-height slab on the cell's west face. That blocks the sprint horizontally, forbids a step-up (1.0 > stepHeight 0.6)
and makes the observed position a penetration: `INVALID [NO_LEGAL_INPUT, COLLISION_PENETRATION]`. Capture 2 (the ledge case): every *shift*
candidate had its x movement zeroed by `Player.maybeBackOffFromEdge` because the model saw a drop the client did not.

The disagreement is structural, not a bug in the shape or the physics: a block state is **not one fact shared by both sides**.
`ServerboundUseItemOnPacket` / `ServerboundPlayerActionPacket` carry a prediction sequence; the client applies its own predicted state
IMMEDIATELY and moves against it, while this observer only learns the authoritative state when the server broadcasts it. Symmetrically, a
change this observer applies reaches the client one transport delay later, during which the client still moves against the previous shape. In
both windows the cell's shape is genuinely unknown to us, yet the model reported the world dimension complete and proved a transition impossible
against a shape the client was not using.

**The ordering detail that the first version of the fix got wrong.** `ClientboundBlockChangedAckPacket` is sent by the interaction handler
immediately, but the authoritative change only goes out with the chunk broadcast later in the same server tick
(`ChunkHolder.broadcastChanges`). A gate that settles on the ack therefore reopens the stale window — which is exactly how the second live
failure got through the first build. **Only an applied block change (or the bounded horizon) settles a predicted cell.**

**Fix (smallest forward correction; `anticheat-core` + one additive validator input).**
`BlockDisputeLedger` (per session, shard-owned, bounded: 256 cells / 64 pending predictions, overflow degrades to a whole-world dispute) records
(a) the cell a client interaction targeted plus the placement neighbour, disputed until the authoritative change arrives or the 3 s horizon, and
(b) every applied block change, disputed for the measured RTT plus a 100 ms transport margin. `MovementValidationRequest` gained
`worldDisputed` (the Phase 8 arity is preserved by a delegating constructor); `MovementValidator` turns it into
`worldOk = false` + `UncertainReason.WORLD_INCOMPLETE` with a named note. The runner asks the ledger for the same reach region the climbable
gate uses (frontier ∪ target, ±1 horizontally, −1/+2 vertically). Consequences: an explained transition stays VALID with the world dimension
marked incomplete, an unexplained one is UNCERTAIN [WORLD_INCOMPLETE], and INVALID is impossible for a transition whose reach touches a disputed
cell. No tolerance, no epsilon, no threshold; the Evidence Engine is untouched.

**What the fix does NOT do.** It does not weaken detection away from disputed cells (a dispute elsewhere leaves the same scene INVALID — tested),
it does not gate whole mechanics (sneak, rest anchors, diagonals and item use are all unaffected), and it does not settle on the acknowledgement.

**Regression** (`BlockDisputeTest`, 4 tests, production path: rest anchor → context → timing → reconstruction → validation):
the live failure reproduced offline — an observer holding the open trapdoor while the client moves in a world where it is closed produces the
exact `INVALID [..., COLLISION_PENETRATION]`; with the gate the same scene is UNCERTAIN with WORLD_INCOMPLETE named; an undisputed world still
classifies normally and a dispute far from the transition leaves the INVALID intact; the ledger's bounds, ack ordering, applied-change settling
and horizon are pinned.

**Verification after the fix.** test 683 / nativeTest 137, 0 failures (one transient `CheckpointReplayTest` failure under parallel execution,
green in isolation and on rerun — the known Phase 1–3 replay-timing flake). Phase 5 native differential 88/88 scenarios, 3 105 ticks, bit-exact,
drift 0.0, 0 false exclusions; Phase 6 reconstruction-vs-oracle 3 105/3 105 witnesses, 0 false exclusions; mandatory coverage, replay conformance,
movement-property and shape coverage gates reproduced; Phase 2–8 fixtures regenerate byte-identical (only JSON key order / run-seconds differ, and
those files were restored). Phase 8 corpora unchanged: legitimate 32 314 → 0 INVALID, impossible 3 290 → 3 290. Phase 9 corpora unchanged:
legitimate → 0 positive evidence, impossible → one event each, duplicates idempotent.

**Live requalification on the fixed jar `17468cdeb2143f90bb04bdd51a852205bbcace13a6f15ceacf27b268f144aea2`** (targeted round first, then a mixed
course): 6 321 transitions. The block-interaction class produced **zero** INVALIDs — the gate fired 15–16 times per round exactly where the
failures used to occur (7 UNCERTAIN [WORLD_INCOMPLETE] gaps), including toggling the trapdoor while sprinting onto it, placing a block and walking
onto it immediately, and breaking a block and walking into the gap. A follow-up control (place a block, wait 5 s, then walk on it for 30 s — well
past the 3 s horizon) produced 1 215 clean transitions, which confirms empirically that the authoritative update for a player's own edit does
reach this observer, so the bounded window is sufficient and no permanent staleness remains. Controlled injections and the duplicate recheck still
pass (1 INVALID → 1 event, re-delivery → DUPLICATE, live ledger untouched). Paper smoke: clean start and stop, 0 exceptions.

### P9-F3 — sneak scaling one tick late, and a resting sneaker anchored standing — FIXED (origin: Phase 5 input pipeline, Phase 8 rest anchoring)

**Status: root-caused from the pinned client code, fixed, pinned by regressions that fail pre-fix, re-verified against the real server.**
It first appeared as one false INVALID in 6 321 live transitions (capture 3: "the tick after a landing needs an impulse 7.31 % larger than any
modelled input"). The diagnostics added for it (world cells with friction/speed/jump factors, the per-candidate speed/friction/processed input
vector/crouch flag, the dispute state) then caught captures 5 and 6, which settled it. It was never an impulse-magnitude error.

**Capture 5 — the rest anchor.** The owner rested while holding sneak, then walked under `oak_slab[type=top]` (collision box y 68.5–69.0) with
the feet at y 67.0: a crouching box (67.0–68.5) passes, a standing box (67.0–68.8) is clipped. The impulse the observation required was
bit-identical to the model's own processed input vector; the whole discrepancy was the collision clip. The chain's only frontier node was
`pose=STANDING` from `rest (1 pose state)`: `MovementValidator.restFixedPoint` probed every pose with a shift-RELEASED idle input, so the
crouching fixed point converged to STANDING and deduplicated away. Fix: each pose is probed with the input that keeps it
(`restingInput(pose, yaw)`: CROUCHING holds shift), so the sneaking rest stays in the anchor union — a strict superset of start states, which
can only remove impossibility claims. The oracle plugin's pose update was corrected the same way (`previousShift`).

**Capture 6 — the input timing.** Eight standing sprint ticks, then one tick that moved with a sneak-scaled impulse while its box was already
crouch-height. That is only possible if sneak reaches the movement one tick later than the pose, which the pinned 26.2 client confirms
(`LocalPlayer.aiStep`, read from the unobfuscated client jar): `crouching` is assigned from `isShiftKeyDown()` (bytecode 126 → putfield 154)
BEFORE `ClientInput.tick()` (161) reads the keyboard, and `modifyInput` scales by `sneaking_speed` when `isMovingSlowly()` (the stale
`crouching`); `maybeBackOffFromEdge` and `updatePlayerPose` read the fresh key. Hence the sneak scaling of tick T follows the key sampled at
T−1, which is exactly "the pose this tick starts in is CROUCHING". The model tied both to the current key, and the Phase 6 oracle harness had
copied the same assumption, which is why the server-side differential never saw it. Fix: `PhysicsSimulator` scales by sneak iff the start pose
is CROUCHING; `AbstractTransfer` transfers every start pose the box may hold with its own scaling (the end pose still follows the key);
`InputReconstructor` expectations now state that the final tick's sneak key is the only remaining ambiguity (`assertUniqueUpToFinalSneak`).
Capture 3's "7.31 %" was this same timing (a sneak-scaled step the model attributed to the wrong tick), not a friction or speed error.

**Verification.** Real-server differential after correcting model and harness: 88/88 scenarios, 3 105/3 105 ticks bit-exact single-step and
chained, drift 0.0; the 166-scenario / 4 005-tick landing matrix (`p9f3-landing-matrix-results.json`, `p9f3-landing-matrix.patch`) bit-exact;
Phase 6 no-false-exclusion 3 105/3 105; legitimate corpus 32 451 transitions / 0 INVALID; impossible corpus 3 290/3 290 INVALID; timing replays
0 truth lost. About 14 tests carried the old client behaviour and were corrected against the client code, none loosened; the re-pinned
reference data lives in `docs/phase9-artifacts/` (the P6-F1 forward-correction precedent), the frozen Phase 5–8 artifacts are untouched.
`RestPoseAnchorTest` (5 tests, 3 fail pre-fix) pins capture 5; `PhysicsCoreMatrixTest`, `InputReconstructorTest`, `AbstractTransferTest`
pin the timing. Live on the local server (jar `1497c44e…`): targeted crouch/landing course 3 749 transitions all VALID, full local course 5 756
transitions / 0 INVALID.

### P9-F4 — the client does not tick its player until it has loaded the level — FIXED (origin: Phase 7/8 correction timing)

Capture 4 (local): after a world change the player was placed 0.1 above a stone floor and for seven client tick-ends reported the exact
teleport position with `onGround=false`, never falling. Capture 9 (VPS, `p9f5-forensic-capture-9-vps.log`, on a death/respawn with
difficulty Normal): respawn correction 68 at the spawn point, then **nine tick ends with no movement**, then one tick that moved exactly one
walking AIR impulse (−0.0196 in x, Δy = 0) — the first tick of a freshly respawned player, whose `onGround` starts false and whose first move
has zero vertical velocity. The model simulated nine physics ticks after the correction (the air node fell and landed; the ground node took the
ground impulse 0.098), so no candidate matched: a complete INVALID on a legitimate player.

**Root cause (pinned 26.2 client, read from the jar).** `ClientPacketListener.handleLogin` and `handleRespawn` call
`setClientLoaded(false)`; `LocalPlayer.tick` begins `if (!connection.hasClientLoaded()) return;` — no movement, no position packet. In
`Minecraft.tick` the player tick (`ClientLevel.tickEntities`) runs first, then `ServerboundClientTickEnd` is sent, then `Connection.tick` →
`ClientPacketListener.tick`, which, once `LevelLoadTracker.isLevelReady()`, sends `ServerboundPlayerLoaded` and sets the flag
(`notifyPlayerLoaded`). So every tick end between a login/respawn and the player-loaded packet closes a tick in which the player did not
move, and the player ticks again from the next client tick. The packet was already captured (`Lifecycle(PLAYER_LOADED)`) but unused.

**Fix.** `PacketEngine` tells the runner when the server sends a JoinGame/Respawn (`clientLoadReset`) and when the client's player-loaded
packet arrives (`clientLoaded`); in between, `MovementValidationRunner` leaves `ClientTickEnd` out of the physics slice. That reduces a
respawn exactly to the ordinary correction shape (teleport echo, then the first physics tick), no window, no tolerance. Guard: a position
packet after a frozen tick (impossible for a vanilla client, which sends none while not loaded) drops the anchor — re-anchor, never a verdict.
`ClientLoadedTicksTest` (3): the live shape is VALID with the rule; the same stream counting the frozen ticks as physics is INVALID (the live
failure, reproduced); a position while supposedly not loaded is never judged.

### P9-F5 — animals and mobs push the local player on the 26.2 client — FIXED (origin: P8-F3 entity relevance)

**Found by the VPS qualification** (a second, remote player on the Lunar client over a real 63 ms link; capture 7,
`p9f3-forensic-capture-7-vps.log`). One false INVALID after ~2 680 VALID transitions on jar `1497c44e…`.

**The failing tick.** Airborne, holding backward, falling onto grass: height exact for one tick (Δy = −0.373904 = the carried vertical
velocity), the previous horizontal velocity pinned by four consecutive exact position matches, no server velocity/explosion/teleport pending,
no disputed block, no other player online, one ClientTickEnd in the slice. The observation required a horizontal impulse of 0.038099; the
largest legal air impulse is 0.02548 (sprinting; 0.0196 walking). Residual against the walking backward candidate: (+0.018486, −0.000961).

**Not the client.** The pinned 26.2 client jar was read end to end for the input and air-speed path: `KeyboardInput.tick` (±1 per axis,
normalized), `LocalPlayer.modifyInput` (×0.98, item use, sneak, `modifyInputSpeedForSquareMovement` → length ≤ 1),
`LivingEntity.getFrictionInfluencedSpeed` → `Player.getFlyingSpeed` (0.02 / 0.026 unless flying). No client path exceeds 0.02548 in the air.
Lunar is therefore not implicated; the same happens on the vanilla client.

**Root cause.** 26.2 moved client-side pushing: `LivingEntity.pushEntities` calls `Level.getPushableEntities(this, box)`;
`ClientLevel.getPushableEntities` returns `List.of(localPlayer)` for every OTHER entity whose box intersects the local player's (and nothing when
the pusher is the local player itself), and `EntitySelector.pushableBy` admits only the local player on the client. So every remote living
entity's `aiStep → pushEntities → doPush → Entity.push` shoves the local player on its own client — up to 0.05·√overlap per tick on the dominant
axis. P8-F3 had classified animals and mobs as irrelevant (untracked) on the premise "client-side `pushEntities` pushes only players"
(true before 26.x), so a nearby animal left the context "complete" and the push became an INVALID. The chunk still holds a rabbit a few blocks
from the failure point; the residual matches `Entity.push` from an entity ≈ 0.14 west of the player (the player was drifting west into it, the push
points east), and a rabbit's box on the grass (y 69.0–69.5) overlapped the player's feet (69.495) at the start of the tick.

**Fix.** `MovementContextLedger.EntityClass` gains `PUSHER`; every entity type that pushes the local player is tracked, so one within 4 blocks
makes the entity neighbourhood UNKNOWN (UNCERTAIN `ENTITY_NEIGHBORHOOD_UNKNOWN`) — exactly the existing treatment of another player, because
the client's interpolated position of a remote entity is not reproducible by the server. No tolerance, no new gate. The non-pusher set is exact
and pinned against the jar (`entity-push-classes-26.2.json`: all 158 types, derived from the `EntityTypes` field signatures, each class
hierarchy and the `pushEntities`/`doPush` overrides): 95 pushers (every `LivingEntity` except `Bat` — empty push — `ArmorStand` — pushes ridable
minecarts only — and `Parrot` — `doPush` returns for a Player; plus minecarts), 41 non-pushers (items, projectiles, displays, markers, …),
21 hard colliders, 1 player. A type the table does not know is a pusher: it can cost precision, never soundness. The physics comments that
repeated the old premise were corrected.

**Regressions.** `EntityPushClassificationTest` (3: every 26.2 type classified exactly as the jar pushes and `NON_PUSHERS` equal to the jar's
non-pushing set; the live pushers/non-pushers; a hopping rabbit making the neighbourhood relevant and an item not);
`MovementValidationRunnerTest` now asserts a rabbit next to the player → UNCERTAIN `ENTITY_NEIGHBORHOOD_UNKNOWN`, and the previous
"a cow is irrelevant, INVALID stands" case is kept with an item (P8-F3's assertion was the defect); `MovementContextLedgerTest`'s ordinary-world
bound uses items and arrows. Corpora unchanged: legitimate 32 451 / 0 INVALID, impossible 3 290 / 3 290 (A/B regeneration with the pre-fix
classification reproduces every value). While regenerating, the two `totals` objects of `ValidationFixtureMain` were found to use `Map.of`,
whose iteration order changes per JVM run; they now keep insertion order, so regenerated artifacts are byte-identical run to run.

**Second root cause: the tracker never saw a live spawn (capture 8).** The first requalification on jar `a3fc9a58…` produced two INVALIDs
in ~860 transitions (`p9f5-forensic-capture-8-vps.log`): from an exact rest on flat grass, one tick moved 0.060364 straight backward (and
0.0554 six seconds later) while the model's only backward step from rest is 0.098 (walk) or 0.0294 (sneak) — a clean extra ≈ 0.037 push,
diagonal, while the context reported `trackedEntities=0` with a llama wandering a few blocks away. A controlled live probe (a summoned pig
next to the player) left `relevantEntities=0/0`: no spawn ever reached the ledger. Vanilla pairs every tracked entity
(`ServerEntity.addPairing`, read from the 26.2 jar) as ONE `ClientboundBundlePacket` — AddEntity, data, attributes, equipment — and
`ContextObservationMapper` had no bundle case (`WorldObservationMapper` has one), so every spawn was dropped. This predates P9-F5: since
Phase 8 the "relevant entity within 4 blocks → UNCERTAIN" rule never fired live, for players either. Fix: the context mapper maps a bundle's
children in wire order under the bundle's sequence (the normalizer already rejects nesting). The only other vanilla bundle
(`ServerEntity.sendChanges`: motion + projectile power, for `AbstractHurtingProjectile`s only) never carries the player's own velocity, so the
timing mapper is unaffected. Regressions (both fail pre-fix): `MovementContextLedgerTest` maps a bundle holding a rabbit spawn, and
`NativeSemanticTest.aBundledNativeSpawnReachesTheNeighbourhoodTracker` builds a real vanilla bundle around a native llama `AddEntity`,
normalizes it, maps it and finds the llama as a pusher in the ledger.

**Precision cost.** Transitions within 4 blocks of an animal or mob are now UNCERTAIN instead of VALID. That is the price of the client's
unreproducible interpolation; tightening it would need a sound bound on each entity's client-side position and push, a later refinement.

## ACCEPTANCE MATRIX

| # | requirement | verdict | evidence |
|---|---|---|---|
| 1 | Only a completed Phase 8 INVALID with a complete, intact proof creates positive evidence (structural gate, not caller convention) | PASS | `EvidenceEvent.check/from`, `EvidenceEventTest` (5), `EvidencePropertyTest` (6 × 60 seeds) |
| 2 | VALID / UNCERTAIN / SEARCH_LIMIT / unsupported / internal error / malformed proof → zero positive evidence | PASS | `false-evidence-results.json` (32 314 transitions → 0 events, 0 rejections), `EvidenceCorpusTest`, live R3 (`UNSUPPORTED_FLUID` gap) |
| 3 | Immutable EvidenceEvent with session, epoch, seq range, monotonic time, reasons, completeness, proof digest, residual excess, provenance | PASS | `EvidenceEvent`, `EvidenceEventTest`, `evidence-fixtures.json` |
| 4 | Typed families derived from the Phase 8 reasons; no renaming (NO_LEGAL_INPUT is never "Speed") | PASS | `EvidenceVocabulary.Category`, `evidence-corpus-results.json` (6 families over 3 290 events) |
| 5 | Deterministic dedup identity; re-delivery never doubles | PASS | `dedup-results.json` (3 290 / 3 290 duplicates), live injection E2 |
| 6 | Branch dedup inherent (one transition, one event, whatever the search explored) | PASS | digest excludes search work; `EvidencePropertyTest` |
| 7 | Session isolation; session replacement starts empty | PASS | `EvidenceLedgerTest`, `sessionReplacement` kernel |
| 8 | World-epoch segmentation | PASS | `EvidenceLedgerTest`, `worldEpochSegment` kernel |
| 9 | VALID coverage and UNCERTAIN gaps recorded, never as evidence | PASS | `EvidenceLedger` gap records, live R3 |
| 10 | Explicit continuity (I I I vs I U U I vs I V V I) | PASS | ordering corpus in `EvidenceLedgerTest`, `currentRun` / `longestRun` |
| 11 | No wall-clock ordering; monotonic time only | PASS | `ArchitectureTest.evidenceStaysWithinThePhase9Boundary` |
| 12 | Bounded windows / caps per EVIDENCE_MODEL (30 s, 256 events, 128 groups, 512 ids) | PASS | `EvidenceBounds`, `window-results.json`, `memory-results.json` |
| 13 | Deterministic expiry; no invented decay or VL | PASS | `window-results.json`, DECAY section |
| 14 | Magnitude only from complete INVALID residual excess, typed units | PASS | `EvidenceEvent`, `evidence-corpus-results.json` (maxH 0.6646, maxV 1.0) |
| 15 | Deterministic digest (no time, no search work) | PASS | `EvidencePropertyTest`, `evidence-fixtures.json` |
| 16 | Immutable bounded snapshot with no isCheater / ban / kick / setback field | PASS | `EvidenceSnapshot`, vocabulary scan in `ArchitectureTest` |
| 17 | Safety regressions: P8-F2, P8-F5, unknown world, unsupported, internal error, proof corruption | PASS | `EvidenceEventTest`, `EvidenceRunnerTest`, `ValidationSafetyTest` |
| 18 | Deterministic replay (same input → same events, same digests) | PASS | `EvidenceCorpusTest`, `EvidenceFixtureMain` |
| 19 | Legit corpus 32 314 transitions → 0 positive evidence | PASS | `false-evidence-results.json` |
| 20 | Impossible corpus 3 290 → exactly one event each | PASS | `evidence-corpus-results.json` |
| 21 | Duplicate / ordering / world-epoch / session-replacement corpora | PASS | `dedup-results.json`, `EvidenceLedgerTest` (10) |
| 22 | Hard caps and memory accounting | PASS | `memory-results.json` (full bounded 188 816 B; per-ingest 128–3 813 B) |
| 23 | JMH (evidence-only and end-to-end, 3 forks, 5×1 s / 10×1 s, `-prof gc`) | PASS | `benchmarks.json`, PERFORMANCE |
| 24 | Shard-owned threading, failure containment, fixed-cardinality metrics | PASS | `EvidenceRunner` (owner thread, scheduled reingest), `ArchitectureTest` |
| 25 | `/papea2ac evidence <player>` diagnostic | PASS | live rounds R1–R4, injections E1–E4 |
| 26 | Property and metamorphic tests | PASS | `EvidencePropertyTest` (6 properties × 60 seeds) |
| 27 | Artifacts under `docs/phase9-artifacts/` | PASS | live logs (local and VPS), forensic captures 1–9, the P9-F2 probe patch, the P9-F3 landing matrix, `entity-push-classes-26.2.json`, re-pinned fixtures |
| 28 | Paper smoke: clean start/stop, 0 exceptions | PASS | `real-client-server.log`, `real-client-server-vps-jar297134a6.log` (final jar, ≈ 14 h) |
| 29 | Real-client: one INVALID → one event; re-delivery → no duplicate; several distinct → several | PASS | injections E1–E3 |
| 30 | Real-client: a legitimate course produces 0 positive evidence | PASS | final jar `297134a6…`: 22 183 live transitions, two players, 0 INVALID, 0 positive events; P9-F1…F5 found on earlier jars and fixed |
| 31 | Phase 0–8 documents and artifacts unmodified | PASS | `git status docs` shows only the Phase 9 paths (a run-order rewrite of `phase5-artifacts/movement-property-coverage.json` by the coverage gate was restored) |
| 32 | Not committed, not baselined, no Phase 10 work | PASS | working tree only; HEAD still `3cc327d` |
