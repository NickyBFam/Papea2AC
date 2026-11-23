# Real-cheat VPS qualification protocol (shared by Phases 12–18)

Status: RESEARCH CATALOG, prepared 2026-10-03 by the qualification thread on `wip/qualification` (base: Phase 10 receipt
`59caec5a0b4bde0c13dadca1807c0b0f3f039a7d`). Nothing here is committed, baselined or approved.

This document holds the rules every per-phase matrix relies on. The matrices are:

| Phase | Matrix | Contract token it waits on |
|---|---|---|
| 12 Reach / hitbox / ray tracing | [P12_REACH_MATRIX.md](P12_REACH_MATRIX.md) | `PHASE12_REACH_CONTRACT_READY` (published, version 3, snapshot 3) |
| 13 Velocity / anti-knockback | [P13_VELOCITY_MATRIX.md](P13_VELOCITY_MATRIX.md) | `PHASE13_VELOCITY_CONTRACT_READY` (published, version 4) |
| 14 Timer | [P14_TIMER_MATRIX.md](P14_TIMER_MATRIX.md) | `PHASE14_TIMER_CONTRACT_READY` (published, version 2, snapshot 4; `TickCadence*` names) |
| 15 Rotation analysis | [P15_ROTATION_MATRIX.md](P15_ROTATION_MATRIX.md) | Phase 15 rotation contract (no handoff token published yet) |
| 16 Combat heuristics / KillAura | [P16_KILLAURA_MATRIX.md](P16_KILLAURA_MATRIX.md) | Phase 16 contract (phase not opened) |
| 17 Click analysis / AutoClicker | [P17_AUTOCLICKER_MATRIX.md](P17_AUTOCLICKER_MATRIX.md) | Phase 17 contract (phase not opened) |
| 18 Placement / Scaffold | [P18_SCAFFOLD_MATRIX.md](P18_SCAFFOLD_MATRIX.md) | Phase 18 contract (phase not opened) |

