# Phase 13 — Velocity / anti-knockback — real-cheat VPS qualification matrix

Status: RESEARCH CATALOG (2026-10-03, revision 5: `PHASE13_VELOCITY_CONTRACT_READY` **version 4**, `CONTRACT_CHANGED-5.md`,
`phase13-snapshot-4.tar`, 30 files on 59caec5 + Phase 11 snapshot 2 + **Phase 12 snapshot 2**). **The velocity detector is not qualified.**
Phase 13's offline work is done (`gradlew test` 891/891, `nativeTest` 148/148). Version 3 added melee push exclusion (`core.PushExclusion`);
version 4 only rebases onto Phase 12 contract version 2 and drops Phase 13's copy of the entity-bounds methods: corpus results are
byte-identical and no type changed. **Use snapshot 4, never snapshot 3, on top of `phase12-snapshot-2.tar`**: snapshot 3 carries the old
`ReachValidator` / `ReachRunner` and would overwrite Phase 12 version 2. The VPS round runs after the Phase 11 VPS run, on Phase 13's
exact candidate jar only.

Shared rules: [QUALIFICATION_PROTOCOL.md](QUALIFICATION_PROTOCOL.md).

Governing specs: ROADMAP (Phase 13 = shared simulation consumption and impulse ordering; no duplicate motion formula),
LATENCY_COMPENSATION N1/N2, EVIDENCE_MODEL (movement and velocity mismatches from one simulation are **one** causal family), TESTING
TC04/TC05 and the Phase 7b gate (repeated knockback at 50/150/300 ms: ≥ 90 % definite outcomes, 0 false INVALID), P11-F10 (an influence
past the 3 s horizon stays OUTSTANDING until a later answered marker, a confirmed correction or a world change).

## 1. The model and the owner's decision

- **Frozen profile `PAPER_26_2`.** While a knockback might not have been applied yet, the chain keeps a branch in which the client ignored
  it. Offline, a client that cancels knockback was **never** contradicted (40/40 cancel scenarios, 0 INVALID).
- **Fenced profile `PAPER_26_2_FENCED`**, the live default since contract version 2 (`pongBoundsApplication = true`, client fact P13-N1 from the pinned 26.2 client bytecode). A pong to
  a ping written after the velocity proves the velocity was applied before the first client tick whose packets follow that pong. Past that
  tick there is no "not yet applied" branch. The fence is off when a correction was written after the influence or is pending, or when
  alignment is inexact.
- **Owner decision, 2026-10-03 06:23Z: "Fenced by default"** for the Phase 13 candidate jar. On that jar, cancelled, zeroed, reversed and
  scaled knockback is expected INVALID outside melee, and, since contract version 3, in melee at range where push exclusion succeeds; and the cancelled-knockback class is new live evidence, observe-only. Phase 13
  reruns its corpus on the combat-time marker cadence before the jar is cut.
- **Melee push exclusion (contract version 3).** A melee attacker within 4 blocks used to make the push neighbourhood unknown (P9-F5), so
  melee was always UNCERTAIN. `core.PushExclusion` now tries to prove that no entity box can have intersected the player's box during the
  transition: the player region is the last transmitted position ± 2e-4 at standing height and width × scale; each entity's bound is
  Phase 12's `ReachValidator.possibleEntityBox` (interpolation hull since the newest answered marker, grown by the widest admissible
  dimensions, plus 0.25 on every side). If nothing intersects, melee knockback is judged like any other knockback. If any bound is unknown or
  intersects, it stays UNCERTAIN (`ENTITY_NEIGHBORHOOD_UNKNOWN`).
  - Offline, attacker 2.5 blocks away on the side the knockback pushes from: 0 false INVALID (about 0.3 % UNCERTAIN when the walk turns
    toward the attacker), **90–97.5 % of cheat cases detected**.
  - Offline, attacker 0.4 blocks away (contact possible): 0 false INVALID, every transition UNCERTAIN, none detected.
  - Bounds stay unknown, so melee stays UNCERTAIN, for VARIABLE hitbox types, dynamic interpolation, mounted or locally displaced entities,
    unknown lifetime, and unknown scale (the bound grows to scale 16).
