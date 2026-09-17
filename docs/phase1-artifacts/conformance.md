# Transport conformance fixture v1

Origin: explicit synthetic Phase 1 input, not a native player capture. No account data, chat, world blobs, or third-party anti-cheat material is present. The source marker `synthetic-fixture-v1` and zero configuration digest deliberately identify a fixture rather than a real deployment.

The committed base64 capsule starts from an empty epoch-1 session, supplies a provisional movement with signed-zero/subnormal IEEE values, advances recorded time beyond the 500 ms deadline, applies an overload barrier, and closes. Expected behavior: no certified ingress advancement; expiration remains terminal; the later barrier cannot heal health; close releases provisional state. Four expected canonical checkpoint hashes are stored separately. Tests never regenerate those expectations.

The codec uses explicit schema tags, exact IEEE bits, stable ordering, and checksums. State hashing includes pending payload/deadline/outcome identity. It uses no transcendental functions, table synthesis, random ordering, or platform state. This qualifies only the transport serialization/reducer algorithm if the required hosts agree. It makes no claim about future numeric physics profiles.

Run with Java 25 and the verified wrapper, setting the Gradle cache beneath this repository:

```text
gradlew :anticheat-testing:replayConformance -q
```

On Linux use `./gradlew`. Compare stdout with `transport-conformance.expected.txt`. Run `gradlew test` as well; it asserts the committed hashes and runs a separate capsule in two fresh JVMs. Record source tree digest, JVM vendor/version, OS/architecture, artifact hashes, command, stdout and exit status for each host. Do not rewrite expected values to make a mismatching host pass.

Current evidence: Windows 11 x64, Temurin 25.0.4.1+1, same-host/fresh-process checks pass. A second qualified OS/architecture pair is **pending**. No WSL distribution or container runtime was available here. This fixture is portable preparation, not evidence of a run on a second host.
