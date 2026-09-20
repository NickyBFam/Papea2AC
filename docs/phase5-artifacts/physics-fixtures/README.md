# Phase 5 physics fixtures

`native-trajectories.json` pins, for every walking scenario of the Phase 5 catalog (`PhysicsScenarios`), the trajectory the
PINNED Paper 26.2 build 124 server produced when the scenario was run through the server's own movement code
(`physicsNativeDifferential`, oracle plugin `anticheat-testing/src/oracle`, never shipped). The digest is a SHA-256 over
the raw little-endian bits of `x, y, z, vx, vy, vz, fallDistance, stuck xyz` and a flag byte (onGround, horizontal /
vertical / vertical-below collision, inPowderSnow, pose) per tick. `PhysicsOracleFixtureTest` (plain `test` lane, no
server) replays every scenario chained on the simulator and requires the identical digest, and requires the fluid
scenarios to be refused as `UNSUPPORTED_FLUID`. The expected side is therefore the server's arithmetic, never the
simulator's own output (TESTING.md fixture rule). A changed digest is a reviewed event: re-run the native differential
and explain the change; never re-pin from the simulator.
