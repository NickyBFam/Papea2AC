package me.nicolas.papea2AC.platform;
import java.lang.reflect.*;
import java.util.*;
import me.nicolas.papea2ac.protocol.PacketPayload;
import me.nicolas.papea2ac.protocol.Normalizer;
import me.nicolas.papea2ac.version.ProtocolProfile;
/** Exact 26.2 accessors inspected against pinned server bytecode; semantic qualification remains separate. */
public final class PaperPacketNormalizer {
    private PaperPacketNormalizer(){}
    private static Object call(Object o,String method)throws ReflectiveOperationException{return o.getClass().getMethod(method).invoke(o);}
    private static long number(Object o,String method)throws ReflectiveOperationException{return ((Number)call(o,method)).longValue();}
    private static PacketPayload.Position vector(Object o)throws ReflectiveOperationException{
        return new PacketPayload.Position(o.getClass().getField("x").getDouble(o),o.getClass().getField("y").getDouble(o),o.getClass().getField("z").getDouble(o));
    }
    public static PacketPayload normalize(Object p)throws ReflectiveOperationException{
        String name=p.getClass().getName();
        if(!name.startsWith("net.minecraft.network.protocol."))return unsupported(PacketPayload.Family.UNKNOWN,name);// class name: bounded diagnosability for non-protocol pipeline objects (P3-F3 qualification)
        PacketPayload semantic=PaperSemanticNormalizer.normalize(p);if(semantic!=null)return semantic;
        if(name.startsWith("net.minecraft.network.protocol.game.ServerboundMovePlayerPacket")){
            Class<?> c=p.getClass();boolean pos=(boolean)call(p,"hasPosition"),rot=(boolean)call(p,"hasRotation");
            return Normalizer.movement(ProtocolProfile.PAPER_26_2,pos,
                (double)c.getMethod("getX",double.class).invoke(p,0d),(double)c.getMethod("getY",double.class).invoke(p,0d),
                (double)c.getMethod("getZ",double.class).invoke(p,0d),rot,
                (float)c.getMethod("getYRot",float.class).invoke(p,0f),(float)c.getMethod("getXRot",float.class).invoke(p,0f),
                (boolean)call(p,"isOnGround"),(boolean)call(p,"horizontalCollision"));
        }
        return switch(p.getClass().getSimpleName()){
            case "ServerboundClientTickEndPacket" -> control(PacketPayload.ControlKind.TICK_END,0);
            case "ServerboundSetCarriedItemPacket" -> control(PacketPayload.ControlKind.HELD_ITEM,number(p,"getSlot"));
            case "ServerboundAcceptTeleportationPacket" -> control(PacketPayload.ControlKind.TELEPORT_CONFIRM,number(p,"getId"));
            case "ServerboundPongPacket" -> control(PacketPayload.ControlKind.PONG,number(p,"getId"));
            case "ServerboundKeepAlivePacket","ClientboundKeepAlivePacket" -> control(PacketPayload.ControlKind.KEEPALIVE,number(p,"getId"));
            case "ClientboundPingPacket" -> control(PacketPayload.ControlKind.PING,number(p,"getId"));
            case "ServerboundSwingPacket" -> control(PacketPayload.ControlKind.SWING,((Enum<?>)call(p,"getHand")).ordinal());
            case "ServerboundPaddleBoatPacket" -> control(PacketPayload.ControlKind.PADDLE,((boolean)call(p,"getLeft")?1:0)|((boolean)call(p,"getRight")?2:0));
            case "ServerboundPlayerInputPacket" -> input(call(p,"input"));
            case "ClientboundSetEntityMotionPacket" -> new PacketPayload.Velocity((int)number(p,"id"),vector(call(p,"movement")));
            case "ClientboundExplodePacket" -> explosion(p);
            case "ClientboundSetPassengersPacket" -> passengers(p);
            case "ClientboundSetHealthPacket" -> new PacketPayload.Health((float)call(p,"getHealth"),(int)number(p,"getFood"),(float)call(p,"getSaturation"));
            case "ClientboundTickingStatePacket" -> new PacketPayload.TickRate((float)call(p,"tickRate"),(boolean)call(p,"isFrozen"));
            case "ClientboundTickingStepPacket" -> control(PacketPayload.ControlKind.TICK_STEP,number(p,"tickSteps"));
            case "ServerboundMoveVehiclePacket" -> new PacketPayload.Vehicle(vector(call(p,"position")),new PacketPayload.Rotation((float)call(p,"yRot"),(float)call(p,"xRot")),(boolean)call(p,"onGround"));
            case "ClientboundSetCameraPacket" -> camera(p);
            default -> unsupported(classify(p.getClass().getSimpleName()));
        };
    }
    private static PacketPayload.Control control(PacketPayload.ControlKind kind,long value){return new PacketPayload.Control(kind,value);}
    private static PacketPayload input(Object input)throws ReflectiveOperationException{
        String[] fields={"forward","backward","left","right","jump","shift","sprint"};int bits=0;
        for(int i=0;i<fields.length;i++)if((boolean)call(input,fields[i]))bits|=1<<i;
        return control(PacketPayload.ControlKind.INPUT,bits);
    }
    private static PacketPayload explosion(Object p)throws ReflectiveOperationException{
        Optional<?> impulse=(Optional<?>)call(p,"playerKnockback");
        return new PacketPayload.Explosion(vector(call(p,"center")),(float)call(p,"radius"),(int)number(p,"blockCount"),
            impulse.isPresent()?Optional.of(vector(impulse.get())):Optional.empty());
    }
    private static PacketPayload passengers(Object p)throws ReflectiveOperationException{
        int[] ids=(int[])call(p,"getPassengers");if(ids.length>256)throw new IllegalArgumentException("passenger count");
        return new PacketPayload.Passengers((int)number(p,"getVehicle"),Arrays.stream(ids).boxed().toList());
    }
    private static PacketPayload camera(Object p)throws ReflectiveOperationException{
        Field field=p.getClass().getDeclaredField("cameraId");field.setAccessible(true);
        return control(PacketPayload.ControlKind.CAMERA,field.getInt(p));
    }
    public static PacketPayload.Family classify(String name){
        if(name.contains("Chunk")||name.contains("Block")||name.contains("Border"))return PacketPayload.Family.WORLD;
        if(name.contains("Respawn")||name.contains("Login")||name.contains("GameEvent"))return PacketPayload.Family.LIFECYCLE;
        if(name.contains("Attributes")||name.contains("Effect")||name.contains("Equipment")||name.contains("Abilities"))return PacketPayload.Family.ATTRIBUTES;
        if(name.contains("Container")||name.contains("Slot")||name.contains("CarriedItem"))return PacketPayload.Family.INVENTORY;
        if(name.contains("Interact")||name.contains("UseItem")||name.contains("PlayerAction")||name.contains("PlayerCommand"))return PacketPayload.Family.INTERACTION;
        if(name.contains("Vehicle"))return PacketPayload.Family.VEHICLE;
        if(name.contains("Entity")||name.contains("Entities"))return PacketPayload.Family.ENTITY;
        return PacketPayload.Family.PROTOCOL;
    }
    private static PacketPayload unsupported(PacketPayload.Family family){return new PacketPayload.Uncovered(family,"MissingObservationFamily");}
    private static PacketPayload unsupported(PacketPayload.Family family,String className){String r="MissingObservationFamily:"+className;return new PacketPayload.Uncovered(family,r.length()>128?r.substring(0,128):r);}
}
