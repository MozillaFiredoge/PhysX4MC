package com.firedoge.px4mc.mixin;

import com.firedoge.px4mc.render.ClientSubLevelEffectProjection;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class ClientPlayerMixin {
    @Inject(method = "canInteractWithBlock", at = @At("HEAD"), cancellable = true)
    private void px4mc$canInteractWithClientPlotBlock(
            BlockPos pos,
            double distance,
            CallbackInfoReturnable<Boolean> cir
    ) {
        Player player = (Player) (Object) this;
        Level level = player.level();
        if (!(level instanceof ClientLevel clientLevel)) {
            return;
        }

        ClientSubLevelEffectProjection.Projection projection = ClientSubLevelEffectProjection
                .projection(clientLevel, pos)
                .orElse(null);
        if (projection == null) {
            return;
        }

        Vec3 plotEye = projection.worldToPlot(player.getEyePosition());
        double maxDistance = player.blockInteractionRange() + distance;
        cir.setReturnValue(new AABB(pos).distanceToSqr(plotEye) < maxDistance * maxDistance);
    }
}
