# Phase 6 baseline receipt

Date: 2026-09-21.

This receipt is metadata recorded *after* the Phase 6 implementation baseline commit succeeded and its committed
blobs were independently verified against the frozen manifest. It is committed separately from the implementation
baseline, so this receipt's own commit SHA is never required inside the commit it describes (no self-referential
hashing) - the same model as the Phase 0, 1, 2, 3, 4 and 5 receipts.

## Record

```
PHASE:
Phase 6 - Input Reconstruction

STATUS:
HUMAN-APPROVED IMPLEMENTATION BASELINE

PARENT:
8494857bbefd071b50f3d88fb1a3f925b0971c39

PHASE 1 IMPLEMENTATION BASELINE:
35c44f9bf7694363d284c646e881351d887cf0bf

PHASE 2 IMPLEMENTATION BASELINE:
f24824f9f5e009b835195f9d5b7ef7d5cb4ceb24

PHASE 3 IMPLEMENTATION BASELINE:
92159ec5ef8524c5953ecaa22f663c1f556d52ac

PHASE 4 IMPLEMENTATION BASELINE:
1685e96519f2eaec3f5b28c12d687e8118809b64

PHASE 5 IMPLEMENTATION BASELINE:
03a538c4496df92df16b7e0ce503bb61750847ae

PHASE 6 IMPLEMENTATION BASELINE:
ca6c16004247808fdb81203ecd1e041d3cfe7259

PHASE 6 MANIFEST:
docs/phase6-artifacts/baseline-manifest.sha256

PHASE 6 MANIFEST COMPOSITE:
4ef5d810f18bd9481d9ffd4d1d1feb831271b59f9ab30ac979591dc37cb2b57b

BUILD:
PASS

TESTS:
test 531
nativeTest 132
unique total 663
0 failed
0 skipped

PHASE 1 COVERAGE:
127/127

PHASE 1 MANDATORY GAPS:
0

INPUT RECONSTRUCTION:
PASS (owner module anticheat-simulation; immutable reconstruct(ReconstructionRequest) -> ReconstructionResult;
no verdict, no VALID/INVALID/CHEATING, no violation/alert/setback/punishment; NO_MODELLED_CANDIDATE is data)

INPUT DOMAIN:
144 raw tuples / 136 context-free classes, canonical deterministic order; legality assumptions opt-in and unverified

M3:
PASS (finite tagged interval boxes, <= 16 union members, IEEE outward rounding, TOP never excludes,
397440/397440 successor containment, envelope prefilter ON vs OFF 0 false exclusions)

SINGLE-TICK:
PASS (complete enumeration; exact+compatible retained, exact+incompatible excluded, uncertain UNDECIDED)

MULTI-TICK:
PASS - 1-3 ticks (bit-identical state merge only, raw history provenance, no beam search, SEARCH_LIMIT honest;
horizons 5/20 NOT APPLICABLE BY SPEC)

NO-FALSE-EXCLUSION:
PASS

NATIVE ACTUAL INPUT:
3105/3105 (declared input an EXACT witness on every tick, 0 false exclusions, chain and oracle-state lanes)

ROUND-TRIP:
1200/1200

UNCERTAINTY:
PASS (unknown world/attribute/effect/equipment/entity/border/fluid/elytra -> coverage reasons, never exclusions)

SEARCH BOUNDS:
PASS (256 nodes / 4096 evaluations / 3 ticks / 256 histories per node / 64 reported candidates /
4096 envelope cells; SEARCH_LIMIT / QUERY_LIMIT with partial evidence, complete=false)

MEMORY:
PASS (stateless reconstructor ~16 B; nothing retained per player; per 100 retained results ~153352 B idle
ambiguous / ~80440 B walk unique; per-request allocation documented; no unbounded caches)

PERFORMANCE:
PASS (final-code JMH run: idle unique 19.6 us, idle ambiguous 71.1 us, walk 33.1 us, sprint 32.2 us, jump 46.1 us,
airborne 25.1 us, full domain 144 33.7 us with envelope / 788.2 us concrete-only, multi-tick 2 95.1 us,
multi-tick 3 412.5 us; bounded hotspots: worst bounded search ~5.39 ms / ~6.69 MB SEARCH_LIMIT,
unobserved-middle ~42.9 ms / ~160 MB SEARCH_LIMIT; pre-P6-F1 run kept alongside because of documented host drift;
descriptive kernels, not capacity claims)

PAPER SMOKE:
PASS (final jar d9b5f0f96cac0c92, Done (7.519s), Disabling Papea2AC before the player's disconnect, 0 exceptions)

REAL CLIENT:
PASS (92 evidence blocks, 0 secret matches; qualified jar byte-identical to the jar rebuilt from the final code)

DECLARED INPUT INCLUDED:
18/18 qualifying scenarios (rest, W, S, A, D, W+A, W+D, W+sprint, jump, W+jump, W+sprint+jump, W+shift, W+use,
wall, slab, stairs, ice, ladder); release tails explained by idle; airborne W-release reconstructed exactly,
the operator's W->A switch within one airtime not achieved - recorded as performed

TIMING-AMBIGUOUS TRACES:
0 (every counted transition explained by the one-tick hypothesis, gapHypotheses 0)

NEGATIVE TEST:
declared hold_s ABSENT 44/44 while W was actually held - reconstruction discrimination, NOT A VERDICT

P6-F1:
DOCUMENTED / FIXED / VERIFIED / REQUALIFIED (Phase 5 forward model correction discovered during Phase 6: the
26.2 client's float movement-key preprocessing - Vec2 normalization, x0.98F, x0.2F using item, x sneakingSpeed,
square-movement rescale, diagonal component ~0.7071067F - replaced the former (0.98F, 0.98F)-into-double-
normalization path; PhysicsKernels.clientMoveVector is the one implementation, scaledImpulse removed,
MovementProfile owns the constants, oracle glue reproduces the client preprocessing; straight inputs bit-identical;
8/32 diagonal tuples affected, <= ~1e-8 block/tick; after correction 88/88, 3105/3105 exact, drift 0.0,
81/88 pinned digests byte-identical and the 7 changed are exactly the diagonal cases; PHASE_6_IMPLEMENTATION.md §30)

PHASE 5 HISTORICAL BASELINE:
UNCHANGED (03a538c4496df92df16b7e0ce503bb61750847ae, receipt 8494857bbefd071b50f3d88fb1a3f925b0971c39 and
docs/phase5-artifacts are the frozen pre-P6-F1 record; nothing amended, nothing regenerated)

PHASE 0 DRIFT:
NONE

PHASE 1/2/3/4 HISTORICAL BASELINES:
UNCHANGED

PHASE 7:
NOT STARTED

PHASE 8:
NOT STARTED

KNOWN FORWARD ITEMS:
- latency / ACK / teleport / external-motion timing deferred to Phase 7 (caller-supplied hypotheses <= 3 ticks;
  TIMING_UNRESOLVED / DISCONTINUITY otherwise)
- legality assumptions (ClientAssumption) remain opt-in until proven; the raw domain is the full 144
- envelope pilot domain optimized for flat contact / free flight; obstacle cases (wall, slab, stairs, ladder,
  special blocks, entities, unknown cells) use the concrete lane (~0.9-1.1 ms per node-tick)
- two fully unobserved ticks from an arbitrary state can hit SEARCH_LIMIT (honest, bounded)
- sneak+use diagonal final-bit ordering of the two client scale steps remains unmeasured
- tracer lifetime (60 s) / sample limits (64) are operational qualification constraints, not engine limits
- envelope-lane allocation (~0.6 KB per input) is an optimization target
- unobserved-middle union search is expensive (~42.9 ms / ~160 MB) but bounded by the M2 caps
- the reported-input (ServerboundPlayerInputPacket) witness proposal is supported by the engine but not yet fed
  by the diagnostic tracer
- JMH absolute figures carry documented host drift between the morning and evening runs; both runs are recorded

ENVIRONMENTAL / NON-REPRODUCED FLAKES:
- one "capsule byte limit" failure in the pre-existing Phase 3 CheckpointReplayTest during a test-lane run taken
  while the qualification server and the game client were running on the same host; 8/8 on the immediate solo
  rerun and on both subsequent full --rerun-tasks lanes (not a Phase 6 defect)
- a first evening JMH run taken while the operator's game client was still open was discarded as contended
```

