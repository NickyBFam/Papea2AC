package me.nicolas.papea2ac.testing;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import me.nicolas.papea2AC.platform.PaperPacketNormalizer;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.protocol.SemanticPayload.*;

/** Native ItemStack/container family: modern data-component items, never NMS leakage. */
class NativeInventoryTest {
    static final String GAME="net.minecraft.network.protocol.game.";
    static Object registryProvider;
    @BeforeAll static void bootstrap()throws Exception{
        NativePacketTest.bootstrap();
        Class.forName("net.minecraft.server.RegistryLayer").getField("STATIC_ACCESS").get(null);
        registryProvider=Class.forName("net.minecraft.data.registries.VanillaRegistries").getMethod("createLookup").invoke(null);
    }
    static Class<?> type(String name)throws Exception{return Class.forName(name);}
    static Object value(String name,String field)throws Exception{return type(name).getField(field).get(null);}
    static Object create(String name,Class<?>[] types,Object... args)throws Exception{return NativePacketTest.create(name,types,args);}
    static PacketPayload check(Object packet)throws Exception{
        var p=PaperPacketNormalizer.normalize(packet);assertFalse(p instanceof PacketPayload.Uncovered);
        var restored=PayloadCodec.decode(PayloadCodec.encode(p));assertEquals(p,restored);assertEquals(PayloadDigest.of(p),PayloadDigest.of(restored));return p;
    }
    static Object enchantmentHolder(String fieldName)throws Exception{
        Object key=type("net.minecraft.world.item.enchantment.Enchantments").getField(fieldName).get(null);
        Object registryKey=type("net.minecraft.core.registries.Registries").getField("ENCHANTMENT").get(null);
        Object lookup=registryProvider.getClass().getMethod("lookupOrThrow",type("net.minecraft.resources.ResourceKey")).invoke(registryProvider,registryKey);
        return lookup.getClass().getMethod("getOrThrow",type("net.minecraft.resources.ResourceKey")).invoke(lookup,key);
    }
    /**
     * A real, running server binds every item's default {@code DataComponentMap} during full
     * server startup (verified: the smoke-tested live server sends real ItemStacks with no issue).
     * The minimal {@code net.minecraft.server.Bootstrap.bootStrap()} this test harness otherwise
     * uses does not reach that step (verified: no caller of {@code Holder.Reference.bindComponents}
     * appears in Bootstrap, BuiltInRegistries, WorldLoader, RegistryLayer, or Item's own bytecode -
     * it is reached only via the dynamic-registry-dependent {@code DataComponentInitializers} path a
     * full server drives). Rather than reproduce that whole path, bind an empty default map directly
     * through the same public, real {@code Holder.Reference.bindComponents} API a real server uses;
     * every component this test cares about is set explicitly afterward via {@code ItemStack.set}.
     */
    static Object stack(String item,int count)throws Exception{
        Object i=value("net.minecraft.world.item.Items",item);
        Object holder=call(i,"builtInRegistryHolder");
        try{holder.getClass().getMethod("bindComponents",type("net.minecraft.core.component.DataComponentMap")).invoke(holder,value("net.minecraft.core.component.DataComponentMap","EMPTY"));}
        catch(java.lang.reflect.InvocationTargetException alreadyBound){ /* another test already bound this singleton item's components */ }
        return create("net.minecraft.world.item.ItemStack",new Class<?>[]{type("net.minecraft.world.level.ItemLike"),int.class},i,count);
    }
    static Object call(Object target,String method)throws Exception{return target.getClass().getMethod(method).invoke(target);}
    static Object withDamage(Object stack,int damage)throws Exception{
        Object type=type("net.minecraft.core.component.DataComponents").getField("DAMAGE").get(null);
        stack.getClass().getMethod("set",type("net.minecraft.core.component.DataComponentType"),Object.class).invoke(stack,type,damage);
        return stack;
    }
    static Object withEnchant(Object stack,String enchantmentField,int level)throws Exception{
        Object holder=enchantmentHolder(enchantmentField);
        Class<?> mutableClass=type("net.minecraft.world.item.enchantment.ItemEnchantments$Mutable");
        Object empty=type("net.minecraft.world.item.enchantment.ItemEnchantments").getField("EMPTY").get(null);
        Object mutable=mutableClass.getConstructor(type("net.minecraft.world.item.enchantment.ItemEnchantments")).newInstance(empty);
        mutableClass.getMethod("set",type("net.minecraft.core.Holder"),int.class).invoke(mutable,holder,level);
        Object immutable=mutableClass.getMethod("toImmutable").invoke(mutable);
        Object type=type("net.minecraft.core.component.DataComponents").getField("ENCHANTMENTS").get(null);
        stack.getClass().getMethod("set",type("net.minecraft.core.component.DataComponentType"),Object.class).invoke(stack,type,immutable);
        return stack;
    }
    static Object empty()throws Exception{return type("net.minecraft.world.item.ItemStack").getField("EMPTY").get(null);}

