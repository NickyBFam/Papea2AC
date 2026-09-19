package me.nicolas.papea2ac.protocol;

import java.util.*;
import me.nicolas.papea2ac.protocol.PacketPayload.Position;
import me.nicolas.papea2ac.protocol.PacketPayload.Rotation;

/** Typed packet facts only; no interpretation as movement, combat, or world validation. */
public sealed interface SemanticPayload extends PacketPayload {
    static String tag(String value){
        Objects.requireNonNull(value);if(value.isEmpty()||value.length()>256)throw new IllegalArgumentException("semantic identifier");return value;
    }
    static void entity(int id){if(id<0)throw new IllegalArgumentException("entity id");}
    static void finite(double value){if(!Double.isFinite(value))throw new IllegalArgumentException("nonfinite semantic number");}
    static <T> List<T> bounded(List<T> values,int count){if(values.size()>count)throw new IllegalArgumentException("semantic count");return List.copyOf(values);}
    /** Vertical range must be section-aligned and inside the protocol's representable world height (DimensionType codec: minY multiple of 16 >= -2032, height multiple of 16 in 16..4064, minY+height <= 2032).
     *  (0,0) is the explicit NOT-OBSERVED sentinel: payload-schema-1 (Phase 1/2) JoinGame/Respawn records carried no range and decode to it (P3-C1 migration); the live normalizer always supplies the real range. */
    static void dimensionRange(int minY,int height){
        if(minY==0&&height==0)return;
        if(minY%16!=0||height%16!=0||height<16||height>4064||minY< -2032||minY+height>2032)throw new IllegalArgumentException("dimension range");
    }
    enum Hand { MAIN_HAND, OFF_HAND }
    enum Face { DOWN, UP, NORTH, SOUTH, WEST, EAST }
    enum Relative { X,Y,Z,Y_ROT,X_ROT,DELTA_X,DELTA_Y,DELTA_Z,ROTATE_DELTA }
    record BlockPosition(int x,int y,int z){}
    record Attack(int entityId) implements SemanticPayload {
        public Attack{entity(entityId);}public int chargedBytes(){return 64;}
    }
    record Interact(int entityId,Hand hand,Position location,boolean secondary) implements SemanticPayload {
        public Interact{entity(entityId);Objects.requireNonNull(hand);Objects.requireNonNull(location);}public int chargedBytes(){return 128;}
    }
    record Dig(String action,BlockPosition block,Face face,int sequence) implements SemanticPayload {
        public Dig{tag(action);Objects.requireNonNull(block);Objects.requireNonNull(face);}public int chargedBytes(){return 768;}
    }
    record PlayerCommand(int entityId,String action,int data) implements SemanticPayload {
        public PlayerCommand{entity(entityId);tag(action);}public int chargedBytes(){return 640;}
    }
    record UseItem(Hand hand,int sequence,Rotation rotation) implements SemanticPayload {
        public UseItem{Objects.requireNonNull(hand);Objects.requireNonNull(rotation);}public int chargedBytes(){return 128;}
    }
    record UseOn(Hand hand,int sequence,BlockPosition block,Face face,Position hit,boolean inside,boolean borderHit) implements SemanticPayload {
        public UseOn{Objects.requireNonNull(hand);Objects.requireNonNull(block);Objects.requireNonNull(face);Objects.requireNonNull(hit);}public int chargedBytes(){return 192;}
    }
    record Correction(int teleportId,Position position,Position delta,Rotation rotation,Set<Relative> relative) implements SemanticPayload {
        public Correction{Objects.requireNonNull(position);Objects.requireNonNull(delta);Objects.requireNonNull(rotation);relative=Set.copyOf(relative);}public int chargedBytes(){return 512;}
    }
    record RotationCorrection(Rotation rotation,boolean relativeYaw,boolean relativePitch) implements SemanticPayload {
        public RotationCorrection{Objects.requireNonNull(rotation);}public int chargedBytes(){return 128;}
    }
    record VehicleCorrection(Position position,Rotation rotation) implements SemanticPayload {
        public VehicleCorrection{Objects.requireNonNull(position);Objects.requireNonNull(rotation);}public int chargedBytes(){return 160;}
    }
    record Abilities(boolean invulnerable,boolean flying,boolean canFly,boolean instantBuild,float flyingSpeed,float walkingSpeed) implements SemanticPayload {
        public Abilities{finite(flyingSpeed);finite(walkingSpeed);}public int chargedBytes(){return 96;}
    }
    record FlightRequest(boolean flying) implements SemanticPayload {public int chargedBytes(){return 64;}}
    record Modifier(String id,double amount,String operation){public Modifier{tag(id);tag(operation);finite(amount);}}
    record Attribute(String id,double base,List<Modifier> modifiers){public Attribute{tag(id);finite(base);modifiers=bounded(modifiers,32);}}
    record Attributes(int entityId,List<Attribute> values) implements SemanticPayload {
        public Attributes{entity(entityId);values=bounded(values,64);if(values.stream().mapToInt(a->a.modifiers().size()).sum()>128)throw new IllegalArgumentException("modifier total");if(128+values.stream().mapToInt(a->640+1152*a.modifiers().size()).sum()>65536)throw new IllegalArgumentException("attribute retained bytes");}
        public int chargedBytes(){return 128+values.stream().mapToInt(a->640+1152*a.modifiers().size()).sum();}
    }
    record Effect(int entityId,String effect,int amplifier,int duration,boolean ambient,boolean visible,boolean icon,boolean blend) implements SemanticPayload {
        public Effect{entity(entityId);tag(effect);}public int chargedBytes(){return 640;}
    }
    record RemoveEffect(int entityId,String effect) implements SemanticPayload {
        public RemoveEffect{entity(entityId);tag(effect);}public int chargedBytes(){return 640;}
    }
    /** Preserve encoded relative deltas, not a guessed absolute entity position. */
    record EntityDelta(int entityId,short dx,short dy,short dz,Rotation rotation,boolean hasPosition,boolean hasRotation,boolean onGround) implements SemanticPayload {
        public EntityDelta{entity(entityId);Objects.requireNonNull(rotation);}public int chargedBytes(){return 160;}
    }
    record EntityPosition(int entityId,Position position,Position delta,Rotation rotation,Set<Relative> relative,boolean onGround) implements SemanticPayload {
        public EntityPosition{entity(entityId);Objects.requireNonNull(position);Objects.requireNonNull(delta);Objects.requireNonNull(rotation);relative=Set.copyOf(relative);}public int chargedBytes(){return 512;}
    }
    record RemoveEntities(List<Integer> ids) implements SemanticPayload {
        public RemoveEntities{ids=bounded(ids,1024);ids.forEach(SemanticPayload::entity);}public int chargedBytes(){return 128+32*ids.size();}
    }
    record Property(String name,String value){public Property{tag(name);tag(value);}
        /** Retained estimate from the actual strings (record + two String objects with their char arrays), not the 256-char worst case. */
        public int chargedBytes(){return 48+2*(name.length()+value.length());}}
    record BlockState(String block,List<Property> properties){
        public BlockState{tag(block);properties=bounded(properties,32);properties=properties.stream().sorted(Comparator.comparing(Property::name)).toList();
            if(properties.stream().map(Property::name).distinct().count()!=properties.size())throw new IllegalArgumentException("duplicate block property");}
        /** P3-F9 (real-client qualification): the former worst-case charge (640 + 1152 per property, i.e. 256-char names/values) made a multi-block update with a
         *  few dozen stair/fence states weigh hundreds of KiB, saturating the ingress arena and the work quota under ordinary terrain traffic. The charge is now
         *  the retained estimate from the actual strings: record + block String + property list, still a bound on what is really retained. */
        public int chargedBytes(){int b=96+2*block.length();for(var p:properties)b+=16+p.chargedBytes();return b;}
    }
    record BlockChange(BlockPosition position,BlockState state) implements SemanticPayload {
        public BlockChange{Objects.requireNonNull(position);Objects.requireNonNull(state);}public int chargedBytes(){return 128+state.chargedBytes();}
    }
    record ChunkUnload(int x,int z) implements SemanticPayload {public int chargedBytes(){return 96;}}
    record BlockEvent(BlockPosition position,String block,int action,int parameter) implements SemanticPayload {
        public BlockEvent{Objects.requireNonNull(position);tag(block);}public int chargedBytes(){return 640;}
    }
    record GameEvent(int kind,float value) implements SemanticPayload {
        public GameEvent{if(kind<0||kind>255)throw new IllegalArgumentException("event kind");finite(value);}public int chargedBytes(){return 96;}
    }
    record SequenceAck(int sequence) implements SemanticPayload {public int chargedBytes(){return 64;}}
    record HeldSlot(int slot) implements SemanticPayload {public int chargedBytes(){return 64;}}
    record EntitySpawn(int entityId,UUID uuid,String type,Position position,Position delta,Rotation rotation,float headYaw,int data) implements SemanticPayload {
        public EntitySpawn{entity(entityId);Objects.requireNonNull(uuid);tag(type);Objects.requireNonNull(position);Objects.requireNonNull(delta);Objects.requireNonNull(rotation);finite(headYaw);}public int chargedBytes(){return 832;}
    }
    record BorderCenter(double x,double z) implements SemanticPayload {
        public BorderCenter{finite(x);finite(z);}public int chargedBytes(){return 96;}
    }
    record BorderSize(double size) implements SemanticPayload {
        public BorderSize{finite(size);}public int chargedBytes(){return 80;}
    }
    record BorderLerp(double oldSize,double newSize,long millis) implements SemanticPayload {
        public BorderLerp{finite(oldSize);finite(newSize);if(millis<0)throw new IllegalArgumentException("border time");}public int chargedBytes(){return 112;}
    }
    record BorderWarning(boolean distance,int value) implements SemanticPayload {public int chargedBytes(){return 80;}}
    record BorderInitialize(double x,double z,double oldSize,double newSize,long millis,int maxSize,int warningBlocks,int warningTime) implements SemanticPayload {
        public BorderInitialize{finite(x);finite(z);finite(oldSize);finite(newSize);if(millis<0)throw new IllegalArgumentException("border time");}public int chargedBytes(){return 160;}
    }
    record HeadRotation(int entityId,float yaw) implements SemanticPayload {
        public HeadRotation{entity(entityId);finite(yaw);}public int chargedBytes(){return 80;}
    }
    record EntityLink(int source,int destination) implements SemanticPayload {public int chargedBytes(){return 80;}}
    /** Session/protocol epoch identity only; shared class instances are captured whichever protocol state they recur in. */
    enum LifecycleTransition { CONFIGURATION_FINISH, RESET_CHAT, ACCEPT_CODE_OF_CONDUCT, PLAY_TO_CONFIGURATION, PLAY_TO_CONFIGURATION_ACK, PLAYER_LOADED, LOGIN_ENCRYPTION_REQUEST, LOGIN_KEY_RESPONSE, LOGIN_ACKNOWLEDGED, CHUNK_BATCH_START, BUNDLE_DELIMITER }
    record Lifecycle(LifecycleTransition transition) implements SemanticPayload {
        public Lifecycle{Objects.requireNonNull(transition);}public int chargedBytes(){return 64;}
    }
    record Disconnect(String reason) implements SemanticPayload {
        public Disconnect{Objects.requireNonNull(reason);if(reason.length()>256)throw new IllegalArgumentException("disconnect reason bound");}
        public int chargedBytes(){return 128+2*reason.length();}
    }
    record ClientSettings(String language,int viewDistance,String mainHand,boolean allowsListing) implements SemanticPayload {
        public ClientSettings{tag(language);tag(mainHand);if(viewDistance<0||viewDistance>32)throw new IllegalArgumentException("view distance");}
        public int chargedBytes(){return 256;}
    }
    record Transfer(String host,int port) implements SemanticPayload {
        public Transfer{tag(host);if(port<0||port>65535)throw new IllegalArgumentException("transfer port");}
        public int chargedBytes(){return 256;}
    }
    record TagSync(List<String> registries) implements SemanticPayload {
        public TagSync{registries=bounded(registries.stream().sorted().toList(),64);registries.forEach(SemanticPayload::tag);}
        public int chargedBytes(){return 128+64*registries.size();}
    }
    record RegistrySync(String registry,int entryCount) implements SemanticPayload {
        public RegistrySync{tag(registry);if(entryCount<0||entryCount>65536)throw new IllegalArgumentException("registry entry count");}
        public int chargedBytes(){return 128;}
    }
    record EnabledFeatures(List<String> features) implements SemanticPayload {
        public EnabledFeatures{features=bounded(features.stream().sorted().toList(),64);features.forEach(SemanticPayload::tag);}
        public int chargedBytes(){return 128+64*features.size();}
    }
    record KnownPacks(List<String> packs) implements SemanticPayload {
        public KnownPacks{packs=bounded(packs,64);packs.forEach(SemanticPayload::tag);}
        public int chargedBytes(){return 128+80*packs.size();}
    }
    record CodeOfConduct(String text) implements SemanticPayload {
        public CodeOfConduct{Objects.requireNonNull(text);if(text.length()>4096)throw new IllegalArgumentException("code of conduct bound");}
        public int chargedBytes(){return 128+2*text.length();}
    }
    record ContainerClose(int containerId) implements SemanticPayload {
        public ContainerClose{if(containerId<0||containerId>255)throw new IllegalArgumentException("container id");}
        public int chargedBytes(){return 64;}
    }
    record Cooldown(String group,int duration) implements SemanticPayload {
        public Cooldown{tag(group);if(duration<0)throw new IllegalArgumentException("cooldown duration");}
        public int chargedBytes(){return 128;}
    }
    record ProjectilePower(int entityId,double power) implements SemanticPayload {
        public ProjectilePower{entity(entityId);finite(power);}public int chargedBytes(){return 96;}
    }
    record GameModeChange(String mode) implements SemanticPayload {
        public GameModeChange{tag(mode);}public int chargedBytes(){return 80;}
    }
    record ClientCommand(String action) implements SemanticPayload {
        public ClientCommand{tag(action);}public int chargedBytes(){return 80;}
    }
    record ChunkBatchAck(float desiredChunksPerTick) implements SemanticPayload {
        public ChunkBatchAck{finite(desiredChunksPerTick);}public int chargedBytes(){return 64;}
    }
    /** Client render-distance bookkeeping only; no chunk/block content. */
    record ChunkCacheCenter(int x,int z) implements SemanticPayload {public int chargedBytes(){return 80;}}
    record ChunkCacheRadius(int radius) implements SemanticPayload {
        public ChunkCacheRadius{if(radius<0||radius>32)throw new IllegalArgumentException("chunk cache radius");}public int chargedBytes(){return 64;}
    }
    record SimulationDistance(int distance) implements SemanticPayload {
        public SimulationDistance{if(distance<0||distance>32)throw new IllegalArgumentException("simulation distance");}public int chargedBytes(){return 64;}
    }
    record OpenScreen(int containerId,String menuType,String title) implements SemanticPayload {
        public OpenScreen{if(containerId<0||containerId>255)throw new IllegalArgumentException("container id");tag(menuType);Objects.requireNonNull(title);if(title.length()>256)throw new IllegalArgumentException("open screen title bound");}
        public int chargedBytes(){return 256+2*title.length();}
    }
    record CombatKill(int playerId,String message) implements SemanticPayload {
        public CombatKill{entity(playerId);Objects.requireNonNull(message);if(message.length()>256)throw new IllegalArgumentException("combat kill message bound");}
        public int chargedBytes(){return 128+2*message.length();}
    }
    /** Protocol/version negotiation only; not yet any session or player identity. */
    record Handshake(int protocolVersion,String intent) implements SemanticPayload {
        public Handshake{tag(intent);}public int chargedBytes(){return 96;}
    }
    /** Client-CLAIMED identity only. Never treated as trusted/authenticated player identity. */
    record LoginHello(String claimedName,UUID claimedProfileId) implements SemanticPayload {
        public LoginHello{tag(claimedName);Objects.requireNonNull(claimedProfileId);}public int chargedBytes(){return 128;}
    }
    record LoginPluginQuery(int transactionId,String channel) implements SemanticPayload {
        public LoginPluginQuery{tag(channel);}public int chargedBytes(){return 128;}
    }
    record LoginPluginQueryResponse(int transactionId,boolean understood) implements SemanticPayload {
        public int chargedBytes(){return 64;}
    }
    record LoginCompression(int threshold) implements SemanticPayload {
        public int chargedBytes(){return 64;}
    }
    /** Server-committed profile at the moment LOGIN finishes; distinct from the client's earlier claim. */
    record LoginFinished(UUID profileId,String profileName) implements SemanticPayload {
        public LoginFinished{Objects.requireNonNull(profileId);tag(profileName);}public int chargedBytes(){return 128;}
    }
    /** Join-game baseline: identity/coarse world facts. Of the DimensionType holder only the vertical range is read (P3-E1: dimensionMinY/dimensionHeight place chunk sections at absolute Y without hard-coding legacy 0..255); no other dimension-type content crosses. */
    record JoinGame(int playerId,boolean hardcore,String dimension,int maxPlayers,int chunkRadius,int simulationDistance,String gameType,boolean onlineMode,boolean enforcesSecureChat,
                    int dimensionMinY,int dimensionHeight) implements SemanticPayload {
        public JoinGame{entity(playerId);tag(dimension);tag(gameType);dimensionRange(dimensionMinY,dimensionHeight);}public int chargedBytes(){return 256;}
        /** False only for payload-schema-1 legacy records: the world model then reports DIMENSION_UNKNOWN instead of guessing a range. */
        public boolean hasDimensionRange(){return dimensionHeight!=0;}
    }

