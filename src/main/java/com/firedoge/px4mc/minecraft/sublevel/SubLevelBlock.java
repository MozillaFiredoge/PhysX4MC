package com.firedoge.px4mc.minecraft.sublevel;

import java.util.Objects;

import com.firedoge.px4mc.api.PhysicsVector;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public record SubLevelBlock(
        BlockPos sourcePos,
        BlockPos localPos,
        BlockState blockState,
        AABB localCollisionBounds,
        PhysicsVector visualLocalOrigin
) {
    public SubLevelBlock {
        Objects.requireNonNull(sourcePos, "sourcePos");
        Objects.requireNonNull(localPos, "localPos");
        Objects.requireNonNull(blockState, "blockState");
        Objects.requireNonNull(localCollisionBounds, "localCollisionBounds");
        Objects.requireNonNull(visualLocalOrigin, "visualLocalOrigin");
    }

    public SubLevelBlock withVisualLocalOrigin(PhysicsVector visualLocalOrigin) {
        return new SubLevelBlock(sourcePos, localPos, blockState, localCollisionBounds, visualLocalOrigin);
    }
}
