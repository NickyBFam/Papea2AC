# Phase 1 hardening pass

Date: 2026-09-16. This is a focused hardening pass against concrete risks an independent Senior Reviewer
surfaced before running out of usage. It is not a Senior Review and does not approve Phase 1. No commit,
no self-approval, no Phase 2 work.

## Review context

Before stopping, the reviewer identified three concrete risk areas in the P1-H2/H3/M1 implementation:

1. Pre-login (not-yet-authenticated) transport state had per-connection bounds but no *global* admission
   bound, so a large number of simultaneous connections could still create unbounded aggregate memory
   pressure even though each individual connection was correctly bounded.
2. The periodic pipeline audit (`PaperCapture.audit()`) was written for the pre-early-hook attachment
   topology and had not been re-derived for the early-hook architecture that replaced it.
3. The PreLoginTransport → authoritative Bukkit identity → PacketEngine.Session binding lifecycle, buffer
   flush ordering, and shutdown ordering had not been traced for concrete races.

Each is addressed below with what was found, what was fixed, and what was proven safe by tracing/testing
rather than merely asserted.

## Global pre-login admission

**Found:** `PreLoginTransport` bounded its own journal (256 entries / 256 KiB) but `PaperCapture` tracked
no aggregate across connections - 10,000 simultaneous pre-login connections at up to 256 KiB each could
reach multi-gigabyte aggregate exposure while every individual connection still looked correctly bounded.

