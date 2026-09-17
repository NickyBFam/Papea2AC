package me.nicolas.papea2ac.testing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import me.nicolas.papea2ac.core.*;
import me.nicolas.papea2ac.model.*;
import me.nicolas.papea2ac.packet.*;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.replay.*;
import me.nicolas.papea2ac.schema.*;

class ReplayTest {
    @Test void committedConformanceCapsuleMatchesExpectedCheckpoints()throws Exception{
        Path artifacts=Path.of(System.getProperty("repo.root"),"docs","phase1-artifacts");
        byte[] bytes=Base64.getDecoder().decode(Files.readString(artifacts.resolve("transport-conformance.capsule.b64")).strip());
        var expected=Files.readAllLines(artifacts.resolve("transport-conformance.expected.txt"));
        assertEquals(expected,new TransportReplayDriver().replay(CapsuleCodec.decode(bytes)));
    }
    private static final SessionKey KEY = new SessionKey(new UUID(123, 456), 1);
    private static final PacketPayload.Movement MOVEMENT = new PacketPayload.Movement(new PacketPayload.Position(-0.0, 64, Double.MIN_VALUE), new PacketPayload.Rotation(-0.0f, 90), true, false);
    private static TransportCapsule capsule(List<TransportCapsule.Frame> frames) {
        return new TransportCapsule(1, TransportReplayDriver.PROFILE, "0".repeat(64), "synthetic-fixture-v1", 1, true, frames, true);
    }
    static TransportCapsule fixture() throws IOException {
        var a = new Observation.Attempt(1, 1, MOVEMENT);
        var b = new Observation.Outcome(1, 1, 1, Observation.Result.REJECTED, null, PayloadDigest.of(MOVEMENT));
        return capsule(List.of(
                TransportReplayMapper.export(new Observation(KEY, 1, 0, 0, a), 0, 0),
                TransportReplayMapper.export(new Observation(KEY, 2, 10, 0, b), 0, 10),
                new TransportCapsule.Clock(1_000_000_000L), new TransportCapsule.Close(1_000_000_001L)));
    }
    @Test void roundTripExecutesSameReducerAndCancellationNeverPromotes() throws Exception {
        var source = fixture(); byte[] bytes = CapsuleCodec.encode(source);
        var decoded = CapsuleCodec.decode(bytes);
        assertArrayEquals(bytes, CapsuleCodec.encode(decoded));
        var actor = new TruthActor(KEY, new HealthLatch(), new TransportCapability(true,true,true,true), new PacketMetrics(),256);
        var expected = new ArrayList<String>();
        for (var frame : source.frames()) {
            if (frame instanceof TransportCapsule.Input input) actor.process(TransportReplayMapper.restore(input, KEY), input.elapsedNanos());
            else if (frame instanceof TransportCapsule.Clock clock) actor.tick(clock.elapsedNanos());
            else if (frame instanceof TransportCapsule.Close close) actor.close();
            assertEquals(0, actor.snapshot().certifiedIngressOrdinal(), "cancelled claim cannot become accepted during replay or cleanup");
            expected.add(actor.replayStateDigest());
        }
        assertEquals(expected, new TransportReplayDriver().replay(decoded));
    }
    @Test void everyPayloadFamilyRoundTripsWithExactBits() throws Exception {
        List<PacketPayload> values = List.of(MOVEMENT, new PacketPayload.Movement(null,null,false,true),
                new PacketPayload.Control(PacketPayload.ControlKind.KEEPALIVE,Long.MIN_VALUE),
                new PacketPayload.Velocity(7,new PacketPayload.Position(.1,-0.0,.3)),
                new PacketPayload.Explosion(new PacketPayload.Position(0,0,0),0f,0,Optional.empty()),
                new PacketPayload.Explosion(new PacketPayload.Position(1,2,3),4.5f,12,Optional.of(new PacketPayload.Position(4,5,6))),
                new PacketPayload.Vehicle(new PacketPayload.Position(1,2,3),new PacketPayload.Rotation(-90,45),true),
                new PacketPayload.Passengers(3,List.of(4,5)),new PacketPayload.Health(19,20,5),new PacketPayload.TickRate(10,true),
                new PacketPayload.Uncovered(PacketPayload.Family.WORLD,"unqualified"));
        for (var value : values) {
            var restored=PayloadCodec.decode(PayloadCodec.encode(value));
            assertEquals(value,restored);assertEquals(PayloadDigest.of(value),PayloadDigest.of(restored));
        }
        var bundle=new PacketPayload.Bundle(values);
        assertEquals(bundle,PayloadCodec.decode(PayloadCodec.encode(bundle)));
    }
    @Test void malformedLengthsNestingAndNonfiniteBytesAreRejected() throws Exception {
        assertThrows(IOException.class,()->PayloadCodec.decode(new byte[65537]));
        assertThrows(IOException.class,()->PayloadCodec.decode(new byte[]{1,4,127,-1,-1,-1}));
        assertThrows(IOException.class,()->PayloadCodec.decode(new byte[]{1,4,0,0,0,1,4}));
        byte[] valid=PayloadCodec.encode(MOVEMENT);
        for(int length=0;length<valid.length;length++) {
            byte[] truncated=Arrays.copyOf(valid,length); assertThrows(IOException.class,()->PayloadCodec.decode(truncated));
        }
        valid[2]=2;assertThrows(IOException.class,()->PayloadCodec.decode(valid));
    }
    @Test void corruptedOrIncompleteCapsulesNeverReplay() throws Exception {
        byte[] bytes=CapsuleCodec.encode(fixture());bytes[20]^=1;
        assertThrows(IOException.class,()->CapsuleCodec.decode(bytes));
        var c=fixture();var incomplete=new TransportCapsule(c.schema(),c.profile(),c.configurationDigest(),c.sourceRevision(),c.epoch(),true,c.frames(),false);
        assertThrows(IOException.class,()->new TransportReplayDriver().replay(incomplete));
        var mismatch=new TransportCapsule(1,"other-profile","0".repeat(64),"fixture",1,true,c.frames(),true);
        assertThrows(IOException.class,()->new TransportReplayDriver().replay(mismatch));
    }
    @Test void payloadBytesAreDefensiveAndPendingDigestIncludesPayload() throws Exception {
        byte[] bytes=PayloadCodec.encode(MOVEMENT);var payload=new TransportCapsule.Payload(bytes);bytes[0]=99;
        assertEquals(1,payload.bytes()[0]);byte[] copy=payload.bytes();copy[0]=99;assertEquals(1,payload.bytes()[0]);
        var a=new TruthActor(KEY,new HealthLatch(),TransportCapability.observeOnly(),new PacketMetrics(),256);
        var b=new TruthActor(KEY,new HealthLatch(),TransportCapability.observeOnly(),new PacketMetrics(),256);
        a.process(new Observation(KEY,1,0,0,new Observation.Attempt(1,1,MOVEMENT)),0);
        b.process(new Observation(KEY,1,0,0,new Observation.Attempt(1,1,new PacketPayload.Movement(null,null,true,false))),0);
        assertNotEquals(a.replayStateDigest(),b.replayStateDigest());
    }
    @Test void healthBarrierAndExpiryReproduceAcrossFreshProcesses() throws Exception {
        var frames=new ArrayList<TransportCapsule.Frame>();
        frames.add(TransportReplayMapper.export(new Observation(KEY,1,0,0,new Observation.Attempt(1,1,MOVEMENT)),0,0));
        frames.add(new TransportCapsule.Clock(500_000_001L));
        frames.add(new TransportCapsule.Barrier(500_000_002L,"OVERLOADED",2));
        frames.add(new TransportCapsule.Close(500_000_003L));
        var c=capsule(frames);
        Path dir=Path.of(System.getProperty("repo.root"),"anticheat-testing","build","conformance");Files.createDirectories(dir);
        Path file=dir.resolve("transport.pac");Files.write(file,CapsuleCodec.encode(c));
        String expected=String.join("\n",new TransportReplayDriver().replay(c));
        for(int run=0;run<2;run++) {
            Path output=dir.resolve("run-"+run+".txt");
            var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                    "-cp",System.getProperty("test.runtime.classpath"),ReplayConformanceMain.class.getName(),file.toString())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            assertTrue(process.waitFor(30,TimeUnit.SECONDS),"replay subprocess deadline");
            assertEquals(0,process.exitValue(),Files.readString(output));
            assertEquals(expected,Files.readString(output).strip().replace("\r\n","\n"));
        }
    }
    @Test void missingPrefixAndClockRegressionFailAdmission() throws Exception {
        var input=(TransportCapsule.Input)fixture().frames().getFirst();
        var missing=new TransportCapsule.Input(0,0,2,0,input.context(),input.event());
        assertThrows(IllegalArgumentException.class,()->capsule(List.of(missing)));
        assertThrows(IllegalArgumentException.class,()->capsule(List.of(new TransportCapsule.Clock(2),new TransportCapsule.Clock(1))));
    }
}
