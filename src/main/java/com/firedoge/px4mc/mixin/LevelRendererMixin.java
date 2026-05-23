package com.firedoge.px4mc.mixin;

import com.firedoge.px4mc.render.ClientSubLevelBreakingProgress;
import com.firedoge.px4mc.render.ClientSubLevelEffectProjection;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow
    private ClientLevel level;
    private boolean px4mc$projectingSubLevelParticle;

    @Inject(method = "destroyBlockProgress", at = @At("HEAD"), cancellable = true)
    private void px4mc$storePlotDestroyProgress(int breakerId, BlockPos pos, int progress, CallbackInfo ci) {
        ClientLevel clientLevel = level != null ? level : Minecraft.getInstance().level;
        if (clientLevel != null && ClientSubLevelBreakingProgress.update(clientLevel, breakerId, pos, progress)) {
            ci.cancel();
        } else if (progress < 0 && ClientSubLevelBreakingProgress.removeIfTracked(breakerId)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDDDD)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void px4mc$addPlotParticle(
            ParticleOptions options,
            boolean force,
            boolean decreased,
            double x,
            double y,
            double z,
            double xSpeed,
            double ySpeed,
            double zSpeed,
            CallbackInfo ci
    ) {
        if (px4mc$projectingSubLevelParticle || level == null) {
            return;
        }

        ClientSubLevelEffectProjection.Projection projection = ClientSubLevelEffectProjection
                .projection(level, BlockPos.containing(x, y, z))
                .orElse(null);
        if (projection == null) {
            return;
        }

        Vec3 world = projection.toWorld(new Vec3(x, y, z));
        Vec3 worldSpeed = projection.directionToWorld(new Vec3(xSpeed, ySpeed, zSpeed));
        px4mc$projectingSubLevelParticle = true;
        try {
            ((LevelRenderer) (Object) this).addParticle(
                    options,
                    force,
                    decreased,
                    world.x,
                    world.y,
                    world.z,
                    worldSpeed.x,
                    worldSpeed.y,
                    worldSpeed.z
            );
        } finally {
            px4mc$projectingSubLevelParticle = false;
        }
        ci.cancel();
    }
}
