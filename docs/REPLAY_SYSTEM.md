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

Use PERFORMANCE's global 96 MiB replay partition, not a fixed player buffer: 48 MiB adaptive rolling, 32 MiB promotion/pin liabilities, 16 MiB I/O. Share q=min(256 KiB, 48 MiB/activeSessions) lazily. Prefer at most three available seconds of prehistory and two seconds follow-up; these are opportunities, not guarantees. The timeline ring is distinct but globally accounted. Full checkpoint/blob closure must fit an 8 MiB capsule reservation; reject or select a later coherent checkpoint if it cannot. No incomplete capture is relabeled complete.

The default action policy requires a complete capsule through the decision point and its durable receipt. Follow-up actions/outcomes form a linked continuation capsule, avoiding a circular requirement that an action be recorded before it occurs. Offline verification has two modes: decision replay verifies the first capsule's proposed intent; end-to-end replay consumes the linked receipt/outcome continuation. A missing continuation limits the latter only.

I/O serializes/compresses to repository-configured temporary storage, flushes and atomically promotes the manifest before a durable receipt. Queue capacity is 16 MiB globally inside replay's partition, charged by retained bytes/pins. Partial captures cannot justify durable-required effects. Supervisor leases account for writer failures. No file work runs on main/network/actor threads.

## Replay runner and security

Replay imports only replay-schema. Core implements that schema's ReplayDriverPort and dispatches restored DTOs to domain-local mappers/reducers. Testing supplies recorded clocks/control outcomes without Bukkit. Codec/diff compares canonical schema fields, status, uncertainty, evidence/intent IDs and checkpoints. Replays never execute real effects.

Input files are untrusted. Enforce compressed/uncompressed byte limits, nesting/count limits, bounded strings, supported codecs and blob reference closure before allocation. Reject path traversal, duplicate conflicting IDs and decompression bombs. No Java object deserialization or executable embedded scripts. Hash integrity detects corruption, not authenticity; exported artifacts need a signed provenance envelope if used outside the trusted local workflow.

Confirmed false positives become minimized sanitized capsules with expected legitimate outcomes, trigger labels and relevant capability matrix. Preserve original failure as a regression until an independently reviewed change updates expectations. Do not rewrite fixtures to conceal a failed detector.

## Decision R2: local projection and schema-only serialization

**DECISION:** replay-schema owns immutable Player/World/Network/Simulation/Combat/Action/Evidence/Mitigation/Config DTOs with primitive fields and tagged records. Each respective owner (player,world,protocol,simulation,combat,interaction,evidence,mitigation,core) owns ReplayMapper and restore validation. **WHY:** Replay must not import live implementations or expand public api. **ALTERNATIVES:** Serialize live classes; put all domain state in api. **TRADEOFFS:** Round-trip projection/digest tests and explicit schema migration.

Domain state -> canonical DTO -> record/codec -> validated DTO -> owner restore. Core coordinates coherent snapshot boundaries; it does not flatten private objects with reflection. World mapper supplies all content referenced by player snapshots. Network DTOs include provisional attempts, outcomes and certification positions. Simulation projection includes replay-required physics/collision inputs and proof/profile IDs; transient scratch is not serialized. Diff never assumes implementation object equality.

Header also contains RegistryDictionary, numeric algorithm/table bytes/digests, host/conformance metadata and schema compatibility. NUMERICAL_POLICY governs cross-host verification. Export/restore/export must preserve canonical digest despite different runtime intern IDs. Missing schema/blob closure rejects restore.

DURABLE_REQUIRED remains the only initial policy. Phase 9 measures sparse movement, dense combat and chunk-churn capsule size/durability; >=99% complete within 400 ms is required before Phase 10 enables 500 ms-deadline effects. No measurements exist in Phase 0. Failure keeps effects disabled, not automatically switched to memory-only capture. Linked continuations preserve later storage/admission/outcomes without a circular pre-action requirement.