    @Test void plainItemRoundTripsIdentityAndCount()throws Exception{
        var pick=(ContainerSlot)check(create(GAME+"ClientboundContainerSetSlotPacket",new Class<?>[]{int.class,int.class,int.class,type("net.minecraft.world.item.ItemStack")},1,7,0,stack("DIAMOND_SWORD",1)));
        assertEquals("minecraft:diamond_sword",pick.item().type());assertEquals(1,pick.item().count());assertFalse(pick.item().empty());
    }
    @Test void emptySlotIsRepresentedAsExplicitEmptyNotNull()throws Exception{
        var slot=(ContainerSlot)check(create(GAME+"ClientboundContainerSetSlotPacket",new Class<?>[]{int.class,int.class,int.class,type("net.minecraft.world.item.ItemStack")},1,7,3,empty()));
        assertEquals(Item.EMPTY,slot.item());assertTrue(slot.item().empty());
    }
    @Test void damageAndEnchantmentsAreTypedNotOpaque()throws Exception{
        Object sword=withEnchant(withDamage(stack("DIAMOND_SWORD",1),12),"SHARPNESS",3);
        var slot=(CursorItem)check(create(GAME+"ClientboundSetCursorItemPacket",new Class<?>[]{type("net.minecraft.world.item.ItemStack")},sword));
        assertEquals(12,slot.item().damage());assertEquals(new Enchant("minecraft:sharpness",3),slot.item().enchantments().getFirst());
    }
    @Test void unknownComponentsAreDisclosedByKeyNotSilentlyDropped()throws Exception{
        Object stick=stack("STICK",1);
        Object customModelDataType=type("net.minecraft.core.component.DataComponents").getField("CUSTOM_MODEL_DATA").get(null);
        Object customModelData=type("net.minecraft.world.item.component.CustomModelData").getField("EMPTY").get(null);
        stick.getClass().getMethod("set",type("net.minecraft.core.component.DataComponentType"),Object.class).invoke(stick,customModelDataType,customModelData);
        var slot=(CursorItem)check(create(GAME+"ClientboundSetCursorItemPacket",new Class<?>[]{type("net.minecraft.world.item.ItemStack")},stick));
        assertTrue(slot.item().otherComponents().contains("minecraft:custom_model_data"),slot.item().otherComponents().toString());
    }
    @Test void containerContentCarriesFullListAndCursor()throws Exception{
        var content=(ContainerContent)check(create(GAME+"ClientboundContainerSetContentPacket",
            new Class<?>[]{int.class,int.class,java.util.List.class,type("net.minecraft.world.item.ItemStack")},
            2,9,List.of(stack("STICK",3),empty()),stack("DIAMOND_SWORD",1)));
        assertEquals(2,content.items().size());assertEquals(3,content.items().get(0).count());assertTrue(content.items().get(1).empty());
        assertEquals("minecraft:diamond_sword",content.carried().type());
    }
    @Test void equipmentCarriesSlotAndItemPairs()throws Exception{
        Object slotEnum=value("net.minecraft.world.entity.EquipmentSlot","MAINHAND");
        Object pair=create("com.mojang.datafixers.util.Pair",new Class<?>[]{Object.class,Object.class},slotEnum,stack("DIAMOND_SWORD",1));
        var eq=(Equipment)check(create(GAME+"ClientboundSetEquipmentPacket",new Class<?>[]{int.class,java.util.List.class},4,List.of(pair)));
        assertEquals(4,eq.entityId());assertEquals("MAINHAND",eq.entries().getFirst().slot());assertEquals("minecraft:diamond_sword",eq.entries().getFirst().item().type());
    }
    @Test void playerInventorySlotAndDataPropertyAreSimpleAndTyped()throws Exception{
        var inv=(PlayerInventorySlot)check(create(GAME+"ClientboundSetPlayerInventoryPacket",new Class<?>[]{int.class,type("net.minecraft.world.item.ItemStack")},36,stack("STICK",1)));
        assertEquals(36,inv.slot());
        assertEquals(new ContainerDataProperty(3,0,20),check(create(GAME+"ClientboundContainerSetDataPacket",new Class<?>[]{int.class,int.class,int.class},3,0,20)));
    }
    @Test void containerButtonAndSlotStateAreSimpleServerboundFacts()throws Exception{
        assertEquals(new ContainerButtonClick(3,1),check(create(GAME+"ServerboundContainerButtonClickPacket",new Class<?>[]{int.class,int.class},3,1)));
        assertEquals(new ContainerSlotStateChanged(5,3,true),check(create(GAME+"ServerboundContainerSlotStateChangedPacket",new Class<?>[]{int.class,int.class,boolean.class},3,5,true)));
    }
    @Test void containerClickCarriesChangedSlotIdentityNeverFabricatedItemContent()throws Exception{
        Object hashedEmpty=type("net.minecraft.network.HashedStack").getField("EMPTY").get(null);
        var changed=new java.util.LinkedHashMap<Integer,Object>();changed.put(3,hashedEmpty);
        Object map=type("it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap").getConstructor().newInstance();
        for(var e:changed.entrySet())map.getClass().getMethod("put",int.class,Object.class).invoke(map,e.getKey(),e.getValue());
        Object input=value("net.minecraft.world.inventory.ContainerInput","PICKUP");
        var click=(ContainerClickClaim)check(create(GAME+"ServerboundContainerClickPacket",
            new Class<?>[]{int.class,int.class,short.class,byte.class,type("net.minecraft.world.inventory.ContainerInput"),type("it.unimi.dsi.fastutil.ints.Int2ObjectMap"),type("net.minecraft.network.HashedStack")},
            2,5,(short)3,(byte)0,input,map,hashedEmpty));
        assertEquals("PICKUP",click.input());assertEquals(1,click.changedSlots().size());assertTrue(click.changedSlots().getFirst().empty());assertTrue(click.carriedEmpty());
    }
}
