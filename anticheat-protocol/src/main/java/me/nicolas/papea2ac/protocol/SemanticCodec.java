package me.nicolas.papea2ac.protocol;

import java.io.*;
import java.util.*;
import me.nicolas.papea2ac.protocol.SemanticPayload.*;

/** Stable schema tags; exact primitive bits; explicitly bounded lists and identifiers. */
final class SemanticCodec {
    private SemanticCodec(){}
    private static void pos(DataOutputStream o,PacketPayload.Position p)throws IOException{o.writeDouble(p.x());o.writeDouble(p.y());o.writeDouble(p.z());}
    private static PacketPayload.Position pos(DataInputStream i)throws IOException{return new PacketPayload.Position(i.readDouble(),i.readDouble(),i.readDouble());}
    private static void rot(DataOutputStream o,PacketPayload.Rotation r)throws IOException{o.writeFloat(r.yaw());o.writeFloat(r.pitch());}
    private static PacketPayload.Rotation rot(DataInputStream i)throws IOException{return new PacketPayload.Rotation(i.readFloat(),i.readFloat());}
    private static void block(DataOutputStream o,BlockPosition p)throws IOException{o.writeInt(p.x());o.writeInt(p.y());o.writeInt(p.z());}
    private static BlockPosition block(DataInputStream i)throws IOException{return new BlockPosition(i.readInt(),i.readInt(),i.readInt());}
    private static boolean bool(DataInputStream i)throws IOException{int b=i.readUnsignedByte();if(b>1)throw new IOException("boolean");return b==1;}
    private static String text(DataInputStream i)throws IOException{return text(i,256);}
    private static String text(DataInputStream i,int limit)throws IOException{String s=i.readUTF();if(s.length()>limit)throw new IOException("identifier bound");return s;}
    private static int count(DataInputStream i,int max)throws IOException{int n=i.readInt();if(n<0||n>max)throw new IOException("semantic count");return n;}
    private static void relative(DataOutputStream o,Set<Relative> values)throws IOException{int bits=0;for(var v:values)bits|=1<<v.ordinal();o.writeInt(bits);}
    private static void item(DataOutputStream o,Item v)throws IOException{
        o.writeBoolean(v.empty());
        if(!v.empty()){
            o.writeUTF(v.type());o.writeInt(v.count());o.writeInt(v.damage());o.writeInt(v.maxDamage());o.writeBoolean(v.unbreakable());
            o.writeInt(v.enchantments().size());for(var e:v.enchantments()){o.writeUTF(e.id());o.writeInt(e.level());}
            o.writeInt(v.otherComponents().size());for(var k:v.otherComponents())o.writeUTF(k);
        }
    }
    private static void blockState(DataOutputStream o,BlockState v)throws IOException{
        o.writeUTF(v.block());o.writeInt(v.properties().size());for(var property:v.properties()){o.writeUTF(property.name());o.writeUTF(property.value());}
    }
    private static BlockState blockState(DataInputStream i)throws IOException{
        String key=text(i);int n=count(i,32);var properties=new ArrayList<Property>(n);
        for(int a=0;a<n;a++)properties.add(new Property(text(i),text(i)));
        return new BlockState(key,properties);
    }
    private static Item item(DataInputStream i)throws IOException{
        if(bool(i))return Item.EMPTY;
        String type=text(i);int count=i.readInt(),damage=i.readInt(),maxDamage=i.readInt();boolean unbreakable=bool(i);
        int en=count(i,32);var enchantments=new ArrayList<Enchant>(en);for(int a=0;a<en;a++)enchantments.add(new Enchant(text(i),i.readInt()));
        int oc=count(i,64);var other=new ArrayList<String>(oc);for(int a=0;a<oc;a++)other.add(text(i));
        return new Item(false,type,count,damage,maxDamage,unbreakable,enchantments,other);
    }
    private static Set<Relative> relative(DataInputStream i)throws IOException{
        int bits=i.readInt();if((bits&~511)!=0)throw new IOException("relative flags");var values=EnumSet.noneOf(Relative.class);
        for(var v:Relative.values())if((bits&(1<<v.ordinal()))!=0)values.add(v);return values;
    }
    static void write(DataOutputStream o,SemanticPayload p)throws IOException{
        switch(p){
            case Attack v->{o.writeByte(1);o.writeInt(v.entityId());}
            case Interact v->{o.writeByte(2);o.writeInt(v.entityId());o.writeUTF(v.hand().name());pos(o,v.location());o.writeBoolean(v.secondary());}
            case Dig v->{o.writeByte(3);o.writeUTF(v.action());block(o,v.block());o.writeUTF(v.face().name());o.writeInt(v.sequence());}
            case PlayerCommand v->{o.writeByte(4);o.writeInt(v.entityId());o.writeUTF(v.action());o.writeInt(v.data());}
            case UseItem v->{o.writeByte(5);o.writeUTF(v.hand().name());o.writeInt(v.sequence());rot(o,v.rotation());}
            case UseOn v->{o.writeByte(6);o.writeUTF(v.hand().name());o.writeInt(v.sequence());block(o,v.block());o.writeUTF(v.face().name());pos(o,v.hit());o.writeBoolean(v.inside());o.writeBoolean(v.borderHit());}
            case Correction v->{o.writeByte(7);o.writeInt(v.teleportId());pos(o,v.position());pos(o,v.delta());rot(o,v.rotation());relative(o,v.relative());}
            case RotationCorrection v->{o.writeByte(8);rot(o,v.rotation());o.writeBoolean(v.relativeYaw());o.writeBoolean(v.relativePitch());}
            case VehicleCorrection v->{o.writeByte(9);pos(o,v.position());rot(o,v.rotation());}
            case Abilities v->{o.writeByte(10);o.writeBoolean(v.invulnerable());o.writeBoolean(v.flying());o.writeBoolean(v.canFly());o.writeBoolean(v.instantBuild());o.writeFloat(v.flyingSpeed());o.writeFloat(v.walkingSpeed());}
            case FlightRequest v->{o.writeByte(11);o.writeBoolean(v.flying());}
            case Attributes v->{o.writeByte(12);o.writeInt(v.entityId());o.writeInt(v.values().size());for(var a:v.values()){
                o.writeUTF(a.id());o.writeDouble(a.base());o.writeInt(a.modifiers().size());for(var m:a.modifiers()){o.writeUTF(m.id());o.writeDouble(m.amount());o.writeUTF(m.operation());}}}
            case Effect v->{o.writeByte(13);o.writeInt(v.entityId());o.writeUTF(v.effect());o.writeInt(v.amplifier());o.writeInt(v.duration());o.writeBoolean(v.ambient());o.writeBoolean(v.visible());o.writeBoolean(v.icon());o.writeBoolean(v.blend());}
            case RemoveEffect v->{o.writeByte(14);o.writeInt(v.entityId());o.writeUTF(v.effect());}
            case EntityDelta v->{o.writeByte(15);o.writeInt(v.entityId());o.writeShort(v.dx());o.writeShort(v.dy());o.writeShort(v.dz());rot(o,v.rotation());o.writeBoolean(v.hasPosition());o.writeBoolean(v.hasRotation());o.writeBoolean(v.onGround());}
            case EntityPosition v->{o.writeByte(16);o.writeInt(v.entityId());pos(o,v.position());pos(o,v.delta());rot(o,v.rotation());relative(o,v.relative());o.writeBoolean(v.onGround());}
            case RemoveEntities v->{o.writeByte(17);o.writeInt(v.ids().size());for(int id:v.ids())o.writeInt(id);}
            case BlockChange v->{o.writeByte(18);block(o,v.position());o.writeUTF(v.state().block());o.writeInt(v.state().properties().size());for(var property:v.state().properties()){o.writeUTF(property.name());o.writeUTF(property.value());}}
            case ChunkUnload v->{o.writeByte(19);o.writeInt(v.x());o.writeInt(v.z());}
            case BlockEvent v->{o.writeByte(20);block(o,v.position());o.writeUTF(v.block());o.writeInt(v.action());o.writeInt(v.parameter());}
            case GameEvent v->{o.writeByte(21);o.writeInt(v.kind());o.writeFloat(v.value());}
            case SequenceAck v->{o.writeByte(22);o.writeInt(v.sequence());}
            case HeldSlot v->{o.writeByte(23);o.writeInt(v.slot());}
            case EntitySpawn v->{o.writeByte(24);o.writeInt(v.entityId());o.writeLong(v.uuid().getMostSignificantBits());o.writeLong(v.uuid().getLeastSignificantBits());o.writeUTF(v.type());pos(o,v.position());pos(o,v.delta());rot(o,v.rotation());o.writeFloat(v.headYaw());o.writeInt(v.data());}
            case BorderCenter v->{o.writeByte(25);o.writeDouble(v.x());o.writeDouble(v.z());}
            case BorderSize v->{o.writeByte(26);o.writeDouble(v.size());}
            case BorderLerp v->{o.writeByte(27);o.writeDouble(v.oldSize());o.writeDouble(v.newSize());o.writeLong(v.millis());}
            case BorderWarning v->{o.writeByte(28);o.writeBoolean(v.distance());o.writeInt(v.value());}
            case BorderInitialize v->{o.writeByte(29);o.writeDouble(v.x());o.writeDouble(v.z());o.writeDouble(v.oldSize());o.writeDouble(v.newSize());o.writeLong(v.millis());o.writeInt(v.maxSize());o.writeInt(v.warningBlocks());o.writeInt(v.warningTime());}
            case HeadRotation v->{o.writeByte(30);o.writeInt(v.entityId());o.writeFloat(v.yaw());}
            case EntityLink v->{o.writeByte(31);o.writeInt(v.source());o.writeInt(v.destination());}
            case Lifecycle v->{o.writeByte(32);o.writeUTF(v.transition().name());}
            case Disconnect v->{o.writeByte(33);o.writeUTF(v.reason());}
            case ClientSettings v->{o.writeByte(34);o.writeUTF(v.language());o.writeInt(v.viewDistance());o.writeUTF(v.mainHand());o.writeBoolean(v.allowsListing());}
            case Transfer v->{o.writeByte(35);o.writeUTF(v.host());o.writeInt(v.port());}
            case TagSync v->{o.writeByte(36);o.writeInt(v.registries().size());for(var r:v.registries())o.writeUTF(r);}
            case RegistrySync v->{o.writeByte(37);o.writeUTF(v.registry());o.writeInt(v.entryCount());}
            case EnabledFeatures v->{o.writeByte(38);o.writeInt(v.features().size());for(var f:v.features())o.writeUTF(f);}
            case KnownPacks v->{o.writeByte(39);o.writeInt(v.packs().size());for(var pack:v.packs())o.writeUTF(pack);}
            case CodeOfConduct v->{o.writeByte(40);o.writeUTF(v.text());}
            case ContainerClose v->{o.writeByte(41);o.writeInt(v.containerId());}
            case Cooldown v->{o.writeByte(42);o.writeUTF(v.group());o.writeInt(v.duration());}
            case ProjectilePower v->{o.writeByte(43);o.writeInt(v.entityId());o.writeDouble(v.power());}
            case GameModeChange v->{o.writeByte(44);o.writeUTF(v.mode());}
            case ClientCommand v->{o.writeByte(45);o.writeUTF(v.action());}
            case ChunkBatchAck v->{o.writeByte(46);o.writeFloat(v.desiredChunksPerTick());}
            case ChunkCacheCenter v->{o.writeByte(47);o.writeInt(v.x());o.writeInt(v.z());}
            case ChunkCacheRadius v->{o.writeByte(48);o.writeInt(v.radius());}
            case SimulationDistance v->{o.writeByte(49);o.writeInt(v.distance());}
            case OpenScreen v->{o.writeByte(50);o.writeInt(v.containerId());o.writeUTF(v.menuType());o.writeUTF(v.title());}
            case CombatKill v->{o.writeByte(52);o.writeInt(v.playerId());o.writeUTF(v.message());}
            case Handshake v->{o.writeByte(53);o.writeInt(v.protocolVersion());o.writeUTF(v.intent());}
            case LoginHello v->{o.writeByte(54);o.writeUTF(v.claimedName());o.writeLong(v.claimedProfileId().getMostSignificantBits());o.writeLong(v.claimedProfileId().getLeastSignificantBits());}
            case LoginPluginQuery v->{o.writeByte(55);o.writeInt(v.transactionId());o.writeUTF(v.channel());}
            case LoginPluginQueryResponse v->{o.writeByte(56);o.writeInt(v.transactionId());o.writeBoolean(v.understood());}
            case LoginCompression v->{o.writeByte(57);o.writeInt(v.threshold());}
            case LoginFinished v->{o.writeByte(58);o.writeLong(v.profileId().getMostSignificantBits());o.writeLong(v.profileId().getLeastSignificantBits());o.writeUTF(v.profileName());}
            case JoinGame v->{o.writeByte(59);o.writeInt(v.playerId());o.writeBoolean(v.hardcore());o.writeUTF(v.dimension());o.writeInt(v.maxPlayers());o.writeInt(v.chunkRadius());o.writeInt(v.simulationDistance());o.writeUTF(v.gameType());o.writeBoolean(v.onlineMode());o.writeBoolean(v.enforcesSecureChat());}
            case ContainerContent v->{o.writeByte(60);o.writeInt(v.containerId());o.writeInt(v.stateId());o.writeInt(v.items().size());for(var it:v.items())item(o,it);item(o,v.carried());}
            case ContainerSlot v->{o.writeByte(61);o.writeInt(v.containerId());o.writeInt(v.stateId());o.writeInt(v.slot());item(o,v.item());}
            case CursorItem v->{o.writeByte(62);item(o,v.item());}
            case Equipment v->{o.writeByte(63);o.writeInt(v.entityId());o.writeInt(v.entries().size());for(var e:v.entries()){o.writeUTF(e.slot());item(o,e.item());}}
            case PlayerInventorySlot v->{o.writeByte(64);o.writeInt(v.slot());item(o,v.item());}
            case ContainerDataProperty v->{o.writeByte(65);o.writeInt(v.containerId());o.writeInt(v.propertyId());o.writeInt(v.value());}
            case ContainerButtonClick v->{o.writeByte(66);o.writeInt(v.containerId());o.writeInt(v.buttonId());}
            case ContainerSlotStateChanged v->{o.writeByte(67);o.writeInt(v.containerId());o.writeInt(v.slotId());o.writeBoolean(v.state());}
            case ContainerClickClaim v->{o.writeByte(68);o.writeInt(v.containerId());o.writeInt(v.stateId());o.writeInt(v.slot());o.writeInt(v.button());o.writeUTF(v.input());
                o.writeInt(v.changedSlots().size());for(var c:v.changedSlots()){o.writeInt(c.slot());o.writeBoolean(c.empty());}o.writeBoolean(v.carriedEmpty());}
            case DamageEvent v->{o.writeByte(69);o.writeInt(v.entityId());o.writeUTF(v.sourceType());o.writeInt(v.sourceCauseId());o.writeInt(v.sourceDirectId());
                o.writeBoolean(v.hasPosition());if(v.hasPosition()){o.writeDouble(v.x());o.writeDouble(v.y());o.writeDouble(v.z());}}
            case EntitySimpleEvent v->{o.writeByte(70);o.writeInt(v.entityId());o.writeByte(v.eventId());}
            case EntitySyncedData v->{o.writeByte(71);o.writeInt(v.entityId());o.writeInt(v.fields().size());for(var f:v.fields()){o.writeInt(f.id());o.writeUTF(f.kind());o.writeUTF(f.value());}}
            case LookAt v->{o.writeByte(72);o.writeUTF(v.fromAnchor());o.writeDouble(v.x());o.writeDouble(v.y());o.writeDouble(v.z());o.writeInt(v.entityId());o.writeUTF(v.toAnchor());o.writeBoolean(v.atEntity());}
            case MinecartMove v->{o.writeByte(73);o.writeInt(v.entityId());o.writeInt(v.steps().size());for(var s:v.steps()){o.writeDouble(s.x());o.writeDouble(s.y());o.writeDouble(s.z());o.writeFloat(s.yaw());o.writeFloat(s.pitch());}}
            case Respawn v->{o.writeByte(74);o.writeUTF(v.dimension());o.writeByte(v.keepFlags());}
            case BlockEntityObservation v->{o.writeByte(75);block(o,v.position());o.writeUTF(v.type());o.writeInt(v.encodedTagBytes());}
            case ChunksBiomes v->{o.writeByte(76);o.writeInt(v.chunks().size());for(var c:v.chunks()){o.writeInt(c.chunkX());o.writeInt(c.chunkZ());o.writeInt(c.encodedBytes());}}
            case SectionBlocksUpdate v->{o.writeByte(77);o.writeInt(v.sectionX());o.writeInt(v.sectionY());o.writeInt(v.sectionZ());
                o.writeInt(v.updates().size());for(var u:v.updates()){block(o,u.position());blockState(o,u.state());}}
            case LightUpdate v->{o.writeByte(78);o.writeInt(v.chunkX());o.writeInt(v.chunkZ());o.writeInt(v.skySectionCount());o.writeInt(v.blockSectionCount());o.writeInt(v.encodedBytes());}
            case ChunkBaseline v->{o.writeByte(79);o.writeInt(v.chunkX());o.writeInt(v.chunkZ());o.writeInt(v.encodedBlockBytes());o.writeInt(v.blockEntityCount());o.writeInt(v.heightmapCount());o.writeBoolean(v.hasLight());}
        }
    }
    static SemanticPayload read(DataInputStream i)throws IOException{
        return switch(i.readUnsignedByte()){
            case 1->new Attack(i.readInt());
            case 2->new Interact(i.readInt(),Hand.valueOf(text(i)),pos(i),bool(i));
            case 3->new Dig(text(i),block(i),Face.valueOf(text(i)),i.readInt());
            case 4->new PlayerCommand(i.readInt(),text(i),i.readInt());
            case 5->new UseItem(Hand.valueOf(text(i)),i.readInt(),rot(i));
            case 6->new UseOn(Hand.valueOf(text(i)),i.readInt(),block(i),Face.valueOf(text(i)),pos(i),bool(i),bool(i));
            case 7->new Correction(i.readInt(),pos(i),pos(i),rot(i),relative(i));
            case 8->new RotationCorrection(rot(i),bool(i),bool(i));
            case 9->new VehicleCorrection(pos(i),rot(i));
            case 10->new Abilities(bool(i),bool(i),bool(i),bool(i),i.readFloat(),i.readFloat());
            case 11->new FlightRequest(bool(i));
            case 12->{int id=i.readInt(),n=count(i,64),total=0;var values=new ArrayList<Attribute>(n);for(int a=0;a<n;a++){
                String key=text(i);double base=i.readDouble();int m=count(i,32);total+=m;if(total>128)throw new IOException("modifier total");
                var modifiers=new ArrayList<Modifier>(m);for(int b=0;b<m;b++)modifiers.add(new Modifier(text(i),i.readDouble(),text(i)));
                values.add(new Attribute(key,base,modifiers));}yield new Attributes(id,values);}
            case 13->new Effect(i.readInt(),text(i),i.readInt(),i.readInt(),bool(i),bool(i),bool(i),bool(i));
            case 14->new RemoveEffect(i.readInt(),text(i));
            case 15->new EntityDelta(i.readInt(),i.readShort(),i.readShort(),i.readShort(),rot(i),bool(i),bool(i),bool(i));
            case 16->new EntityPosition(i.readInt(),pos(i),pos(i),rot(i),relative(i),bool(i));
            case 17->{int n=count(i,1024);var ids=new ArrayList<Integer>(n);for(int a=0;a<n;a++)ids.add(i.readInt());yield new RemoveEntities(ids);}
            case 18->{BlockPosition block=block(i);String key=text(i);int n=count(i,32);var properties=new ArrayList<Property>(n);
                for(int a=0;a<n;a++)properties.add(new Property(text(i),text(i)));yield new BlockChange(block,new BlockState(key,properties));}
            case 19->new ChunkUnload(i.readInt(),i.readInt());
            case 20->new BlockEvent(block(i),text(i),i.readInt(),i.readInt());
            case 21->new GameEvent(i.readInt(),i.readFloat());
            case 22->new SequenceAck(i.readInt());
            case 23->new HeldSlot(i.readInt());
            case 24->new EntitySpawn(i.readInt(),new UUID(i.readLong(),i.readLong()),text(i),pos(i),pos(i),rot(i),i.readFloat(),i.readInt());
            case 25->new BorderCenter(i.readDouble(),i.readDouble());
            case 26->new BorderSize(i.readDouble());
            case 27->new BorderLerp(i.readDouble(),i.readDouble(),i.readLong());
            case 28->new BorderWarning(bool(i),i.readInt());
            case 29->new BorderInitialize(i.readDouble(),i.readDouble(),i.readDouble(),i.readDouble(),i.readLong(),i.readInt(),i.readInt(),i.readInt());
            case 30->new HeadRotation(i.readInt(),i.readFloat());
            case 31->new EntityLink(i.readInt(),i.readInt());
            case 32->new Lifecycle(LifecycleTransition.valueOf(text(i)));
            case 33->new Disconnect(text(i));
            case 34->new ClientSettings(text(i),i.readInt(),text(i),bool(i));
            case 35->new Transfer(text(i),i.readInt());
            case 36->{int n=count(i,64);var registries=new ArrayList<String>(n);for(int a=0;a<n;a++)registries.add(text(i));yield new TagSync(registries);}
            case 37->new RegistrySync(text(i),i.readInt());
            case 38->{int n=count(i,64);var features=new ArrayList<String>(n);for(int a=0;a<n;a++)features.add(text(i));yield new EnabledFeatures(features);}
            case 39->{int n=count(i,64);var packs=new ArrayList<String>(n);for(int a=0;a<n;a++)packs.add(text(i));yield new KnownPacks(packs);}
            case 40->new CodeOfConduct(text(i,4096));
            case 41->new ContainerClose(i.readInt());
            case 42->new Cooldown(text(i),i.readInt());
            case 43->new ProjectilePower(i.readInt(),i.readDouble());
            case 44->new GameModeChange(text(i));
            case 45->new ClientCommand(text(i));
            case 46->new ChunkBatchAck(i.readFloat());
            case 47->new ChunkCacheCenter(i.readInt(),i.readInt());
            case 48->new ChunkCacheRadius(i.readInt());
            case 49->new SimulationDistance(i.readInt());
            case 50->new OpenScreen(i.readInt(),text(i),text(i,256));
            case 52->new CombatKill(i.readInt(),text(i,256));
            case 53->new Handshake(i.readInt(),text(i));
            case 54->new LoginHello(text(i),new UUID(i.readLong(),i.readLong()));
            case 55->new LoginPluginQuery(i.readInt(),text(i));
            case 56->new LoginPluginQueryResponse(i.readInt(),bool(i));
            case 57->new LoginCompression(i.readInt());
            case 58->new LoginFinished(new UUID(i.readLong(),i.readLong()),text(i));
            case 59->new JoinGame(i.readInt(),bool(i),text(i),i.readInt(),i.readInt(),i.readInt(),text(i),bool(i),bool(i));
            case 60->{int cid=i.readInt(),sid=i.readInt(),n=count(i,256);var items=new ArrayList<Item>(n);for(int a=0;a<n;a++)items.add(item(i));yield new ContainerContent(cid,sid,items,item(i));}
            case 61->new ContainerSlot(i.readInt(),i.readInt(),i.readInt(),item(i));
            case 62->new CursorItem(item(i));
            case 63->{int id=i.readInt(),n=count(i,16);var entries=new ArrayList<EquipmentEntry>(n);for(int a=0;a<n;a++)entries.add(new EquipmentEntry(text(i),item(i)));yield new Equipment(id,entries);}
            case 64->new PlayerInventorySlot(i.readInt(),item(i));
            case 65->new ContainerDataProperty(i.readInt(),i.readInt(),i.readInt());
            case 66->new ContainerButtonClick(i.readInt(),i.readInt());
            case 67->new ContainerSlotStateChanged(i.readInt(),i.readInt(),bool(i));
            case 68->{int cid=i.readInt(),sid=i.readInt(),slot=i.readInt(),button=i.readInt();String input=text(i);
                int n=count(i,128);var changed=new ArrayList<ChangedSlot>(n);for(int a=0;a<n;a++)changed.add(new ChangedSlot(i.readInt(),bool(i)));
                yield new ContainerClickClaim(cid,sid,slot,button,input,changed,bool(i));}
            case 69->{int id=i.readInt();String type=text(i);int cause=i.readInt(),direct=i.readInt();boolean has=bool(i);
                yield new DamageEvent(id,type,cause,direct,has,has?i.readDouble():0,has?i.readDouble():0,has?i.readDouble():0);}
            case 70->new EntitySimpleEvent(i.readInt(),i.readByte());
            case 71->{int id=i.readInt(),n=count(i,64);var fields=new ArrayList<EntityDataField>(n);for(int a=0;a<n;a++)fields.add(new EntityDataField(i.readInt(),text(i),text(i,256)));yield new EntitySyncedData(id,fields);}
            case 72->new LookAt(text(i),i.readDouble(),i.readDouble(),i.readDouble(),i.readInt(),text(i),bool(i));
            case 73->{int id=i.readInt(),n=count(i,256);var steps=new ArrayList<MinecartStepFact>(n);for(int a=0;a<n;a++)steps.add(new MinecartStepFact(i.readDouble(),i.readDouble(),i.readDouble(),i.readFloat(),i.readFloat()));yield new MinecartMove(id,steps);}
            case 74->new Respawn(text(i),i.readByte());
            case 75->new BlockEntityObservation(block(i),text(i),i.readInt());
            case 76->{int n=count(i,256);var chunks=new ArrayList<ChunkBiomeObservation>(n);for(int a=0;a<n;a++)chunks.add(new ChunkBiomeObservation(i.readInt(),i.readInt(),i.readInt()));yield new ChunksBiomes(chunks);}
            case 77->{int sx=i.readInt(),sy=i.readInt(),sz=i.readInt(),n=count(i,4096);var updates=new ArrayList<BlockUpdate>(n);for(int a=0;a<n;a++)updates.add(new BlockUpdate(block(i),blockState(i)));yield new SectionBlocksUpdate(sx,sy,sz,updates);}
            case 78->new LightUpdate(i.readInt(),i.readInt(),i.readInt(),i.readInt(),i.readInt());
            case 79->new ChunkBaseline(i.readInt(),i.readInt(),i.readInt(),i.readInt(),i.readInt(),bool(i));
            default->throw new IOException("unknown semantic tag");
        };
    }
}
