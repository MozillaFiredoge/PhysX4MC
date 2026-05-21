package com.firedoge.px4mc.minecraft.sublevel;

import java.util.Objects;

import com.firedoge.px4mc.api.PhysicsVector;
import com.firedoge.px4mc.mechanics.MechanicsBodySnapshot;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public record SubLevelPickResult(
        SubLevelId id,
        MechanicsBodySnapshot body,
        SubLevelBlock block,
        BlockPos localPos,
        BlockState blockState,
        PhysicsVector worldHit,
        PhysicsVector localHit,
        double distance
) {
    public SubLevelPickResult {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(localPos, "localPos");
        Objects.requireNonNull(blockState, "blockState");
        Objects.requireNonNull(worldHit, "worldHit");
        Objects.requireNonNull(localHit, "localHit");
        if (distance < 0.0D || Double.isNaN(distance)) {
            throw new IllegalArgumentException("distance must not be negative or NaN");
        }
    }
}
