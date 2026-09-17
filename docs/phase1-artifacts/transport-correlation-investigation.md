# Selected integration outcome: UNSUPPORTED

Scope: the implemented direct Netty tap, unmodified Paper 26.2 build 124, and its accessible dispatch API. This does not assert that a separately instrumented server could never qualify.

Inspected the actual pinned server with Java 25 `javap -p -c`, including `Connection`, `PacketUtils`, `PacketProcessor`, and `ServerGamePacketListenerImpl`. The local inspection output is `.phase1-tools/dispatch-investigation.txt`; it is reproducible from the pinned runtime and is not an external qualification fixture.

* `Connection.channelRead0` calls packet dispatch but catches `RunningOnDifferentThreadException`. A normal return to the tap can mean only that later work was scheduled.
* `PacketUtils.ensureRunningOnSameThread` checks `PacketProcessor.isSameThread`, calls `scheduleIfPossible(listener, packet)`, and throws that control-flow exception on the network thread.
* `PacketProcessor.scheduleIfPossible` returns void. Its public processing/counter methods expose neither an attempt-correlated final semantic payload nor an authoritative application revision/result.
* `ServerGamePacketListenerImpl` performs validation and Bukkit event processing after scheduling. Movement paths inspect cancellation and changed destinations. Those events cover specific gameplay paths and do not constitute a complete per-packet dispatch result with the original tap identity.
* `ChannelPromise` is outbound write completion. It cannot report inbound acceptance or client application. Handler identity/adjacency checks cannot prove absence of transient transforms or complete downstream mediation.

| Required distinction | Selected integration evidence |
|---|---|
| OBSERVED | Supported at decoded inbound / encoder-side outbound taps |
| ACCEPTED | Unsupported authoritative correlation |
| CANCELLED | Unsupported complete authoritative correlation; synthetic/native-class tests demonstrate no promotion |
| MUTATED/REPLACED | Unsupported complete final-payload correlation; transformed fixture remains distinct and uncertified |
| UNKNOWN | Explicit safe disposition; expiry and missing coverage never become accepted |

Production remains **CAPTURE-ONLY**, with outcome, mediated-integrity, and reconciled-baseline capability false. Enabling enforcement requires a separately qualified dispatch/application provider and baseline; configuration cannot manufacture either. No server position comparison, timeout, normal return, successful write, or topology match is used as acceptance.

The earlier unverified provider question is resolved for this integration: **UNSUPPORTED**. Real-client capture, lifecycle coverage, and semantic completeness are separate remaining gates.