## Manifest and committed-blob verification

`docs/phase6-artifacts/baseline-manifest.sha256` records SHA-256 hashes for the 57 files of the Phase 6 baseline
set (12 modified tracked files, 45 new files including `docs/PHASE_6_HUMAN_APPROVAL.md`, the Phase 6 artifacts, the
P6-F1 re-pinned native trajectories and the real-client evidence log; the manifest excludes itself), computed over
**committed Git blob content** (`git cat-file blob :<path>` on the staged index, i.e. after `.gitattributes`
normalization) rather than working-tree checkout bytes. It was verified twice before the commit via two independent
enumerations (`git diff --cached --name-only` vs. index-minus-HEAD-tree by blob id) and two independent SHA-256
implementations (coreutils `sha256sum` vs. Node `crypto`): identical file list, LC_ALL=C ordering, per-file hashes
and composite.

After the baseline commit (`ca6c16004247808fdb81203ecd1e041d3cfe7259`), every one of the 57 files was re-read from
the **committed blob** (`git cat-file blob ca6c1600…:<path>`) and re-hashed: **57 of 57 matched exactly**, the
recomputed composite equals the manifest composite, the commit's changed-file set equals the manifest set plus the
manifest itself (58 files: 46 added, 12 modified, nothing deleted, nothing unintended, nothing missing), the P6-F1
correction (`PhysicsKernels.clientMoveVector`, the oracle glue) and the 1707-line real-client evidence log are
present and exact, every document of the Phase 0 baseline `43d8988…` is byte-identical, and the Phase 1, 2, 3, 4
and 5 documents and artifacts are unchanged relative to the parent (`docs/phase5-artifacts` is the frozen
pre-P6-F1 record). The evidence log (`*.log` is git-ignored by the generic rule) was added explicitly after the
secret scan of every staged blob reported zero matches (the literal runtime RCON password, `rcon.password=`,
access/session token, bearer authorization, credential and password patterns); no ignored build, runtime, IDE,
JDK, heap-dump, benchmark-output or oracle-runtime directory was staged. Whitespace-check notes exist only in two
verbatim generated artifacts (JMH-written `benchmarks.json` and the server-output evidence log), kept
byte-identical on purpose; all sources are clean.

