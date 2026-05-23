package com.firedoge.px4mc.mixin;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.firedoge.px4mc.minecraft.sublevel.ServerSubLevelContainer;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelContainers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {
    @Shadow
    @Final
    public ServerLevel level;

    @Unique
    private EmptyLevelChunk px4mc$emptyPlotChunk;

    @Inject(
            method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void px4mc$getPlotChunk(
            int x,
            int z,
            ChunkStatus status,
            boolean create,
            CallbackInfoReturnable<ChunkAccess> cir
    ) {
        ServerSubLevelContainer container = px4mc$plotContainer();
        if (container == null || !container.inPlotBounds(x, z)) {
            return;
        }

        LevelChunk chunk = container.plotChunk(new ChunkPos(x, z)).orElse(null);
        cir.setReturnValue(chunk != null ? chunk : create ? px4mc$emptyPlotChunk() : null);
    }

    @Inject(method = "getChunkNow", at = @At("HEAD"), cancellable = true)
    private void px4mc$getPlotChunkNow(int x, int z, CallbackInfoReturnable<LevelChunk> cir) {
        ServerSubLevelContainer container = px4mc$plotContainer();
        if (container == null || !container.inPlotBounds(x, z)) {
            return;
        }

        cir.setReturnValue(container.plotChunk(new ChunkPos(x, z)).orElse(null));
    }

    @Inject(method = "getChunkFutureMainThread", at = @At("HEAD"), cancellable = true)
    private void px4mc$getPlotChunkFutureMainThread(
            int x,
            int z,
            ChunkStatus status,
            boolean create,
            CallbackInfoReturnable<CompletableFuture<ChunkResult<ChunkAccess>>> cir
    ) {
        ServerSubLevelContainer container = px4mc$plotContainer();
        if (container == null || !container.inPlotBounds(x, z)) {
            return;
        }

        ChunkAccess chunk = container.plotChunk(new ChunkPos(x, z)).orElseGet(this::px4mc$emptyPlotChunk);
        cir.setReturnValue(CompletableFuture.completedFuture(ChunkResult.of(chunk)));
    }

    @Inject(method = "hasChunk", at = @At("HEAD"), cancellable = true)
    private void px4mc$hasPlotChunk(int x, int z, CallbackInfoReturnable<Boolean> cir) {
        ServerSubLevelContainer container = px4mc$plotContainer();
        if (container == null || !container.inPlotBounds(x, z)) {
            return;
        }

        cir.setReturnValue(container.plotChunk(new ChunkPos(x, z)).isPresent());
    }

    @Inject(method = "getChunkForLighting", at = @At("HEAD"), cancellable = true)
    private void px4mc$getPlotChunkForLighting(int x, int z, CallbackInfoReturnable<LightChunk> cir) {
        ServerSubLevelContainer container = px4mc$plotContainer();
        if (container == null || !container.inPlotBounds(x, z)) {
            return;
        }

        cir.setReturnValue(container.plotChunk(new ChunkPos(x, z)).orElse(null));
    }

    @Inject(method = "isPositionTicking", at = @At("HEAD"), cancellable = true)
    private void px4mc$isPlotPositionTicking(long pos, CallbackInfoReturnable<Boolean> cir) {
        ServerSubLevelContainer container = px4mc$plotContainer();
        if (container == null || !container.inPlotBounds(ChunkPos.getX(pos), ChunkPos.getZ(pos))) {
            return;
        }

        cir.setReturnValue(container.plotChunk(new ChunkPos(pos)).isPresent());
    }

    @Inject(method = "getFullChunk", at = @At("HEAD"), cancellable = true)
    private void px4mc$getFullPlotChunk(long pos, Consumer<LevelChunk> consumer, CallbackInfo ci) {
        ServerSubLevelContainer container = px4mc$plotContainer();
        if (container == null || !container.inPlotBounds(ChunkPos.getX(pos), ChunkPos.getZ(pos))) {
            return;
        }

        container.plotChunk(new ChunkPos(pos)).ifPresent(consumer);
        ci.cancel();
    }

    @Inject(method = "blockChanged", at = @At("HEAD"), cancellable = true)
    private void px4mc$plotBlockChanged(BlockPos blockPos, CallbackInfo ci) {
        ServerSubLevelContainer container = px4mc$plotContainer();
        if (container == null || !container.inPlotBounds(new ChunkPos(blockPos))) {
            return;
        }

        container.plotChunkHolder(new ChunkPos(blockPos)).ifPresent(holder -> holder.blockChanged(blockPos));
        ci.cancel();
    }

    @Inject(method = "getVisibleChunkIfPresent", at = @At("HEAD"), cancellable = true)
    private void px4mc$getVisiblePlotChunkIfPresent(long pos, CallbackInfoReturnable<ChunkHolder> cir) {
        ServerSubLevelContainer container = px4mc$plotContainer();
        if (container == null || !container.inPlotBounds(ChunkPos.getX(pos), ChunkPos.getZ(pos))) {
            return;
        }

        cir.setReturnValue(container.plotChunkHolder(new ChunkPos(pos)).orElse(null));
    }

    @Inject(
            method = "addRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private <T> void px4mc$addPlotRegionTicket(TicketType<T> type, ChunkPos pos, int distance, T value, CallbackInfo ci) {
        ServerSubLevelContainer container = px4mc$plotContainer();
        if (container != null && container.inPlotBounds(pos)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "addRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;Z)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private <T> void px4mc$addPlotRegionTicket(
            TicketType<T> type,
            ChunkPos pos,
            int distance,
            T value,
            boolean forceTicks,
            CallbackInfo ci
    ) {
        ServerSubLevelContainer container = px4mc$plotContainer();
        if (container != null && container.inPlotBounds(pos)) {
            ci.cancel();
        }
    }

    @Unique
    private ServerSubLevelContainer px4mc$plotContainer() {
        return SubLevelContainers.server(level).orElse(null);
    }

    @Unique
    private EmptyLevelChunk px4mc$emptyPlotChunk() {
        if (px4mc$emptyPlotChunk == null) {
            px4mc$emptyPlotChunk = new EmptyLevelChunk(
                    level,
                    new ChunkPos(0, 0),
                    level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS)
            );
        }
        return px4mc$emptyPlotChunk;
    }
}