- Knockback outcomes are movement outcomes. An EXCLUDED influence annotates the same movement INVALID and is never a second strike.
- Offline results, rerun on Phase 12's combat-time markers (contract version 2): 0 false INVALID across 7 legitimate classes; **40/40
  detected in every non-melee cheat class** (cancel, horizontal-0, vertical-0, reverse, scale 40–95 %, delay past the pong). On the old
  frozen profile, cancel is still 0/40. (The version 1 corpus at 1 Hz markers gave 39/40 for some classes.)

## 2. Arena and stimulus sources

- Arena: the overworld platform at x 30..50, y 150, z 30..50, with a clear 10-block run-out lane, a wall lane, a ceiling lane (2-block-high
  ceiling), a water lane, a cobweb lane and a ladder lane. The Nether glass room at 0,100,0 for a second epoch.
- Velocity sources, recorded per trial, split by whether they can be deterministic:
  - **Non-melee (deterministic on the fenced jar):** `/papea2ac timing <p> velocity <x> <y> <z>`; `/damage <p> 1 minecraft:mob_attack by
    <entity>` with the entity more than 4 blocks away; arrows and Punch II from more than 4 blocks; snowball and egg; fishing-rod pull with
    the puller more than 4 blocks away; TNT; end crystal; creeper (after it is gone); wind charge.
  - **Melee at range** (attacker about 2.5 blocks away when the hit lands, plain-dimension type, no contact possible): a second player
    (normal, sprint, Knockback II), zombie. Deterministic when push exclusion succeeds.
  - **Melee in contact** (attacker within about 0.5 blocks, or a VARIABLE type such as a slime, or a mounted attacker): UNCERTAIN.
  - The iron golem is a large plain type; record whether its bound allowed exclusion.
  - Riptide, elytra and firework only as TC23 capability cells (unsupported modes stay visibly unsupported).
- Keep every other pusher (players, mobs, minecarts, boats) more than 4 blocks away in non-melee trials, or the trial becomes melee-class.
- Testers: Resistance V, Saturation, survival; `spawn_mobs false`; arena mobs summoned per trial.

## 3. Legitimate controls

Every tier-L cell, on the fenced jar: **0 INVALID and 0 positive evidence.**

