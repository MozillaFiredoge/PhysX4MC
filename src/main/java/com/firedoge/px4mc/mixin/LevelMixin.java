package com.firedoge.px4mc.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.firedoge.px4mc.minecraft.sublevel.SubLevelEntityBridge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelMixin {
    @Inject(
            method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true
    )
    private void px4mc$appendProjectedPlotEntities(
            @Nullable Entity entity,
            AABB boundingBox,
            Predicate<? super Entity> predicate,
            CallbackInfoReturnable<List<Entity>> cir
    ) {
        if (!((Object) this instanceof ServerLevel level) || SubLevelEntityBridge.isProjectingQuery()) {
            return;
        }

        List<Entity> projected = SubLevelEntityBridge.projectedEntities(level, entity, boundingBox, predicate, cir.getReturnValue());
        if (!projected.isEmpty()) {
            List<Entity> combined = new ArrayList<>(cir.getReturnValue());
            combined.addAll(projected);
            cir.setReturnValue(combined);
        }
    }

    @Inject(
            method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true
    )
    private <T extends Entity> void px4mc$appendProjectedPlotEntitiesOfType(
            EntityTypeTest<Entity, T> entityTypeTest,
            AABB bounds,
            Predicate<? super T> predicate,
            CallbackInfoReturnable<List<T>> cir
    ) {
        if (!((Object) this instanceof ServerLevel level) || SubLevelEntityBridge.isProjectingQuery()) {
            return;
        }

        List<T> projected = SubLevelEntityBridge.projectedEntities(level, entityTypeTest, bounds, predicate, cir.getReturnValue());
        if (!projected.isEmpty()) {
            List<T> combined = new ArrayList<>(cir.getReturnValue());
            combined.addAll(projected);
            cir.setReturnValue(combined);
        }
    }
}
