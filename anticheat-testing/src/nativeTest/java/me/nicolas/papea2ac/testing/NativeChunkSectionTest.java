package me.nicolas.papea2ac.testing;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import me.nicolas.papea2AC.platform.PaperPacketNormalizer;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.protocol.SemanticPayload.*;

/**
 * Oracle for the P3-E1 chunk-section decoder: the section bytes are produced by the pinned server's OWN
 * {@code PalettedContainer.write} (never hand-assembled), for every block-state palette configuration the
 * 26.2 Strategy can choose (single / linear / hashmap / global), then normalized through the real
 * {@code ClientboundLevelChunkWithLightPacket} path. Expected canonical BlockStates are obtained through an
 * independent path (a {@code ClientboundBlockUpdatePacket} normalized by the same public entry point), so
 * the decoder is never checked against itself.
 */
class NativeChunkSectionTest {
    static final String GAME="net.minecraft.network.protocol.game.";
    static Object registry,strategy,air;
    @BeforeAll static void bootstrap()throws Exception{
        NativeChunkTest.bootstrap();
        registry=Class.forName("net.minecraft.world.level.block.Block").getField("BLOCK_STATE_REGISTRY").get(null);
        strategy=Class.forName("net.minecraft.world.level.chunk.Strategy").getMethod("createForBlockStates",Class.forName("net.minecraft.core.IdMap")).invoke(null,registry);
        air=Class.forName("net.minecraft.world.level.block.Block").getMethod("defaultBlockState").invoke(Class.forName("net.minecraft.world.level.block.Blocks").getField("AIR").get(null));
    }
    static Object byId(int id)throws Exception{return registry.getClass().getMethod("byId",int.class).invoke(registry,id);}
    static int idOf(Object state)throws Exception{return (int)registry.getClass().getMethod("getId",Object.class).invoke(registry,state);}
    /** Independent expected value: the same native state normalized as a single block update. */
    static BlockState expected(Object nativeState)throws Exception{
        Object pos=NativePacketTest.create("net.minecraft.core.BlockPos",new Class<?>[]{int.class,int.class,int.class},0,0,0);
        var change=(BlockChange)PaperPacketNormalizer.normalize(NativePacketTest.create(GAME+"ClientboundBlockUpdatePacket",
            new Class<?>[]{Class.forName("net.minecraft.core.BlockPos"),Class.forName("net.minecraft.world.level.block.state.BlockState")},pos,nativeState));
        return change.state();
    }
    /** A real PalettedContainer for block states, filled through its public set(x,y,z,state). */
    static Object container()throws Exception{
        return Class.forName("net.minecraft.world.level.chunk.PalettedContainer").getConstructor(Object.class,Class.forName("net.minecraft.world.level.chunk.Strategy")).newInstance(air,strategy);
    }
    static void set(Object container,int x,int y,int z,Object state)throws Exception{
        container.getClass().getMethod("set",int.class,int.class,int.class,Object.class).invoke(container,x,y,z,state);
    }
    /** One wire section: short nonEmpty, short fluid, states (real writer), biomes (single-value, id 0). */
    static byte[] sectionBytes(Object states,int nonEmpty)throws Exception{
        Class<?> fbb=Class.forName("net.minecraft.network.FriendlyByteBuf");
        Object buf=fbb.getConstructor(io.netty.buffer.ByteBuf.class).newInstance(io.netty.buffer.Unpooled.buffer());
        fbb.getMethod("writeShort",int.class).invoke(buf,nonEmpty);fbb.getMethod("writeShort",int.class).invoke(buf,0);
        states.getClass().getMethod("write",fbb).invoke(states,buf);
        fbb.getMethod("writeByte",int.class).invoke(buf,0);fbb.getMethod("writeVarInt",int.class).invoke(buf,0);
        io.netty.buffer.ByteBuf raw=(io.netty.buffer.ByteBuf)buf;byte[] out=new byte[raw.readableBytes()];raw.readBytes(out);return out;
    }
    static byte[] concat(byte[]... parts){int n=0;for(var p:parts)n+=p.length;byte[] out=new byte[n];int o=0;for(var p:parts){System.arraycopy(p,0,out,o,p.length);o+=p.length;}return out;}
    static ChunkBaseline normalizeChunk(byte[] buffer,int cx,int cz)throws Exception{
        Object chunkData=NativeChunkTest.allocate(GAME+"ClientboundLevelChunkPacketData");
        NativeChunkTest.setField(chunkData,"heightmaps",Map.of());NativeChunkTest.setField(chunkData,"buffer",buffer);
        NativeChunkTest.setField(chunkData,"blockEntitiesData",List.of());NativeChunkTest.setField(chunkData,"extraPackets",List.of());
        Object packet=NativeChunkTest.allocate(GAME+"ClientboundLevelChunkWithLightPacket");
        NativeChunkTest.setField(packet,"x",cx);NativeChunkTest.setField(packet,"z",cz);NativeChunkTest.setField(packet,"chunkData",chunkData);
        NativeChunkTest.setField(packet,"lightData",NativeChunkTest.lightData(List.of(),List.of()));NativeChunkTest.setField(packet,"ready",true);
        var baseline=(ChunkBaseline)NativeChunkTest.check(packet);
        assertEquals(cx,baseline.chunkX());assertEquals(cz,baseline.chunkZ());assertEquals(buffer.length,baseline.encodedBlockBytes());
        return baseline;
    }

