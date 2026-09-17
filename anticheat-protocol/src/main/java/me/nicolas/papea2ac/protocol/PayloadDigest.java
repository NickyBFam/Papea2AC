package me.nicolas.papea2ac.protocol;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
/** Schema v1 canonical bit encoding, independent of record names, locale and toString(). */
public final class PayloadDigest {
    private PayloadDigest() {}
    public static String of(PacketPayload payload) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            digest.update((byte)1);append(digest,payload);
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    private static void number(MessageDigest d,long value){for(int i=56;i>=0;i-=8)d.update((byte)(value>>>i));}
    private static void vector(MessageDigest d,PacketPayload.Position p){
        number(d,Double.doubleToRawLongBits(p.x()));number(d,Double.doubleToRawLongBits(p.y()));number(d,Double.doubleToRawLongBits(p.z()));
    }
    private static void rotation(MessageDigest d,PacketPayload.Rotation r){
        number(d,Float.floatToRawIntBits(r.yaw()));number(d,Float.floatToRawIntBits(r.pitch()));
    }
    private static void text(MessageDigest d,String value){byte[] bytes=value.getBytes(StandardCharsets.UTF_8);number(d,bytes.length);d.update(bytes);}
    private static void append(MessageDigest d,PacketPayload p){
        switch(p){
            case SemanticPayload semantic -> {
                d.update((byte)11);
                try{d.update(PayloadCodec.encode(semantic));}catch(java.io.IOException e){throw new IllegalArgumentException("semantic encoding",e);}
            }
            case PacketPayload.Movement m -> {
                d.update((byte)1);d.update((byte)(m.position()!=null?1:0));if(m.position()!=null)vector(d,m.position());
                d.update((byte)(m.rotation()!=null?1:0));if(m.rotation()!=null)rotation(d,m.rotation());
                d.update((byte)(m.onGround()?1:0));d.update((byte)(m.horizontalCollision()?1:0));
            }
            case PacketPayload.Control c -> {d.update((byte)2);text(d,c.kind().name());number(d,c.value());}
            case PacketPayload.Uncovered u -> {d.update((byte)3);text(d,u.family().name());text(d,u.reason());}
            case PacketPayload.Bundle b -> {d.update((byte)4);number(d,b.children().size());for(var child:b.children())append(d,child);}
            case PacketPayload.Velocity v -> {d.update((byte)5);number(d,v.entityId());vector(d,v.vector());}
            case PacketPayload.Explosion e -> {
                d.update((byte)6);vector(d,e.center());d.update((byte)(e.playerKnockback().isPresent()?1:0));
                e.playerKnockback().ifPresent(v->vector(d,v));
            }
            case PacketPayload.Vehicle v -> {d.update((byte)7);vector(d,v.position());rotation(d,v.rotation());d.update((byte)(v.onGround()?1:0));}
            case PacketPayload.Passengers psg -> {d.update((byte)8);number(d,psg.vehicleId());number(d,psg.passengers().size());for(int id:psg.passengers())number(d,id);}
            case PacketPayload.Health h -> {d.update((byte)9);number(d,Float.floatToRawIntBits(h.health()));number(d,h.food());number(d,Float.floatToRawIntBits(h.saturation()));}
            case PacketPayload.TickRate t -> {d.update((byte)10);number(d,Float.floatToRawIntBits(t.rate()));d.update((byte)(t.frozen()?1:0));}
        }
    }
}
