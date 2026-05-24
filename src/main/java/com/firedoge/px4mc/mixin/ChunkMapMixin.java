package com.firedoge.px4mc.mixin;

import java.util.List;

import com.firedoge.px4mc.minecraft.sublevel.PlotChunkHolder;
import com.firedoge.px4mc.minecraft.sublevel.ServerSubLevelContainer;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelContainers;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @Inject(method = "getPlayers", at = @At("HEAD"), cancellable = true)
    private void px4mc$getPlayersTrackingPlot(ChunkPos chunkPos, boolean boundaryOnly, CallbackInfoReturnable<List<ServerPlayer>> cir) {
        ServerSubLevelContainer container = px4mc$plotContainer();
        if (container != null && container.plotChunkHolder(chunkPos).isPresent()) {
            cir.setReturnValue(container.trackingSystem().playersTracking(chunkPos));
        }
    }

    @Inject(method = "saveChunkIfNeeded", at = @At("HEAD"), cancellable = true)
    private void px4mc$skipSavingPlotChunks(ChunkHolder chunkHolder, CallbackInfoReturnable<Boolean> cir) {
        if (chunkHolder instanceof PlotChunkHolder) {
            cir.setReturnValue(false);
        }
    }

    @Redirect(
            method = "hasWork",
            at = @At(
                    value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap;isEmpty()Z",
                    ordinal = 1,
                    remap = false
            )
    )
    private boolean px4mc$hasNonPlotChunkWork(Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap) {
        return updatingChunkMap.values().stream().noneMatch(holder -> !(holder instanceof PlotChunkHolder));
    }

    @Inject(method = "isChunkTracked", at = @At("HEAD"), cancellable = true)
    private void px4mc$isPlotChunkTracked(ServerPlayer player, int x, int z, CallbackInfoReturnable<Boolean> cir) {
        ChunkPos chunkPos = new ChunkPos(x, z);
        ServerSubLevelContainer container = px4mc$plotContainer();
        if (container != null && container.plotChunkHolder(chunkPos).isPresent()) {
            cir.setReturnValue(container.trackingSystem().playersTracking(chunkPos).contains(player));
        }
    }

    @Inject(method = "anyPlayerCloseEnoughForSpawning", at = @At("HEAD"), cancellable = true)
    private void px4mc$anyPlotPlayerCloseEnoughForSpawning(ChunkPos chunkPos, CallbackInfoReturnable<Boolean> cir) {
        ServerSubLevelContainer container = px4mc$plotContainer();
        if (container != null && container.plotChunkHolder(chunkPos).isPresent()) {
            cir.setReturnValue(!container.trackingSystem().playersTracking(chunkPos).isEmpty());
        }
    }

    private ServerSubLevelContainer px4mc$plotContainer() {
        return SubLevelContainers.server(level).orElse(null);
    }
}