These matrices are the single real-cheat qualification source for Phases 12–18. Phase threads do not write their own. They send
phase-specific input (detector expectations, uncertainty cases, telemetry, false-positive scenarios) as findings addressed to
`qualification` in `Papea2AC-coordination\findings\`, and the qualification thread folds them in. The copies in
`Papea2AC-coordination\qualification\` are the published versions.

## 1. What this catalog does and does not claim

- **No detector exists yet.** As of 2026-10-03, Phase 11 (combat engine, descriptive only) is uncommitted WIP with VPS requalification of
  jar `837e1fa7…` pending, and Phases 12–18 have no frozen contract. Every "expected detector behavior" cell in the matrices is an
  **acceptance target the phase must be measured against**, not a description of existing behavior.
- Where an expected outcome depends on a decision only the phase contract can make, the cell says **WAITING ON `<token>`** and states the
  question. Those cells must be filled from the published contract, never guessed.
- Detection numbers are never assumed. Each matrix lists what to count; the phase reports what it measured, with denominators.
- Production stays OBSERVE_ONLY. P10-F1 is unchanged: production transport is CAPTURE-ONLY, every real session latches
  EXPIRED_OBSERVE_ONLY, and G1 suppresses any effect. **Every trial in every matrix, cheat trials included, must show 0 mitigation effects.**
  An effect is a defect, not a detection.

## 2. Stimulus tiers

| Tier | Meaning | Used as |
|---|---|---|
| **L** — legitimate | Vanilla 26.2 client (or the remote player's usual client, e.g. Lunar, with its version and mods recorded) played honestly | False-positive denominator; the main gate |
| **R** — real cheat client | A publicly released cheat client/mod, unmodified, at a recorded build and config | Adversarial stimulus; measures detection and bypasses |
| **C** — controlled stimulus | Server-side harness commands that already exist (`/papea2ac timing <p> velocity`, `… teleport`, `/attribute`, `/tick`) | Exact, known ground truth for boundary and latency cells |

Rules:

- **A cheat client is never a correctness oracle** (TESTING.md, NativeScenarioHarness). Its configured setting is the trial's *label*; whether a
  given packet was actually impossible is established independently (replay geometry, the pinned server/client jars as oracle, or the
  phase's exclusion certificate). A cheat setting of "reach 3.1" does not prove that every attack was beyond 3.0.
- No in-house cheat client or client-side injector exists. If a phase needs a precisely controlled client-side stimulus that tiers R and C
  cannot provide, that is a tooling decision for the owner; the matrices mark such cells "needs tier-C client tooling (not available)".
- Candidate tier-R clients are open-source Fabric clients that historically ship the relevant modules (for example Meteor Client, Wurst,
  LiquidBounce). **Whether any of them supports Minecraft 26.2, and the exact module and setting names, must be verified on the build actually
  used.** Module names in the matrices are descriptive ("reach distance", "horizontal velocity %"); the trial sheet records the client's real
  setting names and an exported config file.

### Cheat-client hygiene (owner actions)

1. Obtain the client only from its official source repository/release page, or build it from a tagged commit. Record name, version, Minecraft
   version, loader version, and the SHA-256 of every mod jar in the instance.
2. Run it in a **separate launcher instance** (own game directory), ideally in a VM or a separate Windows user: cheat-client binaries are a
   common malware vector. Never point the owner's everyday instance at it.
3. Use it only on the owner's qualification VPS. Never connect that instance to any third-party server.
4. Export the client's config after setting up each trial set; record its SHA-256 in the trial sheet; archive it with the artifacts.
5. Turn off every module that the trial does not test (a stray Sprint/NoFall/AutoArmor module contaminates other phases' labels). The trial
   sheet lists **all** enabled modules, not only the tested one.

## 3. Jar identity — never mix evidence between jars

1. Before staging: compute the local SHA-256 of the candidate jar. After copying: `sha256sum /opt/papea2ac/server/plugins/Papea2AC.jar` on the
   VPS. Both must match; record both in the round header.
2. At server start and again before stop, the evidence helper logs `sha256sum plugins/Papea2AC.jar` and the `papea2ac status` profile lines
   (physics/collision/timing profile digests).
3. **One jar per round.** Swapping the jar ends the round. A new jar starts a new round with a new evidence log and a new server log.
4. File names carry the first 8 hex digits of the jar hash:
   - evidence transcript: `real-cheat-evidence-jar<jar8>-r<N>.log`
   - server log: `real-cheat-server-vps-jar<jar8>-r<N>.log`
   - trial sheet: `real-cheat-trials-jar<jar8>-r<N>.csv`
   - client configs: `client-configs-jar<jar8>-r<N>/`
   They live under the owning phase's artifact folder (`docs/phaseNN-artifacts/real-cheat/`), created by that phase's thread.
5. Result tables are keyed by jar. Counts from different jars are never summed; cross-jar comparisons are side by side with both hashes.
6. A defect found on jar A and fixed in jar B requires the affected cells to be rerun on jar B (as with P11-F10).
7. **Offline before VPS.** The VPS is not opened for preliminary work while a phase's offline qualification is incomplete (owner rule). Every
   live round qualifies the exact candidate jar of the phase it belongs to. There are no standalone "baseline" rounds on earlier jars. A
   question such as "what do Phases 8–11 already report for this cheat" is answered offline, from replay or synthetic traces, by the owning
   phase.
8. **Superseded rounds.** When a jar is superseded (a fix changes production semantics on a qualified path), its live results stay in the
   artifacts, labelled SUPERSEDED with the jar hash and the reason, and are never merged into the new jar's counts. Currently: the Phase 11
   VPS qualification of `b6699bed23fe1ce2cf85337930999b5637b4bb62742b9a5430476c3db1707c9c` is superseded for baselining by the P11-F27 fix;
   the Phase 11 Lead requalifies on the VPS the first jar whose P11-F27 fix passes review (snapshot 5, a different design waiting on the
   owner), (sprint and knockback around boats, minecarts, item frames and paintings, plus
   a PvP/mob combat smoke). **Rejected jars** are a separate case: jar
   `70240bc7d002368e70d6f1a9c6d8b24facff3504461baabef703f7a11e10e7e7` (Phase 11 snapshot 4, `CONTRACT_CHANGED-7.md`) failed QA and the
   oracle before any live round. It is recorded as REJECTED, never qualified, and nothing is rebased onto Phase 11 snapshot 4.

### Round header (first lines of every evidence log)

```text
# Papea2AC real-cheat qualification — Phase <NN> — round <N>
# Jar SHA-256 (local / VPS): <64 hex> / <64 hex>
# Paper: 26.2 build <b>   Java: <version>   Mode: OBSERVE_ONLY   qualificationEnforce flag: absent
# Clients: <account> = <client, version, loader, mods + SHA-256>; <account> = ...
# Cheat config files: <file> sha256=<...>
# Latency injection: none | <exact tc/clumsy commands>, applied <UTC> removed <UTC>
# World: <seed/arena coords>, gamerules changed: <list>
# Redaction: IPv4 replaced by <ip-redacted>; RCON password scan: <count> occurrences
```

## 4. Trial protocol

1. **Pre-register** every trial as a row in the trial sheet *before* running it: trial ID, tier (L/R/C), client + module + exact settings,
   scenario ID from the matrix, moving/static, latency profile, target, expected outcome class, operator, player.
2. Toggle cheat modules **only in the quiet gap between trials**, never inside one. The player confirms the toggle in chat ("ON <module>").
3. The operator brackets the trial with RCON: `say [Q] <trialId> START` and `say [Q] <trialId> END`. These appear in the server log, the evidence
   log, and the player's screen.
4. **Screen recording (recommended for tier R):** the cheat player records the client screen (e.g. OBS) with the module-list HUD visible. The
   `[Q] … START/END` chat lines align it with server time. This is the only independent record of when a module was really active.
5. Leave at least 5 s between trials, so that windowed features and correlation groups (30 s window) are attributable. Any phase with
   a longer window states its own gap.
6. Snapshot commands run at START, at END, and on the cadence the matrix states (see §7).
7. A trial is **void** (kept in the log, excluded from counts, with a reason) if: the label was wrong, an extra module was active, the
   player disconnected, the jar changed, or the latency profile was not as declared. Voiding is decided from the record, not from the outcome.
8. **Known false positives on earlier jars** are recorded under their finding ID and are not counted against the feature under test.
   Currently: P13-F2 / P11-F27. Sprint-hitting, or hitting with a knockback item, a painting, item frame, boat, minecart, leash knot or
   shulker bullet with no pusher within 4 blocks gives a movement INVALID on jars without the fix, including `837e1fa7…` and `b6699bed…`
   (see P13 V-L15). By owner decision (2026-10-03 14:17Z) the fix moves forward into Phase 11 before its baseline, so every jar from the
   new Phase 11 final jar onward must show 0 INVALID there. The round header states whether the jar carries the fix. Such cells ride the normal candidate-jar
   runs; there is no separate round for them.
9. Legitimate trials are labelled legitimate only if the player used no assistance at all. A remote player's usual client mods (minimap,
   zoom, freelook, FPS mods) are recorded and are part of the false-positive surface, not a reason to void.

## 5. Latency profiles

Natural latency is always recorded (`papea2ac timing <p> view`: last/min/smoothed/jitter/max RTT). Artificial latency is claimed **only**
when injected, with the exact command logged.

| ID | Profile | How (to be confirmed by the owner per session) |
|---|---|---|
| N | Natural internet path | Nothing injected; record RTT (Phase 11 observed min 36.5 / smoothed 44.7 / max 92.5 ms) |
| D50 / D150 / D300 | Added one-way server→client delay (Phase 7b profiles) | `tc netem` on the VPS, filtered to traffic from port 25565 |
| J | Jitter (e.g. 80 ± 40 ms) | `tc netem delay 80ms 40ms distribution normal` |
| A | Asymmetric (client→server delay only) | Client-side tool on the player's PC (e.g. clumsy), or VPS ingress shaping via ifb |
| S500 / S2 / S9 | Stall of 0.5 s / 2 s / 9 s then burst | Short full-delay netem change or a timed packet hold; the 9 s case reproduces the P11-F10 condition |
| P1 | 1 % loss (TCP retransmit bursts) | `tc netem loss 1%` |
| T | Server-side tick stall | A heavy, documented server workload or `/tick freeze` for a recorded duration (not network latency) |

Injection is a temporary VPS change: apply it only on the owner's go for that session, log `tc qdisc show` before and after, and remove it
before the end-of-session checklist. Filter by port so unrelated VPS services are never delayed.

## 6. Counting and statistics

- Report **VALID / UNCERTAIN / INVALID separately**, per event (attack, knockback, window…), per player-hour, and per latency profile. An
  all-UNCERTAIN result never counts as success (TESTING.md).
- Zero false positives in *n* independent legitimate events bounds the false-positive rate at about **3/n** (95 %, rule of three). Each matrix
  states its minimum *n* from this.
- Detection rates use a Wilson 95 % interval and name their denominator. Subtle-cheat cells have **no pass threshold**: they are measured and
  reported, including the UNCERTAIN reasons, so bypasses are known rather than assumed absent.
- For behavioral phases (15, 16, 17 and the scaffold features of 18): the qualification corpus is **fixed and labelled before tuning**; the
  held-out set is split **by session and by player**, never by window within a session; nobody tunes on held-out data; the number of distinct
  legitimate humans is reported as a limitation (currently 2–3 people).
- Correlated findings from one root (TC20) count once. A reach finding and a movement finding from the same spoofed position are one group.

## 7. Telemetry common to every matrix

Available today (frozen or Phase 11 WIP commands), polled by a VPS-side helper (`systemd-run`, never a local loop):

| What | Command | Cadence |
|---|---|---|
| Engine-wide counters, profiles, evidence, combat line | `papea2ac status` | Trial START/END + every 30 s |
| Movement validation, notable results | `papea2ac validate <p> view` and `… notable` | Trial END + every 30 s |
| Timing, RTT, tick ends, influences, markers | `papea2ac timing <p> view` | Every 10 s |
| Combat contexts (12 most recent only) | `papea2ac combat <p>` | Every 2–3 s during combat trials (the ring holds 12) |
| Evidence ingests | `papea2ac evidence <p> view` and `… recent` | Trial END |
| Mitigation state (must stay inert) | `papea2ac mitigation <p>` | Trial END |
| Server log | `real-cheat-server-vps-jar<jar8>-r<N>.log` (RCON client lines filtered out) | Whole round |

- RCON replies longer than 4 KiB arrive in several packets. The helper must read until a short timeout, and pass batched commands as a bash
  array (see the Phase 11 helper fix).
- `papea2ac recording <p> on` has produced 75-byte capsule stubs before. Verify capsule size before relying on it for offline replay.
- **Each phase must add its own per-event export.** A 12-entry diagnostic ring polled over RCON loses events at combat rates. Each matrix
  lists the per-event fields it needs; the phase thread decides how to expose them (bounded log, replay capsule, export command).

## 8. VPS session checklist

Start:

1. Jar staged with the port closed; hashes recorded (§3).
2. Server started as the `papea2ac` systemd unit in OBSERVE_ONLY, without `-Dpapea2ac.qualificationEnforce=true`.
3. `papea2ac status` shows `effectsSuspended=true` or OBSERVE_ONLY, `sessions=0`.
4. Whitelist holds only that round's accounts; a temporary second player is added and removed in the log.
5. `ufw allow 25565/tcp` only on the owner's go. RCON stays local.
6. Natural spawning is set per matrix (usually `spawn_mobs false`, Easy difficulty when hunger matters, Resistance V on testers if damage would
   end a trial early).

End:

1. Final `papea2ac status` after all sessions close (per-session counters vanish with the session).
2. Remove latency injection and log `tc qdisc show`.
3. Stop the server; check the log says all dimensions saved; `systemctl reset-failed papea2ac`.
4. Remove the ufw rule; verify from outside that 25565 and 25575 do not answer.
5. Restore the whitelist; leave unrelated services (web app on 127.0.0.1:3000, tailscale, relay) untouched.
6. Before copying logs into artifacts: replace IPv4 addresses with `<ip-redacted>` (keep the Java version string), scan for the RCON
   password, and drop RCON client noise lines.

## 9. People

- Owner: `Emojiado`. Known remote testers: `iDTR` (Lunar Client; in-game messages in Spanish), `PotSG_` (temporary whitelist).
- Every tester is told beforehand that the session uses a cheat client on the owner's account and that it is recorded.
- The cheat client runs on the owner's account only, unless the owner decides otherwise.
