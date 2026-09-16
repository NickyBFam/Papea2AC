# Mitigation and safe setbacks

## Decision G1: intents with preconditions

**DECISION:** A worker emits an immutable MitigationIntent; only the Paper main-thread executor applies game changes. The intent is conditional, expiring and idempotent. Detection never executes punishment commands.

**WHY:** A correct asynchronous finding can become stale before an action reaches the server.

**ALTERNATIVES:** Teleport directly from packet thread; always teleport to last received coordinates; replay a stored Bukkit Location later.

**TRADEOFFS:** Some justified actions expire without executing. Safety and coherent recovery take priority over guaranteed immediate correction.

## Intent and validation

Intent includes unique action ID, session and all epochs, transport health generation, source evidence/decision IDs, durable replay receipt, proposed safe anchor, permissible current-state revision/range, world identity, deadline in monotonic/server observations, reason, policy version and action type. Main thread checks identity, online state, current world, death/respawn/vehicle transition, current health latch, pending teleports, permissions/exemptions and action expiry before any API call. Rejections return typed outcomes to the actor.

The health check and effect admission use one atomic generation/state transition: HEALTHY(g) -> EFFECT_ADMITTED(g, actionId). Gap invalidation atomically marks either healthy or admitted state unhealthy and advances generation. If invalidation wins before admission, execution is rejected. If admission wins, the main thread rechecks immediately before the platform call and aborts if already invalidated, but cannot undo an effect whose platform call has begun. This is an explicit linearization boundary, not a guarantee of rollback across concurrent network failure. Capture both admission and outcome; any invalidation during an admitted effect forces subsequent resync and forbids follow-up effects. No cross-thread lock is held while calling Paper or invoking plugin callbacks.

SafeAnchor is a previously supported position/pose with server compatibility, collision clearance, world revision, trusted velocity context and age. Latest claimed position is not automatically safe. Immediately before execution, main thread verifies destination against current loaded world and platform restrictions. Do not synchronously load a chunk. If block changes, unload or mode changes invalidate the anchor, reject and resync; do not choose an arbitrary nearby coordinate.

SafeAnchor is a model value from a certified concrete trajectory and correlated accepted server state. Mitigation implements model SafeAnchorValidationPort using world/collision, and core injects it into the platform executor. The main thread captures a bounded immutable destination region; the validator uses the same shared geometry. Reject unknown coverage or a changed world revision. Maximum anchor age is 2 seconds, maximum correction distance 8 blocks; these bound staleness/player disruption, not cheat thresholds. Exceeding either produces RESYNC_REQUIRED, no long-distance fallback.

