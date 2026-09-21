# Phase 6 human approval record

Date: 2026-09-21.

The human project owner reviewed `docs/PHASE_6_IMPLEMENTATION.md` (implementation report, native trajectory
reconstruction, round-trip fuzz, M3 containment, multi-tick, candidate distribution, JMH/memory qualification, Paper
smoke, real-client controlled-input reconstruction qualification, the P6-F1 forward correction record) together with
its verification artifacts under `docs/phase6-artifacts/`, performed the real-client qualification personally (in-game
`Emojiado`), and authorized creation of the Phase 6 implementation baseline. This record is written by the
implementation engineer at the owner's instruction; the engineer does not self-approve. No independent Senior Code
Review is claimed - the current project workflow does not require one.

```
PHASE:
Phase 6 - Input Reconstruction

STATUS:
HUMAN APPROVED FOR BASELINE

IMPLEMENTATION ENGINEER:
Claude

PARENT:
8494857bbefd071b50f3d88fb1a3f925b0971c39

PHASE 5 IMPLEMENTATION BASELINE:
03a538c4496df92df16b7e0ce503bb61750847ae

PHASE 4 IMPLEMENTATION BASELINE:
1685e96519f2eaec3f5b28c12d687e8118809b64

PHASE 3 IMPLEMENTATION BASELINE:
92159ec5ef8524c5953ecaa22f663c1f556d52ac

PHASE 2 IMPLEMENTATION BASELINE:
f24824f9f5e009b835195f9d5b7ef7d5cb4ceb24

PHASE 1 IMPLEMENTATION BASELINE:
35c44f9bf7694363d284c646e881351d887cf0bf

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

INPUT RECONSTRUCTION OWNER MODULE:
anticheat-simulation (inputs: model, version, world, collision, physics; output: core; platform-paper displays only,
no simulation edge; physics never imports simulation)

RECONSTRUCTION BOUNDARY:
PASS (immutable request/result; no VALID/INVALID/CHEATING, no violation/alert/setback/punishment, no ping rewind,
no velocity consumption timing, no teleport acknowledgement resolution; NO_MODELLED_CANDIDATE is reconstruction
data, never a verdict; ArchitectureTest simulationModuleStaysWithinThePhase6Boundary)

INPUT DOMAIN:
144 raw tuples / 136 context-free classes, canonical lexicographic order (ordinal 0 "SD----" .. 143 "WAJCRU");
ClientAssumption legality rules opt-in, unverified, never applied by default; no undocumented pruning

OBSERVATION CONTRACT:
Exact | WithinSendThreshold | Unobserved positions, per-tick yaw, ground claim reported only;
MovementObservationProfile paper-26.2-124-movement-observation (send threshold 2.0E-4 squared, 20-tick reminder);
no generic epsilon

M3 ABSTRACT / INTERVAL MODEL:
PASS (finite tagged interval boxes, at most 16 union members, IEEE outward rounding, TOP never excludes;
397440/397440 concrete successors contained; envelope prefilter ON vs OFF: 0 false exclusions)

SINGLE-TICK:
PASS (complete enumeration of the 144 raw candidates; exact + compatible retained, exact + incompatible excluded,
non-exact UNDECIDED - never excluded from uncertainty alone)

MULTI-TICK:
PASS - SPEC HORIZON 1-3 (M2: at most three inferred steps; bit-identical state merge only; raw history provenance;
no beam search, no best-N; SEARCH_LIMIT honest; horizons 5/20 NOT APPLICABLE BY SPEC)

SEARCH BOUNDS:
256 nodes / 4096 physics evaluations / 3 ticks / 256 histories per node / 64 reported candidates /
4096 envelope cells; SEARCH_LIMIT / QUERY_LIMIT carry partial evidence with complete=false

NO-FALSE-EXCLUSION:
PASS

NATIVE ACTUAL INPUT INCLUDED:
3105/3105 (declared input an EXACT witness on every tick, 0 false exclusions, both the digest-verified chain and the
oracle's own per-tick states; 1324 unique / 250 equivalent / 1531 distinct / 0 incomplete)

ROUND-TRIP:
1200/1200 (flat, course, ice; 188 unique / 1012 ambiguous)

UNCERTAINTY PROPAGATION:
PASS (unknown world/attribute/effect/equipment/entity/border/fluid/elytra are coverage reasons, never defaults,
never exclusions)

CANDIDATE DISTRIBUTION:
native 3105 ticks 42.64 % unique / 8.05 % equivalent / 49.31 % distinct / 0 % incomplete; random corpus
15.47 % unique (data for Phase 8, never a defect)

PERFORMANCE QUALIFICATION:
PASS (JMH 1.37, Temurin 25, 3 forks, 5x1s/10x1s, -prof gc; final-code run: idle unique 19.6 us, idle ambiguous
71.1 us, walk 33.1 us, sprint 32.2 us, jump 46.1 us, airborne 25.1 us, full domain 144 33.7 us with envelope /
788.2 us concrete-only, multi-tick 2 95.1 us, multi-tick 3 412.5 us; bounded hotspots documented prominently:
worst bounded search ~5.39 ms / ~6.69 MB SEARCH_LIMIT, unobserved-middle ~42.9 ms / ~160 MB SEARCH_LIMIT;
the pre-P6-F1 morning run is kept alongside (benchmarks-pre-p6f1.json) because of documented host drift;
descriptive kernels on one host, not capacity claims)

MEMORY QUALIFICATION:
PASS (reconstructor stateless, ~16 B; nothing retained per player; per 100 retained results ~153352 B idle
ambiguous / ~80440 B walk unique; per-request allocation explicitly documented; no unbounded caches)

PAPER SMOKE:
PASS (final jar d9b5f0f96cac0c92, Done (7.519s), Disabling Papea2AC before the player's disconnect,
0 exceptions on the final instance)

REAL CLIENT INPUT RECONSTRUCTION:
PASS (owner's Minecraft Java 26.2 client, Paper 26.2 build 124, 2026-09-20/21; 92 RCON evidence blocks, 0 secret
matches; the qualified jar d9b5f0f96cac0c92 is byte-identical to the jar rebuilt from the final code)

DECLARED INPUT INCLUDED:
18/18 qualifying declared scenarios - rest, W, S, A, D, W+A, W+D, W+sprint, jump, W+jump, W+sprint+jump,
W+shift, W+use item, walk into wall, slab step-up, stairs, ice walk/slide, ladder climb: the declared keys were in
the candidate set of every counted held transition; release tails explained by idle; airborne input change
(W released mid-air) reconstructed exactly - the operator's W->A switch within one airtime was not achieved and is
recorded as performed, not as a failure

TIMING-AMBIGUOUS TRACES:
0 (every counted transition explained by the one-tick hypothesis, gapHypotheses 0)

NEGATIVE TEST:
declared hold_s ABSENT 44/44 while W was actually held - reconstruction discrimination, NOT A VERDICT

P6-F1:
DOCUMENTED FORWARD PHASE 5 CORRECTION - the 26.2 client pre-processes the movement-key vector in FLOAT before
the server-visible movement path (Vec2 normalization, x0.98F, x0.2F while using an item, x sneakingSpeed while
moving slowly, square-movement rescale; diagonal component ~0.7071067F); the former implementation fed
(0.98F, 0.98F) into the later DOUBLE normalization; discovered live (W+A: NO_MODELLED_CANDIDATE, nearest WA----
residual ~1.014e-8 radial); one authoritative implementation PhysicsKernels.clientMoveVector, the duplicated
scaledImpulse path removed, MovementProfile owns the constants, oracle glue reproduces the client preprocessing;
straight inputs bit-identical; 8/32 diagonal tuples affected, <= ~1e-8 block/tick; after correction 88/88,
3105/3105 exact, drift 0.0; 81/88 pinned trajectory digests byte-identical, the 7 changed are exactly the
diagonal-input scenarios; re-qualified in game (W+A, W+D, W re-check)

PHASE 5 HISTORICAL BASELINE:
UNCHANGED (03a538c4496df92df16b7e0ce503bb61750847ae and docs/phase5-artifacts are the frozen pre-P6-F1 record;
the corrected forward implementation and its re-pin live in the Phase 6 baseline under docs/phase6-artifacts/)

PHASE 0 DRIFT:
NONE

PHASE 1/2/3/4 HISTORICAL BASELINES:
UNCHANGED

PHASE 5 FORWARD ADDITIONS (besides P6-F1):
PhysicsSimulator.resolveAttributes(env, sprinting) accessor; inputVector made public

PHASE 2/3/4 API:
UNTOUCHED (timeline projection consumed through TimelineStatus; world views and BlockBehaviorQuery read-only;
CollisionEngine.query COMPLETE for the envelope region proof)

PRODUCTION TRANSPORT:
CAPTURE-ONLY

AUTHORITATIVE OUTCOME CORRELATION:
UNSUPPORTED

SCHEMA:
2 current, 1 readable (unchanged)

PHASE 7:
NOT STARTED

PHASE 8:
NOT STARTED
```

## Scope of this approval

Approval covers the Phase 6 scope as documented: the input reconstruction engine in `anticheat-simulation`
(`reconstruct(ReconstructionRequest)` over the actual Phase 5 input domain, the concrete witness lane and the M3
envelope lane, single- and bounded multi-tick search, provenance, residuals, coverage and work accounting), the
version-scoped observation profile, the core composition and diagnostic tracer, the platform diagnostic command, the
tests, gates, benchmarks, harnesses and artifacts listed in `docs/phase6-artifacts/baseline-manifest.sha256`, and the
P6-F1 forward correction of the Phase 5 client input pipeline. It does not open Phase 7 (latency / acknowledgement /
external-motion timing) or Phase 8 (movement validation) work, and it does not approve any verdict, alert, setback
or punishment logic - none exists.
