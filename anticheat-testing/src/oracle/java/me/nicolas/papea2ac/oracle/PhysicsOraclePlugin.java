package me.nicolas.papea2ac.oracle;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Phase 5 native oracle (test-only plugin, never shipped): runs explicit movement scenarios through the pinned server's
 * OWN movement code on a never-connected {@link OraclePlayer} and records every tick's state. Input: {@code oracle-scenarios.json}
 * in the server directory (written by {@code PhysicsOracleMain}); output: {@code oracle-results.json}; then the server
 * shuts itself down. The world is a void superflat so every block the scenarios see was placed by the scenario itself
 * (mirroring what the differential driver builds into the observer world).
 *
 * Per tick the driver performs exactly the LocalPlayer glue the client performs around the shared code (see
 * PHASE_5_IMPLEMENTATION.md "NATIVE DIFFERENTIAL"): baseTick fluid/powder-snow bookkeeping, the crouch / sneak-speed /
 * item-use input scaling into {@code xxa/zza}, the jump/shift/sprint/yaw flags, then the jar's {@code aiStep()} and
 * {@code updatePlayerPose()}.
 */
public final class PhysicsOraclePlugin extends JavaPlugin {
    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, this::runAll, 40L);
    }
    private void runAll() {
        Path dir = Bukkit.getWorldContainer().toPath();
        Path in = dir.resolve("oracle-scenarios.json"), out = dir.resolve("oracle-results.json");
        JsonObject results = new JsonObject();
        try {
            JsonObject spec = JsonParser.parseString(Files.readString(in, StandardCharsets.UTF_8)).getAsJsonObject();
            World world = Bukkit.getWorlds().get(0);
            world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0); world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false); world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
            world.setGameRule(GameRule.DO_MOB_SPAWNING, false); world.setGameRule(GameRule.DO_FIRE_TICK, false); world.setGameRule(GameRule.MOB_GRIEFING, false);
            for (int cx = -3; cx <= 3; cx++) for (int cz = -3; cz <= 3; cz++) { world.getChunkAt(cx, cz).load(true); world.setChunkForceLoaded(cx, cz, true); }
            ServerLevel level = ((CraftWorld) world).getHandle();
            results.addProperty("profile", spec.get("profile").getAsString());
            results.addProperty("server", Bukkit.getVersion());
            JsonArray outScenarios = new JsonArray();
            for (JsonElement e : spec.getAsJsonArray("scenarios")) {
                JsonObject sc = e.getAsJsonObject();
                JsonObject r = new JsonObject(); r.addProperty("id", sc.get("id").getAsString());
                List<BlockPos> placed = new ArrayList<>();
                try {
                    for (JsonElement b : sc.getAsJsonArray("blocks")) {
                        JsonObject bo = b.getAsJsonObject();
                        BlockPos pos = new BlockPos(bo.get("x").getAsInt(), bo.get("y").getAsInt(), bo.get("z").getAsInt());
                        BlockState state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, bo.get("state").getAsString(), false).blockState();
                        level.setBlock(pos, state, 2 | 16); placed.add(pos);
                    }
                    r.add("ticks", run(level, sc));
                } catch (Throwable t) {
                    StringWriter sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw));
                    r.addProperty("error", sw.toString());
                    getLogger().warning("scenario " + sc.get("id") + " failed: " + t);
                } finally {
                    for (BlockPos p : placed) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
                outScenarios.add(r);
            }
            results.add("scenarios", outScenarios);
        } catch (Throwable t) {
            StringWriter sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw));
            results.addProperty("error", sw.toString());
        }
        try { Files.writeString(out, new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create().toJson(results), StandardCharsets.UTF_8); }
        catch (IOException e) { getLogger().severe("cannot write results: " + e); }
        Bukkit.getScheduler().runTaskLater(this, Bukkit::shutdown, 5L);
    }

    private JsonArray run(ServerLevel level, JsonObject sc) {
        MinecraftServer server = MinecraftServer.getServer();
        OraclePlayer p = new OraclePlayer(server, level, new GameProfile(UUID.nameUUIDFromBytes(("oracle:" + sc.get("id").getAsString()).getBytes(StandardCharsets.UTF_8)), "Oracle"));
        JsonObject st = sc.getAsJsonObject("start");
        if (sc.has("attributes")) for (var a : sc.getAsJsonObject("attributes").entrySet()) {
            Holder<Attribute> holder = BuiltInRegistries.ATTRIBUTE.get(Identifier.parse(a.getKey())).orElseThrow();
            AttributeInstance inst = p.getAttribute(holder);
            if (inst == null) throw new IllegalArgumentException("player has no attribute " + a.getKey());
            inst.setBaseValue(a.getValue().getAsDouble());
        }
        if (sc.has("effects")) for (JsonElement e : sc.getAsJsonArray("effects")) {
            JsonObject eo = e.getAsJsonObject();
            Holder<MobEffect> holder = BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(eo.get("id").getAsString())).orElseThrow();
            if (!p.addEffect(new MobEffectInstance(holder, 1_000_000, eo.get("amplifier").getAsInt(), false, false))) throw new IllegalStateException("effect refused " + eo);
        }
        if (sc.has("boots") && sc.get("boots").getAsBoolean()) p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));
        p.setPose(Pose.valueOf(st.get("pose").getAsString()));
        p.refreshDimensions(); // LivingEntity.tick would do this through refreshDirtyAttributes (scale attribute); the oracle never runs tick()
        p.setYRot(st.get("yaw").getAsFloat()); p.setXRot(0f);
        p.setPos(st.get("x").getAsDouble(), st.get("y").getAsDouble(), st.get("z").getAsDouble());
        p.setDeltaMovement(st.get("vx").getAsDouble(), st.get("vy").getAsDouble(), st.get("vz").getAsDouble());
        p.setOnGround(st.get("onGround").getAsBoolean());
        p.fallDistance = st.get("fallDistance").getAsDouble();
        p.setStuck(new Vec3(st.get("stuckX").getAsDouble(), st.get("stuckY").getAsDouble(), st.get("stuckZ").getAsDouble()));
        p.isInPowderSnow = st.get("inPowderSnow").getAsBoolean();
        p.setNoJumpDelay(st.get("noJumpDelay").getAsInt());
        if (st.has("support") && !st.get("support").isJsonNull()) { JsonArray s = st.getAsJsonArray("support"); p.setSupport(Optional.of(new BlockPos(s.get(0).getAsInt(), s.get(1).getAsInt(), s.get(2).getAsInt()))); p.setOnGroundNoBlocks(false); }
        else { p.setSupport(Optional.empty()); p.setOnGroundNoBlocks(st.has("onGroundNoBlocks") && st.get("onGroundNoBlocks").getAsBoolean()); }
        boolean sprint = st.has("sprinting") && st.get("sprinting").getAsBoolean();
        p.setSprinting(sprint);
        p.setShiftKeyDown(st.has("shift") && st.get("shift").getAsBoolean());
        JsonArray out = new JsonArray();
        for (JsonElement te : sc.getAsJsonArray("ticks")) {
            JsonObject t = te.getAsJsonObject();
            int forward = t.get("f").getAsInt(), strafe = t.get("s").getAsInt();
            boolean jump = t.get("jump").getAsBoolean(), shift = t.get("shift").getAsBoolean(), sprinting = t.get("sprint").getAsBoolean(), use = t.get("use").getAsBoolean();
            float yaw = t.get("yaw").getAsFloat();
            // ---- one client tick around the jar's shared code
            p.setOldPosAndRot();
            p.baseTickFluidsAndPowderSnow();
            // P9-F3 (pinned 26.2 client, LocalPlayer.aiStep): `crouching` is computed from isShiftKeyDown() BEFORE ClientInput.tick() reads the
            // keyboard, i.e. from the key of the PREVIOUS tick; the fresh key then drives edge back-off and updatePlayerPose. Replicated literally.
            boolean previousShift = p.isShiftKeyDown();
            p.setShiftKeyDown(shift);
            if (p.isSprinting() != sprinting) p.setSprinting(sprinting);
            p.setYRot(yaw);
            boolean crouching = !p.getAbilities().flying && !p.isSwimming() && !p.isPassenger() && p.fits(Pose.CROUCHING) && (previousShift || (!p.isSleeping() && !p.fits(Pose.STANDING)));
            boolean movingSlowly = crouching || (p.hasPose(Pose.SWIMMING) && !p.isInWater());
            // P6-F1: the local client's input pipeline (client-only LocalPlayer code, established by real-client measurement): the key vector
            // normalized in float (the jar's Vec2.normalized), scaled by 0.98F, by 0.2F while using an item, by the sneaking speed while moving
            // slowly, then the square-movement rescale; the shared applyInput 0.98F is NOT applied on top (OraclePlayer.applyInput is a no-op).
            Vec2 move = new Vec2(strafe, forward).normalized().scale(0.98F);
            if (use) move = move.scale(0.2F);
            if (movingSlowly) move = move.scale((float) p.getAttributeValue(Attributes.SNEAKING_SPEED));
            move = squareMovement(move);
            p.setInput(move.x, move.y, jump);
            p.resetPaperJumpGate();
            p.aiStep();
            p.updatePlayerPose();
            out.add(record(p, crouching));
        }
        try { p.discard(); } catch (RuntimeException ignored) { /* connection-less removal side effects are irrelevant to the recorded motion */ }
        return out;
    }
    /** The client's {@code modifyInputSpeedForSquareMovement}: {@code u = v.scale(1/|v|)}, length {@code min(|v| * sqrt(1 + (min/max)^2), 1)} - float, {@code Mth.sqrt}. */
    private static Vec2 squareMovement(Vec2 v) {
        float f = v.length();
        if (f <= 0.0F) return v;
        Vec2 u = v.scale(1.0F / f);
        float ax = Math.abs(u.x), ay = Math.abs(u.y), h = ay > ax ? ax / ay : ay / ax;
        float g = Mth.sqrt(1.0F + h * h);
        return u.scale(Math.min(f * g, 1.0F));
    }
    private static JsonObject record(OraclePlayer p, boolean crouching) {
        JsonObject o = new JsonObject();
        Vec3 pos = p.position(), v = p.getDeltaMovement(), stuck = p.stuck();
        o.addProperty("x", pos.x); o.addProperty("y", pos.y); o.addProperty("z", pos.z);
        o.addProperty("vx", v.x); o.addProperty("vy", v.y); o.addProperty("vz", v.z);
        o.addProperty("onGround", p.onGround()); o.addProperty("hc", p.horizontalCollision); o.addProperty("vc", p.verticalCollision); o.addProperty("vcb", p.verticalCollisionBelow);
        o.addProperty("fall", p.fallDistance); o.addProperty("stuckX", stuck.x); o.addProperty("stuckY", stuck.y); o.addProperty("stuckZ", stuck.z);
        o.addProperty("pose", p.getPose().name()); o.addProperty("inPowderSnow", p.isInPowderSnow); o.addProperty("noJumpDelay", p.noJumpDelay()); o.addProperty("crouching", crouching);
        o.addProperty("onGroundNoBlocks", p.onGroundNoBlocks());
        Optional<BlockPos> s = p.support();
        if (s.isPresent()) { JsonArray a = new JsonArray(); a.add(s.get().getX()); a.add(s.get().getY()); a.add(s.get().getZ()); o.add("support", a); } else o.add("support", JsonNull.INSTANCE);
        o.addProperty("speedAttr", p.getAttributeValue(Attributes.MOVEMENT_SPEED));
        return o;
    }
}