Only qualified already-loaded destinations are eligible. Platform prepares any required loaded-chunk lease without waiting, rechecks world/entity/mode and executes through a profile-proven no-synchronous-load path. Do not assume ordinary teleport avoids loading. If the adapter cannot guarantee that path, reject the intent; general teleportAsync may load asynchronously but is not a substitute for a fresh final precondition check. An optional asynchronous preparation may finish after expiry and then must be discarded. Never join/get its future on main. [Paper teleport documentation](https://docs.papermc.io/paper/dev/entity-teleport/).

Initial setback profile is on-foot only; reject vehicle/elytra transitions rather than implicitly dismount/stop gliding. Preserve rotation; capture fall-distance/velocity/reset side effects and apply only the reviewed target-profile correction semantics. Foreign teleport/respawn advances a platform state generation: if before final execution, supersede the intent; if after an already-started effect, the foreign action wins and reconciles a new baseline. Record both outcomes without violation weight. Paper/plugin cancellation returns REJECTED, not a fake successful anchor.

## State machine

IDLE -> PROPOSED -> WAITING_CAPTURE -> QUEUED -> EXECUTING -> SENT_AWAITING_CONFIRM -> CONFIRMED -> RECOVERY -> IDLE.

PROPOSED/WAITING_CAPTURE/QUEUED may end as EXPIRED, REJECTED or SUPERSEDED. A send failure becomes RESYNC_REQUIRED. One active correction per player; eight predecessor IDs retained to identify duplicates. Initial intent deadline is 500 ms from proposal; pending client confirmation times out after five seconds, bounded by session validity. These are operational limits, not proof that a slow client cheated.

Execution requests a platform teleport with explicitly resolved absolute target and intended relative-flag semantics. Its actual outgoing correction packet and teleport ID are captured in the normal pipeline. Do not synthesize actor state from the requested target before observing send and confirmation. Actor records pre/post hypotheses during the transition. Generic network ACK cannot substitute for teleport confirmation. Duplicate/stale confirmations are idempotent or diagnostic; they cannot confirm a newer correction.

## Feedback-loop prevention

Tag own teleports and optional velocity effects with action IDs. During recovery, dependent movement/velocity validation remains uncertain until a coherent confirmed baseline exists. Do not award violation weight for motion caused by the anticheat itself. Preserve look direction unless a justified platform correction requires otherwise; velocity changes require independently tested mode-specific policy and are disabled initially.

Only one correction may be sent in a two-second cooldown by default. Three failed corrections within 30 seconds disable further setbacks for that session and raise an operational alert. A loop breaker does not ban. Main-thread queue saturation rejects the action without blocking. Shutdown invalidates all queued intents before hooks are removed.

Alerts are informational and rate-limited. Resynchronization is recovery, not punishment. Resource/protocol disconnection has its own policy and recorded reason. Damage rollback and retroactive inventory/placement cancellation are outside the initial design; each would require dedicated transaction semantics rather than a generic setback.

Test stale intents across reconnect/world change, destination obstruction, cancelled teleport by another plugin, write failure, delayed/duplicate confirm, capture failure, queue overload, death, vehicle change and disable. Every intent must reach exactly one terminal execution outcome, even if its subsequent client confirmation is unavailable.

## Decision G2: global effect admission and emergency mode control

**DECISION:** All effects pass one platform main-thread admission arbiter in addition to session generation checks. It owns runtime mode and a global generation. **WHY:** Per-player loop breakers cannot stop a correlated server fault. **ALTERNATIVES:** Wait for every actor to acknowledge a switch; restart the server to disable effects. **TRADEOFFS:** Conservative correlated thresholds can suspend enforcement during a real coordinated attack; suspension is safer than mass corrections.

Modes: DISABLED (no new AC work beyond safe shutdown/control), OBSERVE_ONLY (default; capture/validate but no effects), ENFORCE (qualified and human-enabled). Permissioned restriction immediately updates global mode/generation at main-thread command execution, before notifying actors. Actor notifications/config/control records are sequenced; global arbiter also records admission-order outcomes, so replay includes the actual controlling outcome without assuming a global order across connections. Re-enable requires explicit command and current healthy coverage/profiles; no automatic timer reset.

Guard window 10 seconds: suspend before admitting the next effect if distinct actionable sessions >= max(3,ceil(0.20*N)), where N is eligible sessions at window start. A second burst gate suspends at 3 distinct sessions in 1 second, regardless of N. Count proposed actionable decisions before capture/queue delay and before execution, not only completed setbacks. Keep a bounded distinct-session window (at most admitted capacity) and emit GlobalAnomalySuspend; no cheating weight. Explicit correlated fault tests must show suspension before individual three-failure loop breakers fire. These thresholds are reviewable configuration, not measured optimal settings.

Main-thread admission serializes global checks with effect starts; per-session transport invalidation still uses the atomic race rule above. Already-started platform calls cannot be undone, but no new effect starts after the global restrictive transition. A stalled main thread cannot execute either effects or the command; report control latency rather than promise a wall-clock response during a stall. Core supervises health and proposes restriction; bounded control queues have an out-of-band restrictive latch. No engine depends on the platform or directly invokes this arbiter.