| ID | Scenario | M/S | Latency | Expected (acceptance target) |
|---|---|---|---|---|
| V-L01 | Standing player receives a controlled SET velocity (0.4, 0.36, 0) | S | N | APPLIED (VALID with EXTERNAL_MOTION_EXPLAINS) |
| V-L02 | Same while walking, sprinting, jumping and sneaking | M | N | APPLIED |
| V-L03 | Repeated knockback: 5 hits 300 ms apart, then a 3-hit combo inside one second (non-melee: controlled velocity; melee: zombie) | M | N, D50, D150, D300 | Phase 7b gate: ≥ 90 % definite on non-melee. Overflow beyond 8 unresolved influences is UNCERTAIN_APPLICATION_OVERFLOW with zero weight |
| V-L04 | Knockback into a wall, under a 2-block ceiling, into water, into cobweb, onto a ladder | M | N | APPLIED or UNCERTAIN (collision reduces motion legally) |
| V-L05 | **Jump reset**: a human jumps exactly as they are hit | M | N, D150 | VALID: a jump on the application tick is legitimate input |
| V-L06 | **Knockback resistance**: netherite armor; `/attribute … knockback_resistance` 0.5 and 1.0; `explosion_knockback_resistance` | S/M | N | Scaled application explained by the attribute value in effect |
| V-L07 | Shield blocking a melee hit and an arrow (check on the 26.2 oracle what the blocker receives) | S | N | 0 INVALID |
| V-L08 | Two attackers hit in the same tick; explosion plus melee in the same tick | S/M | N | 0 INVALID; same-tick influences ordered as the profile defines |
| V-L09 | Creeper at 3 distances; TNT; end crystal; wind charge (TC05) | S/M | N, D150, D300 | APPLIED |
| V-L10 | Knockback while riding (horse, boat) and right after dismount | M | N | Vehicle modes UNSUPPORTED or qualified; never INVALID |
| V-L11 | Knockback during a server-to-client stall, applied late (the P11-F10 class) | M | S2, S9 | Influence OUTSTANDING until a later answered marker; never INVALID |
| V-L12 | Knockback then a dimension change, death or respawn before it is applied | M | N, D300 | World change retires the influence |
| V-L13 | Fishing-rod pull, arrows, Punch II from more than 4 blocks | M | N | APPLIED |
| V-L14 | Remote players on their usual clients (Lunar) in long free PvP | M | N | At least 1 000 knockback events; **0 INVALID**. Report the share of melee events judged (push exclusion succeeded) versus UNCERTAIN |
| V-L20 | Melee knockback at range: the attacker hits from about 2.5 blocks and stays back; the victim walks, sprints, and turns toward the attacker | M | N, D150 | **0 INVALID**; VALID where push exclusion succeeds, about 0.3 % UNCERTAIN when the walk turns toward the attacker |
| V-L21 | Melee in contact: the attacker stands inside 0.5 blocks, pushes, or the victim is cornered | M | N | 0 INVALID; UNCERTAIN expected |
| V-L22 | Melee from VARIABLE-type or mounted attackers (slime, magma cube, a zombie riding a chicken) and from a scaled attacker (`scale` 2.0) | M | N | 0 INVALID; UNCERTAIN where the bound is unknown |
| V-L15 | **Attacker slowdown (P13-F2 / P11-F27; owner decision 2026-10-03 14:17Z: fixed forward in Phase 11 before its baseline)**: the attacker sprint-hits, or hits with a knockback item, each non-mob target that slows the attacker on the client (another player, a boat, a minecart, a painting, an item frame, a leash knot, a shulker bullet, an end crystal). There is **no** pusher within 4 blocks for the painting, item frame, leash knot, bullet and end crystal cases. The end crystal explodes on the hit, so give that trial Resistance V and a separate explosion-knockback label. A sprint-hit on a mob is the control (no slowdown) | M | N, D150 | **0 INVALID and 0 positive evidence on any jar that carries the fix**: the first Phase 11 jar whose P11-F27 fix passes review (Phase 11 snapshot 5, a different design, waiting on the owner; jar `70240bc7…` from snapshot 4 was **rejected** by QA and the oracle and never qualified) and every later jar, or the Phase 13 jars that carry it. **Known false positive on jars without it**, including `837e1fa7…` and `b6699bed…`. Record it under P13-F2 / P11-F27, not as a finding of the feature under test. Once Phase 13 rebases onto the fixed Phase 11, P13-F2 reads "fixed in Phase 11" and this cell becomes a plain regression check |
| V-L16 | Attack and knockback in the same tick (server knockback drained first, then the attacker slowdown) | M | N | VALID (offline VALID) |
| V-L17 | Several attacks in one tick (fast clicking on a painting or player) | M | N | VALID, or UNCERTAIN beyond 8 slowdown variants; never INVALID |
| V-L18 | Knockback while standing still, with the pong delayed past the silent stretch | S | N, D300 | UNCERTAIN, never INVALID (the branching closure needs the fence inside the silent stretch) |
| V-L19 | A correction (teleport) after a knockback, before its fence | M | N | VALID or UNCERTAIN (fences are switched off for that interval) |

## 4. Blatant cheat settings (tier R) — fenced jar

