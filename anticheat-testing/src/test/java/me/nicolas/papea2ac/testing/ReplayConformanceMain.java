package me.nicolas.papea2ac.testing;

import java.nio.file.*;
import me.nicolas.papea2ac.core.TransportReplayDriver;
import me.nicolas.papea2ac.replay.CapsuleCodec;
import me.nicolas.papea2ac.schema.TransportCapsule;

/** A fresh JVM recomputes output hashes; no recorded intermediate states are substituted. */
public final class ReplayConformanceMain {
    private ReplayConformanceMain() {}
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("capsule path required");
        Path file=Path.of(args[0]);
        if(Files.size(file)>TransportCapsule.MAX_BYTES)throw new IllegalArgumentException("capsule size");
        byte[] bytes=Files.readAllBytes(file);
        if(file.getFileName().toString().endsWith(".b64"))bytes=java.util.Base64.getDecoder().decode(new String(bytes,java.nio.charset.StandardCharsets.US_ASCII).strip());
        var capsule=CapsuleCodec.decode(bytes);
        for(String digest:new TransportReplayDriver().replay(capsule))System.out.println(digest);
    }
}
