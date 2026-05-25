package com.firedoge.px4mc.mixin;

import java.util.List;

import com.firedoge.px4mc.minecraft.sublevel.SubLevelEntityBridge;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelManager;
import com.google.common.collect.Iterables;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CollisionGetter.class)
public interface CollisionGetterMixin {
    @Inject(method = "getBlockCollisions", at = @At("RETURN"), cancellable = true)
    private void px4mc$appendServerSubLevelBlockCollisions(
            Entity entity,
            AABB collisionBox,
            CallbackInfoReturnable<Iterable<VoxelShape>> cir
    ) {
        if (!((Object) this instanceof ServerLevel level)) {
            return;
        }
        if (entity != null && SubLevelEntityBridge.isRegisteredAttachedEntity(level, entity)) {
            return;
        }

        List<VoxelShape> subLevelCollisions = SubLevelManager.INSTANCE.blockCollisionShapes(level, collisionBox);
        if (!subLevelCollisions.isEmpty()) {
            cir.setReturnValue(Iterables.concat(cir.getReturnValue(), subLevelCollisions));
        }
    }
}