**Fixed:** `PaperCapture` now tracks a global connection count (`preLoginCount`, default cap 4096) and a
global aggregate journal-byte total (`preLoginAggregateBytes`, default cap 64 MiB - a small fraction of the
engine's own 256 MiB global session budget). Policy is deliberately simple, matching the task's own
preference for the simplest correct policy over unnecessary scheduling machinery: **a hard global cap,
first-admitted-wins, no fairness/priority logic**. A connection beyond the count cap gets *no* early-hook
handlers installed at all - not blocked, not queued, just not admitted to pre-login journaling. This is not
a dead end: `bind()` already has a fallback for "no pre-login transport on record" (`attachFresh`, the
pre-early-hook mechanism), so the connection still becomes a fully functional session once the player
joins; it only misses pre-login-phase (HANDSHAKING/LOGIN) observations, an explicit, metered (
`PRELOGIN_ADMISSION_REJECTED` metric) degradation. The aggregate byte cap works per-entry (`recordBounded`
checks the running total before adding, mirroring the existing per-connection bound's own style) and
degrades by marking that connection's journal truncated (`PreLoginTransport.truncateForGlobalPressure()`),
the same visible effect as a local overflow, plus a `PRELOGIN_TRUNCATED_GLOBAL` metric.

**Capacity is always reusable:** both counters are released when a connection closes (`closeTransport`) or
successfully binds (`bind()` - once the connection becomes a real session, it is no longer "pre-login" and
its slot is freed for someone else), guarded by `PreLoginTransport.releaseGlobalAdmissionOnce()` (an
`AtomicBoolean` one-shot) so a connection whose lifecycle touches both paths (bind, then eventually
channelInactive on the still-installed handler) is never double-released.

**Coverage honesty:** `bind()` now checks `pre.truncated()` (whether truncated locally or globally) after
replaying the journal and, if true, invalidates the resulting session's health (`OVERLOADED`) - a
resource-limited connection can never look like clean, complete coverage. This closed a second, adjacent
gap: the *existing* per-connection truncation was never being surfaced into session health before this pass
either.

**Tests:** `PreLoginAdmissionAndAuditTest` (11 tests) - global count admission/rejection/reuse, global byte
truncation and its health effect, many empty connections never accumulating state, and the shutdown-race
guard (below). Thresholds are plain instance fields rather than `static final` (which javac would inline as
compile-time constants, defeating reflection-based test overrides) specifically so tests can exercise the
mechanism deterministically with small numbers instead of needing thousands of real connections.

## Pipeline audit re-derivation

**Found, and this was a real, previously undetected bug:** `PaperCapture.audit()`'s topology check compared
`pipeline.get("encoder")` against a stored `audit.encoder` field and `pipeline.get("packet_handler")`
against `audit.dispatch`. For sessions bound through the early-hook path (`bind()`, the common path), those
two fields are **never populated** - they are set only inside `attachFresh()` (the fallback path). This
means `audit()` would have found `pipeline.get("encoder") != null` (some real handler) compared against
`audit.encoder == null`, unconditionally true, and **immediately invalidated every early-hook-bound
session's health as PIPELINE_UNCOVERED the first time the periodic audit ran** (every 20 ticks, per
`Papea2AC`'s scheduler) - a real player joining and simply staying connected past the first audit tick would
have silently lost coverage. This was never caught because no existing test exercised the periodic audit
against an early-hook-bound session over time, and the local smoke test never had a real player connected
for the scheduler to audit.

**Re-derived topology (documented once, checked per-session against the correct one):**

- **Early-hook path** (`afterInitChannel`, the common path): inbound handler installed immediately before
  `packet_handler` via `addBefore`; outbound handler installed immediately after `hackfix` via `addAfter`
  (chosen, per `docs/PHASE_1_EARLY_TRANSPORT_INVESTIGATION.md`, specifically because it is stable and
  adjacent to the eventual encoder regardless of protocol negotiation state - so there is no specific
  encoder/dispatch *instance* to track for this path, only the two anchor handlers' continued presence and
  the adjacency invariant).
- **`attachFresh` fallback path** (a connection the early hook never saw): inbound handler immediately
  before `packet_handler`; outbound handler immediately after `encoder`. Unlike the early-hook path, the
  *specific instances* of `packet_handler`/`encoder` at attach time are recorded and compared, since those
  handlers can legitimately be replaced during protocol negotiation and a silent replacement is exactly the
  "handler still present but meaningless" case worth catching for this path.

**Fixed:** `Audit` now carries `earlyHook`/`inboundAnchor`/`outboundAnchor` fields set correctly at
construction for whichever path actually attached the session, and `audit()` checks the topology that
matches: adjacency to the recorded anchor names always; for the early-hook path, that both anchor handlers
still exist at all; for the fallback path, that the specific recorded encoder/dispatch instances are still
in place. No stale "must be immediately before X" assumption from the pre-early-hook design remains
unexamined - both are examined and each checks only what is actually true for its own attachment model.

**Audit is diagnostic, not continuous proof:** the audit's own javadoc now says explicitly what Phase 0
already flagged - a periodic check cannot prove a handler was not removed and reinserted between two ticks.
A failed audit degrades coverage *going forward*; `HealthLatch`'s existing stickiness means a later
successful audit tick never heals a reason it did not itself invalidate, so there is no retroactive claim
that earlier observations were complete.

**Tests:** 8 of `PreLoginAdmissionAndAuditTest`'s 11 tests plus `BindingRaceTest`'s `duplicateLogin...` test
cover: intact topology audits clean (both paths), owned inbound/outbound handler removed, handler moved to
an invalid position (position matters, not mere presence), anchor handler itself missing, fallback-path
encoder silently replaced under the same name, duplicate owned handler name (Netty itself rejects this -
nothing for the audit to separately guard), and plugin-disable (`detach`) removing the owned handlers from a
still-open channel.

## Binding races

Traced (per the review leads) rather than just documented; each of the following was proven safe by a
passing test against the real `PaperCapture`, not merely reasoned about:

- **Old connection closing after a new one is bound** (`BindingRaceTest.oldConnectionClosingAfter...`):
  `PreLoginTransport` is keyed by the physical `Channel` object, and `PacketEngine`'s `SessionKey` is
  epoch-scoped per player - a late `channelInactive` from an old, already-superseded physical connection
  can only ever close *its own* session (looked up via *its own* `pre.boundSession`, set once and never
  reassigned), never the replacement's.
- **Binding while capture is disabled** (`bindingWhileCaptureDisabledFailsCleanlyWithoutCorruptingPreLoginState`):
  `engine.connect(...)` is the first substantive statement in `bind()`; if it throws (capture disabled), no
  pre-login state has been touched yet, so a later retry (once re-enabled) still replays everything
  correctly. The existing caller (`Papea2AC.attach()`) already catches this.
- **Duplicate login** (`duplicateLoginProactivelyDetachesTheOldBindingBeforeCreatingTheNewOne`): the
  platform layer proactively detaches the old binding before creating a new one; verified only the
  replacement remains active.
- **Buffer flush ordering** (`bufferedObservationsFlushInOrderAndLiveCapturesArrivingDuringTheGapAreNeitherLostNorReordered`):
  packets arriving in the real gap between "PlayerJoinEvent calls `bind()`" (main thread) and "the posted
  replay-then-bind task actually runs" (the channel's own event loop) are still journaled (since
  `boundSession` is still null) and are included in the *same* journal snapshot the posted task replays -
  the switchover from journal to live capture happens atomically, from the event loop's perspective, inside
  that one posted task. This is not a lucky accident: it follows directly from Netty's single-threaded-per-
  channel event loop guarantee, the same guarantee `ConnectionIngress.capture()`'s `inEventLoop()` assertion
  already depends on. No separate concurrency stress test was needed beyond exercising the real gap, because
  the safety property is structural, not timing-dependent.

**Not fixed because not broken:** no code change was needed for any of the above; each was already correct
by construction. The value of this section was in proving it, not patching it.

## Shutdown ordering

Traced: `Papea2AC.onDisable()` unregisters the Bukkit listener first (no more join events can fire during
teardown), then unregisters the early hook, then detaches existing bindings, then closes the engine.

**Found:** a connection accepted by Netty right as `unregisterEarlyHook()` runs on the main thread could, in
principle, have its `afterInitChannel` callback already in flight on its own event-loop thread (Paper's own
internal listener iteration may have already grabbed a reference before the removal takes effect) - it
would then install a permanent `EarlyInbound`/`EarlyOutbound` pair referencing a `PaperCapture`/`PacketEngine`
that is about to be discarded, leaking a `PreLoginTransport` that would never be bound or cleaned up until
that specific channel eventually disconnects.

**Fixed:** `PaperCapture.closing` (`volatile boolean`) is set as the very first statement of
`unregisterEarlyHook()`, before the reflective listener-removal call. `afterInitChannel` checks it as its
first statement. Even if Paper's own iteration already has a reference to the listener proxy, the flag
alone (not the removal) is what prevents new pre-login state from being created.

**Tests:** `PreLoginAdmissionAndAuditTest.serverShutdownReturnsGlobalUsageAndNewAcceptDuringShutdownInstallsNothing`
- a connection accepted after `unregisterEarlyHook()` installs zero handlers and leaves no `PreLoginTransport`
entry.

**Checkpoint/replay-control lifecycle during shutdown:** already covered by the P1-H2/H3/M1 pass's own test
suite (`WriterWatchdogTest`'s blocked/shutdown tests, `FaultInjectionAndFairnessTest.shutdownDuringAnInFlightExportStillReclaimsAllReservedBytes`,
`CheckpointReplayTest`) - re-verified passing this pass, not re-derived, since neither the writer nor the
checkpoint/replay lifecycle changed in this pass.

## Stale control/replay events across session replacement

Traced and tested directly at the engine level (`StaleControlAndResourceAccountingTest`, 2 tests):

- A `RECORDING` control bridged against a session that is then replaced (reconnect) before the bridged
  event is drained never applies to the replacement: `PacketEngine.setRecordingEnabled`/`requestReplay`
  resolve the *current* session by UUID at call time, and once bridged, the event lives in that specific
  session's own per-instance mailbox, never a global queue - if drained after the session has already
  closed, `ConnectionIngress.capture()`'s `closed.get()` check discards it silently (already-existing
  behavior, confirmed still correct).
