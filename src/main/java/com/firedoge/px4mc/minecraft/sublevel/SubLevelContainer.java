package com.firedoge.px4mc.minecraft.sublevel;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public interface SubLevelContainer {
    Level level();

    List<PhysicsSubLevel> subLevels();

    Optional<PhysicsSubLevel> subLevel(SubLevelId id);

    Optional<PhysicsSubLevel> subLevelAtPlotBlock(BlockPos plotPos);

    int size();

    default boolean isEmpty() {
        return size() == 0;
    }

    default void clientStartTracking(SubLevelClientMetadata metadata) {
    }

    default void clientFinalizeTracking(SubLevelId id) {
    }

    default void clientUpdateTransform(SubLevelId id, com.firedoge.px4mc.api.PhysicsPose pose) {
    }

    default void clientStopTracking(SubLevelId id) {
    }
}
