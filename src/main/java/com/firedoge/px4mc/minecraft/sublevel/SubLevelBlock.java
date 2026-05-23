package com.firedoge.px4mc.minecraft.sublevel;

import java.util.List;
import java.util.Objects;

import com.firedoge.px4mc.api.PhysicsVector;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

public record SubLevelBlock(
        BlockPos sourcePos,
        BlockPos localPos,
        BlockState blockState,
        AABB localCollisionBounds,
        List<AABB> localCollisionBoxes,
        PhysicsVector visualLocalOrigin,
        @Nullable CompoundTag blockEntityTag
) {
    public SubLevelBlock {
        Objects.requireNonNull(sourcePos, "sourcePos");
        Objects.requireNonNull(localPos, "localPos");
        Objects.requireNonNull(blockState, "blockState");
        Objects.requireNonNull(localCollisionBounds, "localCollisionBounds");
        Objects.requireNonNull(localCollisionBoxes, "localCollisionBoxes");
        Objects.requireNonNull(visualLocalOrigin, "visualLocalOrigin");
        if (localCollisionBoxes.isEmpty()) {
            throw new IllegalArgumentException("localCollisionBoxes must not be empty");
        }
        localCollisionBoxes = localCollisionBoxes.stream()
                .map(SubLevelBlock::copy)
                .toList();
        blockEntityTag = blockEntityTag == null ? null : blockEntityTag.copy();
    }

    public SubLevelBlock(
            BlockPos sourcePos,
            BlockPos localPos,
            BlockState blockState,
            AABB localCollisionBounds,
            List<AABB> localCollisionBoxes,
            PhysicsVector visualLocalOrigin
    ) {
        this(sourcePos, localPos, blockState, localCollisionBounds, localCollisionBoxes, visualLocalOrigin, null);
    }

    public SubLevelBlock(
            BlockPos sourcePos,
            BlockPos localPos,
            BlockState blockState,
            AABB localCollisionBounds,
            PhysicsVector visualLocalOrigin
    ) {
        this(sourcePos, localPos, blockState, localCollisionBounds, List.of(localCollisionBounds), visualLocalOrigin, null);
    }

    public SubLevelBlock withVisualLocalOrigin(PhysicsVector visualLocalOrigin) {
        return new SubLevelBlock(sourcePos, localPos, blockState, localCollisionBounds, localCollisionBoxes, visualLocalOrigin, blockEntityTag);
    }

    public List<AABB> bodyLocalCollisionBoxes() {
        return localCollisionBoxes.stream()
                .map(bounds -> new AABB(
                        visualLocalOrigin.x() + bounds.minX,
                        visualLocalOrigin.y() + bounds.minY,
                        visualLocalOrigin.z() + bounds.minZ,
                        visualLocalOrigin.x() + bounds.maxX,
                        visualLocalOrigin.y() + bounds.maxY,
                        visualLocalOrigin.z() + bounds.maxZ
                ))
                .toList();
    }

    private static AABB copy(AABB bounds) {
        Objects.requireNonNull(bounds, "bounds");
        return new AABB(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
    }
}
