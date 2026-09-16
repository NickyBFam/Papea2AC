# Replay system

## Decision R1: deterministic reducer inputs from the beginning

**DECISION:** Define the replay schema in Phase 1 and build its minimal round-trip harness before detections. Record normalized causal inputs, coherent initial state and external outcomes. Offline replay executes the same pure reducers/engines with platform effects replaced by a recording sink.

**WHY:** Retrofitting capture after detections loses the information needed to explain false positives.

**ALTERNATIVES:** Packet dumps alone; log only final violations; require a running server for every regression.

**TRADEOFFS:** Capture has CPU/storage/privacy costs and requires explicit schema compatibility. Full staff playback tooling can wait until Phase 21.

## Capsule format

Header: format/schema version, producer build/source revision, native client/server/protocol profile, ruleset/registry/config digests, numeric profile, deterministic branch-order version, capture pseudonym, limits and optional seeded randomness. Unknown future schemas fail clearly; migrations are explicit and preserve original hashes.

Body: initial coherent checkpoint with component roots and all reachable world/entity/registry data; sequence-ordered normalized input records; relative monotonic times and server tick observations; write outcomes, marker/application constraints, command/config events, gap/health/budget aborts, action outcomes and storage receipts. Content-addressed immutable blobs may be referenced only when present in the capsule or a verified fixture pack with a manifest. A memory pointer or hash of an unavailable live chunk is insufficient.

Footer: record/blob counts, per-chunk checksums, whole-manifest digest, covered sequence range, complete/incomplete flag, terminal reason, expected outputs/state hashes and required capabilities. Raw packet bytes are optional adapter-debug attachments under a separate size limit; they cannot replace semantic inputs and server-only observations.

Each event transaction produces a deterministic digest over canonical ordered output/state values. Exclude unstable object IDs, wall time, thread scheduling and collection iteration order. Preserve IEEE float/double bits where relevant; NaN/infinity rejection occurs consistently. Randomized heuristics require recorded seed and algorithm version; production deterministic validation should not require randomness.

## Capture lifecycle

Maintain a bounded recent ring; initial target is 2 MiB per player for replay staging, in addition to the 2 MiB timeline cap. Trigger promotes a coherent prefix checkpoint and relevant immutable blobs under global capture admission. Capture at most ten seconds of prehistory and two seconds of follow-up when retained; actual coverage is stated explicitly. Required full world closure can exceed a reservation: reject capture or shorten to a later coherent checkpoint, never pretend it is complete.

The default action policy requires a complete capsule through the decision point and its durable receipt. Follow-up actions/outcomes form a linked continuation capsule, avoiding a circular requirement that an action be recorded before it occurs. Offline verification has two modes: decision replay verifies the first capsule's proposed intent; end-to-end replay consumes the linked receipt/outcome continuation. A missing continuation limits the latter only.

I/O executor serializes/compresses bounded chunks to a temporary local file under repository-configured runtime storage, flushes and atomically promotes a complete manifest, then returns a receipt. Queue capacity is 64 MiB globally; capture admission includes pinned blobs. Failed/partial files are marked incomplete and cannot justify effects requiring durable capture. Disk quota/retention failures are operational diagnostics.

## Replay runner and security

Replay imports only API/protocol/version; core provides a reducer port. Offline runner lives in testing and supplies recorded clocks/outcomes, not Bukkit. It verifies manifest integrity, initializes state, feeds observations in sequence and compares validation status, uncertainty, evidence IDs, intent decisions and checkpoints. Replays never execute real teleports or commands.

Input files are untrusted. Enforce compressed/uncompressed byte limits, nesting/count limits, bounded strings, supported codecs and blob reference closure before allocation. Reject path traversal, duplicate conflicting IDs and decompression bombs. No Java object deserialization or executable embedded scripts. Hash integrity detects corruption, not authenticity; exported artifacts need a signed provenance envelope if used outside the trusted local workflow.

Confirmed false positives become minimized sanitized capsules with expected legitimate outcomes, trigger labels and relevant capability matrix. Preserve original failure as a regression until an independently reviewed change updates expectations. Do not rewrite fixtures to conceal a failed detector.
