package com.firedoge.px4mc.mixin;

import java.util.function.BooleanSupplier;

import com.firedoge.px4mc.api.PhysicsVector;
import com.firedoge.px4mc.minecraft.sublevel.ServerSubLevelContainer;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelContainerHolder;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin implements SubLevelContainerHolder {
    @Unique
    private final ServerSubLevelContainer px4mc$subLevelContainer =
            new ServerSubLevelContainer((ServerLevel) (Object) this);
    @Unique
    private boolean px4mc$projectingSubLevelEffect;
    @Unique
    private boolean px4mc$projectingSubLevelEntity;

    @Override
    public ServerSubLevelContainer px4mc$subLevelContainer() {
        return px4mc$subLevelContainer;
    }

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void px4mc$tickPlotBlockUpdatePrimes(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        px4mc$subLevelContainer.tickPlotBlockUpdatePrimes();
    }

    @Inject(
            method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void px4mc$playPlotSound(
            Player player,
            double x,
            double y,
            double z,
            Holder<SoundEvent> sound,
            SoundSource category,
            float volume,
            float pitch,
            long seed,
            CallbackInfo ci
    ) {
        if (px4mc$projectingSubLevelEffect) {
            return;
        }
        PhysicsVector world = SubLevelManager.INSTANCE
                .plotPositionToWorld(px4mc$self(), new Vec3(x, y, z))
                .orElse(null);
        if (world == null) {
            return;
        }

        px4mc$projectingSubLevelEffect = true;
        try {
            px4mc$self().playSeededSound(player, world.x(), world.y(), world.z(), sound, category, volume, pitch, seed);
        } finally {
            px4mc$projectingSubLevelEffect = false;
        }
        ci.cancel();
    }

    @Inject(method = "globalLevelEvent", at = @At("HEAD"), cancellable = true)
    private void px4mc$globalPlotLevelEvent(int id, BlockPos pos, int data, CallbackInfo ci) {
        if (px4mc$projectingSubLevelEffect) {
            return;
        }
        PhysicsVector world = px4mc$plotBlockWorldCenter(pos);
        if (world == null) {
            return;
        }

        if (px4mc$self().getGameRules().getBoolean(GameRules.RULE_GLOBAL_SOUND_EVENTS)) {
            px4mc$self().getServer().getPlayerList().broadcastAll(new ClientboundLevelEventPacket(id, pos, data, true));
        } else {
            px4mc$self().getServer().getPlayerList().broadcast(
                    null,
                    world.x(),
                    world.y(),
                    world.z(),
                    64.0D,
                    px4mc$self().dimension(),
                    new ClientboundLevelEventPacket(id, pos, data, false)
            );
        }
        ci.cancel();
    }

    @Inject(method = "levelEvent", at = @At("HEAD"), cancellable = true)
    private void px4mc$plotLevelEvent(Player player, int type, BlockPos pos, int data, CallbackInfo ci) {
        if (px4mc$projectingSubLevelEffect) {
            return;
        }
        PhysicsVector world = px4mc$plotBlockWorldCenter(pos);
        if (world == null) {
            return;
        }

        px4mc$self().getServer().getPlayerList().broadcast(
                player,
                world.x(),
                world.y(),
                world.z(),
                64.0D,
                px4mc$self().dimension(),
                new ClientboundLevelEventPacket(type, pos, data, false)
        );
        ci.cancel();
    }

    @Inject(
            method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I",
            at = @At("HEAD"),
            cancellable = true
    )
    private <T extends ParticleOptions> void px4mc$sendPlotParticles(
            T type,
            double posX,
            double posY,
            double posZ,
            int particleCount,
            double xOffset,
            double yOffset,
            double zOffset,
            double speed,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (px4mc$projectingSubLevelEffect) {
            return;
        }
        PhysicsVector world = SubLevelManager.INSTANCE
                .plotPositionToWorld(px4mc$self(), new Vec3(posX, posY, posZ))
                .orElse(null);
        if (world == null) {
            return;
        }

        px4mc$projectingSubLevelEffect = true;
        try {
            cir.setReturnValue(px4mc$self().sendParticles(type, world.x(), world.y(), world.z(), particleCount, xOffset, yOffset, zOffset, speed));
        } finally {
            px4mc$projectingSubLevelEffect = false;
        }
    }

    @Inject(
            method = "sendParticles(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/core/particles/ParticleOptions;ZDDDIDDDD)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private <T extends ParticleOptions> void px4mc$sendPlotParticlesToPlayer(
            ServerPlayer player,
            T type,
            boolean longDistance,
            double posX,
            double posY,
            double posZ,
            int particleCount,
            double xOffset,
            double yOffset,
            double zOffset,
            double speed,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (px4mc$projectingSubLevelEffect) {
            return;
        }
        PhysicsVector world = SubLevelManager.INSTANCE
                .plotPositionToWorld(px4mc$self(), new Vec3(posX, posY, posZ))
                .orElse(null);
        if (world == null) {
            return;
        }

        px4mc$projectingSubLevelEffect = true;
        try {
            cir.setReturnValue(px4mc$self().sendParticles(player, type, longDistance, world.x(), world.y(), world.z(), particleCount, xOffset, yOffset, zOffset, speed));
        } finally {
            px4mc$projectingSubLevelEffect = false;
        }
    }

    @Inject(method = "gameEvent", at = @At("HEAD"), cancellable = true)
    private void px4mc$plotGameEvent(Holder<GameEvent> gameEvent, Vec3 pos, GameEvent.Context context, CallbackInfo ci) {
        if (px4mc$projectingSubLevelEffect) {
            return;
        }
        PhysicsVector world = SubLevelManager.INSTANCE.plotPositionToWorld(px4mc$self(), pos).orElse(null);
        if (world == null) {
            return;
        }

        px4mc$projectingSubLevelEffect = true;
        try {
            px4mc$self().gameEvent(gameEvent, new Vec3(world.x(), world.y(), world.z()), context);
        } finally {
            px4mc$projectingSubLevelEffect = false;
        }
        ci.cancel();
    }

    @Redirect(
            method = "runBlockEvents",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/players/PlayerList;broadcast(Lnet/minecraft/world/entity/player/Player;DDDDLnet/minecraft/resources/ResourceKey;Lnet/minecraft/network/protocol/Packet;)V"
            )
    )
    private void px4mc$broadcastPlotBlockEvent(
            PlayerList playerList,
            Player except,
            double x,
            double y,
            double z,
            double radius,
            ResourceKey<Level> dimension,
            Packet<?> packet
    ) {
        if (packet instanceof ClientboundBlockEventPacket blockEvent) {
            PhysicsVector world = SubLevelManager.INSTANCE
                    .plotPositionToWorld(px4mc$self(), Vec3.atCenterOf(blockEvent.getPos()))
                    .orElse(null);
            if (world != null) {
                playerList.broadcast(except, world.x(), world.y(), world.z(), radius, dimension, packet);
                return;
            }
        }
        playerList.broadcast(except, x, y, z, radius, dimension, packet);
    }

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void px4mc$addPlotEntityAtWorldPosition(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (px4mc$projectingSubLevelEntity || entity.level() != px4mc$self()) {
            return;
        }
        Vec3 plotPosition = entity.position();
        SubLevelManager.PlotProjection projection = px4mc$entityPlotProjection(plotPosition);
        if (projection == null) {
            return;
        }

        PhysicsVector worldPosition = projection.toWorld(plotPosition);
        PhysicsVector worldMovement = projection.directionToWorld(entity.getDeltaMovement());
        entity.setPos(worldPosition.x(), worldPosition.y(), worldPosition.z());
        entity.setDeltaMovement(worldMovement.x(), worldMovement.y(), worldMovement.z());

        px4mc$projectingSubLevelEntity = true;
        try {
            cir.setReturnValue(px4mc$self().addFreshEntity(entity));
        } finally {
            px4mc$projectingSubLevelEntity = false;
        }
    }

    @ModifyConstant(method = "destroyBlockProgress", constant = @Constant(doubleValue = 1024.0D))
    private double px4mc$sendPlotDestroyProgressPastVanillaRange(double originalDistance) {
        return Double.MAX_VALUE;
    }

    @Inject(method = "shouldTickBlocksAt", at = @At("HEAD"), cancellable = true)
    private void px4mc$shouldTickPlotBlocksAt(long chunkPos, CallbackInfoReturnable<Boolean> cir) {
        if (px4mc$subLevelContainer.plotChunkHolder(new ChunkPos(chunkPos)).isPresent()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isPositionTickingWithEntitiesLoaded", at = @At("HEAD"), cancellable = true)
    private void px4mc$isPlotPositionTickingWithEntitiesLoaded(long chunkPos, CallbackInfoReturnable<Boolean> cir) {
        if (px4mc$subLevelContainer.plotChunkHolder(new ChunkPos(chunkPos)).isPresent()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isNaturalSpawningAllowed(Lnet/minecraft/world/level/ChunkPos;)Z", at = @At("HEAD"), cancellable = true)
    private void px4mc$isNaturalSpawningAllowedInPlot(ChunkPos chunkPos, CallbackInfoReturnable<Boolean> cir) {
        if (px4mc$subLevelContainer.plotChunkHolder(chunkPos).isPresent()) {
            cir.setReturnValue(true);
        }
    }

    @Unique
    private PhysicsVector px4mc$plotBlockWorldCenter(BlockPos pos) {
        return SubLevelManager.INSTANCE.plotPositionToWorld(px4mc$self(), Vec3.atCenterOf(pos))
                .orElse(null);
    }

    @Unique
    private SubLevelManager.PlotProjection px4mc$entityPlotProjection(Vec3 plotPosition) {
        BlockPos origin = BlockPos.containing(plotPosition);
        SubLevelManager.PlotProjection projection = SubLevelManager.INSTANCE
                .plotProjection(px4mc$self(), origin)
                .orElse(null);
        if (projection != null) {
            return projection;
        }
        for (Direction direction : Direction.values()) {
            projection = SubLevelManager.INSTANCE
                    .plotProjection(px4mc$self(), origin.relative(direction))
                    .orElse(null);
            if (projection != null) {
                return projection;
            }
        }
        return null;
    }

    @Unique
    private ServerLevel px4mc$self() {
        return (ServerLevel) (Object) this;
    }
}
