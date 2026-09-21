package me.nicolas.papea2ac.version;
import java.util.Objects;
/**
 * Version-scoped timing facts and guarantee flags for the pinned Paper 26.2 build 124 / vanilla 26.2 client pair
 * (LATENCY_COMPENSATION.md N1: "Exact protocol ID, mappings and these semantics are release-gated artifacts, not assumptions
 * hidden in code"). Every field cites where it was established; a flag that is {@code false} means the guarantee is NOT claimed
 * for this version, so consumers stay wider (sound), never narrower.
 *
 * <ul>
 * <li>{@code keepAliveIntervalNanos}: the server's keep-alive cadence. In the pinned Paper jar
 *     {@code ServerCommonPacketListenerImpl.keepConnectionAlive} sends one {@code ClientboundKeepAlivePacket} per second
 *     ({@code nanoTime - lastKeepAliveTx >= TimeUnit.SECONDS.toNanos(1)}, a pending-challenge queue, kick after
 *     {@code KEEPALIVE_LIMIT} = 30 s); the vanilla constant {@code LATENCY_CHECK_INTERVAL = 15000} still exists but does not drive
 *     the cadence (P7-F3: this profile first said 15 s; NativeAckInventoryTest pins the fact from the bytecode).</li>
 * <li>{@code clientTickEndPerTick}: the 26.2 client sends {@code ServerboundClientTickEndPacket} once per client tick while in a
 *     level (client send rule, verified live in the Phase 7 real-client qualification; a session proves it for itself by
 *     the packet's presence).</li>
 * <li>{@code applicationHorizonNanos}: how long an outbound influence stays branchable as "not yet applied" (the qualification
 *     tier's three-second reconstruction horizon); after it the influence is EXPIRED = unresolved, never "not applied".</li>
 * <li>{@code teleportConfirmIsGameThreadFence}: the 26.2 client applies a position correction and sends the confirm and the
 *     corrected position from its game thread in one handler ({@code ClientPacketListener.handleMovePlayer}); movement packets
 *     before the confirm in stream order are pre-application, the next position after it is the applied position (verified
 *     live: the Phase 7 real-client teleport test).</li>
 * <li>{@code pongBoundsApplication}: whether a marker answer (network-thread receipt) may be used to bound the LATEST possible
 *     application tick of an earlier influence. NOT claimed (N1: "marker ACKs supply receipt/RTT evidence only and must not
 *     advance a physics-application frontier" until established); the diagnostic measures what the bound would have been.</li>
 * </ul>
 */
public record TimingProfile(String id, long keepAliveIntervalNanos, boolean clientTickEndPerTick, long applicationHorizonNanos, boolean teleportConfirmIsGameThreadFence, boolean pongBoundsApplication) {
    public TimingProfile { Objects.requireNonNull(id); if (keepAliveIntervalNanos <= 0 || applicationHorizonNanos <= 0) throw new IllegalArgumentException("timing profile"); }
    public static final TimingProfile PAPER_26_2 = new TimingProfile("paper-26.2-124-timing", 1_000_000_000L, true, 3_000_000_000L, true, false);
}