    @Test void singleValueSectionDecodesAsBitsZeroWithOnePaletteEntry()throws Exception{
        var baseline=normalizeChunk(sectionBytes(container(),0),3,-7);
        assertTrue(baseline.contentRetained(),baseline.contentNote());assertEquals(1,baseline.sections().size());
        var s=baseline.sections().getFirst();
        assertEquals(0,s.bitsPerEntry());assertEquals(1,s.palette().size());assertEquals(0,s.packedLength());
        assertEquals(expected(air),s.stateAt(15,15,15));
    }
    @Test void linearPaletteSectionRoundTripsEveryPlacedState()throws Exception{
        Object c=container();Object s1=byId(1),s2=byId(2),s3=byId(3);
        set(c,0,0,0,s1);set(c,15,0,0,s2);set(c,7,9,3,s3);set(c,15,15,15,s1);
        var baseline=normalizeChunk(sectionBytes(c,4),0,0);
        assertTrue(baseline.contentRetained(),baseline.contentNote());
        var s=baseline.sections().getFirst();
        assertEquals(4,s.bitsPerEntry(),"1..4 wire bits are stored at 4 bits, exactly like the client reader");
        assertEquals(expected(s1),s.stateAt(0,0,0));assertEquals(expected(s2),s.stateAt(15,0,0));assertEquals(expected(s3),s.stateAt(7,9,3));
        assertEquals(expected(s1),s.stateAt(15,15,15));assertEquals(expected(air),s.stateAt(1,1,1));
        assertEquals(4,s.nonEmptyBlockCount());
    }
    @Test void hashMapPaletteSectionDecodesFiveToEightBits()throws Exception{
        Object c=container();
        for(int i=1;i<=40;i++)set(c,i&15,(i>>4)&15,0,byId(i));
        var baseline=normalizeChunk(sectionBytes(c,40),0,0);
        assertTrue(baseline.contentRetained(),baseline.contentNote());
        var s=baseline.sections().getFirst();
        assertTrue(s.bitsPerEntry()>=5&&s.bitsPerEntry()<=8,"41 states need a hashmap palette: "+s.bitsPerEntry());
        for(int i=1;i<=40;i++)assertEquals(expected(byId(i)),s.stateAt(i&15,(i>>4)&15,0),"state "+i);
        assertEquals(expected(air),s.stateAt(0,15,15));
    }
    @Test void globalPaletteSectionIsRePalettizedToCanonicalStatesWithoutLeakingIds()throws Exception{
        Object c=container();
        for(int i=1;i<=300;i++)set(c,i&15,(i>>4)&15,(i>>8)&15,byId(i));
        var baseline=normalizeChunk(sectionBytes(c,300),0,0);
        assertTrue(baseline.contentRetained(),baseline.contentNote());
        var s=baseline.sections().getFirst();
        assertEquals(301,s.palette().size(),"300 distinct states plus air");
        assertEquals(9,s.bitsPerEntry(),"re-palettized to the minimal indirect width (>=4)");
        for(int i=1;i<=300;i++)assertEquals(expected(byId(i)),s.stateAt(i&15,(i>>4)&15,(i>>8)&15),"state "+i);
        assertEquals(expected(air),s.stateAt(0,0,0));
        for(var state:s.palette())assertTrue(state.block().startsWith("minecraft:"),"canonical registry key, never a numeric id: "+state);
    }
    @Test void multipleSectionsAreReadUntilTheBufferIsExhaustedInWireOrder()throws Exception{
        Object c1=container();set(c1,1,1,1,byId(1));Object c2=container();set(c2,2,2,2,byId(2));
        var baseline=normalizeChunk(concat(sectionBytes(container(),0),sectionBytes(c1,1),sectionBytes(c2,1)),-1,-1);
        assertTrue(baseline.contentRetained(),baseline.contentNote());assertEquals(3,baseline.sections().size());
        assertEquals(0,baseline.sections().get(0).bitsPerEntry());
        assertEquals(expected(byId(1)),baseline.sections().get(1).stateAt(1,1,1));
        assertEquals(expected(byId(2)),baseline.sections().get(2).stateAt(2,2,2));
    }
    @Test void truncatedBufferKeepsTheSummaryButMarksContentNotRetained()throws Exception{
        Object c=container();set(c,0,0,0,byId(1));
        byte[] full=sectionBytes(c,1);byte[] cut=Arrays.copyOf(full,full.length/2);
        var baseline=normalizeChunk(cut,5,5);
        assertFalse(baseline.contentRetained());assertTrue(baseline.sections().isEmpty());assertTrue(baseline.contentNote().startsWith("DECODE_FAULT:"),baseline.contentNote());
    }
    @Test void contentOverTheInlineCapIsExplicitlyNotRetainedNeverSilentlyTruncated()throws Exception{
        Object c=container();for(int i=1;i<=200;i++)set(c,i&15,(i>>4)&15,0,byId(i)); // 8-bit hashmap section: 512 longs
        byte[] one=sectionBytes(c,200);byte[][] many=new byte[24][];Arrays.fill(many,one);
        var baseline=normalizeChunk(concat(many),0,0);
        assertFalse(baseline.contentRetained());assertTrue(baseline.contentNote().startsWith("OVER_INLINE_CAP:"),baseline.contentNote());
        assertTrue(baseline.sections().isEmpty());assertEquals(24*one.length,baseline.encodedBlockBytes());
    }
    @Test void aZeroFilledLegacyFixtureIsRejectedAsASectionCountFaultNotDecodedAsFiveHundredAirSections()throws Exception{
        var baseline=normalizeChunk(new byte[4096],12,-5);
        assertFalse(baseline.contentRetained());assertEquals("DECODE_FAULT:SECTION_COUNT",baseline.contentNote());
    }
}