- A checkpoint request racing session replacement neither corrupts the replacement nor misdelivers -
  verified the replacement session stays healthy and unaffected.

No code change was needed here either: the existing per-session (not global) queue design and the
`closed`-flag check in `ConnectionIngress.capture()` already provide this safety.

## Resource accounting

Audited every release path reachable from this pass's changes for double-release/underflow:

- **Pre-login global admission** (new this pass): count and byte releases both guarded (byte release is
  naturally idempotent once the journal is cleared to zero; count release uses an explicit one-shot
  `AtomicBoolean`, since decrementing an int has no natural idempotency). Tested directly.
- **ReplayQuota lease** (pre-existing): `replayQuotaLeaseDoubleCloseNeverUnderflowsOrDoubleReleases` proves
  a lease closed three times only ever releases its charge once (the lease's own `AtomicBoolean` guard,
  unchanged, confirmed still correct).
- **Writer leases under fence + completion race** (pre-existing mechanism, new test):
  `writerLeaseDoubleReleaseAcrossFenceAndCompletionNeverUnderflows` proves fencing a writer whose job has
  already completed and released its own leases does not double-account.
- **PacketEngine session lease** (`reservedBytes`/`activeSessionCount`): traced `Shard.remove()`'s two call
  sites (the shard's own closing-branch loop and `supervise()`'s terminated-shard cleanup) and confirmed
  they are structurally mutually exclusive per session - a shard whose thread has terminated cannot still be
  running its own closing-branch loop for the same session, so `remove()` runs at most once. Unchanged this
  pass; re-confirmed given the new pre-login counters sit alongside it.