    // ---- Item / inventory model ----
    /** One enchantment entry; sorted by id at construction so equality/replay never depends on native iteration order. */
    record Enchant(String id,int level){public Enchant{tag(id);}}
    /**
     * Normalized item snapshot. Deliberately not a clone of every data component: known-relevant fields
     * (durability, unbreakable, enchantments) are typed; everything else present is recorded only as a
     * bounded, sorted list of registry keys in {@code otherComponents} - visible so coverage is never
     * silently claimed complete, but not deep-parsed, since Phase 0 (PLAYER_STATE.md InventoryState row)
     * asks only for "relevant items/components," not an exhaustive component clone.
     */
    record Item(boolean empty,String type,int count,int damage,int maxDamage,boolean unbreakable,List<Enchant> enchantments,List<String> otherComponents) {
        public static final Item EMPTY=new Item(true,"",0,0,0,false,List.of(),List.of());
        public Item {
            if(!empty){tag(type);if(count<0||count>99)throw new IllegalArgumentException("item count");if(damage<0||maxDamage<0)throw new IllegalArgumentException("item damage");}
            enchantments=bounded(enchantments.stream().sorted(Comparator.comparing(Enchant::id)).toList(),32);
            otherComponents=bounded(otherComponents.stream().distinct().sorted().toList(),64);
            otherComponents.forEach(SemanticPayload::tag);
        }
        public int chargedBytes(){return 96+40*enchantments.size()+48*otherComponents.size();}
    }
    record ContainerContent(int containerId,int stateId,List<Item> items,Item carried) implements SemanticPayload {
        public ContainerContent{if(containerId<0||containerId>255)throw new IllegalArgumentException("container id");items=bounded(items,256);Objects.requireNonNull(carried);}
        public int chargedBytes(){return 128+carried.chargedBytes()+items.stream().mapToInt(Item::chargedBytes).sum();}
    }
    record ContainerSlot(int containerId,int stateId,int slot,Item item) implements SemanticPayload {
        public ContainerSlot{if(containerId< -1||containerId>255)throw new IllegalArgumentException("container id");Objects.requireNonNull(item);}
        public int chargedBytes(){return 96+item.chargedBytes();}
    }
    record CursorItem(Item item) implements SemanticPayload {
        public CursorItem{Objects.requireNonNull(item);}public int chargedBytes(){return 64+item.chargedBytes();}
    }
    record EquipmentEntry(String slot,Item item){public EquipmentEntry{tag(slot);Objects.requireNonNull(item);}}
    record Equipment(int entityId,List<EquipmentEntry> entries) implements SemanticPayload {
        public Equipment{entity(entityId);entries=bounded(entries,16);}
        public int chargedBytes(){return 96+entries.stream().mapToInt(e->32+e.item().chargedBytes()).sum();}
    }
    record PlayerInventorySlot(int slot,Item item) implements SemanticPayload {
        public PlayerInventorySlot{Objects.requireNonNull(item);}public int chargedBytes(){return 64+item.chargedBytes();}
    }
    record ContainerDataProperty(int containerId,int propertyId,int value) implements SemanticPayload {
        public int chargedBytes(){return 64;}
    }
    record ContainerButtonClick(int containerId,int buttonId) implements SemanticPayload {
        public int chargedBytes(){return 64;}
    }
    record ContainerSlotStateChanged(int containerId,int slotId,boolean state) implements SemanticPayload {
        public int chargedBytes(){return 64;}
    }
    /**
     * The client sends a bandwidth/verification hash of each changed slot, not the item itself
     * (verified: the native hashed-stack accessor exposes only an equality/match check, no accessor
     * for its content). The only facts genuinely on the wire are which slots changed and whether
     * each one is empty (comparable against a well-known empty singleton).
     */
    record ChangedSlot(int slot,boolean empty){}
    record ContainerClickClaim(int containerId,int stateId,int slot,int button,String input,List<ChangedSlot> changedSlots,boolean carriedEmpty) implements SemanticPayload {
        public ContainerClickClaim{tag(input);changedSlots=bounded(changedSlots,128);}
        public int chargedBytes(){return 128+24*changedSlots.size();}
    }

