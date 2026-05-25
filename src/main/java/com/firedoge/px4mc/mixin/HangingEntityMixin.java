package com.firedoge.px4mc.mixin;

import com.firedoge.px4mc.minecraft.sublevel.SubLevelEntityBridge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HangingEntity.class)
public abstract class HangingEntityMixin {
    @Inject(method = "survives", at = @At("HEAD"), cancellable = true)
    private void px4mc$subLevelAttachedEntitySurvives(CallbackInfoReturnable<Boolean> cir) {
        Level level = ((HangingEntity) (Object) this).level();
        if (level instanceof ServerLevel serverLevel) {
            SubLevelEntityBridge.attachedEntitySurvives(serverLevel, (HangingEntity) (Object) this)
                    .ifPresent(cir::setReturnValue);
        }
    }
}
