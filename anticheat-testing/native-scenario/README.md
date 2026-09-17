# Phase 1 native scenario procedure

Status: **native-client qualification pending**. Native-class/EmbeddedChannel tests are a separate layer.

Pinned server: Paper 26.2 build 124, protocol 776; server SHA-256
`274bbcb9807ad79a479617ce695a7c0686e652a8d1cabfd0e288d92a3ea29593`.
Pinned native client: installed Mojang 26.2 release JAR, SHA-1
`2dc72797acbc1b63fc16a11c4ac393605f453754` from its version metadata.
Use a legitimately installed client. No authentication files are collected. This procedure neither implements nor substitutes client movement.

The local owner accepted the Minecraft EULA on 2026-09-16. A new operator must independently accept it before starting a server. Runtime assets, world, logs, and the isolated client game directory stay under `.phase1-runtime/`; project tools and caches stay inside the repository. Do not commit downloaded game files or personal logs.

1. Use Java 25 and the checksum-pinned Gradle wrapper. Set `GRADLE_USER_HOME` to `.gradle-home/`. Run `gradlew test :anticheat-testing:nativeTest :anticheat-platform-paper:pluginJar` after preparing the pinned Paper runtime. The native test task fails if that runtime is absent.
2. Verify the Paper SHA-256 above. Put the resulting plugin JAR in `.phase1-runtime/plugins/Papea2AC.jar`. Set `server-ip=127.0.0.1`, `server-port=25576`, `enable-rcon=false`, `enable-query=false`. Use a disposable world. Record `server.properties` and world seed hashes with the run. Keep online authentication for a licensed normal-client run; isolated test identities may use offline mode only on this loopback endpoint.
3. Start Paper from `.phase1-runtime` with `java -Xms1G -Xmx4G -jar paper.jar --nogui`. Check plugin initialization and inventory probe results. Failure is a failed capture test, not a successful degraded run.
4. Start the installed native 26.2 client with a separate game directory `.phase1-runtime/client`. Join `127.0.0.1:25576`. Keep account authentication under operator control. Record driver revision as `manual-v1` and declare this run uninstrumented.
5. Record the console `papea2ac` counters before and after walking, turning, jumping, changing held item, swinging, riding/dismounting, and reconnecting. Verify captures increase, certification remains unavailable, and no normal packet is cancelled by Papea2AC. Use a second controlled run for server tick freeze/step/rate and respawn/camera transitions; unsupported modes must remain visible as uncovered.
6. Repeat cancellation, replacement, failed-write, delayed-outcome, and foreign-handler scenarios using a separately reviewed test-only server injector. The current EmbeddedChannel tests cover these semantics but **do not fulfill this real-server injector step**. Do not report them as native-client demonstrations.
7. Disconnect and wait for session count zero. Compare reserved bytes with the 256 MiB global baseline. Stop Paper normally, verify plugin disable, and check no shard writer remains. Preserve sanitized counters and outcomes, not names, chat, credentials, or identifying addresses.
8. For each scenario, store a fixture ID, exact source/build hashes, world/config hashes, seed, action timeline, expected capture/certification status, actual counters, perturbation statement, and explicit pass/fail. Any missing join, capture, or cleanup evidence is pending/failure. Compare instrumented and uninstrumented runs before qualifying instrumentation.

Observed local attempts: server startup/disable succeeded; two isolated native-client launches exited before a server join could be verified. They are **not passing native fixtures**. No 50â€“100 player load profile, native fault matrix, or complete harness qualification has been claimed.

Continuation smoke (2026-09-16 14:21–14:22 local): rebuilt plugin enabled on pinned Paper, native inventory and semantic-inventory resource verification passed. Console `papea2ac status`, `capture off`, `capture on`, and `effects suspend` reported the requested capture state, effects suspended, correlation UNSUPPORTED, zero sessions/certification, and 256 MiB global reservation. Normal `stop` disabled the plugin and saved all dimensions; exit code 0. This proves console controls and empty-server lifecycle only.

One earlier smoke launch in this continuation did not retain stdin and was terminated before restarting with an interactive console; that run is not normal-shutdown evidence. No player was connected. Paper's pre-existing OSHI/Perflib warning remains outside plugin behavior.

For the pending joined-client test, additionally run `papea2ac player <name>` and `papea2ac replay <name>`, then verify writer counters and a resulting file under the plugin's `transport-captures` directory. Decode the capsule and inspect `complete` before replay; an incomplete prefix must be rejected. Capture-off must remove coverage without affecting player connectivity, capture-on must create a fresh epoch, and the final disconnect must release the session and recorder reservation. Do not count zero-session status output as evidence for these steps.
