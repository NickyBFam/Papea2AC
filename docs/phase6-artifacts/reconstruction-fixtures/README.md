# Phase 6 reconstruction fixtures

`reconstruction-fixtures.json` pins the outcome of every case of the `ReconstructionFixtures` catalog (`anticheat-testing`,
test lane): status, completeness, ambiguity class, the exact counts (matching states / histories, exact and envelope
exclusions, undecided inputs, physics evaluations, reported candidates), the first canonical candidates and a SHA-256 over the
canonical candidate set (every raw history, its match kind and its predicted state). Cases cover the unique, ambiguous,
empty, incomplete (unknown world / attribute / effects / entities, unresolved external motion), search-limited, multi-tick
(exact, silent middle, unobserved middle), world-epoch-mismatch, discontinuity, fluid-deferred, envelope-only (abstract start),
known-constraint/reported-input, step-up and ice-deceleration situations.

`ReconstructionFixtureTest` (plain `test` lane, no server) re-runs every case and requires the identical summary and digest;
`gradlew.bat reconstructionFixtures` regenerates the file. The expected side is the frozen Phase 5 forward model over the
Phase 3/4 test scene, never the reconstructor's own opinion of itself: the true input of each case is known by construction
and the gates (`ReconstructionRoundTripTest`, `NativeTrajectoryReconstructionTest`) prove it is never excluded. No live
object, cache or mutable state is serialized. A changed digest is a reviewed event, never a silent re-pin. Reviewed re-pin: after P6-F1 (client input pipeline, PHASE_6_IMPLEMENTATION.md §30)
the `multi_tick_2_exact` candidate-set digest changed (its candidate set contains a diagonal second-tick prediction); the other
23 cases and every pinned count are identical.
