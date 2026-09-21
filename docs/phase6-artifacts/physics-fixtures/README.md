# Pinned native trajectories (P6-F1 re-pin)

`native-trajectories.json` is written by `gradlew.bat physicsNativeDifferential`: the pinned Paper 26.2 build 124 server runs the
`PhysicsScenarios` catalog through its own movement code (the test-only oracle plugin, `anticheat-testing/src/oracle`) and every
scenario's per-tick trajectory is digested. `PhysicsOracleFixtureTest` (plain `test` lane) replays each scenario chained on the
simulator and requires the identical digest; `NativeTrajectoryReconstructionTest` / `NativeReconstructionGate` reconstruct every
tick of the same trajectories with the declared input hidden.

This file supersedes `docs/phase5-artifacts/physics-fixtures/native-trajectories.json` as the ACTIVE expected side since
**P6-F1** (PHASE_6_IMPLEMENTATION.md §30): the local client's key-vector pre-processing (float normalize, 0.98F, item use,
sneaking, square-movement rescale) is now mirrored by the oracle glue and by `PhysicsKernels.clientMoveVector`. Compared with
the frozen Phase 5 record, 81 of 88 digests are byte-identical; the 7 that changed are exactly the scenarios with diagonal
key pairs (`diagonal_forward_left`, `diagonal_back_right`, `walk_yaw_180_strafe`, `air_strafe`, `wall_diagonal_corner`,
`packed_ice_strafe`, `mixed_course_random_100`). The Phase 5 artifacts are not modified: they are the pre-P6-F1 record.
A changed digest remains a reviewed event, never a silent re-pin.