**A real, separate bug was found and fixed while writing these tests, unrelated to resource accounting
directly but adjacent:** `CaptureWriter.run()`'s failure path called `failed.incrementAndGet()` *before*
`failures.accept(e)`, so a caller polling `writer.failed()` could observe the counter before the
corresponding failure callback had run - a genuine, if narrow, test race
(`WriterWatchdogTest.writerExceptionReleasesChargesAndReportsFailureButKeepsTheThreadAlive` failed
intermittently under full-suite runs because of it). Fixed by reordering: report first, count second, so
observing the counter also guarantees the report already happened.

## Broad `catch` review

Searched every `catch` clause in `anticheat-core`, `anticheat-replay`, `anticheat-packet`, and
`anticheat-platform-paper`'s production sources: **zero** instances of `catch (Throwable ...)` or
`catch (Exception ...)` exist anywhere. Every catch clause names a specific checked or unchecked exception
type (`RuntimeException`, `ReflectiveOperationException`, `IOException`, `InterruptedException`, etc.).
Since `Error`/`ThreadDeath`/`VirtualMachineError`/`LinkageError` all extend `Error`, not `RuntimeException`,
none of the existing isolation boundaries (early hook, pipeline callbacks, bind, shutdown, writer lifecycle,
controls/replay) can accidentally swallow a VM-fatal condition. This was already correct; no change was
needed, only verification.

## Package case

Re-ran the canonical-package check across source paths, package declarations, `plugin.yml`, reflection
strings, resources, tests, and Gradle configuration. Found and fixed one remaining inconsistency the prior
session's fix did not reach: the physical on-disk source directory
(`anticheat-platform-paper/src/main/java/me/nicolas/papea2ac/`) was still lowercase, while every package
declaration inside it (and `plugin.yml`'s `main:`) was uppercase `papea2AC`. This was **not** a functional
bug - `javac` writes compiled output based on each file's own `package` statement, not its source
directory's case, so this compiled correctly on Windows and would have on Linux too - but it was a genuine
latent-confusion risk (an editor or future contributor could plausibly "fix" the casing back to match the
directory, reintroducing the real bug the previous session found). Renamed the directory to
`me/nicolas/papea2AC/` to match. No `Class.forName` reflection string anywhere references the plugin's own
package name, and no Gradle build file hardcodes a package-cased path, so no other file needed a change.
Recompiled and reran the full test suite to confirm.

## Heavy normalizer performance

Full JMH was judged impractical for these specific families (each needs real, heavyweight native
construction - `VanillaRegistries` bootstrap, `Holder.Reference.bindComponents`,
`sun.misc.Unsafe.allocateInstance` field injection - that only exists in the `nativeTest` source set, which
is wired to the pinned Paper runtime jar; the JMH `benchmark` source set is not, and wiring it just for four
methods was judged not worth the added surface). Instead, `HeavyNormalizerBenchmarkMain` (a new, plain,
deterministic warmup-then-measure harness in `nativeTest`, run via a new `heavyNormalizerBenchmark` Gradle
task) reuses the exact native-construction helpers the correctness tests already use, isolates the
normalizer's own cost from test-fixture construction (packets are pre-built into a pool before timing
starts; only `PaperPacketNormalizer.normalize(...)` itself is timed), and samples approximate
allocated-bytes/op via heap-used before/after a batch (imprecise - GC can run mid-batch - but sufficient to
catch a gross regression, which is all this is for). Results (`docs/phase1-artifacts/heavy-normalizer-benchmark.json`):

| Family | ns/op | approx bytes/op |
|---|---|---|
| Item + data-component (damage, enchantment, unknown component) | 63.2 | 68.3 |
| Large container, 200 items | 461,591.2 (≈2.3µs/item) | 16,264.9 (≈81 bytes/item) |
| Chunk baseline (4096-byte block buffer) | 1,070.4 | 1,048.6 |
| Entity metadata, 30 fields | 4,718.1 (≈157ns/field) | 3,356.7 (≈112 bytes/field) |

No per-block heavyweight object, no per-state string, no unbounded copy chain was found in any of the four:
the container and entity-metadata costs scale linearly with item/field count as expected (each Item/
EntityDataField record is a small, fixed-size allocation), and the chunk baseline's allocation is dominated
by the reflective accessor calls themselves (`integer`/`call`/`field` each box primitives and allocate a
small `Object[]` args array for `Method.invoke`), not by the 4096-byte block buffer, which is read by
reference (`.length`) and never copied - matching `chunkBaseline()`'s actual implementation.

