package com.firedoge.px4mc.minecraft.sublevel;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public record SubLevelPlotTarget(
        SubLevelId id,
        BlockPos localPos,
        BlockState blockState
) {
    public SubLevelPlotTarget {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(localPos, "localPos");
        Objects.requireNonNull(blockState, "blockState");
    }
}