## Commit lineage

```
Phase 1 implementation baseline:
35c44f9bf7694363d284c646e881351d887cf0bf
  |
  v
Phase 1 receipt commit:
769100fec8477a5958050c376a86781842df0229
  |
  v
Phase 2 implementation baseline:
f24824f9f5e009b835195f9d5b7ef7d5cb4ceb24
  |
  v
Phase 2 receipt commit:
a95169e861a843e868f58c7b3a87ed050f14004a
  |
  v
Phase 3 implementation baseline:
92159ec5ef8524c5953ecaa22f663c1f556d52ac
  |
  v
Phase 3 receipt commit:
d9d50661c899f6c852247472d7ffde15dca3e78a
  |
  v
Phase 4 implementation baseline:
1685e96519f2eaec3f5b28c12d687e8118809b64
  |
  v
Phase 4 receipt commit:
8bc0cc1ba63dc946003584aaec215f7087308544
  |
  v
Phase 5 implementation baseline:
03a538c4496df92df16b7e0ce503bb61750847ae
  |
  v
Phase 5 receipt commit:
8494857bbefd071b50f3d88fb1a3f925b0971c39
  |
  v
Phase 6 implementation baseline:
ca6c16004247808fdb81203ecd1e041d3cfe7259
  |
  v
Phase 6 receipt commit:
(the follow-up commit that adds this file; see repository log)
```

## What this receipt does not claim

Same as `docs/PHASE_6_HUMAN_APPROVAL.md`: no independent Senior Code Review was completed (not required by the
current workflow); one real Minecraft client (the owner's) on one host exercised the controlled-input scenarios;
the airborne W->A key switch within a single jump's airtime was not achieved by the operator (what was performed -
a mid-air W release - reconstructed exactly); no second OS/architecture host has been exercised; memory and JMH
figures are empirical estimates and descriptive kernels on one host with documented drift, not supported capacity.
Production transport remains CAPTURE-ONLY and authoritative outcome correlation remains UNSUPPORTED. Tick
alignment, latency, acknowledgement, velocity-application and teleport timing are Phase 7 and are not resolved;
no verdict, violation, alert, setback or punishment behaviour exists (Phase 8). P6-F1 is a forward correction of
the Phase 5 client input pipeline carried by this baseline; it is not a change to the Phase 5 historical baseline,
whose commits and artifacts are unchanged. The Phase 1, 2, 3, 4 and 5 implementation baseline commits are
unchanged in history.