**One real, measured hot-path allocation problem was found and fixed:** `entitySyncedData()` rebuilt its
seven-entry scalar-serializer-kind lookup map via `Class.forName` + `getField().get(null)` on **every single
call**, even though the pinned server's serializer singletons never change at runtime. Before the fix, the
30-field entity-metadata case measured 11,679ns/op and ~19,084 bytes/op; after memoizing the lookup once
(a `volatile` field with a benign, idempotent recomputation race - safe because the map's content is
deterministic regardless of which thread builds it first), the same case measures 4,718ns/op and 3,357
bytes/op - a 60% latency reduction and 82% allocation reduction, isolated to exactly the method identified.
This is the only normalizer change made in this pass; it is a pure caching fix with no semantic change
(verified by the full, unchanged `nativeTest` suite still passing, including every entity-metadata
correctness test).

## Tests added

- `PreLoginAdmissionAndAuditTest` (nativeTest, 11): global admission count/bytes bound and reuse, shutdown
  race, pipeline audit topology re-derivation for both attachment paths.
- `BindingRaceTest` (nativeTest, 4): old-connection-closes-after-new-connection, bind-while-capture-disabled,
  buffer flush ordering across the real bind gap, duplicate login.
- `StaleControlAndResourceAccountingTest` (test, 4): stale RECORDING/checkpoint control safety across
  session replacement, ReplayQuota lease double-close, writer lease fence/completion race.
- `HeavyNormalizerBenchmarkMain` (nativeTest main, descriptive, not a JUnit test): the performance harness
  above.

Total: 210 tests passed (0 failed, 0 skipped), up from 191 before this pass.

## Issues found

1. Global pre-login admission bound did not exist (reviewer finding 1) - **fixed**.
2. Periodic pipeline audit checked the wrong (pre-early-hook) topology for early-hook-bound sessions,
   unconditionally degrading their health on the first audit tick (reviewer finding 2) - **fixed**. This was
   the most severe finding: a real, deterministic bug that would have broken coverage for every connected
   player, previously undetected because no test exercised the periodic audit against a real, time-elapsed,
   early-hook-bound session.
3. Pre-login journal truncation (both the pre-existing per-connection bound and the new global bound) was
   never surfaced into the resulting session's health - **fixed**.
4. A connection accepted during shutdown teardown could install permanent state referencing a discarded
   plugin instance - **fixed** with a `closing` flag checked before the reflective listener-removal
   completes.
5. `CaptureWriter`'s failure-count/failure-report ordering created a narrow, real test race - **fixed**.
6. On-disk source directory casing still did not match the package declarations it contained (latent
   confusion risk, not a functional bug) - **fixed**.
7. `entitySyncedData()`'s scalar-kind lookup was rebuilt via reflection on every call, a measured hot-path
   allocation cost - **fixed**.

## Fixes made

See "Issues found" above; each fix is described in its own section with the file(s) touched:
`PreLoginTransport.java`, `PaperCapture.java`, `PaperSemanticNormalizer.java`, `PacketMetrics.java` (two new
`Kind` values, `PRELOGIN_ADMISSION_REJECTED`/`PRELOGIN_TRUNCATED_GLOBAL`), `CaptureWriter.java`, and the
`anticheat-platform-paper` source directory rename.

## Remaining risks

- **Real client qualification remains external**, as it always has - none of this pass's fixes change that.
- The pre-login global admission thresholds (4096 connections / 64 MiB) are policy choices, not derived from
  a measured production workload - reasonable defaults chosen to be far above legitimate use and a small
  fraction of the engine's own budget, but genuinely untested against real-world connection-flood behavior
  (that would require real network load, which is external qualification).
- The periodic audit remains a diagnostic detection mechanism, not continuous proof, by design (Phase 0's
  own stated limitation) - a sufficiently narrow handler removal-then-reinsertion between two audit ticks
  (every 20 ticks, ~1 second) would still not be caught. This is now correctly documented rather than
  silently assumed away, but it is not "fixed" because it is not fixable by periodic auditing alone.
- `HeavyNormalizerBenchmarkMain`'s allocation sampling (heap-used before/after a batch) is inherently
  imprecise under concurrent GC; it is sufficient to catch a gross regression, not a tight performance gate.
