package me.nicolas.papea2ac.testing;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import me.nicolas.papea2AC.platform.PaperPacketNormalizer;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.protocol.SemanticPayload.*;

/**
 * {@code ClientboundLevelChunkPacketData}/{@code ClientboundLightUpdatePacketData} have no public
 * constructor reachable without a real, heavyweight {@code LevelChunk}/{@code LevelLightEngine}
 * (verified: both public constructors take one of those). Rather than approximate their behavior
 * with a hand-built stand-in class, this test allocates the REAL native class via {@code Unsafe} and
 * sets its (already bytecode-verified) plain fields directly - it is still the real class our
 * normalizer reflects into, just constructed without running business logic that needs a live world.
 */
class NativeChunkTest {
    static final String GAME="net.minecraft.network.protocol.game.";
    static sun.misc.Unsafe unsafe;
    @BeforeAll static void bootstrap()throws Exception{
        NativePacketTest.bootstrap();
        Field f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);unsafe=(sun.misc.Unsafe)f.get(null);
    }
    static Class<?> type(String name)throws Exception{return Class.forName(name);}
    static Object allocate(String className)throws Exception{return unsafe.allocateInstance(type(className));}
    static void setField(Object target,String name,Object value)throws Exception{
        for(Class<?> c=target.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);f.set(target,value);return;}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
    static PacketPayload check(Object packet)throws Exception{
        var p=PaperPacketNormalizer.normalize(packet);assertFalse(p instanceof PacketPayload.Uncovered);
        var restored=PayloadCodec.decode(PayloadCodec.encode(p));assertEquals(p,restored);assertEquals(PayloadDigest.of(p),PayloadDigest.of(restored));return p;
    }
    static Object lightData(List<byte[]> sky,List<byte[]> block)throws Exception{
        Object data=allocate(GAME+"ClientboundLightUpdatePacketData");
        setField(data,"skyYMask",new BitSet());setField(data,"blockYMask",new BitSet());
        setField(data,"emptySkyYMask",new BitSet());setField(data,"emptyBlockYMask",new BitSet());
        setField(data,"skyUpdates",sky);setField(data,"blockUpdates",block);
        return data;
    }
    @Test void lightUpdateCountsSectionsAndBytesNotPerBlockValues()throws Exception{
        Object data=lightData(List.of(new byte[2048],new byte[2048]),List.of(new byte[2048]));
        Object packet=allocate(GAME+"ClientboundLightUpdatePacket");
        setField(packet,"x",7);setField(packet,"z",-3);setField(packet,"lightData",data);
        var light=(LightUpdate)check(packet);
        assertEquals(7,light.chunkX());assertEquals(-3,light.chunkZ());assertEquals(2,light.skySectionCount());assertEquals(1,light.blockSectionCount());assertEquals(3*2048,light.encodedBytes());
    }
    @Test void chunkBaselineIsCompactNeverExplodingIntoPerBlockObjects()throws Exception{
        Object chunkData=allocate(GAME+"ClientboundLevelChunkPacketData");
        setField(chunkData,"heightmaps",Map.of());
        setField(chunkData,"buffer",new byte[4096]);
        setField(chunkData,"blockEntitiesData",List.of());
        setField(chunkData,"extraPackets",List.of());
        Object lightData=lightData(List.of(),List.of());
        Object packet=allocate(GAME+"ClientboundLevelChunkWithLightPacket");
        setField(packet,"x",12);setField(packet,"z",-5);setField(packet,"chunkData",chunkData);setField(packet,"lightData",lightData);setField(packet,"ready",true);
        var baseline=(ChunkBaseline)check(packet);
        assertEquals(12,baseline.chunkX());assertEquals(-5,baseline.chunkZ());assertEquals(4096,baseline.encodedBlockBytes());assertEquals(0,baseline.blockEntityCount());
    }
}
