package me.nicolas.papea2ac.testing;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.UUID;
import me.nicolas.papea2AC.platform.PaperCapture;
import me.nicolas.papea2ac.core.PacketEngine;
import me.nicolas.papea2ac.model.HealthLatch;

/**
 * P3-F11 (real-client qualification): the vanilla pipeline writes non-packet control objects - the
 * UnconfiguredPipelineHandler$OutboundConfigurationTask at every protocol-state switch - through the same handlers as
 * packets. They are not observations: they must pass through un-numbered, never be captured as an "uncovered packet",
 * and never degrade the session (which ended every real session's recording at frame 2 and marked it PIPELINE_UNCOVERED).
 */
class NativePipelineControlObjectTest {
    @Test void aProtocolSwitchTaskWrittenThroughTheHookedPipelineIsNeitherCapturedNorADegrade() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> fail(e)); var capture = new PaperCapture(engine);
        try {
            assertTrue(capture.registerEarlyHook());
            var channel = BindingRaceTest.acceptedChannel(); BindingRaceTest.fireAccept(channel);
            channel.writeInbound(BindingRaceTest.handshake(772));
            var session = capture.bind(new UUID(60, 1), channel); channel.runPendingTasks();
            NativePacketTest.awaitSequence(session, 1);
            long before = session.actor.snapshot().processedSequence();
            var taskType = Class.forName("net.minecraft.network.UnconfiguredPipelineHandler$OutboundConfigurationTask");
            Object task = Proxy.newProxyInstance(taskType.getClassLoader(), new Class<?>[]{taskType}, (proxy, method, args) -> method.getName().equals("toString") ? "task" : null);
            assertFalse(task.getClass().getName().startsWith("net.minecraft.network.protocol."), "a pipeline control object is not a protocol packet");
            channel.writeOutbound(task); channel.runPendingTasks();
            assertSame(task, channel.readOutbound(), "passed through untouched");
            Thread.sleep(50); channel.runPendingTasks();
            assertEquals(before, session.actor.snapshot().processedSequence(), "not an observation: no sequence consumed");
            assertTrue(session.health.get().healthy() || session.health.get().reason() == HealthLatch.Reason.EXPIRED, "never a PIPELINE_UNCOVERED degrade: " + session.health.get());
            assertNotEquals(HealthLatch.Reason.PIPELINE_UNCOVERED, session.health.get().reason());
        } finally { engine.close(); }
    }
    /** P3-F12: removing the real outbound capture handler from a hooked channel is the world-relevant PIPELINE_UNCOVERED case; it must reach the world as an explicit CAPTURE_COMPROMISED break (via the next ordered observation, here a bridged tick), while an uncovered inbound packet alone does not break the world. */
    @Test void removingTheRealCaptureHandlerBreaksWorldCoverageExplicitly() throws Exception {
        var engine = new PacketEngine(1, 1L << 30, e -> fail(e)); var capture = new PaperCapture(engine);
        try {
            assertTrue(capture.registerEarlyHook());
            var channel = BindingRaceTest.acceptedChannel(); BindingRaceTest.fireAccept(channel);
            channel.writeInbound(BindingRaceTest.handshake(772));
            var session = capture.bind(new UUID(60, 2), channel); channel.runPendingTasks();
            NativePacketTest.awaitSequence(session, 1);
            channel.writeInbound(NativePacketTest.create("net.minecraft.network.protocol.game.ServerboundChatCommandPacket", new Class<?>[]{String.class}, "brand-like uncovered inbound"));
            channel.runPendingTasks(); NativePacketTest.awaitSequence(session, 2);
            long until = System.nanoTime() + 2_000_000_000L; while (session.health.get().healthy() && System.nanoTime() < until) Thread.sleep(1);
            assertEquals(HealthLatch.Reason.PIPELINE_UNCOVERED, session.health.get().reason(), "the Phase 1 latch degrades on the uncovered inbound packet");
            session.ingress.bridge(new me.nicolas.papea2ac.protocol.Observation.Tick(1)); channel.runPendingTasks(); NativePacketTest.awaitSequence(session, 3);
            assertFalse(session.world.snapshot().transportBroken(), "an uncovered inbound packet is not a world break");
            String outboundHandler = channel.pipeline().names().stream().filter(n -> n.startsWith("papea2ac-out")).findFirst().orElseThrow(); // early-transport handlers are named by transport id
            channel.pipeline().remove(outboundHandler); // the real world-relevant case
            session.ingress.bridge(new me.nicolas.papea2ac.protocol.Observation.Tick(2)); channel.runPendingTasks(); NativePacketTest.awaitSequence(session, 4);
            until = System.nanoTime() + 2_000_000_000L; while (!session.world.snapshot().transportBroken() && System.nanoTime() < until) Thread.sleep(1);
            assertTrue(session.world.snapshot().transportBroken()); assertEquals("CAPTURE_COMPROMISED:EARLY_HANDLER_REMOVED", session.world.snapshot().transportBreakReason());
        } finally { engine.close(); }
    }
}