    // ---- Entity ----
    record DamageEvent(int entityId,String sourceType,int sourceCauseId,int sourceDirectId,boolean hasPosition,double x,double y,double z) implements SemanticPayload {
        public DamageEvent{entity(entityId);tag(sourceType);if(hasPosition){finite(x);finite(y);finite(z);}}
        public int chargedBytes(){return 160;}
    }
    record EntitySimpleEvent(int entityId,byte eventId) implements SemanticPayload {
        public EntitySimpleEvent{entity(entityId);}public int chargedBytes(){return 64;}
    }
    /** Presence-only for heavy serializer kinds (ITEM_STACK/BLOCK_STATE/COMPONENT/PARTICLE/...); scalar kinds carry their value's canonical string form. */
    record EntityDataField(int id,String kind,String value){public EntityDataField{tag(kind);Objects.requireNonNull(value);if(value.length()>256)throw new IllegalArgumentException("entity data field value bound");}}
    record EntitySyncedData(int entityId,List<EntityDataField> fields) implements SemanticPayload {
        public EntitySyncedData{entity(entityId);fields=bounded(fields,64);}
        public int chargedBytes(){return 96+fields.stream().mapToInt(f->32+2*f.value().length()).sum();}
    }
    record LookAt(String fromAnchor,double x,double y,double z,int entityId,String toAnchor,boolean atEntity) implements SemanticPayload {
        public LookAt{tag(fromAnchor);finite(x);finite(y);finite(z);tag(toAnchor);}
        public int chargedBytes(){return 128;}
    }
    record MinecartStepFact(double x,double y,double z,float yaw,float pitch){public int chargedBytes(){return 48;}}
    record MinecartMove(int entityId,List<MinecartStepFact> steps) implements SemanticPayload {
        public MinecartMove{entity(entityId);steps=bounded(steps,256);}
        public int chargedBytes(){return 64+48*steps.size();}
    }
    record Respawn(String dimension,byte keepFlags,int dimensionMinY,int dimensionHeight) implements SemanticPayload {
        public Respawn{tag(dimension);dimensionRange(dimensionMinY,dimensionHeight);}public int chargedBytes(){return 96;}
        public boolean hasDimensionRange(){return dimensionHeight!=0;}
    }

