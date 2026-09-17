# Phase 1 evidence

All results are implementation evidence, not independent approval or production qualification.

- `paper-26.2-124-packet-inventory.tsv`: all 256 numeric wire-ID entries from the pinned native server's `ProtocolInfo.Details` tables, including handshake/status/login/configuration/play. SHA-256 `6078740cadcfa6c271425af2975ce94a88f989a81309c1af21690a6c17e1d7a8`. `:anticheat-testing:captureInventory` is the explicit recapture command; tests never regenerate this expectation.
- `packet-coverage.tsv`: implementation status for each inventory entry. A copied subset is not complete packet semantics. Unsupported rows are retained rather than removed from coverage reporting.
- `benchmarks-initial.json`: historical measurement before canonical digest/history changes; **not a current performance claim**.
- `benchmarks.json`: JMH measurement for the source used by the recorded run. See the implementation report for methodology and limitations.
- Test XML and HTML reports are generated under `anticheat-testing/build/`; downloaded binaries, raw server/client logs, caches and machine paths are ignored under `.phase1-runtime/`, `.phase1-tools/`, and `.gradle-home/`.

Artifact pins used locally:

| Artifact | Version | Digest |
|---|---|---|
| Paper server | 26.2 build 124, STABLE | SHA-256 `274bbcb9807ad79a479617ce695a7c0686e652a8d1cabfd0e288d92a3ea29593` |
| Gradle distribution | 9.7.1 bin | SHA-256 `acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a` |
| Gradle wrapper JAR | 9.7.1 | SHA-256 `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d` |
| Temurin Windows x64 JDK archive | 25.0.4.1+1 | SHA-256 `00c847d804f4a78e9f04f2683faf14fed898535b177b7fc704486cb0284e9283` |
| Installed Mojang client | 26.2 | SHA-1 from Mojang metadata `2dc72797acbc1b63fc16a11c4ac393605f453754` |

Release/channel sources were checked before migration: [Paper build API](https://fill.papermc.io/v3/projects/paper/versions/26.2/builds), [Paper 26.3 research lane](https://fill.papermc.io/v3/projects/paper/versions/26.3/builds), [Gradle releases](https://services.gradle.org/versions/current), [Temurin Java 25 metadata](https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=x64&image_type=jdk&os=windows). The approved provisional 26.2 development lane is used; no latest-Minecraft production support is claimed.

The native packet fixture provenance is the checksum-pinned Paper binary. Inputs are explicit synthetic values, with no external anti-cheat code, account data, chat, or player captures. Native-client launch attempts did not produce a verified join and are recorded as pending, not as successful fixtures. Cross-process replay on this Windows x64 host passed; a second OS/architecture run is still required for cross-host qualification.