| ID | Module / setting (descriptive) | Source | Latency | Expected (acceptance target) |
|---|---|---|---|---|
| V-B01 | Velocity horizontal 0 %, vertical 0 % (cancel) | Non-melee | N | **INVALID** on the first transition after the fence answer, when the context is complete. Influence EXCLUDED, reason FENCED_APPLICATION_EXCLUDED. New live evidence class, observe-only |
| V-B01m | As V-B01 | Melee at range | N | **INVALID** when push exclusion succeeds (offline 90–97.5 %); UNCERTAIN otherwise, with the reason |
| V-B01c | As V-B01 | Melee in contact | N | **UNCERTAIN (`ENTITY_NEIGHBORHOOD_UNKNOWN`)**. Reported as coverage, not as a miss |
| V-B02 | Horizontal 0 %, vertical 100 % | Non-melee / melee | N | As V-B01 / V-B01m / V-B01c |
| V-B03 | Horizontal 100 %, vertical 0 % | Non-melee / melee | N | As V-B01 / V-B01m / V-B01c |
| V-B04 | Explosion-only cancel (TNT, creeper, crystal) | Non-melee | N | As V-B01 |
| V-B05 | Reverse velocity (moves toward the source) | Non-melee / melee | N | As V-B01 / V-B01m / V-B01c. Moving toward the attacker can create contact, so expect more UNCERTAIN in melee |
| V-B06 | Velocity 0 % in sustained PvP | Melee | N, D150 | Per event: INVALID where push exclusion succeeds, UNCERTAIN in contact. Report the split and the attacker distance |

On a jar with the frozen profile (`PAPER_26_2`), every V-B cell is a **known bypass** (cancel still 0/40 offline under contract version 2).
The round header records the jar's live timing profile.

## 5. Subtle cheat settings (tier R) — measured

