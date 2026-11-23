# Phase 12 — Reach / hitbox / historical ray tracing — real-cheat VPS qualification matrix

Status: RESEARCH CATALOG (2026-10-03, revision 6: `PHASE12_REACH_CONTRACT_READY` **version 3** (`CONTRACT_CHANGED-8.md`,
`phase12-snapshot-3.tar`, 43 files, SHA-256 `0167394d885df3934668289ebaeaeb7d4d47c5aa16699c37922e1bbc6f273021`, on Phase 11 snapshot 2;
version 3 only removes a per-attack performance cost, adds `ReachRunner.Metrics.frontierRebuilds`, and changes no result, so every figure
below is from version 2's byte-identical corpus) and `phase12-to-qualification-01`). **The reach detector is not qualified.** Phase 12 is NOT READY: offline qualification is in progress, there is no VPS round
yet, and Phase 12 cannot be integrated before Phase 11 is approved, baselined, receipted and pushed. Rules R1–R13 are in thread 4's
`docs/PHASE_12_IMPLEMENTATION.md` (`Papea2AC-wip-phase12`); where this matrix and that document differ, the document wins and this matrix
is corrected. Inputs come from `PHASE11_COMBAT_CONTRACT_READY` version 2 (`CONTRACT_CHANGED-1.md`).

Shared rules (jar identity, tiers, trial protocol, latency profiles, statistics, VPS checklist): [QUALIFICATION_PROTOCOL.md](QUALIFICATION_PROTOCOL.md).

Governing specs: COMBAT_MODEL.md (B1 coherent observer scenes, B2 as amended, 64 scenes / 4096 work units per attack), LATENCY_COMPENSATION.md
(no RTT/2 rewind), TESTING.md TC20/TC21.

## 0. Decisions and contract facts that set the expectations

1. **B2 amended for exclusion only (owner, D12-1).** VALID needs a `CERTIFIED_CONCRETE` eye. The attacker's own position claims (± the
   2e-4 send threshold, every pose's eye height) may support INVALID only.
2. **Combat-time markers (owner, D12-2).** After any ATTACK, a PING is due every 50 ms for 3 s (`COMBAT_MARKER_WINDOW_NANOS`,
   `MARKER_MIN_GAP_NANOS`, `MARKER_MAX_PENDING` = 16). The newest answered PING before the attack proves every earlier clientbound packet
   applied (`ApplicationFrontier` MARKER_PROVEN); without one the window is HORIZON_ONLY. Since contract version 2, **every** answered marker
   before the attack bounds the target window (`ApplicationFrontier.all`, nested `TargetHull` bounds), not only the newest one.
3. **P11-F11 accepted as a known limitation.** In block-heavy areas some entities are untracked under the shared memory budget. Attacks on
   them report incomplete coverage: record a **known coverage gap**, never a miss or a detection failure.
4. **Player targets (R13).** Phase 11 v2 puts `TARGET_PLAYER_INFO_UNTRACKED` on every player target, but a reach INVALID on a player is
   still possible. PvP cells expect INVALID for cheats and 0 INVALID for legitimate play.
5. **REACH, ANGLE and INVALID_TARGET only.** Contract version 2 adds the block outline table (`OutlineShapeTable.paper26_2()`, all 1 196
   blocks), but it only strengthens VALID witnesses. **There is still no OCCLUSION INVALID**: a wall alone never produces INVALID.
6. **Observe-only.** Phase 12 produces no evidence yet. The planned category is `REACH_EXCLUSION` (DeterministicExclusion), wired only after
   offline and VPS qualification and review. Until then "0 positive evidence" holds trivially; **the outcome to record is the
   ReachValidation classification.**
7. **Offline corpus, contract version 2** (`docs/phase12-artifacts/reach-corpus-results.json`):
   - legitimate: **0 INVALID in 11 479 attacks** (stationary, walking, knockback, high latency); VALID on legitimate walking rose from 79 to
     1 119 of 3 455;
   - reach 6.0: 1 140/1 140 INVALID; reach 4.0: 1 075/1 377 (78 %); reach 3.5: 672/927 (72 %); hitbox +1.0: 2 388/4 321 (55 %, version 1
     figure, not rerun in the version 2 report);
   - backtrack: 179/3 462 INVALID at 4 ticks (5.2 %) and 420/3 461 at 8 ticks (12.1 %). Backtrack against a slowly moving target is still
     not caught;
   - interpolation hull against the exact client simulation: 54 259 checks, 0 escapes; client-jar geometry differential: 1.05 M cases,
     0 mismatches; a decision takes 0.9–1.5 µs; about 170 KB per full session.

Phase 11 contract v2 facts used here: riders, dismounted riders and the observer's vehicle are `VEHICLE_DEPENDENT` (never EXACT); a dead
target's pose is UNKNOWN until new pose data; attacks after a coverage break are counted and answer INCOMPLETE_COVERAGE; a spear sends a
target-less `PiercingAttack` (`stabs=`), never an ATTACK; the network target is not always the client's interpolation endpoint (P11-F24).

## 1. Pinned vanilla facts (from the contract; record in the trial sheet header)

- The client decides an attack with `pick(1.0F)` at the **start** of its tick; several clicks in a tick share one hit result. The rotation
  used is the one the same tick's movement packet carries (after the ATTACK in the stream).
- Eye = `lerp(1.0, xo, x)` plus the pose's float eye height; view from `calculateViewVector` with the Mth sine table.
- Classic pick: block raycast (OUTLINE, fluids ignored) to `max(blockRange, entityRange)`; the nearest pickable entity whose box (inflated
  by its pick radius, 0 for every living type) the ray clips wins if strictly closer than the block hit; kept only if `distance² < range²`.
- `entity_interaction_range` default 3.0 (0–64), `block_interaction_range` 4.5, `scale` 1.0 (0.0625–16), creative modifier +2.0.
- Paper accepts attacks up to `range + 3.0` from the box (`client-interaction-leniency-distance`). Server acceptance is not legitimacy, and
  Phase 12 never uses it.
- Hitboxes from `EntityGeometryTable.paper26_2()`: PLAIN / PLAIN_LIVING / AVATAR / BABY / VARIABLE. A VARIABLE type is never guessed.

## 2. Arena and targets

- Arena: the stone platform with glass walls at x 30..50, y 150, z 30..50 (overworld). Wool distance lines measured from the **target
  hitbox edge** at 2.5 / 2.9 / 3.0 / 3.1 / 3.3 / 3.5 / 4.0 / 4.5 / 5.0 / 6.0 blocks, and an occlusion lane (full stone wall, glass wall, fence
  row, iron-bars row, slab gap, one-block window).
- Targets: NoAI husk (static), cow with AI (moving), NoAI villager, slime size 4, baby zombie, end crystal, item frame, a target in a boat and
  in a minecart, a second real player (PvP).
- World: `spawn_mobs false`; testers get Resistance V; kill stray entities twice before each trial set.
- Paired tests: stand at a line, then attack; the operator logs the line ID with the trial.

## 3. Legitimate controls (tier L / C)

Every tier-L cell: **0 INVALID.**

| ID | Scenario | M/S | Latency | Expected (acceptance target) |
|---|---|---|---|---|
| R-L01 | Vanilla, static attacker, static NoAI husk at edge distance 2.0–2.9 | S | N | VALID with a witness on ≥ 95 % (proposed); 0 INVALID |
| R-L02 | Boundary hits at 2.95–3.0, including exact-boundary and grazing corner hits | S | N | VALID or UNCERTAIN (BORDERLINE allowed); 0 INVALID |
| R-L03 | Vanilla player tries to hit from 3.0–3.5 (client refuses at `distance² >= range²`, measured to the ray's clip point on the box, not the box's nearest point; at exactly 3.0 it sends nothing) | S | N | 0 ATTACK packets; swings only. Any ATTACK is a capture defect |
| R-L04 | Sprint-strafe, W-tap and circle-strafe vs moving cow | M | N, D150 | 0 INVALID; VALID share per class |
| R-L05 | Jump/crit attacks, falling attacks, stairs, slabs, ladders | M | N | 0 INVALID |
| R-L06 | Attacker crouching, crawling, swimming, gliding (if practical), riding a horse, in a boat | S/M | N | 0 INVALID; a passenger attacker is UNCERTAIN (ATTACKER_PASSENGER: its yaw can change inside the tick); other pose cells may be UNCERTAIN with a named reason |
| R-L07 | Creative attacker at 3.5–4.9 | S | N | 0 INVALID. Every creative attacker is UNCERTAIN (held-item path unknown, unchanged in contract version 2), whatever the distance |
| R-L08 | `/attribute @s minecraft:entity_interaction_range base set 4.5`, attacks at 4.0–4.4; then 2.0 and attacks at 1.5–1.9; then reset mid-fight | S | N, D300 | 0 INVALID; an attribute change sent after the newest answered marker makes the range unknown for that attack (UNCERTAIN RANGE_UNKNOWN) |
| R-L09 | `scale` 0.5 and 2.0 on the attacker; `scale` 2.0 on the target | S/M | N | 0 INVALID |
| R-L10 | Target teleported or knocked back just before the attack | M | N, D150 | 0 INVALID |
| R-L11 | Attacks through legal gaps: pane gaps, fence gaps, slab windows, a door slit | S | N | VALID where a clear ray exists (the version 2 outline table lets VALID through non-full blocks); never INVALID (no OCCLUSION exclusion) |
| R-L12 | Slime size 4, magma cube, armor stand, item frame, baby zombie, end crystal, mounted target; also an attack aimed at a dropped item or XP orb | S | N | 0 INVALID on pickable targets. The 31 VARIABLE types (slime, magma cube, armor stand, shulker, interaction, item frame, ender dragon, phantom, pufferfish…; `entity-geometry-summary.json`) are UNCERTAIN (TARGET_DIMENSIONS_UNSUPPORTED), never guessed. End crystal is supported (PLAIN). Baby mobs use the union box for exclusion. Item and XP-orb attacks are INVALID_TARGET (never pickable; the server also kicks); a vanilla client never sends them, so any such attack is a protocol anomaly, not a legitimate-play false positive |
| R-L13 | Crowd: 20+ chickens, attack the far ones | M | N | EVICTED / incomplete gives UNCERTAIN |
| R-L13b | Block-heavy area where some entities are untracked (P11-F11) | M | N | Known coverage gap; not a miss, not counted against detection |
| R-L14 | Attack right after a dimension change and after respawn | S | N | No cross-epoch scene; UNCERTAIN until coverage exists |
| R-L15 | PvP between two vanilla players: trading, combos, sprint reset, shield | M | N, D50, D150, D300, J, A | **0 INVALID** across all profiles (R13 allows INVALID on players, so this cell is a real test) |
| R-L16 | Target behind a wall that the attacker moves around | M | N, D150 | 0 INVALID |
| R-L17 | Target displaced client-side during interpolation: piston push, a moving vehicle, a >64-block snap while interpolating (P11-F24) | M | N | 0 INVALID; unreconstructable cases UNCERTAIN |
| R-L18 | Spear stabs at static and moving targets at 2–3 blocks, plus a charged (kinetic) hit | S/M | N, D150 | Each stab is a target-less `PiercingAttack` (`stabs=`), never an AttackContext and never validated. A spear in hand makes any ATTACK UNCERTAIN (ATTACK_RANGE_ITEM_UNSUPPORTED). Kinetic hits produce no observation (known limitation). 0 INVALID |
| R-L19 | Attacks with an `attack_range` item or an unknown held item | S | N | UNCERTAIN (ATTACK_RANGE_ITEM_UNSUPPORTED / HELD_ITEM_UNKNOWN); never INVALID |

## 4. Blatant cheat settings (tier R)

| ID | Module / setting (descriptive) | M/S | Latency | Expected (acceptance target) |
|---|---|---|---|---|
| R-B01 | Reach 6.0 vs static husk at edge distance 5.0–5.9 | S | N | **INVALID (REACH)**. Gate: ≥ 80 % of attacks (offline 100 %). Paper accepts these attacks and applies damage (`isWithinAttackRange` with leniency 3.0 accepts up to range + 3.0 from the box), so do not expect a no-damage observation |
| R-B02 | Reach 4.5 vs static husk at 3.6–4.4 | S | N | INVALID (REACH) on a share at or above the offline reach 4.0 figure (78 %); measured and compared with the corpus, no hard gate |
| R-B03 | Reach 4.0 vs moving cow, attacker sprinting | M | N, D150 | INVALID on a measured share (offline 78 %); UNCERTAIN with named reasons otherwise. No hard gate |
| R-B04 | Through-walls attack vs husk 2.5 blocks behind a full stone wall | S | N | **No OCCLUSION exclusion, even with the version 2 outline table.** Expect VALID or UNCERTAIN unless the attack also misses on REACH or ANGLE. Recorded known gap |
| R-B05 | Hitbox expansion +0.5 to +1.0 vs a target at 2.5 with the crosshair beside the box | S | N | INVALID (ANGLE) on a measured share (offline +1.0: 55 %); kept distinct from REACH |
| R-B06 | Teleport-style reach ("TP-aura") that moves the claimed position toward the target, attacks and moves back | M | N | One causal group with movement (TC20, contract rule 1), never two strikes. Outcome as `PHASE_12_IMPLEMENTATION.md` defines it |
| R-B07 | Reach 5.0 in PvP vs a moving real player | M | N, D150 | **INVALID (REACH)** expected (R13); rate reported and compared with the offline 4.0/6.0 figures |
| R-B08 | Reach module while holding a spear, and an ATTACK sent while holding a spear | S | N | Stabs are `PiercingAttack`, not validated; an ATTACK with a spear in hand is UNCERTAIN (R11). Report counts only |

## 5. Subtle cheat settings (tier R) — measured, no hard gate

| ID | Module / setting | M/S | Latency | What to measure |
|---|---|---|---|---|
| R-S01 | Reach 3.1 | S, M | N | INVALID / UNCERTAIN / VALID shares; certificate `minResidual`. No offline figure below 3.5; expect lower detection than reach 3.5 |
| R-S02 | Reach 3.2 | S, M | N, D150 | As R-S01 |
| R-S03 | Reach 3.3–3.5 with "chance" 30–50 % | M | N | Per-attack outcomes on the cheated attacks; compare with the offline reach 3.5 figure (672/927, 72 %) |
| R-S04 | Hitbox expansion +0.1 / +0.2 | S, M | N | ANGLE share; known low detection |
| R-S05 | Within-tolerance position shift toward the target | M | N | **TC21:** no raw-claim-only VALID witness (VALID needs CERTIFIED_CONCRETE) |
| R-S06 | Backtrack of about 4 and 8 ticks (≈ 200 and 400 ms), against slow and fast targets | M | N, D150 | Measured, no hard gate. Offline (all-markers bound): 5.2 % INVALID at 4 ticks and 12.1 % at 8 ticks. **Known gap: backtrack against a slowly moving target is not caught.** Record `frontier` (MARKER_PROVEN / HORIZON_ONLY, `ticksSincePong`) per attack, and the INVALID share by target speed and delay. HORIZON_ONLY during combat, with pings flowing, is a defect to report |
| R-S07 | Fake lag / ping spoof (delays outbound packets and pongs) | M | N | Must not create INVALID on the same player's legitimate attacks; report HORIZON_ONLY share |
| R-S08 | Reach only on the first hit of a combo, or only while sprinting | M | N | Per-attack and per-window rates |

## 6. Latency-sensitive cells

Run R-L01, R-L04, R-L10, R-L15, R-B03, R-S01, R-S03 and R-S06 under N, D50, D150, D300, J, A and S2. Run R-L08 and R-L10 under D300 and S2.
Every legitimate cell: **0 INVALID**. Record per profile the UNCERTAIN share and reasons (PENDING_EXPIRED after 3 s, SCENE_LIMIT, WORK_LIMIT,
TARGET_*, EYE_*), and the MARKER_PROVEN / HORIZON_ONLY split.

## 7. False-positive scenarios to target deliberately

| ID | Why it is risky |
|---|---|
| R-FP01 | Target interpolation: the client shows the target between network endpoints |
| R-FP02 | Target knockback/teleport between the last network update and the attack |
| R-FP03 | Attacker eye height changes (crouch toggle, scale, pose, mount) inside the attack tick |
| R-FP04 | Attribute or game-mode change racing the attack under latency |
| R-FP05 | Unusual hitboxes (VARIABLE types, scaled entities, baby mobs, mounted targets) |
| R-FP06 | Grazing hits on box corners and exact boundary distances |
| R-FP07 | Non-full blocks near the ray (panes, fences, bars, slabs, doors, leaves, snow layers) |
| R-FP08 | Remote high-jitter player and server-to-client stalls (the P11-F10 class) |
| R-FP09 | Client mods that change rendering (hitbox display, freelook, zoom) but not the protocol |
| R-FP10 | Crowds that evict deep history; respawn and dimension change |
| R-FP11 | The client's endpoint differs from the network target: pistons, vehicles, far snaps, rotation-only moves (P11-F24) |
| R-FP12 | Spear stabs: no target id on the wire; the server chooses the targets |
| R-FP13 | Sprint-hitting a painting, item frame, boat, minecart, leash knot or player slows the attacker on the client. On jars without the fix (P13-F2 / P11-F27, being fixed forward in Phase 11 before its baseline; present in `837e1fa7…` and `b6699bed…`) this is a **known movement false INVALID** in the same causal group as the attack. Not a reach finding; see [P13_VELOCITY_MATRIX.md](P13_VELOCITY_MATRIX.md) V-L15 |
| R-FP14 | Another entity in front of the target (R8: not evaluated; VALID records `interposedEntitiesNotEvaluated`) |

## 8. Acceptance targets

- Per attack: one `ReachValidation`. VALID = a robust witness; INVALID = every admissible scene fails (certificate); UNCERTAIN = at least one
  reason. Miss classes REACH / ANGLE / INVALID_TARGET kept apart (no OCCLUSION exclusion yet; the outline table only widens VALID).
- Legitimate gate: **0 INVALID** across all tier-L cells. Minimum 3 000 legitimate attacks in total and 300 per latency profile (rule of
  three: ≤ 0.1 % overall, ≤ 1 % per profile at 95 %).
- Blatant gate: **R-B01 (reach 6.0) ≥ 80 % INVALID.** R-B02, R-B03, R-B05 and R-B07 are measured and compared with the offline corpus
  (reach 4.0: 78 %; hitbox +1.0: 55 %), with no hard gate. R-B04 (walls) is a recorded known gap.
- Subtle (R-S01–R-S04) and backtrack (R-S06): measured against the offline figures (reach 3.5: 72 %; backtrack 5.2 % / 12.1 %); no gate.
  Slow-target backtrack is a recorded known gap.
- Evidence: none produced yet (`REACH_EXCLUSION` is planned). Record the classification. Always 0 mitigation effects.

## 9. Telemetry to capture

Phase 12 result per attack (`ReachValidation`, keyed by `(SessionKey, sequence)`, attackId `A<sessionEpoch>:<sequence>`): `status`,
`missClasses`, `reasons` (of the 27), `eyeSource`, `frontier` (`ApplicationFrontier`: status, markerSequence, pongSequence, ticksSincePong; version 2 bounds the window with every answered
marker, `ApplicationFrontier.all`),
`targetType`, `targetNetworkId`, `targetIncarnation`, `scenes`, `workUnits`, `witness` (scene, eye, yaw, pitch, admissible position box,
maxEntryDistance, range, interposedEntitiesNotEvaluated) or `certificate` (scenes, classes, minDistance, minResidual), `entityRange`,
`scale`, `profileId`, `notes`.

Where to read it: `ReachRunner.Status` (attacks, valid, uncertain, invalid, expired, pending, ≤ 12 recent, internalErrors, lastError); the
reach status line in `/papea2ac status` (`PacketEngine.reachStatusLine()`); the reach lines appended to `/papea2ac combat <p>`. The runner
retains ≤ 64 results, and a bounded per-attack qualification export (beyond the 12-line ring) is **not built yet**. Poll `combat` every
2–3 s during trials, or replay `ReachCapsule` (64 results per session) offline. The `/papea2ac status` reach line also carries byMiss, byEye,
the markerProven / horizonOnly split and combatMarkers.

Phase 11 inputs (contract v2): `EntityLookup.status`, `EntityKey`, `EntityRevision` (`positionStatus` EXACT / INTERPOLATION_DEPENDENT /
VEHICLE_DEPENDENT / UNSUPPORTED, `cause`, `pose`, `vehicleId`), `CombatProvenance.Attacker` and `Timing`, `CombatCompleteness` reasons
and standing limitations, and the `stabs=` counter.

For the operator only, labelled SERVER_STATE (never engine input): the server-side eye-to-box distance at the attack tick.

Per trial: the common commands (protocol §7) and the screen recording for tier R. Per round: attacks, VALID / UNCERTAIN (by reason) /
INVALID (by miss class), MARKER_PROVEN / HORIZON_ONLY, mitigation effects (must be 0), exceptions, with the jar hash.

## 10. Run order

1. Offline, by Phase 12: native geometry differential, corpus, replay (in progress).
2. VPS only on the exact integrated candidate jar that contains Phase 12, after Phase 11 is baselined and Phase 12's offline qualification
   is complete: tier L under N, then latency cells, then tier-R blatant, then subtle.
3. Any defect: new jar, rerun the affected cells plus the full tier-L set.
