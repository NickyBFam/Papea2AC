package me.nicolas.papea2AC.platform;

import java.lang.reflect.Field;
import java.util.*;
import me.nicolas.papea2ac.protocol.*;
import me.nicolas.papea2ac.protocol.SemanticPayload.*;

/** Pinned 26.2 semantic accessors. No live native value escapes this file. */
final class PaperSemanticNormalizer {
    private PaperSemanticNormalizer(){}
    static Object call(Object value,String method)throws ReflectiveOperationException{return value.getClass().getMethod(method).invoke(value);}
    static Object call(Object value,String method,int argument)throws ReflectiveOperationException{return value.getClass().getMethod(method,int.class).invoke(value,argument);}
    static Object call(Object value,String method,Class<?> paramType,Object argument)throws ReflectiveOperationException{return value.getClass().getMethod(method,paramType).invoke(value,argument);}
    static Object field(Object value,String name)throws ReflectiveOperationException{
        for(Class<?> c=value.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(value);}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
    static int integer(Object value,String method)throws ReflectiveOperationException{return ((Number)call(value,method)).intValue();}
    static boolean bool(Object value,String method)throws ReflectiveOperationException{return (boolean)call(value,method);}
    static String enumeration(Object value){return ((Enum<?>)value).name();}
    static Hand hand(Object value){return Hand.valueOf(enumeration(value));}
    static PacketPayload.Position vector(Object value)throws ReflectiveOperationException{
        return new PacketPayload.Position(((Number)field(value,"x")).doubleValue(),((Number)field(value,"y")).doubleValue(),((Number)field(value,"z")).doubleValue());
    }
    static PacketPayload.Rotation rotation(Object value,String yaw,String pitch)throws ReflectiveOperationException{
        return new PacketPayload.Rotation(((Number)call(value,yaw)).floatValue(),((Number)call(value,pitch)).floatValue());
    }
    static BlockPosition blockPosition(Object value)throws ReflectiveOperationException{return new BlockPosition(integer(value,"getX"),integer(value,"getY"),integer(value,"getZ"));}
    static String registryKey(String registry,Object value)throws ReflectiveOperationException{
        Object nativeRegistry=Class.forName("net.minecraft.core.registries.BuiltInRegistries").getField(registry).get(null);
        Object key=Class.forName("net.minecraft.core.Registry").getMethod("getKey",Object.class).invoke(nativeRegistry,value);
        if(key==null)throw new IllegalArgumentException("unregistered semantic value");return key.toString();
    }
    static String holderKey(Object holder)throws ReflectiveOperationException{
        Optional<?> key=(Optional<?>)Class.forName("net.minecraft.core.Holder").getMethod("unwrapKey").invoke(holder);
        if(key.isEmpty())throw new IllegalArgumentException("unregistered holder");return call(key.get(),"identifier").toString();
    }
    private static Set<Relative> relatives(Object packet)throws ReflectiveOperationException{
        Set<?> set=(Set<?>)call(packet,"relatives");if(set.size()>9)throw new IllegalArgumentException("relative flags");
        var result=EnumSet.noneOf(Relative.class);for(Object item:set)result.add(Relative.valueOf(enumeration(item)));return result;
    }
    static BlockState blockState(Object nativeState)throws ReflectiveOperationException{
        String key=registryKey("BLOCK",call(nativeState,"getBlock"));Collection<?> values=(Collection<?>)call(nativeState,"getProperties");
        if(values.size()>32)throw new IllegalArgumentException("block properties");var properties=new ArrayList<Property>(values.size());
        Class<?> property=Class.forName("net.minecraft.world.level.block.state.properties.Property");
        for(Object entry:values){Object value=nativeState.getClass().getMethod("getValue",property).invoke(nativeState,entry);
            properties.add(new Property((String)property.getMethod("getName").invoke(entry),
                (String)property.getMethod("getName",Comparable.class).invoke(entry,value)));}
        return new BlockState(key,properties);
    }
    static PacketPayload normalize(Object p)throws ReflectiveOperationException{
        if(p.getClass().getName().startsWith("net.minecraft.network.protocol.game.ClientboundMoveEntityPacket$"))
            return new EntityDelta(((Number)field(p,"entityId")).intValue(),(short)integer(p,"getXa"),(short)integer(p,"getYa"),(short)integer(p,"getZa"),
                rotation(p,"getYRot","getXRot"),bool(p,"hasPosition"),bool(p,"hasRotation"),bool(p,"isOnGround"));
        return switch(p.getClass().getSimpleName()){
            case "ServerboundAttackPacket" -> new Attack(integer(p,"entityId"));
            case "ServerboundInteractPacket" -> new Interact(integer(p,"entityId"),hand(call(p,"hand")),vector(call(p,"location")),bool(p,"usingSecondaryAction"));
            case "ServerboundPlayerActionPacket" -> new Dig(enumeration(call(p,"getAction")),blockPosition(call(p,"getPos")),Face.valueOf(enumeration(call(p,"getDirection"))),integer(p,"getSequence"));
            case "ServerboundPlayerCommandPacket" -> new PlayerCommand(integer(p,"getId"),enumeration(call(p,"getAction")),integer(p,"getData"));
            case "ServerboundUseItemPacket" -> new UseItem(hand(call(p,"getHand")),integer(p,"getSequence"),rotation(p,"getYRot","getXRot"));
            case "ServerboundUseItemOnPacket" -> useOn(p);
            case "ClientboundPlayerPositionPacket" -> {
                Object change=call(p,"change");yield new Correction(integer(p,"id"),vector(call(change,"position")),vector(call(change,"deltaMovement")),rotation(change,"yRot","xRot"),relatives(p));
            }
            case "ClientboundPlayerRotationPacket" -> new RotationCorrection(rotation(p,"yRot","xRot"),bool(p,"relativeY"),bool(p,"relativeX"));
            case "ClientboundMoveVehiclePacket" -> new VehicleCorrection(vector(call(p,"position")),rotation(p,"yRot","xRot"));
            case "ClientboundPlayerAbilitiesPacket" -> new Abilities(bool(p,"isInvulnerable"),bool(p,"isFlying"),bool(p,"canFly"),bool(p,"canInstabuild"),(float)call(p,"getFlyingSpeed"),(float)call(p,"getWalkingSpeed"));
            case "ServerboundPlayerAbilitiesPacket" -> new FlightRequest(bool(p,"isFlying"));
            case "ClientboundUpdateAttributesPacket" -> attributes(p);
            case "ClientboundUpdateMobEffectPacket" -> new Effect(integer(p,"getEntityId"),holderKey(call(p,"getEffect")),integer(p,"getEffectAmplifier"),integer(p,"getEffectDurationTicks"),bool(p,"isEffectAmbient"),bool(p,"isEffectVisible"),bool(p,"effectShowsIcon"),bool(p,"shouldBlend"));
            case "ClientboundRemoveMobEffectPacket" -> new RemoveEffect(integer(p,"entityId"),holderKey(call(p,"effect")));
            case "ClientboundEntityPositionSyncPacket","ClientboundTeleportEntityPacket" -> {
                boolean teleport=p.getClass().getSimpleName().equals("ClientboundTeleportEntityPacket");Object value=call(p,teleport?"change":"values");
                yield new EntityPosition(integer(p,"id"),vector(call(value,"position")),vector(call(value,"deltaMovement")),rotation(value,"yRot","xRot"),teleport?relatives(p):Set.of(),bool(p,"onGround"));
            }
            case "ClientboundRemoveEntitiesPacket" -> {
                Collection<?> ids=(Collection<?>)call(p,"getEntityIds");if(ids.size()>1024)throw new IllegalArgumentException("remove entities");
                var copy=new ArrayList<Integer>(ids.size());for(Object id:ids)copy.add((Integer)id);yield new RemoveEntities(copy);
            }
            case "ClientboundBlockUpdatePacket" -> new BlockChange(blockPosition(call(p,"getPos")),blockState(call(p,"getBlockState")));
            case "ClientboundForgetLevelChunkPacket" -> {Object pos=call(p,"pos");yield new ChunkUnload(((Number)field(pos,"x")).intValue(),((Number)field(pos,"z")).intValue());}
            case "ClientboundBlockEventPacket" -> new BlockEvent(blockPosition(call(p,"getPos")),registryKey("BLOCK",call(p,"getBlock")),integer(p,"getB0"),integer(p,"getB1"));
            case "ClientboundGameEventPacket" -> new GameEvent(((Number)field(call(p,"getEvent"),"id")).intValue(),(float)call(p,"getParam"));
            case "ClientboundBlockChangedAckPacket" -> new SequenceAck(integer(p,"sequence"));
            case "ClientboundSetHeldSlotPacket" -> new HeldSlot(integer(p,"slot"));
            case "ClientboundBundlePacket" -> bundle(p);
            case "ClientboundAddEntityPacket" -> new EntitySpawn(integer(p,"getId"),(UUID)call(p,"getUUID"),registryKey("ENTITY_TYPE",call(p,"getType")),new PacketPayload.Position((double)call(p,"getX"),(double)call(p,"getY"),(double)call(p,"getZ")),vector(call(p,"getMovement")),rotation(p,"getYRot","getXRot"),(float)call(p,"getYHeadRot"),integer(p,"getData"));
            case "ClientboundSetBorderCenterPacket" -> new BorderCenter((double)call(p,"getNewCenterX"),(double)call(p,"getNewCenterZ"));
            case "ClientboundSetBorderSizePacket" -> new BorderSize((double)call(p,"getSize"));
            case "ClientboundSetBorderLerpSizePacket" -> new BorderLerp((double)call(p,"getOldSize"),(double)call(p,"getNewSize"),(long)call(p,"getLerpTime"));
            case "ClientboundSetBorderWarningDelayPacket" -> new BorderWarning(false,integer(p,"getWarningDelay"));
            case "ClientboundSetBorderWarningDistancePacket" -> new BorderWarning(true,integer(p,"getWarningBlocks"));
            case "ClientboundInitializeBorderPacket" -> new BorderInitialize((double)call(p,"getNewCenterX"),(double)call(p,"getNewCenterZ"),(double)call(p,"getOldSize"),(double)call(p,"getNewSize"),(long)call(p,"getLerpTime"),integer(p,"getNewAbsoluteMaxSize"),integer(p,"getWarningBlocks"),integer(p,"getWarningTime"));
            case "ClientboundRotateHeadPacket" -> new HeadRotation(((Number)field(p,"entityId")).intValue(),(float)call(p,"getYHeadRot"));
            case "ClientboundSetEntityLinkPacket" -> new EntityLink(integer(p,"getSourceId"),integer(p,"getDestId"));
            case "ClientboundDisconnectPacket" -> new Disconnect((String)call(call(p,"reason"),"getString",256));
            case "ClientboundFinishConfigurationPacket" -> new Lifecycle(LifecycleTransition.CONFIGURATION_FINISH);
            case "ServerboundFinishConfigurationPacket" -> new Lifecycle(LifecycleTransition.CONFIGURATION_FINISH);
            case "ClientboundResetChatPacket" -> new Lifecycle(LifecycleTransition.RESET_CHAT);
            case "ServerboundAcceptCodeOfConductPacket" -> new Lifecycle(LifecycleTransition.ACCEPT_CODE_OF_CONDUCT);
            case "ClientboundCodeOfConductPacket" -> new CodeOfConduct((String)call(p,"codeOfConduct"));
            case "ClientboundTransferPacket" -> new Transfer((String)call(p,"host"),integer(p,"port"));
            case "ServerboundClientInformationPacket" -> clientSettings(call(p,"information"));
            case "ClientboundUpdateTagsPacket" -> tagSync((Map<?,?>)call(p,"getTags"));
            case "ClientboundRegistryDataPacket" -> registrySync(p);
            case "ClientboundUpdateEnabledFeaturesPacket" -> enabledFeatures((Set<?>)call(p,"features"));
            case "ClientboundSelectKnownPacks","ServerboundSelectKnownPacks" -> knownPacks((List<?>)call(p,"knownPacks"));
            case "ClientboundContainerClosePacket","ServerboundContainerClosePacket" -> new ContainerClose(integer(p,"getContainerId"));
            case "ClientboundCooldownPacket" -> new Cooldown(call(p,"cooldownGroup").toString(),integer(p,"duration"));
            case "ClientboundProjectilePowerPacket" -> new ProjectilePower(integer(p,"getId"),(double)call(p,"getAccelerationPower"));
            case "ClientboundStartConfigurationPacket" -> new Lifecycle(LifecycleTransition.PLAY_TO_CONFIGURATION);
            case "ServerboundConfigurationAcknowledgedPacket" -> new Lifecycle(LifecycleTransition.PLAY_TO_CONFIGURATION_ACK);
            case "ServerboundPlayerLoadedPacket" -> new Lifecycle(LifecycleTransition.PLAYER_LOADED);
            case "ServerboundChangeGameModePacket" -> new GameModeChange(enumeration(call(p,"mode")));
            case "ServerboundClientCommandPacket" -> new ClientCommand(enumeration(call(p,"getAction")));
            case "ServerboundChunkBatchReceivedPacket" -> new ChunkBatchAck((float)call(p,"desiredChunksPerTick"));
            case "ClientboundSetChunkCacheCenterPacket" -> new ChunkCacheCenter(integer(p,"getX"),integer(p,"getZ"));
            case "ClientboundSetChunkCacheRadiusPacket" -> new ChunkCacheRadius(integer(p,"getRadius"));
            case "ClientboundSetSimulationDistancePacket" -> new SimulationDistance(integer(p,"simulationDistance"));
            case "ClientboundOpenScreenPacket" -> new OpenScreen(integer(p,"getContainerId"),registryKey("MENU",call(p,"getType")),(String)call(call(p,"getTitle"),"getString",256));
            case "ClientboundPlayerCombatKillPacket" -> new CombatKill(integer(p,"playerId"),(String)call(call(p,"message"),"getString",256));
            case "ClientIntentionPacket" -> new Handshake(integer(p,"protocolVersion"),enumeration(call(p,"intention")));
            case "ClientboundHelloPacket" -> new Lifecycle(LifecycleTransition.LOGIN_ENCRYPTION_REQUEST);
            case "ServerboundKeyPacket" -> new Lifecycle(LifecycleTransition.LOGIN_KEY_RESPONSE);
            case "ServerboundLoginAcknowledgedPacket" -> new Lifecycle(LifecycleTransition.LOGIN_ACKNOWLEDGED);
            case "ClientboundLoginCompressionPacket" -> new LoginCompression(integer(p,"getCompressionThreshold"));
            case "ClientboundLoginDisconnectPacket" -> new Disconnect((String)call(call(p,"reason"),"getString",256));
            case "ServerboundHelloPacket" -> new LoginHello((String)call(p,"name"),(UUID)call(p,"profileId"));
            case "ClientboundCustomQueryPacket" -> new LoginPluginQuery(integer(p,"transactionId"),call(call(p,"payload"),"id").toString());
            case "ServerboundCustomQueryAnswerPacket" -> new LoginPluginQueryResponse(integer(p,"transactionId"),call(p,"payload")!=null);
            case "ClientboundLoginFinishedPacket" -> {
                Object profile=call(p,"gameProfile");yield new LoginFinished((UUID)call(profile,"id"),(String)call(profile,"name"));
            }
            case "ClientboundLoginPacket" -> joinGame(p);
            case "ClientboundChunkBatchFinishedPacket" -> new ChunkBatchAck((float)integer(p,"batchSize"));
            case "ClientboundChunkBatchStartPacket" -> new Lifecycle(LifecycleTransition.CHUNK_BATCH_START);
            case "ClientboundContainerSetDataPacket" -> new ContainerDataProperty(integer(p,"getContainerId"),integer(p,"getId"),integer(p,"getValue"));
            case "ServerboundContainerButtonClickPacket" -> new ContainerButtonClick(integer(p,"containerId"),integer(p,"buttonId"));
            case "ServerboundContainerSlotStateChangedPacket" -> new ContainerSlotStateChanged(integer(p,"containerId"),integer(p,"slotId"),bool(p,"newState"));
            case "ClientboundEntityEventPacket" -> new EntitySimpleEvent(((Number)field(p,"entityId")).intValue(),(byte)call(p,"getEventId"));
            case "ClientboundBundleDelimiterPacket" -> new Lifecycle(LifecycleTransition.BUNDLE_DELIMITER);
            case "ClientboundDamageEventPacket" -> damageEvent(p);
            case "ClientboundPlayerLookAtPacket" -> lookAt(p);
            case "ClientboundMoveMinecartPacket" -> minecartMove(p);
            case "ClientboundRespawnPacket" -> respawn(p);
            case "ClientboundSectionBlocksUpdatePacket" -> sectionBlocksUpdate(p);
            case "ClientboundChunksBiomesPacket" -> chunksBiomes(p);
            case "ClientboundLightUpdatePacket" -> lightUpdate(call(p,"getLightData"),integer(p,"getX"),integer(p,"getZ"));
            case "ClientboundLevelChunkWithLightPacket" -> chunkBaseline(p);
            case "ClientboundBlockEntityDataPacket" -> new BlockEntityObservation(blockPosition(call(p,"getPos")),registryKey("BLOCK_ENTITY_TYPE",call(p,"getType")),Math.min(integer(call(p,"getTag"),"sizeInBytes"),1_048_576));
            case "ClientboundSetEntityDataPacket" -> entitySyncedData(p);
            case "ClientboundContainerSetContentPacket" -> containerContent(p);
            case "ClientboundContainerSetSlotPacket" -> new ContainerSlot(integer(p,"getContainerId"),integer(p,"getStateId"),integer(p,"getSlot"),item(call(p,"getItem")));
            case "ClientboundSetCursorItemPacket" -> new CursorItem(item(call(p,"contents")));
            case "ClientboundSetEquipmentPacket" -> equipment(p);
            case "ClientboundSetPlayerInventoryPacket" -> new PlayerInventorySlot(integer(p,"slot"),item(call(p,"contents")));
            case "ServerboundContainerClickPacket" -> containerClickClaim(p);
            default -> null;
        };
    }
    private static ClientSettings clientSettings(Object info)throws ReflectiveOperationException{
        return new ClientSettings((String)call(info,"language"),integer(info,"viewDistance"),enumeration(call(info,"mainHand")),bool(info,"allowsListing"));
    }
    private static TagSync tagSync(Map<?,?> tags)throws ReflectiveOperationException{
        if(tags.size()>64)throw new IllegalArgumentException("tag registries");
        var registries=new ArrayList<String>(tags.size());
        for(Object key:tags.keySet())registries.add(call(key,"identifier").toString());
        return new TagSync(registries);
    }
    private static RegistrySync registrySync(Object p)throws ReflectiveOperationException{
        Object key=call(p,"registry");String name=call(key,"identifier").toString();
        Collection<?> entries=(Collection<?>)call(p,"entries");
        return new RegistrySync(name,entries.size());
    }
    private static EnabledFeatures enabledFeatures(Set<?> features)throws ReflectiveOperationException{
        if(features.size()>64)throw new IllegalArgumentException("feature count");
        var list=new ArrayList<String>(features.size());
        for(Object feature:features)list.add(feature.toString());
        return new EnabledFeatures(list);
    }
    private static JoinGame joinGame(Object p)throws ReflectiveOperationException{
        Object spawnInfo=call(p,"commonPlayerSpawnInfo");Object dimensionKey=call(spawnInfo,"dimension");
        return new JoinGame(integer(p,"playerId"),bool(p,"hardcore"),call(dimensionKey,"identifier").toString(),
            integer(p,"maxPlayers"),integer(p,"chunkRadius"),integer(p,"simulationDistance"),
            enumeration(call(spawnInfo,"gameType")),bool(p,"onlineMode"),bool(p,"enforcesSecureChat"));
    }
    /**
     * Known-relevant fields are typed (durability, unbreakable, enchantments); every other present
     * component is recorded only by registry key in {@code otherComponents}. Verified against the
     * pinned server: {@code DataComponentMap} is iterable/keyed by singleton {@code DataComponentType}
     * instances compared here with {@code .equals()}, never by guessing a wire/registry numeric ID.
     */
    static Item item(Object stack)throws ReflectiveOperationException{
        if(bool(stack,"isEmpty"))return Item.EMPTY;
        String type=registryKey("ITEM",call(stack,"getItem"));
        int count=integer(stack,"getCount");
        Object components=call(stack,"getComponents");
        Set<?> keys=(Set<?>)call(components,"keySet");
        if(keys.size()>128)throw new IllegalArgumentException("item component count");
        Class<?> componentType=Class.forName("net.minecraft.core.component.DataComponentType");
        Class<?> dataComponents=Class.forName("net.minecraft.core.component.DataComponents");
        Object damageKey=dataComponents.getField("DAMAGE").get(null);
        Object maxDamageKey=dataComponents.getField("MAX_DAMAGE").get(null);
        Object unbreakableKey=dataComponents.getField("UNBREAKABLE").get(null);
        Object enchantmentsKey=dataComponents.getField("ENCHANTMENTS").get(null);
        int damage=0,maxDamage=0;boolean unbreakable=false;List<Enchant> enchantments=List.of();var other=new ArrayList<String>();
        for(Object key:keys){
            if(key.equals(damageKey))damage=(Integer)call(components,"get",componentType,key);
            else if(key.equals(maxDamageKey))maxDamage=(Integer)call(components,"get",componentType,key);
            else if(key.equals(unbreakableKey))unbreakable=true;
            else if(key.equals(enchantmentsKey))enchantments=enchantments(call(components,"get",componentType,key));
            else other.add(registryKey("DATA_COMPONENT_TYPE",key));
        }
        return new Item(false,type,count,damage,maxDamage,unbreakable,enchantments,other);
    }
    private static List<Enchant> enchantments(Object itemEnchantments)throws ReflectiveOperationException{
        Set<?> entries=(Set<?>)call(itemEnchantments,"entrySet");
        if(entries.size()>32)throw new IllegalArgumentException("enchantment count");
        var list=new ArrayList<Enchant>(entries.size());
        for(Object entry:entries)list.add(new Enchant(holderKey(call(entry,"getKey")),(int)call(entry,"getIntValue")));
        return list;
    }
    private static Respawn respawn(Object p)throws ReflectiveOperationException{
        Object spawnInfo=call(p,"commonPlayerSpawnInfo");Object dimensionKey=call(spawnInfo,"dimension");
        return new Respawn(call(dimensionKey,"identifier").toString(),(byte)call(p,"dataToKeep"));
    }
    private static DamageEvent damageEvent(Object p)throws ReflectiveOperationException{
        int entityId=integer(p,"entityId");String type=holderKey(call(p,"sourceType"));
        int cause=integer(p,"sourceCauseId"),direct=integer(p,"sourceDirectId");
        Optional<?> position=(Optional<?>)call(p,"sourcePosition");
        if(position.isPresent()){Object vec=position.get();return new DamageEvent(entityId,type,cause,direct,true,
            ((Number)field(vec,"x")).doubleValue(),((Number)field(vec,"y")).doubleValue(),((Number)field(vec,"z")).doubleValue());}
        return new DamageEvent(entityId,type,cause,direct,false,0,0,0);
    }
    /** The position-only variant leaves toAnchor/entity unset (null/0); atEntity distinguishes the two shapes rather than guessing from nullability alone. */
    private static LookAt lookAt(Object p)throws ReflectiveOperationException{
        String from=enumeration(field(p,"fromAnchor"));
        boolean atEntity=(boolean)field(p,"atEntity");
        Object toAnchorValue=field(p,"toAnchor");
        String to=toAnchorValue!=null?enumeration(toAnchorValue):"NONE";
        return new LookAt(from,((Number)field(p,"x")).doubleValue(),((Number)field(p,"y")).doubleValue(),((Number)field(p,"z")).doubleValue(),
            ((Number)field(p,"entity")).intValue(),to,atEntity);
    }
    private static MinecartMove minecartMove(Object p)throws ReflectiveOperationException{
        int entityId=integer(p,"entityId");List<?> steps=(List<?>)call(p,"lerpSteps");
        if(steps.size()>256)throw new IllegalArgumentException("minecart step count");
        var list=new ArrayList<MinecartStepFact>(steps.size());
        for(Object step:steps){
            Object position=call(step,"position"),movement=call(step,"movement");
            list.add(new MinecartStepFact(((Number)field(position,"x")).doubleValue(),((Number)field(position,"y")).doubleValue(),((Number)field(position,"z")).doubleValue(),
                (float)call(step,"yRot"),(float)call(step,"xRot")));
        }
        return new MinecartMove(entityId,list);
    }
    private static SectionBlocksUpdate sectionBlocksUpdate(Object p)throws ReflectiveOperationException{
        Object sectionPos=field(p,"sectionPos");
        int sx=integer(sectionPos,"x"),sy=integer(sectionPos,"y"),sz=integer(sectionPos,"z");
        short[] positions=(short[])field(p,"positions");Object[] states=(Object[])field(p,"states");
        if(positions.length>4096)throw new IllegalArgumentException("section block update count");
        var updates=new ArrayList<BlockUpdate>(positions.length);
        for(int i=0;i<positions.length;i++){
            int packed=positions[i]&0xFFFF;
            int localX=packed>>8&0xF,localZ=packed>>4&0xF,localY=packed&0xF;
            updates.add(new BlockUpdate(new BlockPosition((sx<<4)+localX,(sy<<4)+localY,(sz<<4)+localZ),blockState(states[i])));
        }
        return new SectionBlocksUpdate(sx,sy,sz,updates);
    }
    private static ChunksBiomes chunksBiomes(Object p)throws ReflectiveOperationException{
        List<?> chunkBiomeData=(List<?>)call(p,"chunkBiomeData");
        if(chunkBiomeData.size()>256)throw new IllegalArgumentException("chunk biome count");
        var chunks=new ArrayList<ChunkBiomeObservation>(chunkBiomeData.size());
        for(Object entry:chunkBiomeData){
            Object pos=call(entry,"pos");byte[] buffer=(byte[])call(entry,"buffer");
            chunks.add(new ChunkBiomeObservation(integer(pos,"x"),integer(pos,"z"),buffer.length));
        }
        return new ChunksBiomes(chunks);
    }
    private static LightUpdate lightUpdate(Object lightData,int chunkX,int chunkZ)throws ReflectiveOperationException{
        List<?> sky=(List<?>)call(lightData,"getSkyUpdates"),block=(List<?>)call(lightData,"getBlockUpdates");
        int encoded=0;for(Object arr:sky)encoded+=((byte[])arr).length;for(Object arr:block)encoded+=((byte[])arr).length;
        return new LightUpdate(chunkX,chunkZ,sky.size(),block.size(),encoded);
    }
    private static ChunkBaseline chunkBaseline(Object p)throws ReflectiveOperationException{
        int x=integer(p,"getX"),z=integer(p,"getZ");
        Object chunkData=call(p,"getChunkData");
        byte[] buffer=(byte[])field(chunkData,"buffer");
        List<?> blockEntities=(List<?>)field(chunkData,"blockEntitiesData");
        Map<?,?> heightmaps=(Map<?,?>)call(chunkData,"getHeightmaps");
        return new ChunkBaseline(x,z,buffer.length,blockEntities.size(),heightmaps.size(),true);
    }
    /** Lazily memoized: this reflective lookup is identical on every call (the pinned server's serializer
     * singletons never change at runtime), so resolving it once instead of on every packet materially
     * lowers entity-metadata normalization's per-call allocation (measured via HeavyNormalizerBenchmarkMain:
     * ~636 bytes/field before caching for a 30-field packet, dominated by this map's own reconstruction). */
    private static volatile Map<Object,String> scalarEntityDataKinds;
    private static Map<Object,String> scalarEntityDataKinds()throws ReflectiveOperationException{
        Map<Object,String> cached=scalarEntityDataKinds;
        if(cached!=null)return cached;
        Class<?> serializers=Class.forName("net.minecraft.network.syncher.EntityDataSerializers");
        Map<Object,String> built=new LinkedHashMap<>();
        for(String name:List.of("BYTE","INT","LONG","FLOAT","STRING","BOOLEAN","DIRECTION"))built.put(serializers.getField(name).get(null),name);
        scalarEntityDataKinds=built;
        return built;
    }
    private static EntitySyncedData entitySyncedData(Object p)throws ReflectiveOperationException{
        int entityId=integer(p,"id");List<?> packed=(List<?>)call(p,"packedItems");
        if(packed.size()>64)throw new IllegalArgumentException("entity data field count");
        Map<Object,String> scalars=scalarEntityDataKinds();
        var fields=new ArrayList<EntityDataField>(packed.size());
        for(Object value:packed){
            int id=integer(value,"id");Object serializer=call(value,"serializer");Object raw=call(value,"value");
            String kind=scalars.get(serializer);
            fields.add(kind!=null?new EntityDataField(id,kind,String.valueOf(raw)):new EntityDataField(id,"OPAQUE",""));
        }
        return new EntitySyncedData(entityId,fields);
    }
    private static ContainerContent containerContent(Object p)throws ReflectiveOperationException{
        int containerId=integer(p,"containerId"),stateId=integer(p,"stateId");
        List<?> items=(List<?>)call(p,"items");if(items.size()>256)throw new IllegalArgumentException("container content size");
        var list=new ArrayList<Item>(items.size());for(Object stack:items)list.add(item(stack));
        return new ContainerContent(containerId,stateId,list,item(call(p,"carriedItem")));
    }
    private static Equipment equipment(Object p)throws ReflectiveOperationException{
        int entityId=integer(p,"getEntity");List<?> slots=(List<?>)call(p,"getSlots");
        if(slots.size()>16)throw new IllegalArgumentException("equipment slot count");
        var entries=new ArrayList<EquipmentEntry>(slots.size());
        for(Object pair:slots){
            Object slot=call(pair,"getFirst");Object stack=call(pair,"getSecond");
            entries.add(new EquipmentEntry(enumeration(slot),item(stack)));
        }
        return new Equipment(entityId,entries);
    }
    private static ContainerClickClaim containerClickClaim(Object p)throws ReflectiveOperationException{
        int containerId=integer(p,"containerId"),stateId=integer(p,"stateId");
        int slot=((Number)call(p,"slotNum")).intValue(),button=((Number)call(p,"buttonNum")).intValue();
        String input=enumeration(call(p,"containerInput"));
        Object empty=Class.forName("net.minecraft.network.HashedStack").getField("EMPTY").get(null);
        Map<?,?> changed=(Map<?,?>)call(p,"changedSlots");
        if(changed.size()>128)throw new IllegalArgumentException("changed slot count");
        var list=new ArrayList<ChangedSlot>(changed.size());
        for(var entry:changed.entrySet())list.add(new ChangedSlot((Integer)entry.getKey(),entry.getValue().equals(empty)));
        list.sort(Comparator.comparingInt(ChangedSlot::slot));
        boolean carriedEmpty=call(p,"carriedItem").equals(empty);
        return new ContainerClickClaim(containerId,stateId,slot,button,input,list,carriedEmpty);
    }
    private static KnownPacks knownPacks(List<?> packs)throws ReflectiveOperationException{
        if(packs.size()>64)throw new IllegalArgumentException("known pack count");
        var list=new ArrayList<String>(packs.size());
        for(Object pack:packs)list.add(call(pack,"namespace")+":"+call(pack,"id")+"@"+call(pack,"version"));
        return new KnownPacks(list);
    }
    private static UseOn useOn(Object p)throws ReflectiveOperationException{
        Object hit=call(p,"getHitResult");
        return new UseOn(hand(call(p,"getHand")),integer(p,"getSequence"),blockPosition(call(hit,"getBlockPos")),
            Face.valueOf(enumeration(call(hit,"getDirection"))),vector(call(hit,"getLocation")),bool(hit,"isInside"),bool(hit,"isWorldBorderHit"));
    }
    private static Attributes attributes(Object p)throws ReflectiveOperationException{
        Collection<?> values=(Collection<?>)call(p,"getValues");if(values.size()>64)throw new IllegalArgumentException("attribute count");
        var copy=new ArrayList<Attribute>(values.size());int count=0,bytes=128;
        for(Object value:values){
            Collection<?> modifiers=(Collection<?>)call(value,"modifiers");count+=modifiers.size();bytes+=640+1152*modifiers.size();
            if(modifiers.size()>32||count>128||bytes>65536)throw new IllegalArgumentException("attribute retained-byte/count limit");
            var list=new ArrayList<Modifier>(modifiers.size());
            for(Object modifier:modifiers)list.add(new Modifier(call(modifier,"id").toString(),(double)call(modifier,"amount"),enumeration(call(modifier,"operation"))));
            copy.add(new Attribute(holderKey(call(value,"attribute")),(double)call(value,"base"),list));
        }
        return new Attributes(integer(p,"getEntityId"),copy);
    }
    private static PacketPayload.Bundle bundle(Object p)throws ReflectiveOperationException{
        Iterable<?> packets=(Iterable<?>)call(p,"subPackets");var children=new ArrayList<PacketPayload>();int bytes=0;
        for(Object child:packets){
            if(children.size()>=256||child.getClass().getSimpleName().equals("ClientboundBundlePacket"))throw new IllegalArgumentException("bundle count/nesting");
            PacketPayload value=PaperPacketNormalizer.normalize(child);bytes+=value.chargedBytes();if(bytes>65536)throw new IllegalArgumentException("bundle retained bytes");children.add(value);
        }
        return new PacketPayload.Bundle(children);
    }
}