| ID | Module / setting | Expected on the fenced jar / what to measure |
|---|---|---|
| V-S01 | Horizontal 80–95 %, vertical 100 % | Non-melee: INVALID after the fence (offline 40/40 per scale on combat-time markers). Melee at range: INVALID where push exclusion succeeds. Melee in contact: UNCERTAIN |
| V-S02 | Horizontal 40–60 % | As V-S01 |
| V-S03 | "Chance" mode: cancel on 30–50 % of hits | Per event: each cancelled event INVALID (non-melee, or melee at range with push exclusion), each honoured one VALID |
| V-S04 | Automated jump reset | VALID: a perfect jump on the application tick is legitimate input. Not distinguishable by exclusion |
| V-S05a | Delayed application ("tick delay" 2–10 ticks) **while answering pings on time** | INVALID (offline 40/40 on combat-time markers): impossible for the vanilla FIFO |
| V-S05b | Delayed application **with the pong held back too** | VALID or UNCERTAIN: indistinguishable from latency. Expected bypass; its cost is a stale RTT and expired markers |
| V-S06 | Anticheat-specific bypass modes (record the mode name exactly) | Outcome per mode, split non-melee / melee at range / melee in contact |
| V-S07 | Ground-only or air-only cancel | As V-B01 / V-B01m |
| V-S08 | **Keep-sprint** (no slowdown and no sprint stop after the player's own hit) | **Known undetectable for now, on every jar.** VALID: attack cooldown and held-item knockback are not observed, so both branches stay open |
| V-S09 | Pong suppression (never answer pings) | UNCERTAIN; influences OUTSTANDING (P11-F10). Ack suppression is a resource/protocol matter (Phase 14/20), never a movement violation |

## 6. Latency-sensitive cells

Run V-L01, V-L03, V-L05, V-L09, V-B01, V-B05, V-S01 and V-S05a/b under N, D50, D150, D300, J, A, S2 and S9. Legitimate cells: 0 INVALID in
every profile. Report per profile: definite share (Phase 7b gate ≥ 90 % on non-melee), overflow count, OUTSTANDING influences, expired
markers, the time from velocity send to fence answer, and outcome reasons. After integration, report results under the combat-time marker
cadence (owner decision of 06:20Z) separately from 1 Hz stretches.

## 7. False-positive scenarios to target deliberately

| ID | Why it is risky |
|---|---|
| V-FP01 | Late application after a stall (P11-F10) |
| V-FP02 | Jump reset and sprint reset by skilled humans |
| V-FP03 | Knockback-resistance attributes and armor changing mid-fight |
| V-FP04 | Collisions eating the knockback (walls, ceilings, corners, water, cobweb, powder snow, honey) |
| V-FP05 | Multiple same-tick influences (multi-attacker, explosion plus hit), more than eight unresolved |
| V-FP06 | Vehicle, elytra and riptide transitions |
| V-FP07 | Death, respawn or dimension change before application |
| V-FP08 | Bundled packets: the velocity and a position correction in one flush |
| V-FP09 | Shield blocking and invulnerability ticks (a hit inside the invulnerability window sends no knockback) |
| V-FP10 | The attacker's own slowdown after sprint-hitting a player, boat, minecart, painting, item frame, leash knot, shulker bullet or end crystal (P13-F2 / P11-F27; a false INVALID on jars without the fix, which moves into Phase 11) |
| V-FP11 | Attack and knockback in the same tick (V-L16) |
| V-FP12 | Several attacks in one tick, more than 8 slowdown variants (V-L17) |
| V-FP13 | Knockback while standing, with the pong delayed past the silent stretch (V-L18) |
| V-FP14 | A correction after a knockback, before its fence (V-L19) |
| V-FP15 | A non-melee trial that accidentally has a pusher within 4 blocks (it becomes a melee-class trial; label it) |
| V-FP16 | The victim turns or walks toward the melee attacker after the hit (contact becomes possible) |
| V-FP17 | An attacker whose bound is unknown: VARIABLE types, unknown scale, mounted or locally displaced entities |

## 8. Acceptance targets

- Legitimate: **0 INVALID and 0 positive evidence** in every tier-L cell, on the fenced jar (and on any frozen-profile jar). At least
  1 000 legitimate knockback events in total and 200 per latency profile.
- Phase 7b gate: ≥ 90 % definite outcomes at 50/150/300 ms on non-melee sources.
- Blatant on the fenced jar, non-melee sources: **≥ 80 % of events INVALID per class** (V-B01–V-B05; offline 40/40 per class on
  combat-time markers).
- Melee at range (V-B01m and the melee-at-range rows of V-B02–V-B06): proposed gate **≥ 80 % INVALID** on events where push exclusion
  succeeded (offline 90–97.5 %); also report the share of melee events where it succeeded.
- Melee in contact (V-B01c and contact rows): reported as UNCERTAIN coverage. No gate.
- Cancelled knockback is live evidence, observe-only: 0 mitigation effects, as always.

## 9. Telemetry to capture

Phase 13 provides `VelocityRunner.Status` per session (`PacketEngine.velocityStatus(UUID)`; bounded to 64 influences and 48 lines):

- per influence: id (`m<wire ordinal>`), source (`velocity` / `explosion`), operation, vector as applied by the client, sent capture sequence;
- outcome APPLIED / PENDING / EXCLUDED / UNRESOLVED, with reason FENCED_APPLICATION_EXCLUDED / ALL_APPLICATION_ALTERNATIVES_EXCLUDED /
  NO_FENCE_YET / FENCE_NOT_CLAIMED_BY_PROFILE / EXPIRED / UNCERTAIN_TRANSITION;
- the fence (marker capture sequence, wire ordinal, answer sequence, answer nanos), the profile claim flag, the transition classification
  and interval;
- metrics: influences, applied, excluded, fencedExcluded, unresolved, pendingObservations.

A `/papea2ac velocity <p>` command is added at integration. Until then: `papea2ac validate <p> notable` and `papea2ac timing <p> view`.

The operator also records, per trial: the source class (non-melee / melee) and the nearest pusher distance; the timing profile of the jar
(`PAPER_26_2_FENCED` expected); for V-L15 / V-S08, the attacker's ATTACK packets (Phase 11 context ID, target type), the sprint flag from
`ServerboundPlayerInput` before and after the hit, and the held item; for tier R, the screen recording.

Per trial: the common commands, plus `papea2ac timing <p> view` every 2 s during knockback trials.

## 10. Run order

1. Offline, done by Phase 13: the velocity corpus on both profiles, rerun on the combat-time marker cadence (done for contract version 2).
2. VPS only on the exact Phase 13 candidate jar (fenced by default), after its offline qualification is complete: tier L under N, then the
   latency cells, then tier R (non-melee first, then melee).
3. Phase 13's offline work is complete (contract version 4). The VPS velocity round runs after the Phase 11 VPS run, which goes first once
   the owner says go, and only on Phase 13's exact candidate jar.
4. Any defect: new jar, rerun the affected cells plus the full tier-L set.
