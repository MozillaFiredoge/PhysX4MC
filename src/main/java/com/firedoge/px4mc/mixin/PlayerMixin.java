package com.firedoge.px4mc.mixin;

import com.firedoge.px4mc.minecraft.sublevel.ServerSubLevelContainer;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelContainers;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin {
    @Inject(method = "canInteractWithBlock", at = @At("HEAD"), cancellable = true)
    private void px4mc$canInteractWithPlotBlock(BlockPos pos, double distance, CallbackInfoReturnable<Boolean> cir) {
        Player player = (Player) (Object) this;
        Level level = player.level();
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        ServerSubLevelContainer container = SubLevelContainers.server(serverLevel).orElse(null);
        if (container == null
                || !container.inPlotBounds(new ChunkPos(pos))
                || container.subLevelAtPlotBlock(pos).isEmpty()) {
            return;
        }

        cir.setReturnValue(SubLevelManager.INSTANCE.playerCanReachPlotBlock(serverLevel, serverPlayer, pos, distance));
    }
}