    // ---- Chunk / world (compact projections; never a per-block explosion) ----
    record BlockEntityObservation(BlockPosition position,String type,int encodedTagBytes) implements SemanticPayload {
        public BlockEntityObservation{Objects.requireNonNull(position);tag(type);if(encodedTagBytes<0)throw new IllegalArgumentException("nbt size");}
        public int chargedBytes(){return 96;}
    }
    record ChunkBiomeObservation(int chunkX,int chunkZ,int encodedBytes){}
    record ChunksBiomes(List<ChunkBiomeObservation> chunks) implements SemanticPayload {
        public ChunksBiomes{chunks=bounded(chunks,256);}public int chargedBytes(){return 64+32*chunks.size();}
    }
    record BlockUpdate(BlockPosition position,BlockState state){public BlockUpdate{Objects.requireNonNull(position);Objects.requireNonNull(state);}}
    record SectionBlocksUpdate(int sectionX,int sectionY,int sectionZ,List<BlockUpdate> updates) implements SemanticPayload {
        public SectionBlocksUpdate{updates=bounded(updates,4096);}
        public int chargedBytes(){return 96+updates.stream().mapToInt(u->128+u.state().chargedBytes()).sum();}
    }
    record LightUpdate(int chunkX,int chunkZ,int skySectionCount,int blockSectionCount,int encodedBytes) implements SemanticPayload {
        public int chargedBytes(){return 96;}
    }
    /**
     * One chunk section's block-state content exactly as the client's own parser would recover it (Phase 3
     * forward extension P3-E1 of the Phase 1 normalizer; wire format pinned from the 26.2 server bytecode:
     * {@code short nonEmpty, short fluid, byte bits, palette, fixed long[]}). Always re-palettized to an
     * INDIRECT palette of canonical BlockStates (a global/direct wire palette is converted by the normalizer,
     * which is the only place the native id->state registry exists), so no wire/native numeric id ever
     * crosses this boundary. Storage stays packed: {@code bitsPerEntry} bits per block, 4096 blocks
     * (index = y<<8 | z<<4 | x), values never span a long (SimpleBitStorage layout). Not a record: the packed
     * array must never be handed out for mutation, so equality/hash are explicit and the only accessors are
     * read-only.
     */
    final class ChunkSection {
        public static final int BLOCKS=4096,MAX_PALETTE=4096,MAX_BITS=12;
        private final int nonEmptyBlockCount,fluidCount,bitsPerEntry;private final List<BlockState> palette;private final long[] packed;
        public ChunkSection(int nonEmptyBlockCount,int fluidCount,List<BlockState> palette,int bitsPerEntry,long[] packed){
            Objects.requireNonNull(palette);Objects.requireNonNull(packed);
            if(nonEmptyBlockCount<0||nonEmptyBlockCount>BLOCKS||fluidCount<0||fluidCount>BLOCKS)throw new IllegalArgumentException("section counts");
            if(palette.isEmpty()||palette.size()>MAX_PALETTE)throw new IllegalArgumentException("section palette");
            if(bitsPerEntry<0||bitsPerEntry>MAX_BITS)throw new IllegalArgumentException("section bits");
            if(bitsPerEntry==0){if(palette.size()!=1||packed.length!=0)throw new IllegalArgumentException("single-value section");}
            else{if(palette.size()>(1<<bitsPerEntry)||packed.length!=packedLongs(bitsPerEntry))throw new IllegalArgumentException("section storage");}
            this.nonEmptyBlockCount=nonEmptyBlockCount;this.fluidCount=fluidCount;this.palette=List.copyOf(palette);this.bitsPerEntry=bitsPerEntry;this.packed=packed.clone();
        }
        public static int packedLongs(int bits){if(bits==0)return 0;int perLong=64/bits;return (BLOCKS+perLong-1)/perLong;}
        public int nonEmptyBlockCount(){return nonEmptyBlockCount;}
        public int fluidCount(){return fluidCount;}
        public int bitsPerEntry(){return bitsPerEntry;}
        public List<BlockState> palette(){return palette;}
        public int packedLength(){return packed.length;}
        public long packedWord(int index){return packed[index];}
        /** Palette index of block (x,y,z) in 0..15 each; validated against the palette so a malformed word can never index out of range. */
        public int paletteIndex(int x,int y,int z){
            if(bitsPerEntry==0)return 0;
            int index=(y<<8)|(z<<4)|x,perLong=64/bitsPerEntry;long word=packed[index/perLong];
            int value=(int)(word>>>((index%perLong)*bitsPerEntry))&((1<<bitsPerEntry)-1);
            if(value>=palette.size())throw new IllegalStateException("palette index out of range");return value;
        }
        public BlockState stateAt(int x,int y,int z){return palette.get(paletteIndex(x,y,z));}
        /** Compact accounting: real retained bytes of this representation, not the per-BlockState diagnostic estimate used elsewhere. */
        public int chargedBytes(){int bytes=64+8*packed.length;for(var s:palette)bytes+=48+16*s.properties().size();return bytes;}
        @Override public boolean equals(Object o){
            return o instanceof ChunkSection s&&s.nonEmptyBlockCount==nonEmptyBlockCount&&s.fluidCount==fluidCount&&s.bitsPerEntry==bitsPerEntry&&s.palette.equals(palette)&&Arrays.equals(s.packed,packed);
        }
        @Override public int hashCode(){return Objects.hash(nonEmptyBlockCount,fluidCount,bitsPerEntry,palette)*31+Arrays.hashCode(packed);}
        @Override public String toString(){return "ChunkSection[nonEmpty="+nonEmptyBlockCount+",fluid="+fluidCount+",bits="+bitsPerEntry+",palette="+palette.size()+"]";}
    }
    /**
     * Chunk-baseline observation: identity + bounded byte-size facts, plus (P3-E1) the section content when it
     * fit the inline retention cap. {@code contentRetained=false} means the sections were observed but their
     * content was deliberately not carried (over cap, or a decode fault) - an explicit, never-fabricated gap that
     * the world model reports as coverage, never as air. Sections are ordered bottom-up as on the wire; absolute
     * section Y needs the dimension's minY (JoinGame/Respawn), which the world model owns.
     */
    record ChunkBaseline(int chunkX,int chunkZ,int encodedBlockBytes,int blockEntityCount,int heightmapCount,boolean hasLight,
                         boolean contentRetained,List<ChunkSection> sections,String contentNote) implements SemanticPayload {
        public static final int MAX_SECTIONS=256,INLINE_CONTENT_CAP=40*1024;
        public ChunkBaseline{
            Objects.requireNonNull(sections);Objects.requireNonNull(contentNote);
            if(encodedBlockBytes<0||blockEntityCount<0||heightmapCount<0)throw new IllegalArgumentException("chunk baseline");
            if(sections.size()>MAX_SECTIONS||contentNote.length()>64)throw new IllegalArgumentException("chunk baseline content");
            if(!contentRetained&&!sections.isEmpty())throw new IllegalArgumentException("unretained content cannot carry sections");
            sections=List.copyOf(sections);
        }
        /** Compact (pre-P3) form: summary facts only. */
        public ChunkBaseline(int chunkX,int chunkZ,int encodedBlockBytes,int blockEntityCount,int heightmapCount,boolean hasLight){
            this(chunkX,chunkZ,encodedBlockBytes,blockEntityCount,heightmapCount,hasLight,false,List.of(),"NOT_DECODED");
        }
        public int sectionBytes(){int b=0;for(var s:sections)b+=s.chargedBytes();return b;}
        public int chargedBytes(){return 128+sectionBytes();}
        @Override public int bulkBytes(){return sectionBytes();}
        /** Shedding keeps the observation (no sequence gap) and makes the content loss explicit: the world reports the chunk CONTENT_NOT_RETAINED with this note. */
        @Override public PacketPayload shedBulk(String reason){return contentRetained?new ChunkBaseline(chunkX,chunkZ,encodedBlockBytes,blockEntityCount,heightmapCount,hasLight,false,List.of(),reason):this;}
    }
}
