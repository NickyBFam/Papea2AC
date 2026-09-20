package me.nicolas.papea2ac.oracle;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

/**
 * A real {@link ServerPlayer} of the pinned jar, never added to the level and never connected, whose authority flags are
 * flipped so the SERVER executes the LOCAL-CLIENT branches of the shared movement code: {@code isClientAuthoritative()}
 * false makes {@code Entity.move} update ground/collision flags, run {@code checkFallDamage} and (with
 * {@code canSimulateMovement()} true) {@code restituteMovementAfterCollisions}; {@code isEffectiveAi()} true makes
 * {@code LivingEntity.aiStep} call {@code travel}. Everything else - {@code jumpFromGround}, {@code travelInAir},
 * {@code moveRelative}, {@code handleOnClimbable}, {@code Entity.move}, {@code collide}, {@code applyEffectsFromBlocks},
 * {@code updatePlayerPose} - is the jar's own code path. Nothing here reimplements physics; the only glue is the
 * LocalPlayer input handling (documented in PHASE_5_IMPLEMENTATION.md "NATIVE DIFFERENTIAL").
 */
public final class OraclePlayer extends ServerPlayer {
    private static final Field IN_BLOCK_STATE, NO_JUMP_DELAY, ON_GROUND_NO_BLOCKS;
    static {
        try {
            IN_BLOCK_STATE = net.minecraft.world.entity.Entity.class.getDeclaredField("inBlockState"); IN_BLOCK_STATE.setAccessible(true);
            NO_JUMP_DELAY = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("noJumpDelay"); NO_JUMP_DELAY.setAccessible(true);
            ON_GROUND_NO_BLOCKS = net.minecraft.world.entity.Entity.class.getDeclaredField("onGroundNoBlocks"); ON_GROUND_NO_BLOCKS.setAccessible(true);
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }

    public OraclePlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, ClientInformation.createDefault());
        this.valid = true; // Paper: an entity outside a level is "immobile" (Player.isImmobile) unless valid
        getAbilities().mayfly = true;      // Player.causeFallDamage short-circuits: no damage path, no fall sounds
        getAbilities().invulnerable = true; // berry bush / cactus damage never reaches the hurt path
        getAbilities().flying = false;
    }
    @Override public boolean isClientAuthoritative() { return false; }
    @Override public boolean canSimulateMovement() { return true; }
    @Override public boolean isEffectiveAi() { return true; }
    @Override public void resendPossiblyDesyncedDataValues(List<EntityDataAccessor<?>> accessors, ServerPlayer player) { /* no connection */ }
    /** LivingEntity.onEffectAdded without the ServerPlayer packet send (no connection): the attribute modifiers are what movement reads. */
    @Override protected void onEffectAdded(net.minecraft.world.effect.MobEffectInstance effect, net.minecraft.world.entity.Entity source) { effect.getEffect().value().addAttributeModifiers(getAttributes(), effect.getAmplifier()); }
    @Override protected void onEffectsRemoved(java.util.Collection<net.minecraft.world.effect.MobEffectInstance> effects) { for (var e : effects) e.getEffect().value().removeAttributeModifiers(getAttributes()); }
    /** Damage never reaches a connection-less player (magma, cactus, berry bush, fall): the oracle measures motion only. */
    @Override public boolean hurtServer(ServerLevel level, net.minecraft.world.damagesource.DamageSource source, float amount) { return false; }

    // ---- glue exposed to the oracle driver (each is a vanilla method the client tick calls at that point)
    public void baseTickFluidsAndPowderSnow() {
        this.wasInPowderSnow = this.isInPowderSnow; this.isInPowderSnow = false;
        try { IN_BLOCK_STATE.set(this, null); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        updateFluidInteraction();
        updateSwimming();
    }
    public boolean fits(Pose pose) { return canPlayerFitWithinBlocksAndEntitiesWhen(pose); }
    public void setInput(float leftImpulse, float forwardImpulse, boolean jump) { this.xxa = leftImpulse; this.zza = forwardImpulse; setJumping(jump); }
    public void resetPaperJumpGate() { this.lastJumpTime = 0L; }
    public int noJumpDelay() { try { return NO_JUMP_DELAY.getInt(this); } catch (IllegalAccessException e) { throw new IllegalStateException(e); } }
    public void setNoJumpDelay(int v) { try { NO_JUMP_DELAY.setInt(this, v); } catch (IllegalAccessException e) { throw new IllegalStateException(e); } }
    public boolean onGroundNoBlocks() { try { return ON_GROUND_NO_BLOCKS.getBoolean(this); } catch (IllegalAccessException e) { throw new IllegalStateException(e); } }
    public void setOnGroundNoBlocks(boolean v) { try { ON_GROUND_NO_BLOCKS.setBoolean(this, v); } catch (IllegalAccessException e) { throw new IllegalStateException(e); } }
    public Vec3 stuck() { return this.stuckSpeedMultiplier; }
    public void setStuck(Vec3 v) { this.stuckSpeedMultiplier = v; }
    public Optional<BlockPos> support() { return this.mainSupportingBlockPos; }
    public void setSupport(Optional<BlockPos> s) { this.mainSupportingBlockPos = s; }
}
