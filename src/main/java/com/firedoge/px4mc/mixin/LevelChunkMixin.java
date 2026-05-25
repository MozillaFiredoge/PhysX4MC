package com.firedoge.px4mc.mixin;

import java.util.Optional;

import com.firedoge.px4mc.minecraft.sublevel.ClientSubLevelContainer;
import com.firedoge.px4mc.minecraft.sublevel.PhysicsSubLevel;
import com.firedoge.px4mc.minecraft.sublevel.ServerSubLevelContainer;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelContainers;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Shadow
    @Final
    Level level;

    @Inject(method = "setBlockState", at = @At("HEAD"), cancellable = true)
    private void px4mc$setPlotChunkBlockState(
            BlockPos pos,
            BlockState state,
            boolean isMoving,
            CallbackInfoReturnable<BlockState> cir
    ) {
        ServerSubLevelContainer container = px4mc$plotContainer(pos);
        if (container == null || container.isRebuildingPlotChunks()) {
            return;
        }

        Optional<PhysicsSubLevel> maybeSubLevel = container.subLevelAtPlotBlock(pos);
        if (maybeSubLevel.isEmpty()) {
            return;
        }

        BlockState previous = ((LevelChunk) (Object) this).getBlockState(pos);
        if (!SubLevelManager.INSTANCE.setPlotBlockState((ServerLevel) level, pos, state)) {
            cir.setReturnValue(null);
            return;
        }
        px4mc$runLatePlotDiodePlaceHook(pos, previous, state, isMoving);
        cir.setReturnValue(previous);
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void px4mc$markClientPlotChunkBlockChanged(
            BlockPos pos,
            BlockState state,
            boolean isMoving,
            CallbackInfoReturnable<BlockState> cir
    ) {
        if (!level.isClientSide()) {
            return;
        }

        ClientSubLevelContainer container = SubLevelContainers.container(level)
                .filter(ClientSubLevelContainer.class::isInstance)
                .map(ClientSubLevelContainer.class::cast)
                .orElse(null);
        if (container != null && container.inPlotBounds(new ChunkPos(pos))) {
            container.markPlotBlockChanged(pos);
        }
    }

    @Inject(method = "setBlockEntity", at = @At("HEAD"), cancellable = true)
    private void px4mc$setPlotChunkBlockEntity(BlockEntity blockEntity, CallbackInfo ci) {
        ServerSubLevelContainer container = px4mc$plotContainer(blockEntity.getBlockPos());
        if (container == null || container.isRebuildingPlotChunks()) {
            return;
        }
        if (SubLevelManager.INSTANCE.setPlotBlockEntity((ServerLevel) level, blockEntity)) {
            ci.cancel();
        }
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"), cancellable = true)
    private void px4mc$removePlotChunkBlockEntity(BlockPos pos, CallbackInfo ci) {
        ServerSubLevelContainer container = px4mc$plotContainer(pos);
        if (container == null || container.isRebuildingPlotChunks()) {
            return;
        }
        if (SubLevelManager.INSTANCE.removePlotBlockEntity((ServerLevel) level, pos)) {
            ci.cancel();
        }
    }

    @Unique
    private ServerSubLevelContainer px4mc$plotContainer(BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        ServerSubLevelContainer container = SubLevelContainers.server(serverLevel).orElse(null);
        if (container == null || !container.inPlotBounds(new ChunkPos(pos))) {
            return null;
        }
        return container;
    }

    @Unique
    private void px4mc$runLatePlotDiodePlaceHook(BlockPos pos, BlockState previous, BlockState requested, boolean isMoving) {
        if (level.captureBlockSnapshots || previous.equals(requested) || !(requested.getBlock() instanceof DiodeBlock)) {
            return;
        }
        if (((LevelChunk) (Object) this).getBlockState(pos).equals(requested)) {
            return;
        }

        BlockState live = level.getBlockState(pos);
        if (live.getBlock() == requested.getBlock()) {
            live.onPlace(level, pos, previous, isMoving);
        }
    }
}
