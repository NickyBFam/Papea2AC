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

## State machine

IDLE -> PROPOSED -> WAITING_CAPTURE -> QUEUED -> EXECUTING -> SENT_AWAITING_CONFIRM -> CONFIRMED -> RECOVERY -> IDLE.

PROPOSED/WAITING_CAPTURE/QUEUED may end as EXPIRED, REJECTED or SUPERSEDED. A send failure becomes RESYNC_REQUIRED. One active correction per player; eight predecessor IDs retained to identify duplicates. Initial intent deadline is 500 ms from proposal; pending client confirmation times out after five seconds, bounded by session validity. These are operational limits, not proof that a slow client cheated.

Execution requests a platform teleport with explicitly resolved absolute target and intended relative-flag semantics. Its actual outgoing correction packet and teleport ID are captured in the normal pipeline. Do not synthesize actor state from the requested target before observing send and confirmation. Actor records pre/post hypotheses during the transition. Generic network ACK cannot substitute for teleport confirmation. Duplicate/stale confirmations are idempotent or diagnostic; they cannot confirm a newer correction.

## Feedback-loop prevention

Tag own teleports and optional velocity effects with action IDs. During recovery, dependent movement/velocity validation remains uncertain until a coherent confirmed baseline exists. Do not award violation weight for motion caused by the anticheat itself. Preserve look direction unless a justified platform correction requires otherwise; velocity changes require independently tested mode-specific policy and are disabled initially.

Only one correction may be sent in a two-second cooldown by default. Three failed corrections within 30 seconds disable further setbacks for that session and raise an operational alert. A loop breaker does not ban. Main-thread queue saturation rejects the action without blocking. Shutdown invalidates all queued intents before hooks are removed.

Alerts are informational and rate-limited. Resynchronization is recovery, not punishment. Resource/protocol disconnection has its own policy and recorded reason. Damage rollback and retroactive inventory/placement cancellation are outside the initial design; each would require dedicated transaction semantics rather than a generic setback.

Test stale intents across reconnect/world change, destination obstruction, cancelled teleport by another plugin, write failure, delayed/duplicate confirm, capture failure, queue overload, death, vehicle change and disable. Every intent must reach exactly one terminal execution outcome, even if its subsequent client confirmation is unavailable.
