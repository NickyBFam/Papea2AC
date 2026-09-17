package me.nicolas.papea2ac.testing;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2AC.platform.PaperPacketNormalizer;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.protocol.SemanticPayload.*;

/** Simple lifecycle markers, explosion motion/scope, entity-synced-data, and compact non-chunk world facts. */
class NativeWorldMiscTest {
    static final String GAME="net.minecraft.network.protocol.game.";
    @BeforeAll static void bootstrap()throws Exception{NativePacketTest.bootstrap();}
    static Class<?> type(String name)throws Exception{return Class.forName(name);}
    static Object value(String name,String field)throws Exception{return type(name).getField(field).get(null);}
    static Object create(String name,Class<?>[] types,Object... args)throws Exception{return NativePacketTest.create(name,types,args);}
    static Object vec(double x,double y,double z)throws Exception{return create("net.minecraft.world.phys.Vec3",new Class<?>[]{double.class,double.class,double.class},x,y,z);}
    static Object block(int x,int y,int z)throws Exception{return create("net.minecraft.core.BlockPos",new Class<?>[]{int.class,int.class,int.class},x,y,z);}
    static PacketPayload check(Object packet)throws Exception{
        var p=PaperPacketNormalizer.normalize(packet);assertFalse(p instanceof PacketPayload.Uncovered);
        var restored=PayloadCodec.decode(PayloadCodec.encode(p));assertEquals(p,restored);assertEquals(PayloadDigest.of(p),PayloadDigest.of(restored));return p;
    }
    @Test void chunkBatchMarkersAndBundleDelimiterAreDistinctTransitions()throws Exception{
        assertEquals(new Lifecycle(LifecycleTransition.CHUNK_BATCH_START),check(value(GAME+"ClientboundChunkBatchStartPacket","INSTANCE")));
        assertEquals(new ChunkBatchAck(12f),check(create(GAME+"ClientboundChunkBatchFinishedPacket",new Class<?>[]{int.class},12)));
        assertEquals(new Lifecycle(LifecycleTransition.BUNDLE_DELIMITER),check(create(GAME+"ClientboundBundleDelimiterPacket",new Class<?>[]{},new Object[]{})));
        assertNotEquals(new Lifecycle(LifecycleTransition.CHUNK_BATCH_START),new Lifecycle(LifecycleTransition.BUNDLE_DELIMITER));
    }
    @Test void explosionCarriesMotionAndScopeButNotCosmeticFields()throws Exception{
        Object particle=value("net.minecraft.core.particles.ParticleTypes","EXPLOSION");
        Object soundHolder=value("net.minecraft.sounds.SoundEvents","GENERIC_EXPLODE");
        Object emptyWeighted=type("net.minecraft.util.random.WeightedList").getMethod("of").invoke(null);
        var explosion=(PacketPayload.Explosion)check(create(GAME+"ClientboundExplodePacket",
            new Class<?>[]{type("net.minecraft.world.phys.Vec3"),float.class,int.class,Optional.class,type("net.minecraft.core.particles.ParticleOptions"),type("net.minecraft.core.Holder"),type("net.minecraft.util.random.WeightedList")},
            vec(1,64,3),4.5f,7,Optional.of(vec(.1,.2,.3)),particle,soundHolder,emptyWeighted));
        assertEquals(4.5f,explosion.radius());assertEquals(7,explosion.blockCount());assertTrue(explosion.playerKnockback().isPresent());
    }
    @Test void entitySyncedDataDistinguishesScalarFromOpaqueSerializerKinds()throws Exception{
        Object intSerializer=value("net.minecraft.network.syncher.EntityDataSerializers","INT");
        Object itemStackSerializer=value("net.minecraft.network.syncher.EntityDataSerializers","ITEM_STACK");
        Object intValue=create("net.minecraft.network.syncher.SynchedEntityData$DataValue",new Class<?>[]{int.class,type("net.minecraft.network.syncher.EntityDataSerializer"),Object.class},0,intSerializer,7);
        Object stackValue=create("net.minecraft.network.syncher.SynchedEntityData$DataValue",new Class<?>[]{int.class,type("net.minecraft.network.syncher.EntityDataSerializer"),Object.class},1,itemStackSerializer,
            type("net.minecraft.world.item.ItemStack").getField("EMPTY").get(null));
        var data=(EntitySyncedData)check(create(GAME+"ClientboundSetEntityDataPacket",new Class<?>[]{int.class,java.util.List.class},5,List.of(intValue,stackValue)));
        assertEquals(5,data.entityId());
        var intField=data.fields().stream().filter(f->f.id()==0).findFirst().orElseThrow();
        assertEquals("INT",intField.kind());assertEquals("7",intField.value());
        var stackField=data.fields().stream().filter(f->f.id()==1).findFirst().orElseThrow();
        assertEquals("OPAQUE",stackField.kind());assertEquals("",stackField.value(),"heavy serializer kinds are presence-only, never a native object leak");
    }
    @Test void blockEntityObservationCarriesIdentityAndBoundedNbtSizeNotTreeContent()throws Exception{
        Object tag=create("net.minecraft.nbt.CompoundTag",new Class<?>[]{},new Object[]{});
        tag.getClass().getMethod("putInt",String.class,int.class).invoke(tag,"Items",3);
        Object chestType=value("net.minecraft.core.registries.BuiltInRegistries","BLOCK_ENTITY_TYPE");
        Object chest=chestType.getClass().getMethod("getValue",type("net.minecraft.resources.Identifier")).invoke(chestType,
            type("net.minecraft.resources.Identifier").getMethod("parse",String.class).invoke(null,"minecraft:chest"));
        var observation=(BlockEntityObservation)check(create(GAME+"ClientboundBlockEntityDataPacket",
            new Class<?>[]{type("net.minecraft.core.BlockPos"),type("net.minecraft.world.level.block.entity.BlockEntityType"),type("net.minecraft.nbt.CompoundTag")},
            block(1,2,3),chest,tag));
        assertEquals("minecraft:chest",observation.type());assertTrue(observation.encodedTagBytes()>0);
    }
    @Test void chunksBiomesCarriesChunkIdentityAndBoundedByteLengthNotPaletteContent()throws Exception{
        Object pos=create("net.minecraft.world.level.ChunkPos",new Class<?>[]{int.class,int.class},4,-2);
        Object biomeData=create(GAME+"ClientboundChunksBiomesPacket$ChunkBiomeData",new Class<?>[]{type("net.minecraft.world.level.ChunkPos"),byte[].class},pos,new byte[]{1,2,3,4});
        var biomes=(ChunksBiomes)check(create(GAME+"ClientboundChunksBiomesPacket",new Class<?>[]{java.util.List.class},List.of(biomeData)));
        assertEquals(1,biomes.chunks().size());assertEquals(4,biomes.chunks().getFirst().chunkX());assertEquals(4,biomes.chunks().getFirst().encodedBytes());
    }
    @Test void sectionBlocksUpdateCarriesEachChangedBlockAsCanonicalBlockState()throws Exception{
        Object sectionPos=type("net.minecraft.core.SectionPos").getMethod("of",int.class,int.class,int.class).invoke(null,0,4,-1);
        Object stone=value("net.minecraft.world.level.block.Blocks","STONE");
        Object stoneState=stone.getClass().getMethod("defaultBlockState").invoke(stone);
        Object map=type("it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap").getConstructor().newInstance();
        map.getClass().getMethod("put",short.class,Object.class).invoke(map,(short)0,stoneState);
        var update=(SectionBlocksUpdate)check(create(GAME+"ClientboundSectionBlocksUpdatePacket",
            new Class<?>[]{type("net.minecraft.core.SectionPos"),type("it.unimi.dsi.fastutil.shorts.Short2ObjectMap")},sectionPos,map));
        assertEquals(0,update.sectionX());assertEquals(4,update.sectionY());assertEquals(-1,update.sectionZ());
        assertEquals(1,update.updates().size());assertEquals("minecraft:stone",update.updates().getFirst().state().block());
    }
}
