package me.nicolas.papea2ac.testing;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import me.nicolas.papea2AC.platform.PaperPacketNormalizer;

/**
 * Descriptive, deterministic hot-path timing for the heaviest normalizer families (item/data-component,
 * large container, chunk baseline, entity metadata) - NOT a JMH run and NOT a production capacity claim.
 *
 * <p>Full JMH (fork-isolated, warmup/measurement-iteration harness) was judged impractical for these
 * specific families: each requires real, heavyweight native construction (VanillaRegistries bootstrap,
 * Holder.Reference.bindComponents, sun.misc.Unsafe.allocateInstance field injection - see NativeInventoryTest/
 * NativeChunkTest) that only exists in the nativeTest source set, which is wired to the pinned Paper runtime
 * jar; the benchmark source set is not. Rather than duplicate that wiring for a handful of methods, this is a
 * plain warmup-then-measure loop in nativeTest, reusing the exact same construction helpers the correctness
 * tests already use - the same limitation JMH's own single-fork descriptive mode would have anyway on a
 * shared host. Allocation is sampled via Runtime heap-used before/after a batch, which is imprecise (GC can
 * run mid-batch) but sufficient to catch a gross per-op allocation regression, which is all this is for.
 */
public final class HeavyNormalizerBenchmarkMain {
    private HeavyNormalizerBenchmarkMain(){}
    static final String GAME="net.minecraft.network.protocol.game.";
    static Object registryProvider;
    static Class<?> type(String name)throws Exception{return Class.forName(name);}
    static Object value(String name,String field)throws Exception{return type(name).getField(field).get(null);}
    static Object call(Object target,String method)throws Exception{return target.getClass().getMethod(method).invoke(target);}
    static Object create(String name,Class<?>[] types,Object... args)throws Exception{
        return type(name).getConstructor(types).newInstance(args);
    }
    static Object stack(String item,int count)throws Exception{
        Object i=value("net.minecraft.world.item.Items",item);
        Object holder=call(i,"builtInRegistryHolder");
        try{holder.getClass().getMethod("bindComponents",type("net.minecraft.core.component.DataComponentMap")).invoke(holder,value("net.minecraft.core.component.DataComponentMap","EMPTY"));}
        catch(InvocationTargetException alreadyBound){ /* already bound */ }
        return create("net.minecraft.world.item.ItemStack",new Class<?>[]{type("net.minecraft.world.level.ItemLike"),int.class},i,count);
    }
    static Object heavyItem()throws Exception{
        Object sword=stack("DIAMOND_SWORD",1);
        Object damageType=type("net.minecraft.core.component.DataComponents").getField("DAMAGE").get(null);
        sword.getClass().getMethod("set",type("net.minecraft.core.component.DataComponentType"),Object.class).invoke(sword,damageType,50);
        Object registryKey=type("net.minecraft.core.registries.Registries").getField("ENCHANTMENT").get(null);
        Object lookup=registryProvider.getClass().getMethod("lookupOrThrow",type("net.minecraft.resources.ResourceKey")).invoke(registryProvider,registryKey);
        Object key=type("net.minecraft.world.item.enchantment.Enchantments").getField("SHARPNESS").get(null);
        Object holder=lookup.getClass().getMethod("getOrThrow",type("net.minecraft.resources.ResourceKey")).invoke(lookup,key);
        Class<?> mutableClass=type("net.minecraft.world.item.enchantment.ItemEnchantments$Mutable");
        Object empty=type("net.minecraft.world.item.enchantment.ItemEnchantments").getField("EMPTY").get(null);
        Object mutable=mutableClass.getConstructor(type("net.minecraft.world.item.enchantment.ItemEnchantments")).newInstance(empty);
        mutableClass.getMethod("set",type("net.minecraft.core.Holder"),int.class).invoke(mutable,holder,5);
        Object immutable=mutableClass.getMethod("toImmutable").invoke(mutable);
        Object enchantType=type("net.minecraft.core.component.DataComponents").getField("ENCHANTMENTS").get(null);
        sword.getClass().getMethod("set",type("net.minecraft.core.component.DataComponentType"),Object.class).invoke(sword,enchantType,immutable);
        Object customModelDataType=type("net.minecraft.core.component.DataComponents").getField("CUSTOM_MODEL_DATA").get(null);
        Object customModelData=type("net.minecraft.world.item.component.CustomModelData").getField("EMPTY").get(null);
        sword.getClass().getMethod("set",type("net.minecraft.core.component.DataComponentType"),Object.class).invoke(sword,customModelDataType,customModelData);
        return sword;
    }
    static Object largeContainer(int itemCount)throws Exception{
        var items=new ArrayList<>();
        for(int i=0;i<itemCount;i++)items.add(i%5==0?type("net.minecraft.world.item.ItemStack").getField("EMPTY").get(null):stack("STICK",1+i%64));
        return create(GAME+"ClientboundContainerSetContentPacket",
            new Class<?>[]{int.class,int.class,List.class,type("net.minecraft.world.item.ItemStack")},
            1,itemCount,items,heavyItem());
    }
    static sun.misc.Unsafe unsafe;
    static Object allocate(String className)throws Exception{return unsafe.allocateInstance(type(className));}
    static void setField(Object target,String name,Object value)throws Exception{
        for(Class<?> c=target.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);f.set(target,value);return;}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
    static Object chunkBaseline(int blockBytes)throws Exception{
        Object chunkData=allocate(GAME+"ClientboundLevelChunkPacketData");
        setField(chunkData,"heightmaps",Map.of());
        setField(chunkData,"buffer",new byte[blockBytes]);
        setField(chunkData,"blockEntitiesData",List.of());
        setField(chunkData,"extraPackets",List.of());
        Object lightData=allocate(GAME+"ClientboundLightUpdatePacketData");
        setField(lightData,"skyYMask",new BitSet());setField(lightData,"blockYMask",new BitSet());
        setField(lightData,"emptySkyYMask",new BitSet());setField(lightData,"emptyBlockYMask",new BitSet());
        setField(lightData,"skyUpdates",List.of(new byte[2048],new byte[2048]));setField(lightData,"blockUpdates",List.of(new byte[2048]));
        Object packet=allocate(GAME+"ClientboundLevelChunkWithLightPacket");
        setField(packet,"x",0);setField(packet,"z",0);setField(packet,"chunkData",chunkData);setField(packet,"lightData",lightData);setField(packet,"ready",true);
        return packet;
    }
    static Object entityMetadata(int fieldCount)throws Exception{
        Object intSerializer=value("net.minecraft.network.syncher.EntityDataSerializers","INT");
        Object itemStackSerializer=value("net.minecraft.network.syncher.EntityDataSerializers","ITEM_STACK");
        var values=new ArrayList<>();
        for(int i=0;i<fieldCount;i++){
            boolean opaque=i%3==0;
            values.add(create("net.minecraft.network.syncher.SynchedEntityData$DataValue",
                new Class<?>[]{int.class,type("net.minecraft.network.syncher.EntityDataSerializer"),Object.class},
                i,opaque?itemStackSerializer:intSerializer,opaque?type("net.minecraft.world.item.ItemStack").getField("EMPTY").get(null):i));
        }
        return create(GAME+"ClientboundSetEntityDataPacket",new Class<?>[]{int.class,List.class},7,values);
    }
    private record Result(String name,long warmupOps,long measuredOps,double nsPerOp,double approxBytesPerOp){}
    /**
     * Pre-builds a pool of distinct native packets BEFORE timing starts, then only times
     * {@code PaperPacketNormalizer.normalize(...)} itself, cycling through the pool. This isolates the
     * normalizer's own cost from the (often much larger, and irrelevant here) cost of constructing a fake
     * native packet through reflection for test purposes - an earlier version of this harness timed both
     * together and materially overstated allocation for chunk/container families as a result.
     */
    private static Result measure(String name,int warmup,int iterations,ReflectiveSupplier factory)throws Exception{
        int poolSize=Math.min(iterations,256);
        var pool=new Object[poolSize];
        for(int i=0;i<poolSize;i++)pool[i]=factory.get();
        for(int i=0;i<warmup;i++)PaperPacketNormalizer.normalize(pool[i%poolSize]);
        System.gc();
        long heapBefore=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        long start=System.nanoTime();
        for(int i=0;i<iterations;i++)PaperPacketNormalizer.normalize(pool[i%poolSize]);
        long elapsed=System.nanoTime()-start;
        long heapAfter=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        double bytesPerOp=Math.max(0,heapAfter-heapBefore)/(double)iterations;
        return new Result(name,warmup,iterations,elapsed/(double)iterations,bytesPerOp);
    }
    @FunctionalInterface private interface ReflectiveSupplier{Object get()throws Exception;}
    public static void main(String[] args)throws Exception{
        NativePacketTest.bootstrap();
        Class.forName("net.minecraft.server.RegistryLayer").getField("STATIC_ACCESS").get(null);
        registryProvider=Class.forName("net.minecraft.data.registries.VanillaRegistries").getMethod("createLookup").invoke(null);
        Field f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);unsafe=(sun.misc.Unsafe)f.get(null);
        var results=new ArrayList<Result>();
        results.add(measure("itemDataComponent",2_000,20_000,HeavyNormalizerBenchmarkMain::heavyItem));
        results.add(measure("largeContainer200Items",200,2_000,()->largeContainer(200)));
        results.add(measure("chunkBaseline4096Bytes",500,5_000,()->chunkBaseline(4096)));
        results.add(measure("entityMetadata30Fields",1_000,10_000,()->entityMetadata(30)));
        var json=new StringBuilder("{\"kind\":\"descriptive-not-jmh\",\"results\":[");
        for(int i=0;i<results.size();i++){
            var r=results.get(i);
            if(i>0)json.append(",");
            json.append(String.format(Locale.ROOT,"{\"name\":\"%s\",\"warmupOps\":%d,\"measuredOps\":%d,\"nsPerOp\":%.3f,\"approxBytesPerOp\":%.1f}",
                r.name(),r.warmupOps(),r.measuredOps(),r.nsPerOp(),r.approxBytesPerOp()));
        }
        json.append("]}\n");
        Files.writeString(Path.of(args[0]),json.toString());
        System.out.println(json);
    }
}
