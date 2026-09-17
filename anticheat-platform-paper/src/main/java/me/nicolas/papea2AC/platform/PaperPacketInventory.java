package me.nicolas.papea2AC.platform;

import java.lang.reflect.*;
import java.util.*;

/** Startup-only inventory from the native protocol's numeric ID table. No packet instances are retained. */
public final class PaperPacketInventory {
    public static final String EXPECTED_SHA256="6078740cadcfa6c271425af2975ce94a88f989a81309c1af21690a6c17e1d7a8";
    private PaperPacketInventory() {}
    public record Entry(String state, String flow, int wireId, String type) {
        public Entry {
            if(wireId<0||wireId>1023||type.length()>128)throw new IllegalArgumentException("inventory bounds");
        }
        public String canonical() { return state+"\t"+flow+"\t"+wireId+"\t"+type; }
    }
    public static List<Entry> inspect() throws ReflectiveOperationException {
        Class<?> provider=Class.forName("net.minecraft.network.ProtocolInfo$DetailsProvider");
        Class<?> details=Class.forName("net.minecraft.network.ProtocolInfo$Details");
        Class<?> visitor=Class.forName("net.minecraft.network.ProtocolInfo$Details$PacketVisitor");
        Class<?> packetType=Class.forName("net.minecraft.network.protocol.PacketType");
        var entries=new ArrayList<Entry>();
        String[] protocols={"handshake.HandshakeProtocols","status.StatusProtocols","login.LoginProtocols",
                "configuration.ConfigurationProtocols","game.GameProtocols"};
        for(String protocol:protocols){
            Class<?> owner=Class.forName("net.minecraft.network.protocol."+protocol);
            for(String field:List.of("SERVERBOUND_TEMPLATE","CLIENTBOUND_TEMPLATE")){
                if(protocol.startsWith("handshake.")&&field.startsWith("CLIENTBOUND"))continue;
                Object template=owner.getField(field).get(null);
                Object inventory=provider.getMethod("details").invoke(template);
                String state=details.getMethod("id").invoke(inventory).toString();
                String flow=details.getMethod("flow").invoke(inventory).toString();
                Object callback=Proxy.newProxyInstance(visitor.getClassLoader(),new Class<?>[]{visitor},(proxy,method,args)->{
                    if(method.getName().equals("accept")){
                        if(entries.size()>=1024)throw new IllegalArgumentException("inventory count");
                        entries.add(new Entry(state,flow,(int)args[1],packetType.getMethod("id").invoke(args[0]).toString()));
                        return null;
                    }
                    return switch(method.getName()){
                        case "toString" -> "Papea2AC inventory visitor";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy==args[0];
                        default -> throw new IllegalStateException("unexpected visitor method");
                    };
                });
                details.getMethod("listPackets",visitor).invoke(inventory,callback);
            }
        }
        entries.sort(Comparator.comparing(Entry::state).thenComparing(Entry::flow).thenComparingInt(Entry::wireId));
        return List.copyOf(entries);
    }
    public static String canonical(List<Entry> entries){
        return String.join("\n",entries.stream().map(Entry::canonical).toList())+"\n";
    }
    public static boolean matchesPinned(List<Entry> entries){
        try{return EXPECTED_SHA256.equals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical(entries).getBytes(java.nio.charset.StandardCharsets.UTF_8))));}
        catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
}
