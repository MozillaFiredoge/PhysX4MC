package com.firedoge.px4mc.minecraft.sublevel;

import java.util.List;
import java.util.Objects;

import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsVector;

import net.minecraft.world.level.ChunkPos;

public record SubLevelClientMetadata(
        SubLevelId id,
        SubLevelPlot plot,
        PhysicsPose pose,
        PhysicsVector bodyToPlotOrigin,
        List<ChunkPos> chunkPositions
) {
    public SubLevelClientMetadata {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(plot, "plot");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(bodyToPlotOrigin, "bodyToPlotOrigin");
        chunkPositions = List.copyOf(chunkPositions);
    }

    public static SubLevelClientMetadata from(PhysicsSubLevel subLevel, PhysicsPose pose) {
        Objects.requireNonNull(subLevel, "subLevel");
        return new SubLevelClientMetadata(
                subLevel.id(),
                subLevel.plot(),
                pose,
                bodyToPlotOrigin(subLevel),
                subLevel.plot().chunkPositions()
        );
    }

    private static PhysicsVector bodyToPlotOrigin(PhysicsSubLevel subLevel) {
        return subLevel.blocks().stream()
                .findFirst()
                .map(block -> new PhysicsVector(
                        block.visualLocalOrigin().x() - block.localPos().getX(),
                        block.visualLocalOrigin().y() - block.localPos().getY(),
                        block.visualLocalOrigin().z() - block.localPos().getZ()
                ))
                .orElse(PhysicsVector.ZERO);
    }
}
