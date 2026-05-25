package com.firedoge.px4mc.mixin;

import com.firedoge.px4mc.minecraft.sublevel.SubLevelEntityBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ItemFrame.class)
public abstract class ItemFrameMixin {
    @Redirect(
            method = "survives",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
            )
    )
    private BlockState px4mc$getPlotSupportBlockState(Level level, BlockPos supportPos) {
        if (level instanceof ServerLevel serverLevel) {
            return SubLevelEntityBridge.attachedSupportBlockState(serverLevel, (ItemFrame) (Object) this)
                    .orElseGet(() -> level.getBlockState(supportPos));
        }
        return level.getBlockState(supportPos);
    }

    @Redirect(
            method = "setRotation(IZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;updateNeighbourForOutputSignal(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V"
            )
    )
    private void px4mc$updatePlotOutputSignal(Level level, BlockPos pos, Block block) {
        if (level instanceof ServerLevel serverLevel) {
            BlockPos plotAnchor = SubLevelEntityBridge.attachedPlotAnchor(serverLevel, (ItemFrame) (Object) this)
                    .orElse(null);
            if (plotAnchor != null) {
                level.updateNeighbourForOutputSignal(plotAnchor, block);
                return;
            }
        }
        level.updateNeighbourForOutputSignal(pos, block);
    }
}
