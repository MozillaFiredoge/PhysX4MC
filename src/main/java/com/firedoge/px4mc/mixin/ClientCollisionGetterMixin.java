package com.firedoge.px4mc.mixin;

import java.util.List;

import com.firedoge.px4mc.render.ClientSubLevelCollision;
import com.google.common.collect.Iterables;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CollisionGetter.class)
public interface ClientCollisionGetterMixin {
    @Inject(method = "getBlockCollisions", at = @At("RETURN"), cancellable = true)
    private void px4mc$appendClientSubLevelBlockCollisions(
            Entity entity,
            AABB collisionBox,
            CallbackInfoReturnable<Iterable<VoxelShape>> cir
    ) {
        if (!((Object) this instanceof ClientLevel level)) {
            return;
        }

        List<VoxelShape> subLevelCollisions = ClientSubLevelCollision.blockCollisionShapes(level, collisionBox);
        if (!subLevelCollisions.isEmpty()) {
            cir.setReturnValue(Iterables.concat(cir.getReturnValue(), subLevelCollisions));
        }
    }
}
